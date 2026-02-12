#ifndef CONFIG_H
#define CONFIG_H

// ════════════════════════════════════════════════════════════════
//  Hardware Configuration
// ════════════════════════════════════════════════════════════════

// I2S Pins (shared bus for mic & speaker)
#define I2S_BCLK        18   // Shared BCLK
#define I2S_WS          22   // Shared WS/LRCLK
#define MIC_SD          16   // Mic data input (INMP441)
#define AMP_DIN         20   // Amp data output (MAX98357A)

// Push-to-talk button
#define PTT_PIN         23
#define PTT_ACTIVE_LOW  true

// ════════════════════════════════════════════════════════════════
//  Audio Configuration
// ════════════════════════════════════════════════════════════════

#define MIC_SAMPLE_RATE         16000
#define SPEAKER_SAMPLE_RATE     22050
#define SAMPLES_PER_CHUNK       512
#define MAX_AUDIO_BUFFER_SIZE   (200 * 1024)  // 200KB

// I2S DMA Configuration
#define I2S_DMA_BUF_COUNT       8
#define I2S_MIC_DMA_BUF_LEN     256
#define I2S_SPK_DMA_BUF_LEN     512

// ════════════════════════════════════════════════════════════════
//  BLE Configuration
// ════════════════════════════════════════════════════════════════

#define BLE_DEVICE_NAME     "AIGlasses-ESP32C6"
#define BLE_MTU             512
#define BLE_HEADER_SIZE     2
#define BLE_MAX_PAYLOAD     (BLE_MTU - 3 - BLE_HEADER_SIZE)

// BLE Service & Characteristic UUIDs
#define SERVICE_UUID        "0000aa00-1234-5678-abcd-0e5032c6b1e0"
#define CHAR_AUDIO_TX_UUID  "0000aa01-1234-5678-abcd-0e5032c6b1e0"
#define CHAR_AUDIO_RX_UUID  "0000aa02-1234-5678-abcd-0e5032c6b1e0"
#define CHAR_CONTROL_UUID   "0000aa03-1234-5678-abcd-0e5032c6b1e0"

// BLE Connection Parameters
#define BLE_CONN_INTERVAL_MIN   6    // 7.5ms
#define BLE_CONN_INTERVAL_MAX   12   // 15ms
#define BLE_CONN_LATENCY        0
#define BLE_CONN_TIMEOUT        500  // 5s

// ════════════════════════════════════════════════════════════════
//  Debug Configuration
// ════════════════════════════════════════════════════════════════

#define SERIAL_BAUD_RATE    115200
#define DEBUG_ENABLED       true

#if DEBUG_ENABLED
  #define DEBUG_PRINT(x)    Serial.print(x)
  #define DEBUG_PRINTLN(x)  Serial.println(x)
  #define DEBUG_PRINTF(...) Serial.printf(__VA_ARGS__)
#else
  #define DEBUG_PRINT(x)
  #define DEBUG_PRINTLN(x)
  #define DEBUG_PRINTF(...)
#endif

#endif // CONFIG_H
