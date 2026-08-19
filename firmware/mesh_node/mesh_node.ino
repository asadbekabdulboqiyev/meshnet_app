/*
 * MeshNet — ESP32-C3 BLE relay node (PHASE 7)
 *
 * Maqsad: telefonlar orasida "shaffof repeater" — Android app'siz ham
 * xabarlarni uzatish. App bilan to'liq wire-kompatibil:
 *  - GATT servis/char: BleTransport.kt bilan bir xil UUID
 *  - Reklama (MFG data): 0x4D4E LE + MARKER + 16B node deviceId
 *  - Frame: MESH_PROTOCOL.md §3 (MeshFrame.kt encode/decode)
 *  - Har bir BLE write = bitta to'liq frame (≤244 bayt), qayta yig'ish yo'q
 *
 * Oqim:  Phone A --write--> node RX char --(dedup/ttl)---> node --> Phone B
 *        (node = GATT server)                       (node = GATT client)
 *
 * Node o'zi xabar yaratmaydi va ochmaydi (E2E shifrlangan) — faqat uzatadi.
 *
 * ESP32-C3 build'i NimBLE stack'idan foydalanadi (CONFIG_BT_NIMBLE_ENABLED):
 *  - MTU avtomatik 256 gacha kelishiladi (244 baytli frame sig'adi)
 *  - MAX_CONNECTIONS=3 -> telefonga ulanishlar soni cheklangan
 *  - onWrite/onConnect callback'lariga ble_gap_conn_desc* uzatiladi
 */

#include <Arduino.h>
#include <BLEAdvertisedDevice.h>
#include <BLEClient.h>
#include <BLEDevice.h>
#include <BLEUtils.h>
#include <host/ble_gap.h>

#include "mesh_frame.h"
#include "relay.h"

// ---------------- GATT kontrakti (BleTransport.kt bilan bir xil) ----------------
static const char *SERVICE_UUID = "6a4e9f01-1d5b-4f1a-8f2b-2e75a4b8c0d1";
static const char *TX_CHAR_UUID = "6a4e9f02-1d5b-4f1a-8f2b-2e75a4b8c0d1";
static const char *RX_CHAR_UUID = "6a4e9f03-1d5b-4f1a-8f2b-2e75a4b8c0d1";

// Reklama: [4E 4D][4D 4E][nodeId(16)]  (0x4D4E LE + MARKER + id)
#define MFG_B0 0x4E
#define MFG_B1 0x4D
#define MFG_B2 0x4D
#define MFG_B3 0x4E
#define MFG_TOTAL (4 + MESH_ID_BYTES) /* 20 */

// NimBLE MAX_CONNECTIONS=3 -> server + client linklari jami 3 bo'lishi mumkin
#define MAX_PHONE_CLIENTS 2

static uint8_t nodeId[MESH_ID_BYTES];
static relay_ctx_t relayCtx;

// ---------------- Server tomoni: telefonlar node'ga ulanadi ----------------
static BLEServer *pServer = nullptr;
static BLECharacteristic *rxChar = nullptr;

class ServerCallbacks : public BLEServerCallbacks {
    void onConnect(BLEServer *srv) override {
        Serial.printf("[srv] telefon ulandi (ulanishlar: %d)\n", srv->getConnectedCount());
    }
    void onDisconnect(BLEServer *srv) override {
        Serial.printf("[srv] telefon uzildi\n");
    }
};

// ---------------- Client tomoni: node telefonlarga ulanadi ----------------
struct phone_client_t {
    bool used;
    bool ready;             // ulangan + RX char topildi
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
        Serial.printf("[clt] ulandi: %s\n", c->getPeerAddress().toString().c_str());
    }
    void onDisconnect(BLEClient *c) override {
        for (int i = 0; i < MAX_PHONE_CLIENTS; i++) {
            if (phones[i].used && phones[i].client == c) {
                phones[i].ready = false;
                Serial.printf("[clt] uzildi: %s\n", c->getPeerAddress().toString().c_str());
            }
        }
    }
};

// ---------------- Relay: RX'ga write kelganda ----------------
class RxCallbacks : public BLECharacteristicCallbacks {
    // NimBLE: onWrite param o'rniga ble_gap_conn_desc* beriladi (manba addres uchun)
    void onWrite(BLECharacteristic *c, ble_gap_conn_desc *desc) override {
        String data = c->getValue();
        if (data.length() == 0) return;

        mesh_frame_t f;
        if (!mesh_frame_parse((const uint8_t *)data.c_str(), data.length(), &f)) {
            Serial.println("[relay] yaroqsiz frame tashlandi");
            return;
        }

        int reason = 0;
        uint32_t now = millis();
        if (!relay_decide(&relayCtx, &f, now, &reason)) {
            Serial.printf("[relay] tashlandi reason=%d\n", reason);
            return;
        }

        relay_decrement_ttl(&f);

        uint8_t out[MESH_MAX_FRAME];
        size_t n = mesh_frame_encode(&f, out, sizeof(out));
        if (n == 0) return;

        // Manbani aniqlaymiz — unga qaytarib yubormaymiz
        uint8_t *srcAddr = desc->peer_id_addr.val;

        int fwd = 0;
        for (int i = 0; i < MAX_PHONE_CLIENTS; i++) {
            phone_client_t &p = phones[i];
            if (!p.used || !p.ready || p.outLen > 0) continue;
            if (memcmp(p.addr, srcAddr, 6) == 0) continue; // manbaga qaytarilmaydi
            memcpy(p.outBuf, out, n);
            p.outLen = n;
            fwd++;
        }
        Serial.printf("[relay] forward %d telefon(ga) (%d bayt)\n", fwd, (int)n);
    }
};

static ServerCallbacks serverCbs;
static ClientCallbacks clientCbs;
static RxCallbacks rxCbs;

// ---------------- Reklama ----------------
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

// ---------------- Client: telefonlar bilan ishlash ----------------
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

    Serial.printf("[clt] ulanmoqda: %s\n", addr.toString().c_str());
    if (!p.client->connect(addr, addrType, 8000)) {
        p.client->disconnect();
        delete p.client; // BLEDevice::createClient() heap'da yaratadi
        p.client = nullptr;
        p.used = false;
        Serial.println("[clt] ulanish muvaffaqiyatsiz");
        return;
    }

    // Katta MTU so'raymiz (244 baytli frame bitta write'da o'tishi uchun)
    p.client->setMTU(512);

    BLERemoteService *svc = p.client->getService(BLEUUID(SERVICE_UUID));
    if (svc != nullptr) {
        p.rx = svc->getCharacteristic(BLEUUID(RX_CHAR_UUID));
    }
    if (p.rx == nullptr) {
        Serial.println("[clt] RX char topilmadi — qurilma app emas");
        p.client->disconnect();
        delete p.client;
        p.client = nullptr;
        p.used = false;
        return;
    }
    p.ready = true;
    Serial.printf("[clt] tayyor: %s\n", addr.toString().c_str());
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
        // O'zimizni hisobga olmaymiz
        if (mesh_frame_id_equals(devId, nodeId)) continue;

        if (findPhoneByAddr(dev.getAddress().getNative()) >= 0) continue; // allaqachon bor

        if (findPhoneSlot() < 0) break; // bo'sh slot yo'q

        connectToPhone(dev.getAddress(), dev.getAddressType(), devId);
        found++;
    }
    scan->clearResults();
    Serial.printf("[scan] %d ta yangi telefon ulandi\n", found);
}

static void drainOutgoing() {
    for (int i = 0; i < MAX_PHONE_CLIENTS; i++) {
        phone_client_t &p = phones[i];
        if (!p.used || !p.ready || p.outLen == 0) continue;
        if (p.rx == nullptr) continue;
        if (p.rx->writeValue(p.outBuf, p.outLen, true)) {
            Serial.printf("[clt] %d bayt yuborildi\n", (int)p.outLen);
        } else {
            Serial.println("[clt] write muvaffaqiyatsiz");
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
    // NimBLE MTU avtomatik 256 gacha kelishiladi — 244 baytli frame sig'adi

    pServer = BLEDevice::createServer();
    pServer->setCallbacks(&serverCbs);

    BLEService *svc = pServer->createService(BLEUUID(SERVICE_UUID));
    rxChar = svc->createCharacteristic(
        BLEUUID(RX_CHAR_UUID),
        BLECharacteristic::PROPERTY_WRITE | BLECharacteristic::PROPERTY_WRITE_NR);
    rxChar->setCallbacks(&rxCbs);
    // TX char (kelajakda notify uchun) — hozir o'qish mumkin
    svc->createCharacteristic(BLEUUID(TX_CHAR_UUID), BLECharacteristic::PROPERTY_READ);
    svc->start();

    pServer->getAdvertising()->addServiceUUID(BLEUUID(SERVICE_UUID));
    startAdvertising();

    Serial.println("[node] tayyor");
}

void loop() {
    // 1) Telefonlarga ulanish (har ~8s)
    static uint32_t lastScan = 0;
    if (millis() - lastScan > 8000) {
        lastScan = millis();
        scanAndConnect();
    }

    // 2) Navbatdagi framelarni telefonlarga yozish
    drainOutgoing();

    delay(50);
}
