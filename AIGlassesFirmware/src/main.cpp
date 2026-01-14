#include <stddef.h>
#include <stdint.h>
#include <freertos/FreeRTOS.h>
#include <freertos/task.h>
#include <esp_log.h>
#include "config.h"
#include "i2s_mic.h"
#include "ble_audio.h"
#include "audio_packetizer.h"

// Module instances (no globals except these)
static I2SMic i2sMic;
static BleAudio bleAudio;
static AudioPacketizer packetizer(BLE_CHUNK_BYTES);

// Streaming state
static bool streamingEnabled = false;

// Audio buffer
static int16_t audioBuffer[AUDIO_BUFFER_LEN];

// Output buffer for BLE chunks (large enough for multiple chunks)
static uint8_t outputBuffer[BLE_CHUNK_BYTES * 4];

static void onStreamingControl(bool enable) {
    streamingEnabled = enable;
#if DEBUG_ENABLED
    ESP_LOGI("app", "Streaming %s", enable ? "ENABLED" : "DISABLED");
#endif
}

extern "C" void app_main(void) {
    vTaskDelay(pdMS_TO_TICKS(500));

#if DEBUG_ENABLED
    ESP_LOGI("app", "=== OpenGlasses Audio Firmware ===");
    ESP_LOGI("app", "Initializing...");
#endif

    // Initialize BLE
    if (!bleAudio.init()) {
#if DEBUG_ENABLED
        ESP_LOGE("app", "ERROR: BLE initialization failed");
#endif
        return;
    }

    bleAudio.setStreamingControlCallback(onStreamingControl);

#if DEBUG_ENABLED
    ESP_LOGI("app", "BLE ready. Scan with LightBlue!");
#endif

    // Initialize I2S microphone
    if (!i2sMic.init()) {
#if DEBUG_ENABLED
        ESP_LOGE("app", "ERROR: I2S initialization failed");
#endif
        return;
    }

    vTaskDelay(pdMS_TO_TICKS(200)); // Allow I2S to stabilize

#if DEBUG_ENABLED
    ESP_LOGI("app", "Initialization complete. Waiting for BLE connection...");
    ESP_LOGI("app", "Send START command to begin streaming.");
#endif

    while (true) {
        // Read audio samples from I2S
        size_t samplesRead = i2sMic.read(audioBuffer, AUDIO_BUFFER_LEN);

        if (samplesRead > 0 && streamingEnabled && bleAudio.isConnected()) {
#if DEBUG_ENABLED
            ESP_LOGD("app", "Read %u samples", static_cast<unsigned int>(samplesRead));
#endif

            // Add samples to packetizer (may flush previous partial chunk first)
            size_t chunksWritten = 0;
            packetizer.addSamples(
                audioBuffer,
                samplesRead,
                outputBuffer,
                sizeof(outputBuffer),
                chunksWritten
            );

            // Send complete chunks
            for (size_t i = 0; i < chunksWritten; ++i) {
                bleAudio.notify(outputBuffer + (i * BLE_CHUNK_BYTES), BLE_CHUNK_BYTES);
                vTaskDelay(pdMS_TO_TICKS(5)); // Small delay between notifications
            }

            // Flush any remaining partial chunk
            uint8_t flushBuffer[BLE_CHUNK_BYTES];
            size_t flushSize = packetizer.flush(flushBuffer, sizeof(flushBuffer));
            if (flushSize > 0) {
                bleAudio.notify(flushBuffer, flushSize);
            }
        }

        vTaskDelay(pdMS_TO_TICKS(10)); // Small delay to prevent tight loop
    }
}