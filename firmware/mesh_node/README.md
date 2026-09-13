# MeshNet ESP32-C3 Relay Node (PHASE 7)

Firmware for a **transparent repeater** that relays messages between phones on
the MeshNet mesh network. It does not create or decrypt messages (E2E encrypted)
— it only receives, deduplicates, decrements the TTL, and forwards.

## Architecture

```
 Phone A --write--> node RX char --(dedup/ttl)--> node --> Phone B
 (GATT client)  node = GATT server    (GATT client)   node --> Phone B
```

- Node as **GATT server**: phones write to the RX char (each write = one complete
  frame, ≤244 bytes).
- Node as **GATT client**: scans for phones (MFG data), connects, and forwards
  frames.
- Frames are not sent back to the source phone.
- The node deviceId is derived from the ESP32 MAC: `[0..1]='m''n'`, `[2..7]=MAC`,
  `[12..15]={4D 4E 0E 01}`.

## Wire compatibility (with the Android app)

| | Value |
|---|---|
| SERVICE | `6a4e9f01-1d5b-4f1a-8f2b-2e75a4b8c0d1` |
| TX char | `6a4e9f02-1d5b-4f1a-8f2b-2e75a4b8c0d1` |
| RX char | `6a4e9f03-1d5b-4f1a-8f2b-2e75a4b8c0d1` |
| Frame | 47-byte header + payload (`MESH_PROTOCOL.md` §3) |
| Advertising | MFG data `[4E 4D][4D 4E][nodeId(16)]` |

## Requirements

- arduino-cli (>= 1.0) + ESP32 core 3.3.x:

```sh
arduino-cli core update-index
arduino-cli core install esp32:esp32
```

## Build and flash

```sh
# Compile (ESP32-C3):
arduino-cli compile --fqbn esp32:esp32:esp32c3 firmware/mesh_node

# Flash (via USB-UART):
arduino-cli upload -p /dev/tty.usbmodem* --fqbn esp32:esp32:esp32c3 firmware/mesh_node

# Serial log:
arduino-cli monitor -p /dev/tty.usbmodem* -c baudrate=115200
```

## Files

| File | Purpose |
|------|--------|
| `mesh_frame.h/c` | Wire format parse/encode (portable C) |
| `relay.h/c` | Dedup `(sender,seq)` for 60s + TTL relay rule |
| `mesh_node.ino` | BLE server + client + relay glue (NimBLE) |
| `test/test_relay.c` | Host tests — `make run` (7/7 PASS) |

## Limitations

- ESP32-C3 NimBLE `MAX_CONNECTIONS=3` → client connections to phones are limited
  to `MAX_PHONE_CLIENTS=2`.
- MTU auto-negotiates up to 256 bytes — a 244-byte frame fits.
- The node presents itself to the phone as **not an app** — the connection is
  dropped if the RX char is not found.