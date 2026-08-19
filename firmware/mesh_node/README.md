# MeshNet ESP32-C3 Relay Node (PHASE 7)

MeshNet mesh tarmog'ida telefonlar orasidagi xabarlarni uzatadigan **shaffof
repeater** firmware'si. Xabarlarni yaratmaydi va ochmaydi (E2E shifrlangan) —
faqat qabul qiladi, dublikatdan tozalaydi, TTLni pasaytiradi va uzatadi.

## Arxitektura

```
 Phone A --write--> node RX char --(dedup/ttl)--> node --> Phone B
 (GATT client)  node = GATT server    (GATT client)   node --> Phone B
```

- Node **GATT server**: telefonlar RX char'ga yozadi (har write = bitta to'liq
  frame, ≤244 bayt).
- Node **GATT client**: telefonlarni skanerlaydi (MFG data), ulanadi va
  framelarni uzatadi.
- Manba telefoniga qayta yuborilmaydi.
- Node deviceId ESP32 MAC dan hosil qilinadi: `[0..1]='m''n'`, `[2..7]=MAC`,
  `[12..15]={4D 4E 0E 01}`.

## Wire-kompatibil (Android app bilan)

| | Qiymat |
|---|---|
| SERVICE | `6a4e9f01-1d5b-4f1a-8f2b-2e75a4b8c0d1` |
| TX char | `6a4e9f02-1d5b-4f1a-8f2b-2e75a4b8c0d1` |
| RX char | `6a4e9f03-1d5b-4f1a-8f2b-2e75a4b8c0d1` |
| Frame | 47 bayt header + payload (`MESH_PROTOCOL.md` §3) |
| Reklama | MFG data `[4E 4D][4D 4E][nodeId(16)]` |

## Talablar

- arduino-cli (>= 1.0) + ESP32 core 3.3.x:

```sh
arduino-cli core update-index
arduino-cli core install esp32:esp32
```

## Build va flash

```sh
# Kompilyatsiya (ESP32-C3):
arduino-cli compile --fqbn esp32:esp32:esp32c3 firmware/mesh_node

# Flash (USB-UART orqali):
arduino-cli upload -p /dev/tty.usbmodem* --fqbn esp32:esp32:esp32c3 firmware/mesh_node

# Serial log:
arduino-cli monitor -p /dev/tty.usbmodem* -c baudrate=115200
```

## Fayllar

| Fayl | Vazifa |
|------|--------|
| `mesh_frame.h/c` | Wire format parse/encode (portable C) |
| `relay.h/c` | Dedup `(sender,seq)` 60s + TTL relay qoidasi |
| `mesh_node.ino` | BLE server + client + relay glue (NimBLE) |
| `test/test_relay.c` | Host (xost) testlar — `make run` (7/7 PASS) |

## Cheklovlar

- ESP32-C3 NimBLE `MAX_CONNECTIONS=3` → telefonga client ulanishlar soni
  `MAX_PHONE_CLIENTS=2` qilib cheklangan.
- MTU avtomatik 256 baytgacha — 244 baytli frame sig'adi.
- Node o'zini telefonga **app emas** — RX char topilmasa ulanish bekor qilinadi.
