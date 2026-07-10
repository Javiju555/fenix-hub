/// HTTP client for pulling content from FenixHub peers.
///
/// Downloads content from a peer's HTTP server with:
/// - HMAC-SHA256 authentication headers
/// - FNX2 stream decryption
/// - Streaming download for large files
///
/// Rust source-of-truth:
///   crates/fenix-hub-core/src/client.rs
///
/// Android source-of-truth:
///   android/app/src/main/java/com/fenixhub/mobile/network/FenixHttpClient.kt

import Foundation
import CryptoKit

/// Result of a content pull operation.
struct PullResult {
    let contentId: String
    let data: Data
    let originalSize: UInt64
    let mimeType: String?
    let fileName: String?
}

/// HTTP client for FenixHub peer content transfer.
class FenixHttpClient: NSObject, URLSessionTaskDelegate {
    private let identity: GroupIdentity
    private let session: URLSession

    init(identity: GroupIdentity) {
        self.identity = identity
        self.session = URLSession(configuration: .default)
        super.init()
    }

    /// Pull content from a peer.
    /// - Parameters:
    ///   - host: Peer IP or hostname
    ///   - port: Peer HTTP server port
    ///   - announcement: The announcement describing the content
    ///   - progressHandler: Optional callback with bytes received so far
    /// - Returns: Decrypted content as PullResult
    func pullContent(
        host: String,
        port: UInt16,
        announcement: Announcement,
        progressHandler: ((Int64) -> Void)? = nil
    ) async throws -> PullResult {
        let url = URL(string: "http://\(host):\(port)/content/\(announcement.contentId)")!
        var request = URLRequest(url: url)
        request.httpMethod = "GET"
        request.setValue("application/octet-stream", forHTTPHeaderField: "Accept")

        // Generate auth headers.
        let nowMs = UInt64(Date().timeIntervalSince1970 * 1000)
        let nonceHex = Self.generateNonceHex()
        let signer = AuthSigner(identity: identity)
        let canonical = signer.contentRequestCanonical(
            contentId: announcement.contentId,
            timestampMs: nowMs,
            nonceHex: nonceHex
        )
        let signature = signer.sign(canonical)

        request.setValue(identity.groupIdHex(), forHTTPHeaderField: "X-FenixHub-Group-Id")
        request.setValue(signature.hexString, forHTTPHeaderField: HMAC_HEADER)
        request.setValue(String(nowMs), forHTTPHeaderField: AUTH_TIMESTAMP_HEADER)
        request.setValue(nonceHex, forHTTPHeaderField: AUTH_NONCE_HEADER)
        request.setValue(EMPTY_BODY_SHA256_HEX, forHTTPHeaderField: AUTH_BODY_SHA256_HEADER)

        let (data, response) = try await session.data(for: request)

        guard let httpResponse = response as? HTTPURLResponse else {
            throw HTTPError.invalidResponse
        }

        guard httpResponse.statusCode == 200 else {
            throw HTTPError.statusCode(httpResponse.statusCode)
        }

        // Check if response is FNX2 encrypted.
        let encryptedHeader = httpResponse.allHeaderFields[ENCRYPTED_HEADER] as? String
        guard encryptedHeader == "2" else {
            throw HTTPError.notEncrypted
        }

        // Parse FNX2 header and decrypt chunks.
        guard data.count >= FNX2_HEADER_SIZE else {
            throw HTTPError.responseTooShort
        }

        let headerData = data.prefix(FNX2_HEADER_SIZE)
        let encryptedBody = data.dropFirst(FNX2_HEADER_SIZE)

        let decoder = try FNX2Decoder(encKey: identity.encKey, headerData: Data(headerData))

        var decrypted = Data(capacity: Int(decoder.originalSize))
        let chunkSize = FNX2_CHUNK_SIZE + 16 // ciphertext + GCM tag
        var offset = 0
        var bytesProcessed: Int64 = 0

        while offset < encryptedBody.count {
            let end = min(offset + chunkSize, encryptedBody.count)
            let encryptedChunk = encryptedBody[offset..<end]
            let plainChunk = try decoder.decryptChunk(Data(encryptedChunk))
            decrypted.append(plainChunk)
            offset = end
            bytesProcessed += Int64(encryptedChunk.count)
            progressHandler?(bytesProcessed)
        }

        // Parse response headers for metadata.
        var mimeType: String? = nil
        if let contentType = httpResponse.allHeaderFields["Content-Type"] as? String {
            mimeType = contentType
        }

        var fileName: String? = announcement.fileName
        if fileName == nil,
           let disposition = httpResponse.allHeaderFields["Content-Disposition"] as? String {
            // Parse filename from Content-Disposition.
            if let filenameRange = disposition.range(of: "filename=\"") {
                let rest = disposition[filenameRange.upperBound...]
                if let endQuote = rest.range(of: "\"") {
                    fileName = String(rest[..<endQuote.lowerBound])
                }
            }
        }

        return PullResult(
            contentId: announcement.contentId,
            data: decrypted,
            originalSize: decoder.originalSize,
            mimeType: mimeType,
            fileName: fileName
        )
    }

    /// Generate a random 16-byte hex nonce (matches Rust 16..=128 hex chars).
    private static func generateNonceHex() -> String {
        var bytes = [UInt8](repeating: 0, count: 16)
        _ = SecRandomCopyBytes(kSecRandomDefault, bytes.count, &bytes)
        return bytes.map { String(format: "%02x", $0) }.joined()
    }
}

enum HTTPError: Error, LocalizedError {
    case invalidResponse
    case statusCode(Int)
    case notEncrypted
    case responseTooShort

    var errorDescription: String? {
        switch self {
        case .invalidResponse: return "Invalid HTTP response"
        case .statusCode(let code): return "HTTP \(code)"
        case .notEncrypted: return "Response missing X-FenixHub-Encrypted header"
        case .responseTooShort: return "Response too short for FNX2 header"
        }
    }
}

private extension Data {
    var hexString: String {
        map { String(format: "%02x", $0) }.joined()
    }
}
