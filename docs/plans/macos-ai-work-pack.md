# AI Work Pack: FenixHub macOS Tauri Port

This file is for a second assistant or the Mac-owning contributor. Read this file and `docs/plans/macos-tauri-port.md` before making changes.

## Ground Rules

- Keep the port Tauri-based. Do not create a new native macOS app.
- First objective is compile + LAN interop, not visual redesign.
- Prefer platform-gated fixes over broad rewrites.
- Do not remove Linux/Windows behavior while making macOS work.
- Record Mac-only findings in docs so the non-Mac owner can follow along.

## Task Queue

### Task 1: macOS build report

Run on a real Mac:

```bash
bun install
cd frontend && bun install && bun run build
cd ..
bun tauri dev
```

Deliverable:

- Create `docs/plans/macos-build-report.md`.
- Include macOS version, chip architecture, Xcode CLT version, Rust version, Bun version.
- Include success/failure and exact errors.

Acceptance:

- Report is enough for a non-Mac maintainer to understand the first blocker.

### Task 2: fix compile blockers

Goal:

- Make `bun tauri dev` compile and launch on macOS.

Likely files:

```text
src-tauri/Cargo.toml
src-tauri/tauri.conf.json
src-tauri/src/lib.rs
src-tauri/src/windowing.rs
```

Acceptance:

- App opens on macOS.
- Linux and Windows cfg gates remain intact.

### Task 3: local network permission audit

Goal:

- Confirm whether macOS prompts for Local Network permission and whether Bonjour works.

Commands:

```bash
dns-sd -B _fenixhub._tcp
dns-sd -B _fenixhub-presence._tcp
```

Deliverable:

- Update `docs/plans/macos-build-report.md` with observations.
- If needed, add Tauri macOS plist configuration for:
  - `NSLocalNetworkUsageDescription`
  - `NSBonjourServices`

Acceptance:

- Mac can browse at least its own FenixHub service or another peer's service.

### Task 4: LAN interop test

Goal:

- Prove Mac <-> existing FenixHub device transfers.

Test matrix:

```text
Mac publishes text -> Android/Windows/Linux pulls
Android/Windows/Linux publishes text -> Mac pulls
Mac publishes mp4 -> peer pulls
peer publishes mp4 -> Mac pulls
```

Deliverable:

- Add results to `docs/plans/macos-build-report.md`.
- Include logs or concise failure messages.

Acceptance:

- At least text transfer works both ways.
- File transfer blockers are documented if present.

### Task 5: local IP selection improvement

Goal:

- Replace the fragile `8.8.8.8`-only LAN IP helper with a more robust interface-based fallback.

File:

```text
src-tauri/src/network.rs
```

Acceptance:

- `FENIXHUB_LOCAL_IP` override still wins.
- Existing behavior remains a fallback.
- Offline LAN/local hotspot still returns the active WiFi IPv4 when possible.
- Add focused unit tests if practical; otherwise document manual verification.

### Task 6: macOS packaging notes

Goal:

- Produce a friend-ready build path.

Deliverable:

- Create `docs/plans/macos-packaging.md`.

Include:

- dev build command
- release build command
- unsigned app caveats
- Gatekeeper "Open Anyway" notes
- signing/notarization TODOs

Acceptance:

- A Mac user can build and hand over a runnable `.app`/`.dmg` with known caveats.

## Prompt Template

```text
You are working in /home/javiju/proyectos/fenix-hub on macOS.
Read docs/plans/macos-ai-work-pack.md and docs/plans/macos-tauri-port.md.
Execute Task <N> only.
Keep edits scoped to the likely files for that task.
After editing, run the smallest relevant build/test command.
Report changed files, verification, and blockers.
```

## Notes for Non-Mac Maintainer

The non-Mac maintainer can prepare docs and protocol fixes, but cannot validate:

- `.app` launch behavior
- menu bar tray behavior
- Local Network permission UI
- Gatekeeper/notarization
- Keychain prompts

Ask the Mac contributor to paste reports into the docs rather than sending screenshots only.

