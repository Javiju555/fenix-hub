package com.fenixhub.mobile.network

import com.github.luben.zstd.Zstd
import org.junit.Assert.assertArrayEquals
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.security.GeneralSecurityException
import javax.crypto.Cipher
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

class FenixHttpClientFnx2DecodeTest {
    @Test
    fun decodeFnx2V2_roundTripWithoutCompression() {
        val key = ByteArray(32) { i -> i.toByte() }
        val plaintext = "fnx2/android/decode/none".repeat(5000).toByteArray(Charsets.UTF_8)
        val payload = buildFnx2Payload(key, plaintext, COMPRESSION_NONE)

        val decoded = invokeDecryptFnx2V2(key, payload)
        assertArrayEquals(plaintext, decoded)
    }

    @Test
    fun decodeFnx2V2_roundTripWithZstdCompression() {
        val key = ByteArray(32) { i -> (255 - i).toByte() }
        val plaintext = "compressible-text-".repeat(6000).toByteArray(Charsets.UTF_8)
        val payload = buildFnx2Payload(key, plaintext, COMPRESSION_ZSTD)

        val decoded = invokeDecryptFnx2V2(key, payload)
        assertArrayEquals(plaintext, decoded)
    }

    private fun invokeDecryptFnx2V2(key: ByteArray, payload: ByteArray): ByteArray {
        val client = FenixHttpClient()
        val method = FenixHttpClient::class.java.getDeclaredMethod(
            "decryptFnx2V2",
            ByteArray::class.java,
            ByteArray::class.java,
        )
        method.isAccessible = true
        return method.invoke(client, key, payload) as ByteArray
    }

    private fun buildFnx2Payload(key: ByteArray, plaintext: ByteArray, compression: Int): ByteArray {
        val transferPayload = when (compression) {
            COMPRESSION_NONE -> plaintext
            COMPRESSION_ZSTD -> Zstd.compress(plaintext)
            else -> error("Unsupported compression")
        }
        val totalChunks = if (transferPayload.isEmpty()) 0 else (transferPayload.size + CHUNK_SIZE - 1) / CHUNK_SIZE
        val baseNonce = byteArrayOf(0, 1, 2, 3, 4, 5, 6, 7, 8, 9, 10, 11)

        val out = ByteArrayOutputStream()
        out.write(FNX2_MAGIC)
        out.write(baseNonce)
        out.write(intToBigEndian(totalChunks))
        out.write(longToBigEndian(plaintext.size.toLong()))
        out.write(byteArrayOf(compression.toByte()))

        for (chunkIndex in 0 until totalChunks) {
            val start = chunkIndex * CHUNK_SIZE
            val end = minOf(start + CHUNK_SIZE, transferPayload.size)
            val chunk = transferPayload.copyOfRange(start, end)
            val nonce = deriveChunkNonce(baseNonce, chunkIndex.toLong())
            out.write(encryptChunk(key, nonce, chunk))
        }

        return out.toByteArray()
    }

    private fun encryptChunk(key: ByteArray, nonce: ByteArray, plaintext: ByteArray): ByteArray {
        return try {
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(
                Cipher.ENCRYPT_MODE,
                SecretKeySpec(key, "AES"),
                GCMParameterSpec(GCM_TAG_BITS, nonce),
            )
            cipher.doFinal(plaintext)
        } catch (e: GeneralSecurityException) {
            throw IllegalStateException("Chunk encryption failed", e)
        }
    }

    private fun deriveChunkNonce(baseNonce: ByteArray, chunkIndex: Long): ByteArray {
        val nonce = baseNonce.copyOf()
        for (i in 0 until 8) {
            val shift = (7 - i) * 8
            val indexByte = ((chunkIndex ushr shift) and 0xffL).toInt()
            nonce[4 + i] = (nonce[4 + i].toInt() xor indexByte).toByte()
        }
        return nonce
    }

    private fun intToBigEndian(value: Int): ByteArray {
        return byteArrayOf(
            ((value ushr 24) and 0xff).toByte(),
            ((value ushr 16) and 0xff).toByte(),
            ((value ushr 8) and 0xff).toByte(),
            (value and 0xff).toByte(),
        )
    }

    private fun longToBigEndian(value: Long): ByteArray {
        return byteArrayOf(
            ((value ushr 56) and 0xffL).toByte(),
            ((value ushr 48) and 0xffL).toByte(),
            ((value ushr 40) and 0xffL).toByte(),
            ((value ushr 32) and 0xffL).toByte(),
            ((value ushr 24) and 0xffL).toByte(),
            ((value ushr 16) and 0xffL).toByte(),
            ((value ushr 8) and 0xffL).toByte(),
            (value and 0xffL).toByte(),
        )
    }

    private companion object {
        const val CHUNK_SIZE = 64 * 1024
        const val COMPRESSION_NONE = 0x00
        const val COMPRESSION_ZSTD = 0x01
        const val GCM_TAG_BITS = 128
        val FNX2_MAGIC = byteArrayOf('F'.code.toByte(), 'N'.code.toByte(), 'X'.code.toByte(), '2'.code.toByte())
    }
}
