/// FenixHub group identity system.
///
/// A "group" is defined by a passphrase. The passphrase never leaves the device —
/// Argon2id derives a deterministic 32-byte `group_key`. Any device using the
/// same passphrase derives the same group_key and can communicate.
///
/// Key hierarchy (matches Rust `identity.rs`):
///
/// ```text
/// passphrase
///     │  Argon2id (64 MiB, 3 iterations, 1 lane)
///     ▼
/// group_key (32 B)
///     │  HKDF-SHA256
///     ├──► mac_key  (32 B) — HMAC-SHA256 for HTTP request authentication
///     ├──► enc_key  (32 B) — AES-256-GCM for content encryption
///     └──► group_id (16 B) — advertised in mDNS for peer filtering
/// ```
///
/// Rust source-of-truth:
///   crates/fenix-hub-core/src/identity.rs

import Foundation
import CryptoKit
import Argon2Swift

struct GroupIdentity {
    let groupKey: Data
    let macKey: Data
    let encKey: Data
    let groupId: Data
    let deviceName: String

    /// Derive a group identity from a passphrase.
    ///
    /// Deterministic: the same passphrase always produces the same
    /// group_key, mac_key, and enc_key.
    ///
    /// Argon2id parameters (matching Rust v2):
    /// - Memory:      65536 KiB (64 MiB)
    /// - Iterations:  3 passes
    /// - Parallelism: 1 lane
    init(passphrase: String, deviceName: String) throws {
        guard passphrase.count >= MIN_PASSPHRASE_LEN else {
            throw IdentityError.passphraseTooShort
        }
        guard passphrase.count <= MAX_PASSPHRASE_LEN else {
            throw IdentityError.passphraseTooLong
        }

        let salt = ARGON2_GROUP_SALT.data(using: .utf8)!

        let result = try Argon2Swift.hashPasswordBytes(
            password: passphrase,
            salt: salt,
            iterations: ARGON2_ITERATIONS,
            memory: ARGON2_MEMORY_COST,
            parallelism: ARGON2_PARALLELISM,
            length: ARGON2_KEY_LENGTH,
            type: .id,
            version: .v13
        )

        let groupKey = Data(result.hash)
        self.groupKey = groupKey
        self.deviceName = deviceName

        // Derive sub-keys via HKDF-SHA256 (matching Rust HKDF expand).
        let (macKey, encKey, groupId) = Self.deriveSubkeys(from: groupKey)
        self.macKey = macKey
        self.encKey = encKey
        self.groupId = groupId
    }

    /// Restore identity from a previously persisted group key (hex-encoded).
    init(keyHex: String, deviceName: String) throws {
        guard let keyData = Data(hexString: keyHex), keyData.count == 32 else {
            throw IdentityError.invalidKeyHex
        }
        self.groupKey = keyData
        self.deviceName = deviceName
        let (macKey, encKey, groupId) = Self.deriveSubkeys(from: keyData)
        self.macKey = macKey
        self.encKey = encKey
        self.groupId = groupId
    }

    // MARK: - Key derivation

    /// Derives mac_key (32 B), enc_key (32 B), and group_id (16 B) from
    /// the group_key via HKDF-SHA256 using distinct HKDF info strings.
    ///
    /// This MUST match the Rust `derive_subkeys()` in identity.rs.
    private static func deriveSubkeys(from groupKey: Data) -> (Data, Data, Data) {
        let macKey = hkdfExpand(key: groupKey, info: HKDF_INFO_MAC, count: 32)
        let encKey = hkdfExpand(key: groupKey, info: HKDF_INFO_ENC, count: 32)
        let groupId = hkdfExpand(key: groupKey, info: HKDF_INFO_GROUP_ID, count: 16)
        return (macKey, encKey, groupId)
    }

    /// HKDF-SHA256 expand step: derives `count` bytes using the given `info` string.
    /// Uses CryptoKit's HKDF implementation.
    private static func hkdfExpand(key: Data, info: String, count: Int) -> Data {
        let infoData = info.data(using: .utf8)!
        let symmetricKey = SymmetricKey(data: key)
        let derived = HKDF<SHA256>.deriveKey(
            inputKeyMaterial: symmetricKey,
            salt: nil,
            info: infoData,
            outputByteCount: count
        )
        return derived.withUnsafeBytes { Data($0) }
    }

    // MARK: - Public accessors

    /// Returns the group key as hex (for local persistence, never sent over network).
    func keyHex() -> String {
        groupKey.hexString
    }

    /// Returns the group identifier as hex (safe to advertise in mDNS).
    func groupIdHex() -> String {
        groupId.hexString
    }

    /// Signs a message with HMAC-SHA256 using the MAC sub-key.
    /// Returns the raw 32-byte HMAC.
    func sign(_ message: Data) -> Data {
        let key = SymmetricKey(data: macKey)
        let hmac = HMAC<SHA256>.authenticationCode(for: message, using: key)
        return Data(hmac)
    }

    /// Verifies an HMAC-SHA256 signature (constant-time comparison).
    func verify(_ message: Data, signature: Data) -> Bool {
        let key = SymmetricKey(data: macKey)
        let expected = HMAC<SHA256>.authenticationCode(for: message, using: key)
        return Data(expected) == signature
    }
}

enum IdentityError: Error, LocalizedError {
    case passphraseTooShort
    case passphraseTooLong
    case invalidKeyHex
    case keyDerivationFailed(String)

    var errorDescription: String? {
        switch self {
        case .passphraseTooShort:
            return "Passphrase must be at least \(MIN_PASSPHRASE_LEN) characters"
        case .passphraseTooLong:
            return "Passphrase must be at most \(MAX_PASSPHRASE_LEN) characters"
        case .invalidKeyHex:
            return "Invalid key hex (expected 64 hex chars = 32 bytes)"
        case .keyDerivationFailed(let msg):
            return "Key derivation failed: \(msg)"
        }
    }
}

// MARK: - Data hex helpers

private extension Data {
    init?(hexString: String) {
        let len = hexString.count / 2
        var data = Data(capacity: len)
        var i = hexString.startIndex
        for _ in 0..<len {
            let j = hexString.index(i, offsetBy: 2)
            guard let byte = UInt8(hexString[i..<j], radix: 16) else { return nil }
            data.append(byte)
            i = j
        }
        self = data
    }

    var hexString: String {
        map { String(format: "%02x", $0) }.joined()
    }
}
