package com.fenixhub.mobile.network

import com.github.luben.zstd.ZstdInputStream
import com.fenixhub.mobile.model.AppSettings
import com.fenixhub.mobile.model.PeerContent
import com.fenixhub.mobile.model.PulledContent
import com.fenixhub.mobile.util.CryptoUtils
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import android.util.Log
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.ByteArrayInputStream
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
                    if (!response.isSuccessful) {
                        error("HTTP ${response.code}")
                    }
                    val body = response.body ?: error("Empty response body")
                    val encryptedHeader = response.header(ENCRYPTED_HEADER)
                    val rawBytes = body.bytes()
                    val recvMs = System.currentTimeMillis() - startMs
                    Log.d(TAG, "Received ${rawBytes.size / 1024} KB in ${recvMs}ms")

                    val decryptStart = System.currentTimeMillis()
                    val bytes = when (encryptedHeader) {
                        "1" -> CryptoUtils.decryptAesGcm(settings.encKeyBytes(), rawBytes)
                        "2" -> decryptFnx2V2(settings.encKeyBytes(), rawBytes)
                        else -> rawBytes
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
                    if (!response.isSuccessful) {
                        error("HTTP ${response.code}")
                    }
                    val body = response.body ?: error("Empty response body")
                    val encryptedHeader = response.header(ENCRYPTED_HEADER)
                    val rawBytes = body.bytes()
                    val recvMs = System.currentTimeMillis() - startMs
                    Log.d(TAG, "Ephemeral received ${rawBytes.size / 1024} KB in ${recvMs}ms")

                    // Ephemeral mode: no encryption (WiFi Direct encrypts at link layer)
                    // But if the server sends encrypted data, try to decrypt
                    // For now, assume unencrypted in ephemeral mode
                    val bytes = rawBytes
                    val decryptMs = System.currentTimeMillis() - startMs - recvMs
                    Log.i(TAG, "Ephemeral pull complete: ${bytes.size / 1024} KB — recv ${recvMs}ms, decrypt ${decryptMs}ms, total ${System.currentTimeMillis() - startMs}ms")

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
        const val EPHEMERAL_DEFAULT_PORT = 8766
        const val FNX2_HEADER_SIZE = 29
        const val FNX2_CHUNK_SIZE = 64 * 1024
        const val FNX2_GCM_TAG_BYTES = 16
        const val FNX2_COMPRESSION_NONE = 0x00
        const val FNX2_COMPRESSION_ZSTD = 0x01
        const val GCM_TAG_BITS = 128
        val FNX2_MAGIC = byteArrayOf('F'.code.toByte(), 'N'.code.toByte(), 'X'.code.toByte(), '2'.code.toByte())
    }

    private fun decryptFnx2V2(encKey: ByteArray, rawPayload: ByteArray): ByteArray {
        require(encKey.size == 32) { "AES key must be 32 bytes" }
        if (rawPayload.size < FNX2_HEADER_SIZE) {
            error("FNX2 header too short: ${rawPayload.size} bytes")
        }
        // Check magic without allocating a copy
        for (i in 0 until 4) {
            if (rawPayload[i] != FNX2_MAGIC[i]) error("Invalid FNX2 magic")
        }

        val baseNonce = rawPayload.copyOfRange(4, 16) // 12 bytes, once
        val totalChunks = readIntBE(rawPayload, 16)
        val originalSize = readLongBE(rawPayload, 20)
        val compression = rawPayload[28].toInt() and 0xff

        if (totalChunks < 0) error("Invalid FNX2 total_chunks: $totalChunks")

        val encryptedLen = rawPayload.size - FNX2_HEADER_SIZE
        if (totalChunks == 0) {
            if (encryptedLen != 0) error("Invalid FNX2 payload: header declares 0 chunks but body has data")
            return decodeFnx2Compression(ByteArray(0), compression, originalSize)
        }

        val fullChunkEncryptedLen = FNX2_CHUNK_SIZE + FNX2_GCM_TAG_BYTES
        val minimumCiphertextBytes =
            (totalChunks.toLong() - 1L) * fullChunkEncryptedLen.toLong() + FNX2_GCM_TAG_BYTES.toLong()
        if (encryptedLen.toLong() < minimumCiphertextBytes) {
            error("FNX2 payload truncated: expected at least $minimumCiphertextBytes bytes of ciphertext, got $encryptedLen")
        }

        // Pre-allocate output — avoids repeated internal copies inside ByteArrayOutputStream
        val expectedPlaintext = originalSize.coerceAtMost(
            (totalChunks.toLong() * FNX2_CHUNK_SIZE).coerceAtMost(Int.MAX_VALUE.toLong())
        ).toInt()
        val plaintextOut = ByteArrayOutputStream(expectedPlaintext.coerceAtLeast(FNX2_CHUNK_SIZE))

        // Create cipher once, re-init per chunk — avoids JCA provider lookup × N
        val secretKey = SecretKeySpec(encKey, "AES")
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")

        // Reusable nonce buffer — mutated per chunk, avoids 12-byte alloc × N
        val chunkNonce = baseNonce.copyOf()

        var cursor = FNX2_HEADER_SIZE

        for (chunkIndex in 0 until totalChunks) {
            val chunkLen = if (chunkIndex < totalChunks - 1) fullChunkEncryptedLen else rawPayload.size - cursor

            if (chunkLen < FNX2_GCM_TAG_BYTES) error("FNX2 chunk $chunkIndex too short: $chunkLen bytes")

            // Derive nonce in-place: reset to baseNonce then XOR chunk index into bytes [4..11]
            baseNonce.copyInto(chunkNonce)
            val idx = chunkIndex.toLong()
            for (i in 0 until 8) {
                chunkNonce[4 + i] = (chunkNonce[4 + i].toInt() xor ((idx ushr ((7 - i) * 8)).toInt() and 0xff)).toByte()
            }

            try {
                cipher.init(Cipher.DECRYPT_MODE, secretKey, GCMParameterSpec(GCM_TAG_BITS, chunkNonce))
                // doFinal with offset — no copyOfRange allocation
                val plaintext = cipher.doFinal(rawPayload, cursor, chunkLen)
                plaintextOut.write(plaintext)
            } catch (e: GeneralSecurityException) {
                throw IllegalStateException("FNX2 chunk $chunkIndex decryption failed", e)
            }

            cursor += chunkLen
        }

        if (cursor != rawPayload.size) error("FNX2 payload malformed: trailing bytes after chunk decode")

        return decodeFnx2Compression(plaintextOut.toByteArray(), compression, originalSize)
    }

    private fun decodeFnx2Compression(payload: ByteArray, compression: Int, originalSize: Long): ByteArray {
        return when (compression) {
            FNX2_COMPRESSION_NONE -> payload
            FNX2_COMPRESSION_ZSTD -> {
                val out = ByteArrayOutputStream(payload.size)
                ByteArrayInputStream(payload).use { input ->
                    ZstdInputStream(input).use { zstd ->
                        val buffer = ByteArray(16 * 1024)
                        while (true) {
                            val read = zstd.read(buffer)
                            if (read < 0) {
                                break
                            }
                            if (read == 0) {
                                continue
                            }
                            out.write(buffer, 0, read)
                        }
                    }
                }
                val decoded = out.toByteArray()
                if (originalSize >= 0 && decoded.size.toLong() != originalSize) {
                    error("FNX2 zstd size mismatch: expected $originalSize bytes, got ${decoded.size}")
                }
                decoded
            }
            else -> error("Unsupported FNX2 compression mode: $compression")
        }
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
