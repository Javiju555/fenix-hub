/// FNX2 chunked AEAD streaming encoder.
///
/// Wire format:
/// ```text
/// Header: FNX2(4) + base_nonce(12) + total_chunks(4) + original_size(8) + compression(1)
/// Per chunk: ciphertext + GCM tag (16 B)
/// ```
///
/// Nonces are derived as: nonce = base_nonce XOR (chunk_index as big-endian u64, zero-padded)
/// This matches the Rust `ChunkEncoder` in crypto.rs, using the same XOR
/// strategy into bytes 4-11 of the nonce (leaving bytes 0-3 unchanged).
///
/// Each chunk is independently authenticated — a bit flip in one chunk does
/// not compromise the others.
///
/// Rust source-of-truth:
///   crates/fenix-hub-core/src/crypto.rs

import Foundation
import CryptoKit

/// FNX2 streaming encoder for serving content.
///
/// Usage:
/// ```
/// let encoder = FNX2Encoder(encKey: identity.encKey, originalSize: fileSize)
/// let header = encoder.header()
/// let encryptedChunk = try encoder.encryptChunk(plaintext)
/// ```
class FNX2Encoder {
    private let key: SymmetricKey
    private let baseNonce: Data
    private let totalChunks: UInt32
    private let originalSize: UInt64
    private var chunkIndex: UInt32 = 0
    private var headerWritten = false

    /// Creates a new FNX2 encoder.
    /// - Parameters:
    ///   - encKey: 32-byte AES-256-GCM encryption key
    ///   - originalSize: total size of the original plaintext
    init(encKey: Data, originalSize: UInt64) {
        var keyData = encKey
        if keyData.count != 32 {
            keyData = Data(repeating: 0, count: 32)
        }
        self.key = SymmetricKey(data: keyData)
        self.originalSize = originalSize

        // Generate random 12-byte base nonce (matches Rust OsRng).
        var nonceBytes = [UInt8](repeating: 0, count: NONCE_SIZE)
        _ = SecRandomCopyBytes(kSecRandomDefault, NONCE_SIZE, &nonceBytes)
        self.baseNonce = Data(nonceBytes)

        // Calculate total chunks (matches Rust logic).
        let total = (originalSize + UInt64(FNX2_CHUNK_SIZE) - 1) / UInt64(FNX2_CHUNK_SIZE)
        self.totalChunks = UInt32(max(total, 1))
    }

    /// Returns the FNX2 header bytes.
    /// Must be sent before any encrypted chunks.
    func header() -> Data {
        var header = Data()
        header.append(contentsOf: FNX2_MAGIC)       // 4 bytes
        header.append(baseNonce)                      // 12 bytes
        header.append(totalChunks.bigEndianBytes)     // 4 bytes
        header.append(originalSize.bigEndianBytes)    // 8 bytes
        header.append(FNX2_COMPRESSION_NONE)          // 1 byte
        return header
    }

    /// Encrypts a chunk of plaintext.
    /// - Parameter plaintext: up to FNX2_CHUNK_SIZE bytes
    /// - Returns: encrypted chunk (ciphertext + 16-byte GCM tag)
    func encryptChunk(_ plaintext: Data) throws -> Data {
        let nonce = try deriveNonce(index: chunkIndex)
        chunkIndex += 1

        let sealedBox = try AES.GCM.seal(plaintext, using: key, nonce: nonce)
        guard let combined = sealedBox.combined else {
            throw FNX2Error.encryptionFailed
        }

        // The combined output is nonce(12) + ciphertext + tag(16).
        // For FNX2 per-chunk format we only want ciphertext + tag,
        // since the nonce is derived on both ends.
        return combined.dropFirst(NONCE_SIZE)
    }

    /// Derive nonce for a given chunk index.
    /// Matches Rust: nonce = base_nonce XOR (chunk_index as u64 big-endian, placed at bytes 4-11).
    private func deriveNonce(index: UInt32) throws -> AES.GCM.Nonce {
        var nonceBytes = [UInt8](baseNonce)
        let indexBE = UInt64(index).bigEndianBytes
        for i in 0..<8 {
            nonceBytes[4 + i] ^= indexBE[i]
        }
        return try AES.GCM.Nonce(data: Data(nonceBytes))
    }
}

/// FNX2 streaming decoder.
class FNX2Decoder {
    let baseNonce: Data
    let totalChunks: UInt32
    let originalSize: UInt64
    private let key: SymmetricKey
    private var chunkIndex: UInt32 = 0

    /// Parse FNX2 header and prepare for decryption.
    /// - Parameters:
    ///   - encKey: 32-byte AES-256-GCM encryption key
    ///   - header: must be at least FNX2_HEADER_SIZE bytes
    init(encKey: Data, header: Data) throws {
        guard header.count >= FNX2_HEADER_SIZE else {
            throw FNX2Error.invalidHeader
        }
        guard header.prefix(4).elementsEqual(FNX2_MAGIC) else {
            throw FNX2Error.invalidMagic
        }

        var keyData = encKey
        if keyData.count != 32 {
            keyData = Data(repeating: 0, count: 32)
        }
        self.key = SymmetricKey(data: keyData)

        self.baseNonce = header[4..<16]
        self.totalChunks = UInt32(bigEndianFrom: header[16..<20])
        self.originalSize = UInt64(bigEndianFrom: header[20..<28])

        // compression byte at index 28 is currently ignored (FNX2_COMPRESSION_NONE)
    }

    /// Decrypts a single chunk.
    /// - Parameter ciphertextChunk: encrypted data (ciphertext + GCM tag)
    /// - Returns: decrypted plaintext
    func decryptChunk(_ ciphertextChunk: Data) throws -> Data {
        let nonce = try deriveNonce(index: chunkIndex)
        chunkIndex += 1

        // Reconstruct the combined format expected by CryptoKit: nonce + ciphertext + tag
        var combined = Data()
        combined.append(nonce.withUnsafeBytes { Data($0) })
        combined.append(ciphertextChunk)

        let sealedBox = try AES.GCM.SealedBox(combined: combined)
        return try AES.GCM.open(sealedBox, using: key)
    }

    private func deriveNonce(index: UInt32) throws -> AES.GCM.Nonce {
        var nonceBytes = [UInt8](baseNonce)
        let indexBE = UInt64(index).bigEndianBytes
        for i in 0..<8 {
            nonceBytes[4 + i] ^= indexBE[i]
        }
        return try AES.GCM.Nonce(data: Data(nonceBytes))
    }
}

// MARK: - Extensions

private extension UInt32 {
    var bigEndianBytes: [UInt8] {
        withUnsafeBytes(of: bigEndian) { [UInt8]($0) }
    }
}

private extension UInt64 {
    var bigEndianBytes: [UInt8] {
        withUnsafeBytes(of: bigEndian) { [UInt8]($0) }
    }
}

private func UInt32(bigEndianFrom data: Data) -> UInt32 {
    data.withUnsafeBytes { $0.load(as: UInt32.self).bigEndian }
}

private func UInt64(bigEndianFrom data: Data) -> UInt64 {
    data.withUnsafeBytes { $0.load(as: UInt64.self).bigEndian }
}

enum FNX2Error: Error, LocalizedError {
    case invalidHeader
    case invalidMagic
    case encryptionFailed
    case decryptionFailed

    var errorDescription: String? {
        switch self {
        case .invalidHeader: return "FNX2 header too short"
        case .invalidMagic: return "Invalid FNX2 magic"
        case .encryptionFailed: return "FNX2 chunk encryption failed"
        case .decryptionFailed: return "FNX2 chunk decryption failed"
        }
    }
}
