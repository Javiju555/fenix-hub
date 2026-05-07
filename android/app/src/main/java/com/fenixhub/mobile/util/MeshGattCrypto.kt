package com.fenixhub.mobile.util

import org.json.JSONObject
import java.security.KeyFactory
import java.security.KeyPair
import java.security.KeyPairGenerator
import java.security.PrivateKey
import java.security.PublicKey
import java.security.spec.ECGenParameterSpec
import java.security.spec.X509EncodedKeySpec
import javax.crypto.KeyAgreement

object MeshGattCrypto {

    private const val HKDF_INFO = "fenixhub-mesh-cred-v1"

    data class EcKeyPair(
        val privateKey: PrivateKey,
        val publicKeyBytes: ByteArray,
    )

    data class MeshCredentialPayload(
        val groupId: String,
        val groupKeyHex: String,
        val ssid: String,
        val p2pPass: String,
        val hostIp: String?,
        val port: Int,
    )

    fun generateEcKeyPair(): EcKeyPair {
        val gen = KeyPairGenerator.getInstance("EC").apply {
            initialize(ECGenParameterSpec("secp256r1"))
        }
        val kp: KeyPair = gen.generateKeyPair()
        val pubBytes = ecPublicKeyToUncompressed(kp.public)
        return EcKeyPair(privateKey = kp.private, publicKeyBytes = pubBytes)
    }

    /**
     * Host encrypts payload for a specific device.
     * Wire format: [1-byte version=1] [65-byte host_pubkey] [ciphertext + 12-byte nonce + 16-byte GCM tag]
     */
    fun encryptCredentials(
        payload: MeshCredentialPayload,
        devicePubKeyBytes: ByteArray,
        hostPrivKey: PrivateKey,
        hostPubKeyBytes: ByteArray,
    ): ByteArray {
        val sharedSecret = ecdh(hostPrivKey, uncompressedToPublicKey(devicePubKeyBytes))
        val encKey = CryptoUtils.hkdfExpand(
            CryptoUtils.hkdfExtract(sharedSecret),
            HKDF_INFO.toByteArray(Charsets.UTF_8),
            32,
        )
        val plaintext = payloadToJson(payload).toByteArray(Charsets.UTF_8)
        val ciphertext = CryptoUtils.encryptAesGcm(encKey, plaintext)
        return byteArrayOf(1) + hostPubKeyBytes + ciphertext
    }

    /**
     * Device decrypts credentials sent by host.
     * Returns null on any decryption failure.
     */
    fun decryptCredentials(
        encryptedBytes: ByteArray,
        devicePrivKey: PrivateKey,
    ): MeshCredentialPayload? = runCatching {
        require(encryptedBytes.size > 1 + 65) { "too short" }
        val version = encryptedBytes[0]
        require(version == 1.toByte()) { "unknown version $version" }

        val hostPubBytes = encryptedBytes.sliceArray(1 until 66)
        val ciphertext   = encryptedBytes.sliceArray(66 until encryptedBytes.size)

        val sharedSecret = ecdh(devicePrivKey, uncompressedToPublicKey(hostPubBytes))
        val encKey = CryptoUtils.hkdfExpand(
            CryptoUtils.hkdfExtract(sharedSecret),
            HKDF_INFO.toByteArray(Charsets.UTF_8),
            32,
        )
        val plaintext = CryptoUtils.decryptAesGcm(encKey, ciphertext)
        payloadFromJson(String(plaintext, Charsets.UTF_8))
    }.getOrNull()

    private fun ecdh(priv: PrivateKey, pub: PublicKey): ByteArray {
        val ka = KeyAgreement.getInstance("ECDH")
        ka.init(priv)
        ka.doPhase(pub, true)
        return ka.generateSecret()
    }

    private fun ecPublicKeyToUncompressed(pub: PublicKey): ByteArray {
        val encoded = pub.encoded
        return encoded.takeLast(65).toByteArray()
    }

    private fun uncompressedToPublicKey(bytes: ByteArray): PublicKey {
        require(bytes.size == 65 && bytes[0] == 0x04.toByte())
        val header = byteArrayOf(
            0x30, 0x59, 0x30, 0x13, 0x06, 0x07, 0x2a.toByte(), 0x86.toByte(),
            0x48, 0xce.toByte(), 0x3d, 0x02, 0x01, 0x06, 0x08, 0x2a.toByte(),
            0x86.toByte(), 0x48, 0xce.toByte(), 0x3d, 0x03, 0x01, 0x07, 0x03,
            0x42, 0x00,
        )
        val spec = X509EncodedKeySpec(header + bytes)
        return KeyFactory.getInstance("EC").generatePublic(spec)
    }

    private fun payloadToJson(p: MeshCredentialPayload): String =
        JSONObject().apply {
            put("gid", p.groupId)
            put("gk", p.groupKeyHex)
            put("ssid", p.ssid)
            put("p2pp", p.p2pPass)
            p.hostIp?.let { put("hip", it) }
            put("port", p.port)
        }.toString()

    private fun payloadFromJson(json: String): MeshCredentialPayload {
        val o = JSONObject(json)
        return MeshCredentialPayload(
            groupId     = o.getString("gid"),
            groupKeyHex = o.getString("gk"),
            ssid        = o.getString("ssid"),
            p2pPass     = o.getString("p2pp"),
            hostIp      = o.optString("hip").takeIf { it.isNotBlank() },
            port        = o.getInt("port"),
        )
    }
}
