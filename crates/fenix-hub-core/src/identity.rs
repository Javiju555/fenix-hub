/// FenixHub identity system.
///
/// A "group" is defined by a passphrase (a PIN, phrase, or anything the user sets).
/// The passphrase never leaves the device — Argon2id derives a deterministic 32-byte
/// `group_key`. Any device using the same passphrase derives the same group_key and
/// can communicate.
///
/// ## Key hierarchy
///
/// ```text
/// passphrase
///     │  Argon2id (64 MiB, 3 iterations, 1 lane)
///     ▼
/// group_key (32 B) ── persisted to disk as hex; passphrase never saved
///     │  HKDF-SHA256
///     ├──► mac_key  (32 B) — HMAC-SHA256 for HTTP request authentication
///     └──► enc_key  (32 B) — AES-256-GCM for content encryption
/// ```
///
/// Deriving separate sub-keys from `group_key` via HKDF is standard practice
/// (key separation). The `group_id` (first 16 bytes of group_key, hex-encoded) is
/// advertised in mDNS so peers can filter out foreign groups before making HTTP calls.
use anyhow::Result;
use argon2::{Algorithm, Argon2, Params, Version};
use hkdf::Hkdf;
use hmac::{Hmac, Mac};
use sha2::Sha256;

/// Argon2id salt — changing this bumps the protocol version and invalidates
/// all existing group keys (users must re-enter their passphrase).
/// v2: increased memory cost (64 MiB → stronger brute-force resistance).
const ARGON2_SALT: &[u8] = b"fenixhub-v2";

/// HKDF info strings for key separation.
const HKDF_INFO_MAC: &[u8] = b"fenixhub-v2-mac";
const HKDF_INFO_ENC: &[u8] = b"fenixhub-v2-enc";

type HmacSha256 = Hmac<Sha256>;

#[derive(Clone)]
pub struct GroupIdentity {
    /// 32-byte group key derived from passphrase via Argon2id.
    /// This is the shared secret between all devices of the same group.
    /// Persisted to disk (as hex); passphrase is never saved.
    group_key: [u8; 32],

    /// HMAC-SHA256 sub-key, derived from group_key via HKDF.
    /// Used to authenticate all HTTP requests (X-FenixHub-Auth header).
    mac_key: [u8; 32],

    /// AES-256-GCM sub-key, derived from group_key via HKDF.
    /// Used to encrypt all content in transit.
    enc_key: [u8; 32],

    /// Human-readable device name shown to peers (e.g. "Arch Desktop").
    pub device_name: String,
}

impl GroupIdentity {
    /// Derive a group identity from a passphrase.
    ///
    /// Deterministic: the same passphrase always produces the same `group_key`
    /// (and therefore the same `mac_key` / `enc_key`). This is intentional —
    /// it allows any device with the same passphrase to join the group without
    /// an out-of-band key exchange.
    ///
    /// Argon2id parameters (v2):
    /// - Memory:      65536 KiB (64 MiB) — resists GPU/ASIC attacks
    /// - Iterations:  3 passes
    /// - Parallelism: 1 lane
    pub fn from_passphrase(passphrase: &str, device_name: &str) -> Result<Self> {
        let params = Params::new(65536, 3, 1, Some(32))
            .map_err(|e| anyhow::anyhow!("Invalid Argon2 params: {}", e))?;
        let argon2 = Argon2::new(Algorithm::Argon2id, Version::V0x13, params);

        let mut group_key = [0u8; 32];
        argon2
            .hash_password_into(passphrase.as_bytes(), ARGON2_SALT, &mut group_key)
            .map_err(|e| anyhow::anyhow!("Key derivation failed: {}", e))?;

        Ok(Self::from_group_key(group_key, device_name))
    }

    /// Restore identity from a previously persisted key (hex-encoded).
    /// Used on startup to avoid re-deriving from passphrase.
    pub fn from_key_hex(key_hex: &str, device_name: &str) -> Result<Self> {
        let bytes = hex::decode(key_hex).map_err(|e| anyhow::anyhow!("Invalid key hex: {}", e))?;
        if bytes.len() != 32 {
            anyhow::bail!("Key must be 32 bytes, got {}", bytes.len());
        }
        let mut group_key = [0u8; 32];
        group_key.copy_from_slice(&bytes);
        Ok(Self::from_group_key(group_key, device_name))
    }

    /// Internal: build identity from raw group_key + derive sub-keys.
    fn from_group_key(group_key: [u8; 32], device_name: &str) -> Self {
        let (mac_key, enc_key) = derive_subkeys(&group_key);
        Self {
            group_key,
            mac_key,
            enc_key,
            device_name: device_name.to_string(),
        }
    }

    /// Returns the full group key as hex (for persistence — stored locally, never sent).
    pub fn key_hex(&self) -> String {
        hex::encode(self.group_key)
    }

    /// Returns the first 16 bytes of group_key as hex (public group identifier).
    /// Safe to advertise in mDNS — peers use it to filter foreign groups.
    pub fn group_id(&self) -> String {
        hex::encode(&self.group_key[..16])
    }

    /// Returns the AES-256-GCM encryption key (derived via HKDF from group_key).
    /// Used by the content server and client to encrypt/decrypt transferred bytes.
    pub fn enc_key(&self) -> &[u8; 32] {
        &self.enc_key
    }

    /// Signs a message with HMAC-SHA256 using the MAC sub-key.
    pub fn sign(&self, message: &[u8]) -> Vec<u8> {
        let mut mac =
            HmacSha256::new_from_slice(&self.mac_key).expect("HMAC key size is always valid");
        mac.update(message);
        mac.finalize().into_bytes().to_vec()
    }

    /// Verifies an HMAC-SHA256 signature from a peer (constant-time comparison).
    pub fn verify(&self, message: &[u8], signature: &[u8]) -> bool {
        let mut mac =
            HmacSha256::new_from_slice(&self.mac_key).expect("HMAC key size is always valid");
        mac.update(message);
        mac.verify_slice(signature).is_ok()
    }
}

/// Derives the MAC and encryption sub-keys from the group_key via HKDF-SHA256.
///
/// HKDF expands the group_key into two independent 32-byte keys using distinct
/// info strings. This ensures that even if the MAC key were somehow leaked, the
/// encryption key remains safe (and vice versa).
fn derive_subkeys(group_key: &[u8; 32]) -> ([u8; 32], [u8; 32]) {
    let hkdf = Hkdf::<Sha256>::new(None, group_key);

    let mut mac_key = [0u8; 32];
    let mut enc_key = [0u8; 32];

    hkdf.expand(HKDF_INFO_MAC, &mut mac_key)
        .expect("HKDF expand for MAC key: output length is valid");
    hkdf.expand(HKDF_INFO_ENC, &mut enc_key)
        .expect("HKDF expand for ENC key: output length is valid");

    (mac_key, enc_key)
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn same_passphrase_same_keys() {
        let a = GroupIdentity::from_passphrase("test", "A").unwrap();
        let b = GroupIdentity::from_passphrase("test", "B").unwrap();
        assert_eq!(a.group_key, b.group_key);
        assert_eq!(a.mac_key, b.mac_key);
        assert_eq!(a.enc_key, b.enc_key);
    }

    #[test]
    fn different_passphrases_different_keys() {
        let a = GroupIdentity::from_passphrase("foo", "dev").unwrap();
        let b = GroupIdentity::from_passphrase("bar", "dev").unwrap();
        assert_ne!(a.group_key, b.group_key);
        assert_ne!(a.enc_key, b.enc_key);
    }

    #[test]
    fn mac_and_enc_keys_differ() {
        let id = GroupIdentity::from_passphrase("passphrase", "dev").unwrap();
        assert_ne!(id.mac_key, id.enc_key);
    }

    #[test]
    fn round_trip_hex() {
        let original = GroupIdentity::from_passphrase("hello", "dev").unwrap();
        let restored = GroupIdentity::from_key_hex(&original.key_hex(), "dev").unwrap();
        assert_eq!(original.group_key, restored.group_key);
        assert_eq!(original.enc_key, restored.enc_key);
    }

    #[test]
    fn sign_verify_round_trip() {
        let id = GroupIdentity::from_passphrase("pw", "dev").unwrap();
        let sig = id.sign(b"content-id-123");
        assert!(id.verify(b"content-id-123", &sig));
        assert!(!id.verify(b"content-id-456", &sig));
    }

    #[test]
    fn group_id_is_16_bytes_hex() {
        let id = GroupIdentity::from_passphrase("abc", "dev").unwrap();
        assert_eq!(id.group_id().len(), 32); // 16 bytes → 32 hex chars
    }
}
