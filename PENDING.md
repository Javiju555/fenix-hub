# FenixHub — Pending tasks / design decisions

## MUST do before public release

### Auth / identity
- [ ] **Remove fenix-auth integration** before any public release.
  - Lives in `src-tauri/src/personal/impl.rs` (gitignored).
  - Compiled only with `--features personal` on Linux.
  - When ready: create a new repo via `git filter-repo` to strip all history
    that ever touched `src/personal/` before publishing.
  - To build personal APK from Linux: `cargo tauri android build --features personal`

### Clean public repo
- [ ] Run `git filter-repo --path src-tauri/src/personal --invert` (or equivalent)
  to produce a history-clean public mirror before the first public release.

---

## Protocol: chunked AEAD + streaming (v2 wire format)

Current limitation: AES-256-GCM puts the auth tag at the end → full file in RAM.

**Target wire format ("FNX2"):**
```
Header (29 bytes):
  magic:          4 B  — b"FNX2"
  base_nonce:    12 B  — random per transfer
  total_chunks:   4 B  — u32 BE
  original_size:  8 B  — u64 BE (pre-compression)
  compression:    1 B  — 0x00 = none, 0x01 = zstd

Per chunk:
  nonce:         12 B  — base_nonce XOR chunk_index (u64 padded to 12 B)
  ciphertext: ≤64 KB
  tag:           16 B  — GCM auth tag
```

- Receiver verifies each 64 KB chunk independently → can stream directly to disk.
- `protocol_version` field in the `Announcement` negotiates old vs new format.
- Old receivers ignore new senders gracefully (they reject unknown protocol_version).
- Must be implemented in both Rust (desktop) and Kotlin (Android) simultaneously.

---

## Compression (zstd / Meta)

- Add **before** encryption in the FNX2 sender path.
- Threshold: files > 10 MB get a compression attempt.
- Skip if MIME type is already compressed:
  `image/jpeg`, `image/webp`, `video/*`, `application/zip`, `application/x-7z-compressed`, etc.
- Skip if compressed size ≥ 95 % of original (no gain).
- `compression` byte in FNX2 header signals receiver whether to decompress.
- Rust crate: `zstd = "0.13"` (bindings to libzstd).

---

## BLE + WiFi Direct (runtime detection)

Goal: no compile-time feature flags for distribution. Detect at runtime.

```rust
pub struct TransportCaps {
    pub lan: bool,         // always true if any network interface available
    pub ble: bool,         // BlueZ on Linux, WinRT on Windows, system on Android
    pub wifi_direct: bool, // wpa_supplicant P2P on Linux, WifiP2pManager on Android
}
```

- UI hides BLE/WiFi Direct buttons if capability is absent — no error, just absent.
- Linux: probe BlueZ via D-Bus (`org.bluez`) for BLE; `wpa_supplicant` P2P for WFD.
- Android: `BluetoothAdapter.isEnabled()` + `WifiP2pManager` availability.
- Windows: WinRT `BluetoothAdapter.GetDefaultAsync()`.

Priority: BLE first (simpler, useful for discovery range beyond LAN), WiFi Direct second.

---

## Settings window

- Separate Tauri window, label `"settings"`, ~600×450 px, movable, resizable.
- Not a modal — independent window that can coexist with the hub.
- Sections:
  1. **Identidad** — device name, group ID (read-only), copy group ID button.
  2. **Perfiles** — list of saved identities, switch active, create new, delete.
  3. **Servicio** — presence beacon on/off, cache size limit, clear cache.
  4. **Zona de peligro** — "Eliminar sesión" (wipes `~/.config/fenix-hub/`), "Salir del grupo".
- Android: equivalent settings screen/activity (same Tauri webview or native).

---

## Multi-user / profiles

```json
{
  "active": "javier-personal",
  "identities": {
    "javier-personal": { "device_name": "...", "group_id": "...", "key_pair": {...} },
    "javier-trabajo":  { ... }
  }
}
```

- Switching profile restarts discovery with new group_id.
- Each profile has its own clipboard history in `~/.cache/fenix-hub/<profile>/`.

---

## Platform completeness

### Windows
- [ ] Verify presence beacon (`_fenixhub-presence._tcp`) with mdns-sd (no avahi).
- [ ] Test cache path: `dirs::cache_dir()` → `AppData\Local\fenix-hub\`.
- [ ] Tray icon + AOT window in release build.

### Android
- [ ] Presence beacon via `NsdManager` or `jmDNS`.
- [ ] Settings screen.
- [ ] WiFi Direct via `WifiP2pManager`.
- [ ] Personal auth (`--features personal`) for personal APK builds only.

---

## Nice to have (post v1)

- [ ] Drag & drop out of FenixHub to other apps (expose cached file path for native drag).
- [ ] Configurable cache FIFO size (currently 30 files hardcoded).
- [ ] Cross-network relay (STUN/TURN + DTLS) for discovery beyond LAN (v2 multi-user).
- [ ] macOS port (if ever relevant).
