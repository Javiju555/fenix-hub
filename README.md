# FenixHub

Shared clipboard across your devices. Local network, no cloud, real encryption.

Copy something on your phone. It appears on your PC. Paste it. That's it.

Works with text, images, and files. Windows + Linux + Android.

---

## How it works

Each device runs its own node. Nodes discover each other via mDNS on the local network and authenticate with a shared `group_id`. Transfers are AES-256-GCM encrypted over authenticated HTTP — no relay, no intermediary, nothing leaves your network.

The Android overlay lets you drop content into any app without switching windows. On desktop, drag files directly to the hub.

---

## Status

**Working**

- Desktop: Windows and Linux (Tauri v2 + Rust)
- Android: native app + overlay + drag-and-drop into overlay
- LAN encrypted transfer: text, image, file
- Discovery via mDNS, pull via authenticated HTTP
- Desktop parity: firewall warning/allow flow on both Windows and Linux
- Desktop DnD: native file drop on both; Windows adds virtual-file interception (Outlook/OneDrive/ZIP shells)

**In progress**

- Full payload transfer over Wi-Fi Direct (near mode without LAN)
- Android identity/profile parity with desktop

---

## Repository Scope

This repository contains the **application source code** and public documentation.

- Included: Rust core/desktop code, frontend code, Android app source, public docs.
- Not included: private packaging pipeline and installer implementation details.

Windows binaries published in GitHub Releases may use a proprietary installer maintained outside this repository.

---

## Project Layout

- `crates/` — shared Rust crates (`fenix-hub-core`, `fenix-hub-daemon`)
- `src-tauri/` — desktop app backend (Tauri + Rust)
- `frontend/` — desktop/android web UI source
- `android/` — native Android app and bridge code
- `docs/` — changelog and roadmap/status docs

---

## Building

### Requirements

- [Rust toolchain](https://rustup.rs)
- [Bun](https://bun.sh)
- Tauri prerequisites for your platform: [tauri.app/start/prerequisites](https://v2.tauri.app/start/prerequisites/)

### Desktop — dev

```bash
bun tauri dev
```

### Desktop — build

```bash
bun tauri build
```

### Rust tests

```bash
cargo test -p fenix-hub-core
```

### Android unit tests

```bash
cd android && ./gradlew :app:testDebugUnitTest
```

---

## Security

Not decorative encryption. Each request carries a canonical signature over `method + path + group_id + timestamp + nonce + body_sha256`. Anti-replay protection with time window and unique nonce per request. The `group_id` is HKDF-derived. Passphrases have a minimum complexity policy.

Transfers run at ~30 MB/s — encryption uses hardware acceleration (ring crate), no software crypto overhead.

---

## Platforms

| Platform | Stack |
|---|---|
| Windows / Linux | Tauri v2, Rust, TypeScript |
| Android | Native Kotlin app + WebView bridge |
| iOS | Pending — requires Mac + Xcode + Apple Developer account |

---

## iOS

No functional iOS port. Requires Mac + Xcode + Apple Developer Program ($99/year) + real hardware for testing.

---

## License

[AGPL-3.0-only](LICENSE)

Strong copyleft. Anyone who forks and offers this as a network service must publish their changes under the same terms.
