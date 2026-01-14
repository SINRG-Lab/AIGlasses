#ifndef BLE_AUDIO_H
#define BLE_AUDIO_H

#include <stdint.h>
#include <stddef.h>
#include <functional>
#include <string>

/**
 * BLE Audio Service
 * Handles BLE initialization, advertising, notifications, and control protocol
 */
class BleAudio {
public:
    // Callback type for streaming control
    using StreamingControlCallback = std::function<void(bool enable)>;

    BleAudio();
    ~BleAudio();

    /**
     * Initialize BLE device, server, service, and characteristic
     * @return true if successful
     */
    bool init();

    /**
     * Start BLE advertising
     */
    void startAdvertising();

    /**
     * Send audio data via BLE notification
     * @param data Audio data bytes
     * @param length Number of bytes to send
     * @return true if sent successfully, false if not connected or error
     */
    bool notify(const uint8_t* data, size_t length);

    /**
     * Set callback for streaming control (START/STOP commands)
     * @param callback Function to call when streaming should start/stop
     */
    void setStreamingControlCallback(StreamingControlCallback callback);

    /**
     * Check if a client is connected
     * @return true if connected
     */
    bool isConnected() const;

private:
    bool deviceConnected;
    uint16_t connHandle;
    bool notifyEnabled;
    StreamingControlCallback streamingCallback;

    void handleCommand(const std::string& command);
    std::string getInfoString() const;
};

#endif // BLE_AUDIO_H
