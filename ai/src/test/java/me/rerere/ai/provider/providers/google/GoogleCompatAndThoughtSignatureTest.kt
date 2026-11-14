package me.rerere.ai.provider.providers.google

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import me.rerere.ai.core.MessageRole
import me.rerere.ai.core.Tool
import me.rerere.ai.provider.BuiltInTools
import me.rerere.ai.provider.ClaudePromptCacheTtl
import me.rerere.ai.provider.GoogleBuiltInToolCompatMode
import me.rerere.ai.provider.GoogleCrossChannelThoughtPolicy
import me.rerere.ai.provider.Model
import me.rerere.ai.provider.ModelAbility
import me.rerere.ai.provider.ProviderSetting
import me.rerere.ai.provider.TextGenerationParams
import me.rerere.ai.provider.providers.claude.ClaudeProvider
import me.rerere.ai.provider.stream.SseEvent
import me.rerere.ai.ui.GoogleThoughtMetadata
import me.rerere.ai.ui.ServerToolMetadata
import me.rerere.ai.ui.ServerToolProtocol
import me.rerere.ai.ui.ServerToolStatus
import me.rerere.ai.ui.StreamChunkHandler
import me.rerere.ai.ui.UIMessage
import me.rerere.ai.ui.UIMessagePart
import me.rerere.ai.ui.metadataAs
import me.rerere.ai.ui.toMetadata
import me.rerere.ai.util.json
import okhttp3.OkHttpClient
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.uuid.Uuid

class GoogleCompatAndThoughtSignatureTest {
    private val provider = GoogleProvider(OkHttpClient())

    @Test
    fun `stream decoder handles standalone thoughtSignature and preserves its original shape`() {
        val vertexSetting = ProviderSetting.Google(
            id = Uuid.random(),
            vertexAI = true,
            useServiceAccount = true,
            projectId = "proj-1",
            location = "global",
        )
        val origin = vertexSetting.thoughtSignatureOrigin()
        val decoder = GoogleStreamDecoder("resp-1", "gemini-4-pro", signatureOrigin = origin)
        val handler = StreamChunkHandler(toolModel())
        var messages = listOf(UIMessage.user("hi"))

        val textEvent = SseEvent(
            data = json.encodeToString(buildJsonObject {
                putJsonArray("candidates") {
                    add(buildJsonObject {
                        put("content", buildJsonObject {
                            put("role", "model")
                            put("parts", buildJsonArray {
                                add(buildJsonObject { put("text", "Hello world") })
                            })
                        })
                    })
                }
            })
        )
        val signatureOnlyEvent = SseEvent(
            data = json.encodeToString(buildJsonObject {
                putJsonArray("candidates") {
                    add(buildJsonObject {
                        put("content", buildJsonObject {
                            put("role", "model")
                            put("parts", buildJsonArray {
                                add(buildJsonObject { put("thoughtSignature", "CAISpAM_SIG_TAIL") })
                            })
                        })
                        put("finishReason", "STOP")
                    })
                }
            })
        )

        decoder.accept(textEvent).chunks.forEach { messages = handler.handle(messages, it) }
        decoder.accept(signatureOnlyEvent).chunks.forEach { messages = handler.handle(messages, it) }
        decoder.onClosed().forEach { messages = handler.handle(messages, it) }

        val assistant = messages.last()
        assertEquals("Hello world", assistant.toText())
        val textParts = assistant.parts.filterIsInstance<UIMessagePart.Text>()
        assertEquals(2, textParts.size)
        assertEquals("Hello world", textParts[0].text)
        assertNull(textParts[0].metadataAs<GoogleThoughtMetadata>()?.thoughtSignature)
        assertEquals("", textParts[1].text)
        assertEquals("CAISpAM_SIG_TAIL", textParts[1].metadataAs<GoogleThoughtMetadata>()?.thoughtSignature)
        assertEquals(origin, textParts[1].metadataAs<GoogleThoughtMetadata>()?.signatureOrigin)

        // Do not invent a text payload for an originally signature-only part.
        val body = invokeBuildRequestBody(messages, TextGenerationParams(model = toolModel()), vertexSetting)
        val modelParts = body["contents"]!!.jsonArray[1].jsonObject["parts"]!!.jsonArray
        assertEquals(2, modelParts.size)
        assertEquals("Hello world", modelParts[0].jsonObject["text"]!!.jsonPrimitive.content)
        assertFalse(modelParts[1].jsonObject.containsKey("text"))
        assertEquals("CAISpAM_SIG_TAIL", modelParts[1].jsonObject["thoughtSignature"]!!.jsonPrimitive.content)
    }

    @Test
    fun `non-streaming parseMessageParts handles standalone thoughtSignature part`() {
        val method = GoogleProvider::class.java.getDeclaredMethod(
            "parseMessageParts",
            JsonArray::class.java,
            String::class.java,
        )
        method.isAccessible = true
        val rawParts = buildJsonArray {
            add(buildJsonObject { put("thought", true); put("thoughtSignature", "THOUGHT_ONLY_SIG") })
            add(buildJsonObject { put("text", "Answer") })
            add(buildJsonObject { put("thoughtSignature", "TEXT_TAIL_SIG") })
        }

        @Suppress("UNCHECKED_CAST")
        val parts = method.invoke(provider, rawParts, "vertex:proj:us-central1") as List<UIMessagePart>
        assertEquals(3, parts.size)
        val reasoning = parts[0] as UIMessagePart.Reasoning
        assertEquals("", reasoning.reasoning)
        assertEquals("THOUGHT_ONLY_SIG", reasoning.metadataAs<GoogleThoughtMetadata>()?.thoughtSignature)
        val tail = parts[2] as UIMessagePart.Text
        assertEquals("", tail.text)
        assertEquals("TEXT_TAIL_SIG", tail.metadataAs<GoogleThoughtMetadata>()?.thoughtSignature)
    }

    @Test
    fun `switching from proxy to vertex skips foreign thought and uses skip validator on first tool`() {
        val proxySetting = ProviderSetting.Google(
            id = Uuid.random(),
            baseUrl = "https://proxy.example.com/v1beta",
            vertexAI = false,
        )
        val vertexSetting = ProviderSetting.Google(
            id = Uuid.random(),
            vertexAI = true,
            useServiceAccount = true,
            projectId = "my-gcp-project",
            location = "global",
            crossChannelThoughtPolicy = GoogleCrossChannelThoughtPolicy.SKIP_FOREIGN_THOUGHT_AND_BYPASS_TOOLS,
        )
        val proxyOrigin = proxySetting.thoughtSignatureOrigin()

        val assistantFromProxy = UIMessage(
            role = MessageRole.ASSISTANT,
            parts = listOf(
                UIMessagePart.Reasoning(
                    reasoning = "Proxy internal reasoning",
                    metadata = GoogleThoughtMetadata(
                        thoughtSignature = "proxy-thought-sig",
                        signatureOrigin = proxyOrigin,
                    ).toMetadata(),
                ),
                UIMessagePart.ServerTool(
                    toolCallId = "srv-1",
                    toolName = "GOOGLE_SEARCH_WEB",
                    status = ServerToolStatus.COMPLETED,
                    metadata = ServerToolMetadata(
                        protocol = ServerToolProtocol.GOOGLE_GENERATE_CONTENT,
                        call = buildJsonObject {
                            put("thoughtSignature", "proxy-srv-sig")
                            put("toolCall", buildJsonObject { put("id", "srv-1") })
                        },
                        result = buildJsonObject {
                            put("thoughtSignature", "proxy-srv-res-sig")
                            put("toolResponse", buildJsonObject { put("id", "srv-1") })
                        },
                        signatureOrigin = proxyOrigin,
                    ).toMetadata(),
                ),
                UIMessagePart.Tool(
                    toolCallId = "call-1",
                    toolName = "read_file",
                    input = """{"path":"a.txt"}""",
                    output = listOf(UIMessagePart.Text("content")),
                    metadata = GoogleThoughtMetadata(
                        thoughtSignature = "proxy-tool-sig-1",
                        signatureOrigin = proxyOrigin,
                    ).toMetadata(),
                ),
                UIMessagePart.Tool(
                    toolCallId = "call-2",
                    toolName = "read_file",
                    input = """{"path":"b.txt"}""",
                    output = listOf(UIMessagePart.Text("content2")),
                    metadata = GoogleThoughtMetadata(
                        thoughtSignature = "proxy-tool-sig-2",
                        signatureOrigin = proxyOrigin,
                    ).toMetadata(),
                ),
                UIMessagePart.Text(
                    text = "Done",
                    metadata = GoogleThoughtMetadata(
                        thoughtSignature = "proxy-text-sig",
                        signatureOrigin = proxyOrigin,
                    ).toMetadata(),
                ),
                UIMessagePart.Text(
                    text = "",
                    metadata = GoogleThoughtMetadata(
                        thoughtSignature = "proxy-tail-sig",
                        signatureOrigin = proxyOrigin,
                    ).toMetadata(),
                ),
            ),
        )

        val body = invokeBuildRequestBody(
            messages = listOf(UIMessage.user("run"), assistantFromProxy),
            params = TextGenerationParams(model = toolModel()),
            providerSetting = vertexSetting,
        )
        val contents = body["contents"]!!.jsonArray
        // [0] user, [1] model(serverTool + 2 functionCalls), [2] user(2 functionResponses), [3] model(text)
        val firstModelParts = contents[1].jsonObject["parts"]!!.jsonArray
        // Foreign reasoning must be skipped; server tool signatures stripped; 1st functionCall uses bypass sentinel
        assertFalse(firstModelParts.any { it.jsonObject["thought"]?.jsonPrimitive?.content == "true" })
        assertFalse(firstModelParts[0].jsonObject.containsKey("thoughtSignature"))
        assertFalse(firstModelParts[1].jsonObject.containsKey("thoughtSignature"))
        assertEquals(
            "skip_thought_signature_validator",
            firstModelParts[2].jsonObject["thoughtSignature"]!!.jsonPrimitive.content,
        )
        assertFalse(firstModelParts[3].jsonObject.containsKey("thoughtSignature"))

        val finalModelParts = contents[3].jsonObject["parts"]!!.jsonArray
        assertEquals(1, finalModelParts.size)
        assertEquals("Done", finalModelParts[0].jsonObject["text"]!!.jsonPrimitive.content)
        assertFalse(finalModelParts[0].jsonObject.containsKey("thoughtSignature"))
    }

    @Test
    fun `keep text policy converts foreign reasoning to plain text without thought flag`() {
        val vertexSetting = ProviderSetting.Google(
            id = Uuid.random(),
            vertexAI = true,
            crossChannelThoughtPolicy = GoogleCrossChannelThoughtPolicy.STRIP_SIGNATURE_KEEP_THOUGHT_TEXT,
        )
        val assistantFromOther = UIMessage(
            role = MessageRole.ASSISTANT,
            parts = listOf(
                UIMessagePart.Reasoning(
                    reasoning = "Step by step",
                    metadata = GoogleThoughtMetadata(
                        thoughtSignature = "foreign-sig",
                        signatureOrigin = "google:other:example.com",
                    ).toMetadata(),
                ),
                UIMessagePart.Text("Final answer"),
            ),
        )

        val body = invokeBuildRequestBody(
            messages = listOf(UIMessage.user("q"), assistantFromOther),
            params = TextGenerationParams(model = toolModel()),
            providerSetting = vertexSetting,
        )
        val modelParts = body["contents"]!!.jsonArray[1].jsonObject["parts"]!!.jsonArray
        assertEquals(2, modelParts.size)
        assertFalse(modelParts[0].jsonObject.containsKey("thought"))
        assertFalse(modelParts[0].jsonObject.containsKey("thoughtSignature"))
        assertTrue(modelParts[0].jsonObject["text"]!!.jsonPrimitive.content.contains("Step by step"))
    }

    @Test
    fun `passthrough policy preserves foreign thought signatures verbatim`() {
        val vertexSetting = ProviderSetting.Google(
            id = Uuid.random(),
            vertexAI = true,
            crossChannelThoughtPolicy = GoogleCrossChannelThoughtPolicy.PASSTHROUGH,
        )
        val assistantFromOther = UIMessage(
            role = MessageRole.ASSISTANT,
            parts = listOf(
                UIMessagePart.Reasoning(
                    reasoning = "Step",
                    metadata = GoogleThoughtMetadata(
                        thoughtSignature = "foreign-sig",
                        signatureOrigin = "google:other:example.com",
                    ).toMetadata(),
                ),
            ),
        )
        val body = invokeBuildRequestBody(
            messages = listOf(UIMessage.user("q"), assistantFromOther),
            params = TextGenerationParams(model = toolModel()),
            providerSetting = vertexSetting,
        )
        val part = body["contents"]!!.jsonArray[1].jsonObject["parts"]!!.jsonArray.single().jsonObject
        assertEquals(true, part["thought"]!!.jsonPrimitive.boolean)
        assertEquals("foreign-sig", part["thoughtSignature"]!!.jsonPrimitive.content)
    }

    @Test
    fun `builtInToolCompatMode controls toolConfig format and built-in tool suppression`() {
        val params = TextGenerationParams(
            model = toolModel(tools = setOf(BuiltInTools.Search)),
            tools = listOf(testTool()),
        )
        val messages = listOf(UIMessage.user("search and save"))

        val snakeCaseBody = invokeBuildRequestBody(
            messages = messages,
            params = params,
            providerSetting = ProviderSetting.Google(
                builtInToolCompatMode = GoogleBuiltInToolCompatMode.SNAKE_CASE_TOOL_CONFIG,
            ),
        )
        assertFalse(snakeCaseBody.containsKey("toolConfig"))
        assertTrue(
            snakeCaseBody["tool_config"]!!.jsonObject["include_server_side_tool_invocations"]!!
                .jsonPrimitive.boolean
        )

        val preferToolsBody = invokeBuildRequestBody(
            messages = messages,
            params = params,
            providerSetting = ProviderSetting.Google(
                builtInToolCompatMode = GoogleBuiltInToolCompatMode.PREFER_FUNCTION_TOOLS,
            ),
        )
        assertFalse(preferToolsBody.containsKey("toolConfig"))
        assertFalse(preferToolsBody.containsKey("tool_config"))
        val tools = preferToolsBody["tools"]!!.jsonArray
        assertTrue(tools.any { it.jsonObject.containsKey("functionDeclarations") })
        assertFalse(tools.any { it.jsonObject.containsKey("googleSearch") })
    }

    @Test
    fun `claude provider ignores empty google signature carrier parts`() {
        val claudeProvider = ClaudeProvider(OkHttpClient())
        val method = ClaudeProvider::class.java.getDeclaredMethod(
            "buildMessages",
            List::class.java,
            Boolean::class.javaPrimitiveType,
            ClaudePromptCacheTtl::class.java,
        )
        method.isAccessible = true

        val assistant = UIMessage(
            role = MessageRole.ASSISTANT,
            parts = listOf(
                UIMessagePart.Reasoning(
                    reasoning = "",
                    metadata = GoogleThoughtMetadata(thoughtSignature = "google-sig-1").toMetadata(),
                ),
                UIMessagePart.Text("Actual text"),
                UIMessagePart.Text(
                    text = "",
                    metadata = GoogleThoughtMetadata(thoughtSignature = "google-sig-2").toMetadata(),
                ),
            ),
        )
        val result = method.invoke(
            claudeProvider,
            listOf(UIMessage.user("hi"), assistant),
            false,
            ClaudePromptCacheTtl.FIVE_MINUTES,
        ) as JsonArray
        val contentBlocks = result[1].jsonObject["content"]!!.jsonArray
        assertEquals(1, contentBlocks.size)
        assertEquals("Actual text", contentBlocks[0].jsonObject["text"]!!.jsonPrimitive.content)
    }

    private fun invokeBuildRequestBody(
        messages: List<UIMessage>,
        params: TextGenerationParams,
        providerSetting: ProviderSetting.Google,
    ): JsonObject {
        val method = GoogleProvider::class.java.getDeclaredMethod(
            "buildCompletionRequestBody",
            List::class.java,
            TextGenerationParams::class.java,
            ProviderSetting.Google::class.java,
        )
        method.isAccessible = true
        return method.invoke(provider, messages, params, providerSetting) as JsonObject
    }

    private fun toolModel(tools: Set<BuiltInTools> = emptySet()) = Model(
        modelId = "gemini-4-pro",
        abilities = listOf(ModelAbility.TOOL, ModelAbility.REASONING),
        tools = tools,
    )

    private fun testTool() = Tool(
        name = "save_weather",
        description = "Save weather",
        execute = { emptyList() },
    )
}
