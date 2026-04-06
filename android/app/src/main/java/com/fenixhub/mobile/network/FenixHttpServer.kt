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
import io.ktor.server.response.respondBytes
import io.ktor.server.response.respondText
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import io.ktor.server.routing.routing
import java.io.File
import java.net.ServerSocket
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
        val server = embeddedServer(Netty, host = "0.0.0.0", port = chosenPort) {
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

                    val payload = runCatching { File(item.cachePath).readBytes() }
                        .getOrElse {
                            call.respond(HttpStatusCode.NotFound)
                            return@get
                        }

                    val encryptedPayload = runCatching {
                        CryptoUtils.encryptAesGcm(settings.encKeyBytes(), payload)
                    }.getOrElse {
                        call.respond(HttpStatusCode.InternalServerError)
                        return@get
                    }

                    call.response.headers.append(ENCRYPTED_HEADER, "1")
                    call.respondBytes(encryptedPayload, contentType = contentType)
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
        const val DEFAULT_PORT = 8765
        const val HMAC_HEADER = "X-FenixHub-Auth"
        const val ENCRYPTED_HEADER = "X-FenixHub-Encrypted"
    }
}
