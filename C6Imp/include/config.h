#ifndef CONFIG_H
#define CONFIG_H

// ════════════════════════════════════════════════════════════════
//  Network Configuration
// ════════════════════════════════════════════════════════════════

#define WIFI_SSID           "Ayoub"
#define WIFI_PASSWORD       "12345678"
#define WIFI_TIMEOUT_MS     20000

#define WS_HOST             "della-insolvable-drolly.ngrok-free.dev"
#define WS_PORT             80
#define WS_PATH             "/"
#define WS_RECONNECT_MS     2000

// ════════════════════════════════════════════════════════════════
//  Hardware Configuration
// ════════════════════════════════════════════════════════════════

// I2S Pins (shared bus for mic & speaker)
#define I2S_BCLK        18   // Shared BCLK
#define I2S_WS          22   // Shared WS/LRCLK
#define MIC_DIN         16   // Mic data input (INMP441)
#define AMP_DOUT        17   // Amp data output (MAX98357A)

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
