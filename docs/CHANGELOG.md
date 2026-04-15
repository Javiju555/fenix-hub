# Changelog

## 0.2.7 — experimental Windows native drop interception (2026-04-13)

### Windows — experimental ⚠️ **(no testeada)**
- Custom `IDropTarget` COM interceptor registered on WebView2 child HWND (`Chrome_RenderWidgetHostHWND`)
- Disables WebView2 external drop via `SetAllowExternalDrop(false)` on `ICoreWebView2Controller4` (raw COM QI)
- Extracts `CF_HDROP` (real filesystem files) and `CFSTR_FILEDESCRIPTORW` + `CFSTR_FILECONTENTS` (virtual files: Outlook attachments, cloud-drive shells, ZIP viewers)
- Virtual files saved to `%TEMP%/fenix-hub-drag/` with timestamp deduplication
- Frontend notified via `fenix://drag-received` event with `{ paths, source }`
- **Estado:** implementación completa, compila limpio, sin pruebas reales de uso

---

## 0.2.6 — overlay UX + drag-drop + FNX2 parsing (2026-04-13)

### Android overlay
- Replaced header button row with 2×2 grid; added "open app" shortcut
- Fixed X-button tap zone: was firing on wrong side after snap
- Fixed grey rectangle artifact around circular bubble (WebView software layer)
- Overlay starts expanded on first show; slide-in animation still plays

### Desktop
- Fixed `dragDropEnabled` — large files (>64 MB) from Explorer now work
- Drop handling fully migrated to `onDragDropEvent`; HTML5 handler covers browser-sourced files

### Protocol
- Fixed FNX2 v2 stream parsing bug: bytes after 29-byte header in first TCP chunk were discarded,
  causing AES-GCM auth failure on every download from Android peers

---

## 0.2.3 — docs + branding + AGPL (2026-04-12)

- Docs reorganized to `docs/`
- License: AGPL-3.0-only
- Version bump to 0.2.3

---

## 0.2.2 — near mode base + FNX2 Android (2026-04-10)

### Near mode (BLE + Wi-Fi Direct)
- Android: `BleIdentityController` for nearby identity advertising/scanning
- Android: `WifiDirectController` for Wi-Fi Direct peer discovery
- Both integrated into service lifecycle
- `get_transport_hardware` snapshot command on Android and desktop

### Security / protocol
- Android decodes FNX2 v2 (`X-FenixHub-Encrypted: 2`) with per-chunk AES-GCM
- zstd decompression in FNX2 Android decode
- Canonical HMAC signature test vector aligned Rust/Kotlin
- Cross-platform parity tests: canonical vector + FNX2 v2 round-trips

---

## 0.2.1 — security hardening cross-platform (2026-04-10)

### Auth + anti-replay
- Canonical request signature: `method + path + group_id + timestamp + nonce + SHA256(body)`
- New headers: `X-FenixHub-Timestamp`, `X-FenixHub-Nonce`, `X-FenixHub-Body-Sha256`
- Time window + unique nonce verification on Rust server and Android
- Nonce cache for replay blocking within acceptance window

### Identity + keys
- `group_id` derived with dedicated HKDF context (`fenixhub-v2-group-id`)
- Minimum passphrase complexity policy on desktop and Android

### Lifecycle
- Publication guard: auto-stop after TTL (10 min) in active session
- Network guard: publication stops if LAN IP changes during active session

---

## 0.2.0 — UX + identity/profiles + compression (2026-04-10)

### UX
- Hub locked to two valid sizes: pill (280×34) and expanded (820×185)
- Settings window redesigned; explicit X button
- Zoom disabled (Ctrl+/-/0, Ctrl+wheel)

### Settings / identity
- Hot-reload of name and device type
- Group/identity change without clearing cache
- Profile support: list, save, activate, delete
- Transport capabilities shown in settings: LAN / BLE / Wi-Fi Direct

### Protocol / transfer
- zstd compression for large files (≥100 MB) where it helps
- Heuristic to skip compression for already-compressed formats
- Streaming `save as` for peer non-text content (avoids RAM spikes)
- Streaming FNX2 server and client (no full payload in memory)

---

## 0.1.x — initial builds

- Desktop Tauri v2 skeleton with mDNS, identity persistence, real content pull
- Image thumbnails, paste-to-hub, click-to-copy
- LAN encrypted transfer: text, image, file
- Android companion app: hub, overlay, drag-and-drop into overlay
- AES-256-GCM E2E encryption + HKDF key separation + Argon2id
