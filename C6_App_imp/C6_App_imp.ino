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
// 32-bit read buffer: INMP441 packs 24-bit audio in upper bits of 32-bit I2S slot
static int32_t micBuf32[SAMPLES_PER_CHUNK];
// 16-bit output buffer sent over BLE
static int16_t micBuf[SAMPLES_PER_CHUNK];

// ════════════════════════════════════════════════════════════════
//  Ring Buffer for streaming playback
//  BLE callback (NimBLE task) writes → main loop reads → I2S
//  Single producer, single consumer = lock-free with volatile indices
// ════════════════════════════════════════════════════════════════
#define RING_SIZE (160 * 1024)   // 160KB ring buffer
static uint8_t ringBuffer[RING_SIZE];
static volatile size_t ringHead = 0;   // Write position (BLE task)
static volatile size_t ringTail = 0;   // Read position  (main loop)

// How much data is available to read
static inline size_t ringAvailable() {
  size_t h = ringHead;
  size_t t = ringTail;
  return (h >= t) ? (h - t) : (RING_SIZE - t + h);
}

// How much free space for writing
static inline size_t ringFree() {
  return RING_SIZE - 1 - ringAvailable();  // -1 to distinguish full from empty
}

// Write data into ring buffer (called from BLE task)
static size_t ringWrite(const uint8_t* data, size_t len) {
  size_t free = ringFree();
  if (len > free) len = free;  // Drop excess if ring is full
  if (len == 0) return 0;

  size_t h = ringHead;
  size_t firstPart = RING_SIZE - h;
  if (firstPart >= len) {
    memcpy(ringBuffer + h, data, len);
  } else {
    memcpy(ringBuffer + h, data, firstPart);
    memcpy(ringBuffer, data + firstPart, len - firstPart);
  }
  ringHead = (h + len) % RING_SIZE;
  return len;
}

// Read data from ring buffer (called from main loop)
static size_t ringRead(uint8_t* dst, size_t maxLen) {
  size_t avail = ringAvailable();
  size_t len = (maxLen < avail) ? maxLen : avail;
  if (len == 0) return 0;

  size_t t = ringTail;
  size_t firstPart = RING_SIZE - t;
  if (firstPart >= len) {
    memcpy(dst, ringBuffer + t, len);
  } else {
    memcpy(dst, ringBuffer + t, firstPart);
    memcpy(dst + firstPart, ringBuffer, len - firstPart);
  }
  ringTail = (t + len) % RING_SIZE;
  return len;
}

// Reset ring buffer
static void ringReset() {
  ringHead = 0;
  ringTail = 0;
}

// ════════════════════════════════════════════════════════════════
//  Streaming playback state machine
// ════════════════════════════════════════════════════════════════
enum StreamState {
  STREAM_IDLE,       // Waiting — mic mode active
  STREAM_BUFFERING,  // Start marker received, accumulating initial data
  STREAM_PLAYING,    // I2S speaker active, playing from ring buffer
  STREAM_DRAINING    // End marker received, playing remaining data
};
static volatile StreamState streamState = STREAM_IDLE;
static volatile bool endMarkerReceived = false;

// Minimum bytes before starting playback (~185ms of audio at 22050Hz)
// Gives headroom for I2S mode switch (~70ms) + jitter
#define STREAM_START_THRESHOLD  8192

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
//  Stats for debugging
// ════════════════════════════════════════════════════════════════
static unsigned int rxChunkCount = 0;
static unsigned int rxTotalBytes = 0;
static unsigned int rxDroppedBytes = 0;
static unsigned int rxLostPackets = 0;

// ════════════════════════════════════════════════════════════════
//  I2S init: Mic (RX) on I2S0
// ════════════════════════════════════════════════════════════════
void i2sMicInit() {
  // Force AMP_DIN LOW before teardown to prevent floating pin buzz
  pinMode(AMP_DIN, OUTPUT);
  digitalWrite(AMP_DIN, LOW);

  if (currentI2SMode != MODE_NONE) {
    i2s_driver_uninstall(I2S_NUM_0);
    delay(50);
  }

  i2s_config_t cfg = {};
  cfg.mode = (i2s_mode_t)(I2S_MODE_MASTER | I2S_MODE_RX);
  cfg.sample_rate = MIC_SR;
  cfg.bits_per_sample = I2S_BITS_PER_SAMPLE_32BIT;
  cfg.channel_format = I2S_CHANNEL_FMT_ONLY_LEFT;
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
  if (e != ESP_OK) Serial.printf("[I2S] MIC install failed: %d\n", (int)e);

  e = i2s_set_pin(I2S_NUM_0, &pins);
  if (e != ESP_OK) Serial.printf("[I2S] MIC pins failed: %d\n", (int)e);
  else Serial.println("[I2S] MIC configured OK");

  i2s_zero_dma_buffer(I2S_NUM_0);

  // Re-assert AMP_DIN LOW after pin mux setup
  pinMode(AMP_DIN, OUTPUT);
  digitalWrite(AMP_DIN, LOW);

  currentI2SMode = MODE_MIC;
}

// ════════════════════════════════════════════════════════════════
//  I2S init: Speaker (TX) on I2S0
// ════════════════════════════════════════════════════════════════
void i2sSpkInit() {
  if (currentI2SMode != MODE_NONE) {
    i2s_driver_uninstall(I2S_NUM_0);
    delay(20);
  }

  i2s_config_t cfg = {};
  cfg.mode = (i2s_mode_t)(I2S_MODE_MASTER | I2S_MODE_TX);
  cfg.sample_rate = SPK_SR;
  cfg.bits_per_sample = I2S_BITS_PER_SAMPLE_16BIT;
  cfg.channel_format = I2S_CHANNEL_FMT_RIGHT_LEFT;
  cfg.communication_format = I2S_COMM_FORMAT_I2S;
  cfg.intr_alloc_flags = ESP_INTR_FLAG_LEVEL1;
  cfg.dma_buf_count = 16;
  cfg.dma_buf_len = 1024;
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
  if (e != ESP_OK) Serial.printf("[I2S] SPK install failed: %d\n", (int)e);

  e = i2s_set_pin(I2S_NUM_0, &pins);
  if (e != ESP_OK) Serial.printf("[I2S] SPK pins failed: %d\n", (int)e);
  else Serial.println("[I2S] SPEAKER configured OK");

  i2s_zero_dma_buffer(I2S_NUM_0);
  currentI2SMode = MODE_SPEAKER;
}

// ════════════════════════════════════════════════════════════════
//  Amplifier control (no SD_MODE pin — amp always on)
// ════════════════════════════════════════════════════════════════
void enableAmp() { Serial.println("[AMP] Ready"); }
void disableAmp() { /* No SD_MODE pin wired */ }

// ════════════════════════════════════════════════════════════════
//  Stereo interleave buffer
//  512 mono samples → 1024 int16 stereo → 2048 bytes per I2S write
// ════════════════════════════════════════════════════════════════
#define STEREO_CHUNK_MONO 512
static int16_t stereoChunk[STEREO_CHUNK_MONO * 2];
// Temp buffer to read mono PCM bytes from ring buffer
static uint8_t monoReadBuf[STEREO_CHUNK_MONO * 2];  // 1024 bytes

// ════════════════════════════════════════════════════════════════
//  Streaming playback — called repeatedly from loop()
//  Reads from ring buffer, interleaves to stereo, writes to I2S
// ════════════════════════════════════════════════════════════════
void streamPlaybackTick() {
  size_t avail = ringAvailable();

  // ── BUFFERING: wait for enough data before switching to speaker ──
  if (streamState == STREAM_BUFFERING) {
    if (avail >= STREAM_START_THRESHOLD || (endMarkerReceived && avail > 0)) {
      Serial.printf("[STREAM] Starting playback (%u bytes buffered)\n", avail);

      // Switch I2S to speaker mode
      i2sSpkInit();
      enableAmp();
      delay(30);  // Let amp stabilize

      streamState = STREAM_PLAYING;
    }
    return;
  }

  // ── PLAYING / DRAINING: feed ring buffer data to I2S ──
  if (streamState == STREAM_PLAYING || streamState == STREAM_DRAINING) {

    // If end marker received, transition to draining
    if (streamState == STREAM_PLAYING && endMarkerReceived) {
      streamState = STREAM_DRAINING;
      Serial.printf("[STREAM] End marker received — draining %u remaining bytes\n", avail);
    }

    // Read a chunk of mono PCM from ring buffer
    size_t wantBytes = STEREO_CHUNK_MONO * 2;  // 1024 bytes = 512 mono samples

    if (avail >= wantBytes) {
      // Full chunk available
      ringRead(monoReadBuf, wantBytes);
    } else if (streamState == STREAM_DRAINING && avail > 0) {
      // Partial final chunk — pad remainder with silence
      size_t got = avail & ~1;  // Ensure even byte count (16-bit alignment)
      if (got == 0) goto finish;
      ringRead(monoReadBuf, got);
      memset(monoReadBuf + got, 0, wantBytes - got);  // Silence pad
      wantBytes = got;  // Only interleave actual + padded data
      // We'll mark this as the last chunk below
    } else if (streamState == STREAM_DRAINING && avail == 0) {
      // All data played — finish up
      goto finish;
    } else {
      // Waiting for more data from BLE — write silence to prevent DMA underrun
      memset(stereoChunk, 0, sizeof(stereoChunk));
      size_t written = 0;
      i2s_write(I2S_NUM_0, stereoChunk, sizeof(stereoChunk), &written, 10 / portTICK_PERIOD_MS);
      return;
    }

    // Interleave mono → stereo (L=R for dual MAX98357A)
    const int16_t* monoSrc = (const int16_t*)monoReadBuf;
    size_t monoSamples = wantBytes / 2;
    for (size_t i = 0; i < monoSamples; i++) {
      stereoChunk[2 * i]     = monoSrc[i];  // LEFT
      stereoChunk[2 * i + 1] = monoSrc[i];  // RIGHT
    }

    // Write stereo data to I2S (blocking — DMA will pace us)
    size_t bytesToWrite = monoSamples * 2 * sizeof(int16_t);
    size_t written = 0;
    i2s_write(I2S_NUM_0, stereoChunk, bytesToWrite, &written, portMAX_DELAY);

    // If this was a partial chunk in drain mode, we're done
    if (streamState == STREAM_DRAINING && ringAvailable() == 0) {
      goto finish;
    }
    return;
  }
  return;

finish:
  // ── Playback complete — flush DMA with silence, switch to mic ──
  Serial.printf("[STREAM] Playback complete! (received %u bytes, %u chunks, dropped %u, lost pkts %u)\n",
                rxTotalBytes, rxChunkCount, rxDroppedBytes, rxLostPackets);

  // Fade would be ideal but we've already written the data — instead,
  // flush with silence to drain the DMA pipeline cleanly
  memset(stereoChunk, 0, sizeof(stereoChunk));
  for (int i = 0; i < 8; i++) {
    size_t written = 0;
    i2s_write(I2S_NUM_0, stereoChunk, sizeof(stereoChunk), &written, portMAX_DELAY);
  }
  delay(200);
  i2s_stop(I2S_NUM_0);

  // Claim AMP_DIN LOW before driver teardown
  pinMode(AMP_DIN, OUTPUT);
  digitalWrite(AMP_DIN, LOW);

  // Switch back to mic
  i2sMicInit();

  // Reset state
  streamState = STREAM_IDLE;
  endMarkerReceived = false;
  ringReset();
  rxChunkCount = 0;
  rxTotalBytes = 0;
  rxDroppedBytes = 0;
  rxLostPackets = 0;
}

// ════════════════════════════════════════════════════════════════
//  BLE Server Callbacks
// ════════════════════════════════════════════════════════════════
class ServerCallbacks : public NimBLEServerCallbacks {
  void onConnect(NimBLEServer* pServer, NimBLEConnInfo& connInfo) override {
    deviceConnected = true;
    Serial.println("\n[BLE] Android connected!");
    pServer->updateConnParams(connInfo.getConnHandle(), 6, 12, 0, 500);
    Serial.println("[BLE] Requested fast connection parameters");
  }

  void onDisconnect(NimBLEServer* pServer, NimBLEConnInfo& connInfo, int reason) override {
    deviceConnected = false;
    streamState = STREAM_IDLE;
    endMarkerReceived = false;
    ringReset();
    Serial.printf("[BLE] Android disconnected (reason=%d)\n", reason);
    NimBLEDevice::startAdvertising();
    Serial.println("[BLE] Advertising restarted, waiting for reconnection...");
  }

  void onMTUChange(uint16_t mtu, NimBLEConnInfo& connInfo) override {
    Serial.printf("[BLE] MTU changed to %u (payload: %u bytes)\n", mtu, mtu - 3);
  }
};

// ════════════════════════════════════════════════════════════════
//  Audio RX Callback (Android → ESP32 speaker data)
//  Runs on NimBLE task — writes to ring buffer
// ════════════════════════════════════════════════════════════════
class AudioRxCallbacks : public NimBLECharacteristicCallbacks {
  uint8_t expectedSeq = 0;

  void onWrite(NimBLECharacteristic* pChar, NimBLEConnInfo& connInfo) override {
    NimBLEAttValue val = pChar->getValue();
    const uint8_t* data = val.data();
    size_t len = val.size();
    if (len < BLE_HEADER_SIZE) return;

    uint8_t tag = data[0];
    uint8_t seq = data[1];
    const uint8_t* payload = data + BLE_HEADER_SIZE;
    size_t payloadLen = len - BLE_HEADER_SIZE;

    if (tag == 'A' && payloadLen > 0) {
      // Sequence number tracking — detect packet loss from WRITE_NR
      if (rxChunkCount > 0 && seq != expectedSeq) {
        uint8_t gap = (seq - expectedSeq);
        rxLostPackets += gap;
        Serial.printf("[BLE-RX] SEQ gap: expected %u got %u (lost ~%u pkts)\n",
                      expectedSeq, seq, (unsigned int)gap);
      }
      expectedSeq = seq + 1;

      // Write to ring buffer
      size_t written = ringWrite(payload, payloadLen);
      rxChunkCount++;
      rxTotalBytes += written;

      if (written < payloadLen) {
        rxDroppedBytes += (payloadLen - written);
        if (rxDroppedBytes % 5000 < payloadLen) {
          Serial.printf("[BLE-RX] Ring buffer full — dropped %u bytes total\n", rxDroppedBytes);
        }
      }

      // Progress every 50 chunks
      if (rxChunkCount % 50 == 0) {
        Serial.printf("[BLE-RX] %u chunks, %u bytes, ring: %u/%u\n",
                      rxChunkCount, rxTotalBytes,
                      (unsigned int)ringAvailable(), RING_SIZE);
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

    if (tag == 'S') {
      // Start marker — begin buffering audio
      Serial.println("[BLE-CTRL] Start marker — buffering audio");
      ringReset();
      endMarkerReceived = false;
      rxChunkCount = 0;
      rxTotalBytes = 0;
      rxDroppedBytes = 0;
      rxLostPackets = 0;
      streamState = STREAM_BUFFERING;

    } else if (tag == 'E') {
      // End marker — signal playback to drain remaining data
      Serial.printf("[BLE-CTRL] End marker — %u bytes received (%u chunks)\n",
                    rxTotalBytes, rxChunkCount);
      endMarkerReceived = true;
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

    uint8_t pkt[BLE_HEADER_SIZE + fragSize];
    pkt[0] = 'A';
    pkt[1] = txSeqNum++;
    memcpy(pkt + BLE_HEADER_SIZE, pcmData + offset, fragSize);

    pAudioTxChar->setValue(pkt, BLE_HEADER_SIZE + fragSize);
    pAudioTxChar->notify();

    offset += fragSize;
    delay(2);
  }
}

// ════════════════════════════════════════════════════════════════
//  Setup
// ════════════════════════════════════════════════════════════════
void setup() {
  Serial.begin(115200);
  delay(1000);

  Serial.println("\n\n============================================================");
  Serial.println("  ESP32-C6 VOICE ASSISTANT — Streaming Playback");
  Serial.println("============================================================");
  Serial.println("BCLK=GPIO18, WS=GPIO22 (shared mic & speaker)");
  Serial.println("MIC_SD=GPIO16, AMP_DIN=GPIO20");
  Serial.printf("PTT Button=GPIO%d\n", PTT_PIN);
  Serial.println("Transport: BLE (WRITE_NR) + Streaming Ring Buffer");
  Serial.println("============================================================\n");

  // Push-to-talk button (active HIGH — pressed = HIGH, released = LOW)
  pinMode(PTT_PIN, INPUT_PULLDOWN);
  delay(100);

  int btnState = digitalRead(PTT_PIN);
  Serial.printf("[PTT] GPIO%d initial state: %s (%d)\n",
                PTT_PIN, btnState == LOW ? "LOW (not pressed)" : "HIGH (pressed!)", btnState);
  if (btnState == HIGH) {
    Serial.println("[PTT] WARNING: Button reads HIGH at boot! Check wiring.");
    while (digitalRead(PTT_PIN) == HIGH) delay(100);
    Serial.println("[PTT] Button released, continuing...");
  }

  // Initialize microphone
  i2sMicInit();

  // ── Initialize BLE ──
  Serial.println("\n[BLE] Initializing...");
  NimBLEDevice::init("AIGlasses-ESP32C6");
  NimBLEDevice::setMTU(BLE_MTU);
  NimBLEDevice::setPower(ESP_PWR_LVL_P9);

  pServer = NimBLEDevice::createServer();
  pServer->setCallbacks(new ServerCallbacks());

  NimBLEService* pService = pServer->createService(SERVICE_UUID);

  // Audio TX: ESP32 mic → Android (NOTIFY)
  pAudioTxChar = pService->createCharacteristic(
    CHAR_AUDIO_TX_UUID,
    NIMBLE_PROPERTY::NOTIFY
  );

  // Audio RX: Android TTS → ESP32 speaker (WRITE_NR for max throughput)
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

  pService->start();

  NimBLEAdvertising* pAdvertising = NimBLEDevice::getAdvertising();
  pAdvertising->addServiceUUID(SERVICE_UUID);
  pAdvertising->start();

  Serial.println("[BLE] GATT server started");
  Serial.println("[BLE] Advertising as 'AIGlasses-ESP32C6'");

  Serial.println();
  Serial.println("============================================================");
  Serial.printf("  READY! Hold GPIO%d to talk, release to send\n", PTT_PIN);
  Serial.println("  Audio plays AS it arrives — no more buffer overflow!");
  Serial.println("============================================================\n");
}

// ════════════════════════════════════════════════════════════════
//  Loop
// ════════════════════════════════════════════════════════════════
void loop() {
  // ── Streaming playback state machine ──
  if (streamState != STREAM_IDLE) {
    streamPlaybackTick();
    return;  // Don't read mic while playing
  }

  // Wait for BLE connection
  if (!deviceConnected) {
    delay(100);
    return;
  }

  // Debounced button read (active HIGH)
  bool raw1 = (digitalRead(PTT_PIN) == HIGH);
  delay(10);
  bool raw2 = (digitalRead(PTT_PIN) == HIGH);
  bool pressed = raw1 && raw2;

  static bool wasPressed = false;

  if (!pressed) {
    if (wasPressed) {
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
    txSeqNum = 0;
    Serial.println("[PTT] Pressed -> Streaming audio via BLE...");
  }
  wasPressed = true;

  // Read mic audio
  size_t bytesRead32 = 0;
  esp_err_t ok = i2s_read(I2S_NUM_0, micBuf32, sizeof(micBuf32), &bytesRead32, 20 / portTICK_PERIOD_MS);
  if (ok != ESP_OK || bytesRead32 == 0) return;

  int samples = bytesRead32 / 4;
  for (int i = 0; i < samples; i++) {
    micBuf[i] = (int16_t)(micBuf32[i] >> 16);
  }
  size_t bytesOut = samples * 2;

  static int debugChunk = 0;
  if (++debugChunk % 20 == 0) {
    int16_t minVal = 32767, maxVal = -32768;
    long sum = 0;
    for (int i = 0; i < samples; i++) {
      int16_t s = micBuf[i];
      if (s < minVal) minVal = s;
      if (s > maxVal) maxVal = s;
      sum += abs(s);
    }
    Serial.printf("[MIC] min=%d max=%d avgAmp=%ld (samples=%d)\n",
                  minVal, maxVal, sum / samples, samples);
  }

  sendMicChunkViaBLE((uint8_t*)micBuf, bytesOut);
  delay(1);
}
