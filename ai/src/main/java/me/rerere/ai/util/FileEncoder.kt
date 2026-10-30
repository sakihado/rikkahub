package me.rerere.ai.util

import android.media.ExifInterface
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.util.Base64
import android.util.Base64OutputStream
import android.util.LruCache
import androidx.core.net.toUri
import me.rerere.ai.ui.UIMessagePart
import java.io.ByteArrayOutputStream
import java.io.File
import kotlin.math.roundToInt
import kotlin.math.sqrt

private val supportedTypes = setOf(
    "image/jpeg",
    "image/png",
    "image/gif",
    "image/webp",
)

data class EncodedImage(
    val base64: String,
    val mimeType: String
)

internal enum class ExifTransformType {
    NONE,
    FLIP_HORIZONTAL,
    ROTATE_180,
    FLIP_VERTICAL,
    TRANSPOSE,
    ROTATE_90,
    TRANSVERSE,
    ROTATE_270,
}

internal fun mapExifOrientationToTransform(orientation: Int): ExifTransformType = when (orientation) {
    ExifInterface.ORIENTATION_FLIP_HORIZONTAL -> ExifTransformType.FLIP_HORIZONTAL
    ExifInterface.ORIENTATION_ROTATE_180 -> ExifTransformType.ROTATE_180
    ExifInterface.ORIENTATION_FLIP_VERTICAL -> ExifTransformType.FLIP_VERTICAL
    ExifInterface.ORIENTATION_TRANSPOSE -> ExifTransformType.TRANSPOSE
    ExifInterface.ORIENTATION_ROTATE_90 -> ExifTransformType.ROTATE_90
    ExifInterface.ORIENTATION_TRANSVERSE -> ExifTransformType.TRANSVERSE
    ExifInterface.ORIENTATION_ROTATE_270 -> ExifTransformType.ROTATE_270
    ExifInterface.ORIENTATION_NORMAL,
    ExifInterface.ORIENTATION_UNDEFINED
    -> ExifTransformType.NONE

    else -> ExifTransformType.NONE
}

fun UIMessagePart.Image.encodeBase64(withPrefix: Boolean = true): Result<EncodedImage> = runCatching {
    when {
        this.url.startsWith("file://") -> {
            val filePath =
                this.url.toUri().path ?: throw IllegalArgumentException("Invalid file URI: ${this.url}")
            val file = File(filePath)
            if (!file.exists()) {
                throw IllegalArgumentException("File does not exist: ${this.url}")
            }
            val mimeType = file.guessMimeType().getOrThrow()
            val encoded = file.encodeForRequest(mimeType)
            EncodedImage(
                base64 = if (withPrefix) "data:${encoded.mimeType};base64,${encoded.base64}" else encoded.base64,
                mimeType = encoded.mimeType
            )
        }

        this.url.startsWith("data:") -> {
            // 从 data URL 提取 mime type
            val mimeType = url.substringAfter("data:").substringBefore(";")
            EncodedImage(base64 = url, mimeType = mimeType)
        }
        this.url.startsWith("http") -> {
            // HTTP URL 无法确定 mime type，默认使用 image/png
            EncodedImage(base64 = url, mimeType = "image/png")
        }
        else -> throw IllegalArgumentException("Unsupported URL format: $url")
    }
}

fun UIMessagePart.Video.encodeBase64(withPrefix: Boolean = true): Result<String> = runCatching {
    when {
        this.url.startsWith("file://") -> {
            val filePath =
                this.url.toUri().path ?: throw IllegalArgumentException("Invalid file URI: ${this.url}")
            val file = File(filePath)
            if (!file.exists()) {
                throw IllegalArgumentException("File does not exist: ${this.url}")
            }
            val encoded = file.encodeToBase64Streaming()
            if (withPrefix) "data:video/mp4;base64,$encoded" else encoded
        }

        else -> throw IllegalArgumentException("Unsupported URL format: $url")
    }
}

fun UIMessagePart.Audio.encodeBase64(withPrefix: Boolean = true): Result<String> = runCatching {
    when {
        this.url.startsWith("file://") -> {
            val filePath =
                this.url.toUri().path ?: throw IllegalArgumentException("Invalid file URI: ${this.url}")
            val file = File(filePath)
            if (!file.exists()) {
                throw IllegalArgumentException("File does not exist: ${this.url}")
            }
            val encoded = file.encodeToBase64Streaming()
            if (withPrefix) "data:audio/mp3;base64,$encoded" else encoded
        }

        else -> throw IllegalArgumentException("Unsupported URL format: $url")
    }
}

fun UIMessagePart.Document.encodeBase64(withPrefix: Boolean = true): Result<String> = runCatching {
    when {
        this.url.startsWith("file://") -> {
            val filePath =
                this.url.toUri().path ?: throw IllegalArgumentException("Invalid file URI: ${this.url}")
            val file = File(filePath)
            if (!file.exists()) {
                throw IllegalArgumentException("File does not exist: ${this.url}")
            }
            val encoded = file.encodeToBase64Streaming()
            val mime = this.mime.ifBlank { "application/pdf" }
            if (withPrefix) "data:$mime;base64,$encoded" else encoded
        }

        else -> throw IllegalArgumentException("Unsupported URL format: $url")
    }
}

// 图片进入请求前的压缩参数：长边 2048、像素 400 万、JPEG q85
// 服务端最终也会把图缩到这个量级（Claude 1568 / Gemini 768 分块），再大只会白耗体积与 token
private const val IMAGE_MAX_DIMENSION = 2048
private const val IMAGE_MAX_PIXELS = 4_000_000L
private const val IMAGE_JPEG_QUALITY = 85
// 编码结果按 KB 计数缓存 24MB，避免每轮请求重复解码+编码历史图片
private const val IMAGE_CACHE_MAX_KB = 24 * 1024
// 未编码过的图片在估算体积时使用的上限（长边 2048 的 JPEG 实际远小于这个值）
private const val IMAGE_ESTIMATE_MAX_BYTES = 700_000L

internal data class EncodedImageBytes(
    val base64: String,
    val mimeType: String,
    val byteSize: Long,
)

private val encodedImageCache = object : LruCache<String, ByteArray>(IMAGE_CACHE_MAX_KB) {
    override fun sizeOf(key: String, value: ByteArray): Int = value.size / 1024
}

private fun File.imageCacheKey(): String =
    "$absolutePath|${lastModified()}|${length()}|$IMAGE_MAX_DIMENSION|$IMAGE_MAX_PIXELS|$IMAGE_JPEG_QUALITY"

/** 压缩（或原样返回 GIF）后的字节；命中缓存时直接返回 */
private fun File.compressedBytes(mimeType: String): ByteArray {
    // GIF 保持原样（可能是动图），也不进缓存
    if (mimeType == "image/gif") {
        return readBytes()
    }
    val key = imageCacheKey()
    encodedImageCache.get(key)?.let { return it }
    val bytes = compressToJpegBytes()
    encodedImageCache.put(key, bytes)
    return bytes
}

private fun File.encodeForRequest(mimeType: String): EncodedImageBytes {
    val bytes = compressedBytes(mimeType)
    val outputMimeType = if (mimeType == "image/gif") mimeType else "image/jpeg"
    return EncodedImageBytes(
        base64 = Base64.encodeToString(bytes, Base64.NO_WRAP),
        mimeType = outputMimeType,
        byteSize = bytes.size.toLong(),
    )
}

/**
 * 估算图片编码后（Base64 之后）的字节数。
 *
 * [exact] 为 true 时会真的编码一次（结果进缓存，随后的请求直接复用），
 * 用于发送前的体积校验；界面展示用 false，避免展开面板时卡顿。
 */
fun estimateImageBase64Bytes(file: File, mimeType: String, exact: Boolean): Long {
    if (!file.exists()) return 0
    val rawBytes = if (mimeType == "image/gif") {
        file.length()
    } else {
        encodedImageCache.get(file.imageCacheKey())?.size?.toLong()
            ?: if (exact) {
                runCatching { file.compressedBytes(mimeType).size.toLong() }
                    .getOrElse { minOf(file.length(), IMAGE_ESTIMATE_MAX_BYTES) }
            } else {
                minOf(file.length(), IMAGE_ESTIMATE_MAX_BYTES)
            }
    }
    return rawBytes / 3 * 4 + 4
}

private fun File.compressToJpegBytes(): ByteArray {
    // 先用 inSampleSize 粗缩（只能是 2 的幂），再做一次精确缩放
    val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
    BitmapFactory.decodeFile(absolutePath, bounds)
    val options = BitmapFactory.Options().apply {
        inSampleSize = calculateImageInSampleSize(
            width = bounds.outWidth,
            height = bounds.outHeight,
            maxDimension = IMAGE_MAX_DIMENSION,
            maxPixels = IMAGE_MAX_PIXELS
        )
        inJustDecodeBounds = false
    }

    val decoded = BitmapFactory.decodeFile(absolutePath, options)
        ?: throw IllegalArgumentException("Failed to decode image: $absolutePath")

    var bitmap = normalizeByExif(decoded)
    if (bitmap !== decoded) decoded.recycle()

    val scaled = bitmap.scaleToFit(IMAGE_MAX_DIMENSION, IMAGE_MAX_PIXELS)
    if (scaled !== bitmap) bitmap.recycle()

    return try {
        ByteArrayOutputStream().use { output ->
            scaled.compress(Bitmap.CompressFormat.JPEG, IMAGE_JPEG_QUALITY, output)
            output.toByteArray()
        }
    } finally {
        scaled.recycle()
    }
}

/** 精确缩放到「长边 ≤ [maxDimension] 且像素 ≤ [maxPixels]」的框内，不放大 */
private fun Bitmap.scaleToFit(maxDimension: Int, maxPixels: Long): Bitmap {
    val sourceWidth = width
    val sourceHeight = height
    if (sourceWidth <= 0 || sourceHeight <= 0) return this

    var scale = minOf(1f, maxDimension.toFloat() / maxOf(sourceWidth, sourceHeight))
    val scaledPixels = (sourceWidth * scale).toLong() * (sourceHeight * scale).toLong()
    if (scaledPixels > maxPixels) {
        scale *= sqrt(maxPixels.toDouble() / scaledPixels.toDouble()).toFloat()
    }
    if (scale >= 0.999f) return this

    val targetWidth = (sourceWidth * scale).roundToInt().coerceAtLeast(1)
    val targetHeight = (sourceHeight * scale).roundToInt().coerceAtLeast(1)
    return Bitmap.createScaledBitmap(this, targetWidth, targetHeight, true)
}

private fun File.normalizeByExif(bitmap: Bitmap): Bitmap {
    val orientation = runCatching {
        ExifInterface(absolutePath).getAttributeInt(
            ExifInterface.TAG_ORIENTATION,
            ExifInterface.ORIENTATION_NORMAL
        )
    }.getOrDefault(ExifInterface.ORIENTATION_NORMAL)
    val transform = mapExifOrientationToTransform(orientation)
    return applyExifTransform(bitmap, transform)
}

private fun applyExifTransform(bitmap: Bitmap, transform: ExifTransformType): Bitmap {
    if (transform == ExifTransformType.NONE) return bitmap

    val matrix = Matrix()
    when (transform) {
        ExifTransformType.NONE -> return bitmap
        ExifTransformType.FLIP_HORIZONTAL -> matrix.setScale(-1f, 1f)
        ExifTransformType.ROTATE_180 -> matrix.setRotate(180f)
        ExifTransformType.FLIP_VERTICAL -> matrix.setScale(1f, -1f)
        ExifTransformType.TRANSPOSE -> {
            matrix.setRotate(90f)
            matrix.postScale(-1f, 1f)
        }
        ExifTransformType.ROTATE_90 -> matrix.setRotate(90f)
        ExifTransformType.TRANSVERSE -> {
            matrix.setRotate(270f)
            matrix.postScale(-1f, 1f)
        }
        ExifTransformType.ROTATE_270 -> matrix.setRotate(270f)
    }

    return runCatching {
        Bitmap.createBitmap(bitmap, 0, 0, bitmap.width, bitmap.height, matrix, true)
    }.getOrElse { bitmap }
}

private fun File.encodeToBase64Streaming(): String {
    val byteArrayOutputStream = ByteArrayOutputStream()
    Base64OutputStream(byteArrayOutputStream, Base64.NO_WRAP).use { base64Stream ->
        inputStream().use { input ->
            input.copyTo(base64Stream, bufferSize = 8 * 1024)
        }
    }
    return byteArrayOutputStream.toString(Charsets.ISO_8859_1.name())
}

internal fun calculateImageInSampleSize(
    width: Int,
    height: Int,
    maxDimension: Int,
    maxPixels: Long
): Int {
    if (width <= 0 || height <= 0) return 1

    var inSampleSize = 1
    while (
        (height / inSampleSize) > maxDimension ||
        (width / inSampleSize) > maxDimension ||
        (width.toLong() / inSampleSize) * (height.toLong() / inSampleSize) > maxPixels
    ) {
        inSampleSize *= 2
    }
    return inSampleSize
}

/** 图片 MIME（按文件头嗅探），失败时返回 null */
internal fun File.guessImageMimeTypeOrNull(): String? = guessMimeType().getOrNull()

private fun File.guessMimeType(): Result<String> = runCatching {
    inputStream().use { input ->
        val bytes = ByteArray(16)
        val read = input.read(bytes)
        if (read < 12) error("File too short to determine MIME type")

        // 判断 HEIF/HEIC/AVIF 格式：ISO-BMFF 容器，"ftyp" box 位于字节 4..8，主品牌码位于 8..12
        // 新手机的 HDR HEIF 照片常用 heix/hevc/mif1/msf1 等品牌码，而非仅 heic，需全部识别
        if (bytes.copyOfRange(4, 8).toString(Charsets.US_ASCII) == "ftyp") {
            when (bytes.copyOfRange(8, 12).toString(Charsets.US_ASCII)) {
                "heic", "heix", "heim", "heis",
                "hevc", "hevx", "hevm", "hevs",
                "mif1", "msf1", "heif",
                    -> return@runCatching "image/heic"

                "avif", "avis" -> return@runCatching "image/avif"
            }
        }

        // 判断 JPEG 格式：开头为 0xFF 0xD8
        if (bytes[0] == 0xFF.toByte() && bytes[1] == 0xD8.toByte()) {
            return@runCatching "image/jpeg"
        }

        // 判断 PNG 格式：开头为 89 50 4E 47 0D 0A 1A 0A
        if (bytes.copyOfRange(0, 8).contentEquals(
                byteArrayOf(0x89.toByte(), 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A)
            )
        ) {
            return@runCatching "image/png"
        }

        // 判断WebP格式：开头为 "RIFF" + 4字节长度 + "WEBP"
        if (bytes.copyOfRange(0, 4).toString(Charsets.US_ASCII) == "RIFF" && bytes.copyOfRange(8, 12)
                .toString(Charsets.US_ASCII) == "WEBP"
        ) {
            return@runCatching "image/webp"
        }

        // 判断 GIF 格式：开头为 "GIF89a" 或 "GIF87a"
        val header = bytes.copyOfRange(0, 6).toString(Charsets.US_ASCII)
        if (header == "GIF89a" || header == "GIF87a") {
            return@runCatching "image/gif"
        }

        error(
            "Failed to guess MIME type: $header, ${
                bytes.joinToString(",") {
                    it.toUByte().toString()
                }
            }"
        )
    }
}
