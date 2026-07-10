import Foundation

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
