# MeshNet

Offline peer-to-peer mesh networking app for Android. No internet required -- devices communicate directly via Bluetooth Low Energy (BLE) and Wi-Fi Direct with multi-hop relay support.

## Features

- **Offline mesh networking** -- no server, no internet, no SIM required
- **End-to-end encryption** -- ChaCha20-Poly1305 with X25519 key exchange
- **Multi-hop relay** -- messages hop through intermediate devices (up to 4 hops)
- **QR code pairing** -- secure out-of-band device pairing via QR scan
- **Text messaging** -- encrypted 1:1 and group chat
- **File transfer** -- send images, documents, and any file type
- **Voice messages** -- record and send audio with Opus/AAC encoding
- **Read receipts** -- blue double-check when messages are read
- **Unread badges** -- per-contact unread message counts
- **Store-and-forward** -- messages queued for offline peers, retried automatically
- **Network topology** -- visualize connected peers, routes, and link quality
- **Dual transport** -- BLE + Wi-Fi Direct for maximum range and throughput
- **Forward secrecy** -- Double Ratchet protocol with periodic key rotation

## Architecture

```
Flutter UI  <-->  MeshService (MethodChannel)  <-->  MeshEngine  <-->  RoutingEngine
                                                                          |
                                                                  TransportManager
                                                                    /          \
                                                               BleTransport  WifiDirectTransport
```

- **Frontend**: Flutter 3.x + Dart, Material 3, Riverpod state management
- **Core**: Kotlin native via Flutter MethodChannel/EventChannel
- **Crypto**: ChaCha20-Poly1305, X25519 ECDH, HKDF, Double Ratchet
- **Transport**: BLE GATT (server/client) + Wi-Fi Direct P2P sockets
- **Storage**: SharedPreferences (peer state, messages, outbox)

See [ARCHITECTURE.md](ARCHITECTURE.md) for full technical details.

## Message Types

21 message types covering text, file transfer, voice, group messaging, pairing, routing, heartbeats, delivery reports, read receipts, and Double Ratchet key management.

## Testing

- **765 Kotlin unit tests** -- crypto, protocol, storage, routing, edge cases, stress tests
- **242 Flutter unit tests** -- models, services, widgets

```bash
# Run Kotlin tests
cd android && ./gradlew test

# Run Flutter tests
flutter test

# Build debug APK
flutter build apk --debug
```

## Requirements

- Android 12+ (API 31)
- Two or more Android devices for mesh communication
- BLE and Wi-Fi Direct hardware support

## Building

```bash
# Clone
git clone https://github.com/asadbek/meshnet_app.git
cd meshnet_app

# Install dependencies
flutter pub get

# Build
flutter build apk --debug
```

## License

This project is licensed under the **GNU Affero General Public License v3.0** -- see the [LICENSE](LICENSE) file for details.

## Next Move: LocalNet

**LocalNet** is the next evolution of MeshNet -- a full offline local area networking platform.

### Vision

LocalNet transforms MeshNet from a simple messaging tool into a complete offline-first communication and collaboration platform. The goal is to create a self-contained local network that functions like the internet, but without any internet connection.

### Planned Features

1. **Decentralized DNS** -- automatic device discovery and name resolution without any central server. Devices register and find each other via mDNS-like mesh broadcasts.

2. **Offline Web Server** -- each device hosts a lightweight HTTP server accessible only within the mesh. Users can share web pages, documentation, wikis, and forms that any peer can access through a built-in browser.

3. **Distributed File System** -- shared folders that automatically sync across all mesh peers. Files are chunked, deduplicated, and encrypted. Works like a peer-to-peer Dropbox that needs no cloud.

4. **Real-Time Collaboration** -- shared whiteboards, collaborative text editing (like Google Docs), and polling/voting -- all running purely over the mesh network.

5. **Offline App Store** -- a local repository of APKs and files that peers can browse and download from each other. No Play Store needed.

6. **Emergency Broadcast System** -- priority alert messages that flood the entire mesh instantly, bypassing normal queue. Designed for disaster scenarios where infrastructure is down.

7. **Mesh VPN** -- route internet-bound traffic through whichever peer has an active connection (tethering sharing). Creates a shared internet pool from individual cellular connections.

8. **Role-Based Access** -- admin, moderator, and guest roles with per-feature permissions. Critical for community deployments.

9. **Mesh-wide Search** -- search across all connected peers' shared files and messages from a single search bar.

10. **Firmware OTA Updates** -- push firmware updates to IoT devices connected to the mesh network.

### Technical Roadmap

| Phase | Milestone | Description |
|-------|-----------|-------------|
| **Phase 1** | LocalNet Core | Decentralized device registry, HTTP server on each node, mesh-wide DNS |
| **Phase 2** | File Sharing | Distributed file system with chunking, dedup, and incremental sync |
| **Phase 3** | Collaboration | Shared whiteboard, collaborative editor, polls |
| **Phase 4** | App Distribution | Local APK repository, mesh-wide file browser |
| **Phase 5** | Mesh VPN | Internet sharing via tethering peers, traffic routing |
| **Phase 6** | Community | Role-based access, emergency broadcasts, mesh-wide search |

### Why LocalNet

In many parts of the world, internet access is expensive, unreliable, or censored. In disaster scenarios, cellular and internet infrastructure fails first. LocalNet creates a resilient communication layer that works independently of any infrastructure -- devices are the infrastructure.

LocalNet is not just a messaging app. It is a platform for communities to build their own offline digital commons.
