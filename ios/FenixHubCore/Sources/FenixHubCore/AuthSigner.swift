/// FenixHub HTTP request authentication.
///
/// Every HTTP request to a FenixHub content server must carry:
/// - `X-FenixHub-Auth`: HMAC-SHA256 of the canonical auth message
/// - `X-FenixHub-Timestamp`: current unix time in milliseconds
/// - `X-FenixHub-Nonce`: random 16-byte hex nonce
/// - `X-FenixHub-Body-Sha256`: SHA-256 of the request body (hex)
///
/// The canonical auth message format (matches Rust `canonical_auth_message()`):
/// ```text
/// fenixhub-auth-v1
/// GET
/// /content/{id}
/// {group_id}
/// {timestamp_ms}
/// {nonce_hex}
/// {body_sha256_hex}
/// ```
///
/// Rust source-of-truth:
///   crates/fenix-hub-core/src/protocol.rs

import Foundation
import CryptoKit

struct AuthSigner {
    private let identity: GroupIdentity

    init(identity: GroupIdentity) {
        self.identity = identity
    }

    /// Build the canonical auth message (must match Rust exactly).
    /// The Rust code joins with '\n' and trims/uppercases/lowercases fields.
    static func canonicalMessage(
        method: String,
        path: String,
        groupId: String,
        timestampMs: UInt64,
        nonceHex: String,
        bodySha256Hex: String
    ) -> Data {
        let msg = [
            AUTH_CANONICAL_PREFIX,
            method.trimmingCharacters(in: .whitespaces).uppercased(),
            path.trimmingCharacters(in: .whitespaces),
            groupId.trimmingCharacters(in: .whitespaces),
            String(timestampMs),
            nonceHex.trimmingCharacters(in: .whitespaces).lowercased(),
            bodySha256Hex.trimmingCharacters(in: .whitespaces).lowercased(),
        ].joined(separator: "\n")
        return msg.data(using: .utf8)!
    }

    /// Generate canonical auth message for a GET content request.
    func contentRequestCanonical(contentId: String, timestampMs: UInt64, nonceHex: String) -> Data {
        Self.canonicalMessage(
            method: "GET",
            path: "/content/\(contentId)",
            groupId: identity.groupIdHex(),
            timestampMs: timestampMs,
            nonceHex: nonceHex,
            bodySha256Hex: EMPTY_BODY_SHA256_HEX
        )
    }

    /// Sign a canonical auth message with the MAC sub-key.
    func sign(_ canonicalMessage: Data) -> Data {
        identity.sign(canonicalMessage)
    }

    /// Verify a signature against a canonical auth message.
    func verify(_ canonicalMessage: Data, signature: Data) -> Bool {
        identity.verify(canonicalMessage, signature: signature)
    }

    /// Verify all auth headers from an incoming HTTP request.
    /// Returns true only if all checks pass (valid HMAC, within time skew, body hash matches).
    func verifyRequest(
        method: String,
        path: String,
        timestampHeader: String?,
        nonceHeader: String?,
        authHeader: String?,
        bodySha256Header: String?
    ) -> Bool {
        guard let timestampStr = timestampHeader,
              let timestampMs = UInt64(timestampStr),
              let nonceHex = nonceHeader,
              let sigHex = authHeader,
              let bodySha256Hex = bodySha256Header,
              !nonceHex.isEmpty, !sigHex.isEmpty
        else { return false }

        // Body hash check: for GET requests the body is empty.
        guard bodySha256Hex.lowercased() == EMPTY_BODY_SHA256_HEX else { return false }

        // Timestamp skew check.
        let nowMs = UInt64(Date().timeIntervalSince1970 * 1000)
        if nowMs.distance(to: timestampMs) > AUTH_MAX_SKEW_MS {
            return false
        }

        let canonical = Self.canonicalMessage(
            method: method,
            path: path,
            groupId: identity.groupIdHex(),
            timestampMs: timestampMs,
            nonceHex: nonceHex,
            bodySha256Hex: bodySha256Hex
        )

        guard let sigData = Data(hexString: sigHex) else { return false }
        return identity.verify(canonical, signature: sigData)
    }
}

/// Hash a string or data with SHA-256 and return lowercase hex.
func sha256Hex(_ data: Data) -> String {
    let hash = SHA256.hash(data: data)
    return hash.compactMap { String(format: "%02x", $0) }.joined()
}
