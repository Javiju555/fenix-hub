/// Encodes and decodes FenixHub announcements to/from mDNS TXT records.
///
/// Because mDNS TXT records have a practical limit of ~240 bytes per key-value
/// pair, large announcements are split across multiple TXT keys (`data0`,
/// `data1`, ...) with a `count` field indicating how many chunks exist.
///
/// Android source-of-truth:
///   android/app/src/main/java/com/fenixhub/mobile/util/AnnouncementCodec.kt
///
/// Desktop parser:
///   crates/fenix-hub-daemon/src/mdns.rs

import Foundation

enum AnnouncementCodec {
    /// Maximum bytes per TXT record value (mDNS practical limit).
    /// Android/Linux mDNS stacks may fragment larger values.
    private static let chunkMaxBytes = TXT_RECORD_MAX_BYTES

    /// Encodes an Announcement into a dictionary of TXT key-value pairs.
    ///
    /// The output contains:
    /// - `group_id`: hex group identifier for peer filtering
    /// - `count`: number of JSON chunks
    /// - `data0`, `data1`, ...: chunked JSON payload
    /// - Optional: `port`, `device_name` for quick filtering
    ///
    /// This matches Android's `AnnouncementCodec.encode()` output.
    static func encode(_ announcement: Announcement) throws -> [String: String] {
        let jsonData = try JSONEncoder().encode(announcement)
        guard let jsonString = String(data: jsonData, encoding: .utf8) else {
            throw CodecError.invalidUTF8
        }

        var txt: [String: String] = [:]
        txt["group_id"] = announcement.groupId

        let chunks = stride(from: 0, to: jsonString.count, by: chunkMaxBytes).map { startIndex in
            let start = jsonString.index(jsonString.startIndex, offsetBy: startIndex)
            let end = jsonString.index(start, offsetBy: min(chunkMaxBytes, jsonString.count - startIndex))
            String(jsonString[start..<end])
        }

        txt["count"] = "\(chunks.count)"
        for (i, chunk) in chunks.enumerated() {
            txt["data\(i)"] = chunk
        }

        return txt
    }

    /// Decodes an Announcement from a dictionary of TXT key-value pairs.
    ///
    /// Expects the reverse of `encode()`:
    /// - Reads `count` to determine number of chunks
    /// - Concatenates `data0`, `data1`, ... into a full JSON string
    /// - Parses the JSON into an `Announcement`
    ///
    /// Returns nil if parsing fails (e.g., foreign group, malformed data).
    static func decode(from txt: [String: String]) -> Announcement? {
        guard let countStr = txt["count"],
              let count = Int(countStr),
              count > 0 else {
            return nil
        }

        var jsonString = ""
        for i in 0..<count {
            guard let chunk = txt["data\(i)"] else {
                return nil
            }
            jsonString += chunk
        }

        guard let jsonData = jsonString.data(using: .utf8) else {
            return nil
        }

        return try? JSONDecoder().decode(Announcement.self, from: jsonData)
    }

    /// Quick pre-filter: returns the group_id from TXT without full decode.
    /// This allows peers to discard foreign announcements cheaply.
    static func peekGroupId(from txt: [String: String]) -> String? {
        return txt["group_id"]
    }
}

enum CodecError: Error {
    case invalidUTF8
    case chunkMismatch
}
