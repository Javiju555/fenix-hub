# FenixHub macOS Tauri Port Plan

Last updated: 2026-07-10
Owner intent: let a contributor with a Mac compile, test, and package the existing desktop app on macOS quickly.

## Goal

Enable the existing Tauri desktop client to run on macOS with LAN discovery and transfer compatibility against Windows, Linux, Android, and the future iOS foreground app.

This is not a rewrite. The macOS port should reuse the current Rust/Tauri backend and TypeScript frontend.

## Expected Viability

High. The current code already has a plausible non-Linux/non-Windows path:

- `crates/fenix-hub-daemon/src/mdns.rs` uses `mdns-sd` directly on non-Linux platforms.
- Windows-only drag target code is behind `#[cfg(target_os = "windows")]`.
- Linux-only GTK window hacks are behind `#[cfg(target_os = "linux")]`.
- Clipboard uses `arboard`, which is cross-platform.
- Identity persistence uses `keyring`, which should map to macOS Keychain.
- Tauri has native macOS bundling support and the repo already includes `icon.icns`.

The first macOS target should be a developer build. Signing/notarization can come after the LAN flow works.

## MVP Scope

MVP features:

- Build and run `bun tauri dev` on macOS.
- Configure identity from passphrase.
- Publish local text/file/folder content.
- Discover FenixHub peers via Bonjour/mDNS.
- Pull content from peers over authenticated HTTP.
- Serve content to peers over authenticated HTTP.
- Tray/menu bar item opens the hub window.
- Basic clipboard/file import/export works.

Out of scope for the first pass:

- App Store distribution.
- Full notarized `.dmg` release automation.
- Native macOS Finder extension.
- macOS-specific drag target parity with Windows virtual-file drag.
- iCloud/Continuity/AirDrop integration.

## Build Owner Requirements

The contributor needs:

- macOS machine.
- Xcode Command Line Tools.
- Rust stable.
- Bun.
- Node dependencies installed from repo.
- Network with another FenixHub device for LAN testing.

Suggested setup:

```bash
xcode-select --install
rustup toolchain install stable
bun install
cd frontend && bun install && bun run build
cd ..
bun tauri dev
```

If root-level `bun install` is enough in the current repo, document that in the test notes.

## Source Audit

Important files:

```text
src-tauri/tauri.conf.json
src-tauri/Cargo.toml
src-tauri/src/lib.rs
src-tauri/src/windowing.rs
src-tauri/src/network.rs
src-tauri/src/commands.rs
src-tauri/src/persist.rs
crates/fenix-hub-daemon/src/mdns.rs
crates/fenix-hub-core/src/server.rs
frontend/src/main.ts
```

Known platform gates:

- `src-tauri/src/drop_target.rs`: Windows-only native drag target.
- `src-tauri/src/windowing.rs`: Linux-only GTK sizing/type-hint paths; macOS uses generic Tauri window APIs.
- `src-tauri/src/lib.rs`: firewall commands are only generated for Linux/Windows.
- `crates/fenix-hub-daemon/src/mdns.rs`: Linux uses `avahi-browse`; macOS should use the `mdns-sd` branch.

## Key macOS Risks

### 1. Local network permission

Recent macOS versions can prompt for Local Network access. If discovery or HTTP access silently fails, verify the app appears under:

```text
System Settings -> Privacy & Security -> Local Network
```

Possible bundle plist additions:

```xml
<key>NSLocalNetworkUsageDescription</key>
<string>FenixHub uses the local network to discover and share files directly with your nearby devices.</string>
<key>NSBonjourServices</key>
<array>
  <string>_fenixhub._tcp</string>
  <string>_fenixhub-presence._tcp</string>
</array>
```

Where to wire this depends on Tauri v2 macOS bundle configuration. The Mac contributor should inspect the generated `Info.plist` in `src-tauri/target` or the app bundle.

### 2. Window behavior

The current hub window is:

- transparent
- undecorated
- always-on-top
- non-resizable
- hidden until tray/menu activation

macOS may need adjustments:

- menu bar tray icon behavior
- top-center positioning with notch/menu bar/safe area
- transparent click-through or shadow behavior
- focus behavior when opened from menu bar

Do not redesign the UI in the first pass. First make the existing window usable.

### 3. Bonjour self-filter

The app filters own announcements by local IP and device name. macOS may report extra interfaces such as:

- `awdl0`
- `llw0`
- VPN interfaces
- IPv6 link-local addresses

If the app sees itself, inspect `Self-filter IPs` logs and add a macOS-specific self-filter improvement if needed.

### 4. Local IP selection

`src-tauri/src/network.rs` currently uses a UDP connect to `8.8.8.8:80` to pick the LAN IP. This is fragile for offline LANs and local-only networks.

For macOS port quality, replace or augment this with interface enumeration:

- prefer active non-loopback IPv4
- deprioritize VPN/tunnel interfaces
- prefer default-route interface when available
- allow `FENIXHUB_LOCAL_IP` override to remain

This fix benefits Linux too.

### 5. Keychain prompts

`keyring` should use macOS Keychain. If prompts are noisy in dev mode, keep the existing JSON fallback and document expected behavior.

### 6. Signing and notarization

Developer build can be unsigned/ad-hoc. Distribution to a friend may need:

- Apple Developer ID certificate
- hardened runtime
- notarization
- zipped `.app` or `.dmg`

Do this after protocol functionality works.

## Implementation Phases

### Phase 1: compile on macOS

Owner: Mac contributor.

Commands:

```bash
bun install
cd frontend && bun install && bun run build
cd ..
bun tauri dev
```

Acceptance:

- App window opens from tray/menu bar.
- No compile errors from `gtk`, `windows`, or platform-specific modules.
- Identity setup works.

If build fails:

- Capture full error.
- Check whether a Linux-only dependency leaked into macOS.
- Check whether Tauri config needs macOS-specific bundle fields.

### Phase 2: LAN discovery smoke test

Setup:

- Mac and another FenixHub device on same WiFi.
- Same passphrase.

Acceptance:

- Mac publishes a small text item.
- Other device sees Mac item.
- Other device pulls it successfully.
- Other device publishes an item.
- Mac sees and pulls it successfully.

Useful commands on macOS:

```bash
dns-sd -B _fenixhub._tcp
dns-sd -B _fenixhub-presence._tcp
```

### Phase 3: file/folder transfer

Acceptance:

- Mac publishes `.mp4` or other large file.
- Android/Windows/Linux pulls it.
- Mac pulls a large file from another device.
- Memory usage remains reasonable.
- Folder sharing and extraction either works or is marked as a known gap.

### Phase 4: macOS polish

Acceptance:

- Menu bar icon looks acceptable.
- Window position is not hidden behind menu bar/notch.
- Close/hide behavior is understandable.
- Drag/drop into the hub works via Tauri built-in file drop if possible.
- Settings window works.

### Phase 5: packaging for friend

Acceptance:

- `bun tauri build --bundles app,dmg` or the closest working Tauri v2 equivalent creates an app bundle.
- Friend can run it after Gatekeeper steps or notarization.
- If unsigned, document exact "Open Anyway" flow.
- If signed/notarized, document certificate/env vars used outside the repo.

## Suggested Fix Queue

1. Add macOS plist local-network keys if the prompt does not appear or discovery is blocked.
2. Improve `local_ipv4()` to avoid `8.8.8.8` dependency.
3. Add macOS smoke-test docs with known-good logs.
4. Add a macOS-specific Tauri config override only if needed.
5. Add release packaging notes.

## Done Definition

The macOS port is usable when:

- A fresh clone builds on a real Mac.
- Mac can publish and receive content over LAN.
- Android/Windows/Linux interoperate with the Mac.
- The app can be sent to the friend with clear install/run instructions.
- Known limitations are documented.

