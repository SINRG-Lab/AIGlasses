#ifndef BLE_MANAGER_H
#define BLE_MANAGER_H

#include <NimBLEDevice.h>
#include <functional>

// Callback types
using AudioDataCallback = std::function<void(const uint8_t*, size_t)>;
using ControlCallback = std::function<void(uint8_t)>;
using ConnectionCallback = std::function<void(bool)>;

class BLEManager {
public:
    BLEManager(const char* deviceName, uint16_t mtu);

    // Initialize BLE
    bool begin();

    // Set callbacks
    void onAudioReceived(AudioDataCallback callback) { audioRxCallback = callback; }
    void onControlReceived(ControlCallback callback) { controlCallback = callback; }
    void onConnectionChange(ConnectionCallback callback) { connectionCallback = callback; }

    // Send audio to Android (NOTIFY)
    void sendAudioData(const uint8_t* data, size_t len);

    // Send control message
    void sendControl(uint8_t tag);

    // Connection status
    bool isConnected() const { return connected; }

    // Friend classes for callbacks
    friend class MyBLEServerCallbacks;
    friend class MyBLEAudioRxCallbacks;
    friend class MyBLEControlCallbacks;

private:
    const char* deviceName;
    const uint16_t mtu;

    NimBLEServer* pServer;
    NimBLECharacteristic* pAudioTxChar;
    NimBLECharacteristic* pAudioRxChar;
    NimBLECharacteristic* pControlChar;

    volatile bool connected;
    uint8_t txSeqNum;

    AudioDataCallback audioRxCallback;
    ControlCallback controlCallback;
    ConnectionCallback connectionCallback;

    void handleConnection(bool state);
    void handleAudioData(const uint8_t* data, size_t len);
    void handleControl(uint8_t tag);
};

#endif // BLE_MANAGER_H
