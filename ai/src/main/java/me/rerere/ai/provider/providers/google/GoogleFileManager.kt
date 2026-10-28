package me.rerere.ai.provider.providers.google

import android.util.Log
import androidx.core.net.toUri
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import me.rerere.ai.provider.CustomHeader
import me.rerere.ai.provider.ProviderSetting
import me.rerere.ai.ui.GoogleFileDataMetadata
import me.rerere.ai.ui.UIMessage
import me.rerere.ai.ui.UIMessagePart
import me.rerere.ai.ui.metadataAs
import me.rerere.ai.ui.toMetadata
import me.rerere.ai.util.KeyRoulette
import me.rerere.ai.util.configureReferHeaders
import me.rerere.ai.util.json
import me.rerere.ai.util.mergeCustomHeaders
import me.rerere.common.http.await
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.MediaType
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody
import okhttp3.RequestBody.Companion.toRequestBody
import okio.BufferedSink
import okio.source
import java.io.File
import java.io.IOException
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit

private const val TAG = "GoogleFileManager"

/**
 * Gemini Files API (`POST /upload/v1beta/files`) 上传与生命周期管理。
 *
 * 将本地的视频、音频及原生支持的文档（如 PDF）通过 Resumable 协议流式上传至 Google File API，
 * 避免大文件 Base64 引发 OOM 或超过 20MB inlineData 限制，并支持多轮对话复用 `file_uri`。
 */
internal class GoogleFileManager(
    private val client: OkHttpClient,
    private val keyRoulette: KeyRoulette,
) {
    data class UploadedFile(
        val name: String,
        val uri: String,
        val mimeType: String,
        val uploadedAtMs: Long = System.currentTimeMillis(),
    )

    // File API 的文件有效期为 48 小时，这里缓存 47 小时以安全复用
    private val cacheTtlMs = 47L * 60 * 60 * 1000
    private val cache = ConcurrentHashMap<String, UploadedFile>()
    private val mutexes = ConcurrentHashMap<String, Mutex>()

    private val uploadClient by lazy {
        client.newBuilder()
            .writeTimeout(15, TimeUnit.MINUTES)
            .readTimeout(5, TimeUnit.MINUTES)
            .callTimeout(20, TimeUnit.MINUTES)
            .build()
    }

    companion object {
        fun guessMediaMimeType(file: File, fallback: String): String {
            return when (file.extension.lowercase()) {
                "mp4", "m4v" -> "video/mp4"
                "mov" -> "video/quicktime"
                "webm" -> "video/webm"
                "mkv" -> "video/x-matroska"
                "avi" -> "video/x-msvideo"
                "flv" -> "video/x-flv"
                "mpg", "mpeg" -> "video/mpeg"
                "3gp" -> "video/3gpp"
                "wmv" -> "video/x-ms-wmv"
                "mp3" -> "audio/mp3"
                "wav" -> "audio/wav"
                "aac" -> "audio/aac"
                "ogg", "oga" -> "audio/ogg"
                "flac" -> "audio/flac"
                "m4a" -> "audio/mp4"
                "opus" -> "audio/opus"
                "pdf" -> "application/pdf"
                else -> fallback
            }
        }

        private fun mergeMetadata(existing: JsonObject?, extra: JsonObject): JsonObject {
            return if (existing == null) extra else JsonObject(existing + extra)
        }
    }

    /**
     * 在构建 Gemini 请求前，将消息中的本地 Video / Audio / Document(PDF) 预上传到 Gemini File API，
     * 并将返回的 `fileUri` 和 `mimeType` 附加到部件的 metadata 中。
     */
    suspend fun resolveFileParts(
        providerSetting: ProviderSetting.Google,
        customHeaders: List<CustomHeader>,
        messages: List<UIMessage>,
    ): List<UIMessage> {
        if (providerSetting.vertexAI) return messages
        return messages.map { message ->
            message.copy(
                parts = message.parts.map { part ->
                    resolvePart(providerSetting, customHeaders, part)
                }
            )
        }
    }

    private suspend fun resolvePart(
        providerSetting: ProviderSetting.Google,
        customHeaders: List<CustomHeader>,
        part: UIMessagePart,
    ): UIMessagePart {
        if (part.metadataAs<GoogleFileDataMetadata>()?.fileUri != null) {
            return part
        }
        return when (part) {
            is UIMessagePart.Video -> {
                if (!part.url.startsWith("file://")) return part
                val uploaded = getOrUploadFile(
                    providerSetting = providerSetting,
                    customHeaders = customHeaders,
                    localUrl = part.url,
                    mimeType = "video/mp4",
                )
                val fileMeta = GoogleFileDataMetadata(fileUri = uploaded.uri, mimeType = uploaded.mimeType).toMetadata()
                part.copy(metadata = mergeMetadata(part.metadata, fileMeta))
            }

            is UIMessagePart.Audio -> {
                if (!part.url.startsWith("file://")) return part
                val uploaded = getOrUploadFile(
                    providerSetting = providerSetting,
                    customHeaders = customHeaders,
                    localUrl = part.url,
                    mimeType = "audio/mp3",
                )
                val fileMeta = GoogleFileDataMetadata(fileUri = uploaded.uri, mimeType = uploaded.mimeType).toMetadata()
                part.copy(metadata = mergeMetadata(part.metadata, fileMeta))
            }

            is UIMessagePart.Document -> {
                val isPdf = part.mime.equals("application/pdf", ignoreCase = true) ||
                    part.fileName.endsWith(".pdf", ignoreCase = true)
                if (!isPdf || !part.url.startsWith("file://")) return part
                val uploaded = getOrUploadFile(
                    providerSetting = providerSetting,
                    customHeaders = customHeaders,
                    localUrl = part.url,
                    mimeType = "application/pdf",
                    displayName = part.fileName,
                )
                val fileMeta = GoogleFileDataMetadata(fileUri = uploaded.uri, mimeType = uploaded.mimeType).toMetadata()
                part.copy(metadata = mergeMetadata(part.metadata, fileMeta))
            }

            is UIMessagePart.Tool -> {
                if (part.output.isEmpty()) return part
                part.copy(
                    output = part.output.map { outPart ->
                        resolvePart(providerSetting, customHeaders, outPart)
                    }
                )
            }

            else -> part
        }
    }

    suspend fun getOrUploadFile(
        providerSetting: ProviderSetting.Google,
        customHeaders: List<CustomHeader>,
        localUrl: String,
        mimeType: String,
        displayName: String? = null,
    ): UploadedFile = withContext(Dispatchers.IO) {
        if (!localUrl.startsWith("file://")) {
            throw IllegalArgumentException("Only file:// URLs can be uploaded to File API: $localUrl")
        }
        val filePath = localUrl.toUri().path
            ?: throw IllegalArgumentException("Invalid file URI: $localUrl")
        val file = File(filePath)
        if (!file.exists() || !file.isFile) {
            throw IllegalArgumentException("File does not exist: $localUrl")
        }

        val resolvedMime = guessMediaMimeType(file, mimeType)

        val cacheKey = "${providerSetting.baseUrl}|${file.absolutePath}|${file.lastModified()}|${file.length()}"
        val now = System.currentTimeMillis()
        cache[cacheKey]?.let { cached ->
            if (now - cached.uploadedAtMs < cacheTtlMs) {
                return@withContext cached
            } else {
                cache.remove(cacheKey)
            }
        }

        val mutex = mutexes.getOrPut(cacheKey) { Mutex() }
        mutex.withLock {
            cache[cacheKey]?.let { cached ->
                if (System.currentTimeMillis() - cached.uploadedAtMs < cacheTtlMs) {
                    return@withLock cached
                }
            }
            val uploaded = uploadFileInternal(
                providerSetting = providerSetting,
                customHeaders = customHeaders,
                file = file,
                mimeType = resolvedMime,
                displayName = displayName ?: file.name,
            )
            cache[cacheKey] = uploaded
            uploaded
        }
    }

    private suspend fun uploadFileInternal(
        providerSetting: ProviderSetting.Google,
        customHeaders: List<CustomHeader>,
        file: File,
        mimeType: String,
        displayName: String,
    ): UploadedFile {
        val fileSize = file.length()
        val apiKey = keyRoulette.next(providerSetting.apiKey, providerSetting.id.toString())

        // 解析 upload 端点：如 https://generativelanguage.googleapis.com/v1beta -> https://generativelanguage.googleapis.com/upload/v1beta/files
        val baseHttpUrl = providerSetting.baseUrl.trimEnd('/').toHttpUrl()
        val basePath = baseHttpUrl.encodedPath.trimEnd('/')
        val uploadPath = if (basePath.startsWith("/upload/")) {
            "$basePath/files"
        } else {
            "/upload$basePath/files"
        }
        val initUrl = baseHttpUrl.newBuilder()
            .encodedPath(uploadPath)
            .build()

        val metadataBody = buildJsonObject {
            put("file", buildJsonObject {
                put("display_name", displayName)
            })
        }

        val initRequest = Request.Builder()
            .url(initUrl)
            .headers(providerSetting.mergeCustomHeaders(customHeaders))
            .addHeader("x-goog-api-key", apiKey)
            .addHeader("X-Goog-Upload-Protocol", "resumable")
            .addHeader("X-Goog-Upload-Command", "start")
            .addHeader("X-Goog-Upload-Header-Content-Length", fileSize.toString())
            .addHeader("X-Goog-Upload-Header-Content-Type", mimeType)
            .post(json.encodeToString(metadataBody).toRequestBody("application/json".toMediaType()))
            .configureReferHeaders(providerSetting.baseUrl)
            .build()

        val uploadUrl = uploadClient.newCall(initRequest).await().use { response ->
            if (!response.isSuccessful) {
                val errBody = response.body?.string().orEmpty()
                throw IOException("Failed to initiate Gemini file upload (${response.code}): $errBody")
            }
            response.header("X-Goog-Upload-URL")
                ?: response.header("x-goog-upload-url")
                ?: throw IOException("Missing X-Goog-Upload-URL header in Gemini upload response")
        }

        val streamRequestBody = object : RequestBody() {
            override fun contentType(): MediaType? = mimeType.toMediaTypeOrNull()
            override fun contentLength(): Long = fileSize
            override fun writeTo(sink: BufferedSink) {
                file.inputStream().use { input ->
                    sink.writeAll(input.source())
                }
            }
        }

        val uploadRequest = Request.Builder()
            .url(uploadUrl)
            .headers(providerSetting.mergeCustomHeaders(customHeaders))
            .addHeader("Content-Length", fileSize.toString())
            .addHeader("X-Goog-Upload-Offset", "0")
            .addHeader("X-Goog-Upload-Command", "upload, finalize")
            .post(streamRequestBody)
            .configureReferHeaders(providerSetting.baseUrl)
            .build()

        var fileName: String
        var fileUri: String
        var state: String

        uploadClient.newCall(uploadRequest).await().use { response ->
            val bodyStr = response.body?.string().orEmpty()
            if (!response.isSuccessful) {
                throw IOException("Failed to upload file to Gemini (${response.code}): $bodyStr")
            }
            val rootObj = json.parseToJsonElement(bodyStr).jsonObject
            val fileObj = rootObj["file"]?.jsonObject ?: rootObj
            fileName = fileObj["name"]?.jsonPrimitive?.contentOrNull
                ?: throw IOException("Missing file.name in upload response: $bodyStr")
            fileUri = fileObj["uri"]?.jsonPrimitive?.contentOrNull
                ?: throw IOException("Missing file.uri in upload response: $bodyStr")
            state = fileObj["state"]?.jsonPrimitive?.contentOrNull ?: "ACTIVE"
        }

        Log.i(TAG, "Uploaded $displayName -> $fileUri (initial state: $state)")

        // 视频等大文件上传后处于 PROCESSING 状态，需轮询至 ACTIVE 才能在对话中使用
        var pollAttempts = 0
        while (state == "PROCESSING" && pollAttempts < 180) {
            delay(2000)
            pollAttempts++
            val checkUrl = "${providerSetting.baseUrl.trimEnd('/')}/${fileName.trimStart('/')}".toHttpUrl()
            val checkRequest = Request.Builder()
                .url(checkUrl)
                .headers(providerSetting.mergeCustomHeaders(customHeaders))
                .addHeader("x-goog-api-key", apiKey)
                .get()
                .configureReferHeaders(providerSetting.baseUrl)
                .build()

            uploadClient.newCall(checkRequest).await().use { response ->
                val bodyStr = response.body?.string().orEmpty()
                if (!response.isSuccessful) {
                    throw IOException("Failed to poll Gemini file status (${response.code}): $bodyStr")
                }
                val rootObj = json.parseToJsonElement(bodyStr).jsonObject
                val fileObj = rootObj["file"]?.jsonObject ?: rootObj
                state = fileObj["state"]?.jsonPrimitive?.contentOrNull ?: "FAILED"
                fileObj["uri"]?.jsonPrimitive?.contentOrNull?.let { fileUri = it }
                if (state == "FAILED") {
                    val errMsg = fileObj["error"]?.jsonObject?.get("message")?.jsonPrimitive?.contentOrNull
                        ?: "Google File API failed to process file $displayName"
                    throw IOException(errMsg)
                }
            }
        }

        if (state != "ACTIVE") {
            throw IOException("Timed out waiting for Gemini file $displayName to become ACTIVE (last state: $state)")
        }

        return UploadedFile(
            name = fileName,
            uri = fileUri,
            mimeType = mimeType,
        )
    }
}
