package com.fenixhub.mobile.util

import com.lambdapioneer.argon2kt.Argon2Kt
import com.lambdapioneer.argon2kt.Argon2Mode
import com.lambdapioneer.argon2kt.Argon2Version
import java.security.MessageDigest
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

object CryptoUtils {
    private val argon2 by lazy { Argon2Kt() }
    private const val AUTH_CHALLENGE_PREFIX = "auth/challenge:"

    fun deriveGroupKey(passphrase: String): ByteArray {
        val result = argon2.hash(
            mode = Argon2Mode.ARGON2_ID,
            password = passphrase.toByteArray(Charsets.UTF_8),
            salt = ARGON2_SALT,
            tCostInIterations = 3,
            mCostInKibibyte = 4096,
            parallelism = 1,
            hashLengthInBytes = 32,
            version = Argon2Version.V13,
        )
        return result.rawHashAsByteArray()
    }

    fun hmacSha256Hex(key: ByteArray, message: ByteArray): String {
        val mac = Mac.getInstance("HmacSHA256")
        mac.init(SecretKeySpec(key, "HmacSHA256"))
        return toHex(mac.doFinal(message))
    }

    fun authChallengeHeaderMessage(nonce: String): ByteArray {
        return "$AUTH_CHALLENGE_PREFIX$nonce".toByteArray(Charsets.UTF_8)
    }

    fun groupIdFromKey(key: ByteArray): String = toHex(key.copyOfRange(0, 16))

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

    private val ARGON2_SALT = "fenixhub-v1-salt".toByteArray(Charsets.UTF_8)
}
