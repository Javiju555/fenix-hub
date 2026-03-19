/// FenixHub identity system.
///
/// A "group" is defined by a passphrase (can be a PIN, phrase, or anything the user sets).
/// The passphrase never leaves the device — Argon2id derives a 32-byte group key from it.
/// Any device that uses the same passphrase derives the same group key and can communicate.
///
/// The group key is used as an HMAC-SHA256 key to sign all mDNS announcements and HTTP
/// requests. Strangers on the same LAN with a different passphrase cannot interact.

use argon2::{Argon2, password_hash::SaltString};
use hmac::{Hmac, Mac};
use sha2::Sha256;
use rand::rngs::OsRng;
use anyhow::Result;

const ARGON2_SALT: &[u8] = b"fenixhub-v1-salt"; // fixed salt — key is deterministic per passphrase

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
    /// Uses Argon2id with a fixed salt — the passphrase is the only variable.
    pub fn from_passphrase(passphrase: &str, device_name: &str) -> Result<Self> {
        let argon2 = Argon2::default();
        let mut group_key = [0u8; 32];

        argon2
            .hash_password_into(
                passphrase.as_bytes(),
                ARGON2_SALT,
                &mut group_key,
            )
            .map_err(|e| anyhow::anyhow!("Key derivation failed: {}", e))?;

        Ok(Self {
            group_key,
            device_name: device_name.to_string(),
        })
    }

    /// Returns the group key as a hex string (used in mDNS TXT records as group identifier).
    /// This is safe to advertise — it allows peers to recognize the group without
    /// being able to derive the passphrase (one-way).
    pub fn group_id(&self) -> String {
        hex::encode(&self.group_key[..16]) // first 16 bytes as public group ID
    }

    /// Signs a message (e.g. announcement JSON) with HMAC-SHA256 using the full group key.
    /// Receivers verify this signature to ensure the sender knows the same passphrase.
    pub fn sign(&self, message: &[u8]) -> Vec<u8> {
        let mut mac = HmacSha256::new_from_slice(&self.group_key)
            .expect("HMAC key size is always valid");
        mac.update(message);
        mac.finalize().into_bytes().to_vec()
    }

    /// Verifies a signature from a peer.
    pub fn verify(&self, message: &[u8], signature: &[u8]) -> bool {
        let mut mac = HmacSha256::new_from_slice(&self.group_key)
            .expect("HMAC key size is always valid");
        mac.update(message);
        mac.verify_slice(signature).is_ok()
    }
}
