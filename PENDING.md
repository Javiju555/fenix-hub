# FenixHub — Pending tasks before public release

## MUST do before publishing to anyone else

### Auth / identity
- [ ] **Remove fenix-auth integration** from the app binary.
  - Currently included for personal use (Javier's setup only).
  - Must NOT ship to the public under any circumstances — it is a personal auth
    system and exposes internal infrastructure assumptions.
  - Kept until Android version is final and the auth flow is no longer needed
    for daily use. Remove in a dedicated PR before any public release.

### Settings window
- [ ] Implement a **standalone Settings window** (separate Tauri window, not modal).
  - Identity management: list profiles, create, delete, switch active.
  - "Delete all data" button (wipes `~/.config/fenix-hub/` and cache).
  - Should exist as a self-contained window in the app even if, on Fenix Desktop,
    it integrates into the DE settings panel instead of opening standalone.
  - Must also exist on Android (as a dedicated settings screen/activity).

### Multi-identity / user profiles
- [ ] Support multiple identities per installation.
  - Extend `identity.json` to store a map of profiles + an `active` pointer.
  - UI in the Settings window: profile list, add / delete / rename, switch.
  - Useful for separating personal and work contexts without reinstalling.

## Platform completeness

### Windows
- [ ] Verify presence beacon (`_fenixhub-presence._tcp`) discovery works on
  Windows with the mdns-sd path (no avahi dependency).
- [ ] Test temp file cache path (`dirs::cache_dir()` → `AppData\Local\...`).
- [ ] Confirm tray icon and window positioning behave correctly in release build.

### Android
- [ ] WiFi Direct support (v2 feature).
  - Use `WifiP2pManager` for device-to-device transfer without a router.
  - Relevant for mobile-to-mobile; desktop stays on LAN/Ethernet.
- [ ] Settings screen (profile management, delete data).
- [ ] Presence beacon on Android (mDNS via NsdManager or similar).

## Nice to have (post v1)

- [ ] Drag & drop from FenixHub window to other apps (expose cached file path
  to the webview for a native file drag).
- [ ] "Guardar como" true streaming to disk (requires chunked AEAD protocol
  change across all platforms — not compatible with current AES-256-GCM
  single-blob approach).
- [ ] Configurable max size for auto-cache (default off for files > N MB).
- [ ] Cross-network relay (STUN/TURN + DTLS) for v2 multi-user discovery
  beyond the local LAN — simpler than WiFi Direct for desktop.
