#include "BLEManager.h"
#include "config.h"

// ════════════════════════════════════════════════════════════════
// BLE Callback Classes
// ════════════════════════════════════════════════════════════════

class MyBLEServerCallbacks : public NimBLEServerCallbacks {
private:
    BLEManager* manager;

public:
    MyBLEServerCallbacks(BLEManager* mgr) : manager(mgr) {}

    void onConnect(NimBLEServer* pServer, NimBLEConnInfo& connInfo) override {
        DEBUG_PRINTLN("[BLE] Android connected!");
        manager->handleConnection(true);

        // Request fast connection for audio
        pServer->updateConnParams(connInfo.getConnHandle(),
                                 BLE_CONN_INTERVAL_MIN,
                                 BLE_CONN_INTERVAL_MAX,
                                 BLE_CONN_LATENCY,
                                 BLE_CONN_TIMEOUT);
        DEBUG_PRINTLN("[BLE] Requested fast connection parameters");
    }

    void onDisconnect(NimBLEServer* pServer, NimBLEConnInfo& connInfo, int reason) override {
        DEBUG_PRINTF("[BLE] Android disconnected (reason=%d)\n", reason);
        manager->handleConnection(false);
        NimBLEDevice::startAdvertising();
        DEBUG_PRINTLN("[BLE] Advertising restarted");
    }

    void onMTUChange(uint16_t mtu, NimBLEConnInfo& connInfo) override {
        DEBUG_PRINTF("[BLE] MTU changed to %u\n", mtu);
    }
};

class MyBLEAudioRxCallbacks : public NimBLECharacteristicCallbacks {
private:
    BLEManager* manager;

public:
    MyBLEAudioRxCallbacks(BLEManager* mgr) : manager(mgr) {}

    void onWrite(NimBLECharacteristic* pChar, NimBLEConnInfo& connInfo) override {
        NimBLEAttValue val = pChar->getValue();
        const uint8_t* data = val.data();
        size_t len = val.size();

        if (len < BLE_HEADER_SIZE) return;

        uint8_t tag = data[0];
        const uint8_t* payload = data + BLE_HEADER_SIZE;
        size_t payloadLen = len - BLE_HEADER_SIZE;

        if (tag == 'A' && payloadLen > 0) {
            manager->handleAudioData(payload, payloadLen);
        }
    }
};

class MyBLEControlCallbacks : public NimBLECharacteristicCallbacks {
private:
    BLEManager* manager;

public:
    MyBLEControlCallbacks(BLEManager* mgr) : manager(mgr) {}

    void onWrite(NimBLECharacteristic* pChar, NimBLEConnInfo& connInfo) override {
        NimBLEAttValue val = pChar->getValue();
        const uint8_t* data = val.data();
        size_t len = val.size();

        if (len < 1) return;
        manager->handleControl(data[0]);
    }
};

// ════════════════════════════════════════════════════════════════
// BLEManager Implementation
// ════════════════════════════════════════════════════════════════

BLEManager::BLEManager(const char* deviceName, uint16_t mtu)
    : deviceName(deviceName), mtu(mtu), pServer(nullptr),
      pAudioTxChar(nullptr), pAudioRxChar(nullptr), pControlChar(nullptr),
      connected(false), txSeqNum(0) {
}

bool BLEManager::begin() {
    DEBUG_PRINTLN("[BLE] Initializing...");

    NimBLEDevice::init(deviceName);
    NimBLEDevice::setMTU(mtu);
    NimBLEDevice::setPower(ESP_PWR_LVL_P9);

    pServer = NimBLEDevice::createServer();
    pServer->setCallbacks(new MyBLEServerCallbacks(this));

    // Create GATT service
    NimBLEService* pService = pServer->createService(SERVICE_UUID);

    // Audio TX: ESP32 → Android (NOTIFY)
    pAudioTxChar = pService->createCharacteristic(
        CHAR_AUDIO_TX_UUID,
        NIMBLE_PROPERTY::NOTIFY
    );

    // Audio RX: Android → ESP32 (WRITE without response)
    pAudioRxChar = pService->createCharacteristic(
        CHAR_AUDIO_RX_UUID,
        NIMBLE_PROPERTY::WRITE_NR
    );
    pAudioRxChar->setCallbacks(new MyBLEAudioRxCallbacks(this));

    // Control: Bidirectional (WRITE + NOTIFY)
    pControlChar = pService->createCharacteristic(
        CHAR_CONTROL_UUID,
        NIMBLE_PROPERTY::WRITE | NIMBLE_PROPERTY::NOTIFY
    );
    pControlChar->setCallbacks(new MyBLEControlCallbacks(this));

    pService->start();

    // Start advertising
    NimBLEAdvertising* pAdvertising = NimBLEDevice::getAdvertising();
    pAdvertising->addServiceUUID(SERVICE_UUID);
    pAdvertising->start();

    DEBUG_PRINTF("[BLE] Started as '%s'\n", deviceName);
    return true;
}

void BLEManager::sendAudioData(const uint8_t* data, size_t len) {
    if (!connected || !pAudioTxChar) return;

    size_t offset = 0;
    while (offset < len) {
        size_t fragSize = min((size_t)BLE_MAX_PAYLOAD, len - offset);

        uint8_t pkt[BLE_HEADER_SIZE + fragSize];
        pkt[0] = 'A';
        pkt[1] = txSeqNum++;
        memcpy(pkt + BLE_HEADER_SIZE, data + offset, fragSize);

        pAudioTxChar->setValue(pkt, BLE_HEADER_SIZE + fragSize);
        pAudioTxChar->notify();

        offset += fragSize;
        delay(2);
    }
}

void BLEManager::sendControl(uint8_t tag) {
    if (!connected || !pControlChar) return;

    uint8_t pkt[BLE_HEADER_SIZE] = {tag, 0};
    pControlChar->setValue(pkt, BLE_HEADER_SIZE);
    pControlChar->notify();
}

void BLEManager::handleConnection(bool state) {
    connected = state;
    if (connectionCallback) {
        connectionCallback(state);
    }
}

void BLEManager::handleAudioData(const uint8_t* data, size_t len) {
    if (audioRxCallback) {
        audioRxCallback(data, len);
    }
}

void BLEManager::handleControl(uint8_t tag) {
    if (controlCallback) {
        controlCallback(tag);
    }
}
