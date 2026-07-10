import Foundation

extension UInt32 {
    var bigEndianBytes: [UInt8] {
        withUnsafeBytes(of: bigEndian) { [UInt8]($0) }
    }
}

extension UInt64 {
    var bigEndianBytes: [UInt8] {
        withUnsafeBytes(of: bigEndian) { [UInt8]($0) }
    }
}

func UInt32(bigEndianFrom data: Data) -> UInt32 {
    data.withUnsafeBytes { $0.load(as: UInt32.self).bigEndian }
}

func UInt64(bigEndianFrom data: Data) -> UInt64 {
    data.withUnsafeBytes { $0.load(as: UInt64.self).bigEndian }
}
