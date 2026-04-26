/// AES-256-GCM authenticated encryption for FenixHub content transfer.
///
/// Wire format: `nonce (12 bytes) || ciphertext+tag`
///
/// The encryption key is derived separately from the HMAC key via HKDF
/// (see `identity.rs`), ensuring key separation. The GCM tag (16 bytes)
/// is appended to ciphertext by the ring crate automatically.
///
/// Uses the `ring` crate for hardware-accelerated AES (AES-NI / ARM
/// crypto extensions), providing ~30 MB/s throughput on supported hardware.
use anyhow::Result;
use ring::aead::{
    Aad, BoundKey, LessSafeKey, Nonce, NonceSequence, OpeningKey, SealingKey,
    UnboundKey, AES_256_GCM,
};
use rand::rngs::OsRng;
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
    let unbound = UnboundKey::new(&AES_256_GCM, key)
        .map_err(|e| anyhow::anyhow!("Failed to create key: {:?}", e))?;
    let less_safe = LessSafeKey::new(unbound);

    let mut nonce_bytes = [0u8; NONCE_SIZE];
    OsRng.fill_bytes(&mut nonce_bytes);
    let nonce = Nonce::assume_unique_for_key(nonce_bytes);

    let mut in_out = plaintext.to_vec();
    less_safe
        .seal_in_place_append_tag(nonce, Aad::empty(), &mut in_out)
        .map_err(|e| anyhow::anyhow!("AES-GCM encryption failed: {:?}", e))?;

    let mut out = Vec::with_capacity(NONCE_SIZE + in_out.len());
    out.extend_from_slice(&nonce_bytes);
    out.extend_from_slice(&in_out);
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
    let nonce = Nonce::assume_unique_for_key(nonce_bytes.try_into().unwrap());

    let unbound = UnboundKey::new(&AES_256_GCM, key)
        .map_err(|e| anyhow::anyhow!("Failed to create key: {:?}", e))?;
    let less_safe = LessSafeKey::new(unbound);

    let mut in_out = ciphertext.to_vec();
    let plaintext = less_safe
        .open_in_place(nonce, Aad::empty(), &mut in_out)
        .map_err(|_| anyhow::anyhow!("AES-GCM decryption failed: invalid key or corrupted data"))?;

    Ok(plaintext.to_vec())
}

/// Streaming nonce sequence for ring's AEAD: yields a unique nonce per chunk.
struct ChunkNonceSequence {
    base_nonce: [u8; 12],
    chunk_index: u32,
}

impl NonceSequence for ChunkNonceSequence {
    fn advance(&mut self) -> Result<Nonce, ring::error::Unspecified> {
        let mut nonce = self.base_nonce;
        let index_bytes = (self.chunk_index as u64).to_be_bytes();
        for (i, byte) in index_bytes.iter().enumerate() {
            nonce[4 + i] ^= *byte;
        }
        self.chunk_index += 1;
        Ok(Nonce::assume_unique_for_key(nonce))
    }
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
    sealing_key: SealingKey<ChunkNonceSequence>,
    base_nonce: [u8; 12],
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

        let unbound = UnboundKey::new(&AES_256_GCM, key).expect("valid AES-256-GCM key");
        let nonce_seq = ChunkNonceSequence {
            base_nonce,
            chunk_index: 0,
        };
        let sealing_key = SealingKey::new(unbound, nonce_seq);
        Self {
            sealing_key,
            base_nonce,
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

        let mut in_out = plaintext.to_vec();
        self.sealing_key
            .seal_in_place_append_tag(Aad::empty(), &mut in_out)
            .map_err(|e| anyhow::anyhow!("Chunk encryption failed: {:?}", e))?;

        Ok(in_out)
    }
}

/// Decodes FNX2 header and prepares for chunked decryption.
pub struct ChunkDecoder {
    pub base_nonce: [u8; 12],
    pub total_chunks: u32,
    pub original_size: u64,
    opening_key: OpeningKey<ChunkNonceSequence>,
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

        let unbound = UnboundKey::new(&AES_256_GCM, key)
            .map_err(|e| anyhow::anyhow!("Failed to create key: {:?}", e))?;
        let nonce_seq = ChunkNonceSequence {
            base_nonce,
            chunk_index: 0,
        };
        let opening_key = OpeningKey::new(unbound, nonce_seq);

        Ok(Self {
            base_nonce,
            total_chunks,
            original_size,
            opening_key,
        })
    }

    /// Decrypts a single chunk. Call sequentially for each chunk.
    /// Returns plaintext for this chunk.
    pub fn decrypt_chunk(&mut self, ciphertext: &[u8]) -> Result<Vec<u8>> {
        let mut in_out = ciphertext.to_vec();
        let plaintext = self
            .opening_key
            .open_in_place(Aad::empty(), &mut in_out)
            .map_err(|_| anyhow::anyhow!("Chunk decryption failed"))?;

        Ok(plaintext.to_vec())
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
