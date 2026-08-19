# MeshNet — Mesh Protokoli va MethodChannel Kontrakti

Versiya: 0.1 (MVP arxitektura spetsifikatsiyasi)
Sana: 2026-08-08
Muallif: TechCorp Bosh Direktor (MD)

## 1. Hujjat maqsadi

Ushbu hujjat MeshNet ilovasining Flutter interfeysi va Kotlin mesh engine'i
o'rtasidagi bog'lanish kontraktini, xabar formatlarini va protokol qoidalarini
belgilaydi. Backend va Frontend bo'limlari aynan shu hujjatga asoslanib ishlaydi.

## 2. Platform Channel API

| maydon | qiymat |
|---|---|
| Method kanal | `meshnet/engine` |
| Event kanal | `meshnet/events` |

**Methodlar (Dart → Kotlin):**

| Metod | Parametrlar | Qaytarish |
|-------|-------------|-----------|
| `initEngine` | `displayName: String` | `true` |
| `startNode` | — | `true` (advertise+scan yoqiladi) |
| `stopNode` | — | `true` |
| `getLocalIdentity` | — | `{deviceId, publicKey, displayName}` |
| `scanForPeers` | — | `true` (qo'shimcha skan chaqiruvi) |
| `pairWithPeer` | `{deviceId, peerPublicKey}` | `true` |
| `sendMessage` | `{targetDeviceId, message}` | `{status, messageId}` |
| `getPeers` | — | `List<{deviceId, displayName, rssi, hopId, authorized}>` |
| `clearPeer` | `deviceId` | `true` |

**Eventlar (Kotlin → Dart):**

| Event | Payload |
|-------|---------|
| `peerDiscovered` | `{deviceId, displayName, rssi, transport}` |
| `peerUpdated` | `{deviceId, rssi, hopCost}` |
| `peerLost` | `{deviceId}` |
| `messageReceived` | `{fromDeviceId, message, messageId}` |
| `deliveryStatus` | `{messageId, status: sent/delivered/failed}` |
| `engineState` | `{state: starting/running/stopped/error}` |

## 3. Kotlin mesh engine paket tuzilmasi

```
com.meshnet.meshnet_app/
├── MeshEngine.kt              -- MethodChannel handler, lifecycle
├── MeshService.kt             -- Foreground service (BL ВУМ yo'late public)
├── IdentityStore.kt           -- deviceId, X25519 kartal, displayName
├── crypto/
│   ├── MeshCrypto.kt          -- X25519 + ChaCha20-Poly1305 (libsodium-ish)
│   └── KeyPack.kt             -- serialization yordamchisi
├── transport/
│   ├── BleTransport.kt        -- BLE advertise + scan + gAtt
│   ├── BleGattServer.kt       -- GATT server (connection + notif)
│   ├── WifiDirectTransport.kt -- Wi-Fi Direct P2P yaqinlashish
│   └── TransportManager.kt    -- transportlarni muvofiqlashtirish
├── protocol/
│   ├── MeshFrame.kt           -- byte frame encoder/decoder
│   ├── MessageType.kt         -- enum (0x01..0x08)
│   └── RoutingEngine.kt       -- 2-hop flooding relay
└── storage/
    ├── PeerStore.kt           -- authorized peers, saqlash
    └── MessageStore.kt        -- xabar tarixi (local-only)
```

## 3. Xabar frame (wire format)

Ko'chirish tarmog'ida barch payload **bayt formatda**. Frame layout:

| Bayt ofset | Maydon | Izoh |
|-----------|--------|------|
| 0-1  | magic `0x4D 0x4E` | MN |
| 2    | version `0x01` | protokol versiya |
| 3    | type | 0x01..0x08 |
| 4    | hop_limit | MVP: 2 |
| 5    | ttl | xabar yashash qoldig'i (maks 6) |
| 6    | flags | bit0 payload shifrlanganmi |
| 7-22 | sender_id | 16 bayt (UUID yangi) |
| 23-38| target_id | 16 bayt (broadcast = all zero) |
| 39-46| msg_seq | 8 bayt big-endian Long (har bir tugun uchun monotonik o'suvchi) |
| 47-78| sender_pubkey | 32 bayt X25519 — faqat PAIR_REQ/PAIR_ACK/FIND_PEER_ACK |
| 79-? | payload | shifrlangan bytes (JSON)— E2E |

### MessageType

| Kod | Nom | Tavsif |
|-----|-----|--------|
| 0x01 | PEER_PING | borlik belgisi |
| 0x02 | TEXT | shifrlangan chat xabari |
| 0x03 | PAIR_REQ | juftlashish so'rovi (QR orqali) |
| 0x04 | PAIR_ACK | juftlashish qabuli |
| 0x05 | RELAY | oraliq tugunning retranslyatsiyasi |
| 0x06 | DELIVERY_REPORT | yetkazish hisoboti |
| 0x07 | FIND_PEER | tarmoqda foydalanchi qidirish (broadcast) |
| 0x08 | FIND_PEER_ACK | qidiruvga javob (topilgan tugun) |

### ESP32-C3 relay node (PHASE 7)

Telefonlar orasidagi xabarlarni uzatadigan "shaffof repeater" — telefon kabi
BLE adapter bilan ishlaydi, xabar **yaratmaydi va ochmaydi** (E2E):

- **GATT kontrakti** `BleTransport.kt` bilan bir xil: SERVICE
  `6a4e9f01-1d5b-4f1a-8f2b-2e75a4b8c0d1`, TX `6a4e9f02-...`, RX `6a4e9f03-...`.
  Har bir RX write = bitta to'liq frame (≤244 bayt). RX char `WRITE|WRITE_NR`,
  TX char `READ|NOTIFY`.
- **Reklama**: MFG data `[4E 4D][4D 4E][nodeId(16)]` — Android
  `addManufacturerData(0x4D4E, ...)` bilan bir xil on-air baytlar.
  Node deviceId: `[0..1]='m''n'`, `[2..7]=ESP32 MAC`, `[12..15]={4D 4E 0E 01}`.
- **Uzatish qoidasi** (`firmware/mesh_node/relay.h`): parse → dedup
  `(sender_id, msg_seq)` 60s (`RELAY_SEEN_TTL_MS`) → `ttl == 0` bo'lsa tashla →
  aks holda `ttl--` va flood barcha ulangan telefonlarga (manba qaytarilmaydi).
  Frame baytlari o'zgarmaydi — RELAY o'rash faqat telefonlarda.
- **MTU**: NimBLE (ESP32-C3) avtomatik 256 baytgacha kelishadi — 244 baytli
  frame bitta write'da sig'adi.
- **Cheklov**: ESP32-C3 `CONFIG_BT_NIMBLE_MAX_CONNECTIONS=3` — server va client
  linklari jami 3 ta, telefonga client ulanishlar soni 2 taga cheklangan.

Manba: `firmware/mesh_node/` (mesh_frame.c/h, relay.c/h, mesh_node.ino).

## 4. E2E kriptografiya sxemasi

- Kalit almashish: **X25519** (RFC 7748)
- Maxfiylik + yaxlitlik: **ChaCha20-Poly1305** (AEAD)
- Har bir (men, peer) juftligi uchun shared secret: `X25519(myPrivate, peerPublic)`
- Payload formati: `base64(ciphertext || authTag)`, frame ichida.
- Kalit zo'ralmagan: asosiy xutilib katta, o'z dizoyin yo'q. (math quvvatini
  ot Xavfsizlik bo'live keldi yozma, security foydali.)

## 5. Routing (2-hop flooding)

- A → B → C: A RELAY frame yuboradi, B qabul → target C bo'lsa: B C'ga uzatadi,
  delivery report'ni A orqali o'tkoradi.
- B xabar qunduz TEMI ko'raman; B payload shifrini o'qiy olmaydi (E2E).
- MVP chejarasi: hop_limit=2 — 3+ tugunlar keyingi bosqich.
- Dublikatdan saqlash: msg_id (sender_id+msg_seq) — ko'pmi oldin kunga rad etiladi.

## 5. Delivery/status

- A → B (bevosita): B qabul → `DELIVERY_REPORT` → A `{status: delivered}`.
- A → C (B orqali): B relay → C qabul → C report A ga, route = A<-B<-C.
- A → no reachable: `{status: failed}` 30 soniya timeout (so'ngra ttl 0).

## 6. QA parali (asosiy sestoriorlar)

| Senariy | Kutilgan natija |
|---------|-----------------|
| 2 qurilma BLE yoqilgan, app ochiq | peerDiscovered happened, listda peer |
| QR juftlash (A→B) | Authorized = true, chat o'chirish mumkin |
| A→C xabar (B relay) | C received, A delivery=delivered |
| B ilovani yopish | A→C failed yoki unroutable po eventi |
| Transport 2 (Wi-Fi Direct) yoqilganda | BLE niarsundissa o'tish, rssi yangilash |

## 6. Flutter strukturasi

- `lib/core/mesh_service.dart` — MethodChannel va EventChannel wrapperlar
- `lib/features/chat/` — xabar skrinni + logic
- `lib/features/contacts/` — tarmoq foydalanuvchanlar ro'yxati
- `lib/features/pairing/` — QR generatsiya + scan
- `lib/theme/` — dark professional tema

Milestone: MVP 1.0 — barcha yukoridagi ishlash: BLE transport + E2E matn chat + 2-hop relay.

---
© 2026 MeshNet / TechCorp. Confidentially.