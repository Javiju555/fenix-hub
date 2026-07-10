import XCTest
@testable import FenixHubCore

final class AnnouncementCodecTests: XCTestCase {

    func testEncodeDecodeRoundTrip() throws {
        let announcement = Announcement(
            protocolVersion: 3,
            groupId: "a1b2c3d4e5f6a7b8c9d0e1f2a3b4c5d6",
            contentId: "test-content-id-123",
            deviceName: "Test iPhone",
            preview: "test.txt",
            contentType: .file,
            sizeBytes: 1024,
            fileName: "test.txt",
            mimeType: "text/plain",
            sendMode: .broadcast,
            createdAt: 1_720_000_000_000,
            port: 7473
        )

        let txt = try AnnouncementCodec.encode(announcement)
        XCTAssertEqual(txt["group_id"], announcement.groupId)

        let decoded = AnnouncementCodec.decode(from: txt)
        XCTAssertNotNil(decoded)
        XCTAssertEqual(decoded?.contentId, announcement.contentId)
        XCTAssertEqual(decoded?.deviceName, announcement.deviceName)
        XCTAssertEqual(decoded?.protocolVersion, announcement.protocolVersion)
        XCTAssertEqual(decoded?.port, announcement.port)
    }

    func testDirectSendModeRoundTrip() throws {
        let announcement = Announcement(
            protocolVersion: 3,
            groupId: "abcdef1234567890abcdef1234567890",
            contentId: "uuid-direct-test",
            deviceName: "Sender iPhone",
            preview: "private.txt",
            contentType: .file,
            sizeBytes: 512,
            fileName: "private.txt",
            mimeType: "text/plain",
            sendMode: .direct(targetDevice: "Peer Desktop"),
            createdAt: 1_720_000_000_001,
            port: 7473
        )

        let txt = try AnnouncementCodec.encode(announcement)
        let decoded = AnnouncementCodec.decode(from: txt)
        XCTAssertNotNil(decoded)
        XCTAssertEqual(decoded?.sendMode, .direct(targetDevice: "Peer Desktop"))
    }

    func testChunkingLargePreview() throws {
        // Create an announcement with a long preview to force chunking.
        let longPreview = String(repeating: "Lorem ipsum dolor sit amet. ", count: 50)
        let announcement = Announcement(
            protocolVersion: 3,
            groupId: "1234567890abcdef1234567890abcdef",
            contentId: "chunk-test",
            deviceName: "Chunk Test",
            preview: longPreview,
            contentType: .text,
            sizeBytes: 0,
            fileName: nil,
            mimeType: nil,
            sendMode: .broadcast,
            createdAt: 1_720_000_000_002,
            port: 7473
        )

        let txt = try AnnouncementCodec.encode(announcement)
        guard let countStr = txt["count"], let count = Int(countStr), count > 0 else {
            XCTFail("Should have at least one data chunk")
            return
        }

        // Verify all data chunks are present.
        for i in 0..<count {
            XCTAssertNotNil(txt["data\(i)"], "Missing data\(i)")
        }

        // Verify round-trip.
        let decoded = AnnouncementCodec.decode(from: txt)
        XCTAssertNotNil(decoded)
        XCTAssertEqual(decoded?.preview, longPreview)
    }

    func testDecodeInvalidReturnsNil() {
        let invalidTxt: [String: String] = ["count": "1"]
        XCTAssertNil(AnnouncementCodec.decode(from: invalidTxt))
    }

    func testPeekGroupId() throws {
        let announcement = Announcement(
            protocolVersion: 3,
            groupId: "peek-test-group-id-hex-1234567890a",
            contentId: "peek-test",
            deviceName: "Peek Test",
            preview: "peek",
            contentType: .text,
            sizeBytes: 0,
            fileName: nil,
            mimeType: nil,
            sendMode: .broadcast,
            createdAt: 1_720_000_000_003,
            port: 7473
        )

        let txt = try AnnouncementCodec.encode(announcement)
        let groupId = AnnouncementCodec.peekGroupId(from: txt)
        XCTAssertEqual(groupId, announcement.groupId)
    }

    func testBroadcastSendModeJSON() throws {
        // Verify that Broadcast serializes to JSON as `{"Broadcast":{}}`.
        let sendMode = SendMode.broadcast
        let encoded = try JSONEncoder().encode(sendMode)
        let jsonStr = String(data: encoded, encoding: .utf8)!
        XCTAssertTrue(jsonStr.contains("Broadcast"))
    }
}
