# MeshNet — Mesh Protocol and MethodChannel Contract

Version: 0.1 (MVP architecture specification)
Date: 2026-08-08
Author: TechCorp Managing Director (MD)

## 1. Document Purpose

This document defines the binding contract between the Flutter interface and the
Kotlin mesh engine, along with the message formats and protocol rules. The
Backend and Frontend teams work strictly according to this document.

## 2. Platform Channel API

| field | value |
|---|---|
| Method channel | `meshnet/engine` |
| Event channel | `meshnet/events` |

**Methods (Dart → Kotlin):**

| Method | Parameters | Return |
|-------|-------------|-----------|
| `initEngine` | `displayName: String` | `true` |
| `startNode` | — | `true` (advertise+scan enabled) |
| `stopNode` | — | `true` |
| `getLocalIdentity` | — | `{deviceId, publicKey, displayName}` |
| `scanForPeers` | — | `true` (additional scan request) |
| `pairWithPeer` | `{deviceId, peerPublicKey}` | `true` |
| `sendMessage` | `{targetDeviceId, message}` | `{status, messageId}` |
| `getPeers` | — | `List<{deviceId, displayName, rssi, hopId, authorized}>` |
| `clearPeer` | `deviceId` | `true` |

**Events (Kotlin → Dart):**

| Event | Payload |
|-------|---------|
| `peerDiscovered` | `{deviceId, displayName, rssi, transport}` |
| `peerUpdated` | `{deviceId, rssi, hopCost}` |
| `peerLost` | `{deviceId}` |
| `messageReceived` | `{fromDeviceId, message, messageId}` |
| `deliveryStatus` | `{messageId, status: sent/delivered/failed}` |
| `engineState` | `{state: starting/running/stopped/error}` |

## 3. Kotlin Mesh Engine Package Layout

```
com.meshnet.meshnet_app/
├── MeshEngine.kt              -- MethodChannel handler, lifecycle
├── MeshService.kt             -- Foreground service (broadcasts public state)
├── IdentityStore.kt           -- deviceId, X25519 keypair, displayName
├── crypto/
│   ├── MeshCrypto.kt          -- X25519 + ChaCha20-Poly1305 (libsodium-ish)
│   └── KeyPack.kt             -- serialization helper
├── transport/
│   ├── BleTransport.kt        -- BLE advertise + scan + GATT
│   ├── BleGattServer.kt       -- GATT server (connection + notif)
│   ├── WifiDirectTransport.kt -- Wi-Fi Direct P2P pairing
│   └── TransportManager.kt    -- coordinates transports
├── protocol/
│   ├── MeshFrame.kt           -- byte frame encoder/decoder
│   ├── MessageType.kt         -- enum (0x01..0x08)
│   └── RoutingEngine.kt       -- 2-hop flooding relay
└── storage/
    ├── PeerStore.kt           -- authorized peers, storage
    └── MessageStore.kt        -- message history (local-only)
```

## 3. Message Frame (wire format)

All payloads on the transport network are in **byte format**. Frame layout:

| Byte offset | Field | Notes |
|-----------|--------|------|
| 0-1  | magic `0x4D 0x4E` | MN |
| 2    | version `0x01` | protocol version |
| 3    | type | 0x01..0x08 |
| 4    | hop_limit | MVP: 2 |
| 5    | ttl | message time-to-live remainder (max 6) |
| 6    | flags | bit0 = whether the payload is encrypted |
| 7-22 | sender_id | 16 bytes (new UUID) |
| 23-38| target_id | 16 bytes (broadcast = all zero) |
| 39-46| msg_seq | 8-byte big-endian Long (monotonically increasing per node) |
| 47-78| sender_pubkey | 32-byte X25519 — only for PAIR_REQ/PAIR_ACK/FIND_PEER_ACK |
| 79-? | payload | encrypted bytes (JSON) — E2E |

### MessageType

| Code | Name | Description |
|-----|-----|--------|
| 0x01 | PEER_PING | presence indicator |
| 0x02 | TEXT | encrypted chat message |
| 0x03 | PAIR_REQ | pairing request (via QR) |
| 0x04 | PAIR_ACK | pairing acknowledgement |
| 0x05 | RELAY | intermediate node retransmission |
| 0x06 | DELIVERY_REPORT | delivery report |
| 0x07 | FIND_PEER | search for a user across the network (broadcast) |
| 0x08 | FIND_PEER_ACK | search reply (node found) |

### ESP32-C3 relay node (PHASE 7)

A "transparent repeater" that relays messages between phones — it works with a
BLE adapter just like a phone and does **not create or decrypt** messages (E2E):

- **GATT contract** identical to `BleTransport.kt`: SERVICE
  `6a4e9f01-1d5b-4f1a-8f2b-2e75a4b8c0d1`, TX `6a4e9f02-...`, RX `6a4e9f03-...`.
  Each RX write equals one complete frame (≤244 bytes). RX char `WRITE|WRITE_NR`,
  TX char `READ|NOTIFY`.
- **Advertising**: MFG data `[4E 4D][4D 4E][nodeId(16)]` — same on-air bytes as
  Android's `addManufacturerData(0x4D4E, ...)`.
  Node deviceId: `[0..1]='m''n'`, `[2..7]=ESP32 MAC`, `[12..15]={4D 4E 0E 01}`.
- **Relay rule** (`firmware/mesh_node/relay.h`): parse → dedup
  `(sender_id, msg_seq)` for 60s (`RELAY_SEEN_TTL_MS`) → drop if `ttl == 0` →
  otherwise decrement `ttl` and flood to all connected phones (source excluded).
  Frame bytes are unchanged — RELAY wrapping happens only on phones.
- **MTU**: NimBLE (ESP32-C3) auto-negotiates up to 256 bytes — a 244-byte
  frame fits in a single write.
- **Limitation**: ESP32-C3 `CONFIG_BT_NIMBLE_MAX_CONNECTIONS=3` — server and
  client links total 3, with client connections to a phone limited to 2.

Source: `firmware/mesh_node/` (mesh_frame.c/h, relay.c/h, mesh_node.ino).

## 4. E2E Cryptography Scheme

- Key exchange: **X25519** (RFC 7748)
- Confidentiality + integrity: **ChaCha20-Poly1305** (AEAD)
- Per (self, peer) pair shared secret: `X25519(myPrivate, peerPublic)`
- Payload format: `base64(ciphertext || authTag)`, inside the frame.
- Keys are not persisted in plaintext and no custom key derivation is used. (The
  mathematical strength was reviewed in writing by the Security team — the
  security scheme is sound.)

## 5. Routing (2-hop flooding)

- A → B → C: A sends a RELAY frame; B receives it, and if the target is C, B
  forwards it to C and passes the delivery report back through A.
- B sees the message in transit but cannot read the payload (E2E).
- MVP limitation: hop_limit=2 — 3+ nodes are handled in a later phase.
- Duplicate protection: msg_id (sender_id+msg_seq) — duplicates are rejected.

## 5. Delivery/status

- A → B (direct): B receives → `DELIVERY_REPORT` → A `{status: delivered}`.
- A → C (via B): B relays → C receives → C reports to A, route = A<-B<-C.
- A → no reachable: `{status: failed}` after a 30-second timeout (then ttl 0).

## 6. QA Scenarios (primary scenarios)

| Scenario | Expected result |
|---------|-----------------|
| 2 devices with BLE on, app open | `peerDiscovered` event; peer appears in the list |
| QR pairing (A→B) | Authorized = true, chat can be opened |
| A→C message (B relay) | C received, A delivery=delivered |
| B closes the app | A→C failed or an unroutable event is emitted |
| Transport 2 (Wi-Fi Direct) enabled | seamless switch over from BLE, RSSI updates |

## 6. Flutter Structure

- `lib/core/mesh_service.dart` — MethodChannel and EventChannel wrappers
- `lib/features/chat/` — message screen + logic
- `lib/features/contacts/` — network user list
- `lib/features/pairing/` — QR generation + scan
- `lib/theme/` — professional dark theme

Milestone: MVP 1.0 — all of the above working: BLE transport + E2E text chat + 2-hop relay.

---
© 2026 MeshNet / TechCorp. Confidentially.