# FenixHub — Technical Reference

**Version:** 2.x (FNX2 protocol era)
**Last updated:** 2026-05-12
**Audience:** Contributors, security researchers, website authors, integration developers

---

## Table of Contents

1. [Architecture Overview](#1-architecture-overview)
2. [Transport Modes](#2-transport-modes)
3. [FNX2 Protocol Specification](#3-fnx2-protocol-specification)
4. [Authentication Protocol](#4-authentication-protocol)
5. [Mesh Protocol](#5-mesh-protocol)
6. [ECDH Credential Exchange](#6-ecdh-credential-exchange)
7. [HTTP API Reference](#7-http-api-reference)
8. [Android Architecture](#8-android-architecture)
9. [Security Model](#9-security-model)
10. [Known Platform Quirks](#10-known-platform-quirks)

---

## 1. Architecture Overview

### High-level diagram

```
┌─────────────────────────────────────────────────────────────────┐
│                        FenixHub Android App                     │
│                                                                 │
│  ┌──────────────┐    ┌──────────────────────────────────────┐  │
│  │  WebView UI  │◄──►│         AndroidHubBridge              │  │
│  │ (TypeScript) │    │   (JS ↔ Kotlin message channel)       │  │
│  └──────────────┘    └───────────────┬──────────────────────┘  │
│                                      │                          │
│                    ┌─────────────────▼─────────────────────┐   │
│                    │           FenixHubService               │   │
│                    │         (Android Foreground Service)    │   │
│                    └──┬────────────┬────────────┬───────────┘   │
│                       │            │            │               │
│          ┌────────────▼──┐  ┌──────▼───────┐  ┌▼────────────┐ │
│          │  MeshManager  │  │ NsdController│  │FenixHttpServ│ │
│          │  (state FSM)  │  │ (mDNS/NSD)   │  │(Ktor/Netty) │ │
│          └──┬─────────┬──┘  └──────────────┘  └─────────────┘ │
│             │         │                                         │
│    ┌────────▼──┐  ┌───▼──────────────────────┐                │
│    │MeshGatt   │  │WifiDirectTransferController│                │
│    │Service    │  │(WiFi P2P group mgmt)       │                │
│    │(BLE GATT) │  └────────────────────────────┘               │
│    └───────────┘                                                │
└─────────────────────────────────────────────────────────────────┘
         │                    │                    │
    BLE (GATT)          WiFi Direct          LAN (mDNS + HTTP)
         │                    │                    │
    Remote devices       Remote devices       Remote devices
```

### Component summary

| Component | Role |
|---|---|
| `FenixHubService` | Android Foreground Service; owns all managers; survives app backgrounding |
| `MeshManager` | Mesh state machine; orchestrates BLE lobby, P2P group creation, device lifecycle |
| `MeshGattService` | BLE GATT server (HOST) and client (DEVICE); handles join requests and credential delivery |
| `WifiDirectTransferController` | WiFi Direct P2P group owner/client; manages network binding and transfer sessions |
| `NsdController` | mDNS/NSD registration and discovery for LAN mode |
| `FenixHttpServer` | Ktor/Netty HTTP server on ports 8765 (authenticated) and 8766 (ephemeral direct) |
| `FenixHttpClient` | OkHttp-based client with HMAC signing interceptor |
| `AndroidHubBridge` | Bidirectional WebView↔Kotlin bridge; serialises events to/from the TypeScript UI |

### Data flow — LAN transfer

```
Sender                              Receiver
  │                                    │
  │── NSD register (content item) ────►│  (passive discovery)
  │                                    │
  │◄─ GET /content/{id} ───────────────│
  │   (HMAC-signed request)            │
  │                                    │
  │── HTTP 200 (FNX2 stream) ─────────►│
  │   chunk0 | chunk1 | ... | chunkN   │
  │                                    │
  │                             decrypt+write to disk
```

---

## 2. Transport Modes

### 2.1 LAN Mode

**Discovery:** NSD (Network Service Discovery) / mDNS
- Service type: `_fenixhub._tcp`
- Each published content item is registered as a separate NSD service
- Service name format: `{deviceName}_{contentId}_{groupId_prefix}`
- Receivers filter discovered services by matching the `groupId` prefix against their own group configuration

**Transfer:** Authenticated HTTP over TCP
- Server: Ktor (Netty engine) on port 8765
- Content streamed as FNX2-encrypted chunks (see Section 3)
- Every request signed with HMAC-SHA256 (see Section 4)
- Socket buffers: 2 MB SO_SNDBUF / SO_RCVBUF

**Use case:** Multiple devices on the same WiFi network. Highest throughput of the three modes. Requires all devices to share the same group passphrase.

### 2.2 Direct Mode

**Discovery:** Bluetooth Low Energy (BLE) advertisement + scan
- Similar model to AirDrop proximity discovery
- Device advertises a FenixHub-specific service UUID when publishing content
- Peers scan and enumerate nearby FenixHub devices regardless of network membership

**Transfer:** WiFi Direct P2P (one-on-one)
- BLE channel used only for discovery and handshake; actual data moves via WiFi Direct
- Ephemeral HTTP server on port 8766 (unauthenticated, short-lived)
- Chunk size: 256 KB (smaller than FNX2 chunks; no encryption in ephemeral path)
- Connection closed immediately after transfer completes

**Use case:** Two devices without a shared WiFi network. Works in fully offline environments.

### 2.3 Mesh Mode

**Discovery:** BLE GATT advertisement + scan (HOST advertises a dedicated mesh service UUID)

**Control plane:** BLE GATT characteristics
- JOIN_REQUEST: DEVICE writes its ECDH public key
- MESH_CREDENTIALS: HOST sends encrypted P2P credentials via GATT indication

**Data plane:** WiFi Direct P2P group
- HOST creates a WPA2-protected P2P group with a randomly generated SSID and passphrase
- All accepted devices join the same P2P group
- Group owner IP is always `192.168.49.1` (Android WiFi Direct standard)
- Content served via the authenticated HTTP server on port 8765

**Use case:** Multiple devices physically co-located without any existing network infrastructure. One device becomes the hub; all others consume its content pool simultaneously.

---

## 3. FNX2 Protocol Specification

FNX2 is FenixHub's streaming encryption format. It allows per-chunk AES-256-GCM encryption without buffering the full file in memory on either the sender or receiver.

### 3.1 File header

Offset | Size | Type | Field
-------|------|------|------
0 | 4 | ASCII | Magic: `FNX2` (0x46 0x4E 0x58 0x32)
4 | 12 | bytes | Base nonce (random, per-file)
16 | 4 | u32 BE | Total chunk count
20 | 8 | u64 BE | Original (plaintext) file size in bytes
28 | 1 | u8 | Compression flag (0x00 = none, 0x01 = zstd — reserved for future use)

**Total header size: 29 bytes**

### 3.2 Chunk format

Each chunk immediately follows the header (and subsequent chunks follow each other):

Offset (relative) | Size | Field
------------------|------|------
0 | variable | AES-256-GCM ciphertext (≤ 4,194,304 bytes = 4 MB)
N | 16 | GCM authentication tag (128-bit)

### 3.3 Per-chunk nonce derivation

The per-chunk nonce is derived from the base nonce by XOR-ing the chunk index into bytes `[4..12]` (8 bytes, big-endian u64):

```
chunk_nonce = base_nonce XOR (chunk_index_u64_be << 32_bits_padding)
```

More precisely, bytes `[0..4]` of the chunk nonce are identical to the base nonce. Bytes `[4..12]` become `base_nonce[4..12] XOR chunk_index.to_be_bytes()`.

This ensures:
- Every chunk uses a unique nonce under the same key
- Nonce derivation is deterministic and requires no additional storage
- A partial file or out-of-order delivery can be detected (wrong GCM tag)

### 3.4 Key derivation

**LAN mode:** Key = HKDF(SHA-256, passphrase, salt=group_id, info="fenixhub-fnx2", length=32)

**Mesh mode:** Key derived from the ECDH shared secret (see Section 6).

### 3.5 Decryption flow

```
1. Read and validate 29-byte header (check magic bytes)
2. Extract base nonce, totalChunks, originalSize
3. For i in 0..totalChunks:
   a. Read min(4MB, remaining) ciphertext bytes + 16-byte GCM tag
   b. Compute chunk_nonce by XOR-ing i into base_nonce[4..12]
   c. AES-256-GCM decrypt with chunk_nonce and key
   d. Verify GCM tag — abort on authentication failure
   e. Write plaintext bytes directly to output file
4. Verify total written bytes == originalSize
```

No full-file memory buffer is required at any point. The receiver writes directly to disk.

---

## 4. Authentication Protocol

All requests to port 8765 (authenticated server) must carry a valid HMAC-SHA256 signature.

### 4.1 Canonical message format

The message over which the HMAC is computed:

```
{METHOD}\n{PATH}\n{GROUP_ID}\n{timestampMs}\n{nonceHex}\n{bodySha256Hex}
```

- `METHOD`: uppercase HTTP method, e.g. `GET`, `POST`
- `PATH`: full request path including query string, e.g. `/content/abc123`
- `GROUP_ID`: the sender's group identifier (shared secret derivation input)
- `timestampMs`: Unix timestamp in milliseconds as a decimal string
- `nonceHex`: 16 random bytes encoded as lowercase hex (32 characters)
- `bodySha256Hex`: SHA-256 of the request body as lowercase hex; `e3b0c44...` (SHA-256 of empty) for GET requests

### 4.2 Request headers

Header | Value
-------|------
`X-FenixHub-Sig` | HMAC-SHA256(canonical_message, derived_key) as lowercase hex
`X-FenixHub-Timestamp` | timestampMs (same value used in canonical message)
`X-FenixHub-Nonce` | nonceHex (same value used in canonical message)
`X-FenixHub-Body-SHA256` | bodySha256Hex (same value used in canonical message)

### 4.3 Server-side validation

1. **Timestamp check:** `|server_time_ms - X-FenixHub-Timestamp| <= 30_000` — rejects requests outside the ±30s window.
2. **Nonce uniqueness:** The nonce is checked against an in-memory LRU cache (capacity 8192). Duplicate nonces within the time window are rejected with HTTP 401.
3. **Signature verification:** Reconstruct the canonical message from the request and verify the HMAC. Constant-time comparison to prevent timing attacks.
4. **Body integrity:** Recompute SHA-256 of the received body and compare to `X-FenixHub-Body-SHA256`.

### 4.4 Auth challenge flow

Before a device can pull content, it may need to prove group membership:

```
Client                            Server
  │                                  │
  │── POST /auth/challenge ─────────►│
  │   body: { deviceId, publicKey }  │
  │                                  │
  │◄─ 200 { challenge: hexBytes } ───│
  │                                  │
  │── POST /auth/verify ────────────►│
  │   body: { response: hmac(challenge, key) }
  │                                  │
  │◄─ 200 { token } ─────────────────│
```

---

## 5. Mesh Protocol

### 5.1 State machine

```
IDLE
  │
  │  startMesh()
  ▼
DISCOVERING
  │  (BLE advertising + scanning)
  │
  │  peer found / join request received
  ▼
PENDING
  │  (user sees lobby; can accept/reject)
  │
  │  user accepts; createP2pGroup()
  ▼
FORMING
  │  (waiting for P2P group to be ready + 1800ms beacon propagation)
  │
  │  group ready; credentials sent to all accepted devices
  ▼
ACTIVE
  │  (mesh operational; heartbeat loop running)
  │
  │  user closes modal            stopMesh()
  ├───────────────────────────►  ──────────────────► IDLE
  ▼
ACTIVE_GHOST
  │  (BLE advertising stopped; mesh still running)
  │
  │  user re-opens modal
  ▼
ACTIVE
  (BLE advertising resumes; new devices can join)
```

### 5.2 Full sequence diagram (HOST perspective)

```
HOST                  BLE Layer              DEVICE
  │                      │                      │
  │── startGattServer() ─►│                      │
  │── startAdvertising() ─►│                     │
  │                      │◄── BLE scan ──────────│
  │                      │◄── connect GATT ──────│
  │◄── onDeviceConnected()│                      │
  │                      │◄── writeCharacteristic│
  │                      │    (JOIN_REQUEST +     │
  │                      │     ECDH pubkey)       │
  │◄── onJoinRequest() ──│                      │
  │  [show in lobby UI]  │                      │
  │                      │                      │
  │  [user accepts]      │                      │
  │── createP2pGroup() ──►│  (WiFi Direct layer) │
  │◄── onGroupFormed() ──│                      │
  │   [wait 1800ms]      │                      │
  │                      │                      │
  │── sendCredentials() ─►│                      │
  │   (GATT indication,  │── indication ────────►│
  │    ECDH-encrypted)   │                       │
  │                      │                    decrypt
  │                      │                    credentials
  │                      │                       │
  │                      │              ┌── connectToP2pGroup()
  │                      │              │   (WifiNetworkSpecifier)
  │                      │              │
  │◄── POST /mesh/hello ─┼──────────────┘
  │    { ip, deviceId }  │
  │                      │
  │  [heartbeat loop]    │
  │◄── GET /mesh/ping ───┼────────────── every 2s
  │── 200 OK ───────────►│
  │                      │
  │  [watchdog: >4s silence → DeviceLeft event]
```

### 5.3 State descriptions

| State | Description |
|---|---|
| `IDLE` | No mesh activity. BLE off. HTTP server may still be running for LAN mode. |
| `DISCOVERING` | HOST is advertising a BLE GATT service. DEVICE is scanning. No P2P group yet. |
| `PENDING` | At least one join request received. HOST's lobby UI is showing. P2P group not yet formed. |
| `FORMING` | HOST has accepted device(s). P2P group creation in progress + propagation wait. |
| `ACTIVE` | Mesh fully operational. BLE advertising continues for new joiners. Heartbeat running. |
| `ACTIVE_GHOST` | Modal closed. BLE advertising stopped. Existing connections maintained. Re-opening resumes advertising. |

### 5.4 Heartbeat and watchdog

- DEVICE sends `GET /mesh/ping` every **2 seconds**
- HOST records last-seen timestamp per device
- HOST watchdog fires every **2 seconds** and checks all tracked devices
- If `now - lastSeen > 4000ms` (2 missed pings), the device is considered lost
- HOST emits a `DeviceLeft` event, removes device from mesh, updates UI
- Ping response is HTTP 200 with an empty body; no payload processing needed

### 5.5 Ghost mode details

Ghost mode (`ACTIVE_GHOST`) exists to solve a UX problem: if the HOST always keeps the lobby modal open, the screen stays occupied. Ghost mode lets the HOST use the app normally while the mesh runs in the background.

- Transition to `ACTIVE_GHOST`: user dismisses the mesh modal
- Effect: `stopAdvertising()` called on BLE GATT server. Existing GATT connections (those awaiting credential delivery) are dropped. Already-connected P2P members are **not** affected.
- Transition back to `ACTIVE`: user re-opens the mesh modal; `startAdvertising()` resumes
- No re-keying required when resuming advertising; the P2P group is unchanged

### 5.6 P2P propagation delay

WiFi Direct group formation is nearly instant on stock Android. However, EMUI (Huawei/Honor) has a known SSID scan cycle of approximately 2.5 seconds. If credentials are delivered too early (before the P2P beacon is visible to the device), the WifiNetworkSpecifier connection attempt will fail with "network not found."

FenixHub inserts a **1800ms fixed delay** between `onGroupFormed()` and the credential-delivery burst. This is a pragmatic tradeoff: 1800ms is inside EMUI's scan cycle (avoids the failure) and short enough not to be noticeable to the user. A future improvement would replace this with a `WifiScanReceiver` callback that confirms beacon visibility before proceeding.

---

## 6. ECDH Credential Exchange

### 6.1 Purpose

WiFi Direct credentials (SSID, passphrase, host IP, port) are transmitted over BLE GATT indications. BLE is not a secure channel by default — pairing is not required for GATT in FenixHub's model. ECDH encryption ensures that even if BLE traffic is captured, credentials cannot be recovered without the DEVICE's private key.

### 6.2 Key generation

Both HOST and DEVICE generate **ephemeral** X25519 (ECDH) key pairs for each mesh session:

```kotlin
// Bouncy Castle / Android KeyStore
val keyPair = KeyPairGenerator.getInstance("X25519").generateKeyPair()
val publicKey: ByteArray  = keyPair.public.encoded  // 32 bytes (raw X25519 key)
val privateKey: PrivateKey = keyPair.private
```

Keys are ephemeral: a new pair is generated for each mesh session. On device expel, the HOST generates a new group key and re-sends credentials to all remaining members (rekey burst).

### 6.3 JOIN_REQUEST flow

1. DEVICE generates ephemeral keypair
2. DEVICE writes `JOIN_REQUEST` GATT characteristic with payload: `{ deviceId (UUID), ecdhPublicKey (32 bytes) }`
3. HOST stores the DEVICE's public key indexed by deviceId
4. HOST computes ECDH shared secret: `sharedSecret = ECDH(host_private_key, device_public_key)`
5. HOST derives an AES-256 key: `aesKey = HKDF(SHA-256, sharedSecret, salt=deviceId, info="fenixhub-mesh-creds")`

### 6.4 Credential encryption

```
plaintext = {
  groupKey:   32 bytes (AES key for FNX2 mesh content)
  ssid:       variable UTF-8
  passphrase: variable UTF-8
  hostIp:     "192.168.49.1" (fixed for Android P2P group owner)
  port:       8765
}

nonce = random 12 bytes
ciphertext = AES-256-GCM(aesKey, nonce, plaintext)
payload = nonce || ciphertext || GCM_tag
```

### 6.5 Credential delivery via GATT indication

- Characteristic: `MESH_CREDENTIALS` (HOST notifies DEVICE via indication)
- GATT indication requires acknowledgement from DEVICE (unlike notification)
- MTU negotiation: FenixHub requests the maximum negotiable MTU. If the credential payload exceeds the negotiated MTU, it is split across multiple indications and reassembled by the DEVICE.
- DEVICE acknowledges each indication; HOST waits for ACK before sending the next fragment

### 6.6 DEVICE-side decryption

1. Receive and reassemble indication fragments
2. Extract nonce (first 12 bytes) and ciphertext + tag (remaining bytes)
3. Compute ECDH shared secret using DEVICE's private key and HOST's public key (obtained from `MESH_CREDENTIALS` extended header or a separate characteristic)
4. Derive AES key with HKDF (same parameters)
5. Decrypt and parse credential payload
6. Store credentials; initiate WiFi Direct connection

### 6.7 Rekey on expel

When a device is expelled from the mesh:
1. HOST removes the device from the tracked list
2. HOST generates a new ephemeral keypair
3. HOST derives new shared secrets with each remaining device
4. HOST sends new `MESH_CREDENTIALS` burst with a new group key to all remaining members
5. Content served after expulsion uses the new group key; expelled device cannot decrypt

---

## 7. HTTP API Reference

### 7.1 Port 8765 — Authenticated server

All endpoints require valid HMAC-SHA256 authentication headers (see Section 4).

#### `GET /content/{id}`

Stream a content item encrypted with FNX2.

**Path parameters:**
- `id` — content item identifier (UUID)

**Response:**
- `200 OK` — FNX2 stream (29-byte header + chunks). `Content-Type: application/octet-stream`
- `404 Not Found` — content id not found
- `401 Unauthorized` — invalid or missing authentication

**Notes:** Response is streamed; client must handle chunked transfer. Clients should not rely on `Content-Length` for very large files.

#### `POST /auth/challenge`

Initiate HMAC challenge-response for group membership verification.

**Request body (JSON):**
```json
{
  "deviceId": "uuid-string",
  "publicKey": "hex-encoded-bytes"
}
```

**Response body (JSON):**
```json
{
  "challenge": "hex-encoded-random-bytes"
}
```

#### `POST /mesh/hello`

Sent by a DEVICE after it connects to the WiFi Direct P2P group, to register its P2P IP address with the HOST.

**Request body (JSON):**
```json
{
  "deviceId": "uuid-string",
  "deviceName": "human-readable name",
  "ip": "192.168.49.x"
}
```

**Response:**
- `200 OK` — registration accepted
- `403 Forbidden` — deviceId not in accepted list

#### `GET /mesh/ping`

Heartbeat endpoint. Sent by DEVICE every 2 seconds.

**Response:**
- `200 OK` — empty body
- `403 Forbidden` — deviceId not recognized (e.g. after expulsion)

### 7.2 Port 8766 — Ephemeral direct server

Used for Direct Mode transfers only. No authentication required. Server is created on demand and destroyed after the transfer completes.

#### `POST /send`

Upload a content item (file, image, or text) from DEVICE to HOST.

**Request body:** raw binary (file content) or UTF-8 text
**Request headers:**
- `X-FenixHub-ContentType`: `file`, `image`, or `text`
- `X-FenixHub-FileName`: original filename (for file/image types)
- `Content-Length`: byte length of body

**Response:**
- `200 OK` — accepted
- `413 Payload Too Large` — exceeds direct transfer size limit

**Notes:** No FNX2 encryption on this endpoint. Content is plaintext. The server binds only to the P2P interface IP for the duration of the transfer session, limiting exposure.

---

## 8. Android Architecture

### 8.1 Service layer

**`FenixHubService`** (foreground service)
- Entry point for all network operations. Started at app launch; survives backgrounding.
- Owns references to all manager instances.
- Posts state updates to `StateFlow` observables consumed by the bridge layer.
- Handles the Android lifecycle: acquires WiFi locks, wake locks, and BLE scan power modes.
- Binds to the WebView via `AndroidHubBridge` using a `ServiceConnection`.

### 8.2 Manager layer

**`MeshManager`**
- Implements the state machine described in Section 5.1.
- Coordinates `MeshGattService` (BLE) and `WifiDirectTransferController` (P2P) lifecycle.
- Exposes `meshStateFlow: StateFlow<MeshState>` and `devicesFlow: StateFlow<List<MeshDevice>>`.
- Watchdog coroutine runs on `Dispatchers.IO`; checks device heartbeats every 2s.

**`MeshGattService`**
- Dual-role: GATT server when in HOST mode, GATT client when joining as DEVICE.
- As server: registers service UUID, JOIN_REQUEST and MESH_CREDENTIALS characteristics.
- As client: discovers the HOST's service UUID, writes JOIN_REQUEST, registers indication handler for MESH_CREDENTIALS.
- GATT operations are serialised via a `Channel<GattOperation>` queue to avoid concurrency issues with the Android BLE stack.
- API 33+ compat: uses `BluetoothGatt.requestMtu()` for MTU negotiation; falls back to minimum 20-byte MTU on older APIs.

**`WifiDirectTransferController`**
- Wraps `WifiP2pManager` and `WifiP2pManager.Channel`.
- HOST path: `createGroup()` → waits for `WIFI_P2P_CONNECTION_CHANGED_ACTION` broadcast → extracts group SSID and passphrase.
- DEVICE path: builds a `WifiNetworkSpecifier` for the received SSID/passphrase → requests network via `ConnectivityManager.requestNetwork()`.
- The `NetworkCallback` provides the bound `Network` object, which is passed to OkHttp for all mesh HTTP requests (`OkHttpClient.Builder().socketFactory(network.socketFactory())`).
- No explicit timeout on `requestNetwork()`; the OS lifecycle governs the connection attempt. A coroutine with a 30-second `withTimeout` wraps the callback to prevent indefinite hangs.

**`NsdController`**
- Registers and resolves `_fenixhub._tcp` NSD services.
- One registration per published content item.
- Resolves discovered services to extract `{deviceName, contentId, groupId_prefix}` from the service name.
- Filters discovered services by comparing the extracted groupId prefix against the device's own group configuration.

**`FenixHttpServer`**
- Ktor application with Netty engine.
- Port 8765: authenticated routes installed via a custom `FenixAuthPlugin` (Ktor `ApplicationPlugin`).
- Port 8766: ephemeral application instance created per-transfer; not registered as an NSD service.
- FNX2 streaming: content is read from the content pool in 4 MB chunks, encrypted on-the-fly, and written to the `ApplicationCall.respondOutputStream()` channel.
- Socket parameters set via Ktor's `engineConnector` with `socketOptions { SO_SNDBUF = 2MB; SO_RCVBUF = 2MB }`.

### 8.3 Bridge layer

**`AndroidHubBridge`**
- Implements `WebViewClient` + `WebChromeClient` + a `JavascriptInterface`.
- TypeScript → Kotlin: `window.FenixBridge.postMessage(jsonString)` is routed to `AndroidHubBridge.postMessage()`.
- Kotlin → TypeScript: `webView.evaluateJavascript("window.fenixBridgeEvent(${json})", null)`.
- Events are serialised as JSON with a `type` discriminator field. Example types: `CONTENT_PUBLISHED`, `DEVICE_JOINED`, `DEVICE_LEFT`, `MESH_STATE_CHANGED`, `TRANSFER_PROGRESS`.
- The bridge subscribes to `StateFlow`s in `FenixHubService` and forwards state transitions to the WebView on the main thread.

### 8.4 State management

FenixHub uses Kotlin `StateFlow` throughout (not LiveData or RxJava):

| Flow | Type | Producer | Consumer |
|---|---|---|---|
| `meshStateFlow` | `StateFlow<MeshState>` | `MeshManager` | `AndroidHubBridge`, `FenixHubService` |
| `devicesFlow` | `StateFlow<List<MeshDevice>>` | `MeshManager` | `AndroidHubBridge` |
| `contentPoolFlow` | `StateFlow<List<ContentItem>>` | `FenixHubService` | `NsdController`, `AndroidHubBridge` |
| `discoveredPeersFlow` | `StateFlow<List<MeshDevice>>` | `NsdController` / `MeshGattService` | `AndroidHubBridge` |

All flows are observed in coroutine scopes tied to the service lifecycle. Flows are cancelled when `FenixHubService.onDestroy()` is called.

### 8.5 Frontend (TypeScript WebView)

- Vanilla TypeScript (no framework). Compiled with Bun + esbuild.
- Communicates with the native layer exclusively through `AndroidHubBridge`.
- Handles all UI rendering: content list, mesh lobby, transfer progress, settings.
- Same TypeScript frontend is used in the Tauri desktop build, with a different bridge implementation (`window.__TAURI__` IPC replaces `window.FenixBridge`).
- Android-specific styles in `android.css`; platform detection at runtime via `window.FenixBridge !== undefined`.

---

## 9. Security Model

### 9.1 Threat model

**What FenixHub protects against:**

- **Passive eavesdropping on LAN:** AES-256-GCM encryption on all content transfers. A WiFi sniffer on the same network sees only ciphertext.
- **Unauthorized content access:** HMAC-SHA256 authentication on every HTTP request. A device without the group passphrase cannot derive the signing key and cannot fetch content.
- **Replay attacks:** Each request uses a unique 16-byte random nonce. The server rejects any nonce seen within the last 30 seconds. An attacker capturing a valid signed request cannot replay it after the window expires.
- **Clock manipulation attacks:** The ±30s timestamp window combined with nonce uniqueness means an attacker cannot simply adjust the timestamp — the nonce would still need to be fresh and unique.
- **BLE credential interception in Mesh mode:** ECDH key exchange ensures mesh credentials (WiFi Direct SSID, passphrase, group AES key) are encrypted before transmission over BLE. Passively capturing BLE traffic does not yield usable credentials.
- **Unauthorized mesh participation:** The HOST explicitly approves each device in the lobby UI. No device auto-joins. The HOST can expel devices and trigger a rekey.

**What FenixHub does NOT protect against:**

- **Compromised group passphrase:** LAN mode security depends entirely on the secrecy of the group passphrase. If an attacker learns the passphrase, they can derive the signing key and decrypt all content.
- **Physical access to the HOST device:** Content stored in the app cache is not encrypted at rest with additional protection beyond Android's standard file-based encryption (FBE). An attacker with unlocked physical access to the HOST can read cached files.
- **Man-in-the-middle on the P2P network:** The WiFi Direct network is WPA2-protected (random passphrase), but once a device is on the network it can see all HTTP traffic. FNX2 encryption means content remains ciphertext on the wire, but a compromised device that has been given the group key can decrypt content.
- **Denial of service:** FenixHub does not implement rate limiting on the HTTP server beyond Android's OS-level TCP stack. A device on the LAN could exhaust the server with requests.
- **BLE proximity attacks:** BLE advertisement scanning does not authenticate advertisers. A malicious device can spoof a FenixHub advertisement and appear in the lobby. The HOST's explicit approval step is the primary defense.

### 9.2 Key management summary

| Key | Derivation | Lifetime | Storage |
|---|---|---|---|
| Group AES key (LAN) | HKDF(passphrase, group_id) | Until passphrase changes | In-memory; derived on demand |
| Mesh group AES key | Random 256-bit | Until device expel or session end | In-memory only |
| ECDH keypair (HOST) | Ephemeral X25519 | Per mesh session | In-memory only |
| ECDH keypair (DEVICE) | Ephemeral X25519 | Per join attempt | In-memory only |

No private keys are written to persistent storage. Session teardown clears all in-memory key material.

---

## 10. Known Platform Quirks

### 10.1 EMUI / HarmonyOS (Huawei, Honor)

**WiFi Direct SSID scan cycle:** EMUI devices refresh their WiFi scan results approximately every 2.5 seconds (vs. ~1s on stock Android). FenixHub's 1800ms post-group-formation delay is designed around this cycle. If the delay is reduced below ~1500ms, EMUI devices fail to find the P2P group SSID.

**WiFi coexistence (STA + P2P):** EMUI enforces stricter WiFi resource sharing between the station (STA) interface and the P2P interface. In some firmware versions, connecting to a P2P group automatically disconnects the device from its existing WiFi AP. This is a hardware/firmware limitation and cannot be worked around in software. Users on EMUI may lose internet access while connected to a FenixHub mesh.

**WPA2 passphrase handling:** FenixHub uses `WifiNetworkSpecifier.Builder().setWpa2Passphrase()` (labeled "Cifrada" in EMUI's internal logs). This is the same codepath used by Samsung accessories and works correctly on EMUI when the passphrase is exactly 8–63 ASCII characters. FenixHub generates P2P passphrases within this range.

### 10.2 Android 10 / API 29 P2P limitations

**`WifiNetworkSpecifier` requires API 29:** `ConnectivityManager.requestNetwork()` with a `WifiNetworkSpecifier` was introduced in API 29. This is why Android 10 is the minimum supported version. Earlier versions would require `WifiManager.enableNetwork()` which is deprecated and unreliable on API 29+.

**`WifiP2pManager.requestGroupInfo()` deprecation:** On API 29+, `WifiP2pManager.requestGroupInfo()` is the correct API for retrieving P2P group credentials after `createGroup()`. On API 30+, the async overload with `WifiP2pManager.GroupInfoListener` is preferred. FenixHub targets both via an API version check.

**Background WiFi scanning restrictions:** Android 10+ restricts background BLE scanning when the app is not in the foreground. FenixHub uses a foreground service (`FenixHubService`) with the `FOREGROUND_SERVICE_CONNECTED_DEVICE` type to maintain BLE scanning permissions while backgrounded.

### 10.3 BLE MTU and GATT indication size

**Default MTU:** Android's BLE stack defaults to an ATT MTU of 23 bytes (20 bytes payload after ATT overhead). GATT indications carrying mesh credentials would require hundreds of fragments at this MTU.

**MTU negotiation:** FenixHub calls `BluetoothGatt.requestMtu(512)` immediately after connection. Most modern Android devices and BLE controllers support at least 185 bytes (minimum for 5.x feature compatibility). In practice, 512 bytes is commonly negotiated.

**Fragmentation:** FenixHub's credential payload (ECDH-encrypted credentials) is typically 150–300 bytes. At 512-byte MTU (509 bytes ATT payload), this fits in a single indication. FenixHub nevertheless implements multi-indication reassembly for robustness on constrained hardware.

**API 33+ GATT change:** `BluetoothGatt.writeCharacteristic(characteristic, value, writeType)` (the three-argument overload) is required on API 33+. The single-argument overload using `characteristic.setValue()` is deprecated and may not function correctly. FenixHub uses a compatibility shim:

```kotlin
if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
    gatt.writeCharacteristic(characteristic, value, writeType)
} else {
    @Suppress("DEPRECATION")
    characteristic.value = value
    @Suppress("DEPRECATION")
    gatt.writeCharacteristic(characteristic)
}
```

### 10.4 Android 12 / API 31 Bluetooth permissions

API 31 introduced `BLUETOOTH_SCAN`, `BLUETOOTH_CONNECT`, and `BLUETOOTH_ADVERTISE` runtime permissions, replacing the older `ACCESS_FINE_LOCATION` requirement for BLE scanning. FenixHub requests all three. The legacy `ACCESS_FINE_LOCATION` is also requested for API 29–30 compatibility. Permission checks are performed at runtime before any BLE operation.

### 10.5 ConnectivityManager.requestNetwork() lifecycle

`requestNetwork()` does not have a configurable timeout on API 29. FenixHub wraps the callback in a 30-second `withTimeout` coroutine. If the network is not available within 30 seconds (e.g. the device never found the P2P SSID), the coroutine cancels and a `MeshError` state is emitted. The timeout was chosen to be longer than the worst-case EMUI scan cycle (2.5s × up to ~10 retries) while still providing eventual failure feedback to the user.

---

*This document is the authoritative technical reference for FenixHub. For the user-facing description, see the top-level [README.md](../README.md).*
