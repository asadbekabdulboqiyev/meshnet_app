# MeshNet — PHASE 0: To'liq Tizim Arxitekturasi

> **Versiya:** 0.2
> **Sana:** 2026-08-16
> **Muallif:** TechCorp — Bosh Direktor (MD) + barcha bo'limlar
> **Holat:** PHASE 0 (arxitektura) — tasdiqlanmagan, ko'rib chiqishda
> **Mavjud kod bazasi:** `meshnet_app/` (Flutter UI + Kotlin mesh engine)

---

## Mundarija

1. [Maqsad va qamrov](#1-maqsad-va-qamrov)
2. [A. To'liq tizim arxitekturasi](#a-to'liq-tizim-arxitekturasi)
3. [B. MVP arxitekturasi](#b-mvp-arxitekturasi)
4. [C. Protokol dizayni](#c-protokol-dizayni)
5. [D. Paket formati](#d-paket-formati)
6. [E. Node identifikatsiyasi](#e-node-identifikatsiyasi)
7. [F. Peer discovery strategiyasi](#f-peer-discovery-strategiyasi)
8. [G. Ulanish strategiyasi](#g-ulanish-strategiyasi)
9. [H. Routing strategiyasi](#h-routing-strategiyasi)
10. [I. Shifrlash/xavfsizlik arxitekturasi](#i-shifrlashxavfsizlik-arxitekturasi)
11. [J. Store-and-forward arxitekturasi](#j-store-and-forward-arxitekturasi)
12. [K. ACK/retry arxitekturasi](#k-ackretry-arxitekturasi)
13. [L. Node xatoligidan qaytarish](#l-node-xatoligidan-qaytarish)
14. [M. Flutter ↔ Kotlin arxitekturasi](#m-flutter--kotlin-arxitekturasi)
15. [N. Android BLE/Wi-Fi strategiyasi](#n-android-blewi-fi-strategiyasi)
16. [O. Embedded apparat yo'l xaritasi](#o-embedded-apparat-yol-xaritasi)
17. [P. Repozitoriy tuzilishi](#p-repozitoriy-tuzilishi)
18. [Q. Jamoa rollari](#q-jamoa-rollari)
19. [R. Rivojlanish yo'l xaritasi](#r-rivojlanish-yol-xaritasi)
20. [S. Risk tahlili](#s-risk-tahlili)
21. [T. Test strategiyasi](#t-test-strategiyasi)
22. [U. Skalabillik tahlili](#u-skalabillik-tahlili)
23. [V. Texnologiya tanlash taqqoslanishi](#v-texnologiya-tanlash-taqqoslanishi)
24. [Platforma cheklovlari (IMPORTANT PLATFORM RULE)](#platforma-cheklovlari)
25. [Mavjud kod bilan solishtirish va bo'shliqlar](#mavjud-kod-bilan-solishtirish)
26. [Ko'rib chiqish uchun ochiq qarorlar](#korbid-korish-uchun-ochiq-qarorlar)

---

## 1. Maqsad va qamrov

Ushbu hujjat MeshNet tizimining **to'liq arxitekturasi**ni belgilaydi. Maqsad —
internet, uyali aloqa va markaziy server bo'lmagan sharoitda odamlar bir-biri
bilan xabar almasha oladigan **real, jismoniy qurilmalarda ishlaydigan**
decentralized offline mesh tarmoq qurish.

Hujjat barcha muhim texnik qarorlarda **NE**ni va **NEGA aynan u**ni tushuntiradi.
Agar biror qarorda noaniqlik bo'lsa, bu ochiq bayon qilinadi va eksperimental
tekshirish yo'li taklif qilinadi.

**Asosiy tamoyillar:**
- Haqiqiy tizim — demo emas. Yashirin/soxta networking, soxta shifrlash YO'Q.
- Fazali rivojlanish: har bir faza sinovdan o'tguncha keyingisiga o'tmaymiz.
- Kichik jamoa uchun oddiy, lekin 10→100→1000 tugungacha kengaya oladigan
  arxitektura.
- Oddiy emas: iloji boricha minimal, lekin zaif emas: xavfsizlik standartlarida.

---

## 2. A. To'liq Tizim Arxitekturasi

### 2.1 Umumiy qatlamlar

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
                        │ Platform Channel kontrakti (kontrakt quyida)
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
│  Android Bluetooth/Wi-Fi radios · (kelajakda) ESP32/LoRa     │
└─────────────────────────────────────────────────────────────┘
```

**Nima uchun bu qatlamlar?**
- **UI (Flutter)** — bitta kod bazasi, keyinchalik iOS'ga ko'chadi. Emergency
  muhitda soddalik muhim.
- **Mesh Core (Kotlin)** — BLE/Wi-Fi P2P API'lari faqat native Android'da mavjud.
  Tarmoq logikasi native'da, UI'dan mustaqil.
- **Platform Channel** — Flutter va Kotlin o'rtasida qat'iy kontrakt. Bu
  kontrakt transport implementatsiyasi o'zgarsa ham (masalan BLE → LoRa) UI
  o'zgarmasligini kafolatlaydi.
- **Transport layer** — har bir radio alohida modul. Yangi transport qo'shish
  `TransportManager` orqali bitta nuqtadan.

### 2.2 Komponentlar oqimi (xabar yuborish misolida)

```
Flutter ChatView
  → Dart sendMessage() [MethodChannel]
  → MeshEngine.handleMethodCall("sendMessage")
  → RoutingEngine.sendText()
      → MeshCrypto.encrypt (X25519 shared secret + ChaCha20-Poly1305)
      → MeshFrame.encode (binary paket)
  → TransportManager.sendFrame (WIFI → BLE fallback)
  → radio → havo → qabul qiluvchi
```

### 2.3 Oqim ma'lumotlari (eventlar)

```
Transport (BLE/Wi-Fi frame qabul)
  → TransportManager.onFrameReceived
  → RoutingEngine.handleIncomingFrame (dublikat tekshiruvi → type bo'yicha)
  → decryptAndDeliver (agar bizga)
      → MessageStore.addIncoming
      → EventChannel emit("messageReceived")
  → Dart incomingMessagesProvider
  → ChatView UI
```

---

## 3. B. MVP Arxitekturasi

### 3.1 MVP qamrovi

| # | Imkoniyat | Maqsad |
|---|-----------|--------|
| 1 | 2-5 ta Android telefon | PHASE 1-2 |
| 2 | BLE + Wi-Fi Direct transport | PHASE 1 |
| 3 | QR orqali juftlash (identity tekshiruv) | PHASE 3 |
| 4 | Matnli chat (E2E shifrlangan) | PHASE 3 |
| 5 | 2-hop relay (A→B→C) | PHASE 2 |
| 6 | Delivery statusi (sent/delivered/failed) | PHASE 3 |
| 7 | Tarmoq topologiyasi (debug) | PHASE 2 |
| 8 | Store-and-forward (offline qabul) | PHASE 5 |

### 3.2 MVP'da YO'Q (ataylab)

- Ovozli/qo'ng'iroq, video, fayl almashish
- Forward secrecy (kalit almashish sessiya darajasida) — keyingi faza
- iOS — Android MVP tasdiqlangach
- LoRa / embedded node — PHASE 7-8
- Metadata privacy (traffic analysis'dan himoya) — advanced privacy

### 3.3 MVP komponentlari (mavjud kod bilan mos)

| Komponent | Fayl | Holat |
|-----------|------|-------|
| Flutter UI (5 ekran) | `lib/` | ✅ bor |
| MethodChannel wrapper | `lib/core/mesh_service.dart` | ✅ bor |
| Identity | `IdentityStore.kt` | ✅ bor (Xavfsizlik zaxirasi bilan) |
| Crypto | `crypto/MeshCrypto.kt` | ✅ bor |
| Frame encode/decode | `protocol/MeshFrame.kt` | ✅ bor |
| Routing | `protocol/RoutingEngine.kt` | ✅ bor (tuzatishlar kerak) |
| BLE | `transport/BleTransport.kt` | ⚠️ bug bor (qarang §25) |
| Wi-Fi Direct | `transport/WifiDirectTransport.kt` | ⚠️ bug bor |
| Peer/Message storage | `storage/*.kt` | ✅ bor |

---

## 4. C. Protokol Dizayni

### 4.1 Xabar turlari

| Kod | Tur | Maqsad | Lifecycle | Xavfsizlik |
|-----|-----|--------|-----------|------------|
| 0x01 | `PEER_PING` | Borlik belgisi (heartbeat) | doimiy, low-power | shifrlanishi shart emas (metadata ochiq) |
| 0x02 | `TEXT` | Shifrlangan chat xabari | CREATED→QUEUED→FORWARDED→DELIVERED | E2E shifrlangan |
| 0x03 | `PAIR_REQ` | QR juftlash so'rovi | bir marta | public key ochiq, o'zaro tekshiruv |
| 0x04 | `PAIR_ACK` | Juftlash qabuli | bir marta | public key ochiq |
| 0x05 | `RELAY` | Oraliq tugun retranslyatsiyasi | o'tkinchi | ichida E2E frame, relay o'qiy olmaydi |
| 0x06 | `DELIVERY_REPORT` | Yetkazish hisoboti (ACK) | A→...→A | faqat meta; final qabul qiluvchi tomonidan |
| 0x07 | `FIND_PEER` | Tarmoqda qidiruv (route recovery) | so'rov/javob | ochiq meta |
| 0x08 | `FIND_PEER_ACK` | Qidiruvga javob (topilgan tugun) | so'rov/javob | ochiq meta, pubkey bilan |

**Kelajakka mo'ljallangan turlar** (PHASE 4+): `ROUTE_REQUEST`, `ROUTE_RESPONSE`,
`ROUTE_ERROR`, `SOS`, `BROADCAST`.

**Nima uchun `SOS` alohida tur?** SOS boshqa tur sifatida: (1) yuqori priority,
(2) boshqa foydalanuvchilarga (hatto paired bo'lmaganlarga) yuborilishi mumkin,
(3) store-and-forward qoidalari boshqacha. MVP'da SOS yo'q — PHASE 4-5 da.

### 4.2 Protokol qoidalari

1. **Broadcast** target: hammaga `target_id = all-zero`.
2. **Dublikat**: `(sender_id, msg_seq)` juftligi seen-cache'da tekshiriladi.
3. **TTL**: har relay'da `ttl = ttl - 1`; `ttl = 0` → DROP.
4. **Hop limit**: MVP'da 2; PHASE 4+ da routing ma'lumotiga ko'ra.
5. **Frame yaxlitligi**: `magic + version` tekshiruvi; noto'g'ri frame → tashlab
   yuborish (log bilan).
6. **Malicious input**: har qanday kelgan frame parse qilinishidan oldin uzunlik
   va format tekshiruvidan o'tadi. Exception → drop, crash emas.

---

## 5. D. Paket Formati

### 5.1 Tanlov: JSON / CBOR / binary

| Mezon | JSON | CBOR | Binary (qat'iy) |
|-------|------|------|------------------|
| Hajm (30 bayt header) | ~120 bayt | ~55 bayt | **43 bayt** |
| Parse tezligi | sekin | o'rtacha | **eng tez** |
| BLE MTU (244 B) sig'imi | ~100 bayt payload | ~180 bayt | **~200 bayt** |
| Deterministik layout | yo'q | qisman | **ha** |
| O'qish osonligi | ✅ | o'rtacha | og'ir (hujjat kerak) |
| Embedded (ESP32) | sekin | o'rtacha | **eng mos** |

**Qaror:** binary qat'iy frame. **Nima uchun:** BLE MTU 244 bayt — har bir bayt
muhim. Payload ko'p hollarda shifrlangan matn (Chacha20-Poly1305 tag + nonce),
shuning uchun JSON o'sha hajmda faqat 40-50% samarali. Deterministik layout
embedded qurilmalarda (C da) ham bir xil kodlanadi.

**Taqdim etilayotgan frame** (joriy `MeshFrame.kt` + kengaytmalar):

| Offset | Maydon | Hajm | Izoh |
|--------|--------|------|------|
| 0-1 | magic `0x4D 0x4E` | 2 | "MN" |
| 2 | version | 1 | `0x01` |
| 3 | type | 1 | MessageType code |
| 4 | hop_limit | 1 | MVP: 2 |
| 5 | ttl | 1 | maks 8 |
| 6 | flags | 1 | bit0=encrypted, bit1=priority(SOS) |
| 7-22 | sender_id | 16 | UUID (128-bit) |
| 23-38 | target_id | 16 | broadcast = all-zero |
| 39-46 | msg_seq | 8 | epoch-ms Long (relay orqali aniq saqlanadi) |
| **47-48** | **payload_len** | **2** | **YANGI: framing uchun zarur (BLE chunk) |
| 49-? | sender_pubkey | 32* | faqat PAIR_REQ/PAIR_ACK |
| ? | payload | n | shifrlangan matn |

**Nima uchun `payload_len` qo'shish kerak:** BLE xabari chunklarga bo'linadi.
Hozirgi kod chunk uzunligini o'zi boshqaradi, lekin qabul qiluvchi to'liq
frameni qachon tugaganini bilishi uchun uzunlik aniq bo'lishi kerak. Bu maydon
Wi-Fi socket protocol'da ham (`DataInputStream.readInt`) allaqachon bor — BLE
tomonda ham bir xil bo'lsin.

### 5.2 Node ID — 128-bit UUID

- **Nega 128-bit:** UUID standarti, Flutter/Kotlin/UUID ta'minoti mavjud,
  kolliziya ehtimoli amalda nolga teng. (Reticulum 80-bit ishlatadi — bizning
  MVP uchun kerak emas, 16 bayt frame'da to'liq mos.)
- Broadcast: 16 bayt nol.

---

## 6. E. Node Identifikatsiyasi

### 6.1 Arxitektura

```
Device
 ├── Node ID        (128-bit UUID, bir marta generatsiya)
 ├── Public Key     (X25519, 32 bayt)  — ochiq, QR'da ko'rinadi
 └── Private Key    (X25519, 32 bayt)  — hech qachon qurilmadan chiqmaydi
```

- **Node ID** — qurilmaning ochiq identifikatori (tarmoqda "MN-7F3A92" kabi
  qisqa ko'rinishda).
- **X25519 kalit juftligi** — identifikatsiya + kalit almashish uchun. Node ID
  va public key bir-biriga bog'liq: ID = hash(public_key) bo'lishi mumkin
  (identity = key). Bu "identity-committed" yondashuv.
  - **Nima uchun identity = public key hash'i?** Impersonatsiya qilishning
    oldini oladi: kimdir sizning ID'ingizni egallashi uchun sizning *private
    key'ingiz* kerak. Bir paytning o'zida Node ID avtomatik generatsiya bo'ladi.
  - **Hozirgi holat:** ID random UUID, kalit alohida — bu ham yaroqli, lekin
    keyingi fazada ID=hash(pubkey) ga o'tish tavsiya.

### 6.2 Identity saqlash, reset, rotatsiya, qaytarish

| Hodisa | Yondashuv | Nega |
|--------|-----------|------|
| **Saqlash** | Private key → **Android Keystore** (hardware-backed, API 23+). Hozir SharedPreferences'da — MVP chegarasi, PHASE 9 da Keystore'ga ko'chiriladi | Keystore xavfsizroq; SharedPreferences root qilingan qurilmada o'qilishi mumkin |
| **Device reset** | Yangi identity generatsiya qilinadi; eski kalitlar yo'qoladi | Dizayn bo'yicha: private key qaytarib bo'lmaydi |
| **Key rotation** | MVP: yo'q. PHASE 6+: eski kalit bilan imzolangan rotatsiya sertifikati | Bir kalit umrbod — kompromet bo'lsa butun identity xavf ostida |
| **Recovery (backup)** | Faqat **public** material nusxalanishi mumkin (QR/papka). Private key backup YO'Q | Private key backup = xavfsizlik nuqsoni |
| **Identity tekshiruv** | QR juftlash — out-of-band kanal (vizual). Ikki tomon o'zaro public key'larni tasdiqlaydi | MITM'dan himoya: QR faqat qo'lda ko'rsatiladi |

---

## 7. F. Peer Discovery Strategiyasi

### 7.1 BLE discovery

- **Advertise:** har tugun o'zini BLE reklamada e'lon qiladi:
  - Service UUID `6a4e9f01-...` (MeshNet)
  - Device name = displayName (reklama nomi)
  - Mode: `LOW_LATENCY` faol / `LOW_POWER` pasaytirilgan (battery)
- **Scan:** `SCAN_MODE_LOW_POWER` fon, `LOW_LATENCY` on-demand (pull-to-refresh).
  Service UUID filter — boshqa BLE qurilmalarini ko'rmaslik uchun.
- **RSSI:** discovery'da qayd etiladi; yangi skan natijasi eski qiymatni
  yangilaydi.
- **Cooldown:** bitta peer uchun discovery event'larini qisqa muddatda
  takrorlamaslik (event flood'ni oldini olish).

### 7.2 Wi-Fi Direct discovery

- `discoverPeers()` + `WIFI_P2P_PEERS_CHANGED_ACTION` receiver.
- `WifiP2pDevice.AVAILABLE` → peer topildi; `UNAVAILABLE` → peer ketdi.

### 7.3 Discovery → PeerStore oqimi

```
Topildi (BLE/Wi-Fi)
  → PeerStore.upsert(deviceId, displayName, rssi, transport, lastSeen=now)
  → emit("peerDiscovered")
  → Dart peersProvider (FutureProvider) yangilanadi
```

**MUHIM BO'SHLIQ (joriy kodda):** `TransportManager.bleListener.onPeerDiscovered`
faqat log yozadi — PeerStore'ga **upsert qilmaydi**, event emas. Shuning uchun
UI "Tarmoq qamrovi" hech qachon to'ldirilmaydi. Bu PHASE 1'ning birinchi
tuzatishlaridan biri (§25).

---

## 8. G. Ulanish Strategiyasi

### 8.1 BLE ulanish modeli

- **Presence:** reklama doim yoqilgan (lazy connection uchun — "men shu yerdaman").
- **Connection:** **on-demand (lazy)** — faqat xabar yuborish kerak bo'lganda
  GATT connection ochiladi.
  - **Nega lazy?** Android'da qurilma uchun faqat ~7-9 ta bir vaqtda GATT
    client ulanishi bo'ladi. Doim hamma bilan ulanish = darhol limitga yetamiz.
  - Connection pool: eski/faolsiz ulanishlar yopiladi (LRU).
- **Xabar almashish:** GATT characteristic write/notify. Uzun xabarlar
  chunklarga bo'linadi (244 bayt / ATT MTU).
- **Disconnect:** RSSI yo'qolsa yoki 30s faolsiz bo'lsa.

### 8.2 Wi-Fi Direct ulanish modeli

- Guruh tuzish: har node `createGroup` (Group Owner) yoki mavjud guruhga qo'shilish.
- TCP socket (PORT 4864): GO ↔ client. Guruh kattaligi Android'da odatda 5-8.
- Socketlar kafolatli: guruh ichida persistent.

### 8.3 Qatlamlararo tanlov

`TransportManager.sendFrame` tartibi: **Wi-Fi Direct → BLE fallback**.
- **Nega Wi-Fi birinchi?** Tezlik va throughput yuqori, socket protokol soddaroq,
  xabar chunklash shart emas.
- **Nega BLE ikkinchi?** Presence (borlik) va kichik xabarlar uchun battery
  jihatidan tejamkor. BLE'da hamma ham Wi-Fi Direct guruhiga kirmagan bo'lishi
  mumkin.

---

## 9. H. Routing Strategiyasi

### 9.1 Routing algoritmlarini taqqoslash

| Mezon | Flooding | BATMAN-ish (proactive rank) | AODV (reactive) | DSR |
|-------|----------|------------------------------|-----------------|-----|
| Latency | past (darhol) | past | yuqori (route topish) | o'rtacha |
| Battery | yuqori xarajat | o'rtacha | past (talab bo'yicha) | past |
| Bandwidth | yuqori | o'rtacha | past | past |
| Memory | past | past | o'rtacha | yuqori |
| Skalabillik (10-20) | ✅ yaxshi | ✅ | ✅ | o'rtacha |
| Skalabillik (100+) | ❌ portlash | o'rtacha | o'rtacha | ❌ |
| Mobil node'lar | ✅ eng yaxshi | ✅ | o'rtacha | zaif |
| Implementatsiya murakkabligi | **eng past** | o'rtacha | o'rtacha | yuqori |
| Konvergensiya (route topish) | yo'q (doim yangi) | tez | sekin | sekin |

### 9.2 Qaror

**MVP → Controlled Flooding (nazoratli flooding).**

Nima uchun:
1. 5-20 tugun uchun flooding xarajati juda past — har xabar 2-3 nusxada.
2. Route jadvali YO'Q → konvergensiya vaqti YO'Q → mobil node'lar bilan ham
   ishlaydi (har qanday vaqtda "yo'l" doim yangi).
3. Node yo'qolsa, yangi flood avtomatik yangi yo'l topadi (PHASE 6 ga tayyor).
4. Eng oddiy va eng ishonchli MVP boshlanishi.

Flooding nazorat mexanizmlari:
- **TTL** (maks 8) — loop cheklovi.
- **Hop limit** (MVP: 2) — yangi tugun chegarasi.
- **Seen-cache** (dublikat) — `(sender_id, msg_seq)`.
- **Cache expiry:** seen-cache'da yozuv 60 soniya yashaydi (RTT shkalasida),
  keyin o'chiriladi (yangilangan xabarlar o'tishi uchun, lekin flood
  qaytarmaslik uchun).

### 9.3 RoutingEngine abstraksiyasi

```kotlin
interface RoutingEngine {
    fun handleIncomingFrame(frame: MeshFrame)
    fun sendText(targetId: String, message: String): String
    fun sendBroadcast(payload: ByteArray)
    fun nodeStatusChanged(deviceId: String, online: Boolean)  // PHASE 6
}
```

Arxitektura routing algoritmini keyinchalik almashtirishga imkon beradi:
`FloodingRoutingEngine` (MVP) → `RankRoutingEngine` (BATMAN-ish, PHASE 4).

### 9.4 Joriy kod tahlili

`RoutingEngine.kt` allaqachon 2-hop flooding qiladi (RELAY turi bilan). Uchun
tuzatishlar kerak:
- `ttl` ni amalda kamaytirish (`hopLimit` ga tayanish bug'li — relay'da TTL
  ham kamayishi kerak).
- `seenMessages` cache'siga expiry qo'shish (hozir cheksiz o'sadi — memory leak).
- Broadcast (all-zero target) qo'llab-quvvatlash.

---

## 10. I. Shifrlash/Xavfsizlik Arxitekturasi

### 10.1 Tanlov: primitivlar

| Komponent | Tanlov | Alternativ | Nega tanlandi |
|-----------|--------|------------|----------------|
| Kalit almashish | **X25519** (static identity keys) | ECDH P-256 | Curves, resistance, keng qo'llab-quvvatlash (BouncyCastle) |
| Maxfiylik + yaxlitlik | **ChaCha20-Poly1305** (AEAD) | AES-GCM | AES-GCM telefondan tashqari (ESP32) hardware acceleratsiya talab qiladi; ChaCha20-Poly1305 software'da tez va xavfsiz |
| Imzo (PHASE 4+) | **Ed25519** | ECDSA | O'ziga xos, tez, xavfsiz |
| Replay himoya | nonce + msg_seq + seen-cache | — | standart usul |

**MUHIM:** `ChaCha20-Poly1305` JCE `Cipher` **Android API 28+** da mavjud.
`minSdk = 26` bo'lsa, API 26-27 qurilmalarda exception tashlanadi. Yechim:
- **BouncyCastle provider** orqali `Cipher.getInstance("ChaCha20-Poly1305", "BC")`
  (bcprov 1.79 allaqachon dependency'da). Bu barcha API'da ishlaydi.
- Yoki `minSdk` ni 28 ga ko'tarish.

**Tavsiya:** BouncyCastle provider'ni `Security.addProvider()` orqali ishga
tushirish (bir satr), minSdk 26 ni saqlash.

### 10.2 Sessiya kaliti sxemasi

```
Alice (X25519)            Bob (X25519)
   │    pubA ───────────────▶ │
   │ ◀────────────── pubB    │
   │                          │
   secret = X25519(privA, pubB) == X25519(privB, pubA)
   │                          │
   encrypt(msg, secret) ────▶ decrypt(msg, secret)
```

- Har bir (men, peer) juftligi uchun bitta static shared secret.
- **Forward secrecy: MVP'da YO'Q** (static key). PHASE 6+: ephemeral
  X25519 + ratchet (Signal-style) yoki HPKE.
  - **Risq:** agar private key kompromet bo'lsa, eski xabarlar o'qilishi mumkin.
  - **Yumshatish:** MVP'da kalit faqat qurilmada, QR faqat qo'lda; xavf past.

### 10.3 AAD (Associated Authenticated Data)

`aad = "MeshNet:" + targetId` — routing ma'lumotini shifrga bog'laydi: qabul
qiluvchi shifrni ochayotganda AAD to'g'ri bo'lmasa, tag ishlamaydi. Bu "replay
boshqa targetga" hujumini oldini oladi. ✅ joriy kodda allaqachon bor.

### 10.4 Identity verification

- QR juftlash: visual kanal — MITM'dan himoya.
- Juftlashdan keyin `PeerStore.markAuthorized(deviceId, pubKey)`.

### 10.5 Xavfsizlik maqsadlari × MVP holati

| Maqsad | MVP | Izoh |
|--------|-----|------|
| E2E shifrlash | ✅ | X25519 + ChaCha20-Poly1305 |
| Autentifikatsiya | ✅ | Identity = kalit, QR tekshiruv |
| Yaxlitlik | ✅ | AEAD tag |
| Replay himoya | ✅ | nonce + seen-cache |
| Forward secrecy | ⏳ | PHASE 6 |
| Key management | ⚠️ | Keystore PHASE 9; hozir SharedPreferences |
| Identity tekshiruv | ✅ | QR |

### 10.6 Threat model (xulosa — to'liq jadval §S)

| Tahdid | Impact | Ehtimol | Yumshatish | Qoldiq risk |
|--------|--------|---------|------------|-------------|
| Eavesdropping | yuqori | o'rta | E2E (relay o'qiy olmaydi) | past |
| MITM | yuqori | past | QR out-of-band | past |
| Replay | o'rta | past | nonce + seen-cache | past |
| Node impersonation | yuqori | past | identity=kalit | past |
| Sybil | o'rta | o'rta | QR tasdiqlash (faqat authorized) | o'rta |
| Packet flooding (DoS) | o'rta | o'rta | seen-cache + rate limit (PHASE 9) | o'rta |
| Fake ACK | past | past | ACK final qabul qiluvchi orqali; MVP oddiy | o'rta |
| Malicious relay | o'rta | past | relay faqat meta ko'radi, E2E himoya | past |
| Traffic analysis | o'rta | o'rta | **MVP da ochiq** — metadata leaki | yuqori |

### 10.7 Metadata privacy: MVP vs Advanced

- **MVP:** sender/target UUID, TTL, vaqt — **ochiq** (relay ko'radi). Bu
  routing uchun zarur va transport darajasida (BLE reklama nomi) ham ochiq.
- **Advanced (PHASE 8+):** reklama nomini doimiy almashish, padding,
  dummy traffic, onion-style — bular MVP'da kiritilmaydi, lekin arxitektura
  protokol turi qo'shish orqali buni qo'llab-quvvatlaydi.

---

## 11. J. Store-and-Forward Arxitekturasi

### 11.1 Maqsad

Qabul qiluvchi oflayn bo'lganda, xabar oraliq tugunda saqlanadi va qabul
qiluvchi yana tarmoqqa qo'shilganda yetkaziladi.

### 11.2 Model

```
Message → QUEUED (local MessageStore, TTL boshlanadi)
  → retry har X soniyada (jitter bilan)
  → peer online bo'lsa → send → DELIVERED
  → TTL tugadi (24h) → EXPIRED (foydalanuvchiga bildiriladi)
```

- **Saqlash hajmi:** maks 5MB yoki 500 xabar (round-robin eviction).
- **Prioritet:** SOS > TEXT > PING.
- **Dublikat:** saqlangan xabar ham `(sender, seq)` bilan deduplikatsiya.
- **Persistence:** `MessageStore` SharedPreferences (MVP); PHASE 9: Room + yaqin
  shifrlangan DB.
- **Xabarlar allaqachon E2E shifrlangan** — diskda ochiq matn saqlanmaydi.

### 11.3 Event: peer rejoin

`peerDiscovered` (online) → `StoreAndForward.flush(deviceId)` → saqlangan
xabarlar yuboriladi.

---

## 12. K. ACK/Retry Arxitekturasi

### 12.1 Xabar lifecycle

```
CREATED → QUEUED → FORWARDED → DELIVERED (ACK keldi)
                             → EXPIRED (TTL 0, saqlanmay qoldi)
                             → FAILED (3 retry → muvaffaqiyatsiz)
```

### 12.2 ACK oqimi

```
A → B → C → D (TEXT)
D decrypt qildi
D → C → B → A (DELIVERY_REPORT, delivered=1)
A: pending map'dan olib tashlash, UI'da ✓✓
```

### 12.3 Retry siyosati

- Timeout: 30 soniya (jitter ±5s).
- Retry soni: maks 3.
- Backoff: 30s → 60s → 120s (eksponensial).
- Retry cheki: 3 ta — **cheksiz retry YO'Q** (battery + bandwidth).
- After fail → `FAILED` state, UI'da qayta yuborish tugmasi.

### 12.4 Fake-ACK himoyasi (MVP yondashuv)

- DELIVERY_REPORT final qabul qiluvchi tomonidan yaratiladi va original
  `(sender, seq)` ni olib qaytadi.
- Relay uni o'zgartirishi mumkin (malicious relay). MVP'da buni to'liq
  oldini olish yo'q; PHASE 6: DELIVERY_REPORT'ni sender public key bilan
  imzolash (Ed25519).

---

## 13. L. Node Xatoligidan Qaytarish

### 13.1 Detektsiya

- **Heartbeat:** `PEER_PING` har 15s (battery-aware, jitter bilan).
- Peer 3× intervalidan (45s) javob bermasa → `offline`, route o'chiriladi.
- BLE RSSI yo'qolsa → `peerLost`.
- Wi-Fi `UNAVAILABLE` → `peerLost`.

### 13.2 Recovery (flooding'da)

- Route jadvali bo'lmagani uchun: yangi xabar yangi flood bilan avtomatik
  yangi yo'l topadi. **Bu flooding'ning eng katta afzalligi** — konvergensiya
  vaqti YO'Q.
- Send muvaffaqiyatsiz bo'lsa: `FIND_PEER` broadcast → yo'l qidirish →
  qayta yuborish.

### 13.3 Stale route tozalash

- `PeerStore` da `lastSeenMs > 60s` → peer UI'da offline ko'rinadi (o'chirilmaydi,
  tarix uchun).
- Seen-cache 60s expiry (yangi flood o'tishi uchun).
- PHASE 6: routing jadvali bo'lsa — `ROUTE_ERROR` va qayta hisoblash.

---

## 14. M. Flutter ↔ Kotlin Arxitekturasi

### 14.1 Platform Channel kontrakti (joriy + kengaytma)

**Method channel: `meshnet/engine`**

| Metod | Parametrlar | Qaytarish | Holat |
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

| Event | Payload | Holat |
|-------|---------|-------|
| `peerDiscovered` | `{deviceId, displayName, rssi, transport}` | ⚠️ hali emit qilinmaydi |
| `peerUpdated` | `{deviceId, rssi, hopCost}` | ⚠️ hali yo'q |
| `peerLost` | `{deviceId}` | ⚠️ hali emit qilinmaydi |
| `messageReceived` | `{fromDeviceId, message, messageId}` | ✅ |
| `deliveryStatus` | `{messageId, status}` | ✅ |
| `engineState` | `{state}` | ✅ |

### 14.2 Dart tomonda (joriy + taklif)

- `lib/core/mesh_service.dart` — MeshService wrapper ✅
- `lib/core/providers.dart` — peersProvider, incomingMessagesProvider ✅
- **Taklif:** `MessageRepository` / `ChatController` (Riverpod `Notifier`) —
  chat history, delivery status, retry UI holatini boshqaradi. Hozir chat
  history faqat widget state'ida (`_messages` List) — app yopilsa yo'qoladi.

### 14.3 Kontrakt qoidalari

1. Barcha `Map<String, dynamic>` — primitiv tiplar (String/num/bool) orqali.
   Qo'shimcha klass seriyalash yo'q (binary frame faqat Kotlin'da).
2. Event'lar broadcast; Flutter tomonda `StreamProvider` orqali kuzatiladi.
3. Xato holatlarida `result.error("code", "msg", null)`.

---

## 15. N. Android BLE/Wi-Fi Strategiyasi

### 15.1 BLE

| Komponent | Strategiya | Battery izohi |
|-----------|------------|----------------|
| Advertise | `LOW_LATENCY` faol / `LOW_POWER` fon | adaptive interval (100ms→1s) |
| Scan | `LOW_POWER` fon, `LOW_LATENCY` on-demand | scan batch (reportDelay) |
| Connection | Lazy, LRU pool, maks ~7 | 1 ulanish ≈ 5-10mA |
| MTU | `requestMtu(512)` so'rov; chunk 244 | — |
| Packet batching | kichik xabarlar bitta write | — |
| Sleep/wake | Wake lock minimal, `acquire` send paytida | — |

### 15.2 Wi-Fi Direct

- Discovery doim emas — interval bilan.
- Guruh yaratish/ulanish faqat xabar uchun kerak bo'lganda.
- Socket TCP keep-alive 30s.

### 15.3 Android background cheklovlari (muhim!)

- Android 8+: background location/scan cheklangan. **Foreground service**
  talab — `FOREGROUND_SERVICE_CONNECTED_DEVICE` ✅ manifestda bor.
- Android 12+: `BLUETOOTH_SCAN`, `BLUETOOTH_ADVERTISE`, `BLUETOOTH_CONNECT`
  runtime ruxsatlari ✅ (permission_handler + `neverForLocation`).
- Android 13+: `POST_NOTIFICATIONS` runtime ✅.
- Doze / App Standby: BLE scan/advertise to'xtatilishi mumkin. Foreground
  service + `batteryOptimization` exception (PHASE 9 da).
- **Background BLE GATT** — Android 10+ da cheklangan: `startScan` background'da
  ishlamaydi. Shuning uchun mesh faqat foreground service'da va app
  qisman ko'rinadigan holda ishlaydi.

### 15.4 Haqiqiy qurilmalarda "bg/BG reliability" pozitsiyasi

| Funktsiya | Foreground | Background | Izoh |
|-----------|-----------|-----------|------|
| BLE advertise | ✅ ishonchli | ⚠️ OS to'xtatishi mumkin | service+optimization |
| BLE scan | ✅ | ⚠️ Android 10+ background'da taqiq | service orqali |
| Wi-Fi Direct | ✅ | ⚠️ doze'da muammo | — |
| GATT connection | ✅ | ⚠️ cheklangan | — |

**Pozitsiya:** MVP "app ochiq + foreground service" rejimida ishlaydi. To'liq
background mesh — Android platforma cheklovi; uni yashirmaymiz. PHASE 9 da
battery optimization exception so'raladi, lekin to'liq fon ishlashi platforma
siyosatiga bog'liq.

---

## 16. O. Embedded Apparat Yo'l Xaritasi

### 16.1 Kandidatlar taqqoslanishi

| Mezon | ESP32-C3 | nRF52840 | RP2040 + radio | ESP32-S3 |
|-------|----------|----------|----------------|----------|
| Narx | $3-4 | $8-10 | $1 + $3 (radio) | $4-6 |
| BLE 5 | ✅ (long range?) | ✅ (2Mbit, Coded) | ❌ (external) | ❌ (Wi-Fi+BLE?) |
| Wi-Fi | ❌ | ❌ | ❌ | ✅ |
| RAM | 400KB | 256KB | 264KB | 512KB |
| Flash | 4MB | 1MB (ext) | 2MB | 8MB |
| Quvvat (sleep) | ~5µA | ~1µA | ~2µA | ~7µA |
| Rivojlanish osonligi | ✅ Arduino/ESP-IDF | o'rtacha (nRF SDK) | ✅ Arduino | ✅ |
| Mavjudligi | ✅ | o'rtacha | ✅ | ✅ |

### 16.2 Qaror

- **Prototip relay node:** **ESP32-C3** — arzon, BLE 5, Arduino/ESP-IDF oson,
  internetdan oson olinadi. PHASE 7 uchun.
- **Field (battery) node:** **nRF52840** — eng yaxshi BLE 5 (Coded PHY — 1km
  gacha masofa), eng past quvvat. LoRa backbone qo'shilganda SX1262 bilan.
- **RP2040** — alohida radio talab qiladi, mesh'ga mos emas (biz radio
  integratsiya qilish uchun qo'shimcha ish). Chiqarib tashlandi.

### 16.3 BLE ↔ node ↔ BLE arxitekturasi

```
Phone A ──BLE──▶ MeshNode (ESP32-C3) ──LoRa/BLE──▶ MeshNode ──BLE──▶ Phone B
```

- Node GATT server sifatida: phone'lar ulanishi mumkin.
- Node → node: BLE (Coded PHY long range) yoki LoRa (SX1262) — PHASE 8.
- Node UI'ga ega emas — LED status + power management.

### 16.5 PHASE 7 implementatsiyasi (2026)

- **Stack**: Arduino-ESP32 **3.3.11**, `esp32:esp32:esp32c3`. ESP32-C3 build'i
  **NimBLE** (`CONFIG_BT_NIMBLE_ENABLED`) — MTU avtomatik 256, MAX_CONNECTIONS=3.
- **Manba**: `firmware/mesh_node/` — `mesh_frame.h/c` (wire parse/encode,
  portable C, xost test), `relay.h/c` (dedup+TTL, 64 entry, 60s TTL),
  `mesh_node.ino` (GATT server RX/WRITE + adv MFG + GATT client scan/connect).
- **Host testlar**: `firmware/mesh_node/test/` — `make run` → 7/7 PASS.
- **Build**: `arduino-cli compile --fqbn esp32:esp32:esp32c3 firmware/mesh_node`.
- **Qoldiq**: real qurilmada flash + telefon bilan ulanish testi (apparat yo'q).

### 16.4 Battery (embedded)

- Deep sleep (5µA), wake-on-event.
- Duty cycle: advertise 1s / listen 1s.
- Battery monitoring (ADC).

---

## 17. P. Repozitoriy Tuzilishi

### 17.1 Taklif (PHASE 5+ da qo'llaniladi)

```
meshnet/
├── mobile/
│   └── flutter_app/          # Flutter UI (hoziroq: meshnet_app/lib)
├── android/
│   └── mesh_network/         # Kotlin mesh engine (hoziroq: meshnet_app/android)
├── protocol/
│   ├── packet/               # frame spetsifikatsiyasi + codec testlari
│   ├── routing/
│   ├── crypto/
│   └── serialization/
├── firmware/
│   └── mesh_node/            # ESP32-C3 (PHASE 7)
├── docs/
│   ├── architecture/         # bu hujjat
│   ├── protocol/
│   ├── security/
│   └── testing/
├── tools/                    # test/benchmark skriptlar
└── README.md
```

### 17.2 Hozirgi holat va qaror

Joriy repo: `meshnet_app/` — Flutter + Android birga, `docs/` bor.

**Qaror:** MVP (PHASE 1-4) davomida joriy tuzilma **saqlanadi** — qayta
tashkil qilish vaqti va git tarixni buzadi, foyda past. PHASE 5 boshlanganda
(store-and-forward va test infrastruktura kerak bo'lganda) monorepo'ga o'tamiz.
Bu qaror "refactor churn" riskini kamaytiradi.

**Nima uchun hozir emas:** MVP 5 kishi, 5 fayl; o'tishning narxi (barcha
import path, CI, git) MVP'ga qiymat qo'shmaydi.

---

## 18. Q. Jamoa Rollari

| Rol | Bo'lim | Vazifa |
|-----|--------|--------|
| Bosh Direktor (MD) | — | Qarorlar, prioritet, xavf nazorati |
| Flutter dasturchi | Frontend | UI ekranlari, Riverpod state, chat UX |
| Kotlin mesh muhandisi | Backend | Identity, crypto, routing, transport, store-forward |
| DevOps | DevOps | Build (Gradle/CI), qurilma deploy, benchmark avtomatlash |
| QA | QA | Test rejasi, unit/integration test, real qurilma testlari |
| Xavfsizlik | Security | Threat model, kripto audit, pen-test |
| Dizayner | Dizayn | Emergency UX, dark theme, holat belgilari |

**Kichik jamoa (talabalar):** 1 kishi 1-2 rolni qamraydi. Eng muhim ikkita rol:
Kotlin mesh muhandisi va Flutter dasturchi. QA va Security vaqtincha (PHASE 2+).

---

## 19. R. Rivojlanish Yo'l Xaritasi

| Faza | Mazmun | Vaqt (hisob) | Chiqish mezonlari |
|------|--------|--------------|-------------------|
| 0 | Arxitektura (bu hujjat) | 1 hafta | Tasdiqlangan arxitektura |
| 1 | **Single-hop P2P** (A↔B) | 1-2 hafta | BLE+Wi-Fi orqali matn chat, ACK, discovery |
| 2 | **Multi-hop** (A→B→C→D) | 2 hafta | 2-hop relay, TTL, dublikat |
| 3 | **Secure messaging** | 1-2 hafta | Juftlash, E2E, delivery status |
| 4 | **Dynamic routing** | 2 hafta | Route recovery, FIND_PEER, rank-based |
| 5 | **Store & forward** ✅ | 2 hafta | Offline qabul, flush, expiry |
| 6 | **Fault tolerance** ✅ | 1-2 hafta | Heartbeat, failover, key rotation* |

> ✅ = kod yozildi (42 JVM test, `flutter analyze` toza, debug APK quriladi).
> *PHASE 6: heartbeat + failover bajarildi; **key rotation PHASE 9 ga ko'chirildi**
> (foydalanuvchi qarori).
> **PHASE 7 (ESP32-C3 node):** `firmware/mesh_node/` yozildi — `mesh_frame.c/h`
> + `relay.c/h` (7/7 host test PASS) va `mesh_node.ino` (BLE server+client,
> NimBLE). `arduino-cli compile --fqbn esp32:esp32:esp32c3` o'tadi. Haqiqiy
> qurilmada test qolgan (apparat yo'q).
| 7 | **Embedded nodes** ⏳ | 4-6 hafta | ESP32-C3 relay node |
| 8 | **Long-range** | 3-4 hafta | LoRa backbone, nRF52840 |
| 9 | **Hardening** | davomiy | Keystore, pen-test, battery opt., CI |

**Qoida:** har faza "real qurilmalarda test" dan keyingina yopiladi.

---

## 20. S. Risk Tahlili

| Risk | Impact | Ehtimol | Yumshatish | Qoldiq |
|------|--------|---------|------------|--------|
| BLE reliability (ulanish limitlari, drop) | Yuqori | Yuqori | Lazy connection, LRU pool, Wi-Fi Direct asosiy | O'rta |
| 5 ta real qurilma logistika | Yuqori | Yuqori | Erta xarid, emulator + real aralash | O'rta |
| Wi-Fi Direct guruh cheklovi (5-8) | O'rta | O'rta | BLE'ga tayanuvchi mesh | Past |
| ChaCha20 API 26-27 muammosi | O'rta | O'rta | BouncyCastle provider | Past |
| Private key SharedPreferences'da | Yuqori | Past | PHASE 9: Keystore | Past (MVP) |
| Routing portlashi (100+ tugun) | O'rta | Past | Rank-based routing PHASE 4 | O'rta |
| Battery drain | O'rta | O'rta | Adaptive interval, lazy | O'rta |
| Noaniq/soxta qurilmalar (spoofing) | O'rta | O'rta | QR identity tekshiruvi | O'rta |

### Threat model (to'liq)

| Tahdid | Ta'rif | Impact | Ehtimol | Yumshatish | Residual |
|--------|--------|--------|---------|------------|----------|
| Eavesdropping | Xabarni tutib o'qish | Yuqori | O'rta | E2E ChaCha20-Poly1305 | Past |
| MITM | A va B o'rtasiga kirish | Yuqori | Past | QR out-of-band tekshiruv | Past |
| Replay | Eski xabarni takrorlash | O'rta | Past | nonce + seen-cache | Past |
| Message injection | Soxta xabar yuborish | O'rta | O'rta | authorized peer talab | O'rta |
| Impersonation | Boshqa ID'ni egallash | Yuqori | Past | identity=kalit, QR | Past |
| Sybil | Ko'p soxta tugun | O'rta | O'rta | Faqat QR paired'larga ishonch | O'rta |
| Flooding (DoS) | Xabar portlashi | O'rta | O'rta | seen-cache, rate limit (P9) | O'rta |
| Route poisoning | Yo'l jadvalini buzish | O'rta | Past | Flooding'da jadval yo'q | Past |
| Fake ACK | Yetkazilgan degan yolg'on | Past | Past | ACK final qabul qiluvchi; P6 imzo | O'rta |
| Malicious relay | Relay xabarni tashlashi | O'rta | Past | E2E himoya; tushirilgan xabar — delivery report yo'q | O'rta |
| Compromised device | Kalit o'g'irlanishi | Yuqori | Past | Keystore, lokal saqlash | O'rta |
| Spam | Keraksiz xabarlar | Past | O'rta | authorized-only | Past |
| Traffic analysis | Kim qachon kimga yozadi | O'rta | O'rta | **MVP'da ochiq**; P8 padding | Yuqori |

---

## 21. T. Test Strategiyasi

### 21.1 Darajalar

| Daraja | Joy | Qamrov | Vosita |
|--------|-----|--------|--------|
| Unit | JVM (Kotlin) | MeshFrame roundtrip, MeshCrypto, RoutingEngine, dedup, TTL | JUnit 4/5 |
| Unit | Dart | MeshService kontrakt, providers | flutter_test |
| Integration | JVM | RoutingEngine + fake transport'lar (loopback) | JUnit |
| Integration | Device | 2 transport, 2-5 qurilma | manual + script |
| E2E | 5 real telefon | testlar 1-10 | manual QA |

### 21.2 Minimal fizik testlar (spec'dan)

| # | Test | Kutilgan |
|---|------|----------|
| 1 | A↔B | discovery, chat, ACK |
| 2 | A→B→C | C xabar oldi, B ko'rmadi (E2E) |
| 3 | A→B→C→D | 3-hop ishlaydi (MVP: 2-hop, D bevosita) |
| 4 | B o'chirish | A→E→C→D yangi yo'l |
| 5 | D offline → online | store-and-forward |
| 6 | Dublikat yuborish | 1 marta qabul |
| 7 | Buzuq paket | xavfsiz drop, crash yo'q |
| 8 | Soxta node | authorized bo'lmasa rad etiladi |
| 9 | Battery stress | iste'mol o'lchov |
| 10 | Ko'p node | skalabillik o'lchov |

### 21.3 Benchmarks

Discovery latency · connection latency · message latency · delivery success ·
packet loss · battery · CPU/RAM · throughput · max nodes · max hops · recovery
time. `tools/bench/` da takrorlanadigan skriptlar (PHASE 1 dan boshlab log
qo'shamiz).

---

## 22. U. Skalabillik Tahlili

| Bosqich | Tugunlar | Imkoniyat | Cheklov |
|---------|----------|-----------|---------|
| 1 | 2-5 | MVP | — |
| 2 | 10-20 | Flooding yaroqli | flooding overhead o'sadi |
| 3 | 30-100 | Rank-based routing (PHASE 4) | BLE ulanish limitlari |
| 4 | 100-1000 | LoRa backbone + hierarchy | kompleks, bandwitdh |

**Aniq cheklovlar (yashirilmaydi):**
- **BLE:** bir qurilma ~7-9 GATT client — "mesh" relay orqali yumshatiladi, lekin
  har qurilma darhol 100 tugunni ko'rmaydi.
- **Wi-Fi Direct:** guruh 5-8 qurilma (Android standarti).
- **Flooding:** 20+ tugunda dublikat portlashi — shuning uchun PHASE 4'da
  rank-based (BATMAN-ish) routing.
- **Bandwidth:** BLE ~0.2-2Mbps, Wi-Fi Direct ~20-50Mbps, LoRa ~0.3-10kbps.
  LoRa faqat TEXT/SOS/metadata uchun.

---

## 23. V. Texnologiya Tanlash Taqqoslanishi

### 23.1 Transport

| | BLE | Wi-Fi Direct | LoRa |
|--|-----|--------------|------|
| Masofa | 10-50m | 50-100m | 1-15km (LOS) |
| Bandwidth | 0.2-2Mbps | 20-50Mbps | 0.3-10kbps |
| Battery | past | o'rta | juda past |
| Android qo'llab-quvvatlash | ✅ | ✅ (barchasi emas) | ❌ (serial orqali) |
| Guruh hajmi | ~7 conn | 5-8 | cheksiz |
| **Rol** | presence + fallback | asosiy xabar yo'li | long-range backbone (P8) |

### 23.2 Serializatsiya — §5.1 da. **Qaror: binary.**

### 23.3 Kripto — §10.1 da. **Qaror: X25519 + ChaCha20-Poly1305 (+Ed25519 P4).**

### 23.4 Routing — §9 da. **Qaror: MVP flooding → PHASE 4 rank-based.**

### 23.5 Storage (Android)

| | SharedPreferences | Room/SQLite | DataStore |
|--|-------------------|-------------|-----------|
| Oddiylik | ✅ | o'rta | ✅ |
| Katta ma'lumot | ❌ | ✅ | o'rta |
| Type-safety | ❌ | ✅ | ✅ |
| **Qaror** | **MVP** (hozir bor) | PHASE 5 (message history) | — |

### 23.6 State management (Flutter)

| | Riverpod | Bloc | setState |
|--|----------|------|----------|
| Testability | ✅ | ✅ | ❌ |
| Murakkablik | o'rta | yuqori | past |
| Ushbu loyiha | **✅ tanlangan** (allaqachon) | — | kichik qismlar |

### 23.7 Async (Kotlin)

| | Coroutines | RxJava | Threads |
|--|------------|--------|---------|
| Oddiylik | ✅ | o'rta | past |
| Cancelation | ✅ | ✅ | ❌ |
| **Qaror** | **✅ Coroutines** (dependency'da bor) | — | faqat soket I/O da |

---

## 24. Platforma Cheklovlari

Hozirgacha aniqlangan Android cheklovlari (PHASE 1'da eksperimental tekshiriladi):

1. **BLE GATT concurrent ulanish limiti (~7-9)** → qancha tugun "to'g'ridan
   to'g'ri" ulanishi mumkin. **Yumshatish:** lazy + LRU pool + relay.
2. **Android 10+ background BLE scan taqiq** → mesh faqat foreground service'da
   to'liq ishlaydi. **To'liq fon mesh'da platforma siyosati cheklaydi.**
3. **Reclama data limiti (31B legacy / 165B extended)** → device name +
   service UUID sig'ishi kerak. Barcha ma'lumot reklamada yuborilmaydi.
4. **ChaCha20-Poly1305 JCE API 28+** → BouncyCastle provider orqali barcha
   API'da.
5. **Wi-Fi Direct hamma qurilmada yo'q** (samarali qo'llab-quvvatlash qurilmaga
   bog'liq) → BLE mustaqil ishlashi shart.
6. **Foreground service'da battery optimization** → foydalanuvchi ruxsati,
   PHASE 9.

---

## 25. Mavjud Kod Bilan Solishtirish va Bo'shliqlar

Qurilgan kod yaxshi poydevor, lekin PHASE 1'ni boshlashdan oldin tuzatilishi
kerak bo'lgan nuqtalar:

### Kritik (build bug / ishlamaydi)

| # | Muammo | Joy | Yechim |
|---|--------|-----|--------|
| 1 | `gatt.connectedCharacteristic` — `BluetoothGatt` da bunday xususiyat yo'q → **compile error** | `BleTransport.kt:189` | `serverTxChar` ni ishlatish (BleTransport ichida e'lon qilingan) yoki GATT client characteristic saqlash |
| 2 | `peers` map hech qachon to'ldirilmaydi → `sendFrame` har doim `false` | `BleTransport.kt` | GATT client ulanish logikasi yozish (discoverServices → connect) |
| 3 | Discovery → PeerStore upsert yo'q → UI'da peer ko'rinmaydi | `TransportManager.kt:35-45` | `onPeerDiscovered` → `peerStore.upsert()` + event emit |

### Muhim (to'g'ri ishlaydi, lekin zaif)

| # | Muammo | Joy | Yechim |
|---|--------|-----|--------|
| 4 | `seenMessages` cheksiz o'sadi (expiry yo'q) — memory leak | `RoutingEngine.kt:40` | LRU + 60s expiry |
| 5 | `ttl` relay'da kamaymaydi (faqat hopLimit) | `RoutingEngine.kt:121-142` | `ttl = frame.ttl - 1`; `ttl<=0` → drop |
| 6 | Chat history faqat widget state'ida | `chat_view.dart:24` | `MessageRepository` (Riverpod Notifier) + saqlash |
| 7 | Private key SharedPreferences'da | `IdentityStore.kt:43` | PHASE 9: Keystore (MVP hujjatlashtirilgan) |
| 8 | ChaCha20 JCE API 26-27'da yo'q | `MeshCrypto.kt:68` | BouncyCastle provider |
| 9 | `MainActivity` ichida `onDestroy` → `meshEngine.stop()` — service alohida, ikkalasi ham bir engine'ni ishlatmaydi (service hozir bo'sh) | `MeshService.kt` | Engine lifecycle'ni bitta joyga birlashtirish (service engine'ni egalik qilishi kerak) |

### Strukturaviy

- `handleMethodCall` da transport tanlov kodi (`transportKey`, `transportName`)
  chalkash — `TransportManager` ichiga ko'chiriladi.
- Testlar yo'q (Kotlin JVM + Dart) — PHASE 1'da birinchi testlar.

---

## 26. Ko'rib Chiqish Uchun Ochiq Qarorlar

| # | Qaror | Variantlar | Tavsiya |
|---|-------|------------|---------|
| 1 | Node ID = hash(pubkey) ga o'tish | ha / yo'q (MVP'da UUID) | MVP: UUID; P6 da o'tish |
| 2 | minSdk 26 saqlash + BouncyCastle / minSdk 28 | 26 / 28 | 26 + BouncyCastle |
| 3 | Routing: flooding (MVP) / darhol rank-based | flooding / rank | flooding |
| 4 | Store-and-forward MVP'ga kiritsinmi | kiritsin / 5-fazada | PHASE 5 |
| 5 | Monorepo restructure | hozir / P5 | P5 |
| 6 | Service engine lifecycle birlashtirish | birik / keyin | PHASE 1 |

---

## Xulosa

MeshNet MVP'si uchun tanlangan stack:

- **Transport:** BLE (presence/fallback) + Wi-Fi Direct (asosiy), PHP uchrashma.
- **Protokol:** binary qat'iy frame (43B header + payload_len).
- **Routing:** 2-hop controlled flooding (MVP) → rank-based (PHASE 4).
- **Kripto:** X25519 (kalit almashish) + ChaCha20-Poly1305 (E2E AEAD).
- **Identity:** 128-bit UUID + X25519 static keys, QR juftlash.
- **Store-and-forward + ACK/retry:** PHASE 5 da to'liq, MVP'da delivery status.
- **App:** Flutter (Riverpod) + Kotlin (Coroutines) + Platform Channel.

**Keyingi qadam (PHASE 1):** arxitektura tasdiqlangach —
1) kritik tuzatishlar (§25), 2) A↔B real qurilmalarda sinov.

---

© 2026 MeshNet / TechCorp. Confidentially.
