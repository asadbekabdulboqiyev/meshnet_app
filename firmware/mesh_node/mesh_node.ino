/*
 * MeshNet — ESP32-C3 BLE relay node (PHASE 7)
 *
 * Purpose: a "transparent repeater" between phones — relays messages even
 * without the Android app. Fully wire-compatible with the app:
 *  - GATT service/char: same UUIDs as BleTransport.kt
 *  - Advertising (MFG data): 0x4D4E LE + MARKER + 16B node deviceId
 *  - Frame: MESH_PROTOCOL.md §3 (MeshFrame.kt encode/decode)
 *  - Each BLE write = one complete frame (≤244 bytes), no reassembly
 *
 * Flow:  Phone A --write--> node RX char --(dedup/ttl)---> node --> Phone B
 *        (node = GATT server)                       (node = GATT client)
 *
 * The node does not create or decrypt messages (E2E encrypted) — it only relays.
 *
 * The ESP32-C3 build uses the NimBLE stack (CONFIG_BT_NIMBLE_ENABLED):
 *  - MTU auto-negotiated up to 256 (a 244-byte frame fits)
 *  - MAX_CONNECTIONS=3 -> number of phone connections is limited
 *  - ble_gap_conn_desc* is passed to onWrite/onConnect callbacks
 */

#include <Arduino.h>
#include <BLEAdvertisedDevice.h>
#include <BLEClient.h>
#include <BLEDevice.h>
#include <BLEUtils.h>
#include <host/ble_gap.h>

#include "mesh_frame.h"
#include "relay.h"

// ---------------- GATT contract (identical to BleTransport.kt) ----------------
static const char *SERVICE_UUID = "6a4e9f01-1d5b-4f1a-8f2b-2e75a4b8c0d1";
static const char *TX_CHAR_UUID = "6a4e9f02-1d5b-4f1a-8f2b-2e75a4b8c0d1";
static const char *RX_CHAR_UUID = "6a4e9f03-1d5b-4f1a-8f2b-2e75a4b8c0d1";

// Advertising: [4E 4D][4D 4E][nodeId(16)]  (0x4D4E LE + MARKER + id)
#define MFG_B0 0x4E
#define MFG_B1 0x4D
#define MFG_B2 0x4D
#define MFG_B3 0x4E
#define MFG_TOTAL (4 + MESH_ID_BYTES) /* 20 */

// NimBLE MAX_CONNECTIONS=3 -> server + client links total 3
#define MAX_PHONE_CLIENTS 2

static uint8_t nodeId[MESH_ID_BYTES];
static relay_ctx_t relayCtx;

// ---------------- Server side: phones connect to the node ----------------
static BLEServer *pServer = nullptr;
static BLECharacteristic *rxChar = nullptr;

class ServerCallbacks : public BLEServerCallbacks {
    void onConnect(BLEServer *srv) override {
        Serial.printf("[srv] phone connected (connections: %d)\n", srv->getConnectedCount());
    }
    void onDisconnect(BLEServer *srv) override {
        Serial.printf("[srv] phone disconnected\n");
    }
};

// ---------------- Client side: the node connects to phones ----------------
struct phone_client_t {
    bool used;
    bool ready;             // connected + RX char found
    uint8_t addr[6];
    uint8_t deviceId[MESH_ID_BYTES];
    BLEClient *client;
    BLERemoteCharacteristic *rx;
    uint8_t outBuf[MESH_MAX_FRAME];
    size_t outLen;
};
static phone_client_t phones[MAX_PHONE_CLIENTS];

class ClientCallbacks : public BLEClientCallbacks {
    void onConnect(BLEClient *c) override {
        Serial.printf("[clt] connected: %s\n", c->getPeerAddress().toString().c_str());
    }
    void onDisconnect(BLEClient *c) override {
        for (int i = 0; i < MAX_PHONE_CLIENTS; i++) {
            if (phones[i].used && phones[i].client == c) {
                phones[i].ready = false;
                Serial.printf("[clt] disconnected: %s\n", c->getPeerAddress().toString().c_str());
            }
        }
    }
};

// ---------------- Relay: when a write arrives on RX ----------------
class RxCallbacks : public BLECharacteristicCallbacks {
    // NimBLE: ble_gap_conn_desc* is passed instead of the onWrite param (for source address)
    void onWrite(BLECharacteristic *c, ble_gap_conn_desc *desc) override {
        String data = c->getValue();
        if (data.length() == 0) return;

        mesh_frame_t f;
        if (!mesh_frame_parse((const uint8_t *)data.c_str(), data.length(), &f)) {
            Serial.println("[relay] invalid frame dropped");
            return;
        }

        int reason = 0;
        uint32_t now = millis();
        if (!relay_decide(&relayCtx, &f, now, &reason)) {
            Serial.printf("[relay] dropped reason=%d\n", reason);
            return;
        }

        relay_decrement_ttl(&f);

        uint8_t out[MESH_MAX_FRAME];
        size_t n = mesh_frame_encode(&f, out, sizeof(out));
        if (n == 0) return;

        // Identify the source — do not relay back to it
        uint8_t *srcAddr = desc->peer_id_addr.val;

        int fwd = 0;
        for (int i = 0; i < MAX_PHONE_CLIENTS; i++) {
            phone_client_t &p = phones[i];
            if (!p.used || !p.ready || p.outLen > 0) continue;
            if (memcmp(p.addr, srcAddr, 6) == 0) continue; // not sent back to source
            memcpy(p.outBuf, out, n);
            p.outLen = n;
            fwd++;
        }
        Serial.printf("[relay] forwarded to %d phone(s) (%d bytes)\n", fwd, (int)n);
    }
};

static ServerCallbacks serverCbs;
static ClientCallbacks clientCbs;
static RxCallbacks rxCbs;

// ---------------- Advertising ----------------
static void startAdvertising() {
    uint8_t mfg[MFG_TOTAL];
    mfg[0] = MFG_B0; mfg[1] = MFG_B1; mfg[2] = MFG_B2; mfg[3] = MFG_B3;
    memcpy(mfg + 4, nodeId, MESH_ID_BYTES);

    BLEAdvertising *adv = BLEDevice::getAdvertising();
    BLEAdvertisementData advData;
    advData.setManufacturerData(String((const char *)mfg, MFG_TOTAL));
    advData.setName("MeshNode");
    adv->setAdvertisementData(advData);
    adv->setScanResponseData(advData);
    adv->start();
}

// ---------------- Client: working with phones ----------------
static int findPhoneSlot() {
    for (int i = 0; i < MAX_PHONE_CLIENTS; i++) {
        if (!phones[i].used) return i;
    }
    return -1;
}

static int findPhoneByAddr(const uint8_t addr[6]) {
    for (int i = 0; i < MAX_PHONE_CLIENTS; i++) {
        if (phones[i].used && memcmp(phones[i].addr, addr, 6) == 0) return i;
    }
    return -1;
}

static void connectToPhone(BLEAddress addr, uint8_t addrType,
                           const uint8_t deviceId[16]) {
    int slot = findPhoneSlot();
    if (slot < 0) return;

    phone_client_t &p = phones[slot];
    p.used = true;
    p.ready = false;
    p.outLen = 0;
    memcpy(p.addr, addr.getNative(), 6);
    memcpy(p.deviceId, deviceId, MESH_ID_BYTES);

    p.client = BLEDevice::createClient();
    p.client->setClientCallbacks(&clientCbs);
    p.rx = nullptr;

    Serial.printf("[clt] connecting: %s\n", addr.toString().c_str());
    if (!p.client->connect(addr, addrType, 8000)) {
        p.client->disconnect();
        delete p.client; // BLEDevice::createClient() allocates on the heap
        p.client = nullptr;
        p.used = false;
        Serial.println("[clt] connection failed");
        return;
    }

    // Request a large MTU (so a 244-byte frame fits in a single write)
    p.client->setMTU(512);

    BLERemoteService *svc = p.client->getService(BLEUUID(SERVICE_UUID));
    if (svc != nullptr) {
        p.rx = svc->getCharacteristic(BLEUUID(RX_CHAR_UUID));
    }
    if (p.rx == nullptr) {
        Serial.println("[clt] RX char not found — device is not the app");
        p.client->disconnect();
        delete p.client;
        p.client = nullptr;
        p.used = false;
        return;
    }
    p.ready = true;
    Serial.printf("[clt] ready: %s\n", addr.toString().c_str());
}

static void scanAndConnect() {
    BLEScan *scan = BLEDevice::getScan();
    scan->setAdvertisedDeviceCallbacks(nullptr);
    scan->setActiveScan(false);
    scan->setInterval(100);
    scan->setWindow(50);

    BLEScanResults *results = scan->start(2, false);
    int found = 0;
    for (int i = 0; i < results->getCount(); i++) {
        BLEAdvertisedDevice dev = results->getDevice(i);
        String mfg = dev.getManufacturerData();
        if ((int)mfg.length() < MFG_TOTAL) continue;
        const uint8_t *b = (const uint8_t *)mfg.c_str();
        if (!(b[0] == MFG_B0 && b[1] == MFG_B1 && b[2] == MFG_B2 && b[3] == MFG_B3)) continue;

        uint8_t devId[MESH_ID_BYTES];
        memcpy(devId, b + 4, MESH_ID_BYTES);
        // Ignore ourselves
        if (mesh_frame_id_equals(devId, nodeId)) continue;

        if (findPhoneByAddr(dev.getAddress().getNative()) >= 0) continue; // already connected

        if (findPhoneSlot() < 0) break; // no free slot

        connectToPhone(dev.getAddress(), dev.getAddressType(), devId);
        found++;
    }
    scan->clearResults();
    Serial.printf("[scan] connected to %d new phone(s)\n", found);
}

static void drainOutgoing() {
    for (int i = 0; i < MAX_PHONE_CLIENTS; i++) {
        phone_client_t &p = phones[i];
        if (!p.used || !p.ready || p.outLen == 0) continue;
        if (p.rx == nullptr) continue;
        if (p.rx->writeValue(p.outBuf, p.outLen, true)) {
            Serial.printf("[clt] sent %d bytes\n", (int)p.outLen);
        } else {
            Serial.println("[clt] write failed");
        }
        p.outLen = 0;
    }
}

// ---------------- Setup / Loop ----------------
void setup() {
    Serial.begin(115200);
    delay(500);

    mesh_node_generate_id(nodeId);
    relay_init(&relayCtx);
    for (int i = 0; i < MAX_PHONE_CLIENTS; i++) phones[i].used = false;

    Serial.printf("MeshNode deviceId: %02X%02X%02X%02X...\n",
                  nodeId[0], nodeId[1], nodeId[2], nodeId[3]);

    BLEDevice::init("MeshNode");
    // NimBLE MTU auto-negotiates to up to 256 — a 244-byte frame fits

    pServer = BLEDevice::createServer();
    pServer->setCallbacks(&serverCbs);

    BLEService *svc = pServer->createService(BLEUUID(SERVICE_UUID));
    rxChar = svc->createCharacteristic(
        BLEUUID(RX_CHAR_UUID),
        BLECharacteristic::PROPERTY_WRITE | BLECharacteristic::PROPERTY_WRITE_NR);
    rxChar->setCallbacks(&rxCbs);
    // TX char (for future notify) — currently readable
    svc->createCharacteristic(BLEUUID(TX_CHAR_UUID), BLECharacteristic::PROPERTY_READ);
    svc->start();

    pServer->getAdvertising()->addServiceUUID(BLEUUID(SERVICE_UUID));
    startAdvertising();

    Serial.println("[node] ready");
}

void loop() {
    // 1) Connect to phones (every ~8s)
    static uint32_t lastScan = 0;
    if (millis() - lastScan > 8000) {
        lastScan = millis();
        scanAndConnect();
    }

    // 2) Write queued frames to phones
    drainOutgoing();

    delay(50);
}
