package com.fenixhub.mobile.network

import com.fenixhub.mobile.model.AppSettings
import com.fenixhub.mobile.model.PeerContent
import com.fenixhub.mobile.model.PulledContent
import com.fenixhub.mobile.util.CryptoUtils
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.net.URLEncoder

class FenixHttpClient {
    private val client = OkHttpClient()

    suspend fun pullContent(peer: PeerContent, settings: AppSettings): Result<PulledContent> {
        return withContext(Dispatchers.IO) {
            runCatching {
                val authHeader = CryptoUtils.hmacSha256Hex(
                    settings.groupKeyBytes(),
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

                client.newCall(request).execute().use { response ->
                    if (!response.isSuccessful) {
                        error("HTTP ${response.code}")
                    }
                    val body = response.body ?: error("Empty response body")
                    PulledContent(
                        bytes = body.bytes(),
                        mimeType = body.contentType()?.toString(),
                        fileName = fileNameFromDisposition(response.header("Content-Disposition")),
                    )
                }
            }
        }
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
