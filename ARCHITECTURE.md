# MeshNet — Architecture

Offline peer-to-peer mesh networking app. No internet required — devices communicate directly via BLE and Wi-Fi Direct with multi-hop relay.

## Tech Stack

- **Frontend**: Flutter 3.x + Dart (Material 3, Riverpod state management)
- **Backend/Core**: Kotlin (Android native) via Flutter MethodChannel/EventChannel
- **Crypto**: ChaCha20-Poly1305, X25519, Double Ratchet
- **Transport**: BLE (GATT) + Wi-Fi Direct (P2P sockets)
- **Minimum SDK**: Android 12+ (API 31) for BLE permissions

## Project Structure

```
meshnet_app/
├── lib/                          # Flutter (Dart) — UI + bridge layer
│   ├── main.dart                 # App entry point
│   ├── core/
│   │   ├── mesh_service.dart     # MethodChannel bridge to Kotlin MeshEngine
│   │   ├── providers.dart        # Riverpod providers (peers, messages, nodeInfo)
│   │   ├── permissions.dart      # Runtime permission requests (BLE, Wi-Fi, location)
│   │   ├── group_service.dart    # Group chat method channel bridge
│   │   ├── file_transfer_service.dart  # File transfer bridge
│   │   └── voice_service.dart    # Voice recording bridge
│   ├── models/
│   │   ├── message_model.dart    # ChatMessage, MessageType, MessageStatus enums
│   │   └── group_model.dart      # MeshGroup, GroupMember data classes
│   ├── screens/
│   │   ├── splash_screen.dart    # Animated splash → HomeScreen
│   │   ├── home_screen.dart      # Bottom nav (Network / Chats / Pair)
│   │   ├── network_view.dart     # Peer discovery list with signal quality
│   │   ├── topology_view.dart    # Device info, neighbors, routes, stats
│   │   ├── contacts_view.dart    # Chat list with unread badges
│   │   ├── chat_view.dart        # 1:1 encrypted messaging, read receipts
│   │   ├── pairing_view.dart     # QR code display + scanner for pairing
│   │   ├── group_chat_view.dart  # Group messaging UI
│   │   ├── create_group_screen.dart  # Create group, select members
│   │   ├── network_map_view.dart # Visual network topology map
│   │   └── media_preview.dart    # Full-screen image viewer
│   ├── widgets/
│   │   ├── audio_waveform.dart   # Voice message waveform painter
│   │   ├── file_bubble.dart      # File/image message bubble
│   │   ├── voice_message_bubble.dart  # Voice message player bubble
│   │   ├── voice_recorder_button.dart # Hold-to-record button
│   │   └── network_map_painter.dart   # Canvas painter for network graph
│   └── theme/
│       └── app_theme.dart        # Dark + Light themes, color system
│
├── android/app/src/main/kotlin/com/meshnet/meshnet_app/
│   ├── MainActivity.kt           # Flutter activity, engine bootstrap
│   ├── MeshEngine.kt             # Core controller — bridges Flutter ↔ Kotlin
│   ├── MeshService.kt            # Foreground service (keeps mesh alive)
│   ├── IdentityStore.kt          # Device identity (keypair, name, deviceId)
│   ├── crypto/
│   │   ├── MeshCrypto.kt         # ChaCha20-Poly1305, X25519, HKDF, Base64
│   │   ├── DoubleRatchet.kt      # Double Ratchet protocol (forward secrecy)
│   │   └── RatchetSession.kt     # Session persistence for Double Ratchet
│   ├── protocol/
│   │   ├── MeshFrame.kt          # Wire frame format (type, hopLimit, ttl, payload)
│   │   ├── MessageType.kt        # 21 message types (TEXT, FILE_*, VOICE, GROUP_*, etc.)
│   │   ├── RoutingEngine.kt      # 2-hop flood relay, routing table, store-and-forward
│   │   ├── FileTransfer.kt       # Chunked file transfer with progress tracking
│   │   ├── GroupStore.kt         # Group management, symmetric key distribution
│   │   ├── VoiceRecorder.kt      # PCM audio recording
│   │   ├── VoiceEncoder.kt       # PCM → Opus/AAC encoding
│   │   └── MediaCompressor.kt    # Image/video compression
│   ├── transport/
│   │   ├── BleTransport.kt       # BLE GATT server/client, advertising, scanning
│   │   ├── WifiDirectTransport.kt # Wi-Fi P2P sockets, group owner
│   │   └── TransportManager.kt   # Multi-transport orchestration, peer management
│   └── storage/
│       ├── PeerStore.kt          # Known peers (in-memory cache + SharedPreferences)
│       └── MessageStore.kt       # Incoming/outgoing messages (SharedPreferences)
│
├── android/app/src/test/kotlin/  # 765 Kotlin unit tests (29 files)
└── test/                         # 242 Flutter unit tests
```

## Core Architecture

### Communication Flow

```
Flutter UI  ←→  MeshService (MethodChannel)  ←→  MeshEngine  ←→  RoutingEngine
                                                                    ↕
                                                            TransportManager
                                                              ↕          ↕
                                                         BleTransport  WifiDirectTransport
```

1. **Flutter** calls `MeshService` methods (sendMessage, getPeers, etc.)
2. **MeshService** forwards via `MethodChannel("meshnet/engine")`
3. **MeshEngine** delegates to `RoutingEngine` for protocol logic
4. **RoutingEngine** encrypts, sequences, and passes frames to **TransportManager**
5. **TransportManager** sends via BLE GATT or Wi-Fi Direct sockets
6. Incoming frames flow back up: Transport → Routing → MeshEngine → EventChannel → Flutter

### Encryption Model

All message payloads are encrypted end-to-end using ChaCha20-Poly1305:
- **Shared secret**: X25519 ECDH between sender's private key and recipient's public key
- **AAD (Additional Authenticated Data)**: `"MeshNet:<targetId>"` — binds ciphertext to routing context
- **Key exchange**: QR code pairing (out-of-band trust)
- **Forward secrecy**: Double Ratchet protocol with periodic key rotation (100 messages or 1 hour)

### Routing (2-Hop Flood Relay)

```
A ──── B ──── C
```
- A sends to C via B (relay node)
- B forwards if hopLimit > 0
- Delivery reports travel back: C → B → A
- **Route table**: Passive AODV-like learning (routes auto-learned from incoming frames)
- **Route quality**: Scored by success/fail counts + RSSI (0-100)
- **Max hops**: 4
- **Route TTL**: 90 seconds

### Message Lifecycle

1. **Send**: encrypt → assign seq → add to outbox → flood via transport
2. **Relay**: receive frame → check duplicate (seen cache) → relay if hopLimit > 0
3. **Deliver**: decrypt → store in MessageStore → emit to Flutter via EventChannel
4. **Ack**: delivery report sent back to sender → removed from outbox
5. **Retry**: store-and-forward queue retries every 15-20s (with jitter)
6. **Expire**: messages older than 24 hours are removed

### Presence System

- **Heartbeat**: PEER_PING broadcast every 15s (+ random jitter for battery)
- **Timeout**: 45s without response → peer marked offline
- **Sweep**: periodic presence check removes stale peers

### Message Types (21 total)

| Type | Code | Purpose |
|------|------|---------|
| TEXT | 0x01 | Encrypted chat message |
| FILE_START | 0x02 | File transfer begin |
| FILE_CHUNK | 0x03 | File data chunk |
| FILE_END | 0x04 | File transfer complete |
| RELAY | 0x05 | Relay wrapper |
| DELIVERY_REPORT | 0x06 | Delivery confirmation |
| PAIR_REQ | 0x07 | Pairing request |
| PAIR_ACK | 0x08 | Pairing acknowledgment |
| FIND_PEER | 0x09 | Peer discovery request |
| FIND_PEER_ACK | 0x0A | Peer discovery response |
| PEER_PING | 0x0B | Heartbeat/presence |
| VOICE_MSG | 0x0C | Voice message |
| GROUP_MSG | 0x0D | Group chat message |
| GROUP_CREATE | 0x0E | Create group |
| GROUP_ADD_MEMBER | 0x0F | Add group member |
| GROUP_REMOVE_MEMBER | 0x10 | Remove group member |
| GROUP_LEAVE | 0x11 | Leave group |
| GROUP_KEY_DIST | 0x12 | Group key distribution |
| RATCHET_INIT | 0x30 | Double Ratchet session init |
| RATCHET_MSG | 0x31 | Double Ratchet encrypted message |
| READ_RECEIPT | 0x50 | Read receipt |

### Transport Layer

**BLE Transport**:
- GATT Server (listens) + GATT Client (connects)
- Advertising with manufacturer data (deviceId + displayName)
- Scanning with manufacturer data filter
- MTU negotiation (requests 512)
- Multi-chunk write for large payloads (>244 bytes)
- Max simultaneous connections: configurable

**Wi-Fi Direct Transport**:
- Wi-Fi P2P group formation (Group Owner / Client)
- TCP socket connections for data transfer
- Auto-reconnect on failure

**Transport Manager**:
- Orchestrates BLE + Wi-Fi Direct
- Peer discovery aggregation
- Frame flooding (sends to all transports)
- Link quality estimation per peer

### Unread Message System

- **Mark as read**: When user opens a chat, `markMessagesRead` is called
- **Read receipt**: `READ_RECEIPT` frame sent back with message IDs
- **Unread count**: Tracked per-device in MessageStore, surfaced in contacts list
- **UI indicators**: Red badge on contact tiles, blue double-check for read status

## Testing

- **765 Kotlin unit tests** across 29 test files (crypto, protocol, storage, transport)
- **242 Flutter unit tests** (models, services)
- Test coverage: crypto operations, frame encoding/decoding, routing logic, message store, group operations, double ratchet, file transfer, edge cases, stress tests

## Build

```bash
# Debug APK
flutter build apk --debug

# Run all Kotlin tests
cd android && ./gradlew test

# Run all Flutter tests
flutter test
```
