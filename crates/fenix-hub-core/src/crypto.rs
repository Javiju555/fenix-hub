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
use rand::RngCore;

use crate::protocol::{FNX2_COMPRESSION_NONE, FNX2_HEADER_SIZE};

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

/// Computes per-chunk nonce: base_nonce XOR chunk_index (padded to 12 bytes).
fn chunk_nonce(base_nonce: &[u8; 12], chunk_index: u64) -> [u8; 12] {
    let mut nonce = *base_nonce;
    let index_bytes = chunk_index.to_be_bytes();
    for (i, byte) in index_bytes.iter().enumerate() {
        nonce[4 + i] ^= *byte;
    }
    nonce
}

/// Encrypts data in chunks for streaming AEAD (FNX2 protocol).
///
/// Each chunk gets its own nonce derived from base_nonce XOR chunk_index.
/// This allows independent verification of each chunk.
///
/// Returns a writer that accepts plaintext and yields encrypted chunks:
/// Header: FNX2(4) + base_nonce(12) + total_chunks(4) + original_size(8) + compression(1)
/// Per chunk: ciphertext + GCM tag
pub struct ChunkEncoder {
    cipher: Aes256Gcm,
    base_nonce: [u8; 12],
    chunk_index: u32,
    total_chunks: u32,
    original_size: u64,
    header_written: bool,
}

impl ChunkEncoder {
    /// Creates a new chunk encoder.
    /// `total_chunks` and `original_size` are needed for the FNX2 header.
    pub fn new(key: &[u8; 32], total_chunks: u32, original_size: u64) -> Self {
        let mut base_nonce = [0u8; 12];
        OsRng.fill_bytes(&mut base_nonce);
        let cipher = Aes256Gcm::new(key.into());
        Self {
            cipher,
            base_nonce,
            chunk_index: 0,
            total_chunks,
            original_size,
            header_written: false,
        }
    }

    /// Returns the FNX2 header bytes.
    pub fn header(&self) -> Vec<u8> {
        let mut header = Vec::with_capacity(FNX2_HEADER_SIZE);
        header.extend_from_slice(b"FNX2");
        header.extend_from_slice(&self.base_nonce);
        header.extend_from_slice(&self.total_chunks.to_be_bytes());
        header.extend_from_slice(&self.original_size.to_be_bytes());
        header.push(FNX2_COMPRESSION_NONE);
        header
    }

    /// Encrypts a chunk of plaintext. Call sequentially for each chunk.
    /// Returns the encrypted chunk (ciphertext + GCM tag).
    pub fn encrypt_chunk(&mut self, plaintext: &[u8]) -> Result<Vec<u8>> {
        if !self.header_written {
            self.header_written = true;
        }

        let nonce = chunk_nonce(&self.base_nonce, self.chunk_index as u64);
        let nonce = Nonce::from_slice(&nonce);
        let ciphertext = self
            .cipher
            .encrypt(nonce, plaintext)
            .map_err(|e| anyhow::anyhow!("Chunk encryption failed: {}", e))?;

        self.chunk_index += 1;
        Ok(ciphertext)
    }
}

/// Decodes FNX2 header and prepares for chunked decryption.
pub struct ChunkDecoder {
    pub base_nonce: [u8; 12],
    pub total_chunks: u32,
    pub original_size: u64,
    chunk_index: u32,
    cipher: Aes256Gcm,
}

impl ChunkDecoder {
    /// Parses FNX2 header from data and creates a decoder.
    pub fn new(key: &[u8; 32], header: &[u8]) -> Result<Self> {
        if header.len() < FNX2_HEADER_SIZE {
            anyhow::bail!("FNX2 header too short: {} bytes", header.len());
        }
        if &header[..4] != b"FNX2" {
            anyhow::bail!("Invalid FNX2 magic");
        }

        let mut base_nonce = [0u8; 12];
        base_nonce.copy_from_slice(&header[4..16]);

        let total_chunks = u32::from_be_bytes(header[16..20].try_into()?);
        let original_size = u64::from_be_bytes(header[20..28].try_into()?);

        Ok(Self {
            base_nonce,
            total_chunks,
            original_size,
            chunk_index: 0,
            cipher: Aes256Gcm::new(key.into()),
        })
    }

    /// Decrypts a single chunk. Call sequentially for each chunk.
    /// Returns plaintext for this chunk.
    pub fn decrypt_chunk(&mut self, ciphertext: &[u8]) -> Result<Vec<u8>> {
        let nonce = chunk_nonce(&self.base_nonce, self.chunk_index as u64);
        let nonce = Nonce::from_slice(&nonce);
        let plaintext = self
            .cipher
            .decrypt(nonce, ciphertext)
            .map_err(|_| anyhow::anyhow!("Chunk {} decryption failed", self.chunk_index))?;

        self.chunk_index += 1;
        Ok(plaintext)
    }
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
