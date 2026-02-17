#include <NimBLEDevice.h>
#include <driver/i2s.h>
// ════════════════════════════════════════════════════════════════
//  BLE GATT Service UUIDs (must match Android BleVoiceService)
// ════════════════════════════════════════════════════════════════
#define SERVICE_UUID        "0000aa00-1234-5678-abcd-0e5032c6b1e0"
#define CHAR_AUDIO_TX_UUID  "0000aa01-1234-5678-abcd-0e5032c6b1e0"  // ESP32->Android (NOTIFY)
#define CHAR_AUDIO_RX_UUID  "0000aa02-1234-5678-abcd-0e5032c6b1e0"  // Android->ESP32 (WRITE)
#define CHAR_CONTROL_UUID   "0000aa03-1234-5678-abcd-0e5032c6b1e0"  // Bidirectional (WRITE+NOTIFY)

// ════════════════════════════════════════════════════════════════
//  BLE packet config
// ════════════════════════════════════════════════════════════════
#define BLE_MTU             512
#define BLE_HEADER_SIZE     2       // TAG(1) + SEQ(1)
// Max audio payload per BLE notification = MTU - 3 (ATT overhead) - header
#define BLE_MAX_PAYLOAD     (BLE_MTU - 3 - BLE_HEADER_SIZE)

// ════════════════════════════════════════════════════════════════
//  I2S Pins (shared bus for mic & speaker — ESP32-C6 has only 1 I2S)
// ════════════════════════════════════════════════════════════════
#define I2S_BCLK        18   // Shared BCLK -> INMP441 SCK & MAX98357A BCLK
#define I2S_WS          22   // Shared WS   -> INMP441 WS  & MAX98357A LRCLK
#define MIC_SD          16   // Data in from mic    (INMP441 SD)
#define AMP_DIN         20   // Data out to speaker (MAX98357A DIN)

// ════════════════════════════════════════════════════════════════
//  Push-to-talk
// ════════════════════════════════════════════════════════════════
#define PTT_PIN         23

// ════════════════════════════════════════════════════════════════
//  Audio settings
// ════════════════════════════════════════════════════════════════
static const int MIC_SR = 16000;
static const int SPK_SR = 22050;
static const int SAMPLES_PER_CHUNK = 512;
static int16_t micBuf[SAMPLES_PER_CHUNK];

// ════════════════════════════════════════════════════════════════
//  Speaker buffer (accumulates TTS audio from Android)
// ════════════════════════════════════════════════════════════════
#define MAX_AUDIO_BUFFER (200 * 1024)
static uint8_t audioBuffer[MAX_AUDIO_BUFFER];
static volatile size_t audioBufferLen = 0;
static volatile bool playAudio = false;
static unsigned int chunkCount = 0;

// ════════════════════════════════════════════════════════════════
//  I2S mode tracking (ESP32-C6 has only 1 I2S peripheral)
// ════════════════════════════════════════════════════════════════
enum I2SMode { MODE_NONE, MODE_MIC, MODE_SPEAKER };
static I2SMode currentI2SMode = MODE_NONE;

// ════════════════════════════════════════════════════════════════
//  BLE state
// ════════════════════════════════════════════════════════════════
static NimBLEServer* pServer = nullptr;
static NimBLECharacteristic* pAudioTxChar = nullptr;
static NimBLECharacteristic* pAudioRxChar = nullptr;
static NimBLECharacteristic* pControlChar = nullptr;
static volatile bool deviceConnected = false;
static uint8_t txSeqNum = 0;

// ════════════════════════════════════════════════════════════════
//  I2S init: Mic (RX) on I2S0
// ════════════════════════════════════════════════════════════════
void i2sMicInit() {
  if (currentI2SMode != MODE_NONE) {
    i2s_driver_uninstall(I2S_NUM_0);
    delay(50);
  }

  i2s_config_t cfg = {};
  cfg.mode = (i2s_mode_t)(I2S_MODE_MASTER | I2S_MODE_RX);
  cfg.sample_rate = MIC_SR;
  cfg.bits_per_sample = I2S_BITS_PER_SAMPLE_16BIT;
  cfg.channel_format = I2S_CHANNEL_FMT_ONLY_RIGHT;  // INMP441 L/R=GND → right slot
  cfg.communication_format = I2S_COMM_FORMAT_I2S;
  cfg.intr_alloc_flags = ESP_INTR_FLAG_LEVEL1;
  cfg.dma_buf_count = 8;
  cfg.dma_buf_len = 256;
  cfg.use_apll = false;
  cfg.tx_desc_auto_clear = false;
  cfg.fixed_mclk = 0;

  i2s_pin_config_t pins = {};
  pins.bck_io_num = I2S_BCLK;
  pins.ws_io_num = I2S_WS;
  pins.data_out_num = I2S_PIN_NO_CHANGE;
  pins.data_in_num = MIC_SD;

  Serial.println("[I2S] Installing MIC driver...");
  esp_err_t e = i2s_driver_install(I2S_NUM_0, &cfg, 0, NULL);
  if (e != ESP_OK) {
    Serial.printf("[I2S] MIC install failed: %d\n", (int)e);
  }

  e = i2s_set_pin(I2S_NUM_0, &pins);
  if (e != ESP_OK) {
    Serial.printf("[I2S] MIC pins failed: %d\n", (int)e);
  } else {
    Serial.println("[I2S] MIC configured OK");
  }

  i2s_zero_dma_buffer(I2S_NUM_0);
  currentI2SMode = MODE_MIC;
}

// ════════════════════════════════════════════════════════════════
//  I2S init: Speaker (TX) on I2S0
// ════════════════════════════════════════════════════════════════
void i2sSpkInit() {
  if (currentI2SMode != MODE_NONE) {
    i2s_driver_uninstall(I2S_NUM_0);
    delay(50);
  }

  i2s_config_t cfg = {};
  cfg.mode = (i2s_mode_t)(I2S_MODE_MASTER | I2S_MODE_TX);
  cfg.sample_rate = SPK_SR;
  cfg.bits_per_sample = I2S_BITS_PER_SAMPLE_16BIT;
  cfg.channel_format = I2S_CHANNEL_FMT_ONLY_LEFT;
  cfg.communication_format = I2S_COMM_FORMAT_I2S;
  cfg.intr_alloc_flags = ESP_INTR_FLAG_LEVEL1;
  cfg.dma_buf_count = 8;
  cfg.dma_buf_len = 512;
  cfg.use_apll = false;
  cfg.tx_desc_auto_clear = true;
  cfg.fixed_mclk = 0;

  i2s_pin_config_t pins = {};
  pins.bck_io_num = I2S_BCLK;
  pins.ws_io_num = I2S_WS;
  pins.data_out_num = AMP_DIN;
  pins.data_in_num = I2S_PIN_NO_CHANGE;

  Serial.println("[I2S] Installing SPEAKER driver...");
  esp_err_t e = i2s_driver_install(I2S_NUM_0, &cfg, 0, NULL);
  if (e != ESP_OK) {
    Serial.printf("[I2S] SPK install failed: %d\n", (int)e);
  }

  e = i2s_set_pin(I2S_NUM_0, &pins);
  if (e != ESP_OK) {
    Serial.printf("[I2S] SPK pins failed: %d\n", (int)e);
  } else {
    Serial.println("[I2S] SPEAKER configured OK");
  }

  i2s_zero_dma_buffer(I2S_NUM_0);
  currentI2SMode = MODE_SPEAKER;
}

// ════════════════════════════════════════════════════════════════
//  Amplifier control (no SD_MODE pin — amp always on)
// ════════════════════════════════════════════════════════════════
void enableAmp() {
  Serial.println("[AMP] Ready");
}

void disableAmp() {
  // No SD_MODE pin wired — amp stays on
}

// ════════════════════════════════════════════════════════════════
//  Play speaker (unchanged from WebSocket version)
// ════════════════════════════════════════════════════════════════
void playSpeaker() {
  if (audioBufferLen == 0) return;

  float duration = (float)audioBufferLen / (SPK_SR * 2);
  Serial.printf("[AUDIO] Playing %u bytes (%.2fs @ %dHz)\n",
                audioBufferLen, duration, SPK_SR);

  // Switch to speaker mode
  i2sSpkInit();
  enableAmp();
  delay(50);

  // Play audio with progress reporting
  size_t offset = 0;
  size_t totalChunks = 0;
  while (offset < audioBufferLen) {
    size_t chunk = min((size_t)2048, audioBufferLen - offset);
    size_t written = 0;
    i2s_write(I2S_NUM_0, audioBuffer + offset, chunk, &written, portMAX_DELAY);
    offset += written;
    totalChunks++;

    // Progress every 20%
    if (totalChunks % ((audioBufferLen / 2048 / 5) + 1) == 0) {
      float progress = (float)offset / audioBufferLen * 100.0f;
      Serial.printf("[AUDIO] Playing... %.1f%% (%u/%u bytes)\n",
                    progress, offset, audioBufferLen);
    }
  }

  delay(100);
  disableAmp();

  Serial.printf("[AUDIO] Playback complete! (%u bytes, %u chunks)\n",
                audioBufferLen, totalChunks);

  // Switch back to mic
  i2sMicInit();

  // Clear buffer
  audioBufferLen = 0;
  chunkCount = 0;
}

// ════════════════════════════════════════════════════════════════
//  BLE Server Callbacks
// ════════════════════════════════════════════════════════════════
class ServerCallbacks : public NimBLEServerCallbacks {
  void onConnect(NimBLEServer* pServer, NimBLEConnInfo& connInfo) override {
    deviceConnected = true;
    Serial.println("\n[BLE] Android connected!");

    // Request fast connection parameters for audio streaming
    // Min interval=7.5ms(6), Max=15ms(12), Latency=0, Timeout=5s(500)
    pServer->updateConnParams(connInfo.getConnHandle(), 6, 12, 0, 500);
    Serial.println("[BLE] Requested fast connection parameters");
  }

  void onDisconnect(NimBLEServer* pServer, NimBLEConnInfo& connInfo, int reason) override {
    deviceConnected = false;
    audioBufferLen = 0;
    chunkCount = 0;
    Serial.printf("[BLE] Android disconnected (reason=%d)\n", reason);

    // Restart advertising so Android can reconnect
    NimBLEDevice::startAdvertising();
    Serial.println("[BLE] Advertising restarted, waiting for reconnection...");
  }

  void onMTUChange(uint16_t mtu, NimBLEConnInfo& connInfo) override {
    Serial.printf("[BLE] MTU changed to %u (payload: %u bytes)\n", mtu, mtu - 3);
  }
};

// ════════════════════════════════════════════════════════════════
//  Audio RX Callback (Android → ESP32 speaker data)
// ════════════════════════════════════════════════════════════════
class AudioRxCallbacks : public NimBLECharacteristicCallbacks {
  void onWrite(NimBLECharacteristic* pChar, NimBLEConnInfo& connInfo) override {
    NimBLEAttValue val = pChar->getValue();
    const uint8_t* data = val.data();
    size_t len = val.size();
    if (len < BLE_HEADER_SIZE) return;

    uint8_t tag = data[0];
    // uint8_t seq = data[1];  // Available for loss detection
    const uint8_t* payload = data + BLE_HEADER_SIZE;
    size_t payloadLen = len - BLE_HEADER_SIZE;

    if (tag == 'A' && payloadLen > 0) {
      // Append audio fragment to speaker buffer
      if (audioBufferLen + payloadLen <= MAX_AUDIO_BUFFER) {
        memcpy(audioBuffer + audioBufferLen, payload, payloadLen);
        audioBufferLen += payloadLen;
        chunkCount++;

        // Progress every 20 chunks
        if (chunkCount % 20 == 0) {
          Serial.printf("[BLE-RX] Received %u chunks, %u bytes\n",
                        chunkCount, (unsigned int)audioBufferLen);
        }
      } else {
        Serial.println("[BLE-RX] Audio buffer full!");
      }
    }
  }
};

// ════════════════════════════════════════════════════════════════
//  Control Callback (bidirectional commands)
// ════════════════════════════════════════════════════════════════
class ControlCallbacks : public NimBLECharacteristicCallbacks {
  void onWrite(NimBLECharacteristic* pChar, NimBLEConnInfo& connInfo) override {
    NimBLEAttValue val = pChar->getValue();
    const uint8_t* data = val.data();
    size_t len = val.size();
    if (len < 1) return;

    uint8_t tag = data[0];

    if (tag == 'E') {
      Serial.printf("[BLE-CTRL] End marker received - %u bytes buffered (%u chunks)\n",
                    (unsigned int)audioBufferLen, chunkCount);
      if (audioBufferLen > 0) {
        playAudio = true;
      }
    } else if (tag == 'S') {
      Serial.println("[BLE-CTRL] Start marker - clearing buffer");
      audioBufferLen = 0;
      chunkCount = 0;
    }
  }
};

// ════════════════════════════════════════════════════════════════
//  Send mic audio to Android via BLE NOTIFY
// ════════════════════════════════════════════════════════════════
void sendMicChunkViaBLE(uint8_t* pcmData, size_t pcmLen) {
  if (!deviceConnected || pAudioTxChar == nullptr) return;

  size_t offset = 0;
  while (offset < pcmLen) {
    size_t fragSize = min((size_t)BLE_MAX_PAYLOAD, pcmLen - offset);

    // Build packet: [TAG][SEQ][audio data]
    uint8_t pkt[BLE_HEADER_SIZE + fragSize];
    pkt[0] = 'A';           // Audio tag
    pkt[1] = txSeqNum++;    // Sequence number (wraps at 255)
    memcpy(pkt + BLE_HEADER_SIZE, pcmData + offset, fragSize);

    pAudioTxChar->setValue(pkt, BLE_HEADER_SIZE + fragSize);
    pAudioTxChar->notify();

    offset += fragSize;
    delay(2);  // Small yield for BLE stack
  }
}

// ════════════════════════════════════════════════════════════════
//  Setup
// ════════════════════════════════════════════════════════════════
void setup() {
  Serial.begin(115200);
  delay(1000);

  Serial.println("\n\n============================================================");
  Serial.println("  ESP32-C6 VOICE ASSISTANT (BLE + Shared I2S Bus)");
  Serial.println("============================================================");
  Serial.println("BCLK=GPIO18, WS=GPIO22 (shared mic & speaker)");
  Serial.println("MIC_SD=GPIO16, AMP_DIN=GPIO20");
  Serial.printf("PTT Button=GPIO%d\n", PTT_PIN);
  Serial.println("Transport: Bluetooth Low Energy (BLE)");
  Serial.println("============================================================\n");

  // Push-to-talk button
  pinMode(PTT_PIN, INPUT_PULLUP);
  delay(100);  // Let pull-up settle

  // Debug: show button state at boot
  int btnState = digitalRead(PTT_PIN);
  Serial.printf("[PTT] GPIO%d initial state: %s (%d)\n",
                PTT_PIN, btnState == HIGH ? "HIGH (not pressed)" : "LOW (pressed!)", btnState);
  if (btnState == LOW) {
    Serial.println("[PTT] WARNING: Button reads LOW at boot! Check wiring.");
    Serial.println("[PTT] Button should connect GPIO23 to GND when pressed.");
    Serial.println("[PTT] Waiting for button to be released...");
    while (digitalRead(PTT_PIN) == LOW) {
      delay(100);
    }
    Serial.println("[PTT] Button released, continuing...");
  }

  // Initialize microphone
  i2sMicInit();

  // ── Initialize BLE ──
  Serial.println("\n[BLE] Initializing...");
  NimBLEDevice::init("AIGlasses-ESP32C6");
  NimBLEDevice::setMTU(BLE_MTU);
  NimBLEDevice::setPower(ESP_PWR_LVL_P9);  // Max TX power for range

  pServer = NimBLEDevice::createServer();
  pServer->setCallbacks(new ServerCallbacks());

  // Create GATT service
  NimBLEService* pService = pServer->createService(SERVICE_UUID);

  // Audio TX: ESP32 mic → Android (NOTIFY)
  pAudioTxChar = pService->createCharacteristic(
    CHAR_AUDIO_TX_UUID,
    NIMBLE_PROPERTY::NOTIFY
  );

  // Audio RX: Android TTS → ESP32 speaker (WRITE without response for speed)
  pAudioRxChar = pService->createCharacteristic(
    CHAR_AUDIO_RX_UUID,
    NIMBLE_PROPERTY::WRITE_NR
  );
  pAudioRxChar->setCallbacks(new AudioRxCallbacks());

  // Control: bidirectional commands (WRITE + NOTIFY)
  pControlChar = pService->createCharacteristic(
    CHAR_CONTROL_UUID,
    NIMBLE_PROPERTY::WRITE | NIMBLE_PROPERTY::NOTIFY
  );
  pControlChar->setCallbacks(new ControlCallbacks());

  // Start service
  pService->start();

  // Start advertising
  NimBLEAdvertising* pAdvertising = NimBLEDevice::getAdvertising();
  pAdvertising->addServiceUUID(SERVICE_UUID);
  pAdvertising->start();

  Serial.println("[BLE] GATT server started");
  Serial.println("[BLE] Advertising as 'AIGlasses-ESP32C6'");

  Serial.println();
  Serial.println("============================================================");
  Serial.printf("  READY! Hold GPIO%d to talk, release to send\n", PTT_PIN);
  Serial.println("  Open the AIGlasses app and tap 'Scan & Connect'");
  Serial.println("  No WiFi hotspot needed — uses BLE directly!");
  Serial.println("============================================================\n");
}

// ════════════════════════════════════════════════════════════════
//  Loop
// ════════════════════════════════════════════════════════════════
void loop() {
  // Play audio if ready (TTS response from Android)
  if (playAudio) {
    playSpeaker();
    playAudio = false;
  }

  // Wait for BLE connection
  if (!deviceConnected) {
    delay(100);
    return;
  }

  // Debounced button read: read twice with a small gap
  bool raw1 = (digitalRead(PTT_PIN) == LOW);
  delay(10);
  bool raw2 = (digitalRead(PTT_PIN) == LOW);
  bool pressed = raw1 && raw2;  // Only count as pressed if both reads agree

  static bool wasPressed = false;

  if (!pressed) {
    if (wasPressed) {
      // Send END marker via Control characteristic
      uint8_t endPkt[BLE_HEADER_SIZE] = {'E', 0};
      pControlChar->setValue(endPkt, BLE_HEADER_SIZE);
      pControlChar->notify();
      Serial.println("[PTT] Released -> Sent END marker via BLE");
    }
    wasPressed = false;
    delay(20);
    return;
  }

  // Button is pressed
  if (!wasPressed) {
    txSeqNum = 0;  // Reset sequence for new utterance
    Serial.println("[PTT] Pressed -> Streaming audio via BLE...");
  }
  wasPressed = true;

  // Read mic audio via I2S
  size_t bytesRead = 0;
  esp_err_t ok = i2s_read(I2S_NUM_0, micBuf, sizeof(micBuf), &bytesRead, 20 / portTICK_PERIOD_MS);
  if (ok != ESP_OK || bytesRead == 0) return;

  // Send mic audio to Android via BLE NOTIFY
  sendMicChunkViaBLE((uint8_t*)micBuf, bytesRead);

  delay(1);
}
