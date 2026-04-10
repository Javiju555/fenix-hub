package com.fenixhub.mobile.network

import com.fenixhub.mobile.data.ContentRepository
import com.fenixhub.mobile.data.SettingsStore
import com.fenixhub.mobile.model.AppSettings
import com.fenixhub.mobile.util.CryptoUtils
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.call
import io.ktor.server.engine.ApplicationEngine
import io.ktor.server.engine.embeddedServer
import io.ktor.server.netty.Netty
import io.ktor.server.request.receiveText
import io.ktor.server.request.header
import io.ktor.server.response.respond
import io.ktor.server.response.respondBytesWriter
import io.ktor.server.response.respondText
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import io.ktor.server.routing.routing
import io.ktor.utils.io.writeFully
import java.io.File
import java.net.ServerSocket
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec
import android.util.Log
import io.netty.channel.ChannelOption
import org.json.JSONObject

class FenixHttpServer(
    private val settingsStore: SettingsStore,
    private val repository: ContentRepository,
) {
    @Volatile
    var activePort: Int? = null
        private set

    private var engine: ApplicationEngine? = null

    fun startIfNeeded(): Int {
        activePort?.let { return it }

        val chosenPort = choosePort()
        val server = embeddedServer(Netty, host = "0.0.0.0", port = chosenPort, configure = {
            // Increase TCP send buffer so the kernel can pipeline large file transfers
            // without stalling — default Android SO_SNDBUF (~128 KB) causes ~4 MB/s.
            httpServerCodec = {
                io.netty.handler.codec.http.HttpServerCodec()
            }
            configureBootstrap = {
                childOption(ChannelOption.SO_SNDBUF, 2 * 1024 * 1024)
                childOption(ChannelOption.SO_RCVBUF, 2 * 1024 * 1024)
            }
        }) {
            routing {
                get("/content/{content_id}") {
                    val settings = call.currentSettingsOrRespond() ?: return@get

                    val contentId = call.parameters["content_id"]
                    if (contentId.isNullOrBlank()) {
                        call.respond(HttpStatusCode.BadRequest)
                        return@get
                    }

                    if (!call.isAuthorized(settings, contentId.toByteArray(Charsets.UTF_8))) {
                        call.respond(HttpStatusCode.Unauthorized)
                        return@get
                    }

                    val item = repository.getLocalContent(contentId)
                    if (item == null || !item.isPublished) {
                        call.respond(HttpStatusCode.NotFound)
                        return@get
                    }

                    val contentType = runCatching { ContentType.parse(item.mimeType) }
                        .getOrElse { ContentType.Application.OctetStream }
                    item.fileName?.let { fileName ->
                        call.response.headers.append(
                            HttpHeaders.ContentDisposition,
                            "inline; filename=\"${fileName.replace("\"", "_")}\"",
                        )
                    }

                    val file = File(item.cachePath)
                    if (!file.exists()) {
                        call.respond(HttpStatusCode.NotFound)
                        return@get
                    }

                    // Stream-encrypt in 64 KB chunks: nonce → cipher.update() per chunk
                    // → cipher.doFinal() for the GCM tag. Never holds the full file in RAM.
                    val encKey = settings.encKeyBytes()
                    val nonce = ByteArray(NONCE_SIZE).also(secureRandom::nextBytes)
                    val cipher = Cipher.getInstance("AES/GCM/NoPadding").also {
                        it.init(
                            Cipher.ENCRYPT_MODE,
                            SecretKeySpec(encKey, "AES"),
                            GCMParameterSpec(GCM_TAG_BITS, nonce),
                        )
                    }

                    val fileSizeBytes = file.length()
                    Log.d(TAG, "Serving $contentId — ${fileSizeBytes / 1024} KB encrypted streaming")
                    val startMs = System.currentTimeMillis()

                    call.response.headers.append(ENCRYPTED_HEADER, "1")
                    call.respondBytesWriter(contentType = contentType) {
                        // Write nonce first.
                        writeFully(nonce)
                        var totalRead = 0L
                        val buf = ByteArray(CHUNK_SIZE)
                        // Read + encrypt on IO dispatcher, then hand each chunk back to
                        // the Ktor channel via writeFully (which suspends on backpressure).
                        file.inputStream().buffered(CHUNK_SIZE).use { input ->
                            while (true) {
                                val read = withContext(Dispatchers.IO) { input.read(buf) }
                                if (read == -1) break
                                val chunk = cipher.update(buf, 0, read) ?: continue
                                if (chunk.isNotEmpty()) writeFully(chunk)
                                totalRead += read
                            }
                        }
                        // GCM auth tag.
                        val final = withContext(Dispatchers.IO) { cipher.doFinal() }
                        if (final != null && final.isNotEmpty()) writeFully(final)
                        val elapsedMs = System.currentTimeMillis() - startMs
                        val speedKBs = if (elapsedMs > 0) totalRead / elapsedMs else 0
                        Log.i(TAG, "Served $contentId — ${totalRead / 1024} KB in ${elapsedMs}ms (${speedKBs} KB/s)")
                    }
                }

                post("/auth/challenge") {
                    val settings = call.currentSettingsOrRespond() ?: return@post
                    val rawBody = call.receiveText()
                    val nonce = runCatching {
                        JSONObject(rawBody).optString("nonce").trim()
                    }.getOrNull()
                    if (nonce.isNullOrBlank()) {
                        call.respond(HttpStatusCode.BadRequest)
                        return@post
                    }

                    if (!call.isAuthorized(settings, CryptoUtils.authChallengeHeaderMessage(nonce))) {
                        call.respond(HttpStatusCode.Unauthorized)
                        return@post
                    }

                    val hmac = CryptoUtils.hmacSha256Hex(
                        settings.macKeyBytes(),
                        nonce.toByteArray(Charsets.UTF_8),
                    )
                    val response = JSONObject().put("hmac", hmac).toString()
                    call.respondText(response, ContentType.Application.Json)
                }
            }
        }

        server.start(wait = false)
        engine = server
        activePort = chosenPort
        return chosenPort
    }

    fun stop() {
        engine?.stop(1_000, 2_000)
        engine = null
        activePort = null
    }

    private fun choosePort(): Int {
        return runCatching {
            ServerSocket(DEFAULT_PORT).use { DEFAULT_PORT }
        }.getOrElse {
            ServerSocket(0).use { it.localPort }
        }
    }

    private suspend fun io.ktor.server.application.ApplicationCall.currentSettingsOrRespond(): AppSettings? {
        val settings = settingsStore.current()
        if (!settings.configured) {
            respond(HttpStatusCode.ServiceUnavailable)
            return null
        }
        return settings
    }

    private fun io.ktor.server.application.ApplicationCall.isAuthorized(
        settings: AppSettings,
        message: ByteArray,
    ): Boolean {
        val receivedHeader = request.header(HMAC_HEADER) ?: return false
        val expected = CryptoUtils.hmacSha256Hex(settings.macKeyBytes(), message)
        return CryptoUtils.constantTimeEquals(expected, receivedHeader)
    }

    private companion object {
        const val TAG = "FenixHubServer"
        const val DEFAULT_PORT = 8765
        const val HMAC_HEADER = "X-FenixHub-Auth"
        const val ENCRYPTED_HEADER = "X-FenixHub-Encrypted"
        const val NONCE_SIZE = 12
        const val GCM_TAG_BITS = 128
        const val CHUNK_SIZE = 256 * 1024  // 256 KB
        val secureRandom = SecureRandom()
    }
}
