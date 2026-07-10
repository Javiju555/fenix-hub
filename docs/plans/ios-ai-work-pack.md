# AI Work Pack: FenixHub iOS Foreground Port

This document is written for a second coding assistant working with limited context. Read this file first, then read `docs/plans/ios-foreground-port.md`.

## Collaboration Rules

- Do not change Android/Desktop behavior unless the task explicitly asks for compatibility fixes.
- Keep each change small and buildable.
- Prefer adding iOS files under `ios/`.
- Preserve wire compatibility with existing Rust/Android protocol.
- When blocked by Xcode/project-generation details, document the blocker and continue with Swift source files or tests that can be reviewed.
- Do not invent a new protocol.

## Repo Orientation

Important files:

```text
README.md
docs/TECHNICAL.md
docs/plans/ios-foreground-port.md
crates/fenix-hub-core/src/protocol.rs
crates/fenix-hub-core/src/server.rs
crates/fenix-hub-daemon/src/mdns.rs
android/app/src/main/java/com/fenixhub/mobile/network/NsdController.kt
android/app/src/main/java/com/fenixhub/mobile/network/FenixHttpServer.kt
android/app/src/main/java/com/fenixhub/mobile/network/FenixHttpClient.kt
android/app/src/main/java/com/fenixhub/mobile/util/AnnouncementCodec.kt
android/app/src/main/java/com/fenixhub/mobile/util/CryptoUtils.kt
```

Current platform model:

- Android app is Kotlin + WebView + foreground service.
- Desktop is Tauri + Rust.
- Discovery is mDNS/NSD/Bonjour service type `_fenixhub._tcp`.
- Transfer is HTTP GET `/content/{id}` with HMAC headers.
- Response is encrypted FNX2 stream.

## Task Queue

### Task 1: Extract protocol constants

Goal:

- Create `docs/plans/ios-protocol-constants.md`.

Steps:

1. Read Rust/Android source files listed above.
2. Record:
   - protocol version
   - service type
   - presence service type
   - default desktop port
   - Android default port
   - auth header names
   - FNX2 magic/header/chunk/tag sizes
   - auth skew window
   - TXT chunk size
3. Note any mismatch between docs and code.

Acceptance:

- The document is purely factual and cites file paths.
- Any mismatch is called out as `TODO: reconcile`.

### Task 2: Draft iOS Swift package core

Goal:

- Add `ios/FenixHubCore/` Swift source files that compile as a standalone Swift package if possible.

Files to create:

```text
ios/FenixHubCore/Package.swift
ios/FenixHubCore/Sources/FenixHubCore/ProtocolConstants.swift
ios/FenixHubCore/Sources/FenixHubCore/Announcement.swift
ios/FenixHubCore/Sources/FenixHubCore/AnnouncementCodec.swift
ios/FenixHubCore/Tests/FenixHubCoreTests/AnnouncementCodecTests.swift
```

Acceptance:

- Announcement JSON matches Android `AnnouncementCodec`.
- Unit tests cover broadcast send mode and nullable file/mime fields.

### Task 3: Crypto and identity

Goal:

- Implement passphrase-derived group identity and request signing.

Files:

```text
ios/FenixHubCore/Sources/FenixHubCore/GroupIdentity.swift
ios/FenixHubCore/Sources/FenixHubCore/AuthSigner.swift
ios/FenixHubCore/Tests/FenixHubCoreTests/AuthSignerTests.swift
```

Acceptance:

- Uses CryptoKit.
- Derives group id/key compatibly with existing code.
- If exact derivation is unclear, stop and write `TODO_COMPATIBILITY.md` with the exact missing details.

### Task 4: FNX2 encoder

Goal:

- Implement streaming FNX2 encryption for serving files.

Files:

```text
ios/FenixHubCore/Sources/FenixHubCore/FNX2Encoder.swift
ios/FenixHubCore/Tests/FenixHubCoreTests/FNX2EncoderTests.swift
```

Acceptance:

- Emits `FNX2` header.
- Uses 4 MB chunk size.
- AES-GCM tag per chunk.
- Does not read full large files into memory.

### Task 5: Bonjour publisher prototype

Goal:

- Publish one announcement on local network.

Files:

```text
ios/FenixHubCore/Sources/FenixHubCore/BonjourPublisher.swift
```

Acceptance:

- Publishes `_fenixhub._tcp.`
- TXT records use `data0`, `data1`, ...
- Can be browsed by `dns-sd` or existing FenixHub desktop.

### Task 6: HTTP server prototype

Goal:

- Serve one local file to existing desktop/Android clients.

Files:

```text
ios/FenixHubCore/Sources/FenixHubCore/LocalHttpServer.swift
```

Acceptance:

- Binds to port 7473 or fallback.
- Handles `GET /content/{id}`.
- Validates HMAC.
- Streams FNX2.

### Task 7: Xcode app skeleton

Goal:

- Create the iOS app and Share Extension skeleton.

Files:

```text
ios/App/
ios/ShareExtension/
```

Acceptance:

- Main app has setup screen and local items screen.
- Share Extension accepts text, images, videos, files.
- Share Extension copies providers into App Group inbox.
- URL scheme opens main app.

### Task 8: End-to-end MVP

Goal:

- Share `.mp4` from iPhone to FenixHub desktop.

Acceptance:

- Real device test.
- Desktop sees iPhone announcement.
- Desktop downloads the file.
- Document exact steps and known limitations in `docs/plans/ios-testflight-checklist.md`.

## Prompt Template for the Second Assistant

Use this prompt when assigning one task:

```text
You are working in /home/javiju/proyectos/fenix-hub.
Read docs/plans/ios-ai-work-pack.md and docs/plans/ios-foreground-port.md.
Execute Task <N> only.
Keep edits scoped to the files named in that task unless you discover a protocol mismatch.
After editing, run the smallest relevant test/build command available.
Report changed files, verification, and blockers.
```

## Human Test Script for MVP

Desktop:

```bash
cd /home/javiju/proyectos/fenix-hub
bun tauri dev
```

iPhone:

1. Install development build.
2. Open app once and set same passphrase as desktop.
3. Open Photos or Files.
4. Share an `.mp4` to FenixHub.
5. Keep FenixHub open.

Expected:

- Desktop network tab shows the iPhone item.
- Pull succeeds.

If not visible:

- Confirm iOS Local Network permission is enabled.
- Confirm same WiFi.
- Capture Bonjour with `dns-sd -B _fenixhub._tcp`.
- Check that TXT contains `group_id` matching desktop.

