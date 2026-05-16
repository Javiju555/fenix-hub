package com.fenixhub.mobile.network

import com.fenixhub.mobile.model.AppSettings
import com.fenixhub.mobile.model.PeerContent
import com.fenixhub.mobile.model.PulledContent
import com.fenixhub.mobile.util.CryptoUtils
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import android.util.Log
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okio.BufferedSource
import org.json.JSONObject
import java.io.File
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.Socket
import java.security.GeneralSecurityException
import java.net.URLEncoder
import java.util.concurrent.TimeUnit
import java.util.UUID
import javax.crypto.Cipher
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec
import javax.net.SocketFactory

class FenixHttpClient(cacheDir: File) {
    private val downloadDir = File(cacheDir, "fenixhub/downloads").apply { mkdirs() }

    // Android's network policy engine tags OkHttp sockets to the default network (home WiFi),
    // which can't reach P2P peers at 192.168.49.x even though a P2P interface is active.
    // HOST: bind socket to GO IP (192.168.49.1) → kernel routes via P2P interface.
    // DEVICE: use Network.socketFactory from WifiNetworkSpecifier's onAvailable() network.
    @Volatile private var meshSocketFactory: SocketFactory? = null

    fun setMeshHostIp(ip: String?) {
        meshSocketFactory = ip?.takeIf { it.isNotBlank() }?.let { localIp ->
            val bindAddr = InetAddress.getByName(localIp)
            object : SocketFactory() {
                private fun bound(): Socket = Socket().apply { bind(InetSocketAddress(bindAddr, 0)) }
                override fun createSocket(): Socket = bound()
                override fun createSocket(h: String, p: Int): Socket = bound().apply { connect(InetSocketAddress(h, p)) }
                override fun createSocket(h: String, p: Int, la: InetAddress, lp: Int): Socket = bound().apply { connect(InetSocketAddress(h, p)) }
                override fun createSocket(a: InetAddress, p: Int): Socket = bound().apply { connect(InetSocketAddress(a, p)) }
                override fun createSocket(a: InetAddress, p: Int, la: InetAddress, lp: Int): Socket = bound().apply { connect(InetSocketAddress(a, p)) }
            }
        }
        Log.d(TAG, "meshSocketFactory hostIp=$ip")
    }

    fun setMeshNetwork(network: android.net.Network?) {
        meshSocketFactory = network?.socketFactory
        Log.d(TAG, "meshSocketFactory network=$network")
    }

    fun clearMeshSocketFactory() {
        meshSocketFactory = null
        cachedHeartbeatClient = null
        Log.d(TAG, "meshSocketFactory cleared")
    }

    @Volatile private var cachedHeartbeatClient: Pair<SocketFactory?, OkHttpClient>? = null

    private fun heartbeatClientFor(factory: SocketFactory?): OkHttpClient {
        val cached = cachedHeartbeatClient
        if (cached != null && cached.first === factory) return cached.second
        val c = OkHttpClient.Builder()
            .apply { factory?.let { socketFactory(it) } }
            .connectTimeout(2, TimeUnit.SECONDS)
            .readTimeout(2, TimeUnit.SECONDS)
            .writeTimeout(2, TimeUnit.SECONDS)
            .build()
        cachedHeartbeatClient = Pair(factory, c)
        return c
    }

    suspend fun meshHello(hostIp: String, port: Int, deviceName: String, factory: SocketFactory?): Boolean {
        return withContext(Dispatchers.IO) {
            runCatching {
                val body = JSONObject().put("device_name", deviceName).toString()
                    .toRequestBody("application/json".toMediaType())
                val request = Request.Builder()
                    .url("http://${urlHost(hostIp)}:$port/mesh/hello")
                    .post(body)
                    .build()
                heartbeatClientFor(factory).newCall(request).execute().use { it.code < 500 }
            }.getOrElse { false }
        }
    }

    suspend fun meshPing(hostIp: String, port: Int, factory: SocketFactory?): Boolean {
        return withContext(Dispatchers.IO) {
            runCatching {
                val request = Request.Builder()
                    .url("http://${urlHost(hostIp)}:$port/mesh/ping")
                    .get()
                    .build()
                heartbeatClientFor(factory).newCall(request).execute().use { it.code < 500 }
            }.getOrElse { false }
        }
    }

    private val client = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        // Large files at LAN speeds can take 30–60s — don't timeout mid-transfer.
        .readTimeout(120, TimeUnit.SECONDS)
        .writeTimeout(30, TimeUnit.SECONDS)
        .build()

    private fun meshClient(factory: SocketFactory): OkHttpClient = OkHttpClient.Builder()
        .socketFactory(factory)
        .connectTimeout(10, TimeUnit.SECONDS)
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
                val factory = meshSocketFactory
                val activeClient = factory?.let { meshClient(it) } ?: client
                val factoryDesc = when {
                    factory == null -> "none (LAN client)"
                    else -> factory.javaClass.simpleName.let { if (it.isNullOrBlank()) "anonymous" else it }
                }
                Log.d(TAG, "Pulling ${peer.announcement.contentId} from ${peer.peerIp}:${peer.port} socketFactory=$factoryDesc groupId=${settings.groupId.take(8)}")

                activeClient.newCall(request).execute().use { response ->
                    if (!response.isSuccessful) error("HTTP ${response.code}")
                    val body = response.body ?: error("Empty response body")
                    val encryptedHeader = response.header(ENCRYPTED_HEADER)
                    val mimeType = body.contentType()?.toString()
                    val fileName = fileNameFromDisposition(response.header("Content-Disposition"))

                    when (encryptedHeader) {
                        "2" -> {
                            val file = newDownloadFile(fileName)
                            val sizeBytes = streamDecryptFnx2ToFile(body.source(), settings.encKeyBytes(), file, startMs)
                            PulledContent(
                                bytes = ByteArray(0),
                                mimeType = mimeType,
                                fileName = fileName,
                                file = file,
                                sizeBytes = sizeBytes,
                            )
                        }
                        "1" -> {
                            val rawBytes = body.bytes()
                            val recvMs = System.currentTimeMillis() - startMs
                            val result = CryptoUtils.decryptAesGcm(settings.encKeyBytes(), rawBytes)
                            Log.i(TAG, "Pull complete (v1): ${result.size / 1024} KB — ${recvMs}ms")
                            PulledContent(bytes = result, mimeType = mimeType, fileName = fileName)
                        }
                        else -> {
                            val file = newDownloadFile(fileName)
                            val sizeBytes = streamPlainBodyToFile(body.source(), file)
                            Log.i(TAG, "Pull complete (legacy stream): ${sizeBytes / 1024} KB — ${System.currentTimeMillis() - startMs}ms")
                            PulledContent(
                                bytes = ByteArray(0),
                                mimeType = mimeType,
                                fileName = fileName,
                                file = file,
                                sizeBytes = sizeBytes,
                            )
                        }
                    }
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
                    when (encryptedHeader) {
                        "2" -> {
                            val file = newDownloadFile(fileName)
                            val sizeBytes = streamDecryptFnx2ToFile(body.source(), ByteArray(32), file, startMs)
                            PulledContent(
                                bytes = ByteArray(0),
                                mimeType = mimeType,
                                fileName = fileName,
                                file = file,
                                sizeBytes = sizeBytes,
                            )
                        }
                        else -> {
                            val file = newDownloadFile(fileName)
                            val sizeBytes = streamPlainBodyToFile(body.source(), file)
                            Log.i(TAG, "Ephemeral pull complete: ${sizeBytes / 1024} KB — ${System.currentTimeMillis() - startMs}ms")
                            PulledContent(
                                bytes = ByteArray(0),
                                mimeType = mimeType,
                                fileName = fileName,
                                file = file,
                                sizeBytes = sizeBytes,
                            )
                        }
                    }
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
        const val WRITE_BUFFER_SIZE = 256 * 1024
        val FNX2_CANDIDATE_CHUNK_SIZES = intArrayOf(
            FNX2_CHUNK_SIZE,
            256 * 1024,
            512 * 1024,
            1024 * 1024,
            2 * 1024 * 1024,
            4 * 1024 * 1024,
        )
        val FNX2_MAGIC = byteArrayOf('F'.code.toByte(), 'N'.code.toByte(), 'X'.code.toByte(), '2'.code.toByte())
    }

    /**
     * Stream-decrypt an FNX2 v2 response body chunk by chunk.
     *
     * Unlike the old approach (body.bytes() -> decrypt whole buffer), this writes
     * decrypted chunks directly to disk. Peak RAM stays bounded by one chunk.
     */
    private fun streamDecryptFnx2ToFile(source: BufferedSource, encKey: ByteArray, outputFile: File, startMs: Long): Long {
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
            outputFile.writeBytes(ByteArray(0))
            return 0L
        }

        val chunkPlainSize = preferredFnx2ChunkSize(totalChunks, originalSize)
        if (chunkPlainSize != FNX2_CHUNK_SIZE) {
            Log.w(TAG, "Peer uses FNX2 chunk size ${chunkPlainSize / 1024} KB (declared chunks=$totalChunks)")
        }
        val fullChunkEncryptedLen = chunkPlainSize + FNX2_GCM_TAG_BYTES
        val secretKey = SecretKeySpec(encKey, "AES")
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        val chunkNonce = baseNonce.copyOf()
        var totalPlaintext = 0L

        outputFile.outputStream().buffered(WRITE_BUFFER_SIZE).use { output ->
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
                    val plaintext = cipher.doFinal(chunkBytes)
                    output.write(plaintext)
                    totalPlaintext += plaintext.size
                } catch (e: GeneralSecurityException) {
                    throw IllegalStateException("FNX2 chunk $chunkIndex decryption failed", e)
                }
            }
        }

        val totalMs = System.currentTimeMillis() - startMs
        val kb = totalPlaintext / 1024
        val mbps = if (totalMs > 0) (totalPlaintext * 8 / totalMs / 1000.0) else 0.0
        Log.i(TAG, "Pull complete (FNX2 stream): ${kb} KB in ${totalMs}ms (%.1f Mbps)".format(mbps))

        if (originalSize >= 0 && totalPlaintext != originalSize) {
            Log.w(TAG, "FNX2 size mismatch: expected=$originalSize actual=$totalPlaintext")
        }
        return totalPlaintext
    }

    private fun streamPlainBodyToFile(source: BufferedSource, outputFile: File): Long {
        var total = 0L
        val buffer = ByteArray(WRITE_BUFFER_SIZE)
        outputFile.outputStream().buffered(WRITE_BUFFER_SIZE).use { output ->
            source.inputStream().use { input ->
                while (true) {
                    val read = input.read(buffer)
                    if (read == -1) break
                    output.write(buffer, 0, read)
                    total += read
                }
            }
        }
        return total
    }

    private fun newDownloadFile(fileName: String?): File {
        downloadDir.mkdirs()
        val safeName = fileName
            ?.map { ch -> if (ch in charArrayOf('/', '\\', ':', '*', '?', '"', '<', '>', '|')) '_' else ch }
            ?.joinToString("")
            ?.takeIf { it.isNotBlank() }
            ?: "content.bin"
        return File(downloadDir, "${UUID.randomUUID()}-$safeName")
    }

    private fun preferredFnx2ChunkSize(totalChunks: Int, originalSize: Long): Int {
        if (totalChunks <= 1 || originalSize <= 0) {
            return FNX2_CHUNK_SIZE
        }

        return FNX2_CANDIDATE_CHUNK_SIZES
            .firstOrNull { chunkCountForSize(originalSize, it) == totalChunks }
            ?: FNX2_CHUNK_SIZE
    }

    private fun chunkCountForSize(originalSize: Long, chunkPlainSize: Int): Int {
        if (originalSize <= 0) return 0
        val chunks = (originalSize + chunkPlainSize - 1) / chunkPlainSize
        return chunks.coerceAtMost(Int.MAX_VALUE.toLong()).toInt()
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
