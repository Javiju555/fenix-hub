/// AES-256-GCM authenticated encryption for FenixHub content transfer.
///
/// Wire format: `nonce (12 bytes) || ciphertext+tag`
///
/// The encryption key is derived separately from the HMAC key via HKDF
/// (see `identity.rs`), ensuring key separation. The GCM tag (16 bytes)
/// is appended to ciphertext by the aes-gcm crate automatically.
use aes_gcm::{
    aead::{Aead, AeadCore, KeyInit, OsRng},
    Aes256Gcm, Nonce,
};
use anyhow::Result;

/// Size of the AES-GCM nonce in bytes (96-bit, as recommended for GCM).
pub const NONCE_SIZE: usize = 12;

/// Minimum size of valid encrypted data: nonce + GCM tag (no plaintext).
pub const MIN_ENCRYPTED_SIZE: usize = NONCE_SIZE + 16;

/// Encrypts `plaintext` with AES-256-GCM using a fresh random nonce.
///
/// Returns `nonce (12 B) || ciphertext+tag` as a single `Vec<u8>`.
/// The GCM tag provides integrity — any bit-flip in the ciphertext or
/// nonce will cause decryption to fail with an error.
pub fn encrypt(key: &[u8; 32], plaintext: &[u8]) -> Result<Vec<u8>> {
    let cipher = Aes256Gcm::new(key.into());
    let nonce = Aes256Gcm::generate_nonce(&mut OsRng);
    let ciphertext = cipher
        .encrypt(&nonce, plaintext)
        .map_err(|e| anyhow::anyhow!("AES-GCM encryption failed: {}", e))?;

    let mut out = Vec::with_capacity(NONCE_SIZE + ciphertext.len());
    out.extend_from_slice(nonce.as_slice());
    out.extend_from_slice(&ciphertext);
    Ok(out)
}

/// Decrypts `data` (format: `nonce || ciphertext+tag`) with AES-256-GCM.
///
/// Returns plaintext on success. Returns an error if the data is too short,
/// or if authentication fails (wrong key, tampered data, etc.).
pub fn decrypt(key: &[u8; 32], data: &[u8]) -> Result<Vec<u8>> {
    if data.len() < MIN_ENCRYPTED_SIZE {
        anyhow::bail!(
            "Encrypted payload too short: {} bytes (minimum {})",
            data.len(),
            MIN_ENCRYPTED_SIZE
        );
    }
    let (nonce_bytes, ciphertext) = data.split_at(NONCE_SIZE);
    let cipher = Aes256Gcm::new(key.into());
    let nonce = Nonce::from_slice(nonce_bytes);
    cipher
        .decrypt(nonce, ciphertext)
        .map_err(|_| anyhow::anyhow!("AES-GCM decryption failed: invalid key or corrupted data"))
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn round_trip_text() {
        let key = [42u8; 32];
        let plaintext = b"hello fenix";
        let enc = encrypt(&key, plaintext).unwrap();
        assert!(enc.len() >= MIN_ENCRYPTED_SIZE);
        let dec = decrypt(&key, &enc).unwrap();
        assert_eq!(dec, plaintext);
    }

    #[test]
    fn round_trip_empty() {
        let key = [0u8; 32];
        let enc = encrypt(&key, b"").unwrap();
        let dec = decrypt(&key, &enc).unwrap();
        assert_eq!(dec, b"");
    }

    #[test]
    fn wrong_key_fails() {
        let key_a = [1u8; 32];
        let key_b = [2u8; 32];
        let enc = encrypt(&key_a, b"secret").unwrap();
        assert!(decrypt(&key_b, &enc).is_err());
    }

    #[test]
    fn tampered_data_fails() {
        let key = [7u8; 32];
        let mut enc = encrypt(&key, b"data").unwrap();
        // Flip a bit in the ciphertext (after the nonce)
        enc[NONCE_SIZE] ^= 0xff;
        assert!(decrypt(&key, &enc).is_err());
    }

    #[test]
    fn too_short_fails() {
        let key = [0u8; 32];
        assert!(decrypt(&key, &[0u8; 10]).is_err());
    }

    #[test]
    fn each_encrypt_produces_unique_nonce() {
        let key = [3u8; 32];
        let a = encrypt(&key, b"same plaintext").unwrap();
        let b = encrypt(&key, b"same plaintext").unwrap();
        // Nonces must differ (random)
        assert_ne!(&a[..NONCE_SIZE], &b[..NONCE_SIZE]);
        // But both decrypt to the same thing
        assert_eq!(decrypt(&key, &a).unwrap(), decrypt(&key, &b).unwrap());
    }
}
