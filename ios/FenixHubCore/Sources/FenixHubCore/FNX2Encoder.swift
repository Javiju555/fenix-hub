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

        var nonceBytes = [UInt8](repeating: 0, count: NONCE_SIZE)
        _ = SecRandomCopyBytes(kSecRandomDefault, NONCE_SIZE, &nonceBytes)
        self.baseNonce = Data(nonceBytes)

        let total = (originalSize + UInt64(FNX2_CHUNK_SIZE) - 1) / UInt64(FNX2_CHUNK_SIZE)
        self.totalChunks = UInt32(max(total, 1))
    }

    /// Returns the FNX2 header bytes.
    /// Must be sent before any encrypted chunks.
    func header() -> Data {
        var header = Data()
        header.append(contentsOf: FNX2_MAGIC)
        header.append(baseNonce)
        header.append(totalChunks.bigEndianBytes)
        header.append(originalSize.bigEndianBytes)
        header.append(FNX2_COMPRESSION_NONE)
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
        return combined.dropFirst(NONCE_SIZE)
    }

    func deriveNonce(index: UInt32) throws -> AES.GCM.Nonce {
        var nonceBytes = [UInt8](baseNonce)
        let indexBE = UInt64(index).bigEndianBytes
        for i in 0..<8 {
            nonceBytes[4 + i] ^= indexBE[i]
        }
        return try AES.GCM.Nonce(data: Data(nonceBytes))
    }
}
