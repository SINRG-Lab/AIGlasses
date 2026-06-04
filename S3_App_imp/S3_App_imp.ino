#include <NimBLEDevice.h>
#include <driver/i2s_std.h>
#include <driver/i2s_pdm.h>
#include <driver/uart.h>
#include "esp_camera.h"
// ════════════════════════════════════════════════════════════════
//  BLE GATT Service UUIDs (must match Android BleVoiceService)
// ════════════════════════════════════════════════════════════════
#define SERVICE_UUID        "0000aa00-1234-5678-abcd-0e5032c6b1e0"
#define CHAR_AUDIO_TX_UUID  "0000aa01-1234-5678-abcd-0e5032c6b1e0"  // ESP32->Android (NOTIFY)
#define CHAR_AUDIO_RX_UUID  "0000aa02-1234-5678-abcd-0e5032c6b1e0"  // Android->ESP32 (WRITE)
#define CHAR_CONTROL_UUID   "0000aa03-1234-5678-abcd-0e5032c6b1e0"  // Bidirectional (WRITE+NOTIFY)
#define CHAR_IMAGE_TX_UUID  "0000aa04-1234-5678-abcd-0e5032c6b1e0"  // ESP32->Android: camera JPEG (NOTIFY)

// ════════════════════════════════════════════════════════════════
//  BLE packet config
// ════════════════════════════════════════════════════════════════
#define BLE_MTU             512
#define BLE_HEADER_SIZE     2       // TAG(1) + SEQ(1)
// Max audio payload per BLE notification = MTU - 3 (ATT overhead) - header
#define BLE_MAX_PAYLOAD     (BLE_MTU - 3 - BLE_HEADER_SIZE)

// ════════════════════════════════════════════════════════════════
//  I2S Pins — XIAO ESP32-S3 Sense
//    Speaker (MAX98357A x2, hardware-panned L/R via SD_MODE):
//      BCLK = GPIO9 (D10), LRC = GPIO4 (D3), DIN = GPIO8 (D9)
//    Microphone: built-in PDM mic (MSM261D3526H1CPM)
//      PDM_CLK  = GPIO42, PDM_DATA = GPIO41
//    Left side is For PCB/C/breadboard
// ════════════════════════════════════════════════════════════════
#define I2S_BCLK    9   // Speaker BCLK 9/18/6
#define I2S_WS      5   // Speaker LRC / WS 5/22/7
#define AMP_DIN     8   // Speaker DIN (data out) 8/20/8

// Built-in PDM mic pins (XIAO ESP32-S3 Sense)
#define PDM_CLK     42
#define PDM_DATA    41

// ════════════════════════════════════════════════════════════════
//  XIAO ESP32S3 Sense — Camera Pin Definitions (OV3660)
// ════════════════════════════════════════════════════════════════
#define CAM_XCLK    10
#define CAM_SIOD    40
#define CAM_SIOC    39
#define CAM_Y9      48
#define CAM_Y8      11
#define CAM_Y7      12
#define CAM_Y6      14
#define CAM_Y5      16
#define CAM_Y4      18
#define CAM_Y3      17
#define CAM_Y2      15
#define CAM_VSYNC   38
#define CAM_HREF    47
#define CAM_PCLK    13

// ════════════════════════════════════════════════════════════════
//  Push-to-talk button: GPIO6 → HIGH when pressed (active HIGH)
// ════════════════════════════════════════════════════════════════
#define PTT_PIN        6 // 6/23/5

// ════════════════════════════════════════════════════════════════
//  Audio settings
// ════════════════════════════════════════════════════════════════
static const int MIC_SR = 16000;
static const int SPK_SR = 24000;   // Matches OpenAI TTS native PCM rate — no resampling needed
static const int SAMPLES_PER_CHUNK = 512;
// 16-bit output buffer sent over BLE (PDM mic outputs 16-bit directly)
static int16_t micBuf[SAMPLES_PER_CHUNK];

// Speaker volume attenuation (right-shift). MAX98357A has fixed 9dB gain;
// full-scale digital audio drives it into clipping → buzzy/distorted output.
// 0 = full, 1 = -6dB (half), 2 = -12dB (quarter), 3 = -18dB (1/8), 4 = -24dB.
// Tune down if still loud/buzzy, up if too quiet.
#define SPK_VOL_SHIFT  0

// ════════════════════════════════════════════════════════════════
//  Ring Buffer for streaming playback (PSRAM-backed)
//  BLE callback (NimBLE task) writes → main loop reads → I2S
//  Single producer, single consumer = lock-free with volatile indices
// ════════════════════════════════════════════════════════════════
#define RING_SIZE (160 * 1024)   // 160KB ring buffer
static uint8_t* ringBuffer = nullptr;
static volatile size_t ringHead = 0;   // Write position (BLE task)
static volatile size_t ringTail = 0;   // Read position  (main loop)

static inline size_t ringAvailable() {
  size_t h = ringHead, t = ringTail;
  return (h >= t) ? (h - t) : (RING_SIZE - t + h);
}
static inline size_t ringFree() {
  return RING_SIZE - 1 - ringAvailable();
}
static size_t ringWrite(const uint8_t* data, size_t len) {
  size_t free = ringFree();
  if (len > free) len = free;
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
static void ringReset() { ringHead = 0; ringTail = 0; }

// ════════════════════════════════════════════════════════════════
//  Streaming playback state machine
// ════════════════════════════════════════════════════════════════
enum StreamState {
  STREAM_IDLE,
  STREAM_BUFFERING,
  STREAM_PLAYING,
  STREAM_DRAINING
};
static volatile StreamState streamState = STREAM_IDLE;
static volatile bool endMarkerReceived = false;
// Pre-buffer ~1s at 24000 Hz mono 16-bit before starting playback.
// Larger = more latency but more tolerance to BLE jitter/bursts (fewer cut-outs).
#define STREAM_START_THRESHOLD  48000
// If the ring drops below this while playing, insert silence rather than
// feeding starved data — prevents audible glitches on brief underruns.
#define STREAM_LOW_WATER        4096

// ════════════════════════════════════════════════════════════════
//  I2S mode tracking (shared bus — must switch between mic & speaker)
// ════════════════════════════════════════════════════════════════
enum I2SMode { MODE_NONE, MODE_MIC, MODE_SPEAKER };
static I2SMode currentI2SMode = MODE_NONE;

// New-API channel handles
// Mic PDM RX on I2S_NUM_1, Speaker STD TX on I2S_NUM_0 (independent ports)
static i2s_chan_handle_t pdmRxHandle = NULL;
static i2s_chan_handle_t stdTxHandle = NULL;

// Stats for debugging
static unsigned int rxChunkCount = 0;
static unsigned int rxTotalBytes = 0;
static unsigned int rxDroppedBytes = 0;
static unsigned int rxLostPackets = 0;
unsigned int _streamUnderruns = 0;  // file-scope so finish: handler can read & reset

// ════════════════════════════════════════════════════════════════
//  Performance measurement state  (grep: [PERF-Mxx])
//  M1  = mic audio BLE TX (ESP32→Android): bytes + throughput
//  M4  = TTS audio BLE RX (Android→ESP32): throughput
//  M5  = ring buffer fill % at playback start
//  M6  = 'S' marker → first I2S write latency
//  M7  = button press → first I2S write (end-to-end round-trip)
//  M8  = speaker I2S init duration
//  M10 = BLE RX throughput (bytes/sec)
//  M11 = utterance size + duration
//  M13 = heap / PSRAM at key init stages
//  M19 = streaming underruns
//  M21 = camera capture: attempts, duration, size
//  M23 = image BLE TX: bytes + throughput
//  M25 = mic amplitude summary (for STT quality diagnosis)
// ════════════════════════════════════════════════════════════════
static unsigned long perfButtonPressMs    = 0;  // M7: set on button press
static unsigned long perfAudioTxStartMs   = 0;  // M1: recording start time
static unsigned long perfAudioTxBytes     = 0;  // M1: bytes sent to Android
static unsigned long perfAudioTxChunks    = 0;  // M1: BLE chunk count
static unsigned long perfRxStartMs        = 0;  // M4/M6: time of 'S' marker
static int16_t       perfMicMinAmp        = 32767;  // M25: min sample this recording
static int16_t       perfMicMaxAmp        = -32768; // M25: max sample this recording
static long          perfMicSumAmp        = 0;      // M25: sum |sample|
static unsigned long perfMicSampleCount   = 0;      // M25: total samples counted

// ════════════════════════════════════════════════════════════════
//  BLE state
// ════════════════════════════════════════════════════════════════
static NimBLEServer* pServer = nullptr;
static NimBLECharacteristic* pAudioTxChar = nullptr;
static NimBLECharacteristic* pAudioRxChar = nullptr;
static NimBLECharacteristic* pControlChar = nullptr;
static NimBLECharacteristic* pImageTxChar = nullptr;
static volatile bool deviceConnected = false;
static uint8_t txSeqNum = 0;

// ════════════════════════════════════════════════════════════════
//  Multi-tap detection
//    1 tap  + hold → voice only
//    2 taps + hold → vision (photo + voice)
//    3 taps + hold → video recording
// ════════════════════════════════════════════════════════════════
#define QUICK_TAP_MAX_MS  350   // press shorter than this counts as a "quick tap"
#define TAP_WINDOW_MS     600   // inter-tap window for multi-tap accumulation

static unsigned long lastQuickTapTime = 0;
static int tapCount = 0;
static unsigned long pressStartTime = 0;
static bool visionMode = false;
static volatile bool sendImageFirst = false;

// ════════════════════════════════════════════════════════════════
//  Video recording state
// ════════════════════════════════════════════════════════════════
static bool videoMode = false;
static int videoFrameCount = 0;

// ════════════════════════════════════════════════════════════════
//  Camera frame buffer (stored in PSRAM until sent)
// ════════════════════════════════════════════════════════════════
static uint8_t* capturedJpeg = nullptr;
static size_t capturedJpegLen = 0;

// ════════════════════════════════════════════════════════════════
//  I2S init: Mic (RX) on I2S0 — shared bus, must teardown speaker first
// ════════════════════════════════════════════════════════════════
void i2sMicInit() {
  // Use NEW i2s driver on I2S_NUM_1 — runs independently from speaker on I2S_NUM_0
  if (pdmRxHandle != NULL) {
    currentI2SMode = MODE_MIC;
    return;  // already initialized; nothing to do
  }

  i2s_chan_config_t chanCfg = I2S_CHANNEL_DEFAULT_CONFIG(I2S_NUM_0, I2S_ROLE_MASTER);
  esp_err_t e = i2s_new_channel(&chanCfg, NULL, &pdmRxHandle);
  if (e != ESP_OK) { Serial.printf("[I2S] new_channel failed: %d\n", (int)e); return; }

  i2s_pdm_rx_config_t pdmCfg = {
    .clk_cfg  = I2S_PDM_RX_CLK_DEFAULT_CONFIG(MIC_SR),
    .slot_cfg = I2S_PDM_RX_SLOT_DEFAULT_CONFIG(I2S_DATA_BIT_WIDTH_16BIT, I2S_SLOT_MODE_MONO),
    .gpio_cfg = {
      .clk = (gpio_num_t)PDM_CLK,
      .din = (gpio_num_t)PDM_DATA,
      .invert_flags = { .clk_inv = false },
    },
  };

  Serial.println("[I2S] Installing PDM MIC driver (new API, I2S_NUM_0)...");
  e = i2s_channel_init_pdm_rx_mode(pdmRxHandle, &pdmCfg);
  if (e != ESP_OK) Serial.printf("[I2S] init_pdm_rx failed: %d\n", (int)e);

  e = i2s_channel_enable(pdmRxHandle);
  if (e != ESP_OK) Serial.printf("[I2S] channel_enable failed: %d\n", (int)e);
  else Serial.println("[I2S] PDM MIC enabled OK");

  // Flush a few DMA buffers — PDM mic outputs garbage on clock startup
  {
    size_t dummy = 0;
    int16_t flushBuf[256];
    for (int i = 0; i < 5; i++) {
      i2s_channel_read(pdmRxHandle, flushBuf, sizeof(flushBuf), &dummy, 20 / portTICK_PERIOD_MS);
    }
  }

  currentI2SMode = MODE_MIC;
}

// ════════════════════════════════════════════════════════════════
//  I2S init: Speaker (TX) on I2S0 — shared bus, must teardown mic first
// ════════════════════════════════════════════════════════════════
void i2sSpkInit() {
  unsigned long _spkInitStart = millis();  // M8
  // Tear down previous TX channel if any
  if (stdTxHandle != NULL) {
    i2s_channel_disable(stdTxHandle);
    i2s_del_channel(stdTxHandle);
    stdTxHandle = NULL;
    delay(20);
  }

  // Pause the PDM mic so GPIO42 (PDM_CLK) stops toggling — eliminates
  // crosstalk between the always-on mic clock and the speaker BCLK line.
  if (pdmRxHandle != NULL) {
    i2s_channel_disable(pdmRxHandle);
  }

  i2s_chan_config_t chanCfg = I2S_CHANNEL_DEFAULT_CONFIG(I2S_NUM_1, I2S_ROLE_MASTER);
  chanCfg.dma_desc_num  = 8;
  chanCfg.dma_frame_num = 512;
  esp_err_t e = i2s_new_channel(&chanCfg, &stdTxHandle, NULL);
  if (e != ESP_OK) {
    Serial.printf("[I2S] SPK new_channel FAILED: %d\n", (int)e);
    stdTxHandle = NULL;
    return;
  }

  i2s_std_config_t stdCfg = {
    .clk_cfg  = I2S_STD_CLK_DEFAULT_CONFIG(SPK_SR),
    .slot_cfg = I2S_STD_PHILIPS_SLOT_DEFAULT_CONFIG(I2S_DATA_BIT_WIDTH_16BIT, I2S_SLOT_MODE_STEREO),
    .gpio_cfg = {
      .mclk = I2S_GPIO_UNUSED,
      .bclk = (gpio_num_t)I2S_BCLK,
      .ws   = (gpio_num_t)I2S_WS,
      .dout = (gpio_num_t)AMP_DIN,
      .din  = I2S_GPIO_UNUSED,
      .invert_flags = { .mclk_inv = false, .bclk_inv = false, .ws_inv = false },
    },
  };

  Serial.println("[I2S] Installing SPEAKER driver (new API, I2S_NUM_1)...");
  e = i2s_channel_init_std_mode(stdTxHandle, &stdCfg);
  if (e != ESP_OK) {
    Serial.printf("[I2S] SPK init_std FAILED: %d\n", (int)e);
    i2s_del_channel(stdTxHandle);
    stdTxHandle = NULL;
    return;
  }

  e = i2s_channel_enable(stdTxHandle);
  if (e != ESP_OK) {
    Serial.printf("[I2S] SPK enable FAILED: %d\n", (int)e);
    i2s_del_channel(stdTxHandle);
    stdTxHandle = NULL;
    return;
  }
  Serial.println("[I2S] SPEAKER enabled OK");
  Serial.printf("[PERF-M8] Speaker I2S init time: %lu ms\n", millis() - _spkInitStart);

  // Preload DMA with silence so the first frames out are clean,
  // not whatever uninitialized memory the driver allocated.
  int16_t silence[256] = {0};
  size_t wrote = 0;
  for (int i = 0; i < 8; i++) {
    i2s_channel_write(stdTxHandle, silence, sizeof(silence), &wrote, 100 / portTICK_PERIOD_MS);
  }

  currentI2SMode = MODE_SPEAKER;
}

// ════════════════════════════════════════════════════════════════
//  Amplifier control (no SD_MODE pin — amp always on)
// ════════════════════════════════════════════════════════════════
void enableAmp() { Serial.println("[AMP] Ready"); }
void disableAmp() { /* No SD_MODE pin wired */ }

// ════════════════════════════════════════════════════════════════
//  Stereo interleave buffer
// ════════════════════════════════════════════════════════════════
#define STEREO_CHUNK_MONO 512
static int16_t stereoChunk[STEREO_CHUNK_MONO * 2];
static uint8_t monoReadBuf[STEREO_CHUNK_MONO * 2];

// ════════════════════════════════════════════════════════════════
//  Initialize OV3660 Camera
// ════════════════════════════════════════════════════════════════
bool initCamera() {
  camera_config_t config = {};
  config.ledc_channel = LEDC_CHANNEL_0;
  config.ledc_timer   = LEDC_TIMER_0;
  config.pin_d0       = CAM_Y2;
  config.pin_d1       = CAM_Y3;
  config.pin_d2       = CAM_Y4;
  config.pin_d3       = CAM_Y5;
  config.pin_d4       = CAM_Y6;
  config.pin_d5       = CAM_Y7;
  config.pin_d6       = CAM_Y8;
  config.pin_d7       = CAM_Y9;
  config.pin_xclk     = CAM_XCLK;
  config.pin_pclk     = CAM_PCLK;
  config.pin_vsync    = CAM_VSYNC;
  config.pin_href     = CAM_HREF;
  config.pin_sscb_sda = CAM_SIOD;
  config.pin_sscb_scl = CAM_SIOC;
  config.pin_pwdn     = -1;
  config.pin_reset    = -1;
  config.xclk_freq_hz = 20000000;
  config.pixel_format = PIXFORMAT_JPEG;
  config.grab_mode    = CAMERA_GRAB_LATEST;
  config.frame_size   = FRAMESIZE_QVGA;   // 320x240 — fewer BLE fragments, less packet loss
  config.jpeg_quality = 12;
  config.fb_count     = 2;
  config.fb_location  = CAMERA_FB_IN_PSRAM;

  esp_err_t err = esp_camera_init(&config);
  if (err != ESP_OK) {
    Serial.printf("[CAM] Init FAILED: 0x%x\n", err);
    return false;
  }

  sensor_t *s = esp_camera_sensor_get();
  if (s) {
    Serial.printf("[CAM] Sensor PID: 0x%04X\n", s->id.PID);
    s->set_brightness(s, 0);
    s->set_contrast(s, 0);
    s->set_saturation(s, 0);
    s->set_whitebal(s, 1);
    s->set_awb_gain(s, 1);
    s->set_exposure_ctrl(s, 1);
    s->set_gain_ctrl(s, 1);
  }

  // Warm up the sensor — discard the first several frames.
  // The OV2640/OV3660 outputs garbage or returns NULL on esp_camera_fb_get()
  // until the AEC/AWB loops have converged (~300–500 ms after clock start).
  Serial.println("[CAM] Warming up sensor (discarding first frames)...");
  for (int i = 0; i < 5; i++) {
    camera_fb_t *warmup = esp_camera_fb_get();
    if (warmup) {
      esp_camera_fb_return(warmup);
    }
    delay(100);
  }
  Serial.println("[CAM] Sensor warm-up complete");

  return true;
}

// ════════════════════════════════════════════════════════════════
//  Capture camera snapshot into PSRAM buffer
// ════════════════════════════════════════════════════════════════
bool captureSnapshot() {
  unsigned long _capStart = millis();  // M21
  int _attempts = 0;

  // Warm up the sensor. After the camera sits idle the auto-exposure/gain (AEC/AGC)
  // hasn't converged, so the first frames come back blank/dark. Discard a few
  // frames (grab+return) so exposure settles before the real capture. This is the
  // fix for "image comes out blank ~half the time".
  const int WARMUP_FRAMES = 4;
  for (int i = 0; i < WARMUP_FRAMES; i++) {
    camera_fb_t *warm = esp_camera_fb_get();
    if (!warm) break;          // sensor not producing — let the retry loop handle it
    esp_camera_fb_return(warm);
    delay(20);                 // ~one frame interval for AEC/AGC to step
  }

  // Retry up to 5 times — sensor occasionally needs a few attempts
  // if it was idle or the DMA pipeline stalled.
  camera_fb_t *fb = nullptr;
  for (int attempt = 1; attempt <= 5; attempt++) {
    _attempts = attempt;
    fb = esp_camera_fb_get();
    if (fb) break;
    Serial.printf("[CAM] Capture attempt %d failed, retrying...\n", attempt);
    delay(150);
  }
  if (!fb) {
    Serial.printf("[PERF-M21] Camera capture FAILED after %d attempts (%lu ms)\n",
                  _attempts, millis() - _capStart);
    return false;
  }

  // Copy into our own PSRAM buffer so we can release the frame buffer
  if (capturedJpeg) free(capturedJpeg);
  capturedJpeg = (uint8_t*)ps_malloc(fb->len);
  if (!capturedJpeg) {
    Serial.println("[CAM] PSRAM alloc failed for JPEG!");
    esp_camera_fb_return(fb);
    return false;
  }
  memcpy(capturedJpeg, fb->buf, fb->len);
  capturedJpegLen = fb->len;
  esp_camera_fb_return(fb);

  Serial.printf("[PERF-M21] Camera capture: %d attempt(s), %lu ms, %u bytes JPEG\n",
                _attempts, millis() - _capStart, (unsigned int)capturedJpegLen);
  return true;
}

// ════════════════════════════════════════════════════════════════
//  notify() with flow control.
//  NimBLE's notify() returns false when the host TX buffer is full
//  (BLE_HS_ENOMEM during a burst). The original code ignored this, so
//  fragments were SILENTLY DROPPED → missing bytes → JPEG that won't
//  reassemble/decode. Retry with backoff until the stack drains.
//  Returns the number of retries used (0 = first try succeeded), or -1
//  if it gave up / disconnected.
// ════════════════════════════════════════════════════════════════
static int notifyWithRetry(NimBLECharacteristic* pChar, const uint8_t* data, size_t len) {
  pChar->setValue(data, len);
  if (pChar->notify()) return 0;
  int tries = 0;
  const int MAX_TRIES = 50;     // ~250 ms worst case
  while (tries < MAX_TRIES && deviceConnected) {
    delay(5);                   // let the BLE stack drain its TX buffers
    tries++;
    if (pChar->notify()) return tries;
  }
  return -1;  // gave up
}

// ════════════════════════════════════════════════════════════════
//  Send 'S' (recording start) marker → tells Android to flush any stale
//  audio chunks left over from quick taps before this utterance begins.
//  The small delay lets the marker (CONTROL char) land before the first
//  mic chunk (AUDIO_TX char) — the two are separate notification streams.
// ════════════════════════════════════════════════════════════════
void sendAudioStartMarker() {
  if (!deviceConnected || pControlChar == nullptr) return;
  uint8_t startPkt[BLE_HEADER_SIZE] = {'S', 0};
  notifyWithRetry(pControlChar, startPkt, BLE_HEADER_SIZE);
  delay(10);
}

// ════════════════════════════════════════════════════════════════
//  Send stored JPEG over BLE via IMAGE_TX characteristic
// ════════════════════════════════════════════════════════════════
void sendCapturedImage() {
  if (!capturedJpeg || capturedJpegLen == 0) return;
  if (!deviceConnected || pImageTxChar == nullptr) return;

  unsigned long _imgTxStart = millis();  // M23
  unsigned int _imgPackets = 0;
  unsigned int _imgRetries = 0;   // M23: total notify retries (flow-control pressure)
  unsigned int _imgDropped = 0;   // M23: fragments that never sent (gave up)

  // Send start marker with total size on control channel
  {
    uint8_t startPkt[6];
    startPkt[0] = 'I';
    startPkt[1] = 0;
    startPkt[2] = (capturedJpegLen >>  0) & 0xFF;
    startPkt[3] = (capturedJpegLen >>  8) & 0xFF;
    startPkt[4] = (capturedJpegLen >> 16) & 0xFF;
    startPkt[5] = (capturedJpegLen >> 24) & 0xFF;
    notifyWithRetry(pControlChar, startPkt, 6);
    // Let the start marker (CONTROL char) land before the first fragment
    // (IMAGE_TX char) — they're separate notification streams and can reorder.
    // Too short a gap = Android resets its buffer after fragments already
    // arrived → corrupt reassembly.
    delay(40);
  }

  // Fragment JPEG and send
  uint8_t imgSeq = 0;
  size_t sent = 0;
  while (sent < capturedJpegLen) {
    size_t fragSize = min((size_t)BLE_MAX_PAYLOAD, capturedJpegLen - sent);

    uint8_t pkt[BLE_HEADER_SIZE + fragSize];
    pkt[0] = 'I';
    pkt[1] = imgSeq++;
    memcpy(pkt + BLE_HEADER_SIZE, capturedJpeg + sent, fragSize);

    int r = notifyWithRetry(pImageTxChar, pkt, BLE_HEADER_SIZE + fragSize);
    if (r < 0) { _imgDropped++; }
    else       { _imgRetries += r; }

    sent += fragSize;
    _imgPackets++;
    delay(15);  // one fragment per connection event — prevents notification drops
  }

  // Guard delay so the last IMAGE_TX fragments drain before the 'J' end marker
  // (sent on a different characteristic) — prevents the end marker racing ahead
  // and closing Android's reassembly buffer early.
  delay(30);

  // Send image end marker
  {
    uint8_t endPkt[2] = {'J', 0};
    notifyWithRetry(pControlChar, endPkt, 2);
  }

  unsigned long _imgTxMs = millis() - _imgTxStart;
  float _imgThroughput = (_imgTxMs > 0) ? (capturedJpegLen * 1000.0f / _imgTxMs) : 0;
  Serial.printf("[PERF-M23] Image TX: %u bytes, %u packets, %lu ms (%.0f B/s = %.1f KB/s) | retries=%u dropped=%u\n",
                (unsigned int)capturedJpegLen, _imgPackets, _imgTxMs,
                _imgThroughput, _imgThroughput / 1024.0f, _imgRetries, _imgDropped);

  // Free the buffer
  free(capturedJpeg);
  capturedJpeg = nullptr;
  capturedJpegLen = 0;
}

// ════════════════════════════════════════════════════════════════
//  Video recording helpers
// ════════════════════════════════════════════════════════════════
void sendVideoStartMarker() {
  if (!deviceConnected || pControlChar == nullptr) return;
  uint8_t pkt[2] = {'V', 0};
  pControlChar->setValue(pkt, 2);
  pControlChar->notify();
  delay(10);
}

// Capture one JPEG frame and stream it immediately over BLE.
// The reserved byte in the control 'I' packet is 0x01 (video frame flag).
void sendVideoFrame(uint8_t frameIndex) {
  if (!deviceConnected || pImageTxChar == nullptr) return;

  camera_fb_t *fb = nullptr;
  for (int attempt = 1; attempt <= 3; attempt++) {
    fb = esp_camera_fb_get();
    if (fb) break;
    delay(50);
  }
  if (!fb) {
    Serial.println("[VID] Frame capture failed");
    return;
  }

  // Frame start: 'I' + 0x01 (video flag) + 4-byte LE size
  uint8_t startPkt[6];
  startPkt[0] = 'I';
  startPkt[1] = 0x01;
  startPkt[2] = (fb->len >>  0) & 0xFF;
  startPkt[3] = (fb->len >>  8) & 0xFF;
  startPkt[4] = (fb->len >> 16) & 0xFF;
  startPkt[5] = (fb->len >> 24) & 0xFF;
  notifyWithRetry(pControlChar, startPkt, 6);
  delay(10);

  // Fragment and send JPEG directly from camera DMA buffer
  uint8_t imgSeq = 0;
  size_t sent = 0;
  while (sent < fb->len) {
    size_t fragSize = min((size_t)BLE_MAX_PAYLOAD, fb->len - sent);
    uint8_t pkt[BLE_HEADER_SIZE + fragSize];
    pkt[0] = 'I';
    pkt[1] = imgSeq++;
    memcpy(pkt + BLE_HEADER_SIZE, fb->buf + sent, fragSize);
    notifyWithRetry(pImageTxChar, pkt, BLE_HEADER_SIZE + fragSize);
    sent += fragSize;
    delay(15);
  }

  // Guard delay so the last fragments drain before the 'J' frame-end marker
  delay(30);

  // Frame end: 'J' + frameIndex
  uint8_t endPkt[2] = {'J', frameIndex};
  notifyWithRetry(pControlChar, endPkt, 2);

  Serial.printf("[VID] Frame %u: %u bytes\n", frameIndex, (unsigned int)fb->len);
  esp_camera_fb_return(fb);
}

void sendVideoEndMarker(uint8_t totalFrames) {
  if (!deviceConnected || pControlChar == nullptr) return;
  uint8_t pkt[2] = {'W', totalFrames};
  pControlChar->setValue(pkt, 2);
  pControlChar->notify();
  delay(10);
}

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
      Serial.printf("[PERF-M5] Ring buffer at playback start: %u/%u bytes (%.1f%%)\n",
                    (unsigned)avail, RING_SIZE, 100.0f * avail / RING_SIZE);
      Serial.printf("[PERF-M6] 'S' marker → playback start: %lu ms\n",
                    millis() - perfRxStartMs);
      Serial.printf("[PERF-M7] Button press → playback start (round-trip): %lu ms\n",
                    millis() - perfButtonPressMs);
      i2sSpkInit();
      if (stdTxHandle == NULL) {
        Serial.println("[STREAM] Speaker init failed — aborting playback");
        streamState = STREAM_IDLE;
        endMarkerReceived = false;
        ringReset();
        return;
      }
      enableAmp();
      delay(30);
      streamState = STREAM_PLAYING;
    }
    return;
  }

  // ── PLAYING / DRAINING: feed ring buffer data to I2S ──
  if (streamState == STREAM_PLAYING || streamState == STREAM_DRAINING) {
    if (streamState == STREAM_PLAYING && endMarkerReceived) {
      streamState = STREAM_DRAINING;
      Serial.printf("[STREAM] End marker received — draining %u remaining bytes\n", avail);
    }

    size_t wantBytes = STEREO_CHUNK_MONO * 2;

    if (avail >= wantBytes) {
      ringRead(monoReadBuf, wantBytes);
    } else if (streamState == STREAM_DRAINING && avail > 0) {
      size_t got = avail & ~1;
      if (got == 0) goto finish;
      ringRead(monoReadBuf, got);
      memset(monoReadBuf + got, 0, wantBytes - got);
      wantBytes = got;
    } else if (streamState == STREAM_DRAINING && avail == 0) {
      goto finish;
    } else {
      // PLAYING but ring is low → wait for more data rather than inserting
      // silence. The DMA TX buffer has ~186 ms of headroom and will smoothly
      // play out while we wait, avoiding the rhythmic "bubbling" that silence
      // splicing produces on repeated brief underruns.
      unsigned long waitStart = millis();
      while (ringAvailable() < wantBytes && (millis() - waitStart) < 300) {
        delay(2);
      }
      if (ringAvailable() >= wantBytes) {
        ringRead(monoReadBuf, wantBytes);
      } else {
        if ((++_streamUnderruns % 20) == 1) {
          Serial.printf("[STREAM] Underrun #%u (ring=%u) — DMA drain\n",
                        _streamUnderruns, (unsigned)ringAvailable());
        }
        // Skip writing — let DMA continue playing its existing buffer.
        // Next tick will re-check the ring.
        return;
      }
    }

    // Interleave mono → stereo (L=R) — MAX98357A expects standard stereo I2S.
    // Mono-slot mode produces a non-standard waveform that some amps decode as clicks.
    const int16_t* monoSrc = (const int16_t*)monoReadBuf;
    size_t monoSamples = wantBytes / 2;
    for (size_t i = 0; i < monoSamples; i++) {
      int16_t s = monoSrc[i] >> SPK_VOL_SHIFT;
      stereoChunk[2 * i]     = s;
      stereoChunk[2 * i + 1] = s;
    }

    size_t bytesToWrite = monoSamples * 2 * sizeof(int16_t);
    size_t written = 0;
    i2s_channel_write(stdTxHandle, stereoChunk, bytesToWrite, &written, portMAX_DELAY);

    if (streamState == STREAM_DRAINING && ringAvailable() == 0) {
      goto finish;
    }
    return;
  }
  return;

finish:
  {
    unsigned long _rxMs = (perfRxStartMs > 0) ? (millis() - perfRxStartMs) : 1;
    float _rxThroughput = rxTotalBytes * 1000.0f / _rxMs;
    Serial.printf("[STREAM] Playback complete! (rx %u bytes, %u chunks, ringDrop %u, seqGaps %u, underruns %u)\n",
                  rxTotalBytes, rxChunkCount, rxDroppedBytes, rxLostPackets, _streamUnderruns);
    Serial.printf("[PERF-M10] BLE RX (Android→ESP32): %u bytes in %lu ms → %.0f B/s (%.1f KB/s)\n",
                  rxTotalBytes, _rxMs, _rxThroughput, _rxThroughput / 1024.0f);
    Serial.printf("[PERF-M19] Streaming underruns: %u | dropped: %u bytes | seq gaps: %u packets\n",
                  _streamUnderruns, rxDroppedBytes, rxLostPackets);
  }
  _streamUnderruns = 0;

  memset(stereoChunk, 0, sizeof(stereoChunk));
  for (int i = 0; i < 8; i++) {
    size_t written = 0;
    i2s_channel_write(stdTxHandle, stereoChunk, sizeof(stereoChunk), &written, portMAX_DELAY);
  }
  delay(200);
  if (stdTxHandle) {
    i2s_channel_disable(stdTxHandle);
    i2s_del_channel(stdTxHandle);
    stdTxHandle = NULL;
  }

  pinMode(AMP_DIN, OUTPUT);
  digitalWrite(AMP_DIN, LOW);

  // Re-enable the PDM mic now that the speaker is torn down
  if (pdmRxHandle != NULL) {
    i2s_channel_enable(pdmRxHandle);
  }
  currentI2SMode = MODE_MIC;  // mic channel is always running on I2S_NUM_1

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

    // Request fast connection parameters for audio streaming
    // Min interval=7.5ms(6), Max=15ms(12), Latency=0, Timeout=5s(500)
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
      // Sequence number tracking
      if (rxChunkCount > 0 && seq != expectedSeq) {
        uint8_t gap = (seq - expectedSeq);
        rxLostPackets += gap;
        Serial.printf("[BLE-RX] SEQ gap: expected %u got %u (lost ~%u pkts)\n",
                      expectedSeq, seq, (unsigned int)gap);
      }
      expectedSeq = seq + 1;

      size_t written = ringWrite(payload, payloadLen);
      rxChunkCount++;
      rxTotalBytes += written;

      if (written < payloadLen) {
        rxDroppedBytes += (payloadLen - written);
        if (rxDroppedBytes % 5000 < payloadLen) {
          Serial.printf("[BLE-RX] Ring buffer full — dropped %u bytes total\n", rxDroppedBytes);
        }
      }

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
      perfRxStartMs = millis();  // M4/M6: start of TTS audio incoming
      Serial.println("[BLE-CTRL] Start marker — buffering audio");
      Serial.printf("[PERF-M4] BLE RX start (TTS audio incoming, heap: %u KB free)\n",
                    ESP.getFreeHeap() / 1024);
      ringReset();
      endMarkerReceived = false;
      rxChunkCount = 0;
      rxTotalBytes = 0;
      rxDroppedBytes = 0;
      rxLostPackets = 0;
      streamState = STREAM_BUFFERING;
    } else if (tag == 'E') {
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

    // Build packet: [TAG][SEQ][audio data]
    uint8_t pkt[BLE_HEADER_SIZE + fragSize];
    pkt[0] = 'A';           // Audio tag
    pkt[1] = txSeqNum++;    // Sequence number (wraps at 255)
    memcpy(pkt + BLE_HEADER_SIZE, pcmData + offset, fragSize);

    pAudioTxChar->setValue(pkt, BLE_HEADER_SIZE + fragSize);
    pAudioTxChar->notify();

    offset += fragSize;
    perfAudioTxBytes += fragSize;   // M1: accumulate payload bytes (excludes header)
    perfAudioTxChunks++;
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
  Serial.println("  ESP32-S3 AI GLASSES (BLE + Shared I2S + Camera)");
  Serial.println("  Running on C6 PCB — shared BCLK/WS bus");
  Serial.println("============================================================");
  Serial.printf("SPK: BCLK=GPIO%d  LRC=GPIO%d  DIN=GPIO%d\n", I2S_BCLK, I2S_WS, AMP_DIN);
  Serial.printf("MIC: built-in PDM (CLK=GPIO%d, DATA=GPIO%d)\n", PDM_CLK, PDM_DATA);
  Serial.printf("CAM: OV3660 (XCLK=GPIO%d)\n", CAM_XCLK);
  Serial.printf("PTT Button=GPIO%d\n", PTT_PIN);
  Serial.println("Transport: BLE (WRITE_NR) + Streaming Ring Buffer");
  Serial.println("============================================================\n");

  Serial.printf("[SYS] PSRAM: %u KB\n", ESP.getPsramSize() / 1024);
  Serial.printf("[SYS] Free heap: %u KB\n", ESP.getFreeHeap() / 1024);
  Serial.printf("[SYS] Free PSRAM: %u KB\n", ESP.getFreePsram() / 1024);
  Serial.printf("[PERF-M13] Heap at boot: %u KB free | PSRAM: %u KB free\n",
                ESP.getFreeHeap() / 1024, ESP.getFreePsram() / 1024);

  // ── Initialize camera FIRST — before any PSRAM/DMA allocations ──
  // The camera driver claims DMA channels and PSRAM frame buffers.
  // If the mic or ring buffer are initialized first, DMA channel conflicts
  // or PSRAM fragmentation can cause esp_camera_fb_get() to return NULL.
  Serial.println("[CAM] Initializing camera...");
  if (!initCamera()) {
    Serial.println("[CAM] FAILED — voice-only mode (no camera)");
  } else {
    Serial.println("[CAM] Camera initialized OK");
  }
  Serial.printf("[SYS] Free PSRAM after camera: %u KB\n", ESP.getFreePsram() / 1024);
  Serial.printf("[PERF-M13] Heap after camera init: %u KB free | PSRAM: %u KB free\n",
                ESP.getFreeHeap() / 1024, ESP.getFreePsram() / 1024);

  // Allocate ring buffer in PSRAM
  ringBuffer = (uint8_t*)ps_malloc(RING_SIZE);
  if (!ringBuffer) {
    Serial.println("[SYS] PSRAM alloc failed for ring buffer! Using internal RAM");
    ringBuffer = (uint8_t*)malloc(RING_SIZE);
  }
  if (ringBuffer) {
    Serial.printf("[SYS] Ring buffer: %u KB allocated\n", RING_SIZE / 1024);
  } else {
    Serial.println("[SYS] FATAL: Could not allocate ring buffer!");
  }
  Serial.printf("[PERF-M13] Heap after ring buffer: %u KB free | PSRAM: %u KB free\n",
                ESP.getFreeHeap() / 1024, ESP.getFreePsram() / 1024);

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

  // Initialize mic on I2S0 (speaker will be initialized on-demand via mode switch)
  i2sMicInit();

  // Flush DMA garbage and let PDM mic stabilize (~500ms warmup)
  Serial.println("[MIC] Warming up built-in PDM mic...");
  for (int i = 0; i < 10; i++) {
    size_t dummy = 0;
    i2s_channel_read(pdmRxHandle, micBuf, sizeof(micBuf), &dummy, 50 / portTICK_PERIOD_MS);
  }
  Serial.println("[MIC] Warmup complete — DMA flushed");

  // ── Initialize BLE ──
  Serial.println("\n[BLE] Initializing...");
  NimBLEDevice::init("AIGlasses-ESP32S3");
  NimBLEDevice::setMTU(BLE_MTU);
  NimBLEDevice::setPower(ESP_PWR_LVL_P9);

  pServer = NimBLEDevice::createServer();
  pServer->setCallbacks(new ServerCallbacks());

  // Create GATT service
  NimBLEService* pService = pServer->createService(SERVICE_UUID);

  // Audio TX: ESP32 mic → Android (NOTIFY)
  pAudioTxChar = pService->createCharacteristic(
    CHAR_AUDIO_TX_UUID,
    NIMBLE_PROPERTY::NOTIFY
  );

  // Audio RX: Android TTS → ESP32 speaker (WRITE + WRITE_NR for compatibility)
  pAudioRxChar = pService->createCharacteristic(
    CHAR_AUDIO_RX_UUID,
    NIMBLE_PROPERTY::WRITE | NIMBLE_PROPERTY::WRITE_NR
  );
  pAudioRxChar->setCallbacks(new AudioRxCallbacks());

  // Control: bidirectional commands (WRITE + WRITE_NR + NOTIFY)
  pControlChar = pService->createCharacteristic(
    CHAR_CONTROL_UUID,
    NIMBLE_PROPERTY::WRITE | NIMBLE_PROPERTY::WRITE_NR | NIMBLE_PROPERTY::NOTIFY
  );
  pControlChar->setCallbacks(new ControlCallbacks());

  // Image TX: camera JPEG → Android (NOTIFY)
  pImageTxChar = pService->createCharacteristic(
    CHAR_IMAGE_TX_UUID,
    NIMBLE_PROPERTY::NOTIFY
  );

  // Start service
  pService->start();

  // Start advertising
  NimBLEAdvertising* pAdvertising = NimBLEDevice::getAdvertising();
  pAdvertising->addServiceUUID(SERVICE_UUID);
  pAdvertising->start();

  Serial.println("[BLE] GATT server started");
  Serial.println("[BLE] Advertising as 'AIGlasses-ESP32S3'");
  Serial.printf("[PERF-M13] Heap after BLE init: %u KB free | PSRAM: %u KB free\n",
                ESP.getFreeHeap() / 1024, ESP.getFreePsram() / 1024);

  Serial.println();
  Serial.println("============================================================");
  Serial.println("  READY!");
  Serial.printf("  Press once  + hold GPIO%d → voice question (attaches a photo taken in last 5s)\n", PTT_PIN);
  Serial.println("  Quick double-tap          → take photo (saved; ask within 5s to query it)");
  Serial.println("  Press twice + hold        → photo + voice bundled (vision AI)");
  Serial.println("  Press 3x    + hold        → video recording");
  Serial.println("  No WiFi hotspot needed — uses BLE directly!");
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

  // Debounced button read (active HIGH — pressed when GPIO reads HIGH)
  bool raw1 = (digitalRead(PTT_PIN) == HIGH);
  delay(10);
  bool raw2 = (digitalRead(PTT_PIN) == HIGH);
  bool pressed = raw1 && raw2;

  static bool wasPressed = false;
  static bool isRecording = false;

  if (!pressed) {
    if (wasPressed) {
      unsigned long pressDuration = millis() - pressStartTime;

      if (videoMode) {
        // Stop video recording
        Serial.printf("[PTT] Video stopped → %d frames captured\n", videoFrameCount);
        sendVideoEndMarker((uint8_t)min(videoFrameCount, 255));
        videoMode = false;
        videoFrameCount = 0;
        tapCount = 0;

      } else if (isRecording) {
        if (pressDuration < QUICK_TAP_MAX_MS) {
          // Quick tap.
          lastQuickTapTime = millis();
          if (tapCount == 2 && visionMode && capturedJpeg) {
            // Quick double-tap → STANDALONE PHOTO: send the image but NO audio
            // END marker. Android stores + saves it and opens a 5s window during
            // which a single-hold question will attach this photo.
            Serial.println("[PTT] Quick double-tap → PHOTO (stored, no question)");
            sendCapturedImage();
            tapCount = 0;  // gesture complete
          } else if (visionMode && capturedJpeg) {
            // Stray captured frame from another tap pattern — discard it.
            free(capturedJpeg);
            capturedJpeg = nullptr;
            capturedJpegLen = 0;
            Serial.printf("[PTT] Quick tap (tapCount=%d) — discarded capture\n", tapCount);
          } else {
            Serial.printf("[PTT] Quick tap (tapCount=%d)\n", tapCount);
          }
          visionMode = false;
          isRecording = false;
        } else {
          // Long press — send image (vision) + audio END
          unsigned long _recMs = millis() - perfAudioTxStartMs;
          float _audioThroughput = (_recMs > 0) ? (perfAudioTxBytes * 1000.0f / _recMs) : 0;
          float _durSec = perfAudioTxBytes / (16000.0f * 2.0f);
          Serial.printf("[PERF-M11] Utterance: %lu bytes (%.2f s), %lu chunks\n",
                        perfAudioTxBytes, _durSec, perfAudioTxChunks);
          Serial.printf("[PERF-M1]  Mic→Android BLE TX: %lu bytes in %lu ms → %.0f B/s (%.1f KB/s)\n",
                        perfAudioTxBytes, _recMs, _audioThroughput, _audioThroughput / 1024.0f);
          if (perfMicSampleCount > 0) {
            Serial.printf("[PERF-M25] Mic amplitude: min=%d max=%d avgAbs=%ld over %lu samples\n",
                          (int)perfMicMinAmp, (int)perfMicMaxAmp,
                          perfMicSumAmp / (long)perfMicSampleCount, perfMicSampleCount);
          }
          if (visionMode) {
            Serial.println("[PTT] Released → sending image + audio END");
            sendCapturedImage();
            delay(20);
          } else {
            Serial.println("[PTT] Released → sending audio END");
          }
          uint8_t endPkt[BLE_HEADER_SIZE] = {'E', 0};
          pControlChar->setValue(endPkt, BLE_HEADER_SIZE);
          pControlChar->notify();
          isRecording = false;
          visionMode = false;
          tapCount = 0;
        }
      }
    }
    wasPressed = false;
    delay(20);
    return;
  }

  // Button is pressed
  if (!wasPressed) {
    pressStartTime = millis();
    perfButtonPressMs = millis();  // M7: start of round-trip timer
    unsigned long now = millis();
    bool withinWindow = (tapCount > 0) && (now - lastQuickTapTime < TAP_WINDOW_MS);

    if (!withinWindow) tapCount = 1;
    else tapCount++;

    if (tapCount >= 3) {
      // Triple-tap + hold → video recording
      Serial.println("[PTT] Triple-tap + hold → VIDEO RECORDING");
      videoMode = true;
      videoFrameCount = 0;
      isRecording = false;
      visionMode = false;
      sendVideoStartMarker();
    } else if (tapCount == 2) {
      // Double-tap: capture a photo now. The release handler decides what to do:
      //   • QUICK release → standalone photo (stored/saved, no question)
      //   • HOLD          → photo + voice question bundled (vision)
      Serial.println("[PTT] Double-tap → capturing photo");
      visionMode = true;
      txSeqNum = 0;
      isRecording = true;
      sendAudioStartMarker();  // flush stale audio on the app side
      // Reset M1/M25 accumulators
      perfAudioTxStartMs = millis();
      perfAudioTxBytes = 0;
      perfAudioTxChunks = 0;
      perfMicMinAmp = 32767;
      perfMicMaxAmp = -32768;
      perfMicSumAmp = 0;
      perfMicSampleCount = 0;
      // If capture fails, fall back to voice-only instead of silently sending
      // audio with no image (which leaves Android waiting for a JPEG that never
      // arrives, or processing a half-baked vision request).
      if (!captureSnapshot()) {
        Serial.println("[CAM] Capture failed → falling back to VOICE-ONLY for this utterance");
        visionMode = false;
      }
    } else {
      // Single press + hold → voice only (app attaches a recent photo if one
      // was taken within the last 5s)
      Serial.println("[PTT] Single press → voice only");
      visionMode = false;
      txSeqNum = 0;
      isRecording = true;
      sendAudioStartMarker();  // flush stale audio on the app side
      // Reset M1/M25 accumulators
      perfAudioTxStartMs = millis();
      perfAudioTxBytes = 0;
      perfAudioTxChunks = 0;
      perfMicMinAmp = 32767;
      perfMicMaxAmp = -32768;
      perfMicSumAmp = 0;
      perfMicSampleCount = 0;
    }
  }
  wasPressed = true;

  // Video mode: capture and stream one frame per loop iteration while held
  if (videoMode) {
    sendVideoFrame((uint8_t)(videoFrameCount & 0xFF));
    videoFrameCount++;
    return;
  }

  if (!isRecording) {
    delay(1);
    return;
  }

  // Read 16-bit PCM directly from PDM mic (new I2S API, I2S_NUM_0)
  size_t bytesRead = 0;
  esp_err_t ok = i2s_channel_read(pdmRxHandle, micBuf, sizeof(micBuf), &bytesRead, 100 / portTICK_PERIOD_MS);
  if (ok != ESP_OK || bytesRead == 0) return;

  int samples = bytesRead / 2;
  size_t bytesOut = bytesRead;

  // ── DC-blocking high-pass + software gain ──
  // PDM mic output is quiet and has a DC offset; this fixes both.
  // y[n] = x[n] - x[n-1] + 0.995 * y[n-1]
  static float dcPrevX = 0.0f;
  static float dcPrevY = 0.0f;
  const float  DC_R    = 0.995f;
  const int    MIC_GAIN = 10;   // raise if still quiet, lower if it clips/distorts

  for (int i = 0; i < samples; i++) {
    float x = (float)micBuf[i];
    float y = x - dcPrevX + DC_R * dcPrevY;
    dcPrevX = x;
    dcPrevY = y;
    int32_t s = (int32_t)(y * MIC_GAIN);
    if (s >  32767) s =  32767;
    if (s < -32768) s = -32768;
    micBuf[i] = (int16_t)s;
  }

  // Debug: print mic amplitude every 20 chunks + accumulate M25 stats
  static int debugChunk = 0;
  {
    int16_t minVal = 32767, maxVal = -32768;
    long sum = 0;
    for (int i = 0; i < samples; i++) {
      int16_t s = micBuf[i];
      if (s < minVal) minVal = s;
      if (s > maxVal) maxVal = s;
      sum += abs(s);
      // M25 rolling stats
      if (s < perfMicMinAmp) perfMicMinAmp = s;
      if (s > perfMicMaxAmp) perfMicMaxAmp = s;
      perfMicSumAmp += abs(s);
    }
    perfMicSampleCount += samples;
    if (++debugChunk % 20 == 0) {
      Serial.printf("[MIC] min=%d max=%d avgAmp=%ld (samples=%d)\n",
                    minVal, maxVal, sum / samples, samples);
    }
  }

  // Send 16-bit PCM to Android via BLE NOTIFY
  sendMicChunkViaBLE((uint8_t*)micBuf, bytesOut);

  delay(1);
}
