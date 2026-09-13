# MeshNet — PHASE 0: Full System Architecture

> **Version:** 0.2
> **Date:** 2026-08-16
> **Author:** TechCorp — Managing Director (MD) + all departments
> **Status:** PHASE 0 (architecture) — not yet approved, under review
> **Existing code base:** `meshnet_app/` (Flutter UI + Kotlin mesh engine)

---

## Table of Contents

1. [Purpose and Scope](#1-purpose-and-scope)
2. [A. Full System Architecture](#a-full-system-architecture)
3. [B. MVP Architecture](#b-mvp-architecture)
4. [C. Protocol Design](#c-protocol-design)
5. [D. Packet Format](#d-packet-format)
6. [E. Node Identification](#e-node-identification)
7. [F. Peer Discovery Strategy](#f-peer-discovery-strategy)
8. [G. Connection Strategy](#g-connection-strategy)
9. [H. Routing Strategy](#h-routing-strategy)
10. [I. Encryption/Security Architecture](#i-encryptionsecurity-architecture)
11. [J. Store-and-Forward Architecture](#j-store-and-forward-architecture)
12. [K. ACK/Retry Architecture](#k-ackretry-architecture)
13. [L. Node Failure Recovery](#l-node-failure-recovery)
14. [M. Flutter ↔ Kotlin Architecture](#m-flutter--kotlin-architecture)
15. [N. Android BLE/Wi-Fi Strategy](#n-android-blewi-fi-strategy)
16. [O. Embedded Hardware Roadmap](#o-embedded-hardware-roadmap)
17. [P. Repository Structure](#p-repository-structure)
18. [Q. Team Roles](#q-team-roles)
19. [R. Development Roadmap](#r-development-roadmap)
20. [S. Risk Analysis](#s-risk-analysis)
21. [T. Testing Strategy](#t-testing-strategy)
22. [U. Scalability Analysis](#u-scalability-analysis)
23. [V. Technology Comparison](#v-technology-comparison)
24. [Platform Constraints (IMPORTANT PLATFORM RULE)](#platform-constraints)
25. [Comparison with Existing Code and Gaps](#comparison-with-existing-code-and-gaps)
26. [Open Decisions for Review](#open-decisions-for-review)

---

## 1. Purpose and Scope

This document defines the **complete architecture** of the MeshNet system. The
goal is to build a **real, working, decentralized** offline mesh network that
runs on **physical devices**, enabling people to exchange messages without
internet, cellular service, or a central server.

For every important technical decision, the document explains **WHAT** is chosen
and **WHY exactly that**. If any decision is uncertain, it is stated openly and
an experimental validation path is proposed.

**Core principles:**
- A real system — not a demo. No fake/simulated networking, no fake encryption.
- Phased development: we move to the next phase only after the current one is tested.
- Simple for a small team, yet able to scale from 10→100→1000 nodes.
- Not simplistic: as minimal as possible, but not weak — security meets standards.

---

## 2. A. Full System Architecture

### 2.1 Overall Layers

```
┌─────────────────────────────────────────────────────────────┐
│  APP LAYER (Flutter UI)                                      │
│  Splash · Identity · Home · Messages · Nearby · Map · SOS ·  │
│  Settings · Debug Mode                                       │
└─────────────────────────────────────────────────────────────┘
                        │ Dart ↔ MethodChannel/EventChannel
┌─────────────────────────────────────────────────────────────┐
│  DART APP LOGIC (Riverpod)                                   │
│  Session · Contact model · Message model · UI state          │
└─────────────────────────────────────────────────────────────┘
                        │ Platform Channel contract (contract below)
┌─────────────────────────────────────────────────────────────┐
│  MESH CORE (Kotlin — Android)                                │
│  IdentityStore · MeshCrypto · RoutingEngine · MessageStore · │
│  PeerStore · StoreAndForward · TransportManager              │
└─────────────────────────────────────────────────────────────┘
                        │
┌─────────────────────────────────────────────────────────────┐
│  TRANSPORT LAYER (Kotlin)                                    │
│  BleTransport (advertise/scan/GATT) · WifiDirectTransport    │
└─────────────────────────────────────────────────────────────┘
                        │
┌─────────────────────────────────────────────────────────────┐
│  PHYSICAL LAYER                                              │
│  Android Bluetooth/Wi-Fi radios · (future) ESP32/LoRa        │
└─────────────────────────────────────────────────────────────┘
```

**Why these layers?**
- **UI (Flutter)** — a single code base that can later move to iOS. Simplicity
  matters in emergency environments.
- **Mesh Core (Kotlin)** — BLE/Wi-Fi P2P APIs exist only in native Android.
  Network logic lives in native code, independent of the UI.
- **Platform Channel** — a strict contract between Flutter and Kotlin. This
  contract guarantees the UI does not change even if the transport
  implementation changes (e.g., BLE → LoRa).
- **Transport layer** — each radio is a separate module. Adding a new transport
  is done from a single point through `TransportManager`.

### 2.2 Component Flow (sending a message as an example)

```
Flutter ChatView
  → Dart sendMessage() [MethodChannel]
  → MeshEngine.handleMethodCall("sendMessage")
  → RoutingEngine.sendText()
      → MeshCrypto.encrypt (X25519 shared secret + ChaCha20-Poly1305)
      → MeshFrame.encode (binary packet)
  → TransportManager.sendFrame (WIFI → BLE fallback)
  → radio → air → receiving device
```

### 2.3 Incoming Data Flow (events)

```
Transport (BLE/Wi-Fi frame received)
  → TransportManager.onFrameReceived
  → RoutingEngine.handleIncomingFrame (duplicate check → by type)
  → decryptAndDeliver (if addressed to us)
      → MessageStore.addIncoming
      → EventChannel emit("messageReceived")
  → Dart incomingMessagesProvider
  → ChatView UI
```

---

## 3. B. MVP Architecture

### 3.1 MVP Scope

| # | Capability | Goal |
|---|-----------|--------|
| 1 | 2-5 Android phones | PHASE 1-2 |
| 2 | BLE + Wi-Fi Direct transport | PHASE 1 |
| 3 | QR pairing (identity verification) | PHASE 3 |
| 4 | Text chat (E2E encrypted) | PHASE 3 |
| 5 | 2-hop relay (A→B→C) | PHASE 2 |
| 6 | Delivery status (sent/delivered/failed) | PHASE 3 |
| 7 | Network topology (debug) | PHASE 2 |
| 8 | Store-and-forward (offline receipt) | PHASE 5 |

### 3.2 Deliberately OUT of MVP

- Voice/calls, video, file sharing
- Forward secrecy (key exchange at session level) — next phase
- iOS — after the Android MVP is validated
- LoRa / embedded nodes — PHASE 7-8
- Metadata privacy (protection against traffic analysis) — advanced privacy

### 3.3 MVP Components (aligned with existing code)

| Component | File | Status |
|-----------|------|-------|
| Flutter UI (5 screens) | `lib/` | ✅ present |
| MethodChannel wrapper | `lib/core/mesh_service.dart` | ✅ present |
| Identity | `IdentityStore.kt` | ✅ present (with Security reserve) |
| Crypto | `crypto/MeshCrypto.kt` | ✅ present |
| Frame encode/decode | `protocol/MeshFrame.kt` | ✅ present |
| Routing | `protocol/RoutingEngine.kt` | ✅ present (fixes needed) |
| BLE | `transport/BleTransport.kt` | ⚠️ has bugs (see §25) |
| Wi-Fi Direct | `transport/WifiDirectTransport.kt` | ⚠️ has bugs |
| Peer/Message storage | `storage/*.kt` | ✅ present |

---

## 4. C. Protocol Design

### 4.1 Message Types

| Code | Type | Purpose | Lifecycle | Security |
|-----|-----|--------|-----------|------------|
| 0x01 | `PEER_PING` | Presence indicator (heartbeat) | recurring, low-power | encryption not required (metadata is open) |
| 0x02 | `TEXT` | Encrypted chat message | CREATED→QUEUED→FORWARDED→DELIVERED | E2E encrypted |
| 0x03 | `PAIR_REQ` | QR pairing request | one-shot | public key open, mutual verification |
| 0x04 | `PAIR_ACK` | Pairing acknowledgement | one-shot | public key open |
| 0x05 | `RELAY` | Intermediate node retransmission | transient | contains an E2E frame; relay cannot read it |
| 0x06 | `DELIVERY_REPORT` | Delivery report (ACK) | A→...→A | metadata only; authored by the final recipient |
| 0x07 | `FIND_PEER` | Network search (route recovery) | request/reply | open metadata |
| 0x08 | `FIND_PEER_ACK` | Search reply (node found) | request/reply | open metadata, with pubkey |

**Future-oriented types** (PHASE 4+): `ROUTE_REQUEST`, `ROUTE_RESPONSE`,
`ROUTE_ERROR`, `SOS`, `BROADCAST`.

**Why is `SOS` a separate type?** SOS is distinct because: (1) it has high
priority, (2) it can be sent to other users (even those not paired), (3) its
store-and-forward rules differ. SOS is not in the MVP — it arrives in PHASE 4-5.

### 4.2 Protocol Rules

1. **Broadcast** target: `target_id = all-zero` for everyone.
2. **Duplicates**: the `(sender_id, msg_seq)` pair is checked in the seen-cache.
3. **TTL**: `ttl = ttl - 1` on each relay; `ttl = 0` → DROP.
4. **Hop limit**: 2 in the MVP; PHASE 4+ based on routing information.
5. **Frame integrity**: `magic + version` check; invalid frames are dropped
   (with logging).
6. **Malicious input**: every incoming frame is length- and format-checked
   before parsing. Exceptions → drop, not crash.

---

## 5. D. Packet Format

### 5.1 Choice: JSON / CBOR / binary

| Criterion | JSON | CBOR | Binary (strict) |
|-------|------|------|------------------|
| Size (30-byte header) | ~120 bytes | ~55 bytes | **43 bytes** |
| Parse speed | slow | average | **fastest** |
| BLE MTU (244 B) capacity | ~100 bytes payload | ~180 bytes | **~200 bytes** |
| Deterministic layout | no | partial | **yes** |
| Readability | ✅ | average | hard (requires documentation) |
| Embedded (ESP32) | slow | average | **best fit** |

**Decision:** binary strict frame. **Why:** the BLE MTU is 244 bytes — every
byte matters. The payload is usually encrypted text (ChaCha20-Poly1305 tag +
nonce), so JSON is only 40-50% efficient at that size. The deterministic layout
is encoded identically even on embedded devices (in C).

**Proposed frame** (current `MeshFrame.kt` + extensions):

| Offset | Field | Size | Notes |
|--------|--------|------|------|
| 0-1 | magic `0x4D 0x4E` | 2 | "MN" |
| 2 | version | 1 | `0x01` |
| 3 | type | 1 | MessageType code |
| 4 | hop_limit | 1 | MVP: 2 |
| 5 | ttl | 1 | max 8 |
| 6 | flags | 1 | bit0=encrypted, bit1=priority(SOS) |
| 7-22 | sender_id | 16 | UUID (128-bit) |
| 23-38 | target_id | 16 | broadcast = all-zero |
| 39-46 | msg_seq | 8 | epoch-ms Long (preserved exactly across relays) |
| **47-48** | **payload_len** | **2** | **NEW: required for framing (BLE chunk)** |
| 49-? | sender_pubkey | 32* | only for PAIR_REQ/PAIR_ACK |
| ? | payload | n | encrypted text |

**Why `payload_len` must be added:** BLE messages are split into chunks. The
current code manages chunk length itself, but the receiver needs an explicit
length to know when the full frame is complete. This field already exists in the
Wi-Fi socket protocol (`DataInputStream.readInt`) — the BLE side should match it.

### 5.2 Node ID — 128-bit UUID

- **Why 128-bit:** it is the UUID standard, Flutter/Kotlin/UUID support exists,
  and the collision probability is practically zero. (Reticulum uses 80-bit —
  not needed for our MVP, and 16 bytes fit perfectly in the frame.)
- Broadcast: 16 zero bytes.

---

## 6. E. Node Identification

### 6.1 Architecture

```
Device
 ├── Node ID        (128-bit UUID, generated once)
 ├── Public Key     (X25519, 32 bytes)  — public, visible in the QR
 └── Private Key    (X25519, 32 bytes)  — never leaves the device
```

- **Node ID** — the device's public identifier (shown in the network as a short
  form like "MN-7F3A92").
- **X25519 key pair** — used for identification and key exchange. The Node ID and
  the public key are linked: ID can be `hash(public_key)` (identity = key). This
  is the "identity-committed" approach.
  - **Why identity = hash of the public key?** It prevents impersonation:
    someone needs your *private key* to take over your ID. The Node ID is also
    generated automatically at the same time.
  - **Current state:** the ID is a random UUID and the key is separate — this is
    also valid, but switching to ID=hash(pubkey) is recommended in the next phase.

### 6.2 Identity storage, reset, rotation, recovery

| Event | Approach | Why |
|--------|-----------|------|
| **Storage** | Private key → **Android Keystore** (hardware-backed, API 23+). Currently in SharedPreferences — an MVP limitation; moved to Keystore in PHASE 9 | Keystore is more secure; SharedPreferences can be read on rooted devices |
| **Device reset** | A new identity is generated; old keys are lost | By design: the private key cannot be recovered |
| **Key rotation** | MVP: none. PHASE 6+: rotation certificate signed with the old key | A single lifelong key puts the whole identity at risk if compromised |
| **Recovery (backup)** | Only **public** material can be copied (QR/folder). No private key backup | Private key backup is a security flaw |
| **Identity verification** | QR pairing — an out-of-band (visual) channel. Both parties confirm each other's public keys | MITM protection: the QR is only shown in person |

---

## 7. F. Peer Discovery Strategy

### 7.1 BLE discovery

- **Advertise:** every node announces itself in a BLE advertisement:
  - Service UUID `6a4e9f01-...` (MeshNet)
  - Device name = displayName (advertisement name)
  - Mode: `LOW_LATENCY` active / `LOW_POWER` reduced (battery)
- **Scan:** `SCAN_MODE_LOW_POWER` in the background, `LOW_LATENCY` on-demand
  (pull-to-refresh). Service UUID filter to avoid seeing other BLE devices.
- **RSSI:** recorded at discovery; a new scan result updates the old value.
- **Cooldown:** avoid repeating discovery events for a single peer within a
  short window (prevent event flooding).

### 7.2 Wi-Fi Direct discovery

- `discoverPeers()` + `WIFI_P2P_PEERS_CHANGED_ACTION` receiver.
- `WifiP2pDevice.AVAILABLE` → peer found; `UNAVAILABLE` → peer gone.

### 7.3 Discovery → PeerStore flow

```
Found (BLE/Wi-Fi)
  → PeerStore.upsert(deviceId, displayName, rssi, transport, lastSeen=now)
  → emit("peerDiscovered")
  → Dart peersProvider (FutureProvider) is updated
```

**IMPORTANT GAP (in the current code):**
`TransportManager.bleListener.onPeerDiscovered` only writes a log — it does
**not upsert** into PeerStore and does not emit the event. As a result, the UI
"Network coverage" is never populated. This is one of the first fixes of PHASE 1
(§25).

---

## 8. G. Connection Strategy

### 8.1 BLE connection model

- **Presence:** advertising is always on (for lazy connection — "I am here").
- **Connection:** **on-demand (lazy)** — a GATT connection is opened only when a
  message needs to be sent.
  - **Why lazy?** On Android, a device supports only ~7-9 simultaneous GATT
    client connections. Staying connected to everyone immediately hits the limit.
  - Connection pool: idle/old connections are closed (LRU).
- **Message exchange:** GATT characteristic write/notify. Long messages are
  split into chunks (244 bytes / ATT MTU).
- **Disconnect:** when RSSI is lost or after 30s of inactivity.

### 8.2 Wi-Fi Direct connection model

- Group formation: each node either calls `createGroup` (Group Owner) or joins an
  existing group.
- TCP socket (PORT 4864): GO ↔ client. Group size on Android is typically 5-8.
- Sockets are reliable: persistent within the group.

### 8.3 Cross-layer selection

`TransportManager.sendFrame` order: **Wi-Fi Direct → BLE fallback**.
- **Why Wi-Fi first?** Higher speed and throughput, simpler socket protocol, no
  message chunking required.
- **Why BLE second?** Battery-efficient for presence and small messages. Not
  everyone on BLE is necessarily in the same Wi-Fi Direct group.

---

## 9. H. Routing Strategy

### 9.1 Routing algorithm comparison

| Criterion | Flooding | BATMAN-like (proactive rank) | AODV (reactive) | DSR |
|-------|----------|------------------------------|-----------------|-----|
| Latency | low (immediate) | low | high (route discovery) | average |
| Battery | high cost | average | low (on demand) | low |
| Bandwidth | high | average | low | low |
| Memory | low | low | average | high |
| Scalability (10-20) | ✅ good | ✅ | ✅ | average |
| Scalability (100+) | ❌ explosion | average | average | ❌ |
| Mobile nodes | ✅ best | ✅ | average | weak |
| Implementation complexity | **lowest** | average | average | high |
| Convergence (route discovery) | none (always fresh) | fast | slow | slow |

### 9.2 Decision

**MVP → Controlled Flooding.**

Why:
1. For 5-20 nodes the flooding cost is very low — each message exists in only
   2-3 copies.
2. No route table → no convergence time → works with mobile nodes (the "path" is
   always fresh at any moment).
3. If a node disappears, a new flood automatically finds a new path (ready for
   PHASE 6).
4. The simplest and most reliable MVP start.

Flooding control mechanisms:
- **TTL** (max 8) — loop limit.
- **Hop limit** (MVP: 2) — limit on new nodes.
- **Seen-cache** (duplicates) — `(sender_id, msg_seq)`.
- **Cache expiry:** a seen-cache entry lives for 60 seconds (on the RTT scale),
  then is removed (so refreshed messages pass through, but floods are not
  rebroadcast).

### 9.3 RoutingEngine abstraction

```kotlin
interface RoutingEngine {
    fun handleIncomingFrame(frame: MeshFrame)
    fun sendText(targetId: String, message: String): String
    fun sendBroadcast(payload: ByteArray)
    fun nodeStatusChanged(deviceId: String, online: Boolean)  // PHASE 6
}
```

The architecture allows replacing the routing algorithm later:
`FloodingRoutingEngine` (MVP) → `RankRoutingEngine` (BATMAN-like, PHASE 4).

### 9.4 Current code analysis

`RoutingEngine.kt` already performs 2-hop flooding (using the RELAY type). Fixes
are needed:
- Actually decrement `ttl` (relying on `hopLimit` is buggy — the TTL must also
  decrease at each relay).
- Add expiry to the `seenMessages` cache (it currently grows without bound —
  memory leak).
- Support broadcast (all-zero target).

---

## 10. I. Encryption/Security Architecture

### 10.1 Choice: primitives

| Component | Choice | Alternative | Why chosen |
|-----------|--------|------------|----------------|
| Key exchange | **X25519** (static identity keys) | ECDH P-256 | Curves resistance, broad support (BouncyCastle) |
| Confidentiality + integrity | **ChaCha20-Poly1305** (AEAD) | AES-GCM | AES-GCM requires hardware acceleration outside phones (ESP32); ChaCha20-Poly1305 is fast and safe in software |
| Signature (PHASE 4+) | **Ed25519** | ECDSA | Distinctive, fast, secure |
| Replay protection | nonce + msg_seq + seen-cache | — | standard approach |

**IMPORTANT:** the `ChaCha20-Poly1305` JCE `Cipher` is available on **Android
API 28+**. With `minSdk = 26`, API 26-27 devices throw an exception. Solutions:
- Use `Cipher.getInstance("ChaCha20-Poly1305", "BC")` via the **BouncyCastle
  provider** (bcprov 1.79 is already a dependency). Works on all APIs.
- Or raise `minSdk` to 28.

**Recommendation:** enable the BouncyCastle provider via `Security.addProvider()`
(one line) and keep `minSdk = 26`.

### 10.2 Session key scheme

```
Alice (X25519)            Bob (X25519)
   │    pubA ───────────────▶ │
   │ ◀────────────── pubB    │
   │                          │
   secret = X25519(privA, pubB) == X25519(privB, pubA)
   │                          │
   encrypt(msg, secret) ────▶ decrypt(msg, secret)
```

- One static shared secret per (self, peer) pair.
- **Forward secrecy: NOT in the MVP** (static key). PHASE 6+: ephemeral
  X25519 + ratchet (Signal-style) or HPKE.
  - **Risk:** if a private key is compromised, old messages can be read.
  - **Mitigation:** in the MVP the key stays only on the device and the QR is
    only shown in person; the risk is low.

### 10.3 AAD (Associated Authenticated Data)

`aad = "MeshNet:" + targetId` — binds the routing information to the ciphertext:
if the AAD is wrong while the receiver decrypts, the tag fails. This prevents a
"replay to another target" attack. ✅ already present in the current code.

### 10.4 Identity verification

- QR pairing: a visual channel — protection against MITM.
- After pairing: `PeerStore.markAuthorized(deviceId, pubKey)`.

### 10.5 Security goals × MVP status

| Goal | MVP | Notes |
|--------|-----|------|
| E2E encryption | ✅ | X25519 + ChaCha20-Poly1305 |
| Authentication | ✅ | Identity = key, QR verification |
| Integrity | ✅ | AEAD tag |
| Replay protection | ✅ | nonce + seen-cache |
| Forward secrecy | ⏳ | PHASE 6 |
| Key management | ⚠️ | Keystore in PHASE 9; currently SharedPreferences |
| Identity verification | ✅ | QR |

### 10.6 Threat model (summary — full table in §S)

| Threat | Impact | Likelihood | Mitigation | Residual risk |
|--------|--------|---------|------------|-------------|
| Eavesdropping | high | medium | E2E (relay cannot read) | low |
| MITM | high | low | QR out-of-band | low |
| Replay | medium | low | nonce + seen-cache | low |
| Node impersonation | high | low | identity=key | low |
| Sybil | medium | medium | QR confirmation (authorized only) | medium |
| Packet flooding (DoS) | medium | medium | seen-cache + rate limit (PHASE 9) | medium |
| Fake ACK | low | low | ACK via final recipient; MVP is simple | medium |
| Malicious relay | medium | low | relay sees only metadata, E2E protects | low |
| Traffic analysis | medium | medium | **open in the MVP** — metadata leak | high |

### 10.7 Metadata privacy: MVP vs Advanced

- **MVP:** sender/target UUID, TTL, timestamps — **open** (the relay sees them).
  This is required for routing and is also open at the transport level (BLE
  advertisement name).
- **Advanced (PHASE 8+):** constantly rotating advertisement names, padding,
  dummy traffic, onion-style — these are not introduced in the MVP, but the
  architecture supports them by adding protocol types.

---

## 11. J. Store-and-Forward Architecture

### 11.1 Purpose

When the recipient is offline, the message is stored on an intermediate node and
delivered once the recipient rejoins the network.

### 11.2 Model

```
Message → QUEUED (local MessageStore, TTL starts)
  → retry every X seconds (with jitter)
  → if peer is online → send → DELIVERED
  → TTL expires (24h) → EXPIRED (the user is notified)
```

- **Storage size:** max 5MB or 500 messages (round-robin eviction).
- **Priority:** SOS > TEXT > PING.
- **Duplicates:** stored messages are also deduplicated by `(sender, seq)`.
- **Persistence:** `MessageStore` in SharedPreferences (MVP); PHASE 9: Room + a
  locally encrypted DB.
- **Messages are already E2E encrypted** — no plaintext is stored on disk.

### 11.3 Event: peer rejoin

`peerDiscovered` (online) → `StoreAndForward.flush(deviceId)` → stored messages
are sent.

---

## 12. K. ACK/Retry Architecture

### 12.1 Message lifecycle

```
CREATED → QUEUED → FORWARDED → DELIVERED (ACK received)
                             → EXPIRED (TTL 0, was never stored)
                             → FAILED (3 retries → failed)
```

### 12.2 ACK flow

```
A → B → C → D (TEXT)
D decrypts
D → C → B → A (DELIVERY_REPORT, delivered=1)
A: removes from pending map, ✓✓ in the UI
```

### 12.3 Retry policy

- Timeout: 30 seconds (jitter ±5s).
- Retry count: max 3.
- Backoff: 30s → 60s → 120s (exponential).
- Retry cap: 3 — **no unlimited retry** (battery + bandwidth).
- After failure → `FAILED` state, resend button in the UI.

### 12.4 Fake-ACK protection (MVP approach)

- The DELIVERY_REPORT is created by the final recipient and carries the original
  `(sender, seq)` back.
- A relay could modify it (malicious relay). There is no complete prevention in
  the MVP; PHASE 6: sign the DELIVERY_REPORT with the sender's public key
  (Ed25519).

---

## 13. L. Node Failure Recovery

### 13.1 Detection

- **Heartbeat:** `PEER_PING` every 15s (battery-aware, with jitter).
- If a peer does not respond for 3× the interval (45s) → `offline`, the route is removed.
- BLE RSSI lost → `peerLost`.
- Wi-Fi `UNAVAILABLE` → `peerLost`.

### 13.2 Recovery (in flooding)

- With no route table: a new message automatically finds a new path via a new
  flood. **This is flooding's biggest advantage** — no convergence time.
- If sending fails: `FIND_PEER` broadcast → path search → resend.

### 13.3 Stale route cleanup

- In `PeerStore`, `lastSeenMs > 60s` → the peer is shown as offline in the UI
  (not deleted — kept for history).
- Seen-cache 60s expiry (so new floods pass through).
- PHASE 6: with a routing table — `ROUTE_ERROR` and recalculation.

---

## 14. M. Flutter ↔ Kotlin Architecture

### 14.1 Platform Channel contract (current + extension)

**Method channel: `meshnet/engine`**

| Method | Parameters | Return | Status |
|-------|-------------|-----------|-------|
| `initEngine` | `displayName` | `true` | ✅ |
| `startNode` | — | `true` | ✅ |
| `stopNode` | — | `true` | ✅ |
| `getLocalIdentity` | — | `{deviceId, publicKey, displayName}` | ✅ |
| `scanForPeers` | — | `true` | ✅ |
| `pairWithPeer` | `{deviceId, peerPublicKey}` | `true` | ✅ |
| `sendMessage` | `{targetDeviceId, message}` | `{status, messageId}` | ✅ |
| `getPeers` | — | `[{deviceId, displayName, rssi, hop, authorized}]` | ✅ |
| `clearPeer` | `deviceId` | `true` | ✅ |

**Event channel: `meshnet/events`**

| Event | Payload | Status |
|-------|---------|-------|
| `peerDiscovered` | `{deviceId, displayName, rssi, transport}` | ⚠️ not emitted yet |
| `peerUpdated` | `{deviceId, rssi, hopCost}` | ⚠️ not yet |
| `peerLost` | `{deviceId}` | ⚠️ not emitted yet |
| `messageReceived` | `{fromDeviceId, message, messageId}` | ✅ |
| `deliveryStatus` | `{messageId, status}` | ✅ |
| `engineState` | `{state}` | ✅ |

### 14.2 Dart side (current + proposal)

- `lib/core/mesh_service.dart` — MeshService wrapper ✅
- `lib/core/providers.dart` — peersProvider, incomingMessagesProvider ✅
- **Proposal:** `MessageRepository` / `ChatController` (Riverpod `Notifier`) —
  manages chat history, delivery status, and retry UI state. Currently, chat
  history lives only in widget state (`_messages` List) — it is lost when the
  app closes.

### 14.3 Contract rules

1. All `Map<String, dynamic>` use primitive types (String/num/bool). No extra
   class serialization (the binary frame exists only in Kotlin).
2. Events are broadcast; on the Flutter side they are observed through
   `StreamProvider`.
3. On errors, `result.error("code", "msg", null)`.

---

## 15. N. Android BLE/Wi-Fi Strategy

### 15.1 BLE

| Component | Strategy | Battery note |
|-----------|------------|----------------|
| Advertise | `LOW_LATENCY` active / `LOW_POWER` background | adaptive interval (100ms→1s) |
| Scan | `LOW_POWER` background, `LOW_LATENCY` on-demand | scan batch (reportDelay) |
| Connection | Lazy, LRU pool, max ~7 | 1 connection ≈ 5-10mA |
| MTU | `requestMtu(512)` request; chunk 244 | — |
| Packet batching | small messages in a single write | — |
| Sleep/wake | minimal wake lock, `acquire` at send time | — |

### 15.2 Wi-Fi Direct

- Discovery is not continuous — on an interval.
- Group creation/connection only when needed for a message.
- TCP socket keep-alive 30s.

### 15.3 Android background restrictions (important!)

- Android 8+: background location/scan restricted. **Foreground service**
  required — `FOREGROUND_SERVICE_CONNECTED_DEVICE` ✅ in the manifest.
- Android 12+: `BLUETOOTH_SCAN`, `BLUETOOTH_ADVERTISE`, `BLUETOOTH_CONNECT`
  runtime permissions ✅ (permission_handler + `neverForLocation`).
- Android 13+: `POST_NOTIFICATIONS` runtime ✅.
- Doze / App Standby: BLE scan/advertise may be stopped. Foreground service +
  `batteryOptimization` exception (in PHASE 9).
- **Background BLE GATT** — restricted on Android 10+: `startScan` does not work
  in the background. Therefore the mesh runs only inside the foreground service
  and while the app is partially visible.

### 15.4 "bg/BG reliability" position on real devices

| Function | Foreground | Background | Notes |
|-----------|-----------|-----------|------|
| BLE advertise | ✅ reliable | ⚠️ OS may stop it | service+optimization |
| BLE scan | ✅ | ⚠️ banned in background on Android 10+ | via service |
| Wi-Fi Direct | ✅ | ⚠️ problems in doze | — |
| GATT connection | ✅ | ⚠️ restricted | — |

**Position:** the MVP operates in "app open + foreground service" mode. Full
background mesh is an Android platform constraint; we do not hide it. In PHASE 9
a battery optimization exception will be requested, but full background
operation depends on platform policy.

---

## 16. O. Embedded Hardware Roadmap

### 16.1 Candidate comparison

| Criterion | ESP32-C3 | nRF52840 | RP2040 + radio | ESP32-S3 |
|-------|----------|----------|----------------|----------|
| Price | $3-4 | $8-10 | $1 + $3 (radio) | $4-6 |
| BLE 5 | ✅ (long range?) | ✅ (2Mbit, Coded) | ❌ (external) | ❌ (Wi-Fi+BLE?) |
| Wi-Fi | ❌ | ❌ | ❌ | ✅ |
| RAM | 400KB | 256KB | 264KB | 512KB |
| Flash | 4MB | 1MB (ext) | 2MB | 8MB |
| Power (sleep) | ~5µA | ~1µA | ~2µA | ~7µA |
| Development ease | ✅ Arduino/ESP-IDF | average (nRF SDK) | ✅ Arduino | ✅ |
| Availability | ✅ | average | ✅ | ✅ |

### 16.2 Decision

- **Prototype relay node:** **ESP32-C3** — cheap, BLE 5, easy Arduino/ESP-IDF,
  easily obtained online. For PHASE 7.
- **Field (battery) node:** **nRF52840** — the best BLE 5 (Coded PHY — up to
  1km range), the lowest power. With an SX1262 when a LoRa backbone is added.
- **RP2040** — requires a separate radio, not a good fit for the mesh (extra work
  to integrate the radio). Discarded.

### 16.3 BLE ↔ node ↔ BLE architecture

```
Phone A ──BLE──▶ MeshNode (ESP32-C3) ──LoRa/BLE──▶ MeshNode ──BLE──▶ Phone B
```

- The node as GATT server: phones can connect.
- Node → node: BLE (Coded PHY long range) or LoRa (SX1262) — PHASE 8.
- The node has no UI — LED status + power management.

### 16.5 PHASE 7 implementation (2026)

- **Stack**: Arduino-ESP32 **3.3.11**, `esp32:esp32:esp32c3`. The ESP32-C3 build
  uses **NimBLE** (`CONFIG_BT_NIMBLE_ENABLED`) — MTU auto 256,
  MAX_CONNECTIONS=3.
- **Source**: `firmware/mesh_node/` — `mesh_frame.h/c` (wire parse/encode,
  portable C, host tests), `relay.h/c` (dedup+TTL, 64 entries, 60s TTL),
  `mesh_node.ino` (GATT server RX/WRITE + adv MFG + GATT client scan/connect).
- **Host tests**: `firmware/mesh_node/test/` — `make run` → 7/7 PASS.
- **Build**: `arduino-cli compile --fqbn esp32:esp32:esp32c3 firmware/mesh_node`.
- **Remaining**: flash on a real device + a connection test with a phone (no
  hardware available).

### 16.4 Battery (embedded)

- Deep sleep (5µA), wake-on-event.
- Duty cycle: advertise 1s / listen 1s.
- Battery monitoring (ADC).

---

## 17. P. Repository Structure

### 17.1 Proposal (applied in PHASE 5+)

```
meshnet/
├── mobile/
│   └── flutter_app/          # Flutter UI (currently: meshnet_app/lib)
├── android/
│   └── mesh_network/         # Kotlin mesh engine (currently: meshnet_app/android)
├── protocol/
│   ├── packet/               # frame specification + codec tests
│   ├── routing/
│   ├── crypto/
│   └── serialization/
├── firmware/
│   └── mesh_node/            # ESP32-C3 (PHASE 7)
├── docs/
│   ├── architecture/         # this document
│   ├── protocol/
│   ├── security/
│   └── testing/
├── tools/                    # test/benchmark scripts
└── README.md
```

### 17.2 Current state and decision

Current repo: `meshnet_app/` — Flutter and Android together, with a `docs/`.

**Decision:** during the MVP (PHASE 1-4) the current structure is **kept** —
restructuring wastes time and breaks git history for low benefit. When PHASE 5
begins (when store-and-forward and test infrastructure are needed) we move to a
monorepo. This decision reduces "refactor churn" risk.

**Why not now:** the MVP is 5 people and 5 files; the cost of the migration (all
import paths, CI, git) adds no value to the MVP.

---

## 18. Q. Team Roles

| Role | Department | Responsibility |
|-----|--------|--------|
| Managing Director (MD) | — | Decisions, priorities, risk oversight |
| Flutter developer | Frontend | UI screens, Riverpod state, chat UX |
| Kotlin mesh engineer | Backend | Identity, crypto, routing, transport, store-forward |
| DevOps | DevOps | Build (Gradle/CI), device deploy, benchmark automation |
| QA | QA | Test plan, unit/integration tests, real-device tests |
| Security | Security | Threat model, crypto audit, pen-test |
| Designer | Design | Emergency UX, dark theme, status indicators |

**Small team (students):** 1 person covers 1-2 roles. The two most important
roles are the Kotlin mesh engineer and the Flutter developer. QA and Security
are temporary (PHASE 2+).

---

## 19. R. Development Roadmap

| Phase | Content | Time (estimate) | Exit criteria |
|------|--------|--------------|-------------------|
| 0 | Architecture (this document) | 1 week | Approved architecture |
| 1 | **Single-hop P2P** (A↔B) | 1-2 weeks | Text chat, ACK, discovery over BLE+Wi-Fi |
| 2 | **Multi-hop** (A→B→C→D) | 2 weeks | 2-hop relay, TTL, duplicates |
| 3 | **Secure messaging** | 1-2 weeks | Pairing, E2E, delivery status |
| 4 | **Dynamic routing** | 2 weeks | Route recovery, FIND_PEER, rank-based |
| 5 | **Store & forward** ✅ | 2 weeks | Offline receipt, flush, expiry |
| 6 | **Fault tolerance** ✅ | 1-2 weeks | Heartbeat, failover, key rotation* |

> ✅ = code written (42 JVM tests, `flutter analyze` clean, debug APK builds).
> *PHASE 6: heartbeat + failover completed; **key rotation moved to PHASE 9**
> (user decision).
> **PHASE 7 (ESP32-C3 node):** `firmware/mesh_node/` written — `mesh_frame.c/h`
> + `relay.c/h` (7/7 host tests PASS) and `mesh_node.ino` (BLE server+client,
> NimBLE). `arduino-cli compile --fqbn esp32:esp32:esp32c3` passes. A real-device
> test remains (no hardware available).
| 7 | **Embedded nodes** ⏳ | 4-6 weeks | ESP32-C3 relay node |
| 8 | **Long-range** | 3-4 weeks | LoRa backbone, nRF52840 |
| 9 | **Hardening** | ongoing | Keystore, pen-test, battery opt., CI |

**Rule:** each phase only closes after "tests on real devices".

---

## 20. S. Risk Analysis

| Risk | Impact | Likelihood | Mitigation | Residual |
|------|--------|---------|------------|--------|
| BLE reliability (connection limits, drops) | High | High | Lazy connection, LRU pool, Wi-Fi Direct primary | Medium |
| Logistics of 5 real devices | High | High | Early purchase, emulator + real mixed | Medium |
| Wi-Fi Direct group limit (5-8) | Medium | Medium | A mesh that falls back to BLE | Low |
| ChaCha20 API 26-27 issue | Medium | Medium | BouncyCastle provider | Low |
| Private key in SharedPreferences | High | Low | PHASE 9: Keystore | Low (MVP) |
| Routing explosion (100+ nodes) | Medium | Low | Rank-based routing PHASE 4 | Medium |
| Battery drain | Medium | Medium | Adaptive interval, lazy | Medium |
| Spoofed/fake devices | Medium | Medium | QR identity verification | Medium |

### Threat model (full)

| Threat | Description | Impact | Likelihood | Mitigation | Residual |
|--------|--------|--------|---------|------------|----------|
| Eavesdropping | Intercepting and reading a message | High | Medium | E2E ChaCha20-Poly1305 | Low |
| MITM | Insertion between A and B | High | Low | QR out-of-band verification | Low |
| Replay | Replaying an old message | Medium | Low | nonce + seen-cache | Low |
| Message injection | Sending a fake message | Medium | Medium | authorized peer required | Medium |
| Impersonation | Taking over another ID | High | Low | identity=key, QR | Low |
| Sybil | Many fake nodes | Medium | Medium | Only trust QR-paired nodes | Medium |
| Flooding (DoS) | Message explosion | Medium | Medium | seen-cache, rate limit (P9) | Medium |
| Route poisoning | Corrupting the route table | Medium | Low | No table in flooding | Low |
| Fake ACK | False "delivered" claims | Low | Low | ACK from final recipient; P6 signatures | Medium |
| Malicious relay | Relay drops a message | Medium | Low | E2E protection; a dropped message means no delivery report | Medium |
| Compromised device | Key theft | High | Low | Keystore, local storage | Medium |
| Spam | Unwanted messages | Low | Medium | authorized-only | Low |
| Traffic analysis | Who writes to whom, when | Medium | Medium | **open in the MVP**; P8 padding | High |

---

## 21. T. Testing Strategy

### 21.1 Levels

| Level | Location | Coverage | Tool |
|--------|-----|--------|--------|
| Unit | JVM (Kotlin) | MeshFrame roundtrip, MeshCrypto, RoutingEngine, dedup, TTL | JUnit 4/5 |
| Unit | Dart | MeshService contract, providers | flutter_test |
| Integration | JVM | RoutingEngine + fake transports (loopback) | JUnit |
| Integration | Device | 2 transports, 2-5 devices | manual + script |
| E2E | 5 real phones | tests 1-10 | manual QA |

### 21.2 Minimal physical tests (from the spec)

| # | Test | Expected |
|---|------|----------|
| 1 | A↔B | discovery, chat, ACK |
| 2 | A→B→C | C receives the message, B does not see it (E2E) |
| 3 | A→B→C→D | 3-hop works (MVP: 2-hop, D indirect) |
| 4 | Remove B | A→E→C→D finds a new path |
| 5 | D offline → online | store-and-forward |
| 6 | Send a duplicate | received once |
| 7 | Corrupted packet | safe drop, no crash |
| 8 | Fake node | rejected if not authorized |
| 9 | Battery stress | consumption measurement |
| 10 | Many nodes | scalability measurement |

### 21.3 Benchmarks

Discovery latency · connection latency · message latency · delivery success ·
packet loss · battery · CPU/RAM · throughput · max nodes · max hops · recovery
time. Reproducible scripts in `tools/bench/` (we start logging from PHASE 1).

---

## 22. U. Scalability Analysis

| Stage | Nodes | Capability | Constraint |
|---------|----------|-----------|---------|
| 1 | 2-5 | MVP | — |
| 2 | 10-20 | Flooding is viable | flooding overhead grows |
| 3 | 30-100 | Rank-based routing (PHASE 4) | BLE connection limits |
| 4 | 100-1000 | LoRa backbone + hierarchy | complex, bandwidth |

**Explicit constraints (not hidden):**
- **BLE:** one device ~7-9 GATT clients — mitigated by "mesh" relay, but each
  device will not instantly see 100 nodes.
- **Wi-Fi Direct:** group of 5-8 devices (Android standard).
- **Flooding:** duplicate explosion beyond 20 nodes — hence rank-based
  (BATMAN-like) routing in PHASE 4.
- **Bandwidth:** BLE ~0.2-2Mbps, Wi-Fi Direct ~20-50Mbps, LoRa ~0.3-10kbps.
  LoRa is for TEXT/SOS/metadata only.

---

## 23. V. Technology Comparison

### 23.1 Transport

| | BLE | Wi-Fi Direct | LoRa |
|--|-----|--------------|------|
| Range | 10-50m | 50-100m | 1-15km (LOS) |
| Bandwidth | 0.2-2Mbps | 20-50Mbps | 0.3-10kbps |
| Battery | low | medium | very low |
| Android support | ✅ | ✅ (not on all devices) | ❌ (via serial) |
| Group size | ~7 conn | 5-8 | unlimited |
| **Role** | presence + fallback | primary message path | long-range backbone (P8) |

### 23.2 Serialization — see §5.1. **Decision: binary.**

### 23.3 Crypto — see §10.1. **Decision: X25519 + ChaCha20-Poly1305 (+Ed25519 P4).**

### 23.4 Routing — see §9. **Decision: MVP flooding → PHASE 4 rank-based.**

### 23.5 Storage (Android)

| | SharedPreferences | Room/SQLite | DataStore |
|--|-------------------|-------------|-----------|
| Simplicity | ✅ | medium | ✅ |
| Large data | ❌ | ✅ | medium |
| Type-safety | ❌ | ✅ | ✅ |
| **Decision** | **MVP** (currently used) | PHASE 5 (message history) | — |

### 23.6 State management (Flutter)

| | Riverpod | Bloc | setState |
|--|----------|------|----------|
| Testability | ✅ | ✅ | ❌ |
| Complexity | medium | high | low |
| This project | **✅ selected** (already) | — | small parts |

### 23.7 Async (Kotlin)

| | Coroutines | RxJava | Threads |
|--|------------|--------|---------|
| Simplicity | ✅ | medium | low |
| Cancellation | ✅ | ✅ | ❌ |
| **Decision** | **✅ Coroutines** (already a dependency) | — | only socket I/O |

---

## 24. Platform Constraints

Android constraints identified so far (to be experimentally verified in PHASE 1):

1. **BLE GATT concurrent connection limit (~7-9)** → how many nodes can connect
   "directly". **Mitigation:** lazy + LRU pool + relay.
2. **Background BLE scan ban on Android 10+** → the mesh only works fully inside
   a foreground service. **Full background mesh is restricted by platform policy.**
3. **Advertisement data limit (31B legacy / 165B extended)** → the device name +
   service UUID must fit. Not all data is sent in the advertisement.
4. **ChaCha20-Poly1305 JCE on API 28+** → via the BouncyCastle provider on all
   APIs.
5. **Wi-Fi Direct is not on every device** (effective support is
   device-dependent) → BLE must work independently.
6. **Battery optimization on foreground services** → user consent, PHASE 9.

---

## 25. Comparison with Existing Code and Gaps

The built code is a good foundation, but points to fix before starting PHASE 1:

### Critical (build bug / does not work)

| # | Problem | Location | Solution |
|---|--------|-----|--------|
| 1 | `gatt.connectedCharacteristic` — no such property on `BluetoothGatt` → **compile error** | `BleTransport.kt:189` | use `serverTxChar` (declared inside BleTransport) or store the GATT client characteristic |
| 2 | the `peers` map is never populated → `sendFrame` always returns `false` | `BleTransport.kt` | write GATT client connection logic (discoverServices → connect) |
| 3 | Discovery → no PeerStore upsert → peer not visible in the UI | `TransportManager.kt:35-45` | `onPeerDiscovered` → `peerStore.upsert()` + emit event |

### Important (works but is weak)

| # | Problem | Location | Solution |
|---|--------|-----|--------|
| 4 | `seenMessages` grows without bound (no expiry) — memory leak | `RoutingEngine.kt:40` | LRU + 60s expiry |
| 5 | `ttl` is not decremented on relay (only hopLimit) | `RoutingEngine.kt:121-142` | `ttl = frame.ttl - 1`; `ttl<=0` → drop |
| 6 | Chat history only in widget state | `chat_view.dart:24` | `MessageRepository` (Riverpod Notifier) + persistence |
| 7 | Private key in SharedPreferences | `IdentityStore.kt:43` | PHASE 9: Keystore (documented for MVP) |
| 8 | ChaCha20 JCE missing on API 26-27 | `MeshCrypto.kt:68` | BouncyCastle provider |
| 9 | `onDestroy` inside `MainActivity` → `meshEngine.stop()` — the service is separate and both do not use the same engine (the service is currently empty) | `MeshService.kt` | consolidate the engine lifecycle in one place (the service should own the engine) |

### Structural

- The transport-selection code in `handleMethodCall` (`transportKey`,
  `transportName`) is confusing — it should move inside `TransportManager`.
- No tests (Kotlin JVM + Dart) — the first tests come in PHASE 1.

---

## 26. Open Decisions for Review

| # | Decision | Options | Recommendation |
|---|-------|------------|---------|
| 1 | Switch to Node ID = hash(pubkey) | yes / no (UUID in the MVP) | MVP: UUID; migrate in P6 |
| 2 | Keep minSdk 26 + BouncyCastle / minSdk 28 | 26 / 28 | 26 + BouncyCastle |
| 3 | Routing: flooding (MVP) / rank-based immediately | flooding / rank | flooding |
| 4 | Include store-and-forward in the MVP | include / in phase 5 | PHASE 5 |
| 5 | Monorepo restructure | now / P5 | P5 |
| 6 | Consolidate the service engine lifecycle | merge / later | PHASE 1 |

---

## Conclusion

The stack chosen for the MeshNet MVP:

- **Transport:** BLE (presence/fallback) + Wi-Fi Direct (primary), mixed.
- **Protocol:** binary strict frame (43B header + payload_len).
- **Routing:** 2-hop controlled flooding (MVP) → rank-based (PHASE 4).
- **Crypto:** X25519 (key exchange) + ChaCha20-Poly1305 (E2E AEAD).
- **Identity:** 128-bit UUID + X25519 static keys, QR pairing.
- **Store-and-forward + ACK/retry:** full in PHASE 5; delivery status in the MVP.
- **App:** Flutter (Riverpod) + Kotlin (Coroutines) + Platform Channel.

**Next step (PHASE 1):** once the architecture is approved —
1) critical fixes (§25), 2) A↔B testing on real devices.

---

© 2026 MeshNet / TechCorp. Confidentially.