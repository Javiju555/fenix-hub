package com.fenixhub.mobile.util

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.util.Base64
import java.io.ByteArrayOutputStream
import java.io.File
import kotlin.math.min
import kotlin.math.max

object PreviewUtils {
    fun textPreview(text: String): String = text.trim().take(80)

    fun imagePreviewDataUrl(bytes: ByteArray, mimeType: String? = null): String {
        return imagePreviewDataUrl(
            decodeBounds = { options ->
                BitmapFactory.decodeByteArray(bytes, 0, bytes.size, options)
            },
            decodeBitmap = { options ->
                BitmapFactory.decodeByteArray(bytes, 0, bytes.size, options)
            },
            sourceMimeType = mimeType,
            maxEdge = LOCAL_PREVIEW_MAX_EDGE,
            quality = LOCAL_PREVIEW_QUALITY,
            format = Bitmap.CompressFormat.WEBP_LOSSY,
            outputMimeType = "image/webp",
        )
    }

    fun imagePreviewDataUrl(file: File, mimeType: String? = null): String {
        return imagePreviewDataUrl(
            decodeBounds = { options ->
                file.inputStream().buffered().use { input ->
                    BitmapFactory.decodeStream(input, null, options)
                }
            },
            decodeBitmap = { options ->
                file.inputStream().buffered().use { input ->
                    BitmapFactory.decodeStream(input, null, options)
                }
            },
            sourceMimeType = mimeType,
            maxEdge = LOCAL_PREVIEW_MAX_EDGE,
            quality = LOCAL_PREVIEW_QUALITY,
            format = Bitmap.CompressFormat.WEBP_LOSSY,
            outputMimeType = "image/webp",
        )
    }

    fun imageAnnouncementPreviewDataUrl(bytes: ByteArray): String {
        ANNOUNCEMENT_PREVIEW_CANDIDATES.forEach { candidate ->
            val preview = imagePreviewDataUrl(
                decodeBounds = { options ->
                    BitmapFactory.decodeByteArray(bytes, 0, bytes.size, options)
                },
                decodeBitmap = { options ->
                    BitmapFactory.decodeByteArray(bytes, 0, bytes.size, options)
                },
                sourceMimeType = null,
                maxEdge = candidate.maxEdge,
                quality = candidate.quality,
                format = Bitmap.CompressFormat.WEBP_LOSSY,
                outputMimeType = "image/webp",
            )
            if (preview.length <= ANNOUNCEMENT_PREVIEW_MAX_CHARS) {
                return preview
            }
        }

        val fallback = ANNOUNCEMENT_PREVIEW_CANDIDATES.last()
        return imagePreviewDataUrl(
            decodeBounds = { options ->
                BitmapFactory.decodeByteArray(bytes, 0, bytes.size, options)
            },
            decodeBitmap = { options ->
                BitmapFactory.decodeByteArray(bytes, 0, bytes.size, options)
            },
            sourceMimeType = null,
            maxEdge = fallback.maxEdge,
            quality = fallback.quality,
            format = Bitmap.CompressFormat.WEBP_LOSSY,
            outputMimeType = "image/webp",
        )
    }

    private fun imagePreviewDataUrl(
        decodeBounds: (BitmapFactory.Options) -> Unit,
        decodeBitmap: (BitmapFactory.Options) -> Bitmap?,
        sourceMimeType: String?,
        maxEdge: Int,
        quality: Int,
        format: Bitmap.CompressFormat,
        outputMimeType: String,
    ): String {
        val bounds = BitmapFactory.Options().apply {
            inJustDecodeBounds = true
        }
        decodeBounds(bounds)
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) {
            val fallbackMimeType = sourceMimeType?.takeIf { it.startsWith("image/") } ?: outputMimeType
            return "data:$fallbackMimeType;base64,"
        }

        val original = decodeBitmap(
            BitmapFactory.Options().apply {
                inSampleSize = calculateInSampleSize(bounds.outWidth, bounds.outHeight, maxEdge)
                inPreferredConfig = Bitmap.Config.RGB_565
            },
        ) ?: return "data:$outputMimeType;base64,"

        val (targetWidth, targetHeight) = scaledSize(original.width, original.height, maxEdge)
        val scaled = if (original.width == targetWidth && original.height == targetHeight) {
            original
        } else {
            Bitmap.createScaledBitmap(original, targetWidth, targetHeight, true)
        }
        val stream = ByteArrayOutputStream()
        scaled.compress(format, quality, stream)
        if (scaled !== original && !original.isRecycled) {
            original.recycle()
        }
        scaled.recycle()
        val encoded = Base64.encodeToString(stream.toByteArray(), Base64.NO_WRAP)
        return "data:$outputMimeType;base64,$encoded"
    }

    fun decodeImageDataUrl(dataUrl: String): ByteArray? {
        val prefixIndex = dataUrl.indexOf("base64,")
        if (!dataUrl.startsWith("data:image") || prefixIndex == -1) return null
        return runCatching {
            Base64.decode(dataUrl.substring(prefixIndex + 7), Base64.DEFAULT)
        }.getOrNull()
    }

    private fun calculateInSampleSize(width: Int, height: Int, maxEdge: Int): Int {
        if (width <= 0 || height <= 0) {
            return 1
        }

        var sampleSize = 1
        while (max(width / sampleSize, height / sampleSize) > maxEdge * 2) {
            sampleSize *= 2
        }
        return sampleSize.coerceAtLeast(1)
    }

    private fun scaledSize(width: Int, height: Int, maxEdge: Int): Pair<Int, Int> {
        if (width <= 0 || height <= 0) {
            return maxEdge to maxEdge
        }
        val scale = min(maxEdge.toFloat() / width, maxEdge.toFloat() / height).coerceAtMost(1f)
        val targetWidth = (width * scale).toInt().coerceAtLeast(1)
        val targetHeight = (height * scale).toInt().coerceAtLeast(1)
        return targetWidth to targetHeight
    }

    private const val LOCAL_PREVIEW_MAX_EDGE = 1024
    private const val LOCAL_PREVIEW_QUALITY = 82
    private const val ANNOUNCEMENT_PREVIEW_MAX_CHARS = 460

    private data class PreviewCandidate(
        val maxEdge: Int,
        val quality: Int,
    )

    private val ANNOUNCEMENT_PREVIEW_CANDIDATES = listOf(
        PreviewCandidate(maxEdge = 40, quality = 34),
        PreviewCandidate(maxEdge = 32, quality = 28),
        PreviewCandidate(maxEdge = 24, quality = 22),
    )
}
