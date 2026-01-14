#ifndef CONFIG_H
#define CONFIG_H

#include <stdint.h>
#include <driver/i2s.h>

// BLE Configuration
#define BLE_DEVICE_NAME "OpenGlasses-Audio"
#define BLE_SERVICE_UUID "4fafc201-1fb5-459e-8fcc-c5c9c331914b"
#define BLE_CHARACTERISTIC_UUID "beb5483e-36e1-4688-b7f5-ea07361b26a8"
#define BLE_CHUNK_BYTES 20  // Default BLE notification payload size

// I2S Configuration (INMP441)
// ESP32-C6 has GPIOs 0-21; pick defaults within that range.
#define I2S_WS_PIN 23
#define I2S_SD_PIN 22
#define I2S_SCK_PIN 19
#define I2S_PORT_NUM I2S_NUM_0

// Audio Configuration
#define AUDIO_SAMPLE_RATE 44100
#define AUDIO_BITS_PER_SAMPLE 16
#define AUDIO_CHANNEL_FORMAT I2S_CHANNEL_FMT_ONLY_LEFT
#define AUDIO_BUFFER_LEN 64  // Number of int16_t samples per read

// Debug Configuration
#define DEBUG_ENABLED 1  // Set to 0 to disable Serial debug output

#endif // CONFIG_H
