package com.fenixhub.mobile.util

import org.junit.Test
import org.junit.Assert.*

class MeshGattCryptoTest {

    @Test
    fun `generateEcKeyPair returns 65-byte uncompressed public key`() {
        val keypair = MeshGattCrypto.generateEcKeyPair()
        assertEquals(65, keypair.publicKeyBytes.size)
        assertEquals(0x04.toByte(), keypair.publicKeyBytes[0])
    }

    @Test
    fun `encrypt and decrypt round-trips credentials`() {
        val deviceKeypair = MeshGattCrypto.generateEcKeyPair()
        val hostKeypair  = MeshGattCrypto.generateEcKeyPair()

        val creds = MeshGattCrypto.MeshCredentialPayload(
            groupId     = "test-group-id",
            groupKeyHex = "a".repeat(64),
            ssid        = "DIRECT-FX-TestMesh",
            p2pPass     = "TestP2PPass123",
            hostIp      = "192.168.49.1",
            port        = 8765,
        )

        val encrypted = MeshGattCrypto.encryptCredentials(
            payload          = creds,
            devicePubKeyBytes = deviceKeypair.publicKeyBytes,
            hostPrivKey       = hostKeypair.privateKey,
            hostPubKeyBytes   = hostKeypair.publicKeyBytes,
        )

        val decrypted = MeshGattCrypto.decryptCredentials(
            encryptedBytes   = encrypted,
            devicePrivKey    = deviceKeypair.privateKey,
        )

        assertNotNull(decrypted)
        assertEquals("test-group-id", decrypted!!.groupId)
        assertEquals("a".repeat(64), decrypted.groupKeyHex)
        assertEquals("DIRECT-FX-TestMesh", decrypted.ssid)
        assertEquals("TestP2PPass123", decrypted.p2pPass)
        assertEquals("192.168.49.1", decrypted.hostIp)
        assertEquals(8765, decrypted.port)
    }

    @Test
    fun `decrypt with wrong key returns null`() {
        val deviceKeypair = MeshGattCrypto.generateEcKeyPair()
        val hostKeypair   = MeshGattCrypto.generateEcKeyPair()
        val wrongKeypair  = MeshGattCrypto.generateEcKeyPair()

        val creds = MeshGattCrypto.MeshCredentialPayload(
            groupId     = "id",
            groupKeyHex = "b".repeat(64),
            ssid        = "SSID",
            p2pPass     = "pass",
            hostIp      = null,
            port        = 8765,
        )

        val encrypted = MeshGattCrypto.encryptCredentials(
            payload           = creds,
            devicePubKeyBytes = deviceKeypair.publicKeyBytes,
            hostPrivKey       = hostKeypair.privateKey,
            hostPubKeyBytes   = hostKeypair.publicKeyBytes,
        )

        val decrypted = MeshGattCrypto.decryptCredentials(
            encryptedBytes = encrypted,
            devicePrivKey  = wrongKeypair.privateKey,
        )
        assertNull(decrypted)
    }
}
