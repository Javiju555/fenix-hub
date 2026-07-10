import XCTest
@testable import FenixHubCore

final class AuthSignerTests: XCTestCase {

    func testCanonicalMessageMatchesRustVector() throws {
        // This test verifies against the Rust test vector from protocol.rs:
        // canonical_auth_hmac_vector_matches_android
        //
        // Rust canonical message:
        // fenixhub-auth-v1
        // GET
        // /content/test-id
        // 0123456789abcdef0123456789abcdef
        // 1735689600000
        // 00112233445566778899aabbccddeeff
        // e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855

        let canonical = AuthSigner.canonicalMessage(
            method: "get",
            path: "/content/test-id",
            groupId: "0123456789abcdef0123456789abcdef",
            timestampMs: 1_735_689_600_000,
            nonceHex: "00112233445566778899AABBCCDDEEFF",
            bodySha256Hex: EMPTY_BODY_SHA256_HEX.uppercased()
        )

        let expected = """
        fenixhub-auth-v1
        GET
        /content/test-id
        0123456789abcdef0123456789abcdef
        1735689600000
        00112233445566778899aabbccddeeff
        e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855
        """

        XCTAssertEqual(canonical, expected.data(using: .utf8)!)
    }

    func testSignAndVerify() throws {
        let identity = try GroupIdentity(passphrase: "TestPassphrase2026!", deviceName: "Test")
        let signer = AuthSigner(identity: identity)

        let message = Data("hello world".utf8)
        let signature = signer.sign(message)

        // Verify with same identity
        XCTAssertTrue(signer.verify(message, signature: signature))

        // Verify with wrong message
        XCTAssertFalse(signer.verify(Data("wrong".utf8), signature: signature))
    }

    func testVerifyRequestValid() throws {
        let identity = try GroupIdentity(passphrase: "VerifyTestPass2026!", deviceName: "Verifier")
        let signer = AuthSigner(identity: identity)
        let nowMs = UInt64(Date().timeIntervalSince1970 * 1000)
        let nonceHex = "aabbccdd00112233445566778899eeff"

        // Build a valid canonical message
        let canonical = AuthSigner.canonicalMessage(
            method: "GET",
            path: "/content/some-id",
            groupId: identity.groupIdHex(),
            timestampMs: nowMs,
            nonceHex: nonceHex,
            bodySha256Hex: EMPTY_BODY_SHA256_HEX
        )
        let signature = signer.sign(canonical)

        // Verify with the same signer (simulating server-side verification)
        let canonical2 = AuthSigner.canonicalMessage(
            method: "GET",
            path: "/content/some-id",
            groupId: identity.groupIdHex(),
            timestampMs: nowMs,
            nonceHex: nonceHex,
            bodySha256Hex: EMPTY_BODY_SHA256_HEX
        )
        XCTAssertTrue(signer.verify(canonical2, signature: signature))
    }

    func testVerifyRejectsWrongSignature() throws {
        let identity = try GroupIdentity(passphrase: "RejectTestPass2026!", deviceName: "Rejector")
        let otherIdentity = try GroupIdentity(passphrase: "OtherPassphrase2026!", deviceName: "Other")
        let signer = AuthSigner(identity: identity)
        let otherSigner = AuthSigner(identity: otherIdentity)
        let nowMs = UInt64(Date().timeIntervalSince1970 * 1000)
        let nonceHex = "ffeeff00112233445566778899001122"

        let canonical = AuthSigner.canonicalMessage(
            method: "GET",
            path: "/content/other-id",
            groupId: identity.groupIdHex(),
            timestampMs: nowMs,
            nonceHex: nonceHex,
            bodySha256Hex: EMPTY_BODY_SHA256_HEX
        )
        let signature = otherSigner.sign(canonical) // Wrong key!

        XCTAssertFalse(signer.verify(canonical, signature: signature))
    }

    func testSha256Hex() throws {
        let data = Data("hello".utf8)
        let hash = sha256Hex(data)
        // Known SHA-256 of "hello"
        XCTAssertEqual(hash, "2cf24dba5fb0a30e26e83b2ac5b9e29e1b161e5c1fa7425e73043362938b9824")
    }
}
