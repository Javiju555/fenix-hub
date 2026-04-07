package com.fenixhub.mobile.util

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.util.Base64
import java.io.ByteArrayOutputStream
import kotlin.math.min
import kotlin.math.max

object PreviewUtils {
    fun textPreview(text: String): String = text.trim().take(80)

    fun imagePreviewDataUrl(bytes: ByteArray, mimeType: String? = null): String {
        val isLosslessSource = mimeType.equals("image/png", ignoreCase = true)
        return imagePreviewDataUrl(
            bytes = bytes,
            maxEdge = LOCAL_PREVIEW_MAX_EDGE,
            quality = if (isLosslessSource) 100 else LOCAL_PREVIEW_QUALITY,
            format = if (isLosslessSource) Bitmap.CompressFormat.PNG else Bitmap.CompressFormat.WEBP_LOSSY,
            mimeType = if (isLosslessSource) "image/png" else "image/webp",
        )
    }

    fun imageAnnouncementPreviewDataUrl(bytes: ByteArray): String {
        ANNOUNCEMENT_PREVIEW_CANDIDATES.forEach { candidate ->
            val preview = imagePreviewDataUrl(
                bytes = bytes,
                maxEdge = candidate.maxEdge,
                quality = candidate.quality,
                format = Bitmap.CompressFormat.WEBP_LOSSY,
                mimeType = "image/webp",
            )
            if (preview.length <= ANNOUNCEMENT_PREVIEW_MAX_CHARS) {
                return preview
            }
        }

        val fallback = ANNOUNCEMENT_PREVIEW_CANDIDATES.last()
        return imagePreviewDataUrl(
            bytes = bytes,
            maxEdge = fallback.maxEdge,
            quality = fallback.quality,
            format = Bitmap.CompressFormat.WEBP_LOSSY,
            mimeType = "image/webp",
        )
    }

    private fun imagePreviewDataUrl(
        bytes: ByteArray,
        maxEdge: Int,
        quality: Int,
        format: Bitmap.CompressFormat,
        mimeType: String,
    ): String {
        val bounds = BitmapFactory.Options().apply {
            inJustDecodeBounds = true
        }
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)

        val original = BitmapFactory.decodeByteArray(
            bytes,
            0,
            bytes.size,
            BitmapFactory.Options().apply {
                inSampleSize = calculateInSampleSize(bounds.outWidth, bounds.outHeight, maxEdge)
            },
        )
            ?: return "data:$mimeType;base64,"

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
        return "data:$mimeType;base64,$encoded"
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

    private const val LOCAL_PREVIEW_MAX_EDGE = 2048
    private const val LOCAL_PREVIEW_QUALITY = 92
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
