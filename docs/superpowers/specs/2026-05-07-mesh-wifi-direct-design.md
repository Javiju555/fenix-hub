# FenixHub Mesh — WiFi Direct Design Spec
_Date: 2026-05-07_

## Summary

Add a "nearby mesh" mode to FenixHub Android. Two or more Android devices form a WiFi Direct group via BLE pairing, then transfer files using the existing FNX2/HMAC/AES-GCM pipeline over the P2P network. iOS joins as a client later (port, not parallel design).

No changes to the existing transfer stack. Only the transport layer, key derivation, and discovery/pairing are new.

---

## Decisions log

| Topic | Decision |
|---|---|
| Platform v1 | Android↔Android only |
| iOS | Port after Android 100% done, joins as WiFi client (not host) |
| Architecture | Thin layer over existing stack (Approach A) |
| Key derivation | CSPRNG 32 bytes — no passphrase, no Argon2 |
| BLE channel | Stays open after pairing as control channel |
| Voluntary leave | No rekey |
| Kick | New P2P group + new group_id/key, burst new creds to remaining members via BLE |
| Host close | Full tear-down: P2P group destroyed, MeshSession null, BLE disconnected |
| Ghost mode | BLE advertising off when host modal closed; control channel stays open |
| Delivery model | Only host publishes; devices receive exactly as in LAN mode |
| Commits | All features in one branch, separate commits per feature |

---

## Architecture

Three new modules. Everything else untouched.

```
┌─────────────────────────────────────────────────────┐
│                  Android App                        │
│                                                     │
│  ┌──────────────┐  ┌──────────────┐                │
│  │ BleService   │  │WifiP2pService│                │
│  │ (GATT server │  │(Group Owner  │                │
│  │  / client)   │  │ / P2P client)│                │
│  └──────┬───────┘  └──────┬───────┘                │
│         └────────┬────────┘                        │
│                  │                                  │
│          ┌───────▼──────┐                          │
│          │ MeshSession  │  ← new                   │
│          │ group_id     │                          │
│          │ group_key    │                          │
│          │ member_list  │                          │
│          └───────┬──────┘                          │
│                  │ injects creds                    │
│   ┌──────────────▼──────────────────────────────┐  │
│   │      Existing stack (unchanged)             │  │
│   │  axum / FNX2 / HMAC / AES-GCM / mDNS       │  │
│   └─────────────────────────────────────────────┘  │
└─────────────────────────────────────────────────────┘
```

### Modules

**`BleService`**
- GATT server when host: advertises `FenixHub-Mesh` UUID + host device name. Does NOT advertise group_id.
- GATT client when device: scans for `FenixHub-Mesh`, connects, sends `JoinRequest`.
- Stays connected after pairing as control channel (kick, rekey, termination signals).
- Custom characteristics per message type (see BLE protocol section).

**`WifiP2pService`**
- Wrapper over Android `WifiP2pManager`.
- Host = Group Owner: creates named group, exposes SSID + passphrase.
- Device = P2P client: receives SSID/pass via BLE, connects via `WifiNetworkSpecifier`.
- Kick: host destroys P2P group and creates a new one; new creds burst to remaining members.

**`MeshSession`**
- Ephemeral in-memory object. Created at group creation moment, destroyed on host termination.
- Injected into hub state exactly as activating a profile today — no changes to HKDF/HMAC/AES pipeline.

```kotlin
data class MeshSession(
    val groupId: String,          // UUID.randomUUID(), never reused
    val groupKey: ByteArray,      // SecureRandom 32 bytes
    val ssid: String,
    val p2pPass: String,
    val members: MutableList<MeshMember>,
    val hostIp: String,           // read from WifiP2pInfo.groupOwnerAddress, not hardcoded
    val port: Int,                // same port as LAN hub
)

data class MeshMember(
    val bleId: String,
    val pubKey: ByteArray,
    val deviceName: String,
)
```

---

## BLE Protocol

### Message types

| Message | Direction | Payload |
|---|---|---|
| `JoinRequest` | Device → Host | `{ dev_name, dev_ble_id, dev_pubkey }` |
| `LobbyAck` | Host → Device | `{ host_name }` |
| `LobbyKicked` | Host → Device | `{}` |
| `MeshCredentials` | Host → Device | `{ group_id, group_key, ssid, p2p_pass, host_ip, port }` — encrypted with device pubkey |
| `MeshRekeyed` | Host → Device | same as MeshCredentials — new values after kick |
| `MeshTerminated` | Host → Device | `{}` |

`MeshCredentials` and `MeshRekeyed` are encrypted with the recipient device's public key (from `JoinRequest`). Scheme: ephemeral ECDH (X25519) + HKDF + AES-256-GCM — same pattern as the existing Rust stack, implemented in Kotlin via BouncyCastle (already present on Android). All other messages are plaintext over the BLE GATT connection (which is already link-layer encrypted by BLE spec).

### Discovery flow (host invites)

```
HOST                                    DEVICE
 │                                         │
 │ — BLE adv: "FenixHub/[host_name]" ───► │ (scanning)
 │                                         │
 │ ◄── GATT connect ─────────────────────  │
 │ ◄── JoinRequest ──────────────────────  │
 │                                         │
 │  [modal: "[dev_name] wants to join"     │
 │   → Accept / Reject]                   │
 │                                         │
 │ ── LobbyAck ──────────────────────────► │
 │                                         │
 │  host stores: dev_ble_id + pubkey       │
 │  device: "waiting for [host] to         │
 │           create mesh"                  │
```

### Discovery flow (device requests)

Device advertises `FenixHub-seeking`. Host modal scans and shows list of nearby devices wanting to join. Host taps one → same flow from `LobbyAck` onward.

Both directions converge at the lobby.

### Mesh creation burst

```
HOST                        DEVICE-A   DEVICE-B   DEVICE-C
 │                              │          │          │
 │  [host: "Create mesh"]       │          │          │
 │  generates group_id,         │          │          │
 │  group_key, SSID, p2p_pass  │          │          │
 │                              │          │          │
 │ ─ MeshCredentials(A) ──────► │          │          │
 │ ─ MeshCredentials(B) ─────────────────► │          │
 │ ─ MeshCredentials(C) ──────────────────────────► │
 │                              │          │          │
 │    (devices connect P2P WiFi, mDNS discovers hub) │
```

Only devices present in the lobby at the moment of creation receive credentials.

---

## Key Derivation

- `group_key`: `SecureRandom.getBytes(32)` — generated at mesh creation, never derived from passphrase
- `group_id`: `UUID.randomUUID().toString()` — unique per session
- HKDF → `mac_key` + `enc_key`: **unchanged** from existing pipeline
- HMAC request signing: **unchanged**
- AES-256-GCM per-chunk encryption: **unchanged**

On device side: `MeshSession` is injected into hub state with `group_id` and `group_key` — identical code path to activating a profile.

---

## Mesh Lifecycle

```
IDLE
  │  host opens modal, BLE advertising starts
  ▼
LOBBY  ← devices join/leave lobby, host can kick before any creds shared
  │
  │  host taps "Create mesh" → credential burst
  ▼
ACTIVE_PUBLIC   ← BLE advertising ON, accepts new invites / QR
  │
  │  host closes modal
  ▼
ACTIVE_GHOST    ← BLE advertising OFF, no new join requests
  │               transfers still active, BLE control channel open
  │  host opens mesh modal
  ▼
ACTIVE_PUBLIC   (can toggle back)
  │
  │  host taps "End mesh"
  ▼
IDLE  (P2P group destroyed, MeshSession = null, BLE disconnected)
```

### Kick in active mesh

1. Host expels device X from mesh management modal
2. Host generates new `group_id` + `group_key` + new P2P group (new SSID/pass)
3. Burst `MeshRekeyed` to remaining BLE IDs with new credentials
4. Remaining devices reconnect to new group (~5-10s disruption)
5. Device X: no new credentials, left stranded on old group (now gone)
6. Device X can rejoin only via fresh BLE invite to the new group

---

## UI

### Host modal

**Tab: Lobby** (before mesh creation)
- List of accepted devices (name, device model)
- Per-device: kick button
- "Create mesh" button — disabled until at least one device in lobby
- Back/cancel: destroys lobby, BLE advertising stops

**Default view when mesh active**
- Normal hub view: add files, publish, everything as usual
- Mesh indicator in header/tray (e.g. mesh icon + member count)

**Tap mesh button → mesh management modal**
- Member list: name + device model
- Per-member: kick button
- "Invite" button: opens lobby-style scan (back to ACTIVE_PUBLIC)
- "QR" button (v0.4.1): shows QR for single-use invite
- "End mesh" button: terminates session

### Device modal

**Tab: Device** (scanning)
- List of nearby hosts advertising mesh
- Tap to send `JoinRequest`
- "Waiting for [host] to create mesh" state after lobby acceptance

**When mesh active**
- Normal hub view (sees host's published content, downloads work as LAN)
- No mesh management controls

---

## Ghost Mode

When host closes the mesh management modal:
- BLE advertising stops → mesh invisible to scanners
- BLE GATT control connections to existing members remain open
- Transfers continue uninterrupted
- Host can re-open modal at any time → advertising resumes → new devices can be invited

The mesh is "phantom" — from the outside, you can't tell if it's active or dissolved.

---

## iOS (future port)

- iOS cannot be Group Owner — Android-only host
- iOS can join as a WiFi client: receives SSID/pass via BLE (`CoreBluetooth`), connects via standard WiFi settings or `NEHotspotConfiguration`
- `MeshCredentials` BLE format is identical — iOS only needs a `CoreBluetooth` central implementation
- FNX2 transfer, mDNS discovery: unchanged, works over P2P subnet
- No code changes needed to transfer stack for iOS support
- **Note**: `NEHotspotConfiguration` requires the `com.apple.developer.networking.HotspotConfiguration` entitlement (App Store review needed) and shows a system confirmation dialog to the user. Alternative: user joins manually via iOS Settings → Wi-Fi. Decide at iOS port time.

---

## QR Pairing (v0.4.1)

QR encodes: `fenixhub://mesh?host_ble=<MAC>&token=<single_use_token>`

- Device scans QR → app opens → connects directly to host BLE GATT using MAC from QR (skips scan)
- `token` is single-use, signed by host, expires after 60s or first use
- Rest of flow identical from `JoinRequest` onward
- Host generates QR from mesh management modal (ACTIVE_PUBLIC state only)

---

## Commit plan

| Commit | Content |
|---|---|
| `feat(android/mesh): BleService GATT server+client + lobby messages` | BleService, JoinRequest/LobbyAck/LobbyKicked |
| `feat(android/mesh): WifiP2pService Group Owner + client join` | WifiP2pService, P2P group create/join/destroy |
| `feat(android/mesh): MeshSession + ephemeral key injection` | MeshSession, group_id/group_key CSPRNG, hub state injection |
| `feat(android/mesh): mesh creation burst + credential delivery` | MeshCredentials encrypt+send, device receive+connect |
| `feat(android/mesh): ghost mode + mesh lifecycle state machine` | LOBBY/ACTIVE_PUBLIC/ACTIVE_GHOST/IDLE transitions |
| `feat(android/mesh): kick + rekey in active mesh` | Kick flow, P2P group recreate, MeshRekeyed burst |
| `feat(android/mesh): host + device modal UI` | Full modal UI both roles |
| `feat(android/mesh): QR single-use invite` | QR generation, token, CoreBluetooth fast-connect |
| `feat(android/mesh): iOS client BLE + WiFi join` | iOS port of BleService client + NEHotspotConfiguration |
