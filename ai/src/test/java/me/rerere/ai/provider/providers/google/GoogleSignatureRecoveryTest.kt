package me.rerere.ai.provider.providers.google

import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import me.rerere.ai.core.MessageRole
import me.rerere.ai.core.Tool
import me.rerere.ai.provider.BuiltInTools
import me.rerere.ai.provider.GoogleBuiltInToolCompatMode
import me.rerere.ai.provider.GoogleCrossChannelThoughtPolicy
import me.rerere.ai.provider.Model
import me.rerere.ai.provider.ModelAbility
import me.rerere.ai.provider.ProviderSetting
import me.rerere.ai.provider.TextGenerationParams
import me.rerere.ai.provider.TextGenerationResult
import me.rerere.ai.provider.stream.SseEvent
import me.rerere.ai.ui.ClaudeReasoningMetadata
import me.rerere.ai.ui.GoogleInteractionsMetadata
import me.rerere.ai.ui.GoogleThoughtMetadata
import me.rerere.ai.ui.ServerToolMetadata
import me.rerere.ai.ui.ServerToolProtocol
import me.rerere.ai.ui.ServerToolStatus
import me.rerere.ai.ui.StreamChunkHandler
import me.rerere.ai.ui.UIMessage
import me.rerere.ai.ui.UIMessagePart
import me.rerere.ai.ui.finishReasoning
import me.rerere.ai.ui.handleTextGenerationResult
import me.rerere.ai.ui.hasGoogleThoughtSignatures
import me.rerere.ai.ui.metadataAs
import me.rerere.ai.ui.toMetadata
import me.rerere.ai.util.json
import okhttp3.OkHttpClient
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

/** Synthetic values only: never commit service-account credentials or captured signatures. */
class GoogleSignatureRecoveryTest {
    private val provider = GoogleProvider(OkHttpClient())
    private val vertex = ProviderSetting.Google(
        vertexAI = true, useServiceAccount = true, projectId = "test-project", location = "global",
    )
    private val model = Model(
        modelId = "gemini-4-hydrogen", abilities = listOf(ModelAbility.REASONING, ModelAbility.TOOL),
    )
    private val origin = vertex.thoughtSignatureOrigin()

    private fun metadata(signature: String? = null, signatureOnly: Boolean? = null) =
        GoogleThoughtMetadata(signature, origin, signatureOnly = signatureOnly).toMetadata()

    private fun build(
        messages: List<UIMessage>,
        setting: ProviderSetting.Google = vertex,
        params: TextGenerationParams = TextGenerationParams(model = model),
    ): JsonObject {
        val method = GoogleProvider::class.java.getDeclaredMethod(
            "buildCompletionRequestBody", List::class.java, TextGenerationParams::class.java,
            ProviderSetting.Google::class.java,
        )
        method.isAccessible = true
        return method.invoke(provider, messages, params, setting) as JsonObject
    }

    private fun modelParts(body: JsonObject): List<JsonObject> = body["contents"]!!.jsonArray
        .map { it.jsonObject }.filter { it["role"]?.jsonPrimitive?.content == "model" }
        .flatMap { it["parts"]!!.jsonArray }.map { it.jsonObject }

    private fun decode(vararg batches: String): UIMessage {
        val decoder = GoogleStreamDecoder("test-response", model.modelId, origin)
        val handler = StreamChunkHandler(model)
        var messages = listOf(UIMessage.user("question"))
        batches.forEach { parts ->
            val event = SseEvent(data = """{"candidates":[{"content":{"role":"model","parts":$parts}}]}""")
            decoder.accept(event).chunks.forEach { messages = handler.handle(messages, it) }
        }
        decoder.onClosed().forEach { messages = handler.handle(messages, it) }
        return messages.last()
    }

    @Test
    fun `Vertex standard mode keeps both tools without requesting server invocation signatures`() {
        val params = TextGenerationParams(
            model = model.copy(tools = setOf(BuiltInTools.Search)),
            tools = listOf(Tool(name = "noop", description = "test", execute = { error("Must not execute") })),
        )
        val body = build(listOf(UIMessage.user("question")), params = params)
        assertFalse(body.containsKey("toolConfig"))
        assertFalse(body.containsKey("tool_config"))
        val tools = body["tools"]!!.jsonArray
        assertTrue(tools.any { it.jsonObject.containsKey("googleSearch") })
        assertTrue(tools.any { it.jsonObject.containsKey("functionDeclarations") })
        val developer = build(listOf(UIMessage.user("question")), vertex.copy(vertexAI = false), params)
        assertTrue(developer["toolConfig"]!!.jsonObject["includeServerSideToolInvocations"]!!.jsonPrimitive.boolean)
        val snake = build(
            listOf(UIMessage.user("question")),
            vertex.copy(vertexAI = false, builtInToolCompatMode = GoogleBuiltInToolCompatMode.SNAKE_CASE_TOOL_CONFIG), params,
        )
        assertFalse(snake.containsKey("toolConfig"))
        assertTrue(snake["tool_config"]!!.jsonObject["include_server_side_tool_invocations"]!!.jsonPrimitive.boolean)
        val prefer = build(
            listOf(UIMessage.user("question")),
            vertex.copy(builtInToolCompatMode = GoogleBuiltInToolCompatMode.PREFER_FUNCTION_TOOLS), params,
        )
        assertFalse(prefer["tools"]!!.jsonArray.any { it.jsonObject.containsKey("googleSearch") })
    }

    @Test
    fun `unsigned thought chunks retain response provenance and same-channel thought flag`() {
        val reply = decode(
            """[{"text":"summary A","thought":true}]""",
            """[{"text":" and B","thought":true}]""",
            """[{"text":"answer"},{"text":"","thoughtSignature":"FINAL"}]""",
        )
        val thought = reply.parts.filterIsInstance<UIMessagePart.Reasoning>().single()
        assertEquals(origin, thought.metadataAs<GoogleThoughtMetadata>()?.signatureOrigin)
        assertNull(thought.metadataAs<GoogleThoughtMetadata>()?.thoughtSignature)
        val parts = modelParts(build(listOf(UIMessage.user("q"), reply)))
        assertEquals("summary A and B", parts[0]["text"]!!.jsonPrimitive.content)
        assertTrue(parts[0]["thought"]!!.jsonPrimitive.boolean)
    }

    @Test
    fun `bare signature and explicit empty text signature retain different wire shapes`() {
        val reply = decode("""[{"text":"answer"},{"thoughtSignature":"OPAQUE"},{"text":"","thoughtSignature":"FINAL"}]""")
        val saved = json.decodeFromString<UIMessage>(json.encodeToString(reply))
        val parts = modelParts(build(listOf(UIMessage.user("q"), saved)))
        assertEquals(3, parts.size)
        assertEquals(setOf("thoughtSignature"), parts[1].keys)
        assertEquals(setOf("text", "thoughtSignature"), parts[2].keys)
        assertEquals("", parts[2]["text"]!!.jsonPrimitive.content)
        assertEquals(true, saved.parts[1].metadataAs<GoogleThoughtMetadata>()?.signatureOnly)
        assertEquals(false, saved.parts[2].metadataAs<GoogleThoughtMetadata>()?.signatureOnly)
    }

    @Test
    fun `legacy signature shape stays backward compatible`() {
        val reply = UIMessage.assistant("answer").copy(parts = listOf(
            UIMessagePart.Text("answer"), UIMessagePart.Text("", metadata("LEGACY")),
        ))
        val part = modelParts(build(listOf(UIMessage.user("q"), reply))).last()
        assertEquals("", part["text"]!!.jsonPrimitive.content)
        assertEquals("LEGACY", part["thoughtSignature"]!!.jsonPrimitive.content)
    }

    @Test
    fun `different signed text parts never coalesce or overwrite signatures`() {
        val reply = decode(
            """[{"text":"unsigned"}]""",
            """[{"text":"A","thoughtSignature":"SIG_A"},{"text":"B","thoughtSignature":"SIG_B"}]""",
            """[{"text":"tail"}]""",
        )
        val texts = reply.parts.filterIsInstance<UIMessagePart.Text>()
        assertEquals(listOf("unsigned", "A", "B", "tail"), texts.map { it.text })
        assertEquals(listOf(null, "SIG_A", "SIG_B", null), texts.map { it.metadataAs<GoogleThoughtMetadata>()?.thoughtSignature })
    }

    @Test
    fun `different signed thought parts never coalesce`() {
        val reply = decode("""[{"text":"A","thought":true,"thoughtSignature":"SIG_A"},{"text":"B","thought":true,"thoughtSignature":"SIG_B"}]""")
        val thoughts = reply.parts.filterIsInstance<UIMessagePart.Reasoning>()
        assertEquals(listOf("A", "B"), thoughts.map { it.reasoning })
        assertEquals(listOf("SIG_A", "SIG_B"), thoughts.map { it.metadataAs<GoogleThoughtMetadata>()?.thoughtSignature })
    }

    @Test
    fun `non-stream result does not merge unsigned text into signed text`() {
        val incoming = UIMessage(role = MessageRole.ASSISTANT, parts = listOf(UIMessagePart.Text("signed", metadata("SIG"))))
        val result = listOf(UIMessage.assistant("unsigned")).handleTextGenerationResult(
            TextGenerationResult("result", model.modelId, incoming), model,
        ).last()
        assertEquals(listOf("unsigned", "signed"), result.parts.filterIsInstance<UIMessagePart.Text>().map { it.text })
    }

    @Test
    fun `unknown signed payload must not be silently downgraded to a pure signature`() {
        assertThrows(IllegalStateException::class.java) {
            decode("""[{"futurePayload":{"value":"keep me"},"thoughtSignature":"SIG"}]""")
        }
    }

    private fun affectedReply(): UIMessage = UIMessage(role = MessageRole.ASSISTANT, parts = listOf(
        UIMessagePart.Reasoning("summary A"),
        UIMessagePart.Text("", metadata("BAD_A")),
        UIMessagePart.Text("", metadata("BAD_B")),
        UIMessagePart.Reasoning("summary B"),
        UIMessagePart.Text("answer"),
        UIMessagePart.Text("", metadata("FINAL")),
    )).finishReasoning()

    @Test
    fun `single-reply recovery overrides passthrough and is reversible without deleting parts`() {
        val original = affectedReply()
        val setting = vertex.copy(crossChannelThoughtPolicy = GoogleCrossChannelThoughtPolicy.PASSTHROUGH)
        val ignored = original.copy(ignoreGoogleThoughtSignatures = true)
        val saved = json.decodeFromString<UIMessage>(json.encodeToString(ignored))
        assertTrue(saved.ignoreGoogleThoughtSignatures)
        assertEquals(original.parts, saved.parts)
        val parts = modelParts(build(listOf(UIMessage.user("q"), saved), setting))
        assertEquals(3, parts.size)
        assertTrue(parts.all { it.keys == setOf("text") })
        assertTrue(parts[0]["text"]!!.jsonPrimitive.content.contains("summary A"))
        assertEquals("answer", parts.last()["text"]!!.jsonPrimitive.content)
        assertEquals(
            build(listOf(UIMessage.user("q"), original), setting),
            build(listOf(UIMessage.user("q"), saved.copy(ignoreGoogleThoughtSignatures = false)), setting),
        )
        assertFalse(json.decodeFromString<UIMessage>("""{"role":"assistant","parts":[{"type":"text","text":"old"}]}""").ignoreGoogleThoughtSignatures)
    }

    @Test
    fun `recovery does not strip another reply signatures`() {
        val first = affectedReply().copy(ignoreGoogleThoughtSignatures = true)
        val second = UIMessage(role = MessageRole.ASSISTANT, parts = listOf(UIMessagePart.Text("good", metadata("OTHER"))))
        val parts = modelParts(build(listOf(UIMessage.user("q"), first, UIMessage.user("next"), second)))
        assertEquals(listOf("OTHER"), parts.mapNotNull { it["thoughtSignature"]?.jsonPrimitive?.content })
    }

    @Test
    fun `recovery preserves function call result pairs and uses Google bypass only for the batch leader`() {
        val tools = listOf("one", "two").map { name -> UIMessagePart.Tool(
            toolCallId = "id-$name", toolName = name, input = "{}", output = listOf(UIMessagePart.Text("done")), metadata = metadata("SIG_$name"),
        ) }
        val reply = UIMessage(role = MessageRole.ASSISTANT, parts = tools, ignoreGoogleThoughtSignatures = true)
        val body = build(listOf(UIMessage.user("q"), reply))
        val calls = modelParts(body)
        assertEquals("skip_thought_signature_validator", calls[0]["thoughtSignature"]!!.jsonPrimitive.content)
        assertFalse(calls[1].containsKey("thoughtSignature"))
        val responses = body["contents"]!!.jsonArray.flatMap { it.jsonObject["parts"]!!.jsonArray }
            .mapNotNull { it.jsonObject["functionResponse"]?.jsonObject }
        assertEquals(listOf("id-one", "id-two"), responses.map { it["id"]!!.jsonPrimitive.content })
        assertEquals(listOf("SIG_one", "SIG_two"), tools.map { it.metadataAs<GoogleThoughtMetadata>()?.thoughtSignature })
    }

    @Test
    fun `recovery renders signed server tool results as text without replaying incomplete native blocks`() {
        val tool = UIMessagePart.ServerTool(
            toolCallId = "search", toolName = "google_search", status = ServerToolStatus.COMPLETED,
            output = JsonPrimitive("search result"),
            metadata = ServerToolMetadata(
                protocol = ServerToolProtocol.GOOGLE_GENERATE_CONTENT,
                call = buildJsonObject { put("thoughtSignature", "SERVER_SIG"); put("toolCall", buildJsonObject {}) },
                signatureOrigin = origin,
            ).toMetadata(),
        )
        val reply = UIMessage(role = MessageRole.ASSISTANT, parts = listOf(tool), ignoreGoogleThoughtSignatures = true)
        assertTrue(reply.hasGoogleThoughtSignatures())
        val body = build(listOf(UIMessage.user("q"), reply))
        assertFalse(body.toString().contains("SERVER_SIG"))
        assertFalse(body.toString().contains("toolCall"))
        assertTrue(body.toString().contains("search result"))
        assertTrue(tool.metadataAs<ServerToolMetadata>()!!.call!!.containsKey("thoughtSignature"))
    }

    @Test
    fun `Interactions recovery keeps readable thoughts and strips signatures without a Google sentinel`() {
        val reply = UIMessage(role = MessageRole.ASSISTANT, parts = listOf(
            UIMessagePart.Reasoning("summary", metadata = GoogleInteractionsMetadata("INT_SIG").toMetadata()),
            UIMessagePart.Tool("call", "noop", "{}", output = listOf(UIMessagePart.Text("done")), metadata = GoogleInteractionsMetadata("TOOL_SIG").toMetadata()),
            UIMessagePart.Text("answer"),
        ), ignoreGoogleThoughtSignatures = true)
        val body = InteractionsAPI(OkHttpClient()).buildRequestBody(
            listOf(UIMessage.user("q"), reply), TextGenerationParams(model = model), stream = true,
        )
        assertTrue(body.toString().contains("summary"))
        assertTrue(body.toString().contains("function_result"))
        assertFalse(body.toString().contains("INT_SIG"))
        assertFalse(body.toString().contains("TOOL_SIG"))
        assertFalse(body.toString().contains("skip_thought_signature_validator"))
    }

    @Test
    fun `recovery eligibility is Google-specific and assistant-only`() {
        assertFalse(UIMessage.assistant("plain").hasGoogleThoughtSignatures())
        val part = UIMessagePart.Reasoning("thinking", metadata = ClaudeReasoningMetadata("CLAUDE_SIG").toMetadata())
        assertFalse(UIMessage(role = MessageRole.ASSISTANT, parts = listOf(part)).hasGoogleThoughtSignatures())
        assertTrue(affectedReply().hasGoogleThoughtSignatures())
        assertFalse(affectedReply().copy(role = MessageRole.USER).hasGoogleThoughtSignatures())
    }
}
