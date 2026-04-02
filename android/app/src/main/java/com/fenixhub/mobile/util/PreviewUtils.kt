package com.fenixhub.mobile.util

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.util.Base64
import java.io.ByteArrayOutputStream
import kotlin.math.max

object PreviewUtils {
    fun textPreview(text: String): String = text.trim().take(80)

    fun imagePreviewDataUrl(bytes: ByteArray): String {
        val bounds = BitmapFactory.Options().apply {
            inJustDecodeBounds = true
        }
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)

        val original = BitmapFactory.decodeByteArray(
            bytes,
            0,
            bytes.size,
            BitmapFactory.Options().apply {
                inSampleSize = calculateInSampleSize(bounds.outWidth, bounds.outHeight)
            },
        )
            ?: return "data:image/png;base64,"

        val scaled = Bitmap.createScaledBitmap(original, PREVIEW_SIZE, PREVIEW_SIZE, true)
        val stream = ByteArrayOutputStream()
        scaled.compress(Bitmap.CompressFormat.JPEG, PREVIEW_QUALITY, stream)
        if (scaled !== original) {
            original.recycle()
        }
        scaled.recycle()
        val encoded = Base64.encodeToString(stream.toByteArray(), Base64.NO_WRAP)
        return "data:image/jpeg;base64,$encoded"
    }

    fun decodeImageDataUrl(dataUrl: String): ByteArray? {
        val prefixIndex = dataUrl.indexOf("base64,")
        if (!dataUrl.startsWith("data:image") || prefixIndex == -1) return null
        return runCatching {
            Base64.decode(dataUrl.substring(prefixIndex + 7), Base64.DEFAULT)
        }.getOrNull()
    }

    private fun calculateInSampleSize(width: Int, height: Int): Int {
        if (width <= 0 || height <= 0) {
            return 1
        }

        var sampleSize = 1
        while (max(width / sampleSize, height / sampleSize) > PREVIEW_SIZE * 2) {
            sampleSize *= 2
        }
        return sampleSize.coerceAtLeast(1)
    }

    private const val PREVIEW_SIZE = 40
    private const val PREVIEW_QUALITY = 36
}
