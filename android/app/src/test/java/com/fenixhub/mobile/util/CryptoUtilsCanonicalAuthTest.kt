package com.fenixhub.mobile.util

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Test

class CryptoUtilsCanonicalAuthTest {
    @Test
    fun canonicalAuthVectorMatchesRustCore() {
        val keyHex = "000102030405060708090a0b0c0d0e0f101112131415161718191a1b1c1d1e1f"
        val method = "get"
        val path = "/content/test-id"
        val groupId = "0123456789abcdef0123456789abcdef"
        val timestampMs = 1_735_689_600_000L
        val nonceHex = "00112233445566778899AABBCCDDEEFF"
        val bodySha256Hex = CryptoUtils.EMPTY_BODY_SHA256_HEX.uppercase()

        val canonical = CryptoUtils.canonicalAuthMessage(
            method = method,
            path = path,
            groupId = groupId,
            timestampMs = timestampMs,
            nonceHex = nonceHex,
            bodySha256Hex = bodySha256Hex,
        )

        val expectedCanonical = (
            "fenixhub-auth-v1\n" +
                "GET\n" +
                "/content/test-id\n" +
                "0123456789abcdef0123456789abcdef\n" +
                "1735689600000\n" +
                "00112233445566778899aabbccddeeff\n" +
                "e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855"
            ).toByteArray(Charsets.UTF_8)

        assertArrayEquals(expectedCanonical, canonical)

        val signature = CryptoUtils.hmacSha256Hex(CryptoUtils.hexToBytes(keyHex), canonical)
        assertEquals(
            "5bce910b31e95e17a0e8fd3373510f562f30d9021ebc5007849edb1487992a37",
            signature,
        )
    }
}
