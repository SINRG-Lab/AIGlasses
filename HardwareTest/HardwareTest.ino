// ════════════════════════════════════════════════════════════════
//  HardwareTest — bench smoke test for the AI Glasses prototype
//  (XIAO ESP32-S3 Sense on the custom carrier + power boards)
//
//  NO BLE, NO app — just USB serial.
//
//  HEAR the mic:  HOLD the PTT button → records; RELEASE → plays it
//                 back through the speakers (normalized). Or send 'r'
//                 for a fixed 3-second record-and-playback.
//  SEE the camera: python3 view_camera.py --live  → browser viewfinder
//                 (grabs frames via the 'p' command underneath)
//
//  Voice-loop commands (used by view_camera.py's Talk button — together
//  they run mic → gpt-4o-mini-transcribe → gpt-4o-mini-tts → speakers):
//    R  = record 4 s, dump it as base64 PCM (<<<PCM ... >>>END)
//    P  = receive PCM to play: 'P' + u32 LE byte count + raw
//         24 kHz 16-bit mono PCM, then it plays through the speakers
//
//  BLE voice mode (GPT Realtime via realtime_ble.py — NO USB needed):
//    Same GATT service as the real S3_App_V2 firmware (aa00/aa01/aa02/aa03).
//    CONTROL writes: 'M' mic streaming on · 'm' off ·
//                    'S' response audio incoming (mutes mic) · 'E' response end
//    AUDIO_TX notify: 'A' + seq + PCM16 @16 kHz (even payload, continuous)
//    AUDIO_RX write:  'A' + seq + PCM16 @16 kHz response audio -> PSRAM ring
//    Playback is 16 kHz on the BLE path (downsampled Mac-side) so the link
//    rate comfortably exceeds consumption even at 1M PHY.
//
//  ALWAYS-ON voice mode (GPT Realtime via view_camera.py):
//    S  = enter streaming mode. Full-duplex over USB serial:
//         up:   [0xA5 0x5A][len u16 LE][PCM16 @16 kHz]   mic, continuous
//         down: [0xBA][len u16 LE][PCM16 @24 kHz]        response audio
//               [0xBE]                                    end of response
//               'T'                                       leave streaming mode
//         Response audio pre-buffers ~200 ms in a PSRAM ring and starts
//         playing immediately (mic pauses during playback — no AEC, so
//         half-duplex turn-taking; the ring absorbs faster-than-realtime
//         generation).
//
//  Serial monitor @ 115200. Commands (single character):
//    r  = record 3 s from the mic, then play it back
//    b  = beep the speakers (1 kHz, 400 ms)
//    B  = louder/longer beep (use battery or solid 5V — brownout risk)
//    c  = capture a camera frame, print JPEG size + header bytes
//    p  = capture a frame and dump it as base64 (used by view_camera.py)
//    v  = toggle camera QVGA 320x240 ↔ VGA 640x480 (VGA = focus checking)
//    m  = toggle the live mic level meter on/off
//    i  = info: PSRAM / heap / camera / uptime
//    h  = help
//
//  Flash settings (Arduino IDE): Board = XIAO_ESP32S3,
//  Tools → PSRAM → **OPI PSRAM** (camera + recording need it).
// ════════════════════════════════════════════════════════════════
#include <Arduino.h>
#include <driver/i2s_std.h>
#include <driver/i2s_pdm.h>
#include "esp_camera.h"
#include <NimBLEDevice.h>   // h2zero NimBLE-Arduino >= 2.x (Library Manager)

// ── Pins (same as S3_App_V2/config.h) ──
#define PDM_CLK    42
#define PDM_DATA   41
#define I2S_BCLK    9
#define I2S_WS      5
#define AMP_DIN     8
#define PTT_PIN     6
#define LED_PIN    21   // XIAO user LED, active LOW

#define MIC_RATE  16000
#define SPK_RATE  24000

// Record-and-playback buffer (PSRAM): 15 s @ 16 kHz 16-bit mono
#define REC_MAX_SECONDS 15
#define REC_MAX_BYTES   (REC_MAX_SECONDS * MIC_RATE * 2)

// Camera pins (XIAO ESP32-S3 Sense)
#define CAM_XCLK 10
#define CAM_SIOD 40
#define CAM_SIOC 39
#define CAM_Y9   48
#define CAM_Y8   11
#define CAM_Y7   12
#define CAM_Y6   14
#define CAM_Y5   16
#define CAM_Y4   18
#define CAM_Y3   17
#define CAM_Y2   15
#define CAM_VSYNC 38
#define CAM_HREF  47
#define CAM_PCLK  13

static i2s_chan_handle_t sMic = NULL;
static bool     sCameraOk  = false;
static bool     sMeterOn   = true;
static bool     sVga       = false;
static uint8_t* sRec       = NULL;
static uint32_t sLastShotMs = 0;
static int16_t  sBuf[512];
static int16_t  sStereo[512];   // 256 stereo frames per speaker write

// ────────────────────────────────────────────────────────────────
//  Mic (PDM RX, I2S_NUM_0) — clocked at 128× like the main firmware
// ────────────────────────────────────────────────────────────────
static bool micInit() {
  i2s_chan_config_t chan = I2S_CHANNEL_DEFAULT_CONFIG(I2S_NUM_0, I2S_ROLE_MASTER);
  if (i2s_new_channel(&chan, NULL, &sMic) != ESP_OK) return false;

  i2s_pdm_rx_config_t cfg = {
    .clk_cfg  = I2S_PDM_RX_CLK_DEFAULT_CONFIG(MIC_RATE),
    .slot_cfg = I2S_PDM_RX_SLOT_DEFAULT_CONFIG(I2S_DATA_BIT_WIDTH_16BIT, I2S_SLOT_MODE_MONO),
    .gpio_cfg = { .clk = (gpio_num_t)PDM_CLK, .din = (gpio_num_t)PDM_DATA,
                  .invert_flags = { .clk_inv = false } },
  };
  // 2.048 MHz PDM clock — the mic's in-spec Standard mode (default 64× lands
  // in an uncharacterized gap; see MIC_ROOT_CAUSE_ANALYSIS.md §5.1)
  cfg.clk_cfg.dn_sample_mode = I2S_PDM_DSR_16S;

  if (i2s_channel_init_pdm_rx_mode(sMic, &cfg) != ESP_OK) return false;
  if (i2s_channel_enable(sMic) != ESP_OK) return false;

  size_t d;                                   // flush startup garbage
  for (int i = 0; i < 5; i++) i2s_channel_read(sMic, sBuf, sizeof(sBuf), &d, 20);
  return true;
}

// ────────────────────────────────────────────────────────────────
//  Speaker helpers (STD TX, I2S_NUM_1) — open, use, close clean
// ────────────────────────────────────────────────────────────────
static i2s_chan_handle_t spkOpen(uint32_t rate) {
  i2s_chan_handle_t spk = NULL;
  i2s_chan_config_t chan = I2S_CHANNEL_DEFAULT_CONFIG(I2S_NUM_1, I2S_ROLE_MASTER);
  if (i2s_new_channel(&chan, &spk, NULL) != ESP_OK) return NULL;

  i2s_std_config_t cfg = {
    .clk_cfg  = I2S_STD_CLK_DEFAULT_CONFIG(rate),
    .slot_cfg = I2S_STD_PHILIPS_SLOT_DEFAULT_CONFIG(I2S_DATA_BIT_WIDTH_16BIT, I2S_SLOT_MODE_STEREO),
    .gpio_cfg = { .mclk = I2S_GPIO_UNUSED, .bclk = (gpio_num_t)I2S_BCLK,
                  .ws = (gpio_num_t)I2S_WS, .dout = (gpio_num_t)AMP_DIN,
                  .din = I2S_GPIO_UNUSED,
                  .invert_flags = { .mclk_inv = false, .bclk_inv = false, .ws_inv = false } },
  };
  if (i2s_channel_init_std_mode(spk, &cfg) != ESP_OK ||
      i2s_channel_enable(spk) != ESP_OK) {
    i2s_del_channel(spk);
    return NULL;
  }
  return spk;
}

static void spkClose(i2s_chan_handle_t spk) {
  if (spk) {
    size_t w;
    memset(sStereo, 0, sizeof(sStereo));      // flush silence — no teardown pop
    for (int i = 0; i < 6; i++) i2s_channel_write(spk, sStereo, sizeof(sStereo), &w, 100);
    delay(50);
    i2s_channel_disable(spk);
    i2s_del_channel(spk);
  }
  pinMode(AMP_DIN, OUTPUT);                   // idle clocking = hiss; pin low = silence
  digitalWrite(AMP_DIN, LOW);
}

static void beep(int freqHz, int ms, int amplitude) {
  if (sMic) i2s_channel_disable(sMic);        // PDM clock crosstalks the speaker BCLK
  i2s_chan_handle_t spk = spkOpen(SPK_RATE);
  if (!spk) {
    Serial.println("[SPK] init FAILED");
  } else {
    Serial.printf("[SPK] beep %d Hz, %d ms, amp %d\n", freqHz, ms, amplitude);
    int totalFrames = (SPK_RATE * ms) / 1000;
    float phase = 0, step = 2.0f * PI * freqHz / SPK_RATE;
    size_t w;
    for (int done = 0; done < totalFrames; ) {
      int n = min(256, totalFrames - done);
      for (int i = 0; i < n; i++) {
        int16_t s = (int16_t)(sinf(phase) * amplitude);
        phase += step;
        sStereo[2 * i] = s;
        sStereo[2 * i + 1] = s;
      }
      i2s_channel_write(spk, sStereo, n * 4, &w, 200);
      done += n;
    }
    spkClose(spk);
  }
  if (sMic) i2s_channel_enable(sMic);
  Serial.println("[SPK] done");
}

// ────────────────────────────────────────────────────────────────
//  Record → playback ("hear what the mic hears")
//  Hold the button (record while held) or 'r' (fixed 3 s).
// ────────────────────────────────────────────────────────────────
static void recordAndPlay(bool untilRelease) {
  if (!sMic) { Serial.println("[REC] mic not available"); return; }
  if (!sRec) { Serial.println("[REC] no PSRAM record buffer (check PSRAM setting)"); return; }

  Serial.println(untilRelease ? "[REC] recording — release the button to stop..."
                              : "[REC] recording 3 seconds — speak now...");
  digitalWrite(LED_PIN, LOW);                 // LED on = recording

  size_t recLen = 0;
  int peak = 0;
  uint32_t start = millis(), lastMeter = 0;
  while (true) {
    if (untilRelease  && digitalRead(PTT_PIN) != HIGH) break;
    if (!untilRelease && millis() - start >= 3000) break;
    if (recLen + sizeof(sBuf) > REC_MAX_BYTES) { Serial.println("[REC] buffer full"); break; }
    size_t got = 0;
    if (i2s_channel_read(sMic, sBuf, sizeof(sBuf), &got, 100) != ESP_OK || got == 0) continue;
    memcpy(sRec + recLen, sBuf, got);
    recLen += got;
    int n = got / 2;
    for (int i = 0; i < n; i++) { int a = abs((int)sBuf[i]); if (a > peak) peak = a; }
    if (millis() - lastMeter >= 250) {
      lastMeter = millis();
      Serial.printf("[REC] %5.2f s  peak=%d\n", recLen / (MIC_RATE * 2.0), peak);
    }
  }
  digitalWrite(LED_PIN, HIGH);

  float secs = recLen / (MIC_RATE * 2.0);
  Serial.printf("[REC] captured %u bytes (%.2f s), peak=%d (%.1f dBFS)\n",
                (unsigned)recLen, secs, peak,
                peak > 0 ? 20.0 * log10((double)peak / 32768.0) : -96.0);
  if (recLen < MIC_RATE / 5 * 2) { Serial.println("[REC] too short — hold longer"); return; }
  if (peak < 200) Serial.println("[REC] very quiet — is the mic port blocked?");

  // Normalize toward ~16000 peak (≈ what the app's ASR normalizer does),
  // capped at 16× so a silent room doesn't become white noise.
  float gain = 1.0f;
  if (peak > 0) gain = min(16.0f, 16000.0f / peak);
  Serial.printf("[PLAY] playing back at gain %.1fx ...\n", gain);

  i2s_channel_disable(sMic);                  // crosstalk + feedback guard
  i2s_chan_handle_t spk = spkOpen(MIC_RATE);  // play at the recording rate
  if (!spk) {
    Serial.println("[SPK] init FAILED");
  } else {
    const int16_t* mono = (const int16_t*)sRec;
    size_t total = recLen / 2;
    size_t w;
    for (size_t off = 0; off < total; ) {
      int n = min((size_t)256, total - off);
      for (int i = 0; i < n; i++) {
        int32_t s = (int32_t)(mono[off + i] * gain);
        if (s >  32767) s =  32767;
        if (s < -32768) s = -32768;
        sStereo[2 * i] = (int16_t)s;
        sStereo[2 * i + 1] = (int16_t)s;
      }
      i2s_channel_write(spk, sStereo, n * 4, &w, 200);
      off += n;
    }
    spkClose(spk);
  }
  i2s_channel_enable(sMic);
  Serial.println("[PLAY] done — that is what the mic heard");
}

// ────────────────────────────────────────────────────────────────
//  Voice loop over the wire (driven by view_camera.py's Talk button)
// ────────────────────────────────────────────────────────────────
static void printBase64(const uint8_t* data, size_t len);   // defined below

// 'R': record 4 s and dump as base64 so the Mac can transcribe it
static void recordAndDump() {
  if (!sMic || !sRec) { Serial.println("[REC] mic/buffer not available"); return; }
  Serial.println("[REC] recording 4 s — speak now...");
  digitalWrite(LED_PIN, LOW);
  size_t recLen = 0;
  uint32_t start = millis();
  while (millis() - start < 4000 && recLen + sizeof(sBuf) <= REC_MAX_BYTES) {
    size_t got = 0;
    if (i2s_channel_read(sMic, sBuf, sizeof(sBuf), &got, 100) == ESP_OK && got > 0) {
      memcpy(sRec + recLen, sBuf, got);
      recLen += got;
    }
  }
  digitalWrite(LED_PIN, HIGH);
  Serial.printf("<<<PCM len=%u rate=%d\n", (unsigned)recLen, MIC_RATE);
  printBase64(sRec, recLen);
  Serial.println(">>>END");
}

// 'P': receive raw PCM (u32 LE length + 24 kHz 16-bit mono data), play it
static void receiveAndPlayPcm() {
  uint8_t hdr[4];
  Serial.setTimeout(3000);
  if (Serial.readBytes(hdr, 4) != 4) { Serial.println("[PLAY] header timeout"); return; }
  uint32_t len = (uint32_t)hdr[0] | ((uint32_t)hdr[1] << 8) |
                 ((uint32_t)hdr[2] << 16) | ((uint32_t)hdr[3] << 24);
  if (!sRec || len == 0 || len > REC_MAX_BYTES) {
    Serial.printf("[PLAY] bad length %u (max %u)\n", len, (unsigned)REC_MAX_BYTES);
    return;
  }
  size_t got = 0;
  // Deadline scales with size: assume ≥10 KB/s effective + 10 s slack
  uint32_t deadline = millis() + (len / 10) + 10000;
  while (got < len && millis() < deadline) {
    size_t r = Serial.readBytes((char*)(sRec + got), min((size_t)4096, (size_t)(len - got)));
    got += r;
  }
  Serial.setTimeout(1000);
  if (got < len) { Serial.printf("[PLAY] rx timeout at %u/%u bytes\n", (unsigned)got, len); return; }

  Serial.printf("[PLAY] %u bytes @ 24 kHz — playing...\n", len);
  if (sMic) i2s_channel_disable(sMic);
  i2s_chan_handle_t spk = spkOpen(24000);     // gpt-4o-mini-tts PCM rate
  if (!spk) {
    Serial.println("[SPK] init FAILED");
  } else {
    const int16_t* mono = (const int16_t*)sRec;
    size_t total = len / 2, w;
    for (size_t off = 0; off < total; ) {
      int n = min((size_t)256, total - off);
      for (int i = 0; i < n; i++) {
        int16_t s = mono[off + i] >> 1;       // −6 dB: full-scale TTS + 9 dB amp
        sStereo[2 * i] = s;                   // gain clips and can sag USB power
        sStereo[2 * i + 1] = s;
      }
      i2s_channel_write(spk, sStereo, n * 4, &w, 200);
      off += n;
    }
    spkClose(spk);
  }
  if (sMic) i2s_channel_enable(sMic);
  Serial.println("[PLAY] done");
}

// ────────────────────────────────────────────────────────────────
//  BLE voice mode — same architecture as the serial voice mode but
//  event-driven from the main loop (bleVoiceTick) instead of modal.
//  Uses the S3_App_V2 GATT layout so the desktop client and, later,
//  the Android app speak one protocol.
//  NOTE: shares the sRec PSRAM ring with the serial voice/record
//  features — use one voice transport at a time.
// ────────────────────────────────────────────────────────────────
#define BLE_SVC_UUID   "0000aa00-1234-5678-abcd-0e5032c6b1e0"
#define BLE_TX_UUID    "0000aa01-1234-5678-abcd-0e5032c6b1e0"  // mic -> central (NOTIFY)
#define BLE_RX_UUID    "0000aa02-1234-5678-abcd-0e5032c6b1e0"  // central -> speaker (WRITE)
#define BLE_CTRL_UUID  "0000aa03-1234-5678-abcd-0e5032c6b1e0"  // commands (WRITE)
// Playback rate is commanded per-response by the client: 'S1' = 16 kHz,
// 'S2' = 24 kHz (native GPT Realtime rate — no resampling, no muffle).
// Prebuffer = 750 ms at the active rate, cushioning link stalls.
#define BLE_PREBUF_BYTES(rate)  ((rate) * 3 / 2)

// ── G.711 µ-law: BLE audio is 1 byte/sample both directions. Halves the
//    packet rate (the macOS central demonstrably drops notifications above
//    ~40/s), at telephony speech quality. Same trick commercial BLE
//    wearables use. ──
static uint8_t ulawEncode(int16_t pcm) {
  const int16_t CLIP = 32635;
  uint8_t sign = (pcm >> 8) & 0x80;
  if (sign) pcm = -pcm;
  if (pcm > CLIP) pcm = CLIP;
  pcm += 0x84;
  uint8_t exp = 7;
  for (uint16_t mask = 0x4000; (pcm & mask) == 0 && exp > 0; mask >>= 1) exp--;
  uint8_t mant = (pcm >> (exp + 3)) & 0x0F;
  return ~(sign | (exp << 4) | mant);
}

static int16_t ulawDecode(uint8_t u) {
  u = ~u;
  int16_t t = (((int16_t)(u & 0x0F)) << 3) + 0x84;
  t <<= (u & 0x70) >> 4;
  return (u & 0x80) ? (0x84 - t) : (t - 0x84);
}

static NimBLECharacteristic* sBleTx = NULL;
static volatile bool     sBleConnected = false;
static volatile bool     sBleMicOn = false;
static volatile bool     sBlePlaying = false;
static volatile bool     sBleEndSeen = false;
static volatile uint32_t sBleLastRxMs = 0;
static volatile size_t   sBleRw = 0, sBleRr = 0, sBleRc = 0;   // ring over sRec
static uint16_t          sBleConnHandle = 0;
static uint8_t           sBleSeq = 0;
static volatile uint32_t sBlePlayRate = 24000;   // set by 'S1'/'S2' marker
// downlink telemetry — the choppiness question needs data, not guesses
static volatile uint32_t sBleDlBytes = 0;
static volatile uint32_t sBleDlGaps = 0;
static volatile uint8_t  sBleDlSeq = 0;
static volatile bool     sBleDlSeqInit = false;

class BleSrvCb : public NimBLEServerCallbacks {
  void onConnect(NimBLEServer* srv, NimBLEConnInfo& info) override {
    sBleConnected = true;
    sBleConnHandle = info.getConnHandle();
    // 15-30 ms: Apple explicitly dislikes <15 ms interval requests from
    // peripherals and may reject/renegotiate erratically.
    srv->updateConnParams(sBleConnHandle, 12, 24, 0, 400);
    ble_gap_set_prefered_le_phy(sBleConnHandle,
                                BLE_GAP_LE_PHY_1M_MASK | BLE_GAP_LE_PHY_2M_MASK,
                                BLE_GAP_LE_PHY_1M_MASK | BLE_GAP_LE_PHY_2M_MASK, 0);
    // LL Data Length Extension — CRITICAL for the mic uplink. Without it each
    // radio packet carries 27 B, so a 508 B notification takes ~19 packets
    // (2-3 connection events) and the link tops out ~30 notifications/s vs
    // the ~94/s continuous mic audio needs (= the 40k lost frames measured).
    // With DLE one packet carries 251 B: a notification fits in ~2 packets.
    int dlrc = ble_gap_set_data_len(sBleConnHandle, 251, 2120);
    Serial.printf("[BLE] central connected (DLE rc=%d)\n", dlrc);
  }
  void onDisconnect(NimBLEServer* srv, NimBLEConnInfo& info, int reason) override {
    sBleConnected = false;
    sBleMicOn = false;
    sBleEndSeen = true;                    // let the tick drain + clean up
    Serial.printf("[BLE] disconnected (%d) — advertising\n", reason);
    NimBLEDevice::startAdvertising();
  }
};

class BleCtrlCb : public NimBLECharacteristicCallbacks {
  void onWrite(NimBLECharacteristic* ch, NimBLEConnInfo&) override {
    NimBLEAttValue v = ch->getValue();
    if (v.size() < 1) return;
    switch (v.data()[0]) {
      case 'M': sBleMicOn = true;  Serial.println("[BLE] mic streaming ON");  break;
      case 'm': sBleMicOn = false; Serial.println("[BLE] mic streaming OFF"); break;
      case 'S':                            // response audio incoming
        sBlePlayRate = (v.size() >= 2 && v.data()[1] == '2') ? 24000 : 16000;
        sBleRw = sBleRr = sBleRc = 0;
        sBleEndSeen = false;
        sBleDlSeqInit = false;
        sBleDlBytes = 0;
        sBleDlGaps = 0;
        sBlePlaying = true;
        sBleLastRxMs = millis();
        break;
      case 'E': sBleEndSeen = true; break;
      default: break;
    }
  }
};

class BleRxCb : public NimBLECharacteristicCallbacks {
  void onWrite(NimBLECharacteristic* ch, NimBLEConnInfo&) override {
    NimBLEAttValue v = ch->getValue();
    if (v.size() < 3 || v.data()[0] != 'A' || !sBlePlaying) return;
    uint8_t seq = v.data()[1];
    if (sBleDlSeqInit) {
      uint8_t gap = (uint8_t)(seq - sBleDlSeq - 1);
      if (gap) sBleDlGaps += gap;          // writes silently dropped somewhere
    }
    sBleDlSeq = seq;
    sBleDlSeqInit = true;
    // payload is µ-law — decode to PCM16 as it enters the ring
    static int16_t dec[512];
    const uint8_t* p = v.data() + 2;
    size_t n = min((size_t)(v.size() - 2), sizeof(dec) / 2);
    for (size_t i = 0; i < n; i++) dec[i] = ulawDecode(p[i]);
    size_t len = n * 2;
    sBleDlBytes += len;
    if (sBleRc + len > REC_MAX_BYTES) return;         // overflow: drop
    size_t first = min(len, REC_MAX_BYTES - sBleRw);
    memcpy(sRec + sBleRw, (uint8_t*)dec, first);
    if (len - first) memcpy(sRec, (uint8_t*)dec + first, len - first);
    sBleRw = (sBleRw + len) % REC_MAX_BYTES;
    sBleRc += len;
    sBleLastRxMs = millis();
  }
};

static void bleVoiceInit() {
  NimBLEDevice::init("AIGlasses-Bench");
  // Fresh random static address EVERY boot: macOS caches GATT tables (and,
  // when the table changed across reflashes, stale double-subscriptions that
  // duplicate/interleave notifications) keyed by address. A new address makes
  // the Mac treat us as a brand-new device with a clean cache, every time.
  uint8_t addr[6];
  esp_fill_random(addr, 6);
  addr[5] |= 0xC0;                       // random static: two MSBs must be 1
  NimBLEDevice::setOwnAddrType(BLE_OWN_ADDR_RANDOM);
  NimBLEDevice::setOwnAddr(addr);
  Serial.printf("[BLE] identity %02X:%02X:%02X:%02X:%02X:%02X (fresh each boot)\n",
                addr[5], addr[4], addr[3], addr[2], addr[1], addr[0]);
  NimBLEDevice::setMTU(512);
  NimBLEDevice::setPower(ESP_PWR_LVL_P9);
  NimBLEServer* srv = NimBLEDevice::createServer();
  srv->setCallbacks(new BleSrvCb());
  NimBLEService* svc = srv->createService(BLE_SVC_UUID);
  sBleTx = svc->createCharacteristic(BLE_TX_UUID, NIMBLE_PROPERTY::NOTIFY);
  svc->createCharacteristic(BLE_RX_UUID,
      NIMBLE_PROPERTY::WRITE | NIMBLE_PROPERTY::WRITE_NR)->setCallbacks(new BleRxCb());
  svc->createCharacteristic(BLE_CTRL_UUID,
      NIMBLE_PROPERTY::WRITE | NIMBLE_PROPERTY::WRITE_NR)->setCallbacks(new BleCtrlCb());
  svc->start();
  NimBLEAdvertising* adv = NimBLEDevice::getAdvertising();
  adv->addServiceUUID(BLE_SVC_UUID);
  adv->start();
  Serial.println("[BLE] advertising as 'AIGlasses-Bench'");
}

// Runs every loop() iteration. Mirrors the serial voice mode's half-duplex
// dance: stream mic up while idle; on 'S' mute mic, prebuffer, play, resume.
static void bleVoiceTick() {
  static i2s_chan_handle_t spk = NULL;
  static bool micMuted = false;
  static uint8_t pkt[512];

  if (sBlePlaying) {
    static uint32_t underruns = 0, dlStatMs = 0;
    static bool starving = false;
    if (!micMuted && sMic) { i2s_channel_disable(sMic); micMuted = true; }
    if (!sBleEndSeen && millis() - sBleLastRxMs > 3000) {   // stall watchdog
      Serial.println("[BLE] downlink stall — finishing turn");
      sBleEndSeen = true;
    }
    if (!spk && (sBleRc >= BLE_PREBUF_BYTES(sBlePlayRate) ||
                 (sBleEndSeen && sBleRc > 0))) {
      spk = spkOpen(sBlePlayRate);
      underruns = 0; starving = false; dlStatMs = millis();
      if (!spk) { Serial.println("[SPK] init FAILED"); sBleRc = 0; sBleEndSeen = true; }
    }
    if (spk && !sBleEndSeen) {                              // telemetry: is the
      if (sBleRc == 0 && !starving) { underruns++; starving = true; }  // ring starving?
      if (sBleRc > 0) starving = false;
      if (millis() - dlStatMs >= 2000) {
        dlStatMs = millis();
        Serial.printf("[BLE] dl 2s: rx=%u KB ring=%u KB gaps=%u underruns=%u @%u Hz\n",
                      sBleDlBytes / 2048, (unsigned)sBleRc / 1024,
                      sBleDlGaps, underruns, sBlePlayRate);
        sBleDlBytes = 0;
      }
    }
    if (spk && sBleRc > 0) {
      uint8_t mono[512];
      size_t n = min(sizeof(mono), (size_t)sBleRc) & ~(size_t)1;
      size_t first = min(n, REC_MAX_BYTES - sBleRr);
      memcpy(mono, sRec + sBleRr, first);
      if (n - first) memcpy(mono + first, sRec, n - first);
      sBleRr = (sBleRr + n) % REC_MAX_BYTES;
      sBleRc -= n;
      const int16_t* m = (const int16_t*)mono;
      size_t frames = n / 2, w;
      for (size_t i = 0; i < frames; i++) {
        int16_t s = m[i] >> 1;                      // -6 dB, as everywhere
        sStereo[2 * i] = s; sStereo[2 * i + 1] = s;
      }
      i2s_channel_write(spk, sStereo, frames * 4, &w, 100);
    }
    if (sBleEndSeen && sBleRc == 0) {
      if (spk) { spkClose(spk); spk = NULL; }
      sBlePlaying = false; sBleEndSeen = false;
      if (micMuted && sMic) { i2s_channel_enable(sMic); micMuted = false; }
      Serial.printf("[BLE] turn done (gaps=%u underruns=%u) — listening\n",
                    sBleDlGaps, underruns);
    }
    return;
  }

  // uplink: PUSH-TO-TALK, like the production firmware — mic notifications
  // flow only while the button is held (short bursts every BLE central
  // tolerates; continuous streaming was never validated on any stack).
  if (sBleMicOn && sBleConnected && sMic && sBleTx) {
    static uint32_t statOk = 0, statDrop = 0, statLastMs = 0;
    bool held = digitalRead(PTT_PIN) == HIGH;
    size_t got = 0;
    // read regardless (keeps the DMA drained and the audio fresh on press)
    if (i2s_channel_read(sMic, sBuf, sizeof(sBuf), &got, 40) == ESP_OK &&
        got > 0 && held) {
      // µ-law encode: 512 samples -> 512 bytes -> usually ONE notification
      static uint8_t enc[512];
      size_t samples = got / 2;
      for (size_t i = 0; i < samples; i++) enc[i] = ulawEncode(sBuf[i]);
      uint16_t mtu = NimBLEDevice::getServer()->getPeerMTU(sBleConnHandle);
      if (mtu < 23) mtu = 23;
      size_t payload = min((size_t)mtu - 3 - 2, (size_t)508);
      size_t off = 0;
      while (off < samples) {
        size_t n = min(payload, samples - off);
        pkt[0] = 'A'; pkt[1] = sBleSeq++;
        memcpy(pkt + 2, enc + off, n);
        sBleTx->setValue(pkt, n + 2);
        bool ok = false;
        for (int t = 0; t < 10; t++) {
          if (sBleTx->notify()) { ok = true; break; }
          delay(3);
        }
        ok ? statOk++ : statDrop++;
        off += n;
        delay(5);   // production pacing: ~one fragment per connection event
      }
    }
    if (millis() - statLastMs >= 2000) {                     // link telemetry
      statLastMs = millis();
      ble_gap_conn_desc d;
      float itvl = (ble_gap_conn_find(sBleConnHandle, &d) == 0)
                       ? d.conn_itvl * 1.25f : -1.0f;
      Serial.printf("[BLE] uplink 2s: ok=%u drop=%u itvl=%.1fms mtu=%u\n",
                    statOk, statDrop,
                    itvl, NimBLEDevice::getServer()->getPeerMTU(sBleConnHandle));
      statOk = statDrop = 0;
    }
  }
}
//  Owns the main loop until 'T' arrives. Half-duplex audio:
//  mic frames stream up; when response audio arrives, the mic pauses
//  (crosstalk + no echo cancellation), the audio plays from a PSRAM
//  ring, then the mic resumes for the next turn.
// ────────────────────────────────────────────────────────────────
#define VOICE_PREBUF_BYTES  9600   // ~200 ms @ 24 kHz before playback starts
#define VOICE_MAX_FRAME     2048   // sanity cap on downlink frame length

static void voiceStreamMode() {
  if (!sMic || !sRec) { Serial.println("[VOICE] mic/buffer missing — cannot stream"); return; }
  Serial.println("[VOICE] streaming ON");
  digitalWrite(LED_PIN, LOW);                 // LED on = session live

  // sRec doubles as the playback ring buffer (mic reads use sBuf directly)
  size_t rw = 0, rr = 0, rc = 0;              // ring write / read / count
  bool playing = false, endSeen = false;
  uint32_t lastDlMs = 0;                      // stall watchdog: last downlink data
  i2s_chan_handle_t spk = NULL;
  static uint8_t frame[VOICE_MAX_FRAME];
  static uint8_t mono[512];

  auto cleanup = [&]() {
    if (spk) { spkClose(spk); spk = NULL; }
    if (sMic) i2s_channel_enable(sMic);       // idempotent-ish: only disabled while playing
    digitalWrite(LED_PIN, HIGH);
    Serial.println("[VOICE] streaming OFF");
  };

  while (true) {
    // ── 1) drain the downlink ──
    while (Serial.available()) {
      int b = Serial.read();
      if (b == 'T') { cleanup(); return; }
      if (b == 0xBE) { endSeen = true; lastDlMs = millis(); continue; }
      if (b != 0xBA) continue;                // ignore stray bytes/newlines
      uint8_t hdr[2];
      if (Serial.readBytes(hdr, 2) != 2) break;
      uint16_t len = hdr[0] | (hdr[1] << 8);
      if (len == 0 || len > VOICE_MAX_FRAME) {          // desync guard
        for (uint16_t k = 0; k < len; k++) { if (Serial.read() < 0) break; }
        continue;
      }
      if (Serial.readBytes(frame, len) != len) break;
      lastDlMs = millis();
      if (!playing) {                          // first audio of a response
        playing = true; endSeen = false;
        if (sMic) i2s_channel_disable(sMic);   // half-duplex: mute mic while speaking
      }
      if (rc + len > REC_MAX_BYTES) {          // ring overflow — drop, keep sync
        Serial.println("[VOICE] ring overflow — dropping audio (answer too long?)");
        continue;
      }
      size_t first = min((size_t)len, REC_MAX_BYTES - rw);
      memcpy(sRec + rw, frame, first);
      if (len - first) memcpy(sRec, frame + first, len - first);
      rw = (rw + len) % REC_MAX_BYTES;
      rc += len;
    }

    // ── 2) feed the speaker from the ring ──
    if (playing) {
      // Stall watchdog: dropped frames or a lost end marker must never strand
      // the session muted. 3 s without downlink data = treat as end of turn
      // (mirrors the main firmware's STREAM_STALL_TIMEOUT).
      if (!endSeen && millis() - lastDlMs > 3000) {
        Serial.println("[VOICE] downlink stall — finishing turn");
        endSeen = true;
      }
      if (!spk && (rc >= VOICE_PREBUF_BYTES || (endSeen && rc > 0))) {
        spk = spkOpen(24000);
        if (!spk) { Serial.println("[SPK] init FAILED"); playing = false; endSeen = false;
                    rc = 0; rr = rw; if (sMic) i2s_channel_enable(sMic); continue; }
      }
      if (spk && rc > 0) {
        size_t n = min((size_t)sizeof(mono), rc) & ~(size_t)1;
        size_t first = min(n, REC_MAX_BYTES - rr);
        memcpy(mono, sRec + rr, first);
        if (n - first) memcpy(mono + first, sRec, n - first);
        rr = (rr + n) % REC_MAX_BYTES;
        rc -= n;
        const int16_t* m = (const int16_t*)mono;
        size_t frames = n / 2, w;
        for (size_t i = 0; i < frames; i++) {
          int16_t s = m[i] >> 1;               // −6 dB, same as 'P' playback
          sStereo[2 * i] = s;
          sStereo[2 * i + 1] = s;
        }
        i2s_channel_write(spk, sStereo, frames * 4, &w, 100);
      }
      if (endSeen && rc == 0) {                // response finished — next turn
        if (spk) { spkClose(spk); spk = NULL; }
        playing = false; endSeen = false;
        if (sMic) i2s_channel_enable(sMic);
        Serial.println("[VOICE] turn done — listening");
      }
      continue;                                // no mic uplink while speaking
    }

    // ── 3) uplink mic frames ──
    size_t got = 0;
    if (i2s_channel_read(sMic, sBuf, sizeof(sBuf), &got, 40) == ESP_OK && got > 0) {
      uint8_t h[4] = { 0xA5, 0x5A, (uint8_t)(got & 0xFF), (uint8_t)(got >> 8) };
      Serial.write(h, 4);
      Serial.write((const uint8_t*)sBuf, got);
    }
  }
}

// ────────────────────────────────────────────────────────────────
//  Camera
// ────────────────────────────────────────────────────────────────
static bool cameraInit() {
  camera_config_t c = {};
  c.ledc_channel = LEDC_CHANNEL_0; c.ledc_timer = LEDC_TIMER_0;
  c.pin_d0 = CAM_Y2; c.pin_d1 = CAM_Y3; c.pin_d2 = CAM_Y4; c.pin_d3 = CAM_Y5;
  c.pin_d4 = CAM_Y6; c.pin_d5 = CAM_Y7; c.pin_d6 = CAM_Y8; c.pin_d7 = CAM_Y9;
  c.pin_xclk = CAM_XCLK; c.pin_pclk = CAM_PCLK; c.pin_vsync = CAM_VSYNC; c.pin_href = CAM_HREF;
  c.pin_sscb_sda = CAM_SIOD; c.pin_sscb_scl = CAM_SIOC;
  c.pin_pwdn = -1; c.pin_reset = -1;
  c.xclk_freq_hz = 20000000;
  c.pixel_format = PIXFORMAT_JPEG;
  c.grab_mode    = CAMERA_GRAB_LATEST;
  // Init at VGA so the driver's frame buffers are big enough for the 'v'
  // toggle (runtime set_framesize can only go up to the init size).
  c.frame_size   = FRAMESIZE_VGA;
  c.jpeg_quality = 12;
  c.fb_count     = 1;
  c.fb_location  = CAMERA_FB_IN_PSRAM;
  if (esp_camera_init(&c) != ESP_OK) return false;
  sensor_t* s = esp_camera_sensor_get();
  if (s) {
    Serial.printf("[CAM] sensor PID 0x%04X (OV3660=0x3660, OV2640=0x2642)\n", s->id.PID);
    s->set_framesize(s, FRAMESIZE_QVGA);      // default: fast; 'v' toggles VGA
  }
  return true;
}

// Warm up AE/AWB only after the camera has been idle — burst captures
// (the --live viewfinder) skip it, which is what makes live view fluid.
static void cameraWarmupIfIdle() {
  if (millis() - sLastShotMs < 3000) return;
  for (int i = 0; i < 3; i++) { camera_fb_t* w = esp_camera_fb_get(); if (w) esp_camera_fb_return(w); delay(30); }
}

static camera_fb_t* grabFrame() {
  if (!sCameraOk) { Serial.println("[CAM] not available (init failed at boot)"); return NULL; }
  cameraWarmupIfIdle();
  camera_fb_t* fb = esp_camera_fb_get();
  if (!fb) Serial.println("[CAM] capture FAILED (fb NULL — check PSRAM setting!)");
  else sLastShotMs = millis();
  return fb;
}

static void captureTest() {
  camera_fb_t* fb = grabFrame();
  if (!fb) return;
  Serial.printf("[CAM] captured %ux%u, %u bytes JPEG, header: %02X %02X %02X %02X %s\n",
                fb->width, fb->height, fb->len,
                fb->buf[0], fb->buf[1], fb->buf[2], fb->buf[3],
                (fb->buf[0] == 0xFF && fb->buf[1] == 0xD8) ? "(valid JPEG SOI ✓)" : "(BAD — not a JPEG!)");
  esp_camera_fb_return(fb);
}

// ── base64 JPEG dump ('p') — decoded on the Mac by view_camera.py ──
static const char B64[] = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789+/";

static void printBase64(const uint8_t* data, size_t len) {
  char line[81];
  size_t li = 0;
  for (size_t i = 0; i < len; i += 3) {
    uint32_t v = ((uint32_t)data[i] << 16) |
                 ((i + 1 < len ? (uint32_t)data[i + 1] : 0) << 8) |
                 (i + 2 < len ? (uint32_t)data[i + 2] : 0);
    line[li++] = B64[(v >> 18) & 63];
    line[li++] = B64[(v >> 12) & 63];
    line[li++] = (i + 1 < len) ? B64[(v >> 6) & 63] : '=';
    line[li++] = (i + 2 < len) ? B64[v & 63] : '=';
    if (li >= 76) { line[li] = 0; Serial.println(line); li = 0; }
  }
  if (li) { line[li] = 0; Serial.println(line); }
}

static void dumpPhoto() {
  camera_fb_t* fb = grabFrame();
  if (!fb) return;
  // Framed so the viewer script can find it; meter lines can't interleave
  // (single-threaded loop). Native USB CDC → the dump takes well under 1 s.
  Serial.printf("<<<JPEG len=%u\n", fb->len);
  printBase64(fb->buf, fb->len);
  Serial.println(">>>END");
  esp_camera_fb_return(fb);
}

static void toggleResolution() {
  if (!sCameraOk) { Serial.println("[CAM] not available"); return; }
  sensor_t* s = esp_camera_sensor_get();
  if (!s) return;
  sVga = !sVga;
  s->set_framesize(s, sVga ? FRAMESIZE_VGA : FRAMESIZE_QVGA);
  sLastShotMs = 0;                            // force a warmup after the switch
  Serial.printf("[CAM] resolution → %s\n", sVga ? "VGA 640x480 (focus checking)" : "QVGA 320x240 (fast)");
}

static void printInfo() {
  Serial.printf("[INFO] PSRAM: %u KB (%s) | free heap: %u KB | free PSRAM: %u KB\n",
                ESP.getPsramSize() / 1024,
                ESP.getPsramSize() > 0 ? "OK" : "MISSING — set Tools->PSRAM->OPI PSRAM!",
                ESP.getFreeHeap() / 1024, ESP.getFreePsram() / 1024);
  Serial.printf("[INFO] camera: %s (%s) | mic: %s | rec buffer: %s | uptime: %lu s\n",
                sCameraOk ? "OK" : "FAILED", sVga ? "VGA" : "QVGA",
                sMic ? "OK" : "FAILED",
                sRec ? "OK (15 s)" : "MISSING",
                millis() / 1000);
}

static void printHelp() {
  Serial.println("HOLD BUTTON = record mic, release = hear it back.");
  Serial.println("Commands: r=3s record+playback  b=beep  B=loud beep  c=camera check");
  Serial.println("          p=photo dump (view_camera.py)  v=QVGA/VGA  m=meter on/off  i=info  h=help");
}

// ────────────────────────────────────────────────────────────────
void setup() {
  // Native USB CDC defaults to a 256-byte RX ring — the 'P' PCM upload
  // overflows it and bytes vanish silently. Must be set before begin().
  // 16 KB (not 32): the BT controller needs contiguous internal RAM, and the
  // paced uploads burst at most ~12 KB.
  Serial.setRxBufferSize(16 * 1024);
  Serial.begin(115200);
  // If the host stops reading (webapp killed mid voice-stream), a blocking
  // Serial.write of mic frames can wedge the whole loop and knock the
  // device off the USB bus. Drop after 50 ms instead of blocking forever.
  Serial.setTxTimeoutMs(50);
  delay(1500);
  Serial.println("\n================ AI GLASSES HARDWARE TEST ================");

  pinMode(LED_PIN, OUTPUT); digitalWrite(LED_PIN, HIGH);  // off (active LOW)
  pinMode(PTT_PIN, INPUT_PULLDOWN);

  // BLE controller FIRST: it needs a large contiguous chunk of *internal*
  // RAM and its init hard-crashes (btdm deinit bug) when that allocation
  // fails after the camera's DMA buffers have fragmented the heap.
  Serial.printf("[SYS] heap before BLE: %u KB\n", ESP.getFreeHeap() / 1024);
  bleVoiceInit();
  Serial.printf("[SYS] heap after BLE: %u KB\n", ESP.getFreeHeap() / 1024);

  // Camera next (before mic/ring — same DMA/PSRAM ordering rule as the
  // main firmware)
  sCameraOk = cameraInit();
  Serial.printf("[CAM] init: %s\n", sCameraOk ? "OK" : "FAILED (continuing without camera)");

  Serial.printf("[MIC] init: %s\n", micInit() ? "OK (PDM @ 2.048 MHz)" : "FAILED");

  sRec = (uint8_t*)ps_malloc(REC_MAX_BYTES);
  Serial.printf("[REC] %d s record buffer in PSRAM: %s\n", REC_MAX_SECONDS, sRec ? "OK" : "ALLOC FAILED");

  printInfo();
  printHelp();
  Serial.println("==========================================================\n");
}

void loop() {
  // ── BLE voice (realtime_ble.py) — active whenever a central drives it ──
  bleVoiceTick();
  if (sBlePlaying || sBleMicOn) { delay(1); return; }   // BLE owns mic/speaker

  // ── serial commands ──
  while (Serial.available()) {
    char cmd = Serial.read();
    switch (cmd) {
      case 'r': recordAndPlay(false);   break;
      case 'R': recordAndDump();        break;
      case 'P': receiveAndPlayPcm();    break;
      case 'S': voiceStreamMode();      break;
      case 'b': beep(1000, 400, 6000);  break;   // safe on USB power
      case 'B': beep(800, 800, 16000);  break;   // louder — battery/solid 5V advised
      case 'c': captureTest();          break;
      case 'p': dumpPhoto();            break;
      case 'v': toggleResolution();     break;
      case 'm': sMeterOn = !sMeterOn; Serial.printf("[MIC] meter %s\n", sMeterOn ? "ON" : "OFF"); break;
      case 'i': printInfo();            break;
      case 'h': printHelp();            break;
      default: break;                            // ignore newlines etc.
    }
  }

  // ── PTT button: hold = record, release = playback ──
  if (digitalRead(PTT_PIN) == HIGH) {
    delay(20);                                   // debounce
    if (digitalRead(PTT_PIN) == HIGH) {
      Serial.println("[BTN] DOWN");
      recordAndPlay(true);                       // returns after release + playback
      Serial.println("[BTN] UP");
    }
  }

  // ── live mic meter out the wire ──
  if (sMic && sMeterOn) {
    size_t got = 0;
    if (i2s_channel_read(sMic, sBuf, sizeof(sBuf), &got, 100) == ESP_OK && got > 0) {
      int n = got / 2, peak = 0; long sum = 0;
      for (int i = 0; i < n; i++) { int a = abs((int)sBuf[i]); if (a > peak) peak = a; sum += a; }
      static uint32_t lastPrint = 0;
      if (millis() - lastPrint >= 250) {                  // 4 lines/sec
        lastPrint = millis();
        int bars = min(50, peak / 300);
        char bar[52]; memset(bar, '#', bars); bar[bars] = 0;
        Serial.printf("MIC peak=%5d avg=%5ld |%-50s|\n", peak, sum / n, bar);
      }
    }
  } else {
    delay(20);
  }
}
