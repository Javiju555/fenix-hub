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
import okio.BufferedSource
import java.io.ByteArrayOutputStream
import java.security.GeneralSecurityException
import java.net.URLEncoder
import java.util.concurrent.TimeUnit
import javax.crypto.Cipher
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

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
                val canonicalPath = "/content/${peer.announcement.contentId}"
                val timestampMs = System.currentTimeMillis()
                val nonceHex = CryptoUtils.newAuthNonceHex()
                val bodySha256Hex = CryptoUtils.EMPTY_BODY_SHA256_HEX
                val canonicalAuth = CryptoUtils.canonicalAuthMessage(
                    method = "GET",
                    path = canonicalPath,
                    groupId = settings.groupId,
                    timestampMs = timestampMs,
                    nonceHex = nonceHex,
                    bodySha256Hex = bodySha256Hex,
                )
                val authHeader = CryptoUtils.hmacSha256Hex(settings.macKeyBytes(), canonicalAuth)

                val request = Request.Builder()
                    .url(
                        "http://${urlHost(peer.peerIp)}:${peer.port}/content/${
                            URLEncoder.encode(peer.announcement.contentId, "UTF-8")
                        }",
                    )
                    .header(CryptoUtils.HMAC_HEADER, authHeader)
                    .header(CryptoUtils.AUTH_TIMESTAMP_HEADER, timestampMs.toString())
                    .header(CryptoUtils.AUTH_NONCE_HEADER, nonceHex)
                    .header(CryptoUtils.AUTH_BODY_SHA256_HEADER, bodySha256Hex)
                    .get()
                    .build()

                val startMs = System.currentTimeMillis()
                Log.d(TAG, "Pulling ${peer.announcement.contentId} from ${peer.peerIp}:${peer.port}")

                client.newCall(request).execute().use { response ->
                    if (!response.isSuccessful) error("HTTP ${response.code}")
                    val body = response.body ?: error("Empty response body")
                    val encryptedHeader = response.header(ENCRYPTED_HEADER)
                    val mimeType = body.contentType()?.toString()
                    val fileName = fileNameFromDisposition(response.header("Content-Disposition"))

                    val bytes = when (encryptedHeader) {
                        "2" -> {
                            // FNX2 v2: stream-decrypt chunk by chunk — never holds full ciphertext in RAM
                            streamDecryptFnx2(body.source(), settings.encKeyBytes(), startMs)
                        }
                        "1" -> {
                            val rawBytes = body.bytes()
                            val recvMs = System.currentTimeMillis() - startMs
                            val result = CryptoUtils.decryptAesGcm(settings.encKeyBytes(), rawBytes)
                            Log.i(TAG, "Pull complete (v1): ${result.size / 1024} KB — ${recvMs}ms")
                            result
                        }
                        else -> {
                            val rawBytes = body.bytes()
                            Log.i(TAG, "Pull complete (legacy): ${rawBytes.size / 1024} KB — ${System.currentTimeMillis() - startMs}ms")
                            rawBytes
                        }
                    }

                    PulledContent(bytes = bytes, mimeType = mimeType, fileName = fileName)
                }
            }
        }
    }

    /**
     * Pull de contenido para modo efímero (sin AppSettings).
     * WiFi Direct ya proporciona seguridad en la capa de transporte.
     * Usa el puerto efímero por defecto (8766).
     */
    suspend fun pullContentEphemeral(
        targetIp: String,
        contentId: String,
        port: Int = EPHEMERAL_DEFAULT_PORT,
    ): Result<PulledContent> {
        return withContext(Dispatchers.IO) {
            runCatching {
                val request = Request.Builder()
                    .url("http://${urlHost(targetIp)}:$port/content/${URLEncoder.encode(contentId, "UTF-8")}")
                    .get()
                    .build()

                val startMs = System.currentTimeMillis()
                Log.d(TAG, "Ephemeral pull $contentId from $targetIp:$port")

                client.newCall(request).execute().use { response ->
                    if (!response.isSuccessful) error("HTTP ${response.code}")
                    val body = response.body ?: error("Empty response body")
                    val encryptedHeader = response.header(ENCRYPTED_HEADER)
                    val mimeType = body.contentType()?.toString()
                    val fileName = fileNameFromDisposition(response.header("Content-Disposition"))

                    // Ephemeral mode: WiFi Direct encrypts at link layer, but if the server
                    // happens to send FNX2 v2 (e.g. reusing the LAN server), stream-decrypt it.
                    val bytes = when (encryptedHeader) {
                        "2" -> streamDecryptFnx2(body.source(), ByteArray(32), startMs)
                        else -> {
                            val rawBytes = body.bytes()
                            Log.i(TAG, "Ephemeral pull complete: ${rawBytes.size / 1024} KB — ${System.currentTimeMillis() - startMs}ms")
                            rawBytes
                        }
                    }

                    PulledContent(bytes = bytes, mimeType = mimeType, fileName = fileName)
                }
            }
        }
    }

    private companion object {
        const val TAG = "FenixHubClient"
        const val ENCRYPTED_HEADER = "X-FenixHub-Encrypted"
        const val EPHEMERAL_DEFAULT_PORT = 8766
        const val FNX2_HEADER_SIZE = 29
        const val FNX2_CHUNK_SIZE = 64 * 1024
        const val FNX2_GCM_TAG_BYTES = 16
        const val GCM_TAG_BITS = 128
        val FNX2_MAGIC = byteArrayOf('F'.code.toByte(), 'N'.code.toByte(), 'X'.code.toByte(), '2'.code.toByte())
    }

    /**
     * Stream-decrypt an FNX2 v2 response body chunk by chunk.
     *
     * Unlike the old approach (body.bytes() → decrypt whole buffer), this reads
     * each 65KB chunk from the network and decrypts it immediately. Peak RAM is
     * ~originalSize (plaintext output) instead of ~2×originalSize (ciphertext + plaintext).
     */
    private fun streamDecryptFnx2(source: BufferedSource, encKey: ByteArray, startMs: Long): ByteArray {
        require(encKey.size == 32) { "AES key must be 32 bytes" }

        // Read and validate 29-byte FNX2 header
        val header = source.readByteArray(FNX2_HEADER_SIZE.toLong())
        if (header.size < FNX2_HEADER_SIZE) error("FNX2 header too short: ${header.size} bytes")
        for (i in 0 until 4) {
            if (header[i] != FNX2_MAGIC[i]) error("Invalid FNX2 magic")
        }

        val baseNonce = header.copyOfRange(4, 16)
        val totalChunks = readIntBE(header, 16)
        val originalSize = readLongBE(header, 20)

        if (totalChunks < 0) error("Invalid FNX2 total_chunks: $totalChunks")
        if (totalChunks == 0) {
            Log.i(TAG, "Pull complete (FNX2 stream): 0 chunks — ${System.currentTimeMillis() - startMs}ms")
            return ByteArray(0)
        }

        val fullChunkEncryptedLen = FNX2_CHUNK_SIZE + FNX2_GCM_TAG_BYTES
        val expectedPlaintext = originalSize
            .coerceAtMost((totalChunks.toLong() * FNX2_CHUNK_SIZE).coerceAtMost(Int.MAX_VALUE.toLong()))
            .toInt()
        val plaintextOut = ByteArrayOutputStream(expectedPlaintext.coerceAtLeast(FNX2_CHUNK_SIZE))

        val secretKey = SecretKeySpec(encKey, "AES")
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        val chunkNonce = baseNonce.copyOf()

        for (chunkIndex in 0 until totalChunks) {
            // Non-last chunks are always fullChunkEncryptedLen; last chunk reads whatever remains
            val chunkBytes = if (chunkIndex < totalChunks - 1) {
                source.readByteArray(fullChunkEncryptedLen.toLong())
            } else {
                source.readByteArray()
            }

            if (chunkBytes.size < FNX2_GCM_TAG_BYTES) {
                error("FNX2 chunk $chunkIndex too short: ${chunkBytes.size} bytes")
            }

            baseNonce.copyInto(chunkNonce)
            val idx = chunkIndex.toLong()
            for (i in 0 until 8) {
                chunkNonce[4 + i] = (chunkNonce[4 + i].toInt() xor ((idx ushr ((7 - i) * 8)).toInt() and 0xff)).toByte()
            }

            try {
                cipher.init(Cipher.DECRYPT_MODE, secretKey, GCMParameterSpec(GCM_TAG_BITS, chunkNonce))
                plaintextOut.write(cipher.doFinal(chunkBytes))
            } catch (e: GeneralSecurityException) {
                throw IllegalStateException("FNX2 chunk $chunkIndex decryption failed", e)
            }
        }

        val totalMs = System.currentTimeMillis() - startMs
        val kb = plaintextOut.size() / 1024
        val mbps = if (totalMs > 0) (plaintextOut.size().toLong() * 8 / totalMs / 1000.0) else 0.0
        Log.i(TAG, "Pull complete (FNX2 stream): ${kb} KB in ${totalMs}ms (%.1f Mbps)".format(mbps))

        return plaintextOut.toByteArray()
    }

    private fun readIntBE(bytes: ByteArray, offset: Int): Int {
        return ((bytes[offset].toInt() and 0xff) shl 24) or
            ((bytes[offset + 1].toInt() and 0xff) shl 16) or
            ((bytes[offset + 2].toInt() and 0xff) shl 8) or
            (bytes[offset + 3].toInt() and 0xff)
    }

    private fun readLongBE(bytes: ByteArray, offset: Int): Long {
        var value = 0L
        for (i in 0 until 8) {
            value = (value shl 8) or (bytes[offset + i].toLong() and 0xffL)
        }
        return value
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
