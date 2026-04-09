/// Personal auth integration — NFC + face via fenix-authd (D-Bus, Linux only).
///
/// This module is gitignored and compiled exclusively when:
///   - target_os = "linux"
///   - feature "personal" is enabled
///
/// To build with personal auth:
///   cargo tauri dev   --features personal
///   cargo tauri build --features personal
///
/// The actual implementation lives in impl.rs (gitignored).
/// This stub is the only file committed to the public repo.

#[cfg(all(target_os = "linux", feature = "personal"))]
mod r#impl;

#[cfg(all(target_os = "linux", feature = "personal"))]
pub use r#impl::*;

/// Called during app setup. No-op in public builds.
#[cfg(not(all(target_os = "linux", feature = "personal")))]
pub fn setup(_app: &tauri::AppHandle) {}
