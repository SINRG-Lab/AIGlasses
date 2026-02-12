#include "I2SManager.h"
#include "config.h"

I2SManager::I2SManager(int bclkPin, int wsPin, int micPin, int spkPin)
    : bclkPin(bclkPin), wsPin(wsPin), micPin(micPin), spkPin(spkPin), currentMode(I2SMode::NONE) {
}

bool I2SManager::initMicrophone(int sampleRate, int dmaBufferCount, int dmaBufferLen) {
    // Uninstall if already running
    if (currentMode != I2SMode::NONE) {
        uninstall();
        delay(50);
    }

    // Configure I2S for RX (microphone)
    i2s_config_t cfg = {};
    cfg.mode = (i2s_mode_t)(I2S_MODE_MASTER | I2S_MODE_RX);
    cfg.sample_rate = sampleRate;
    cfg.bits_per_sample = I2S_BITS_PER_SAMPLE_16BIT;
    cfg.channel_format = I2S_CHANNEL_FMT_ONLY_LEFT;
    cfg.communication_format = I2S_COMM_FORMAT_I2S;
    cfg.intr_alloc_flags = ESP_INTR_FLAG_LEVEL1;
    cfg.dma_buf_count = dmaBufferCount;
    cfg.dma_buf_len = dmaBufferLen;
    cfg.use_apll = false;
    cfg.tx_desc_auto_clear = false;
    cfg.fixed_mclk = 0;

    i2s_pin_config_t pins = {};
    pins.bck_io_num = bclkPin;
    pins.ws_io_num = wsPin;
    pins.data_out_num = I2S_PIN_NO_CHANGE;
    pins.data_in_num = micPin;

    DEBUG_PRINTLN("[I2S] Installing MIC driver...");
    esp_err_t err = i2s_driver_install(I2S_NUM_0, &cfg, 0, NULL);
    if (err != ESP_OK) {
        DEBUG_PRINTF("[I2S] MIC install failed: %d\n", (int)err);
        return false;
    }

    err = i2s_set_pin(I2S_NUM_0, &pins);
    if (err != ESP_OK) {
        DEBUG_PRINTF("[I2S] MIC pins failed: %d\n", (int)err);
        return false;
    }

    clearDMABuffer();
    currentMode = I2SMode::MICROPHONE;
    DEBUG_PRINTLN("[I2S] MIC configured OK");
    return true;
}

bool I2SManager::initSpeaker(int sampleRate, int dmaBufferCount, int dmaBufferLen) {
    // Uninstall if already running
    if (currentMode != I2SMode::NONE) {
        uninstall();
        delay(50);
    }

    // Configure I2S for TX (speaker)
    i2s_config_t cfg = {};
    cfg.mode = (i2s_mode_t)(I2S_MODE_MASTER | I2S_MODE_TX);
    cfg.sample_rate = sampleRate;
    cfg.bits_per_sample = I2S_BITS_PER_SAMPLE_16BIT;
    cfg.channel_format = I2S_CHANNEL_FMT_ONLY_LEFT;
    cfg.communication_format = I2S_COMM_FORMAT_I2S;
    cfg.intr_alloc_flags = ESP_INTR_FLAG_LEVEL1;
    cfg.dma_buf_count = dmaBufferCount;
    cfg.dma_buf_len = dmaBufferLen;
    cfg.use_apll = false;
    cfg.tx_desc_auto_clear = true;
    cfg.fixed_mclk = 0;

    i2s_pin_config_t pins = {};
    pins.bck_io_num = bclkPin;
    pins.ws_io_num = wsPin;
    pins.data_out_num = spkPin;
    pins.data_in_num = I2S_PIN_NO_CHANGE;

    DEBUG_PRINTLN("[I2S] Installing SPEAKER driver...");
    esp_err_t err = i2s_driver_install(I2S_NUM_0, &cfg, 0, NULL);
    if (err != ESP_OK) {
        DEBUG_PRINTF("[I2S] SPK install failed: %d\n", (int)err);
        return false;
    }

    err = i2s_set_pin(I2S_NUM_0, &pins);
    if (err != ESP_OK) {
        DEBUG_PRINTF("[I2S] SPK pins failed: %d\n", (int)err);
        return false;
    }

    clearDMABuffer();
    currentMode = I2SMode::SPEAKER;
    DEBUG_PRINTLN("[I2S] SPEAKER configured OK");
    return true;
}

size_t I2SManager::readMicrophone(int16_t* buffer, size_t bufferSize, uint32_t timeoutMs) {
    if (currentMode != I2SMode::MICROPHONE) {
        DEBUG_PRINTLN("[I2S] Error: Not in microphone mode");
        return 0;
    }

    size_t bytesRead = 0;
    esp_err_t err = i2s_read(I2S_NUM_0, buffer, bufferSize, &bytesRead, timeoutMs / portTICK_PERIOD_MS);

    if (err != ESP_OK) {
        DEBUG_PRINTF("[I2S] Read error: %d\n", (int)err);
        return 0;
    }

    return bytesRead;
}

size_t I2SManager::writeSpeaker(const uint8_t* data, size_t dataLen) {
    if (currentMode != I2SMode::SPEAKER) {
        DEBUG_PRINTLN("[I2S] Error: Not in speaker mode");
        return 0;
    }

    size_t written = 0;
    i2s_write(I2S_NUM_0, data, dataLen, &written, portMAX_DELAY);
    return written;
}

void I2SManager::uninstall() {
    if (currentMode != I2SMode::NONE) {
        i2s_driver_uninstall(I2S_NUM_0);
        currentMode = I2SMode::NONE;
    }
}

void I2SManager::clearDMABuffer() {
    i2s_zero_dma_buffer(I2S_NUM_0);
}
