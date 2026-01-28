/*
 * WIRING DIAGRAM:
 *
 *   INMP441 Microphone:
 *   -------------------
 *   VDD  -> 3.3V
 *   GND  -> GND
 *   SD   -> GPIO 4  (Data)
 *   SCK  -> GPIO 5  (Bit Clock)
 *   WS   -> GPIO 6  (Word Select / LRCLK)
 *   L/R  -> GND     (Left channel)
 *
 *   MAX98357A Amplifier:
 *   --------------------
 *   VIN  -> 3.3V or 5V
 *   GND  -> GND
 *   DIN  -> GPIO 15 (Data from ESP32)
 *   BCLK -> GPIO 16 (Bit Clock)
 *   LRC  -> GPIO 17 (Word Select / LRCLK)
 *   GAIN -> unconnected (9dB default) or GND (12dB) or VIN (15dB)
 *   SD   -> unconnected or HIGH to enable (has internal pull-up)
 *
 *    Button (Push-to-Talk):
 *   -------------------------------
 *   One side -> GPIO 0 (BOOT button on board) or GPIO 18
 *   Other side -> GND
 */

#include <Arduino.h>
#include <WiFi.h>
#include <WiFiClientSecure.h>

#include "driver/i2s_std.h"
#include "esp_system.h"

// ==================== USER CONFIGURATION ====================
// WiFi credentials - UPDATE THESE
const char* WIFI_SSID     = "";
const char* WIFI_PASSWORD = "";

// OpenAI API - UPDATE YOUR API KEY
const char* OPENAI_HOST = "api.openai.com";
const int   OPENAI_PORT = 443;
const char* OPENAI_API_KEY = "";
// ============================================================

// Text model (Chat Completions)
const char* TEXT_MODEL = "gpt-4o-mini"; // from Models list  [oai_citation:5‡OpenAI Platform](https://platform.openai.com/docs/models?utm_source=chatgpt.com)

// TTS model + voice (valid models: tts-1, tts-1-hd)
const char* TTS_MODEL = "tts-1";
const char* TTS_VOICE = "coral"; // voices: alloy, echo, fable, onyx, nova, shimmer, coral

// STT model (valid model: whisper-1)
const char* STT_MODEL = "whisper-1";

// System prompt for AI assistant behavior
const char* SYSTEM_PROMPT = "You are a helpful, concise voice assistant. Keep responses brief and conversational since they will be spoken aloud. Aim for 1-2 sentences when possible.";

// -------------------- I2S (MAX98357A Speaker) --------------------
// Safe GPIOs for ESP32-S3 N16R8 (avoiding USB, flash, PSRAM pins)
static const int I2S_BCLK = 16; // BCLK
static const int I2S_WS   = 17; // LRC / WS
static const int I2S_DOUT = 15; // DIN on MAX98357A (data into amp)

static const int PCM_SAMPLE_RATE = 24000; // OpenAI PCM is 24kHz  [oai_citation:7‡OpenAI Platform](https://platform.openai.com/docs/guides/text-to-speech)

static i2s_chan_handle_t tx_chan = NULL;

// -------------------- I2S Mic (INMP441 or similar) --------------------
// Safe GPIOs for ESP32-S3 N16R8
static const int I2S_MIC_BCLK = 5;  // SCK
static const int I2S_MIC_WS   = 6;  // WS / LRCK
static const int I2S_MIC_DIN  = 4;  // SD (data from mic)

static const int MIC_SAMPLE_RATE = 16000;  // 16 kHz is typical for STT
static const int MIC_RECORD_SECONDS = 3;   // short clip to keep RAM low
static const int MIC_SAMPLES = MIC_SAMPLE_RATE * MIC_RECORD_SECONDS;
static const int MIC_BYTES = MIC_SAMPLES * sizeof(int16_t);

static i2s_chan_handle_t rx_chan = NULL;

// -------------------- Button / Trigger --------------------
// Set to -1 to use Serial input, or set to a GPIO pin for button trigger
// GPIO 0 is the BOOT button on the DevKitC board - convenient for push-to-talk!
static const int BUTTON_PIN = 0;   // Use onboard BOOT button (or -1 for Serial input)
static const int LED_PIN = 48;     // Onboard RGB LED on ESP32-S3 DevKitC (WS2812)

// Device states for feedback
enum DeviceState {
  STATE_IDLE,
  STATE_RECORDING,
  STATE_TRANSCRIBING,
  STATE_THINKING,
  STATE_SPEAKING
};
static DeviceState currentState = STATE_IDLE;

// -------------------- Helpers --------------------
static void die(const char* msg) {
  Serial.println(msg);
  while (true) delay(1000);
}

static void setState(DeviceState state) {
  currentState = state;
  const char* stateNames[] = {"IDLE", "RECORDING", "TRANSCRIBING", "THINKING", "SPEAKING"};
  Serial.printf("[State: %s]\n", stateNames[state]);

  // Optional LED feedback (if LED_PIN is configured)
  if (LED_PIN >= 0) {
    switch (state) {
      case STATE_IDLE:
        digitalWrite(LED_PIN, LOW);
        break;
      case STATE_RECORDING:
        digitalWrite(LED_PIN, HIGH);  // Solid on while recording
        break;
      case STATE_TRANSCRIBING:
      case STATE_THINKING:
        // Could implement blinking here with a timer
        digitalWrite(LED_PIN, HIGH);
        break;
      case STATE_SPEAKING:
        digitalWrite(LED_PIN, LOW);
        break;
    }
  }
}

static bool waitForTrigger() {
  if (BUTTON_PIN >= 0) {
    // Button mode: wait for button press
    while (digitalRead(BUTTON_PIN) == HIGH) {
      delay(10);
    }
    // Debounce
    delay(50);
    // Wait for release
    while (digitalRead(BUTTON_PIN) == LOW) {
      delay(10);
    }
    delay(50);
    return true;
  } else {
    // Serial mode: wait for Enter key
    Serial.println("\nPress Enter to record (3 seconds)...");
    while (!Serial.available()) delay(10);
    while (Serial.available()) Serial.read();  // Clear buffer
    return true;
  }
}

static String readLineBlocking() {
  while (!Serial.available()) delay(10);
  String s = Serial.readStringUntil('\n');
  s.trim();
  return s;
}

static String jsonEscape(const String& in) {
  String out;
  out.reserve(in.length() + 16);
  for (size_t i = 0; i < in.length(); i++) {
    char c = in[i];
    switch (c) {
      case '\\': out += "\\\\"; break;
      case '"':  out += "\\\""; break;
      case '\n': out += "\\n";  break;
      case '\r': out += "\\r";  break;
      case '\t': out += "\\t";  break;
      default:
        // keep normal ASCII; (for full unicode, you'd need more work)
        out += c;
        break;
    }
  }
  return out;
}

static String jsonExtractStringField(const String& json, const char* fieldName) {
  String key = String("\"") + fieldName + "\"";
  int idx = json.indexOf(key);
  if (idx < 0) return "";
  idx = json.indexOf(':', idx);
  if (idx < 0) return "";
  idx = json.indexOf('"', idx);
  if (idx < 0) return "";
  idx++;
  String out;
  out.reserve(128);
  bool escape = false;
  for (int i = idx; i < (int)json.length(); i++) {
    char c = json[i];
    if (escape) {
      if (c == 'n') out += '\n';
      else if (c == 'r') out += '\r';
      else if (c == 't') out += '\t';
      else out += c;
      escape = false;
      continue;
    }
    if (c == '\\') { escape = true; continue; }
    if (c == '"') break;
    out += c;
  }
  out.trim();
  return out;
}

// Read HTTP status line + headers; returns (statusCode, isChunked, contentLength)
// Leaves the stream positioned at start of body.
static bool readHttpHeaders(WiFiClientSecure& client, int& statusCode, bool& chunked, int& contentLength) {
  statusCode = -1;
  chunked = false;
  contentLength = -1;

  // Status line
  String status = client.readStringUntil('\n');
  status.trim();
  // Example: HTTP/1.1 200 OK
  int sp1 = status.indexOf(' ');
  int sp2 = status.indexOf(' ', sp1 + 1);
  if (sp1 < 0 || sp2 < 0) return false;
  statusCode = status.substring(sp1 + 1, sp2).toInt();

  // Headers
  while (true) {
    String line = client.readStringUntil('\n');
    if (!client.connected() && line.length() == 0) return false;
    if (line == "\r" || line.length() == 0) break;
    line.trim();

    String lower = line;
    lower.toLowerCase();

    if (lower.startsWith("content-length:")) {
      contentLength = line.substring(strlen("content-length:")).toInt();
    } else if (lower.startsWith("transfer-encoding:") && lower.indexOf("chunked") >= 0) {
      chunked = true;
    }
  }
  return true;
}

static bool readChunkedBodyToI2S(WiFiClientSecure& client) {
  uint8_t buf[1024];

  while (true) {
    String sizeLine = client.readStringUntil('\n');
    sizeLine.trim();
    if (sizeLine.length() == 0) continue;

    int chunkSize = (int) strtol(sizeLine.c_str(), nullptr, 16);
    if (chunkSize == 0) {
      // Consume trailing headers after last chunk
      client.readStringUntil('\n');
      break;
    }

    int remaining = chunkSize;
    while (remaining > 0) {
      int toRead = remaining > (int)sizeof(buf) ? (int)sizeof(buf) : remaining;
      int got = client.readBytes(buf, toRead);
      if (got <= 0) return false;

      size_t written = 0;
      esp_err_t err = i2s_channel_write(tx_chan, buf, got, &written, portMAX_DELAY);
      if (err != ESP_OK) return false;

      remaining -= got;
    }

    // Each chunk ends with \r\n
    client.read(); // '\r'
    client.read(); // '\n'
  }
  return true;
}

static bool readFixedBodyToI2S(WiFiClientSecure& client, int contentLength) {
  uint8_t buf[1024];
  int remaining = contentLength;

  while (remaining > 0) {
    int toRead = remaining > (int)sizeof(buf) ? (int)sizeof(buf) : remaining;
    int got = client.readBytes(buf, toRead);
    if (got <= 0) return false;

    size_t written = 0;
    esp_err_t err = i2s_channel_write(tx_chan, buf, got, &written, portMAX_DELAY);
    if (err != ESP_OK) return false;

    remaining -= got;
  }
  return true;
}

// -------------------- OpenAI calls --------------------
static String openaiChat(const String& userText) {
  WiFiClientSecure client;
  client.setInsecure(); // simplest; for production, use root CA

  if (!client.connect(OPENAI_HOST, OPENAI_PORT)) {
    Serial.println("Failed to connect to OpenAI (chat).");
    return "";
  }

  String promptEsc = jsonEscape(userText);
  String systemEsc = jsonEscape(String(SYSTEM_PROMPT));

  // Chat Completions payload with system prompt
  String body =
    String("{\"model\":\"") + TEXT_MODEL + "\","
    "\"messages\":["
    "{\"role\":\"system\",\"content\":\"" + systemEsc + "\"},"
    "{\"role\":\"user\",\"content\":\"" + promptEsc + "\"}],"
    "\"max_tokens\":150,"
    "\"temperature\":0.7"
    "}";

  String req =
    String("POST /v1/chat/completions HTTP/1.1\r\n") +
    "Host: " + OPENAI_HOST + "\r\n" +
    "Authorization: Bearer " + OPENAI_API_KEY + "\r\n" +
    "Content-Type: application/json\r\n" +
    "Connection: close\r\n" +
    "Content-Length: " + String(body.length()) + "\r\n\r\n" +
    body;

  client.print(req);

  int statusCode;
  bool chunked;
  int contentLength;
  if (!readHttpHeaders(client, statusCode, chunked, contentLength) || statusCode != 200) {
    Serial.printf("Chat HTTP status: %d\n", statusCode);
    String errBody = client.readString();
    Serial.println(errBody);
    return "";
  }

  // Read full JSON response (small)
  String resp = client.readString();
  client.stop();

  // Small JSON extraction: find "content":"..."
  // (Good enough for short replies; avoids ArduinoJson to keep flash/RAM down.)
  return jsonExtractStringField(resp, "content");
}

static void writeWavHeader(uint8_t* header, uint32_t dataBytes, uint32_t sampleRate) {
  // 44-byte PCM WAV header, mono 16-bit
  uint32_t byteRate = sampleRate * 2; // 16-bit mono
  uint32_t blockAlign = 2;

  memcpy(header + 0, "RIFF", 4);
  uint32_t chunkSize = 36 + dataBytes;
  memcpy(header + 4, &chunkSize, 4);
  memcpy(header + 8, "WAVE", 4);
  memcpy(header + 12, "fmt ", 4);
  uint32_t subChunk1Size = 16;
  memcpy(header + 16, &subChunk1Size, 4);
  uint16_t audioFormat = 1;
  uint16_t numChannels = 1;
  uint16_t bitsPerSample = 16;
  memcpy(header + 20, &audioFormat, 2);
  memcpy(header + 22, &numChannels, 2);
  memcpy(header + 24, &sampleRate, 4);
  memcpy(header + 28, &byteRate, 4);
  memcpy(header + 32, &blockAlign, 2);
  memcpy(header + 34, &bitsPerSample, 2);
  memcpy(header + 36, "data", 4);
  memcpy(header + 40, &dataBytes, 4);
}

static bool recordMicPcm(int16_t* pcm, size_t samples) {
  if (rx_chan == NULL) return false;

  // Short throwaway read to let the mic stabilize
  size_t throwawayBytes = 0;
  int16_t throwaway[256];
  i2s_channel_read(rx_chan, throwaway, sizeof(throwaway), &throwawayBytes, 50);

  size_t totalBytes = samples * sizeof(int16_t);
  size_t offset = 0;
  while (offset < totalBytes) {
    size_t toRead = totalBytes - offset;
    if (toRead > 1024) toRead = 1024;
    size_t got = 0;
    esp_err_t err = i2s_channel_read(rx_chan, (uint8_t*)pcm + offset, toRead, &got, portMAX_DELAY);
    if (err != ESP_OK || got == 0) return false;
    offset += got;
  }
  return true;
}

static String openaiTranscribeWav(const int16_t* pcm, size_t pcmBytes) {
  WiFiClientSecure client;
  client.setInsecure();

  if (!client.connect(OPENAI_HOST, OPENAI_PORT)) {
    Serial.println("Failed to connect to OpenAI (transcribe).");
    return "";
  }

  const char* model = STT_MODEL;
  const char* boundary = "----ESP32AudioBoundary7MA4YWxkTrZu0gW";

  String part1 =
    String("--") + boundary + "\r\n" +
    "Content-Disposition: form-data; name=\"model\"\r\n\r\n" +
    model + String("\r\n");

  String part2 =
    String("--") + boundary + "\r\n" +
    "Content-Disposition: form-data; name=\"file\"; filename=\"audio.wav\"\r\n" +
    "Content-Type: audio/wav\r\n\r\n";

  String part3 = String("\r\n--") + boundary + "--\r\n";

  uint8_t wavHeader[44];
  writeWavHeader(wavHeader, pcmBytes, MIC_SAMPLE_RATE);

  uint32_t contentLength = part1.length() + part2.length() + sizeof(wavHeader) + pcmBytes + part3.length();

  String req =
    String("POST /v1/audio/transcriptions HTTP/1.1\r\n") +
    "Host: " + OPENAI_HOST + "\r\n" +
    "Authorization: Bearer " + OPENAI_API_KEY + "\r\n" +
    "Content-Type: multipart/form-data; boundary=" + boundary + "\r\n" +
    "Connection: close\r\n" +
    "Content-Length: " + String(contentLength) + "\r\n\r\n";

  client.print(req);
  client.print(part1);
  client.print(part2);
  client.write(wavHeader, sizeof(wavHeader));
  client.write((const uint8_t*)pcm, pcmBytes);
  client.print(part3);

  int statusCode;
  bool chunked;
  int contentLengthResp;
  if (!readHttpHeaders(client, statusCode, chunked, contentLengthResp) || statusCode != 200) {
    Serial.printf("Transcribe HTTP status: %d\n", statusCode);
    String errBody = client.readString();
    Serial.println(errBody);
    return "";
  }

  String resp = client.readString();
  client.stop();

  return jsonExtractStringField(resp, "text");
}

static bool openaiSpeakPCM(const String& textToSpeak) {
  WiFiClientSecure client;
  client.setInsecure();

  if (!client.connect(OPENAI_HOST, OPENAI_PORT)) {
    Serial.println("Failed to connect to OpenAI (tts).");
    return false;
  }

  // TTS: response_format supports pcm  [oai_citation:9‡OpenAI Platform](https://platform.openai.com/docs/api-reference/audio)
  String inputEsc = jsonEscape(textToSpeak);
  String body =
    String("{\"model\":\"") + TTS_MODEL + "\","
    "\"voice\":\"" + TTS_VOICE + "\","
    "\"input\":\"" + inputEsc + "\","
    "\"response_format\":\"pcm\""
    "}";

  String req =
    String("POST /v1/audio/speech HTTP/1.1\r\n") +
    "Host: " + OPENAI_HOST + "\r\n" +
    "Authorization: Bearer " + OPENAI_API_KEY + "\r\n" +
    "Content-Type: application/json\r\n" +
    "Accept-Encoding: identity\r\n" +
    "Connection: close\r\n" +
    "Content-Length: " + String(body.length()) + "\r\n\r\n" +
    body;

  client.print(req);

  int statusCode;
  bool chunked;
  int contentLength;
  if (!readHttpHeaders(client, statusCode, chunked, contentLength) || statusCode != 200) {
    Serial.printf("TTS HTTP status: %d\n", statusCode);
    String errBody = client.readString();
    Serial.println(errBody);
    return false;
  }

  Serial.println("Playing PCM...");
  bool ok = false;
  if (chunked) ok = readChunkedBodyToI2S(client);
  else if (contentLength >= 0) ok = readFixedBodyToI2S(client, contentLength);
  else {
    // Fallback: stream until close (may fail if chunked wasn’t detected)
    uint8_t buf[1024];
    while (client.connected()) {
      int got = client.read(buf, sizeof(buf));
      if (got > 0) {
        size_t written = 0;
        i2s_channel_write(tx_chan, buf, got, &written, portMAX_DELAY);
      } else {
        delay(1);
      }
    }
    ok = true;
  }

  client.stop();
  return ok;
}

// -------------------- Setup I2S --------------------
static void setupI2SPlayback() {
  i2s_chan_config_t chan_cfg = I2S_CHANNEL_DEFAULT_CONFIG(I2S_NUM_0, I2S_ROLE_MASTER);
  esp_err_t err = i2s_new_channel(&chan_cfg, &tx_chan, NULL);
  if (err != ESP_OK || tx_chan == NULL) die("Failed to create I2S channel");

  i2s_std_config_t std_cfg = {
    .clk_cfg  = I2S_STD_CLK_DEFAULT_CONFIG(PCM_SAMPLE_RATE),
    .slot_cfg = I2S_STD_PHILIPS_SLOT_DEFAULT_CONFIG(I2S_DATA_BIT_WIDTH_16BIT, I2S_SLOT_MODE_MONO),
    .gpio_cfg = {
      .mclk = I2S_GPIO_UNUSED,
      .bclk = (gpio_num_t)I2S_BCLK,
      .ws   = (gpio_num_t)I2S_WS,
      .dout = (gpio_num_t)I2S_DOUT,
      .din  = I2S_GPIO_UNUSED,
      .invert_flags = {
        .mclk_inv = false,
        .bclk_inv = false,
        .ws_inv   = false,
      },
    },
  };

  // MAX98357A mono: use LEFT slot
  std_cfg.slot_cfg.slot_mask = I2S_STD_SLOT_LEFT;

  err = i2s_channel_init_std_mode(tx_chan, &std_cfg);
  if (err != ESP_OK) die("Failed to init I2S std mode");

  err = i2s_channel_enable(tx_chan);
  if (err != ESP_OK) die("Failed to enable I2S channel");

  Serial.println("I2S ready (24kHz, 16-bit, mono).");
}

static void setupI2SMic() {
  i2s_chan_config_t chan_cfg = I2S_CHANNEL_DEFAULT_CONFIG(I2S_NUM_1, I2S_ROLE_MASTER);
  esp_err_t err = i2s_new_channel(&chan_cfg, NULL, &rx_chan);
  if (err != ESP_OK || rx_chan == NULL) die("Failed to create I2S mic channel");

  i2s_std_config_t std_cfg = {
    .clk_cfg  = I2S_STD_CLK_DEFAULT_CONFIG(MIC_SAMPLE_RATE),
    .slot_cfg = I2S_STD_PHILIPS_SLOT_DEFAULT_CONFIG(I2S_DATA_BIT_WIDTH_16BIT, I2S_SLOT_MODE_MONO),
    .gpio_cfg = {
      .mclk = I2S_GPIO_UNUSED,
      .bclk = (gpio_num_t)I2S_MIC_BCLK,
      .ws   = (gpio_num_t)I2S_MIC_WS,
      .dout = I2S_GPIO_UNUSED,
      .din  = (gpio_num_t)I2S_MIC_DIN,
      .invert_flags = {
        .mclk_inv = false,
        .bclk_inv = false,
        .ws_inv   = false,
      },
    },
  };

  // Many I2S mics output on LEFT channel
  std_cfg.slot_cfg.slot_mask = I2S_STD_SLOT_LEFT;

  err = i2s_channel_init_std_mode(rx_chan, &std_cfg);
  if (err != ESP_OK) die("Failed to init I2S mic mode");

  err = i2s_channel_enable(rx_chan);
  if (err != ESP_OK) die("Failed to enable I2S mic channel");

  Serial.println("I2S mic ready (16kHz, 16-bit, mono).");
}

// -------------------- Setup / Loop --------------------
static bool ensureWiFiConnected() {
  if (WiFi.status() == WL_CONNECTED) return true;

  Serial.println("WiFi disconnected. Reconnecting...");
  WiFi.disconnect();
  delay(100);
  WiFi.begin(WIFI_SSID, WIFI_PASSWORD);

  int attempts = 0;
  while (WiFi.status() != WL_CONNECTED && attempts < 30) {
    delay(500);
    Serial.print(".");
    attempts++;
  }

  if (WiFi.status() == WL_CONNECTED) {
    Serial.println("\nWiFi reconnected!");
    Serial.print("IP: ");
    Serial.println(WiFi.localIP());
    return true;
  }

  Serial.println("\nWiFi reconnection failed.");
  return false;
}

static void connectWiFi() {
  WiFi.mode(WIFI_STA);
  WiFi.begin(WIFI_SSID, WIFI_PASSWORD);

  Serial.print("Connecting WiFi");
  while (WiFi.status() != WL_CONNECTED) {
    delay(400);
    Serial.print(".");
  }
  Serial.println("\nWiFi connected!");
  Serial.print("IP: ");
  Serial.println(WiFi.localIP());
}

void setup() {
  Serial.begin(115200);
  while (!Serial) delay(10);

  Serial.println("\n--- XIAO ESP32-C6 OpenAI Voice Assistant ---");
  Serial.println("Hardware: INMP441 Mic + MAX98357A Speaker");
  Serial.println("API: OpenAI Whisper (STT) + GPT-4o-mini (Chat) + TTS-1");

  // Initialize button/LED if configured
  if (BUTTON_PIN >= 0) {
    pinMode(BUTTON_PIN, INPUT_PULLUP);
    Serial.printf("Button on GPIO %d\n", BUTTON_PIN);
  }
  if (LED_PIN >= 0) {
    pinMode(LED_PIN, OUTPUT);
    digitalWrite(LED_PIN, LOW);
    Serial.printf("LED on GPIO %d\n", LED_PIN);
  }

  connectWiFi();
  setupI2SPlayback();
  setupI2SMic();

  setState(STATE_IDLE);

  // Startup audio test
  Serial.println("Testing audio output...");
  setState(STATE_SPEAKING);
  openaiSpeakPCM("Hello! Voice assistant ready.");
  setState(STATE_IDLE);
}

void loop() {
  setState(STATE_IDLE);

  // Check WiFi connection
  if (!ensureWiFiConnected()) {
    Serial.println("Cannot proceed without WiFi. Retrying in 5 seconds...");
    delay(5000);
    return;
  }

  // Wait for user to trigger recording
  waitForTrigger();

  // Allocate recording buffer
  int16_t* pcm = (int16_t*)ps_malloc(MIC_BYTES);
  if (!pcm) pcm = (int16_t*)malloc(MIC_BYTES);
  if (!pcm) {
    Serial.println("ERROR: Failed to allocate mic buffer.");
    delay(1000);
    return;
  }

  // Record audio
  setState(STATE_RECORDING);
  Serial.printf("Recording %d seconds of audio...\n", MIC_RECORD_SECONDS);
  if (!recordMicPcm(pcm, MIC_SAMPLES)) {
    Serial.println("ERROR: Microphone recording failed.");
    free(pcm);
    setState(STATE_IDLE);
    return;
  }
  Serial.println("Recording complete.");

  // Transcribe speech to text
  setState(STATE_TRANSCRIBING);
  String transcript = openaiTranscribeWav(pcm, MIC_BYTES);
  free(pcm);  // Free buffer as soon as possible

  if (transcript.length() == 0) {
    Serial.println("No speech detected or transcription failed.");
    setState(STATE_IDLE);
    return;
  }

  Serial.print("You said: \"");
  Serial.print(transcript);
  Serial.println("\"");

  // Get AI response
  setState(STATE_THINKING);
  String response = openaiChat(transcript);
  if (response.length() == 0) {
    Serial.println("ERROR: Failed to get AI response.");
    setState(STATE_IDLE);
    return;
  }

  Serial.print("Assistant: \"");
  Serial.print(response);
  Serial.println("\"");

  // Speak the response
  setState(STATE_SPEAKING);
  if (!openaiSpeakPCM(response)) {
    Serial.println("WARNING: Audio playback may have failed.");
  }

  // Small delay before next interaction
  delay(500);
}
