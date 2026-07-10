import XCTest
@testable import FenixHubCore

final class FNX2EncoderTests: XCTestCase {

    func testHeaderFormat() {
        let encKey = Data(repeating: 0x42, count: 32)
        let encoder = FNX2Encoder(encKey: encKey, originalSize: 1024)
        let header = encoder.header()

        // FNX2 magic
        XCTAssertEqual(header.prefix(4), Data(FNX2_MAGIC))
        // Total size: 4 + 12 + 4 + 8 + 1 = 29
        XCTAssertEqual(header.count, FNX2_HEADER_SIZE)
    }

    func testRoundTripSmallData() throws {
        let encKey = Data(repeating: 0x01, count: 32)
        let originalSize: UInt64 = 64
        let encoder = FNX2Encoder(encKey: encKey, originalSize: originalSize)
        let header = encoder.header()

        let plaintext = Data("Hello FenixHub! This is a test of FNX2 encryption.".utf8)
        let encrypted = try encoder.encryptChunk(plaintext)

        // Decrypt
        let decoder = try FNX2Decoder(encKey: encKey, header: header)
        let decrypted = try decoder.decryptChunk(encrypted)

        XCTAssertEqual(decrypted, plaintext)
    }

    func testRoundTripMultipleChunks() throws {
        let encKey = Data(repeating: 0xAB, count: 32)
        let chunkSize = FNX2_CHUNK_SIZE
        let plaintext = Data(repeating: 0x42, count: chunkSize * 2 + 100)

        let encoder = FNX2Encoder(encKey: encKey, originalSize: UInt64(plaintext.count))
        let header = encoder.header()

        var encryptedChunks: [Data] = []
        var offset = 0
        while offset < plaintext.count {
            let end = min(offset + FNX2_CHUNK_SIZE, plaintext.count)
            let chunk = plaintext[offset..<end]
            let enc = try encoder.encryptChunk(Data(chunk))
            encryptedChunks.append(enc)
            offset = end
        }

        let decoder = try FNX2Decoder(encKey: encKey, header: header)
        var decrypted = Data()
        for chunk in encryptedChunks {
            let dec = try decoder.decryptChunk(chunk)
            decrypted.append(dec)
        }

        XCTAssertEqual(decrypted, plaintext)
    }

    func testInvalidMagicFails() {
        let encKey = Data(repeating: 0xFF, count: 32)
        let invalidHeader = Data(repeating: 0x00, count: FNX2_HEADER_SIZE)

        XCTAssertThrowsError(try FNX2Decoder(encKey: encKey, header: invalidHeader)) { error in
            XCTAssertTrue(error is FNX2Error)
        }
    }

    func testInvalidHeaderTooShort() {
        let encKey = Data(repeating: 0xFF, count: 32)
        let shortHeader = Data(repeating: 0x00, count: 10)

        XCTAssertThrowsError(try FNX2Decoder(encKey: encKey, header: shortHeader)) { error in
            XCTAssertTrue(error is FNX2Error)
        }
    }

    func testDifferentKeysFail() throws {
        let keyA = Data(repeating: 0x01, count: 32)
        let keyB = Data(repeating: 0x02, count: 32)
        let plaintext = Data("secret message".utf8)

        let encoder = FNX2Encoder(encKey: keyA, originalSize: UInt64(plaintext.count))
        let header = encoder.header()
        let encrypted = try encoder.encryptChunk(plaintext)

        let decoder = try FNX2Decoder(encKey: keyB, header: header)
        XCTAssertThrowsError(try decoder.decryptChunk(encrypted))
    }

    func testEmptyPlaintext() throws {
        let encKey = Data(repeating: 0x00, count: 32)
        let encoder = FNX2Encoder(encKey: encKey, originalSize: 0)
        let header = encoder.header()

        let encrypted = try encoder.encryptChunk(Data())
        let decoder = try FNX2Decoder(encKey: encKey, header: header)
        let decrypted = try decoder.decryptChunk(encrypted)

        XCTAssertEqual(decrypted, Data())
    }
}
