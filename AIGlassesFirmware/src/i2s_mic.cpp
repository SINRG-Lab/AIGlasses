#include "i2s_mic.h"
#include "config.h"
#include <esp_err.h>
#include <esp_log.h>
#include <freertos/FreeRTOS.h>
#include <freertos/task.h>


I2SMic::I2SMic() : initialized(false), port(I2S_PORT_NUM) {
}

I2SMic::~I2SMic() {
    stop();
}

bool I2SMic::init() {
    if (initialized) {
        return true;
    }

    const i2s_config_t i2s_config = {
        .mode = i2s_mode_t(I2S_MODE_MASTER | I2S_MODE_RX),
        .sample_rate = AUDIO_SAMPLE_RATE,
        .bits_per_sample = i2s_bits_per_sample_t(AUDIO_BITS_PER_SAMPLE),
        .channel_format = AUDIO_CHANNEL_FORMAT,
        .communication_format = i2s_comm_format_t(I2S_COMM_FORMAT_STAND_I2S),
        .intr_alloc_flags = 0,
        .dma_buf_count = 4,
        .dma_buf_len = AUDIO_BUFFER_LEN,
        .use_apll = false
    };

    esp_err_t err = i2s_driver_install(port, &i2s_config, 0, NULL);
    if (err != ESP_OK) {
#if DEBUG_ENABLED
        ESP_LOGE("i2s_mic", "I2S driver install failed: %s", esp_err_to_name(err));
#endif
        return false;
    }

    const i2s_pin_config_t pin_config = {
        .bck_io_num = I2S_SCK_PIN,
        .ws_io_num = I2S_WS_PIN,
        .data_out_num = -1,
        .data_in_num = I2S_SD_PIN
    };

    err = i2s_set_pin(port, &pin_config);
    if (err != ESP_OK) {
#if DEBUG_ENABLED
        ESP_LOGE("i2s_mic", "I2S pin config failed: %s", esp_err_to_name(err));
#endif
        i2s_driver_uninstall(port);
        return false;
    }

    err = i2s_start(port);
    if (err != ESP_OK) {
#if DEBUG_ENABLED
        ESP_LOGE("i2s_mic", "I2S start failed: %s", esp_err_to_name(err));
#endif
        i2s_driver_uninstall(port);
        return false;
    }

    initialized = true;
#if DEBUG_ENABLED
    ESP_LOGI("i2s_mic", "I2S microphone initialized");
#endif
    return true;
}

size_t I2SMic::read(int16_t* buffer, size_t buffer_len) {
    if (!initialized || buffer == nullptr || buffer_len == 0) {
        return 0;
    }

    size_t bytes_read = 0;
    size_t bytes_to_read = buffer_len * sizeof(int16_t);
    
    esp_err_t res = i2s_read(port, (void*)buffer, bytes_to_read, &bytes_read, portMAX_DELAY);
    
    if (res != ESP_OK) {
#if DEBUG_ENABLED
        ESP_LOGE("i2s_mic", "I2S read error: %s", esp_err_to_name(res));
#endif
        return 0;
    }

    return bytes_read / sizeof(int16_t);
}

void I2SMic::stop() {
    if (initialized) {
        i2s_stop(port);
        i2s_driver_uninstall(port);
        initialized = false;
#if DEBUG_ENABLED
        ESP_LOGI("i2s_mic", "I2S microphone stopped");
#endif
    }
}
