/// FNX2 chunked AEAD streaming decoder.
///
/// Parses the FNX2 header and decrypts chunks sequentially.
/// Each chunk is independently authenticated — a bit flip in one chunk
/// does not compromise the others.
///
/// Rust source-of-truth:
///   crates/fenix-hub-core/src/crypto.rs (ChunkDecoder)

import Foundation
import CryptoKit

/// FNX2 streaming decoder for received content.
///
/// Usage:
/// ```
/// let decoder = try FNX2Decoder(encKey: identity.encKey, header: headerData)
/// let plaintext = try decoder.decryptChunk(encryptedChunk)
/// ```
class FNX2Decoder {
    let baseNonce: Data
    let totalChunks: UInt32
    let originalSize: UInt64
    private let key: SymmetricKey
    private var chunkIndex: UInt32 = 0

    /// Parse FNX2 header and prepare for decryption.
    /// - Parameters:
    ///   - encKey: 32-byte AES-256-GCM encryption key
    ///   - headerData: must be at least FNX2_HEADER_SIZE bytes
    init(encKey: Data, headerData: Data) throws {
        guard headerData.count >= FNX2_HEADER_SIZE else {
            throw FNX2Error.invalidHeader
        }
        guard headerData.prefix(4).elementsEqual(FNX2_MAGIC) else {
            throw FNX2Error.invalidMagic
        }

        var keyData = encKey
        if keyData.count != 32 {
            keyData = Data(repeating: 0, count: 32)
        }
        self.key = SymmetricKey(data: keyData)

        self.baseNonce = headerData[4..<16]
        self.totalChunks = UInt32(bigEndianFrom: headerData[16..<20])
        self.originalSize = UInt64(bigEndianFrom: headerData[20..<28])
    }

    /// Decrypts a single chunk.
    /// - Parameter ciphertextChunk: encrypted data (ciphertext + GCM tag)
    /// - Returns: decrypted plaintext
    func decryptChunk(_ ciphertextChunk: Data) throws -> Data {
        let nonce = try deriveNonce(index: chunkIndex)
        chunkIndex += 1

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
