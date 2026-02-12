#ifndef I2S_MANAGER_H
#define I2S_MANAGER_H

#include <Arduino.h>
#include <driver/i2s.h>

enum class I2SMode {
    NONE,
    MICROPHONE,
    SPEAKER
};

class I2SManager {
public:
    I2SManager(int bclkPin, int wsPin, int micPin, int spkPin);

    // Initialize I2S for microphone (RX mode)
    bool initMicrophone(int sampleRate, int dmaBufferCount = 8, int dmaBufferLen = 256);

    // Initialize I2S for speaker (TX mode)
    bool initSpeaker(int sampleRate, int dmaBufferCount = 8, int dmaBufferLen = 512);

    // Read audio from microphone
    size_t readMicrophone(int16_t* buffer, size_t bufferSize, uint32_t timeoutMs = 20);

    // Write audio to speaker
    size_t writeSpeaker(const uint8_t* data, size_t dataLen);

    // Get current mode
    I2SMode getCurrentMode() const { return currentMode; }

    // Uninstall driver (useful for mode switching)
    void uninstall();

private:
    const int bclkPin;
    const int wsPin;
    const int micPin;
    const int spkPin;
    I2SMode currentMode;

    void clearDMABuffer();
};

#endif // I2S_MANAGER_H
