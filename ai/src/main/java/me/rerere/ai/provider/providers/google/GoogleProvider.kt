package me.rerere.ai.provider.providers.google

import android.content.Context
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.channels.onFailure
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.buffer
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonArrayBuilder
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.encodeToJsonElement
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import me.rerere.ai.core.MessageRole
import me.rerere.ai.core.ReasoningLevel
import me.rerere.ai.core.TokenUsage
import me.rerere.ai.provider.BuiltInTools
import me.rerere.ai.provider.GoogleBuiltInToolCompatMode
import me.rerere.ai.provider.GoogleCrossChannelThoughtPolicy
import me.rerere.ai.provider.Modality
import me.rerere.ai.provider.Model
import me.rerere.ai.provider.ModelAbility
import me.rerere.ai.provider.ModelType
import me.rerere.ai.provider.Provider
import me.rerere.ai.provider.ProviderQuota
import me.rerere.ai.provider.ProviderSetting
import me.rerere.ai.provider.TextGenerationParams
import me.rerere.ai.provider.TextGenerationResult
import me.rerere.ai.provider.providers.PartGroup
import me.rerere.ai.provider.providers.google.vertex.ServiceAccountTokenProvider
import me.rerere.ai.provider.providers.groupPartsByToolBoundary
import me.rerere.ai.provider.runQuotaQuery
import me.rerere.ai.provider.stream.SseEvent
import me.rerere.ai.registry.ModelRegistry
import me.rerere.ai.ui.GoogleFileDataMetadata
import me.rerere.ai.ui.GoogleThoughtMetadata
import me.rerere.ai.ui.ServerToolMetadata
import me.rerere.ai.ui.ServerToolProtocol
import me.rerere.ai.ui.ServerToolStatus
import me.rerere.ai.ui.StreamChunk
import me.rerere.ai.ui.UIMessage
import me.rerere.ai.ui.UIMessageAnnotation
import me.rerere.ai.ui.UIMessagePart
import me.rerere.ai.ui.metadataAs
import me.rerere.ai.ui.toMetadata
import me.rerere.ai.util.KeyRoulette
import me.rerere.ai.util.configureReferHeaders
import me.rerere.ai.util.configureSessionHeaders
import me.rerere.ai.util.encodeBase64
import me.rerere.ai.util.json
import me.rerere.ai.util.mergeCustomBody
import me.rerere.ai.util.mergeCustomHeaders
import me.rerere.ai.util.removeElements
import me.rerere.ai.util.stringSafe
import me.rerere.common.http.await
import me.rerere.common.http.jsonArrayOrNull
import me.rerere.common.http.jsonObjectOrNull
import me.rerere.common.http.jsonPrimitiveOrNull
import me.rerere.common.http.sse.newEventSource
import me.rerere.common.http.stringOrNull
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import okhttp3.sse.EventSource
import okhttp3.sse.EventSourceListener
import org.apache.commons.text.StringEscapeUtils
import kotlin.time.Clock
import kotlin.uuid.Uuid

private const val TAG = "GoogleProvider"
private const val SKIP_THOUGHT_SIGNATURE_VALIDATOR = "skip_thought_signature_validator"

internal fun ProviderSetting.Google.thoughtSignatureOrigin(): String = when {
    vertexAI && useServiceAccount ->
        "vertex:${projectId.trim()}:${location.trim()}"
    vertexAI ->
        "vertex:${id}"
    else -> {
        val host = runCatching { baseUrl.trim().toHttpUrl().host }.getOrDefault(baseUrl.trim())
        "google:${id}:$host"
    }
}

class GoogleProvider(private val client: OkHttpClient, context: Context? = null) : Provider<ProviderSetting.Google> {
    private val keyRoulette = if (context != null) KeyRoulette.lru(context) else KeyRoulette.default()
    private val serviceAccountTokenProvider by lazy {
        ServiceAccountTokenProvider(client)
    }
    private val fileManager = GoogleFileManager(client = client, keyRoulette = keyRoulette)
    private val interactionsAPI = InteractionsAPI(
        client = client,
        keyRoulette = keyRoulette,
        fileManager = fileManager,
    )

    override suspend fun getQuota(providerSetting: ProviderSetting.Google): ProviderQuota? =
        client.runQuotaQuery(providerSetting) {
            keyRoulette.next(providerSetting.apiKey, providerSetting.id.toString())
        }

    // Interactions API 目前只有 Gemini Developer API 提供，Vertex AI 仍走 generateContent
    private fun ProviderSetting.Google.usesInteractionsApi() = useInteractionsApi && !vertexAI

    private fun buildUrl(providerSetting: ProviderSetting.Google, path: String): HttpUrl {
        return if (!providerSetting.vertexAI) {
            "${providerSetting.baseUrl}/$path".toHttpUrl()
        } else if (providerSetting.useServiceAccount) {
            "https://aiplatform.googleapis.com/v1/projects/${providerSetting.projectId}/locations/${providerSetting.location}/$path".toHttpUrl()
        } else {
            "https://aiplatform.googleapis.com/v1/$path".toHttpUrl()
        }
    }

    private suspend fun transformRequest(
        providerSetting: ProviderSetting.Google,
        request: Request
    ): Request {
        return if (providerSetting.vertexAI && providerSetting.useServiceAccount) {
            val accessToken = serviceAccountTokenProvider.fetchAccessToken(
                serviceAccountEmail = providerSetting.serviceAccountEmail.trim(),
                privateKeyPem = StringEscapeUtils.unescapeJson(providerSetting.privateKey.trim()),
            )
            request.newBuilder()
                .addHeader("Authorization", "Bearer $accessToken")
                .build()
        } else {
            val key = keyRoulette.next(providerSetting.apiKey, providerSetting.id.toString())
            if (providerSetting.vertexAI) {
                request.newBuilder()
                    .url(request.url.newBuilder().addQueryParameter("key", key).build())
                    .build()
            } else {
                request.newBuilder()
                    .addHeader("x-goog-api-key", key)
                    .build()
            }
        }
    }

    override suspend fun listModels(providerSetting: ProviderSetting.Google): List<Model> =
        withContext(Dispatchers.IO) {
            val url = buildUrl(providerSetting = providerSetting, path = "models?pageSize=100")
            val request = transformRequest(
                providerSetting = providerSetting,
                request = Request.Builder()
                    .url(url)
                    .headers(providerSetting.mergeCustomHeaders())
                    .get()
                    .build()
            )
            val response = client.newCall(request).await()
            if (response.isSuccessful) {
                val body = response.body?.string() ?: error("empty body")
                Log.d(TAG, "listModels: $body")
                val bodyObject = json.parseToJsonElement(body).jsonObjectOrNull ?: return@withContext emptyList()
                val models = bodyObject["models"]?.jsonArrayOrNull ?: return@withContext emptyList()

                models.mapNotNull {
                    val modelObject = it.jsonObjectOrNull ?: return@mapNotNull null
                    val rawName = modelObject.stringOrNull("name")?.takeIf { name -> name.isNotBlank() }
                        ?: return@mapNotNull null
                    val modelId = rawName.substringAfter("/").ifBlank { return@mapNotNull null }

                    // 忽略非chat/embedding模型（若反代未返回 supportedGenerationMethods，默认按 chat 模型处理）
                    val supportedGenerationMethods = modelObject["supportedGenerationMethods"]
                        ?.jsonArrayOrNull
                        ?.mapNotNull { method -> method.jsonPrimitiveOrNull?.contentOrNull }
                    if (supportedGenerationMethods != null &&
                        "generateContent" !in supportedGenerationMethods &&
                        "embedContent" !in supportedGenerationMethods
                    ) {
                        return@mapNotNull null
                    }

                    val displayName = modelObject.stringOrNull("displayName")
                        ?.takeIf { name -> name.isNotBlank() }
                        ?: modelId
                    val type = if (supportedGenerationMethods == null || "generateContent" in supportedGenerationMethods) {
                        ModelType.CHAT
                    } else {
                        ModelType.EMBEDDING
                    }

                    Model(
                        modelId = modelId,
                        displayName = displayName,
                        type = type,
                    )
                }
            } else {
                emptyList()
            }
        }

    override suspend fun generateText(
        providerSetting: ProviderSetting.Google,
        messages: List<UIMessage>,
        params: TextGenerationParams,
    ): TextGenerationResult = withContext(Dispatchers.IO) {
        if (providerSetting.usesInteractionsApi()) {
            return@withContext interactionsAPI.generateText(providerSetting, messages, params)
        }

        val resolvedMessages = fileManager.resolveFileParts(
            providerSetting = providerSetting,
            customHeaders = params.customHeaders,
            messages = messages,
        )
        val requestBody = buildCompletionRequestBody(resolvedMessages, params, providerSetting)

        val url = buildUrl(
            providerSetting = providerSetting,
            path = if (providerSetting.vertexAI) {
                "publishers/google/models/${params.model.modelId}:generateContent"
            } else {
                "models/${params.model.modelId}:generateContent"
            }
        )

        val request = transformRequest(
            providerSetting = providerSetting,
            request = Request.Builder()
                .url(url)
                .headers(providerSetting.mergeCustomHeaders(params.customHeaders))
                .configureSessionHeaders(url.toString(), params.sessionId)
                .post(
                    json.encodeToString(requestBody).toRequestBody("application/json".toMediaType())
                )
                .configureReferHeaders(providerSetting.baseUrl)
                .build()
        )

        val response = client.newCall(request).await()
        if (!response.isSuccessful) {
            val errorBody = response.body?.string()
            throw Exception(enhanceGoogleErrorMessage("Failed to get response: ${response.code} $errorBody"))
        }

        val bodyStr = response.body?.string() ?: ""
        val bodyJson = json.parseToJsonElement(bodyStr).jsonObject

        val candidate = bodyJson["candidates"]?.jsonArray?.firstOrNull()?.jsonObject
            ?: error("No candidates in response")
        TextGenerationResult(
            id = Uuid.random().toString(),
            model = params.model.modelId,
            message = parseMessage(candidate, signatureOrigin = providerSetting.thoughtSignatureOrigin()),
            finishReason = candidate["finishReason"]?.jsonPrimitive?.contentOrNull,
            usage = parseUsageMeta(bodyJson["usageMetadata"] as? JsonObject),
        )
    }

    override suspend fun streamText(
        providerSetting: ProviderSetting.Google,
        messages: List<UIMessage>,
        params: TextGenerationParams,
    ): Flow<StreamChunk> = if (providerSetting.usesInteractionsApi()) {
        interactionsAPI.streamText(providerSetting, messages, params)
    } else {
        streamGenerateContent(providerSetting, messages, params)
    }

    private fun streamGenerateContent(
        providerSetting: ProviderSetting.Google,
        messages: List<UIMessage>,
        params: TextGenerationParams,
    ): Flow<StreamChunk> = callbackFlow {
        val resolvedMessages = fileManager.resolveFileParts(
            providerSetting = providerSetting,
            customHeaders = params.customHeaders,
            messages = messages,
        )
        val requestBody = buildCompletionRequestBody(resolvedMessages, params, providerSetting)

        val url = buildUrl(
            providerSetting = providerSetting,
            path = if (providerSetting.vertexAI) {
                "publishers/google/models/${params.model.modelId}:streamGenerateContent"
            } else {
                "models/${params.model.modelId}:streamGenerateContent"
            }
        ).newBuilder().addQueryParameter("alt", "sse").build()

        val request = transformRequest(
            providerSetting = providerSetting,
            request = Request.Builder()
                .url(url)
                .headers(providerSetting.mergeCustomHeaders(params.customHeaders))
                .configureSessionHeaders(url.toString(), params.sessionId)
                .post(
                    json.encodeToString(requestBody).toRequestBody("application/json".toMediaType())
                )
                .configureReferHeaders(providerSetting.baseUrl)
                .build()
        )

        Log.i(TAG, "streamText: ${json.encodeToString(requestBody)}")

        val responseId = Uuid.random().toString()
        val decoder = GoogleStreamDecoder(
            responseId = responseId,
            model = params.model.modelId,
            signatureOrigin = providerSetting.thoughtSignatureOrigin(),
        )

        fun sendChunks(chunks: Iterable<StreamChunk>) {
            chunks.forEach { chunk ->
                trySend(chunk).onFailure { e ->
                    Log.w(TAG, "onEvent: chunk dropped (${e?.message})")
                }
            }
        }

        val listener = object : EventSourceListener() {
            override fun onEvent(
                eventSource: EventSource,
                id: String?,
                type: String?,
                data: String
            ) {
                Log.i(TAG, "onEvent: $data")

                try {
                    val result = decoder.accept(SseEvent(id = id, event = type, data = data))
                    sendChunks(result.chunks)
                    if (result.completed) close()
                } catch (e: Throwable) {
                    Log.e(TAG, "Failed to parse stream event: $data", e)
                    close(e)
                }
            }

            override fun onFailure(
                eventSource: EventSource,
                t: Throwable?,
                response: Response?
            ) {
                var exception = t

                t?.printStackTrace()
                println("[onFailure] 发生错误: ${t?.message}")

                try {
                    if (t == null && response != null) {
                        val bodyStr = response.body.stringSafe()
                        if (!bodyStr.isNullOrEmpty()) {
                            val bodyElement = json.parseToJsonElement(bodyStr)
                            println(bodyElement)
                            val errorMessage = when (bodyElement) {
                                is JsonObject ->
                                    bodyElement["error"]?.jsonObject?.get("message")?.jsonPrimitive?.contentOrNull
                                        ?: bodyStr
                                is JsonArray ->
                                    bodyElement.firstOrNull()?.jsonObjectOrNull
                                        ?.get("error")?.jsonObjectOrNull
                                        ?.get("message")?.jsonPrimitiveOrNull?.contentOrNull
                                        ?: bodyStr
                                else -> bodyStr
                            }
                            exception = Exception(enhanceGoogleErrorMessage(errorMessage))
                        } else {
                            exception = Exception("Unknown error: ${response.code}")
                        }
                    }
                } catch (e: Throwable) {
                    e.printStackTrace()
                    exception = e
                } finally {
                    close(exception ?: Exception("Stream failed"))
                }
            }

            override fun onClosed(eventSource: EventSource) {
                println("[onClosed] 连接已关闭")
                sendChunks(decoder.onClosed())
                close()
            }
        }

        val eventSource = client.newEventSource(request, listener)

        awaitClose {
            println("[awaitClose] 关闭eventSource")
            eventSource.cancel()
        }
        // trySend 在缓冲满时会静默丢弃 delta，导致回复中间缺字 (#1295)，因此缓冲必须无界
    }.buffer(Channel.UNLIMITED).flowOn(Dispatchers.IO)

    // 保留无 providerSetting 的重载供既有单测反射调用
    private fun buildCompletionRequestBody(
        messages: List<UIMessage>,
        params: TextGenerationParams,
    ): JsonObject = buildCompletionRequestBody(messages, params, providerSetting = null)

    private fun buildCompletionRequestBody(
        messages: List<UIMessage>,
        params: TextGenerationParams,
        providerSetting: ProviderSetting.Google?,
    ): JsonObject = buildJsonObject {
        // System message if available
        val systemMessage = messages.firstOrNull { it.role == MessageRole.SYSTEM }
        if (systemMessage != null && !params.model.outputModalities.contains(Modality.IMAGE)) {
            put("systemInstruction", buildJsonObject {
                putJsonArray("parts") {
                    add(buildJsonObject {
                        put(
                            "text",
                            systemMessage.parts.filterIsInstance<UIMessagePart.Text>()
                                .joinToString { it.text })
                    })
                }
            })
        }

        // Generation config
        put("generationConfig", buildJsonObject {
            if (params.temperature != null) put("temperature", params.temperature)
            if (params.topP != null) put("topP", params.topP)
            if (params.maxTokens != null) put("maxOutputTokens", params.maxTokens)
            if (params.model.outputModalities.contains(Modality.IMAGE)) {
                put("responseModalities", buildJsonArray {
                    add(JsonPrimitive("TEXT"))
                    add(JsonPrimitive("IMAGE"))
                })
            }
            if (params.model.abilities.contains(ModelAbility.REASONING)) {
                put("thinkingConfig", buildJsonObject {
                    put("includeThoughts", true)

                    val isGeminiPro =
                        params.model.modelId.contains(Regex("2\\.5.*pro", RegexOption.IGNORE_CASE))
                    val useThinkingLevel =
                        ModelRegistry.GEMINI_3_SERIES.match(modelId = params.model.modelId) ||
                            ModelRegistry.GEMINI_4.match(modelId = params.model.modelId)

                    when (params.reasoningLevel) {
                        ReasoningLevel.AUTO -> {} // 自动模式，不设置参数

                        ReasoningLevel.OFF -> {
                            if (useThinkingLevel) {
                                put("thinkingLevel", "minimal")
                            } else if (!isGeminiPro) {
                                put("thinkingBudget", 0)
                                put("includeThoughts", false)
                            }
                        }

                        else -> {
                            if (useThinkingLevel) {
                                when (params.reasoningLevel) {
                                    ReasoningLevel.LOW -> put("thinkingLevel", "low")
                                    ReasoningLevel.MEDIUM -> put("thinkingLevel", "medium")
                                    else -> put("thinkingLevel", "high") // HIGH, XHIGH
                                }
                            } else {
                                put("thinkingBudget", params.reasoningLevel.budgetTokens)
                            }
                        }
                    }
                })
            }
        })

        // Contents (user messages)
        val replayContext = providerSetting?.let {
            ThoughtReplayContext(
                currentOrigin = it.thoughtSignatureOrigin(),
                policy = it.crossChannelThoughtPolicy,
            )
        } ?: ThoughtReplayContext()
        put(
            "contents",
            buildContents(messages, replayContext)
        )

        // Client function tools and model built-in tools share the same array.
        val compatMode = providerSetting?.builtInToolCompatMode ?: GoogleBuiltInToolCompatMode.STANDARD
        val useFunctionTools =
            params.tools.isNotEmpty() && params.model.abilities.contains(ModelAbility.TOOL)
        val allowBuiltInTools = !(useFunctionTools && compatMode == GoogleBuiltInToolCompatMode.PREFER_FUNCTION_TOOLS)
        val useBuiltInTools = allowBuiltInTools && params.model.tools.any {
            it == BuiltInTools.Search || it == BuiltInTools.UrlContext
        }
        if (useFunctionTools || useBuiltInTools) {
            putJsonArray("tools") {
                if (useFunctionTools) {
                    add(buildJsonObject {
                        putJsonArray("functionDeclarations") {
                            params.tools.forEach { tool ->
                                add(buildJsonObject {
                                    put("name", JsonPrimitive(tool.name))
                                    put("description", JsonPrimitive(tool.description))
                                    put(
                                        key = "parameters",
                                        element = json.encodeToJsonElement(tool.parameters())
                                            .removeElements(
                                                listOf(
                                                    "const",
                                                    "exclusiveMaximum",
                                                    "exclusiveMinimum",
                                                    "format",
                                                    "additionalProperties",
                                                    "propertyNames",
                                                    "enum",
                                                )
                                            )
                                    )
                                })
                            }
                        }
                    })
                }
                if (allowBuiltInTools) {
                    params.model.tools.forEach { builtInTool ->
                        when (builtInTool) {
                            BuiltInTools.Search -> {
                                add(buildJsonObject {
                                    put("googleSearch", buildJsonObject {})
                                })
                            }

                            BuiltInTools.UrlContext -> {
                                add(buildJsonObject {
                                    put("urlContext", buildJsonObject {})
                                })
                            }

                            else -> {}
                        }
                    }
                }
            }
        }
        if (useFunctionTools && useBuiltInTools) {
            when (compatMode) {
                GoogleBuiltInToolCompatMode.STANDARD -> {
                    // Vertex currently returns opaque search-invocation signatures that it
                    // rejects on replay. Its mixed-tool path works without this opt-in.
                    // Developer API / gateways retain their existing required flag.
                    if (providerSetting?.vertexAI != true) {
                        put("toolConfig", buildJsonObject {
                            put("includeServerSideToolInvocations", true)
                        })
                    }
                }

                GoogleBuiltInToolCompatMode.SNAKE_CASE_TOOL_CONFIG -> {
                    put("tool_config", buildJsonObject {
                        put("include_server_side_tool_invocations", true)
                    })
                }

                GoogleBuiltInToolCompatMode.PREFER_FUNCTION_TOOLS -> Unit
            }
        }

        // Safety Settings
        putJsonArray("safetySettings") {
            add(buildJsonObject {
                put("category", "HARM_CATEGORY_HARASSMENT")
                put("threshold", "OFF")
            })
            add(buildJsonObject {
                put("category", "HARM_CATEGORY_HATE_SPEECH")
                put("threshold", "OFF")
            })
            add(buildJsonObject {
                put("category", "HARM_CATEGORY_SEXUALLY_EXPLICIT")
                put("threshold", "OFF")
            })
            add(buildJsonObject {
                put("category", "HARM_CATEGORY_DANGEROUS_CONTENT")
                put("threshold", "OFF")
            })
            add(buildJsonObject {
                put("category", "HARM_CATEGORY_CIVIC_INTEGRITY")
                put("threshold", "OFF")
            })
        }
    }.mergeCustomBody(params.customBody)

    private fun commonRoleToGoogleRole(role: MessageRole): String {
        return when (role) {
            MessageRole.USER -> "user"
            MessageRole.SYSTEM -> "system"
            MessageRole.ASSISTANT -> "model"
            MessageRole.TOOL -> "user" // google api中, tool结果是用户role发送的
        }
    }

    private fun googleRoleToCommonRole(role: String): MessageRole {
        return when (role) {
            "user" -> MessageRole.USER
            "system" -> MessageRole.SYSTEM
            "model" -> MessageRole.ASSISTANT
            else -> error("Unknown role $role")
        }
    }

    private fun parseMessage(message: JsonObject, signatureOrigin: String? = null): UIMessage {
        val role = googleRoleToCommonRole(
            message["role"]?.jsonPrimitive?.contentOrNull ?: "model"
        )
        val content = message["content"]?.jsonObject ?: error("No content")
        val parts = parseMessageParts(content["parts"]?.jsonArray, signatureOrigin)

        val groundingMetadata = message["groundingMetadata"]?.jsonObject
        Log.i(TAG, "parseMessage: $groundingMetadata")
        val annotations = parseSearchGroundingMetadata(groundingMetadata)

        return UIMessage(
            role = role,
            parts = parts,
            annotations = annotations
        )
    }

    private fun parseSearchGroundingMetadata(jsonObject: JsonObject?): List<UIMessageAnnotation> {
        if (jsonObject == null) return emptyList()
        val groundingChunks = jsonObject["groundingChunks"]?.jsonArray ?: emptyList()
        val chunks = groundingChunks.mapNotNull { chunk ->
            val web = chunk.jsonObject["web"]?.jsonObject ?: return@mapNotNull null
            val uri = web["uri"]?.jsonPrimitive?.contentOrNull ?: return@mapNotNull null
            val title = web["title"]?.jsonPrimitive?.contentOrNull ?: return@mapNotNull null
            UIMessageAnnotation.UrlCitation(
                title = title,
                url = uri
            )
        }
        Log.i(TAG, "parseSearchGroundingMetadata: $chunks")
        return chunks
    }

    private fun parseMessageParts(parts: JsonArray?, signatureOrigin: String? = null): List<UIMessagePart> = buildList {
        parts.orEmpty().forEachIndexed { index, element ->
            val part = parseMessagePart(element.jsonObject, index, signatureOrigin)
            if (part !is UIMessagePart.ServerTool) {
                add(part)
                return@forEachIndexed
            }

            val existingIndex = indexOfFirst {
                it is UIMessagePart.ServerTool && it.toolCallId == part.toolCallId
            }
            if (existingIndex < 0) {
                add(part)
            } else {
                val existing = get(existingIndex) as UIMessagePart.ServerTool
                set(
                    existingIndex, existing.copy(
                        toolName = part.toolName.ifBlank { existing.toolName },
                        input = part.input ?: existing.input,
                        output = part.output ?: existing.output,
                        status = if (part.isFinished) part.status else existing.status,
                        metadata = mergeGoogleMetadata(existing.metadata, part.metadata),
                    )
                )
            }
        }
    }

    private fun parseMessagePart(
        jsonObject: JsonObject,
        index: Int,
        signatureOrigin: String? = null,
    ): UIMessagePart {
        return when {
            jsonObject.containsKey("text") -> {
                val thought = jsonObject["thought"]?.jsonPrimitive?.booleanOrNull ?: false
                val text = jsonObject["text"]?.jsonPrimitive?.content ?: ""
                val metadata = jsonObject.toGoogleThoughtMetadata(
                    signatureOrigin = signatureOrigin,
                    isThought = thought.takeIf { it },
                )
                if (thought) UIMessagePart.Reasoning(
                    reasoning = text,
                    createdAt = Clock.System.now(),
                    finishedAt = null,
                    metadata = metadata,
                ) else UIMessagePart.Text(
                    text = text,
                    metadata = metadata,
                )
            }

            jsonObject.containsKey("functionCall") -> {
                val functionCall = jsonObject["functionCall"]!!.jsonObject
                val toolCallId = functionCall["id"]?.jsonPrimitive?.contentOrNull
                    ?: Uuid.random().toString()
                val thoughtSignature = jsonObject["thoughtSignature"]?.jsonPrimitive?.contentOrNull
                UIMessagePart.Tool(
                    toolCallId = toolCallId,
                    toolName = functionCall["name"]!!.jsonPrimitive.content,
                    input = json.encodeToString(functionCall["args"]),
                    output = emptyList(),
                    metadata = GoogleThoughtMetadata(
                        thoughtSignature = thoughtSignature,
                        signatureOrigin = signatureOrigin,
                    ).takeIf { thoughtSignature != null || signatureOrigin != null }?.toMetadata()
                )
            }

            jsonObject.containsKey("toolCall") -> {
                val toolCall = jsonObject["toolCall"]!!.jsonObject
                UIMessagePart.ServerTool(
                    toolCallId = toolCall["id"]?.jsonPrimitive?.contentOrNull
                        ?: Uuid.random().toString(),
                    toolName = toolCall["toolType"]?.jsonPrimitive?.contentOrNull ?: "",
                    input = toolCall["args"],
                    status = ServerToolStatus.IN_PROGRESS,
                    metadata = ServerToolMetadata(
                        protocol = ServerToolProtocol.GOOGLE_GENERATE_CONTENT,
                        call = jsonObject,
                        callIndex = index,
                        signatureOrigin = signatureOrigin,
                    ).toMetadata(),
                )
            }

            jsonObject.containsKey("toolResponse") -> {
                val toolResponse = jsonObject["toolResponse"]!!.jsonObject
                UIMessagePart.ServerTool(
                    toolCallId = toolResponse["id"]?.jsonPrimitive?.contentOrNull
                        ?: Uuid.random().toString(),
                    toolName = toolResponse["toolType"]?.jsonPrimitive?.contentOrNull ?: "",
                    output = toolResponse["response"],
                    status = ServerToolStatus.COMPLETED,
                    metadata = ServerToolMetadata(
                        protocol = ServerToolProtocol.GOOGLE_GENERATE_CONTENT,
                        result = jsonObject,
                        resultIndex = index,
                        signatureOrigin = signatureOrigin,
                    ).toMetadata(),
                )
            }

            jsonObject.containsKey("inlineData") -> {
                val inlineData = jsonObject["inlineData"]!!.jsonObject
                val mime = inlineData["mimeType"]?.jsonPrimitive?.content ?: "image/png"
                val data = inlineData["data"]?.jsonPrimitive?.content ?: ""
                val thought = jsonObject["thought"]?.jsonPrimitive?.booleanOrNull ?: false
                require(mime.startsWith("image/")) {
                    "Only image mime type is supported"
                }
                // 如果是思考过程中的草稿图，忽略图片数据但保留签名信息
                if (thought) {
                    return UIMessagePart.Reasoning(
                        reasoning = "[Draft Image]\n",
                        createdAt = Clock.System.now(),
                        finishedAt = null,
                        metadata = jsonObject.toGoogleThoughtMetadata(
                            signatureOrigin = signatureOrigin,
                            isThought = true,
                        ),
                    )
                }
                UIMessagePart.Image(
                    url = "data:$mime;base64,$data",
                    metadata = jsonObject.toGoogleThoughtMetadata(signatureOrigin = signatureOrigin),
                )
            }

            jsonObject.containsKey("thoughtSignature") &&
                jsonObject.keys.all { it == "thoughtSignature" || it == "thought" } -> {
                val thought = jsonObject["thought"]?.jsonPrimitive?.booleanOrNull ?: false
                val metadata = jsonObject.toGoogleThoughtMetadata(
                    signatureOrigin = signatureOrigin,
                    isThought = thought.takeIf { it },
                )
                if (thought) {
                    val now = Clock.System.now()
                    UIMessagePart.Reasoning(
                        reasoning = "",
                        createdAt = now,
                        finishedAt = now,
                        metadata = metadata,
                    )
                } else {
                    UIMessagePart.Text(
                        text = "",
                        metadata = metadata,
                    )
                }
            }

            else -> error("unknown message part type: $jsonObject")
        }
    }

    // 保留无 ThoughtReplayContext 的重载供既有单测反射调用
    private fun buildContents(messages: List<UIMessage>): JsonArray =
        buildContents(messages, ThoughtReplayContext())

    private fun buildContents(
        messages: List<UIMessage>,
        replayContext: ThoughtReplayContext,
    ): JsonArray {
        return buildJsonArray {
            messages
                .filter { it.role != MessageRole.SYSTEM && it.isValidToUpload() }
                .forEach { message ->
                    if (message.role == MessageRole.ASSISTANT) {
                        addModelMessage(message, replayContext.forMessage(message))
                    } else {
                        addUserMessage(message, replayContext)
                    }
                }
        }
    }

    private fun JsonArrayBuilder.addModelMessage(
        message: UIMessage,
        replayContext: ThoughtReplayContext,
    ) {
        val groups = groupPartsByToolBoundary(message.parts)
        val partsBuffer = mutableListOf<JsonObject>()

        for (group in groups) {
            when (group) {
                is PartGroup.Content -> {
                    group.parts.flatMap { it.toGoogleParts(replayContext) }.forEach { partsBuffer.add(it) }
                }

                is PartGroup.Tools -> {
                    // 添加 functionCall 到 parts 缓冲
                    group.tools.forEachIndexed { index, tool ->
                        partsBuffer.add(tool.toFunctionCallPart(replayContext, isFirstInBatch = index == 0))
                    }

                    // 输出 model 消息
                    add(buildJsonObject {
                        put("role", "model")
                        putJsonArray("parts") { partsBuffer.forEach { add(it) } }
                    })
                    partsBuffer.clear()

                    // 紧跟 functionResponse
                    add(buildJsonObject {
                        put("role", "user")
                        putJsonArray("parts") {
                            group.tools.forEach { add(it.toFunctionResponsePart(replayContext)) }
                        }
                    })
                }
            }
        }

        // 输出剩余内容
        if (partsBuffer.isNotEmpty()) {
            add(buildJsonObject {
                put("role", "model")
                putJsonArray("parts") { partsBuffer.forEach { add(it) } }
            })
        }
    }

    private fun JsonArrayBuilder.addUserMessage(
        message: UIMessage,
        replayContext: ThoughtReplayContext,
    ) {
        val parts = message.parts.flatMap { it.toGoogleParts(replayContext) }
        if (parts.isEmpty()) return
        add(buildJsonObject {
            put("role", commonRoleToGoogleRole(message.role))
            putJsonArray("parts") {
                parts.forEach { add(it) }
            }
        })
    }

    private fun UIMessagePart.toGoogleParts(
        replayContext: ThoughtReplayContext = ThoughtReplayContext(),
    ): List<JsonObject> = when (this) {
        is UIMessagePart.ServerTool -> toGoogleServerToolParts(replayContext)
        else -> listOfNotNull(toGooglePart(replayContext))
    }

    private fun UIMessagePart.toGooglePart(
        replayContext: ThoughtReplayContext = ThoughtReplayContext(),
    ): JsonObject? = when (this) {
        is UIMessagePart.Text -> {
            val thoughtMeta = metadataAs<GoogleThoughtMetadata>()
            val signature = thoughtMeta?.thoughtSignature?.takeIf {
                !replayContext.isForeignOrUnknown(thoughtMeta.signatureOrigin)
            }
            if (text.isEmpty() && signature == null) {
                null
            } else {
                buildJsonObject {
                    // Preserve the difference between {signature} and {text:"", signature}.
                    // Old records have no marker and retain their previous explicit-text form.
                    if (thoughtMeta?.signatureOnly != true || text.isNotEmpty()) put("text", text)
                    if (thoughtMeta?.signatureOnly == true) {
                        thoughtMeta.isThought?.let { put("thought", it) }
                    }
                    signature?.let { put("thoughtSignature", it) }
                }
            }
        }

        is UIMessagePart.Reasoning -> {
            val thoughtMeta = metadataAs<GoogleThoughtMetadata>()
            val isForeign = replayContext.isForeignOrUnknown(thoughtMeta?.signatureOrigin)
            if (isForeign) {
                when (replayContext.effectivePolicy) {
                    GoogleCrossChannelThoughtPolicy.SKIP_FOREIGN_THOUGHT_AND_BYPASS_TOOLS -> null
                    GoogleCrossChannelThoughtPolicy.STRIP_SIGNATURE_KEEP_THOUGHT_TEXT -> {
                        reasoning.takeIf { it.isNotBlank() }?.let {
                            buildJsonObject {
                                put("text", "[Thought]\n$it")
                            }
                        }
                    }
                    GoogleCrossChannelThoughtPolicy.PASSTHROUGH -> {
                        buildJsonObject {
                            put("text", reasoning)
                            put("thought", true)
                            thoughtMeta?.thoughtSignature?.let { put("thoughtSignature", it) }
                        }
                    }
                }
            } else {
                val signature = thoughtMeta?.thoughtSignature
                if (reasoning.isEmpty() && signature == null) {
                    null
                } else {
                    buildJsonObject {
                        if (thoughtMeta?.signatureOnly != true || reasoning.isNotEmpty()) put("text", reasoning)
                        put("thought", true)
                        signature?.let { put("thoughtSignature", it) }
                    }
                }
            }
        }

        is UIMessagePart.Image -> {
            encodeBase64(false).getOrNull()?.let { encoded ->
                val thoughtMeta = metadataAs<GoogleThoughtMetadata>()
                val signature = thoughtMeta?.thoughtSignature?.takeIf {
                    !replayContext.isForeignOrUnknown(thoughtMeta.signatureOrigin)
                }
                buildJsonObject {
                    put("inlineData", buildJsonObject {
                        put("mimeType", encoded.mimeType)
                        put("data", encoded.base64)
                    })
                    signature?.let { put("thoughtSignature", it) }
                }
            }
        }

        is UIMessagePart.Video -> {
            val videoPart = this
            googleMediaPart(
                fileMeta = videoPart.metadataAs<GoogleFileDataMetadata>(),
                defaultMime = "video/mp4",
                legacyInline = true,
            ) { videoPart.encodeBase64(false).getOrNull() }
        }

        is UIMessagePart.Audio -> {
            val audioPart = this
            googleMediaPart(
                fileMeta = audioPart.metadataAs<GoogleFileDataMetadata>(),
                defaultMime = "audio/mp3",
                legacyInline = true,
            ) { audioPart.encodeBase64(false).getOrNull() }
        }

        is UIMessagePart.Document -> {
            val documentPart = this
            googleMediaPart(
                fileMeta = documentPart.metadataAs<GoogleFileDataMetadata>(),
                defaultMime = documentPart.mime.ifBlank { "application/pdf" },
                legacyInline = false,
            ) { documentPart.encodeBase64(false).getOrNull() }
        }

        else -> null
    }

    /**
     * 附件部件的三种发送方式：
     * - metadata 有 fileUri → `fileData` 引用（Gemini Files API）
     * - metadata 标记 inlineData → `inlineData`（Base64）
     * - 都没有且 legacyInline → 旧行为，直接 Base64 塞进 `inlineData`
     */
    private fun googleMediaPart(
        fileMeta: GoogleFileDataMetadata?,
        defaultMime: String,
        legacyInline: Boolean,
        dataProvider: () -> String?,
    ): JsonObject? {
        val mime = fileMeta?.mimeType ?: defaultMime
        fileMeta?.fileUri?.let { uri ->
            return buildJsonObject {
                put("fileData", buildJsonObject {
                    put("mimeType", mime)
                    put("fileUri", uri)
                })
            }
        }
        val inline = fileMeta?.inlineData == true || (fileMeta == null && legacyInline)
        if (!inline) return null
        val data = dataProvider() ?: return null
        return buildJsonObject {
            put("inlineData", buildJsonObject {
                put("mimeType", mime)
                put("data", data)
            })
        }
    }

    private fun UIMessagePart.Tool.toFunctionCallPart(
        replayContext: ThoughtReplayContext = ThoughtReplayContext(),
        isFirstInBatch: Boolean = true,
    ) = buildJsonObject {
        put("functionCall", buildJsonObject {
            put("name", toolName)
            put("args", inputAsJson())
            put("id", toolCallId)
        })
        val thoughtMeta = metadataAs<GoogleThoughtMetadata>()
        val signature = if (replayContext.isForeignOrUnknown(thoughtMeta?.signatureOrigin)) {
            if (isFirstInBatch) SKIP_THOUGHT_SIGNATURE_VALIDATOR else null
        } else {
            thoughtMeta?.thoughtSignature
        }
        signature?.let { put("thoughtSignature", it) }
    }

    private fun UIMessagePart.Tool.toFunctionResponsePart(
        replayContext: ThoughtReplayContext = ThoughtReplayContext(),
    ) = buildJsonObject {
            put("functionResponse", buildJsonObject {
                put("name", toolName)
                put("id", toolCallId)

                // 1. 拆分出纯文本部分
                val textParts = output.filterIsInstance<UIMessagePart.Text>()
                
                // 2. 提取所有的多模态(图片/视频/音频)，并直接转为 Google 要求的格式
                // 过滤出最终包含 inlineData 的数据块
                val mediaGoogleParts = output
                    .filter { it !is UIMessagePart.Text }
                    .mapNotNull { it.toGooglePart(replayContext) }
                    .filter { it.containsKey("inlineData") } 

                // 3. 构建给模型看的结构化 response 节点
                put("response", buildJsonObject {
                    // 处理文本结果
                    if (textParts.isNotEmpty()) {
                        put(
                            "result", 
                            textParts.joinToString("\n") { it.text }
                        )
                    } else if (mediaGoogleParts.isEmpty()) {
                        // 如果工具啥都没返回，给个兜底成功状态
                        put("result", " ")
                    }

                    // 处理媒体数据（图片、音频、视频），打上 $ref 标签
                    mediaGoogleParts.forEachIndexed { index, _ ->
                        val refName = "media_ref_$index"
                        put(refName, buildJsonObject {
                            put("\$ref", refName)
                        })
                    }
                })

                // 4. 将真实的 Base64 多媒体数据挂载到 parts 中，并建立指针绑定
                if (mediaGoogleParts.isNotEmpty()) {
                    putJsonArray("parts") {
                        mediaGoogleParts.forEachIndexed { index, googlePart ->
                            val refName = "media_ref_$index"
                            val inlineData = googlePart["inlineData"]!!.jsonObject

                            add(buildJsonObject {
                                // 重新组装 inlineData，并在内部注入 displayName
                                put("inlineData", buildJsonObject {
                                    // 复制原有的 mimeType 和 data
                                    inlineData.forEach { (k, v) -> put(k, v) }
                                    // 添加能够让 $ref 认出它的唯一名称
                                    put("displayName", refName)
                                })
                                
                                // 保留可能存在的其他字段
                                googlePart.forEach { (k, v) ->
                                    if (k != "inlineData") put(k, v)
                                }
                            })
                        }
                    }
                }
            })
        }

    private fun UIMessagePart.ServerTool.toGoogleServerToolParts(
        replayContext: ThoughtReplayContext = ThoughtReplayContext(),
    ): List<JsonObject> {
        if (replayContext.ignoreSignatures) {
            return output?.let { result ->
                listOf(buildJsonObject {
                    put("text", "[Previous server tool result: $toolName]\n$result")
                })
            }.orEmpty()
        }

        val metadata = metadataAs<ServerToolMetadata>()
        val protocol = metadata?.protocol
        if (protocol != null && protocol != ServerToolProtocol.GOOGLE_GENERATE_CONTENT) {
            return emptyList()
        }
        val stripForeignSignature = replayContext.isForeignOrUnknown(metadata?.signatureOrigin)
        return buildList {
            metadata?.call?.let { add(if (stripForeignSignature) it.stripThoughtSignature() else it) }
            metadata?.result?.let { add(if (stripForeignSignature) it.stripThoughtSignature() else it) }
        }
    }

    private fun JsonObject.stripThoughtSignature(): JsonObject {
        val stripped = (removeElements(listOf("thoughtSignature")) as? JsonObject) ?: this
        return stripped
    }

    private fun JsonObject.toGoogleThoughtMetadata(
        signatureOrigin: String? = null,
        isThought: Boolean? = null,
    ): JsonObject? {
        val signature = this["thoughtSignature"]?.jsonPrimitive?.contentOrNull
        // Provenance belongs to the whole response, not just its signed parts.
        if (signature == null && signatureOrigin == null) return null
        return GoogleThoughtMetadata(
            thoughtSignature = signature,
            signatureOrigin = signatureOrigin,
            isThought = isThought,
            signatureOnly = signature?.let {
                when {
                    keys.all { key -> key == "thoughtSignature" || key == "thought" } -> true
                    containsKey("text") -> false
                    else -> null // Typed image/function parts already retain their payload type.
                }
            },
        ).toMetadata()
    }

    private fun enhanceGoogleErrorMessage(message: String): String {
        return if (message.contains("include_server_side_tool_invocations", ignoreCase = true)) {
            "$message\n提示：当前渠道不支持直接混用模型内置工具与函数调用，可在 Google 提供商设置中调整「内置工具与函数工具兼容」选项（如切换为「优先函数工具」或「snake_case 字段」）。"
        } else if (message.contains("invalid thought signature", ignoreCase = true)) {
            "$message\n提示：可在出问题的助手回复的更多菜单中选择「忽略本条思考签名」，保留原文后重试；该操作可恢复。"
        } else {
            message
        }
    }

    private fun mergeGoogleMetadata(first: JsonObject?, second: JsonObject?): JsonObject? = when {
        first == null -> second
        second == null -> first
        else -> JsonObject(first + second)
    }

    private fun parseUsageMeta(jsonObject: JsonObject?): TokenUsage? {
        if (jsonObject == null) {
            return null
        }
        val promptTokens = jsonObject["promptTokenCount"]?.jsonPrimitiveOrNull?.intOrNull ?: 0
        val thoughtTokens = jsonObject["thoughtsTokenCount"]?.jsonPrimitiveOrNull?.intOrNull ?: 0
        val cachedTokens = jsonObject["cachedContentTokenCount"]?.jsonPrimitiveOrNull?.intOrNull ?: 0
        val candidatesTokens = jsonObject["candidatesTokenCount"]?.jsonPrimitiveOrNull?.intOrNull ?: 0
        val totalTokens = jsonObject["totalTokenCount"]?.jsonPrimitiveOrNull?.intOrNull ?: 0
        return TokenUsage(
            promptTokens = promptTokens,
            completionTokens = candidatesTokens + thoughtTokens,
            totalTokens = totalTokens,
            cachedTokens = cachedTokens
        )
    }
}
