package com.fenixhub.mobile.util

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.util.Base64
import java.io.ByteArrayOutputStream

object PreviewUtils {
    fun textPreview(text: String): String = text.trim().take(80)

    fun imagePreviewDataUrl(bytes: ByteArray): String {
        val original = BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
            ?: return "data:image/png;base64,"

        val scaled = Bitmap.createScaledBitmap(original, 72, 72, true)
        val stream = ByteArrayOutputStream()
        scaled.compress(Bitmap.CompressFormat.JPEG, 58, stream)
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
}
