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
import io.ktor.server.request.path
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
import kotlin.math.abs

class FenixHttpServer(
    private val settingsStore: SettingsStore,
    private val repository: ContentRepository,
) {
    @Volatile
    var activePort: Int? = null
        private set

    @Volatile var onMeshHello: ((deviceName: String, remoteIp: String) -> Unit)? = null
    @Volatile var onMeshPing: ((remoteIp: String) -> Unit)? = null

    private var engine: ApplicationEngine? = null
    private var ephemeralEngine: ApplicationEngine? = null
    private var ephemeralPort: Int? = null
    private val replayNonceLock = Any()
    private val seenReplayNonces = linkedMapOf<String, Long>()

    fun startIfNeeded(): Int {
        activePort?.let { return it }

        val chosenPort = choosePort()
        engine = buildServer(chosenPort, ephemeralMode = false)
        engine!!.start(wait = false)
        activePort = chosenPort
        return chosenPort
    }

    fun startIfNeededEphemeral(): Int {
        ephemeralPort?.let { return it }

        val chosenPort = runCatching {
            ServerSocket(EPHEMERAL_DEFAULT_PORT).use { EPHEMERAL_DEFAULT_PORT }
        }.getOrElse {
            ServerSocket(0).use { it.localPort }
        }
        ephemeralEngine = buildServer(chosenPort, ephemeralMode = true)
        ephemeralEngine!!.start(wait = false)
        ephemeralPort = chosenPort
        Log.d(TAG, "Ephemeral server started on port $chosenPort")
        return chosenPort
    }

    private fun buildServer(port: Int, ephemeralMode: Boolean): ApplicationEngine {
        return embeddedServer(Netty, host = "0.0.0.0", port = port, configure = {
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
                    if (ephemeralMode) {
                        call.serveContentEphemeral()
                    } else {
                        call.serveContentAuthenticated()
                    }
                }

                post("/auth/challenge") {
                    if (ephemeralMode) {
                        call.respond(HttpStatusCode.NotFound)
                        return@post
                    }
                    call.serveAuthChallenge()
                }

                post("/mesh/hello") {
                    val remoteIp = runCatching { call.request.local.remoteHost }.getOrElse { "" }
                    val body = runCatching { JSONObject(call.receiveText()) }.getOrNull()
                    val deviceName = body?.optString("device_name").orEmpty()
                    Log.d(TAG, "/mesh/hello from=$remoteIp deviceName='$deviceName' valid=${remoteIp.isNotBlank() && deviceName.isNotBlank()}")
                    if (remoteIp.isNotBlank() && deviceName.isNotBlank()) {
                        onMeshHello?.invoke(deviceName, remoteIp)
                    }
                    call.respond(HttpStatusCode.NoContent)
                }

                get("/mesh/ping") {
                    val remoteIp = runCatching { call.request.local.remoteHost }.getOrElse { "" }
                    Log.d(TAG, "/mesh/ping from=$remoteIp")
                    if (remoteIp.isNotBlank()) onMeshPing?.invoke(remoteIp)
                    call.respond(HttpStatusCode.NoContent)
                }
            }
        }
    }

    private suspend fun io.ktor.server.application.ApplicationCall.serveContentEphemeral() {
        val contentId = parameters["content_id"]
        if (contentId.isNullOrBlank()) {
            respond(HttpStatusCode.BadRequest)
            return
        }

        val item = repository.getLocalContent(contentId)
        if (item == null || !item.isPublished) {
            respond(HttpStatusCode.NotFound)
            return
        }

        val contentType = runCatching { ContentType.parse(item.mimeType) }
            .getOrElse { ContentType.Application.OctetStream }
        item.fileName?.let { fileName ->
            response.headers.append(
                HttpHeaders.ContentDisposition,
                "inline; filename=\"${fileName.replace("\"", "_")}\"",
            )
        }

        val file = File(item.cachePath)
        if (!file.exists()) {
            respond(HttpStatusCode.NotFound)
            return
        }

        val fileSizeBytes = file.length()
        Log.d(TAG, "Ephemeral serving $contentId — ${fileSizeBytes / 1024} KB (no encryption)")
        val startMs = System.currentTimeMillis()

        respondBytesWriter(contentType = contentType) {
            var totalRead = 0L
            val buf = ByteArray(CHUNK_SIZE)
            file.inputStream().buffered(CHUNK_SIZE).use { input ->
                while (true) {
                    val read = withContext(Dispatchers.IO) { input.read(buf) }
                    if (read == -1) break
                    writeFully(buf, 0, read)
                    totalRead += read
                }
            }
            val elapsedMs = System.currentTimeMillis() - startMs
            Log.i(TAG, "Ephemeral served $contentId — ${totalRead / 1024} KB in ${elapsedMs}ms")
        }
    }

    private suspend fun io.ktor.server.application.ApplicationCall.serveContentAuthenticated() {
        val settings = currentSettingsOrRespond() ?: return

        val contentId = parameters["content_id"]
        if (contentId.isNullOrBlank()) {
            respond(HttpStatusCode.BadRequest)
            return
        }

        val remoteIp = runCatching { request.local.remoteHost }.getOrElse { "?" }
        Log.d(TAG, "GET /content/$contentId from=$remoteIp authGroupId=${settings.groupId.take(8)}")
        if (!isAuthorized(
                settings = settings,
                method = "GET",
                canonicalPath = "/content/$contentId",
                bodyBytes = ByteArray(0),
            )) {
            Log.w(TAG, "GET /content/$contentId UNAUTHORIZED from=$remoteIp")
            respond(HttpStatusCode.Unauthorized)
            return
        }

        val item = repository.getLocalContent(contentId)
        if (item == null || !item.isPublished) {
            respond(HttpStatusCode.NotFound)
            return
        }

        val contentType = runCatching { ContentType.parse(item.mimeType) }
            .getOrElse { ContentType.Application.OctetStream }
        item.fileName?.let { fileName ->
            response.headers.append(
                HttpHeaders.ContentDisposition,
                "inline; filename=\"${fileName.replace("\"", "_")}\"",
            )
        }

        val file = File(item.cachePath)
        if (!file.exists()) {
            respond(HttpStatusCode.NotFound)
            return
        }

        val encKey = settings.encKeyBytes()
        val originalSize = file.length()
        // totalChunks fits in Int for any realistic file (2^31 * 4MB = 8 PB)
        val totalChunks = if (originalSize == 0L) 0 else
            ((originalSize + FNX2_CHUNK_SIZE - 1) / FNX2_CHUNK_SIZE).toInt()
        val baseNonce = ByteArray(NONCE_SIZE).also(secureRandom::nextBytes)
        val secretKey = SecretKeySpec(encKey, "AES")

        Log.d(TAG, "Serving $contentId — ${originalSize / 1024} KB, $totalChunks FNX2 chunks")
        val startMs = System.currentTimeMillis()

        // FNX2 v2: per-chunk AES-GCM. Desktop stream-decrypts
        // directly to disk — no full-file buffer needed on either side.
        response.headers.append(ENCRYPTED_HEADER, "2")
        respondBytesWriter(contentType = contentType) {
            // ── FNX2 header (29 bytes) ─────────────────────────────────────
            writeFully(FNX2_MAGIC)                  // 4 bytes
            writeFully(baseNonce)                   // 12 bytes
            writeFully(intToBE(totalChunks))        // 4 bytes
            writeFully(longToBE(originalSize))      // 8 bytes
            writeFully(byteArrayOf(FNX2_COMPRESSION_NONE.toByte())) // 1 byte

            if (totalChunks == 0) return@respondBytesWriter

            // ── Chunks ────────────────────────────────────────────────────
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            val chunkNonce = ByteArray(NONCE_SIZE)
            val buf = ByteArray(FNX2_CHUNK_SIZE)
            val encryptedBuf = ByteArray(FNX2_CHUNK_SIZE + FNX2_GCM_TAG_BYTES)

            file.inputStream().buffered(FNX2_CHUNK_SIZE).use { input ->
                for (chunkIndex in 0 until totalChunks) {
                    // Read exactly FNX2_CHUNK_SIZE bytes (or fewer for the last chunk)
                    val read = withContext(Dispatchers.IO) {
                        var total = 0
                        while (total < FNX2_CHUNK_SIZE) {
                            val n = input.read(buf, total, FNX2_CHUNK_SIZE - total)
                            if (n == -1) break
                            total += n
                        }
                        total
                    }
                    if (read == 0) break

                    // Per-chunk nonce: XOR base_nonce[4..12] with chunk index (BE u64)
                    baseNonce.copyInto(chunkNonce)
                    val idx = chunkIndex.toLong()
                    for (i in 0 until 8) {
                        chunkNonce[4 + i] = (chunkNonce[4 + i].toInt() xor
                            ((idx ushr ((7 - i) * 8)).toInt() and 0xff)).toByte()
                    }

                    cipher.init(Cipher.ENCRYPT_MODE, secretKey, GCMParameterSpec(GCM_TAG_BITS, chunkNonce))
                    val encryptedLen = cipher.doFinal(buf, 0, read, encryptedBuf, 0)
                    writeFully(encryptedBuf, 0, encryptedLen)
                }
            }

            val elapsedMs = System.currentTimeMillis() - startMs
            val speedKBs = if (elapsedMs > 0) originalSize / elapsedMs else 0
            Log.i(TAG, "Served $contentId — ${originalSize / 1024} KB in ${elapsedMs}ms ($speedKBs KB/s) FNX2 v2")
        }
    }

    private suspend fun io.ktor.server.application.ApplicationCall.serveAuthChallenge() {
        val settings = currentSettingsOrRespond() ?: return
        val rawBody = receiveText()
        val bodyBytes = rawBody.toByteArray(Charsets.UTF_8)
        val nonce = runCatching {
            JSONObject(rawBody).optString("nonce").trim()
        }.getOrNull()
        if (nonce.isNullOrBlank()) {
            respond(HttpStatusCode.BadRequest)
            return
        }

        if (!isAuthorized(
                settings = settings,
                method = "POST",
                canonicalPath = "/auth/challenge",
                bodyBytes = bodyBytes,
            )) {
            respond(HttpStatusCode.Unauthorized)
            return
        }

        val hmac = CryptoUtils.hmacSha256Hex(
            settings.macKeyBytes(),
            nonce.toByteArray(Charsets.UTF_8),
        )
        val response = JSONObject().put("hmac", hmac).toString()
        respondText(response, ContentType.Application.Json)
    }

    fun stop() {
        engine?.stop(1_000, 2_000)
        engine = null
        activePort = null
        ephemeralEngine?.stop(1_000, 2_000)
        ephemeralEngine = null
        ephemeralPort = null
    }

    val isRunning: Boolean get() = engine != null

    fun ensureRunning(): Int = startIfNeeded()

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
        method: String,
        canonicalPath: String,
        bodyBytes: ByteArray,
    ): Boolean {
        val receivedSignature = request.header(CryptoUtils.HMAC_HEADER)?.trim() ?: return false
        val timestampMs = request.header(CryptoUtils.AUTH_TIMESTAMP_HEADER)
            ?.trim()
            ?.toLongOrNull()
            ?: return false
        val nonceHex = request.header(CryptoUtils.AUTH_NONCE_HEADER)
            ?.trim()
            ?.lowercase()
            ?: return false
        val bodyHashHeader = request.header(CryptoUtils.AUTH_BODY_SHA256_HEADER)
            ?.trim()
            ?.lowercase()
            ?: return false

        if (!CryptoUtils.isValidAuthNonceHex(nonceHex)) {
            return false
        }

        val expectedBodyHash = if (bodyBytes.isEmpty()) {
            CryptoUtils.EMPTY_BODY_SHA256_HEX
        } else {
            CryptoUtils.sha256Hex(bodyBytes)
        }
        if (!CryptoUtils.constantTimeEquals(expectedBodyHash, bodyHashHeader)) {
            return false
        }

        val nowMs = System.currentTimeMillis()
        if (abs(nowMs - timestampMs) > CryptoUtils.AUTH_MAX_SKEW_MS) {
            return false
        }

        val canonical = CryptoUtils.canonicalAuthMessage(
            method = method,
            path = canonicalPath.ifBlank { request.path() },
            groupId = settings.groupId,
            timestampMs = timestampMs,
            nonceHex = nonceHex,
            bodySha256Hex = bodyHashHeader,
        )
        val expectedSignature = CryptoUtils.hmacSha256Hex(settings.macKeyBytes(), canonical)
        if (!CryptoUtils.constantTimeEquals(expectedSignature, receivedSignature)) {
            return false
        }

        return markReplayNonce(nonceHex, nowMs)
    }

    private fun markReplayNonce(nonceHex: String, nowMs: Long): Boolean {
        synchronized(replayNonceLock) {
            val minTimestamp = nowMs - CryptoUtils.AUTH_MAX_SKEW_MS
            val staleKeys = seenReplayNonces
                .filterValues { timestamp -> timestamp < minTimestamp }
                .keys
                .toList()
            staleKeys.forEach(seenReplayNonces::remove)

            if (seenReplayNonces.containsKey(nonceHex)) {
                return false
            }

            if (seenReplayNonces.size >= MAX_REPLAY_CACHE_ENTRIES) {
                val firstKey = seenReplayNonces.keys.firstOrNull()
                if (firstKey != null) {
                    seenReplayNonces.remove(firstKey)
                }
            }

            seenReplayNonces[nonceHex] = nowMs
            return true
        }
    }

    private fun intToBE(value: Int): ByteArray = byteArrayOf(
        (value ushr 24).toByte(),
        (value ushr 16).toByte(),
        (value ushr 8).toByte(),
        value.toByte(),
    )

    private fun longToBE(value: Long): ByteArray {
        val result = ByteArray(8)
        for (i in 0 until 8) result[i] = (value ushr ((7 - i) * 8)).toByte()
        return result
    }

    private companion object {
        const val TAG = "FenixHubServer"
        const val DEFAULT_PORT = 8765
        const val EPHEMERAL_DEFAULT_PORT = 8766
        const val ENCRYPTED_HEADER = "X-FenixHub-Encrypted"
        const val NONCE_SIZE = 12
        const val GCM_TAG_BITS = 128
        const val CHUNK_SIZE = 256 * 1024       // 256 KB (ephemeral plaintext streaming)
        const val FNX2_CHUNK_SIZE = 4 * 1024 * 1024 // 4 MB — fewer AES-GCM init/doFinal calls for video
        const val FNX2_GCM_TAG_BYTES = 16
        const val FNX2_COMPRESSION_NONE = 0x00
        const val MAX_REPLAY_CACHE_ENTRIES = 8_192
        val secureRandom = SecureRandom()
        val FNX2_MAGIC = byteArrayOf(
            'F'.code.toByte(), 'N'.code.toByte(),
            'X'.code.toByte(), '2'.code.toByte(),
        )
    }
}
