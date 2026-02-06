#include <WiFi.h>
#include <WebSocketsClient.h>
#include <driver/i2s.h>

// ---------------- WiFi ----------------
const char* WIFI_SSID = "Ayoub";
const char* WIFI_PASS = "12345678";

// ---------------- ngrok WS endpoint (HTTP scheme) ----------------
const char* WS_HOST = "della-insolvable-drolly.ngrok-free.dev";
const uint16_t WS_PORT = 80;
const char* WS_PATH = "/";

WebSocketsClient ws;

// ---------------- Shared I2S Pins (Mic + Speaker share BCLK & WS) ----------------
#define I2S_BCLK        18   // Shared clock  -> MAX98357A BCLK & INMP441 SCK
#define I2S_WS          22   // Shared WS     -> MAX98357A LRC  & INMP441 WS
#define AMP_DOUT        17   // Data out to speaker (MAX98357A DIN)
#define MIC_DIN         16   // Data in from mic    (INMP441 SD)

// ---------------- Push-to-talk ----------------
#define PTT_PIN         23

// ---------------- Audio settings ----------------
static const int MIC_SR = 16000;
static const int SPK_SR = 22050;
static const int SAMPLES_PER_CHUNK = 512;
static int16_t micBuf[SAMPLES_PER_CHUNK];

// ---------------- Speaker buffer ----------------
#define MAX_AUDIO_BUFFER (200 * 1024)
static uint8_t audioBuffer[MAX_AUDIO_BUFFER];
static size_t audioBufferLen = 0;
static bool playAudio = false;
static unsigned int chunkCount = 0;

// ---------------- I2S init: Mic (RX) on I2S0 ----------------
void i2sMicInit() {
  i2s_driver_uninstall(I2S_NUM_0);
  delay(50);

  i2s_config_t cfg = {};
  cfg.mode = (i2s_mode_t)(I2S_MODE_MASTER | I2S_MODE_RX);
  cfg.sample_rate = MIC_SR;
  cfg.bits_per_sample = I2S_BITS_PER_SAMPLE_16BIT;
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
  pins.data_in_num = MIC_DIN;

  Serial.println("[I2S] Installing MIC driver...");
  esp_err_t e = i2s_driver_install(I2S_NUM_0, &cfg, 0, NULL);
  if (e != ESP_OK) {
    Serial.printf("[I2S] i2s_driver_install failed: %d\n", (int)e);
  }

  Serial.println("[I2S] Setting MIC pins...");
  e = i2s_set_pin(I2S_NUM_0, &pins);
  if (e != ESP_OK) {
    Serial.printf("[I2S] MIC i2s_set_pin failed: %d\n", (int)e);
  } else {
    Serial.println("[I2S] MIC pins OK");
  }

  i2s_zero_dma_buffer(I2S_NUM_0);
}

// ---------------- I2S init: Speaker (TX) on I2S0 ----------------
void i2sSpkInit() {
  i2s_driver_uninstall(I2S_NUM_0);
  delay(50);

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
  pins.data_out_num = AMP_DOUT;
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
    Serial.println("[I2S] SPEAKER pins OK");
  }

  i2s_zero_dma_buffer(I2S_NUM_0);
}

// ---------------- Play speaker ----------------
void playSpeaker() {
  if (audioBufferLen == 0) return;

  Serial.printf("[SPEAKER] Playing %u bytes (%.2fs)\n",
                audioBufferLen, (float)audioBufferLen / (SPK_SR * 2));

  // Switch to speaker mode
  i2sSpkInit();
  delay(50);

  // Play audio
  size_t offset = 0;
  while (offset < audioBufferLen) {
    size_t chunk = min((size_t)2048, audioBufferLen - offset);
    size_t written = 0;
    i2s_write(I2S_NUM_0, audioBuffer + offset, chunk, &written, portMAX_DELAY);
    offset += written;
  }

  delay(100);

  Serial.println("[SPEAKER] Done");

  // Switch back to mic
  i2sMicInit();

  // Clear buffer
  audioBufferLen = 0;
  chunkCount = 0;
}

// ---------------- Show PCM data ----------------
void showPCM(uint8_t* data, size_t len) {
  if (len < 64) return;

  int16_t* samples = (int16_t*)data;
  size_t totalSamples = len / 2;

  // Show samples from the middle of the chunk (more likely to have audio)
  size_t startIdx = totalSamples / 2;
  size_t numSamples = min((size_t)16, totalSamples - startIdx);

  Serial.printf("[PCM DATA] Showing 16 samples from middle (offset %u):\n", startIdx);

  int16_t minVal = 32767, maxVal = -32768;
  long sum = 0;

  for (size_t i = 0; i < numSamples; i++) {
    int16_t val = samples[startIdx + i];
    Serial.printf("  [%2u] %6d | ", startIdx + i, val);

    // Track stats
    if (val < minVal) minVal = val;
    if (val > maxVal) maxVal = val;
    sum += abs(val);

    // Visual bar
    int bars = abs(val) / 500;
    bars = min(bars, 40);
    for (int b = 0; b < bars; b++) {
      Serial.print(val >= 0 ? "=" : "-");
    }
    Serial.println();
  }

  int avgAmp = sum / numSamples;
  Serial.printf("Stats: Min=%d, Max=%d, AvgAmp=%d\n\n", minVal, maxVal, avgAmp);
}

// ---------------- WebSocket events ----------------
void onWsEvent(WStype_t type, uint8_t* payload, size_t length) {
  switch (type) {
    case WStype_CONNECTED:
      Serial.println("[WS] Connected");
      break;

    case WStype_DISCONNECTED:
      Serial.println("[WS] Disconnected");
      break;

    case WStype_TEXT:
      Serial.printf("[WS] Reply text: %s\n", (char*)payload);
      break;

    case WStype_BIN: {
      if (length < 1) break;
      uint8_t tag = payload[0];

      if (tag == 'A') {
        size_t audioLen = length - 1;

        if (audioBufferLen + audioLen > MAX_AUDIO_BUFFER) {
          Serial.println("[AUDIO] Buffer full!");
          break;
        }

        memcpy(audioBuffer + audioBufferLen, payload + 1, audioLen);
        audioBufferLen += audioLen;
        chunkCount++;

        // Show PCM data for chunks 5, 10, 15 (skip early silent chunks)
        if (chunkCount == 5 || chunkCount == 10 || chunkCount == 15) {
          Serial.printf("[AUDIO] Chunk #%u (%u bytes)\n", chunkCount, audioLen);
          showPCM(payload + 1, audioLen);
        }

        // Progress every 10 chunks
        if (chunkCount % 10 == 0) {
          Serial.printf("[AUDIO] Received %u chunks, %u bytes total\n",
                        chunkCount, audioBufferLen);
        }

      } else if (tag == 'E') {
        Serial.printf("[AUDIO] End marker - total %u bytes\n", audioBufferLen);
        if (audioBufferLen > 0) {
          playAudio = true;
        }
      }
      break;
    }

    default:
      break;
  }
}

// ---------------- Setup ----------------
void setup() {
  Serial.begin(115200);
  delay(1000);
  Serial.println("\n\n=== ESP32-C6 VOICE ASSISTANT (Shared I2S Bus) ===");
  Serial.println("BCLK=GPIO18, WS=GPIO22, DOUT=GPIO17, DIN=GPIO16, PTT=GPIO23");

  pinMode(PTT_PIN, INPUT_PULLUP);
  delay(100);  // Let pull-up settle

  // Debug: show button state at boot
  int btnState = digitalRead(PTT_PIN);
  Serial.printf("[PTT] GPIO%d initial state: %s (%d)\n",
                PTT_PIN, btnState == HIGH ? "HIGH (not pressed)" : "LOW (pressed!)", btnState);
  if (btnState == LOW) {
    Serial.println("[PTT] WARNING: Button reads LOW at boot! Check wiring.");
    Serial.println("[PTT] Button should connect GPIO20 to GND when pressed.");
    Serial.println("[PTT] Waiting for button to be released...");
    while (digitalRead(PTT_PIN) == LOW) {
      delay(100);
    }
    Serial.println("[PTT] Button released, continuing...");
  }

  WiFi.mode(WIFI_STA);
  WiFi.begin(WIFI_SSID, WIFI_PASS);

  Serial.print("[WiFi] connecting");
  uint32_t start = millis();
  while (WiFi.status() != WL_CONNECTED) {
    delay(300);
    Serial.print(".");
    if (millis() - start > 20000) {
      Serial.println("\n[WiFi] timeout (check SSID/PASS)");
      start = millis();
    }
  }
  Serial.println("\n[WiFi] connected");
  Serial.print("[WiFi] IP: "); Serial.println(WiFi.localIP());
  Serial.print("[WiFi] RSSI: "); Serial.println(WiFi.RSSI());

  i2sMicInit();

  Serial.printf("[WS] Connecting to %s:%u%s\n", WS_HOST, WS_PORT, WS_PATH);
  ws.begin(WS_HOST, WS_PORT, WS_PATH);
  ws.onEvent(onWsEvent);
  ws.setReconnectInterval(2000);

  Serial.printf("[PTT] Hold GPIO%d to talk, release to send\n", PTT_PIN);
}

// ---------------- Loop ----------------
void loop() {
  ws.loop();

  // Play audio if ready
  if (playAudio) {
    playSpeaker();
    playAudio = false;
  }

  if (!ws.isConnected()) { delay(10); return; }

  // Debounced button read: read twice with a small gap
  bool raw1 = (digitalRead(PTT_PIN) == LOW);
  delay(10);
  bool raw2 = (digitalRead(PTT_PIN) == LOW);
  bool pressed = raw1 && raw2;  // Only count as pressed if both reads agree

  static bool wasPressed = false;

  if (!pressed) {
    if (wasPressed) {
      const uint8_t E = 'E';
      ws.sendBIN((uint8_t*)&E, 1);
      Serial.println("[PTT] Released -> Sent E");
    }
    wasPressed = false;
    delay(20);
    return;
  }

  if (!wasPressed) {
    Serial.println("[PTT] Pressed -> Streaming audio");
  }
  wasPressed = true;

  size_t bytesRead = 0;
  esp_err_t ok = i2s_read(I2S_NUM_0, micBuf, sizeof(micBuf), &bytesRead, 20 / portTICK_PERIOD_MS);
  if (ok != ESP_OK || bytesRead == 0) return;

  static uint8_t pkt[1 + sizeof(micBuf)];
  pkt[0] = 'A';
  memcpy(pkt + 1, micBuf, bytesRead);
  ws.sendBIN(pkt, 1 + bytesRead);

  delay(1);
}
