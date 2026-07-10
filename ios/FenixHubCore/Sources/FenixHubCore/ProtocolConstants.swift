/// Protocol constants extracted from Rust source.
///
/// Source-of-truth files:
/// - crates/fenix-hub-core/src/protocol.rs
/// - crates/fenix-hub-core/src/identity.rs
/// - crates/fenix-hub-core/src/server.rs
/// - crates/fenix-hub-core/src/crypto.rs

import Foundation

// MARK: - Protocol version

/// Current wire protocol version.
/// v0: legacy unencrypted; v1+: AES-256-GCM mandatory; v2: FNX2 chunked AEAD; v3: current stable.
let PROTOCOL_VERSION: UInt8 = 3

// MARK: - FNX2 chunked AEAD wire format

/// FNX2 magic bytes in the stream header.
let FNX2_MAGIC: [UInt8] = [0x46, 0x4E, 0x58, 0x32] // "FNX2"

/// FNX2 header size: magic(4) + base_nonce(12) + total_chunks(4) + original_size(8) + compression(1)
let FNX2_HEADER_SIZE: Int = 29

/// Chunk size for streaming AEAD (4 MB).
let FNX2_CHUNK_SIZE: Int = 4 * 1024 * 1024

/// Compression types
let FNX2_COMPRESSION_NONE: UInt8 = 0x00
let FNX2_COMPRESSION_ZSTD: UInt8 = 0x01

/// AES-GCM nonce size in bytes (96-bit)
let NONCE_SIZE: Int = 12

/// Minimum size of valid encrypted data: nonce + GCM tag
let MIN_ENCRYPTED_SIZE: Int = NONCE_SIZE + 16

// MARK: - mDNS service types

/// mDNS service type for FenixHub content announcements.
let MDNS_SERVICE_TYPE: String = "_fenixhub._tcp.local."

/// mDNS service type for device presence beacons (no content).
let MDNS_PRESENCE_TYPE: String = "_fenixhub-presence._tcp.local."

// MARK: - HTTP server

/// Default content server port.
let DEFAULT_SERVER_PORT: UInt16 = 7473

// MARK: - Auth headers

/// HTTP header carrying the HMAC-SHA256 request signature.
let HMAC_HEADER: String = "X-FenixHub-Auth"

/// HTTP header carrying the request timestamp in unix milliseconds.
let AUTH_TIMESTAMP_HEADER: String = "X-FenixHub-Timestamp"

/// HTTP header carrying a per-request random nonce (hex).
let AUTH_NONCE_HEADER: String = "X-FenixHub-Nonce"

/// HTTP header carrying SHA256(body) as lowercase hex.
let AUTH_BODY_SHA256_HEADER: String = "X-FenixHub-Body-Sha256"

/// HTTP response header indicating encrypted body.
let ENCRYPTED_HEADER: String = "X-FenixHub-Encrypted"

/// Maximum accepted clock skew for authenticated requests (90 seconds).
let AUTH_MAX_SKEW_MS: UInt64 = 90_000

/// SHA-256 of an empty body (lowercase hex).
let EMPTY_BODY_SHA256_HEX: String = "e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855"

// MARK: - TXT record chunking

/// Maximum bytes per TXT record value (mDNS practical limit).
let TXT_RECORD_MAX_BYTES: Int = 240

/// TXT record chunk key prefix: "data0", "data1", ...
let TXT_CHUNK_PREFIX: String = "data"

// MARK: - Key derivation constants

/// Argon2id salt used in Rust: b"fenixhub-v2"
let ARGON2_GROUP_SALT: String = "fenixhub-v2"

/// Argon2id parameters matching Rust code.
let ARGON2_MEMORY_COST: UInt32 = 65536 // 64 MiB
let ARGON2_ITERATIONS: UInt32 = 3
let ARGON2_PARALLELISM: UInt32 = 1
let ARGON2_KEY_LENGTH: Int = 32

/// HKDF info strings for key separation (must match Rust).
let HKDF_INFO_MAC: String = "fenixhub-v2-mac"
let HKDF_INFO_ENC: String = "fenixhub-v2-enc"
let HKDF_INFO_GROUP_ID: String = "fenixhub-v2-group-id"

/// Canonical auth message prefix (matches Rust).
let AUTH_CANONICAL_PREFIX: String = "fenixhub-auth-v1"

/// Minimum passphrase length enforced by Rust.
let MIN_PASSPHRASE_LEN: Int = 10

/// Maximum passphrase length enforced by Rust.
let MAX_PASSPHRASE_LEN: Int = 256
