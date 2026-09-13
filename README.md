# MeshNet

> **Offline P2P mesh networking for Android -- no internet, no server, no SIM required.**

MeshNet turns Android devices into mesh network nodes. Messages hop through intermediate devices via BLE and Wi-Fi Direct, encrypted end-to-end. When cell towers and WiFi go down, this still works.

**[Download APK](https://github.com/asadbekabdulboqiyev/meshnet_app/releases)** • **[Architecture](ARCHITECTURE.md)** • **AGPL-3.0** • **[TEP (Teno Event Protocol)](#tep-events)**

---

## Screenshots

<p align="center">
  <img src="screenshots/01-chats.png" width="180" alt="Chat list">
  &nbsp;&nbsp;
  <img src="screenshots/02-chat.png" width="180" alt="Chat view">
  &nbsp;&nbsp;
  <img src="screenshots/03-network.png" width="180" alt="Network topology">
  &nbsp;&nbsp;
  <img src="screenshots/04-pairing.png" width="180" alt="QR pairing">
  &nbsp;&nbsp;
  <img src="screenshots/05-group.png" width="180" alt="Group chat">
</p>

## Why

Internet is expensive, unreliable, or censored in many parts of the world. During natural disasters, cellular infrastructure fails first. MeshNet creates a communication layer where **devices are the infrastructure** -- no cell towers, no WiFi routers, no cloud servers needed.

## Security Model

MeshNet employs a **hybrid security architecture** designed for offline resilience while providing optional cryptographic verification when online:

### HTTP Endpoint Authentication
- All LocalHttpServer endpoints require `HTTP_SERVER` permission
- Peers must have this role granted via the mesh RBAC wire protocol (ROLE_GRANT 0x77)
- Unauthenticated connections receive 401 Unauthorized responses
- Default allows loopback/127.0.0.1 for testing purposes
- Production deployments should grant `HTTP_SERVER` only to trusted devices

### Emergency Broadcast Encryption
- All SOS messages are signed with ECDSA P-256 signatures
- Fake SOS messages cannot be forged without the signing key
- Every signature-verified alert requires an active acknowledgment
- Invalid signatures are rejected and never rebroadcast

### Backward Compatibility
- Android 7.0 (API 24) — full feature set
- Android 5.0–6.0 (API 21–23) — reduced feature set
- Older devices are warned that the app cannot be installed

### Fake Alert Detection
- Messages containing "SOS", "HELP", "EMERGENCY", "MAYDAY", "TEST", "SIMULATION" are strictly screened
- Incoming alerts are validated and unrelated content is filtered before any response

### App Version Support
- Android 7.0 (API 24) — full feature set
- Android 5.0–6.0 (API 21–23) — reduced feature set
- Older devices are warned that the app cannot be installed

## Features

- **Offline mesh networking** -- BLE + Wi-Fi Direct dual transport
- **Multi-hop relay** -- messages hop through peers (up to 4 hops)
- **E2E encryption** -- ChaCha20-Poly1305 + X25519 key exchange + Double Ratchet forward secrecy
- **QR code pairing** -- secure out-of-band device pairing
- **1:1 and group chat** -- encrypted text messaging
- **TEP events** -- signed app-level events (`peer.joined`, `file.transferred`) via Teno Event Protocol, broadcast over mesh with idempotency

## TEP Events

MeshNet supports the **Teno Event Protocol (TEP)** for signed, idempotent app-level events broadcast over the mesh.

| Type | Description |
|------|-------------|
| `peer.joined` | A new peer has joined the mesh network |
| `message.relayed` | A multi-hop message relay event |
| `file.transferred` | A chunked file transfer completed |
| `group.updated` | Group membership or key change |

**Spec**: `spec/transport-mesh.md` · **Package**: `android/app/src/main/kotlin/.../tep/` (vendored TEP FrameCodec + Signature + TepEnvelope + TepEventManager)