/// Local HTTP server for serving FenixHub content to peers.
///
/// Uses Network framework (NWListener) for a lightweight, zero-dependency
/// HTTP server that:
/// - Binds to port 7473 (or fallback to random)
/// - Handles GET /content/{id}
/// - Validates HMAC authentication headers
/// - Streams FNX2 encrypted content
///
/// Built-in HTTP server frameworks like SwiftNIO are avoided for MVP to
/// keep the initial build simple. Network.framework is available on all iOS
/// and macOS versions and is App Store compatible.
///
/// For the MVP, a minimal HTTP/1.0 response parser is implemented directly.
///
/// Rust source-of-truth:
///   crates/fenix-hub-core/src/server.rs

import Foundation
import Network

class LocalHttpServer {
    private var listener: NWListener?
    private let queue = DispatchQueue(label: "com.fenixhub.httpserver")
    private var identity: GroupIdentity?
    private var contentStore: [String: ContentItem] = [:]
    private var port: UInt16 = DEFAULT_SERVER_PORT

    /// Start the HTTP server on the given port (or fallback to ephemeral).
    /// - Parameter identity: the group identity for auth verification
    /// - Parameter content: dictionary of content items keyed by content_id
    /// - Returns: the actual port the server bound to, or nil on failure
    @discardableResult
    func start(identity: GroupIdentity, content: [String: ContentItem]) throws -> UInt16 {
        stop()

        self.identity = identity
        self.contentStore = content

        let params = NWParameters.tcp
        let port = NWEndpoint.Port(rawValue: port)!

        listener = try NWListener(using: params, on: port)
        listener?.newConnectionHandler = { [weak self] connection in
            self?.handleConnection(connection)
        }
        listener?.start(queue: queue)

        if let actualPort = listener?.port?.rawValue {
            self.port = actualPort
        }

        return self.port
    }

    /// Stop the HTTP server.
    func stop() {
        listener?.cancel()
        listener = nil
    }

    /// Current port the server is listening on.
    func currentPort() -> UInt16 {
        port
    }

    // MARK: - Connection handling

    private func handleConnection(_ connection: NWConnection) {
        connection.start(queue: queue)

        let bufferSize = 4096
        connection.receive(minimumIncompleteLength: 1, maximumLength: bufferSize) {
            [weak self] data, _, isComplete, error in
            guard let self = self, let data = data, error == nil else {
                connection.cancel()
                return
            }

            let response = self.processRequest(data)
            connection.send(content: response, completion: .contentProcessed({ _ in
                if isComplete || data.contains(Data("HTTP/1.1".utf8)) {
                    connection.cancel()
                }
            }))
        }
    }

    /// Minimal HTTP request parser and response builder.
    private func processRequest(_ data: Data) -> Data {
        guard let requestStr = String(data: data, encoding: .utf8),
              let identity = identity else {
            return httpResponse(status: 400, body: "Bad Request")
        }

        let lines = requestStr.components(separatedBy: "\r\n")
        guard let requestLine = lines.first?.components(separatedBy: " "),
              requestLine.count >= 2 else {
            return httpResponse(status: 400, body: "Bad Request")
        }

        let method = requestLine[0]
        let path = requestLine[1]

        guard method == "GET" else {
            return httpResponse(status: 405, body: "Method Not Allowed")
        }

        // Parse path: /content/{id}
        guard path.hasPrefix("/content/") else {
            return httpResponse(status: 404, body: "Not Found")
        }

        let contentId = String(path.dropFirst("/content/".count))

        // Parse headers
        let headers = parseHeaders(from: lines)

        // Verify auth
        let authHeader = headers[HMAC_HEADER]
        let timestampHeader = headers[AUTH_TIMESTAMP_HEADER]
        let nonceHeader = headers[AUTH_NONCE_HEADER]
        let bodyShaHeader = headers[AUTH_BODY_SHA256_HEADER]

        let signer = AuthSigner(identity: identity)
        guard signer.verifyRequest(
            method: method,
            path: path,
            timestampHeader: timestampHeader,
            nonceHeader: nonceHeader,
            authHeader: authHeader,
            bodySha256Header: bodyShaHeader
        ) else {
            return httpResponse(status: 401, body: "Unauthorized")
        }

        // Find content
        guard let item = contentStore[contentId] else {
            return httpResponse(status: 404, body: "Not Found")
        }

        // Build response headers
        var respHeaders: [String: String] = [:]
        respHeaders[ENCRYPTED_HEADER] = "2"
        respHeaders["Content-Type"] = item.mimeType ?? "application/octet-stream"

        if let fileName = item.fileName {
            let safeName = fileName
                .replacingOccurrences(of: "\\", with: "_")
                .replacingOccurrences(of: "\"", with: "_")
            respHeaders["Content-Disposition"] = "inline; filename=\"\(safeName)\""
        }

        // Encrypt and stream content (for MVP, small payloads are buffered).
        let encoder = FNX2Encoder(encKey: identity.encKey, originalSize: UInt64(item.data.count))
        var fnxStream = encoder.header()

        let chunks = stride(from: 0, to: item.data.count, by: FNX2_CHUNK_SIZE).map {
            item.data[$0..<min($0 + FNX2_CHUNK_SIZE, item.data.count)]
        }

        for chunk in chunks {
            if let encrypted = try? encoder.encryptChunk(Data(chunk)) {
                fnxStream.append(encrypted)
            }
        }

        return httpResponse(status: 200, headers: respHeaders, body: fnxStream)
    }

    // MARK: - Helpers

    private func parseHeaders(from lines: [String]) -> [String: String] {
        var headers: [String: String] = [:]
        for line in lines.dropFirst() {
            if line.isEmpty { break }
            if let colonRange = line.range(of: ": ") {
                let key = String(line[..<colonRange.lowerBound])
                let value = String(line[colonRange.upperBound...])
                headers[key] = value
            }
        }
        return headers
    }

    private func httpResponse(status: Int, body: String) -> Data {
        httpResponse(status: status, headers: [:], body: body.data(using: .utf8) ?? Data())
    }

    private func httpResponse(status: Int, headers: [String: String] = [:], body: Data) -> Data {
        let statusText: String
        switch status {
        case 200: statusText = "OK"
        case 400: statusText = "Bad Request"
        case 401: statusText = "Unauthorized"
        case 404: statusText = "Not Found"
        case 405: statusText = "Method Not Allowed"
        default: statusText = "Internal Server Error"
        }

        var response = "HTTP/1.1 \(status) \(statusText)\r\n"
        response += "Content-Length: \(body.count)\r\n"
        for (key, value) in headers {
            response += "\(key): \(value)\r\n"
        }
        response += "\r\n"

        var result = response.data(using: .utf8)!
        result.append(body)
        return result
    }
}

/// Content item stored in the local server.
struct ContentItem {
    let id: String
    let data: Data
    let mimeType: String?
    let fileName: String?
}
