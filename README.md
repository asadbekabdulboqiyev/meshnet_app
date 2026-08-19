# MeshNet

> **Offline P2P mesh networking for Android -- no internet, no server, no SIM required.**

MeshNet turns Android devices into mesh network nodes. Messages hop through intermediate devices via BLE and Wi-Fi Direct, encrypted end-to-end. When cell towers and WiFi go down, this still works.

**[Download APK](https://github.com/asadbekabdulboqiyev/meshnet_app/releases)** • **[Architecture](ARCHITECTURE.md)** • **AGPL-3.0**

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

## Features

- **Offline mesh networking** -- BLE + Wi-Fi Direct dual transport
- **Multi-hop relay** -- messages hop through peers (up to 4 hops)
- **E2E encryption** -- ChaCha20-Poly1305 + X25519 key exchange + Double Ratchet forward secrecy
- **QR code pairing** -- secure out-of-band device pairing
- **1:1 and group chat** -- encrypted text messaging
- **File transfer** -- send any file type over mesh
- **Voice messages** -- Opus/AAC encoded audio
- **Read receipts** -- blue double-check delivery confirmation
- **Store-and-forward** -- messages queued for offline peers, auto-retried
- **Network topology** -- real-time visualization of connected peers and routes

## Architecture

```
Flutter UI  <-->  MeshService (MethodChannel)  <-->  MeshEngine  <-->  RoutingEngine
                                                                          |
                                                                  TransportManager
                                                                    /          \
                                                               BleTransport  WifiDirectTransport
```

| Layer | Tech |
|-------|------|
| Frontend | Flutter 3.x + Dart, Material 3, Riverpod |
| Core | Kotlin native via MethodChannel/EventChannel |
| Crypto | ChaCha20-Poly1305, X25519 ECDH, HKDF, Double Ratchet |
| Transport | BLE GATT (server/client) + Wi-Fi Direct P2P sockets |
| Storage | SharedPreferences (peer state, messages, outbox) |
| Message types | 21 types: text, file, voice, group, pairing, routing, heartbeats, delivery, read receipts, key management |

## Testing

**995 total unit tests** -- all passing.

| Suite | Count | Coverage |
|-------|-------|----------|
| Kotlin | 753 | Crypto, protocol, storage, routing, edge cases, stress tests |
| Flutter | 242 | Models, services, widgets |

```bash
cd android && ./gradlew test     # Kotlin tests
flutter test                      # Flutter tests
```

## Building

```bash
git clone https://github.com/asadbekabdulboqiyev/meshnet_app.git
cd meshnet_app
flutter pub get
flutter build apk --release --no-tree-shake-icons
```

**Requirements:** Android 12+ (API 31), two or more Android devices, BLE + Wi-Fi Direct hardware.

## License

GNU Affero General Public License v3.0 -- see [LICENSE](LICENSE).

---

## What's Next: LocalNet

MeshNet is the foundation. **LocalNet** is the next evolution -- a full offline local area networking platform:

- **Decentralized DNS** -- device discovery without central server
- **Offline Web Server** -- each device hosts a lightweight HTTP server
- **Distributed File System** -- P2P Dropbox that needs no cloud
- **Real-Time Collaboration** -- shared whiteboards, collaborative editing
- **Offline App Store** -- local APK repository
- **Emergency Broadcast** -- priority alerts that flood the mesh instantly
- **Mesh VPN** -- route internet through whichever peer has connectivity

| Phase | Milestone | Description |
|-------|-----------|-------------|
| 1 | LocalNet Core | Device registry, HTTP server, mesh-wide DNS |
| 2 | File Sharing | Chunking, dedup, incremental sync |
| 3 | Collaboration | Whiteboard, collaborative editor, polls |
| 4 | App Distribution | Local APK repository, file browser |
| 5 | Mesh VPN | Internet sharing via tethering peers |
| 6 | Community | RBAC, emergency broadcasts, mesh-wide search |
