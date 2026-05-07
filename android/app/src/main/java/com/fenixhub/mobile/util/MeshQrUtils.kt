package com.fenixhub.mobile.util

import android.graphics.Bitmap
import android.graphics.Color
import com.google.zxing.BarcodeFormat
import com.google.zxing.qrcode.QRCodeWriter

object MeshQrUtils {

    private const val QR_SCHEME = "fenixhub://mesh"
    private const val TOKEN_VALID_MS = 60_000L

    data class QrInvite(
        val uri: String,
        val token: String,
        val expiresAt: Long,
    )

    fun generateInvite(hostBleMac: String): QrInvite {
        val token = java.security.SecureRandom().let { rng ->
            ByteArray(16).also { rng.nextBytes(it) }
                .joinToString("") { "%02x".format(it) }
        }
        val expiresAt = System.currentTimeMillis() + TOKEN_VALID_MS
        val uri = "$QR_SCHEME?host_ble=${hostBleMac.replace(":", "-")}&token=$token&exp=$expiresAt"
        return QrInvite(uri = uri, token = token, expiresAt = expiresAt)
    }

    fun parseInviteUri(uri: String): Pair<String, String>? {
        if (!uri.startsWith(QR_SCHEME)) return null
        val params = uri.substringAfter("?").split("&").associate {
            val (k, v) = it.split("=")
            k to v
        }
        val mac   = params["host_ble"]?.replace("-", ":") ?: return null
        val token = params["token"] ?: return null
        val exp   = params["exp"]?.toLongOrNull() ?: return null
        if (System.currentTimeMillis() > exp) return null
        return mac to token
    }

    fun isTokenValid(token: String, expiresAt: Long): Boolean =
        System.currentTimeMillis() <= expiresAt

    fun generateQrBitmap(uri: String, sizePx: Int = 512): Bitmap {
        val bits = QRCodeWriter().encode(uri, BarcodeFormat.QR_CODE, sizePx, sizePx)
        val bmp = Bitmap.createBitmap(sizePx, sizePx, Bitmap.Config.RGB_565)
        for (x in 0 until sizePx) for (y in 0 until sizePx) {
            bmp.setPixel(x, y, if (bits[x, y]) Color.BLACK else Color.WHITE)
        }
        return bmp
    }
}
