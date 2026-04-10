package com.fenixhub.mobile.network

import com.fenixhub.mobile.model.AppSettings
import com.fenixhub.mobile.model.PeerContent
import com.fenixhub.mobile.model.PulledContent
import com.fenixhub.mobile.util.CryptoUtils
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import android.util.Log
import okhttp3.OkHttpClient
import okhttp3.Request
import java.net.URLEncoder
import java.util.concurrent.TimeUnit

class FenixHttpClient {
    private val client = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        // Large files at LAN speeds can take 30–60s — don't timeout mid-transfer.
        .readTimeout(120, TimeUnit.SECONDS)
        .writeTimeout(30, TimeUnit.SECONDS)
        .build()

    suspend fun pullContent(peer: PeerContent, settings: AppSettings): Result<PulledContent> {
        return withContext(Dispatchers.IO) {
            runCatching {
                val authHeader = CryptoUtils.hmacSha256Hex(
                    settings.macKeyBytes(),
                    peer.announcement.contentId.toByteArray(Charsets.UTF_8),
                )

                val request = Request.Builder()
                    .url(
                        "http://${urlHost(peer.peerIp)}:${peer.port}/content/${
                            URLEncoder.encode(peer.announcement.contentId, "UTF-8")
                        }",
                    )
                    .header("X-FenixHub-Auth", authHeader)
                    .get()
                    .build()

                val startMs = System.currentTimeMillis()
                Log.d(TAG, "Pulling ${peer.announcement.contentId} from ${peer.peerIp}:${peer.port}")

                client.newCall(request).execute().use { response ->
                    if (!response.isSuccessful) {
                        error("HTTP ${response.code}")
                    }
                    val body = response.body ?: error("Empty response body")
                    val encrypted = response.header(ENCRYPTED_HEADER) == "1"
                    val rawBytes = body.bytes()
                    val recvMs = System.currentTimeMillis() - startMs
                    Log.d(TAG, "Received ${rawBytes.size / 1024} KB in ${recvMs}ms")

                    val decryptStart = System.currentTimeMillis()
                    val bytes = if (encrypted) {
                        CryptoUtils.decryptAesGcm(settings.encKeyBytes(), rawBytes)
                    } else {
                        rawBytes
                    }
                    val decryptMs = System.currentTimeMillis() - decryptStart
                    Log.i(TAG, "Pull complete: ${bytes.size / 1024} KB — recv ${recvMs}ms, decrypt ${decryptMs}ms, total ${System.currentTimeMillis() - startMs}ms")

                    PulledContent(
                        bytes = bytes,
                        mimeType = body.contentType()?.toString(),
                        fileName = fileNameFromDisposition(response.header("Content-Disposition")),
                    )
                }
            }
        }
    }

    private companion object {
        const val TAG = "FenixHubClient"
        const val ENCRYPTED_HEADER = "X-FenixHub-Encrypted"
    }

    private fun urlHost(host: String): String = if (host.contains(':')) "[$host]" else host

    private fun fileNameFromDisposition(contentDisposition: String?): String? {
        return contentDisposition
            ?.split(';')
            ?.map(String::trim)
            ?.firstOrNull { it.startsWith("filename=") }
            ?.substringAfter('=')
            ?.trim('"')
            ?.takeIf { it.isNotBlank() }
    }
}
