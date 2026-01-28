/*
 * ESP32 AI Glasses Firmware - Minimal BLE Audio
 * ==============================================
 * Push-to-talk: streams mic audio to Android app via BLE.
 * Receives TTS audio from app and plays through speaker.
 *
 * Hardware: ESP32 + INMP441 mic + MAX98357A amp + button
 */

#include <BLEDevice.h>
#include <BLEServer.h>
#include <BLE2902.h>
#include <driver/i2s.h>

// ==================== BLE CONFIG ====================
#define DEVICE_NAME    "AI_Glasses_ESP32"
#define SERVICE_UUID   "4fafc201-1fb5-459e-8fcc-c5c9c331914b"
#define AUDIO_TX_UUID  "beb5483e-36e1-4688-b7f5-ea07361b26a8"  // ESP32 -> App
#define AUDIO_RX_UUID  "beb5483e-36e1-4688-b7f5-ea07361b26a9"  // App -> ESP32

// ==================== COMMANDS ====================
#define CMD_AUDIO_START  0x03
#define CMD_AUDIO_DATA   0x04
#define CMD_AUDIO_END    0x05
#define CMD_TEXT         0x06  // Transcribed text from app

// ==================== PINS ====================
#define I2S_MIC_SCK   26
#define I2S_MIC_WS    25
#define I2S_MIC_SD    32
#define I2S_SPK_BCLK  21
#define I2S_SPK_WS    16
#define I2S_SPK_DOUT  17
#define BUTTON_PIN    -1  // external button (set to GPIO if used)
#define BOOT_BUTTON_PIN 0 // ESP32-WROOM-32 BOOT is GPIO0
#define LED_PIN       2

// ==================== AUDIO CONFIG ====================
#define MIC_SAMPLE_RATE   16000
#define MIC_BUFFER_LEN    512
#define MAX_CHUNK_PAYLOAD 175  // 180 - 5 byte header

// ==================== GLOBALS ====================
static BLECharacteristic* pAudioTx = nullptr;
static BLECharacteristic* pAudioRx = nullptr;

static volatile bool bleConnected = false;
static volatile bool isRecording = false;
static volatile bool isPlaying = false;

static uint16_t txSequence = 0;

// Button debounce
static bool lastButtonState = HIGH;
static bool lastStablePressed = false;
static uint32_t lastDebounceMs = 0;

// ==================== I2S SETUP ====================
static void initMicI2S() {
  i2s_config_t cfg = {
    .mode = (i2s_mode_t)(I2S_MODE_MASTER | I2S_MODE_RX),
    .sample_rate = MIC_SAMPLE_RATE,
    .bits_per_sample = I2S_BITS_PER_SAMPLE_16BIT,
    .channel_format = I2S_CHANNEL_FMT_ONLY_LEFT,
    .communication_format = I2S_COMM_FORMAT_STAND_I2S,
    .intr_alloc_flags = ESP_INTR_FLAG_LEVEL1,
    .dma_buf_count = 4,
    .dma_buf_len = MIC_BUFFER_LEN,
    .use_apll = false,
    .tx_desc_auto_clear = false,
    .fixed_mclk = 0
  };
  i2s_pin_config_t pins = {
    .bck_io_num = I2S_MIC_SCK,
    .ws_io_num = I2S_MIC_WS,
    .data_out_num = I2S_PIN_NO_CHANGE,
    .data_in_num = I2S_MIC_SD
  };
  i2s_driver_install(I2S_NUM_0, &cfg, 0, nullptr);
  i2s_set_pin(I2S_NUM_0, &pins);
}

static void initSpeakerI2S(uint32_t sampleRate) {
  // Uninstall first in case already running
  i2s_driver_uninstall(I2S_NUM_1);

  i2s_config_t cfg = {
    .mode = (i2s_mode_t)(I2S_MODE_MASTER | I2S_MODE_TX),
    .sample_rate = sampleRate,
    .bits_per_sample = I2S_BITS_PER_SAMPLE_16BIT,
    .channel_format = I2S_CHANNEL_FMT_ONLY_LEFT,
    .communication_format = I2S_COMM_FORMAT_STAND_I2S,
    .intr_alloc_flags = ESP_INTR_FLAG_LEVEL1,
    .dma_buf_count = 8,
    .dma_buf_len = 1024,
    .use_apll = false,
    .tx_desc_auto_clear = true,
    .fixed_mclk = 0
  };
  i2s_pin_config_t pins = {
    .bck_io_num = I2S_SPK_BCLK,
    .ws_io_num = I2S_SPK_WS,
    .data_out_num = I2S_SPK_DOUT,
    .data_in_num = I2S_PIN_NO_CHANGE
  };
  i2s_driver_install(I2S_NUM_1, &cfg, 0, nullptr);
  i2s_set_pin(I2S_NUM_1, &pins);
  i2s_zero_dma_buffer(I2S_NUM_1);
}

// ==================== RECORDING ====================
static void startRecording() {
  if (isRecording || !bleConnected) return;

  isRecording = true;
  txSequence = 0;
  digitalWrite(LED_PIN, HIGH);
  Serial.println("[MIC] Recording started");

  // Send AUDIO_START: [cmd][sampleRate LE][channels][bits]
  uint8_t hdr[7];
  hdr[0] = CMD_AUDIO_START;
  hdr[1] = MIC_SAMPLE_RATE & 0xFF;
  hdr[2] = (MIC_SAMPLE_RATE >> 8) & 0xFF;
  hdr[3] = (MIC_SAMPLE_RATE >> 16) & 0xFF;
  hdr[4] = (MIC_SAMPLE_RATE >> 24) & 0xFF;
  hdr[5] = 1;   // channels
  hdr[6] = 16;  // bits
  pAudioTx->setValue(hdr, 7);
  pAudioTx->notify();
}

static void stopRecording() {
  if (!isRecording) return;

  isRecording = false;
  Serial.println("[MIC] Recording stopped");

  // Send AUDIO_END
  uint8_t cmd = CMD_AUDIO_END;
  pAudioTx->setValue(&cmd, 1);
  pAudioTx->notify();
}

// ==================== BLE CALLBACKS ====================
class ServerCB : public BLEServerCallbacks {
  void onConnect(BLEServer* s) override {
    bleConnected = true;
    digitalWrite(LED_PIN, HIGH);  // LED ON when connected
    Serial.println("[BLE] Connected");
  }
  void onDisconnect(BLEServer* s) override {
    bleConnected = false;
    isRecording = false;
    isPlaying = false;
    digitalWrite(LED_PIN, LOW);  // LED OFF when disconnected
    Serial.println("[BLE] Disconnected");
    // Restart advertising
    BLEDevice::startAdvertising();
  }
};

class AudioRxCB : public BLECharacteristicCallbacks {
  void onWrite(BLECharacteristic* pChar) override {
    // BINARY-SAFE: use getData() for raw byte access
    const uint8_t* buf = pChar->getData();
    size_t len = pChar->getLength();

    if (len == 0) return;

    uint8_t cmd = buf[0];

    switch (cmd) {
      case CMD_AUDIO_START:
        // Format: [cmd][sampleRate u32 LE][channels][bits] = 7 bytes
        if (len >= 5) {
          uint32_t sr = buf[1] | (buf[2] << 8) | (buf[3] << 16) | (buf[4] << 24);
          initSpeakerI2S(sr);
          isPlaying = true;
          digitalWrite(LED_PIN, HIGH);
        }
        break;

      case CMD_AUDIO_DATA:
        // Format: [cmd][seq u16 LE][len u16 LE][payload...]
        if (isPlaying && len >= 5) {
          uint16_t payloadLen = buf[3] | (buf[4] << 8);
          if (len >= 5 + payloadLen) {
            size_t written = 0;
            i2s_write(I2S_NUM_1, buf + 5, payloadLen, &written, portMAX_DELAY);
          }
        }
        break;

      case CMD_AUDIO_END:
        isPlaying = false;
        i2s_zero_dma_buffer(I2S_NUM_1);
        // Keep LED on if connected
        if (bleConnected) digitalWrite(LED_PIN, HIGH);
        break;

      case CMD_TEXT:
        // [cmd][text...] - Print transcribed text to Serial
        if (len > 1) {
          Serial.print("[TRANSCRIPTION] ");
          for (size_t i = 1; i < len; i++) {
            Serial.print((char)buf[i]);
          }
          Serial.println();
        }
        break;
    }
  }
};

// ==================== BLE INIT ====================
static void initBLE() {
  BLEDevice::init(DEVICE_NAME);
  BLEDevice::setMTU(185);

  BLEServer* server = BLEDevice::createServer();
  server->setCallbacks(new ServerCB());

  BLEService* svc = server->createService(SERVICE_UUID);

  // Audio TX (ESP32 -> App): notify mic data
  pAudioTx = svc->createCharacteristic(
    AUDIO_TX_UUID,
    BLECharacteristic::PROPERTY_NOTIFY
  );
  pAudioTx->addDescriptor(new BLE2902());

  // Audio RX (App -> ESP32): receive TTS data
  pAudioRx = svc->createCharacteristic(
    AUDIO_RX_UUID,
    BLECharacteristic::PROPERTY_WRITE_NR
  );
  pAudioRx->setCallbacks(new AudioRxCB());

  svc->start();

  BLEAdvertising* adv = BLEDevice::getAdvertising();
  adv->addServiceUUID(SERVICE_UUID);
  adv->setScanResponse(true);
  BLEDevice::startAdvertising();
}

// ==================== BUTTON ====================
static bool readPressed() {
  bool pressed = false;
  if (BUTTON_PIN >= 0) {
    pressed = pressed || (digitalRead(BUTTON_PIN) == LOW);
  }
  pressed = pressed || (digitalRead(BOOT_BUTTON_PIN) == LOW);
  return pressed;
}

static void handleButton() {
  bool state = readPressed() ? LOW : HIGH;

  if (state != lastButtonState) {
    lastDebounceMs = millis();
  }

  if ((millis() - lastDebounceMs) > 30) {
    bool stablePressed = (state == LOW);
    if (stablePressed != lastStablePressed) {
      lastStablePressed = stablePressed;
      Serial.print("[BUTTON] ");
      Serial.print(stablePressed ? "PRESSED" : "RELEASED");
      Serial.print(" raw=");
      Serial.print(digitalRead(BUTTON_PIN));
      Serial.print("/");
      Serial.print(digitalRead(BOOT_BUTTON_PIN));
      Serial.print("/");
      Serial.print(digitalRead(BOOT_BUTTON_PIN));
      Serial.print(" ble=");
      Serial.println(bleConnected ? "1" : "0");
      if (!bleConnected) {
        digitalWrite(LED_PIN, stablePressed ? HIGH : LOW);
      }
    }
    // Button pressed (LOW) and not already recording
    if (state == LOW && !isRecording && !isPlaying && bleConnected) {
      startRecording();
    }
    // Button released (HIGH) and currently recording
    else if (state == HIGH && isRecording) {
      stopRecording();
    }
  }

  lastButtonState = state;
}

// ==================== MIC STREAMING ====================
// Pre-allocated packet buffer (no heap allocation in loop)
static uint8_t txPacket[MAX_CHUNK_PAYLOAD + 5];

static void streamMicData() {
  if (!isRecording || !bleConnected) return;

  int16_t samples[MIC_BUFFER_LEN];
  size_t bytesRead = 0;

  esp_err_t err = i2s_read(I2S_NUM_0, samples, sizeof(samples), &bytesRead, 0);
  if (err != ESP_OK || bytesRead == 0) return;

  // Output mic level to Serial Plotter (average of first 8 samples)
  int32_t avg = 0;
  int numSamples = bytesRead / 2;
  int samplesToAvg = (numSamples > 8) ? 8 : numSamples;
  for (int i = 0; i < samplesToAvg; i++) {
    avg += samples[i];
  }
  avg /= samplesToAvg;
  Serial.println(avg);  // Single value for Serial Plotter

  const uint8_t* data = (const uint8_t*)samples;
  size_t remaining = bytesRead;

  while (remaining > 0 && isRecording && bleConnected) {
    size_t chunk = (remaining > MAX_CHUNK_PAYLOAD) ? MAX_CHUNK_PAYLOAD : remaining;

    // Build packet: [cmd][seq LE][len LE][data]
    txPacket[0] = CMD_AUDIO_DATA;
    txPacket[1] = txSequence & 0xFF;
    txPacket[2] = (txSequence >> 8) & 0xFF;
    txPacket[3] = chunk & 0xFF;
    txPacket[4] = (chunk >> 8) & 0xFF;
    memcpy(txPacket + 5, data, chunk);

    pAudioTx->setValue(txPacket, 5 + chunk);
    pAudioTx->notify();

    data += chunk;
    remaining -= chunk;
    txSequence++;

    // Minimal delay to prevent BLE congestion (1-2ms is enough)
    delay(1);
  }

  // Blink LED while recording (but stay ON when connected)
  digitalWrite(LED_PIN, HIGH);
}

// ==================== SETUP ====================
void setup() {
  Serial.begin(115200);

  pinMode(LED_PIN, OUTPUT);
  if (BUTTON_PIN >= 0) {
    pinMode(BUTTON_PIN, INPUT_PULLUP);
  }
  pinMode(BOOT_BUTTON_PIN, INPUT_PULLUP);

  initMicI2S();
  initBLE();

  Serial.println("AI Glasses ready. Press button to talk.");
}

// ==================== LOOP ====================
void loop() {
  handleButton();
  streamMicData();
  delay(1);
}
