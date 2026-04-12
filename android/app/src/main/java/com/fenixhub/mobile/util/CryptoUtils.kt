package com.fenixhub.mobile.util

import com.lambdapioneer.argon2kt.Argon2Kt
import com.lambdapioneer.argon2kt.Argon2Mode
import com.lambdapioneer.argon2kt.Argon2Version
import java.io.ByteArrayOutputStream
import java.security.GeneralSecurityException
import java.security.MessageDigest
import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.Mac
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

object CryptoUtils {
    private val argon2 by lazy { Argon2Kt() }
    private val secureRandom = SecureRandom()
    private const val HMAC_SHA256 = "HmacSHA256"
    private const val SHA_256 = "SHA-256"

    const val HMAC_HEADER = "X-FenixHub-Auth"
    const val AUTH_TIMESTAMP_HEADER = "X-FenixHub-Timestamp"
    const val AUTH_NONCE_HEADER = "X-FenixHub-Nonce"
    const val AUTH_BODY_SHA256_HEADER = "X-FenixHub-Body-Sha256"

    const val AUTH_MAX_SKEW_MS = 90_000L
    const val EMPTY_BODY_SHA256_HEX = "e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855"

    private const val MIN_PASSPHRASE_LEN = 10
    private const val MAX_PASSPHRASE_LEN = 256

    private const val NONCE_SIZE = 12
    private const val GCM_TAG_BITS = 128
    private const val MIN_ENCRYPTED_SIZE = NONCE_SIZE + 16

    private val ARGON2_GROUP_SALT = "fenixhub-v2".toByteArray(Charsets.UTF_8)
    private val HKDF_INFO_MAC = "fenixhub-v2-mac".toByteArray(Charsets.UTF_8)
    private val HKDF_INFO_ENC = "fenixhub-v2-enc".toByteArray(Charsets.UTF_8)
    private val HKDF_INFO_GROUP_ID = "fenixhub-v2-group-id".toByteArray(Charsets.UTF_8)
    private val HKDF_ZERO_SALT = ByteArray(32)

    fun deriveGroupKey(passphrase: String): ByteArray {
        val result = argon2.hash(
            mode = Argon2Mode.ARGON2_ID,
            password = passphrase.toByteArray(Charsets.UTF_8),
            salt = ARGON2_GROUP_SALT,
            tCostInIterations = 3,
            mCostInKibibyte = 65536,
            parallelism = 1,
            hashLengthInBytes = 32,
            version = Argon2Version.V13,
        )
        return result.rawHashAsByteArray()
    }

    fun deriveMacKey(groupKey: ByteArray): ByteArray {
        require(groupKey.size == 32) { "groupKey must be 32 bytes" }
        return hkdfExpand(hkdfExtract(groupKey), HKDF_INFO_MAC, 32)
    }

    fun deriveEncKey(groupKey: ByteArray): ByteArray {
        require(groupKey.size == 32) { "groupKey must be 32 bytes" }
        return hkdfExpand(hkdfExtract(groupKey), HKDF_INFO_ENC, 32)
    }

    fun hmacSha256Hex(key: ByteArray, message: ByteArray): String {
        val mac = Mac.getInstance(HMAC_SHA256)
        mac.init(SecretKeySpec(key, HMAC_SHA256))
        return toHex(mac.doFinal(message))
    }

    fun encryptAesGcm(key: ByteArray, plaintext: ByteArray): ByteArray {
        require(key.size == 32) { "AES key must be 32 bytes" }
        val nonce = ByteArray(NONCE_SIZE).also(secureRandom::nextBytes)

        return try {
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(
                Cipher.ENCRYPT_MODE,
                SecretKeySpec(key, "AES"),
                GCMParameterSpec(GCM_TAG_BITS, nonce),
            )
            val ciphertext = cipher.doFinal(plaintext)
            ByteArrayOutputStream(NONCE_SIZE + ciphertext.size).use { out ->
                out.write(nonce)
                out.write(ciphertext)
                out.toByteArray()
            }
        } catch (e: GeneralSecurityException) {
            throw IllegalStateException("AES-GCM encryption failed", e)
        }
    }

    fun decryptAesGcm(key: ByteArray, encrypted: ByteArray): ByteArray {
        require(key.size == 32) { "AES key must be 32 bytes" }
        require(encrypted.size >= MIN_ENCRYPTED_SIZE) {
            "Encrypted payload too short: ${encrypted.size} bytes"
        }

        val nonce = encrypted.copyOfRange(0, NONCE_SIZE)
        val ciphertext = encrypted.copyOfRange(NONCE_SIZE, encrypted.size)

        return try {
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(
                Cipher.DECRYPT_MODE,
                SecretKeySpec(key, "AES"),
                GCMParameterSpec(GCM_TAG_BITS, nonce),
            )
            cipher.doFinal(ciphertext)
        } catch (e: GeneralSecurityException) {
            throw IllegalStateException("AES-GCM decryption failed", e)
        }
    }

    fun groupIdFromKey(key: ByteArray): String {
        require(key.size == 32) { "groupKey must be 32 bytes" }
        val groupIdBytes = hkdfExpand(hkdfExtract(key), HKDF_INFO_GROUP_ID, 16)
        return toHex(groupIdBytes)
    }

    fun newAuthNonceHex(): String {
        val nonce = ByteArray(16).also(secureRandom::nextBytes)
        return toHex(nonce)
    }

    fun isValidAuthNonceHex(nonceHex: String): Boolean {
        val normalized = nonceHex.trim().lowercase()
        return normalized.length in 16..128 &&
            normalized.length % 2 == 0 &&
            normalized.all { it.isDigit() || it in 'a'..'f' }
    }

    fun sha256Hex(message: ByteArray): String {
        return toHex(MessageDigest.getInstance(SHA_256).digest(message))
    }

    fun canonicalAuthMessage(
        method: String,
        path: String,
        groupId: String,
        timestampMs: Long,
        nonceHex: String,
        bodySha256Hex: String,
    ): ByteArray {
        return buildString {
            append("fenixhub-auth-v1\n")
            append(method.trim().uppercase())
            append('\n')
            append(path.trim())
            append('\n')
            append(groupId.trim())
            append('\n')
            append(timestampMs)
            append('\n')
            append(nonceHex.trim().lowercase())
            append('\n')
            append(bodySha256Hex.trim().lowercase())
        }.toByteArray(Charsets.UTF_8)
    }

    fun validatePassphraseStrength(passphrase: String): String? {
        val normalized = passphrase.trim()
        if (normalized.isBlank()) {
            return "La passphrase es obligatoria"
        }

        if (normalized.length < MIN_PASSPHRASE_LEN) {
            return "La passphrase debe tener al menos $MIN_PASSPHRASE_LEN caracteres"
        }

        if (normalized.length > MAX_PASSPHRASE_LEN) {
            return "La passphrase supera el maximo de $MAX_PASSPHRASE_LEN caracteres"
        }

        val hasLower = normalized.any { it.isLowerCase() }
        val hasUpper = normalized.any { it.isUpperCase() }
        val hasDigit = normalized.any { it.isDigit() }
        val hasSymbol = normalized.any { !it.isLetterOrDigit() && !it.isWhitespace() }
        val classCount = listOf(hasLower, hasUpper, hasDigit, hasSymbol).count { it }
        if (classCount < 2) {
            return "Usa al menos 2 tipos de caracteres (minusculas, mayusculas, numeros o simbolos)"
        }

        if (normalized.toSet().size < 4) {
            return "La passphrase es demasiado repetitiva"
        }

        return null
    }

    fun toHex(bytes: ByteArray): String = bytes.joinToString(separator = "") { "%02x".format(it) }

    fun hexToBytes(hex: String): ByteArray {
        require(hex.length % 2 == 0) { "Hex string length must be even" }
        return ByteArray(hex.length / 2) { index ->
            hex.substring(index * 2, index * 2 + 2).toInt(16).toByte()
        }
    }

    fun constantTimeEquals(expectedHex: String, candidateHex: String): Boolean {
        return MessageDigest.isEqual(
            expectedHex.lowercase().toByteArray(Charsets.UTF_8),
            candidateHex.lowercase().toByteArray(Charsets.UTF_8),
        )
    }

    fun hkdfExtract(ikm: ByteArray): ByteArray {
        val mac = Mac.getInstance(HMAC_SHA256)
        mac.init(SecretKeySpec(HKDF_ZERO_SALT, HMAC_SHA256))
        return mac.doFinal(ikm)
    }

    fun hkdfExpand(prk: ByteArray, info: ByteArray, outputLen: Int): ByteArray {
        require(outputLen > 0) { "outputLen must be > 0" }

        val mac = Mac.getInstance(HMAC_SHA256)
        mac.init(SecretKeySpec(prk, HMAC_SHA256))

        val output = ByteArray(outputLen)
        var previous = ByteArray(0)
        var offset = 0
        var counter = 1

        while (offset < outputLen) {
            mac.reset()
            mac.update(previous)
            mac.update(info)
            mac.update(counter.toByte())
            val block = mac.doFinal()

            val copySize = minOf(block.size, outputLen - offset)
            System.arraycopy(block, 0, output, offset, copySize)
            offset += copySize
            previous = block
            counter += 1
        }

        return output
    }
}
