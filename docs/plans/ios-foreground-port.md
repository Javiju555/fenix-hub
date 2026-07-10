# FenixHub iOS Foreground Port Plan

Last updated: 2026-07-10
Owner intent: ship a practical iPhone build quickly for Share Sheet based LAN sharing.

## Goal

Build an iOS version of FenixHub that lets a user open Photos/Files, tap Share, choose FenixHub, and publish the selected item to nearby FenixHub desktop/Android devices on the same local network.

This first iOS port is deliberately foreground-only. It should feel like WhatsApp/Telegram sharing: the user explicitly sends content into FenixHub, the app opens, publishes while visible, and peers can pull it over LAN.

## Viability

Viable:

- iOS app receives files, images, videos, and text through Share Sheet.
- App publishes `_fenixhub._tcp` Bonjour/mDNS services on the local network.
- App serves content over local HTTP while in foreground.
- Windows/Linux/macOS/Android peers discover via existing mDNS/NSD and pull using the existing HMAC + FNX2 protocol.
- User grants Local Network permission on first use.

Not viable for MVP:

- Programmatically create or enable iPhone Personal Hotspot.
- Run as an always-on background daemon.
- Android-style floating overlay.
- WiFi Direct compatible with Android/Windows.
- Clipboard monitoring in background.
- MultipeerConnectivity as the main transport for Windows/Linux interoperability.

Official Apple references:

- Local network privacy: https://developer.apple.com/documentation/technotes/tn3179-understanding-local-network-privacy
- `NSLocalNetworkUsageDescription`: https://developer.apple.com/documentation/bundleresources/information-property-list/nslocalnetworkusagedescription
- Wi-Fi configuration APIs are for joining/configuring networks, not creating an app-owned AP: https://developer.apple.com/documentation/networkextension/wi-fi-configuration
- Hotspot Helper is for hotspot authentication flows, not general local file sharing: https://developer.apple.com/documentation/networkextension/hotspot-helper

## Product Scope

### MVP user flow

1. User installs FenixHub iOS.
2. First launch asks for:
   - device name
   - group passphrase, same as desktop/Android
   - Local Network permission when discovery/advertising starts
3. User opens Photos or Files.
4. User taps Share -> FenixHub.
5. FenixHub imports the shared item into a temporary local cache.
6. FenixHub opens foreground screen showing the item and a "Published" state.
7. Desktop/Android peer sees the item and pulls it.
8. User can stop publishing or close the app.

### MVP features

- Setup identity from passphrase.
- Import via Share Extension:
  - `public.text`
  - images
  - videos such as `.mp4`
  - generic files
- Main iOS app:
  - list local imported items
  - publish/unpublish item
  - show local IP / network status
  - show discovered peer content if easy, but receiving can be phase 2
- LAN publish:
  - Bonjour service type `_fenixhub._tcp.`
  - TXT payload compatible with existing `AnnouncementCodec`
  - HTTP server compatible with desktop/Android clients
  - HMAC request auth
  - FNX2 streaming encryption

### Phase 2 features

- Pull content from desktop/Android peers.
- Save received content through Files/Photos.
- Multiple selected Share Sheet items.
- QR/manual fallback for IP + port if Bonjour is blocked.
- macOS/iOS shared Swift package.
- App Store polish, onboarding, TestFlight.

## Architecture Choice

Use native Swift/SwiftUI for iOS. Do not try to reuse the current WebView frontend for MVP.

Reasoning:

- Share Extension and file-provider style flows are native-first.
- iOS foreground/background constraints are easier to manage natively.
- The current Android WebView bridge is Kotlin-specific.
- The reusable parts are protocol-level, not UI-level.

Recommended project layout:

```text
ios/
  FenixHub.xcodeproj
  App/
    FenixHubApp.swift
    SetupView.swift
    HubView.swift
    LocalItemRow.swift
    SettingsStore.swift
    ContentRepository.swift
    ShareImportInbox.swift
  ShareExtension/
    ShareViewController.swift
    Info.plist
  FenixHubCore/
    Announcement.swift
    AnnouncementCodec.swift
    Crypto.swift
    FNX2Encoder.swift
    FNX2Decoder.swift
    LocalHttpServer.swift
    BonjourPublisher.swift
    BonjourBrowser.swift
```

If building from scratch is faster, start with a plain Xcode iOS App + Share Extension and only later decide whether it should be committed as generated Xcode project files.

## Protocol Compatibility

The iOS port must match the existing wire protocol, not invent a new one.

Source-of-truth files:

- Rust core protocol: `crates/fenix-hub-core/src/protocol.rs`
- Rust HTTP server: `crates/fenix-hub-core/src/server.rs`
- Android announcement codec: `android/app/src/main/java/com/fenixhub/mobile/util/AnnouncementCodec.kt`
- Android HTTP server: `android/app/src/main/java/com/fenixhub/mobile/network/FenixHttpServer.kt`
- Android HTTP client: `android/app/src/main/java/com/fenixhub/mobile/network/FenixHttpClient.kt`
- Android NSD publisher: `android/app/src/main/java/com/fenixhub/mobile/network/NsdController.kt`
- Desktop mDNS parser: `crates/fenix-hub-daemon/src/mdns.rs`

### Announcement JSON

Use the current protocol version and field names:

```json
{
  "protocol_version": 3,
  "group_id": "...",
  "content_id": "...",
  "device_name": "Javier iPhone",
  "preview": "video.mp4",
  "content_type": "file",
  "size_bytes": 123456,
  "file_name": "video.mp4",
  "mime_type": "video/mp4",
  "send_mode": { "Broadcast": {} },
  "created_at": 1720000000000,
  "port": 7473
}
```

TXT record constraints:

- Keep payload below Android/Linux mDNS practical limits.
- Reuse chunking keys `data0`, `data1`, ...
- Stay compatible with `parse_avahi_txt` and Android `TxtRecordCodec`.

### HTTP API

MVP publish server:

```text
GET /content/{content_id}
```

Headers expected from clients:

```text
X-FenixHub-Sig
X-FenixHub-Timestamp
X-FenixHub-Nonce
X-FenixHub-Body-SHA256
```

Response:

```text
200 OK
X-FenixHub-Encrypted: 2
Content-Type: <mime type>
Content-Disposition: inline; filename="<safe name>"

FNX2 stream
```

## iOS Permissions and Plists

Main app `Info.plist`:

```xml
<key>NSLocalNetworkUsageDescription</key>
<string>FenixHub usa la red local para compartir archivos directamente con tus dispositivos cercanos.</string>
<key>NSBonjourServices</key>
<array>
  <string>_fenixhub._tcp</string>
  <string>_fenixhub-presence._tcp</string>
</array>
```

Share Extension:

- Needs App Group entitlement to pass imported files to the containing app.
- Use a shared container, for example `group.com.fenixhub.mobile`.
- Extension should copy selected files into the App Group inbox and then open the containing app via URL scheme.

Suggested URL scheme:

```text
fenixhub://share-import
```

## Implementation Phases

### Phase 0: local protocol audit

Deliverable:

- Confirm exact constants for protocol version, auth headers, FNX2 chunk size, default ports.
- Write them into `ios/FenixHubCore/ProtocolConstants.swift`.

Acceptance:

- Constants match Rust/Android source.

### Phase 1: Xcode skeleton

Deliverable:

- `ios/` project with main SwiftUI app and Share Extension.
- App Group and URL scheme configured.
- Basic setup screen saves device name + passphrase-derived group identity.

Acceptance:

- App launches on simulator/device.
- Share Extension appears in iOS Share Sheet for text/images/videos/files.
- Shared item arrives in app inbox.

### Phase 2: local content repository

Deliverable:

- Import files from App Group inbox into app cache.
- Create local items with content id, mime, size, preview, createdAt.
- Render local item list.

Acceptance:

- Sharing an `.mp4` from Photos/Files shows it in FenixHub.
- File remains accessible while app is open.

### Phase 3: Bonjour publish

Deliverable:

- Publish `_fenixhub._tcp.` service for each published item.
- TXT payload uses chunked announcement JSON.
- Reannounce periodically while app is foreground.

Acceptance:

- Linux/macOS `dns-sd -B _fenixhub._tcp` or packet capture shows iOS announcement.
- Existing FenixHub desktop shows the iPhone item.

### Phase 4: HTTP server + auth

Deliverable:

- Local HTTP server bound to `0.0.0.0`, fixed port 7473 if available, random fallback otherwise.
- Validate HMAC headers.
- Serve FNX2 encrypted stream.

Acceptance:

- Existing desktop/Android can pull the iOS item.
- Wrong passphrase/group receives 401.
- Large `.mp4` streams without full memory load.

### Phase 5: receiving from peers

Deliverable:

- Bonjour browser.
- Pull desktop/Android content.
- Decrypt FNX2.
- Save into app storage and offer Share/Save to Files.

Acceptance:

- iPhone receives text/file/video from desktop.

### Phase 6: distribution

Deliverable:

- TestFlight-ready build.
- Minimal docs for friend/tester.

Acceptance:

- Clean install on a real iPhone.
- Share Sheet flow works on same WiFi with Windows/Linux desktop.

## Risks

- Local Network permission denied: show explicit recovery screen.
- iOS foreground lifetime: clearly communicate that publishing stops when app is closed.
- Share Extension memory limits: always copy via file URLs/streams, never load videos fully into memory.
- Bonjour TXT size: aggressively compact previews and filenames.
- HTTP server library App Store acceptance: prefer SwiftNIO or Network.framework based implementation.
- Photos assets may arrive as `public.movie` or provider-backed URLs: copy before publishing.

## Recommended MVP Technical Stack

- UI: SwiftUI.
- Local network permissions/Bonjour:
  - `NetService` / `NetServiceBrowser`, or
  - Network.framework `NWListener` + `NWBrowser` with Bonjour.
- HTTP:
  - SwiftNIO based lightweight server, or
  - custom `NWListener` HTTP parser for MVP if simpler.
- Crypto:
  - CryptoKit for HMAC-SHA256 and AES.GCM.
  - HKDF from CryptoKit.
- Storage:
  - App Group inbox for Share Extension.
  - App container caches for imported publish items.

## Done Definition

The iOS MVP is done when:

- A real iPhone can Share -> FenixHub a `.mp4`.
- FenixHub iOS opens and publishes it.
- Existing desktop FenixHub sees the iPhone item on the same WiFi.
- Desktop pulls the file successfully.
- iPhone can stop publishing.
- The app handles Local Network permission denial gracefully.

