/// FenixHub announcement model matching Rust `Announcement`.
///
/// Source-of-truth: crates/fenix-hub-core/src/protocol.rs
///
/// This JSON is serialized into mDNS TXT records for peer discovery.
/// Keep it compact: mDNS TXT records have ~255-byte limits per string.

import Foundation

/// How content is being shared (matches Rust `SendMode`).
///
/// Rust serialization (without serde repr attributes):
/// - `Broadcast {}` → `{"Broadcast": {}}`
/// - `Direct { target_device: "x" }` → `{"Direct": {"target_device": "x"}}`
enum SendMode: Codable, Equatable {
    case broadcast
    case direct(targetDevice: String)

    private enum CodingKeys: String, CodingKey {
        case broadcast = "Broadcast"
        case direct = "Direct"
    }

    private enum DirectKeys: String, CodingKey {
        case targetDevice = "target_device"
    }

    init(from decoder: Decoder) throws {
        let container = try decoder.container(keyedBy: CodingKeys.self)
        if container.allKeys.contains(.broadcast) {
            self = .broadcast
        } else if container.allKeys.contains(.direct) {
            let nested = try container.nestedContainer(keyedBy: DirectKeys.self, forKey: .direct)
            let target = try nested.decode(String.self, forKey: .targetDevice)
            self = .direct(targetDevice: target)
        } else {
            throw DecodingError.dataCorrupted(
                .init(codingPath: decoder.codingPath,
                      debugDescription: "Expected Broadcast or Direct"))
        }
    }

    func encode(to encoder: Encoder) throws {
        var container = encoder.container(keyedBy: CodingKeys.self)
        switch self {
        case .broadcast:
            var nested = container.nestedContainer(keyedBy: BroadcastEmptyKeys.self, forKey: .broadcast)
            try nested.encodeNil(forKey: .empty)
        case .direct(let target):
            var nested = container.nestedContainer(keyedBy: DirectKeys.self, forKey: .direct)
            try nested.encode(target, forKey: .targetDevice)
        }
    }

    private enum BroadcastEmptyKeys: String, CodingKey {
        case empty = "{}"
        // Hack: encodeNil on this key produces `{}` in the output.
    }
}

/// Content type classification (matches Rust `ContentType`).
enum ContentType: String, Codable {
    case text
    case file
    case image
    case video
    case audio
    case url
    case folder
    case empty
}

/// mDNS announcement payload — serialized as JSON in TXT records.
///
/// Matches the Rust `Announcement` struct exactly.
struct Announcement: Codable, Equatable {
    /// Protocol version — used for capability negotiation.
    /// Defaults to 0 (legacy) when absent in parsed JSON.
    var protocolVersion: UInt8

    /// First 16 bytes of group_key as hex — used to filter foreign groups.
    let groupId: String

    /// Unique content item ID (UUID).
    let contentId: String

    /// Device name that is serving this content.
    let deviceName: String

    /// Short preview shown in hub UI (max ~80 chars).
    let preview: String

    /// Content type classification.
    let contentType: ContentType

    /// Size of the content in bytes.
    let sizeBytes: UInt64

    /// Optional file name.
    var fileName: String?

    /// Optional MIME type.
    var mimeType: String?

    /// How the content is being shared.
    let sendMode: SendMode

    /// Unix timestamp (milliseconds) when this item was added.
    let createdAt: UInt64

    /// Port where the ephemeral HTTP server is listening.
    let port: UInt16

    enum CodingKeys: String, CodingKey {
        case protocolVersion = "protocol_version"
        case groupId = "group_id"
        case contentId = "content_id"
        case deviceName = "device_name"
        case preview
        case contentType = "content_type"
        case sizeBytes = "size_bytes"
        case fileName = "file_name"
        case mimeType = "mime_type"
        case sendMode = "send_mode"
        case createdAt = "created_at"
        case port
    }
}
