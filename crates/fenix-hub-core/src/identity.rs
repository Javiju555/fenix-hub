use anyhow::Result;
use argon2::{Algorithm, Argon2, Params, Version};
/// FenixHub identity system.
///
/// A "group" is defined by a passphrase (can be a PIN, phrase, or anything the user sets).
/// The passphrase never leaves the device — Argon2id derives a deterministic 32-byte group key.
/// Any device that uses the same passphrase derives the same group key and can communicate.
///
/// The group key is used as an HMAC-SHA256 key to sign all mDNS announcements and HTTP
/// requests. Strangers on the same LAN with a different passphrase cannot interact.
use hmac::{Hmac, Mac};
use sha2::Sha256;

const ARGON2_SALT: &[u8] = b"fenixhub-v1-salt";

type HmacSha256 = Hmac<Sha256>;

#[derive(Clone)]
pub struct GroupIdentity {
    /// 32-byte group key derived from passphrase via Argon2id.
    /// This is the shared secret between all devices of the same group.
    group_key: [u8; 32],
    /// Human-readable device name shown to peers (e.g. "Arch Desktop")
    pub device_name: String,
}

impl GroupIdentity {
    /// Derive a group identity from a passphrase.
    /// This is deterministic: same passphrase always produces same group_key.
    pub fn from_passphrase(passphrase: &str, device_name: &str) -> Result<Self> {
        let params = Params::new(4096, 3, 1, Some(32))
            .map_err(|e| anyhow::anyhow!("Invalid Argon2 params: {}", e))?;
        let argon2 = Argon2::new(Algorithm::Argon2id, Version::V0x13, params);
        let mut group_key = [0u8; 32];
        argon2
            .hash_password_into(passphrase.as_bytes(), ARGON2_SALT, &mut group_key)
            .map_err(|e| anyhow::anyhow!("Key derivation failed: {}", e))?;
        Ok(Self {
            group_key,
            device_name: device_name.to_string(),
        })
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
        Ok(Self {
            group_key,
            device_name: device_name.to_string(),
        })
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

    /// Signs a message with HMAC-SHA256 using the full group key.
    pub fn sign(&self, message: &[u8]) -> Vec<u8> {
        let mut mac =
            HmacSha256::new_from_slice(&self.group_key).expect("HMAC key size is always valid");
        mac.update(message);
        mac.finalize().into_bytes().to_vec()
    }

    /// Verifies a signature from a peer.
    pub fn verify(&self, message: &[u8], signature: &[u8]) -> bool {
        let mut mac =
            HmacSha256::new_from_slice(&self.group_key).expect("HMAC key size is always valid");
        mac.update(message);
        mac.verify_slice(signature).is_ok()
    }
}
