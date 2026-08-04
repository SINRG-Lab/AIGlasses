#pragma once
// ════════════════════════════════════════════════════════════════
//  S3_App_V2 — central configuration
//  Every pin, UUID, and tuning constant lives here. No other file
//  should hard-code a number that you might want to change.
//  Target: Seeed XIAO ESP32-S3 Sense
// ════════════════════════════════════════════════════════════════

// ────────────────────────────────────────────────────────────────
//  BLE GATT Service UUIDs (must match Android BleVoiceService)
// ────────────────────────────────────────────────────────────────
#define BLE_DEVICE_NAME     "AIGlasses-ESP32S3"
#define SERVICE_UUID        "0000aa00-1234-5678-abcd-0e5032c6b1e0"
#define CHAR_AUDIO_TX_UUID  "0000aa01-1234-5678-abcd-0e5032c6b1e0"  // ESP32->Android (NOTIFY)
#define CHAR_AUDIO_RX_UUID  "0000aa02-1234-5678-abcd-0e5032c6b1e0"  // Android->ESP32 (WRITE)
#define CHAR_CONTROL_UUID   "0000aa03-1234-5678-abcd-0e5032c6b1e0"  // Bidirectional (WRITE+NOTIFY)
#define CHAR_IMAGE_TX_UUID  "0000aa04-1234-5678-abcd-0e5032c6b1e0"  // ESP32->Android: camera JPEG (NOTIFY)

// ────────────────────────────────────────────────────────────────
//  BLE packet framing
// ────────────────────────────────────────────────────────────────
#define BLE_MTU             512
#define BLE_HEADER_SIZE     2       // TAG(1) + SEQ(1)
// Max payload per notification = MTU - 3 (ATT overhead) - header, rounded
// DOWN TO EVEN so a lost PCM16 fragment can never flip byte parity and turn
// every subsequent sample into full-scale noise (the Session-2 "clipping").
#define BLE_MAX_PAYLOAD     (((BLE_MTU - 3 - BLE_HEADER_SIZE) / 2) * 2)
// Pacing between fragments: one fragment per connection event
#define BLE_FRAG_DELAY_MS       5   // mic audio fragments
#define BLE_IMG_FRAG_DELAY_MS  15   // photo fragments (large one-shot bursts)
// notifyWithRetry: max retries while the NimBLE TX buffer drains
#define BLE_NOTIFY_MAX_TRIES   50   // x 5 ms = ~250 ms worst case
// LL tuning after connect is STAGGERED from bleTick() (three simultaneous LL
// procedures during iOS's own MTU/discovery is a known early-drop trigger):
#define BLE_TUNE_CONN_PARAMS_MS  300   // +300 ms: conn params 7.5-15 ms / timeout 5 s
#define BLE_TUNE_PHY_MS          600   // +600 ms: request 2M PHY (1M+2M mask)
#define BLE_TUNE_DLE_MS          900   // +900 ms: data-length extension 251/2120

// ────────────────────────────────────────────────────────────────
//  WiFi link (SoftAP + TCP) — optional BULK data plane next to the
//  always-on BLE link. OFF by default; the app enables it with 'F'
//  on CONTROL and the glasses answer with 'N' + these credentials.
//  See wifi_link.h and docs/WIFI_LINK.md.
// ────────────────────────────────────────────────────────────────
#define WIFI_AP_SSID_PREFIX  "AIGlasses-"   // + last two MAC bytes, e.g. AIGlasses-3F2A
#define WIFI_AP_PASS         "glasses-link" // WPA2 (8+ chars)
#define WIFI_AP_CHANNEL      1   // RF scan 2026-07-20: ch6 had 5 competing APs
                                 // in the lab, ch1 was empty. Rescan with
                                 // `system_profiler SPAirPortDataType` if the
                                 // link gets bursty again — congestion moves.
#define WIFI_TCP_PORT        5005
#define WIFI_PROTO_VERSION   1              // sent in the 'R' hello on connect
// Max inner packet per frame (header + payload). TCP segments it; no
// radio-side pacing needed.
#define WIFI_FRAME_MAX       4096
#define WIFI_MAX_PAYLOAD     (WIFI_FRAME_MAX - 2)   // minus TAG+SEQ inner header
#define WIFI_WRITE_TIMEOUT_MS  2000   // stalled socket → drop client
#define WIFI_READ_TIMEOUT_MS   3000   // mid-frame silence → protocol desync
// Liveness watchdog: the app pings ('P' on CONTROL) every 2 s per transport.
// No ping on the WiFi socket for this long → the socket is dead, close it.
// (BLE relies on the 5 s supervision timeout instead.)
#define WIFI_PING_TIMEOUT_MS   10000
#define WIFI_AP_AT_BOOT      0        // 1 = SoftAP from boot (dev convenience,
                                      // ~100 mA extra draw — keep 0 for demos)

// ────────────────────────────────────────────────────────────────
//  Transport routing + link statistics (link.cpp)
//  BLE is ALWAYS the control + realtime-voice plane. Photos route
//  per image: WiFi only when the socket is healthy AND measured
//  faster than BLE — when in doubt, BLE.
// ────────────────────────────────────────────────────────────────
#define LINK_STATS_PERIOD_MS   5000  // [LINK-STATS] serial line + 'T' packet cadence
#define LINK_PING_STALE_MS     6000  // no app ping on WiFi for this long → route BLE
                                     // (3 missed 2 s pings, matches the app's rule)
#define LINK_BLE_SEED_KBS      30.0f // assumed BLE image throughput until measured
                                     // (bench: ~34 KB/s ceiling on IMAGE_TX)
#define LINK_WIFI_REMEASURE_MS 60000 // a WiFi measurement older than this is stale —
                                     // probe the route again with one photo
#define LINK_WIFI_PROBE_MIN_RSSI (-80) // don't probe an unmeasured WiFi route when the
                                       // client's RSSI says edge-of-range — pings can
                                       // survive at -85 dBm while throughput is far
                                       // below BLE ("when in doubt, BLE")

// ────────────────────────────────────────────────────────────────
//  I2S pins — XIAO ESP32-S3 Sense
//    Speaker (MAX98357A x2, hardware-panned L/R via SD_MODE):
//      BCLK = GPIO9, LRC/WS = GPIO5, DIN = GPIO8
//    Microphone: built-in PDM mic (MSM261D3526H1CPM)
//      PDM_CLK = GPIO42, PDM_DATA = GPIO41
// ────────────────────────────────────────────────────────────────
#define I2S_BCLK    9
#define I2S_WS      5
#define AMP_DIN     8
#define PDM_CLK     42
#define PDM_DATA    41

// I2S controller assignment (S3 has two independent controllers)
#define MIC_I2S_PORT  I2S_NUM_0     // PDM RX
#define SPK_I2S_PORT  I2S_NUM_1     // STD TX

// ────────────────────────────────────────────────────────────────
//  Camera pins — XIAO ESP32-S3 Sense (OV2640/OV3660)
// ────────────────────────────────────────────────────────────────
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

// ────────────────────────────────────────────────────────────────
//  Inputs / outputs
// ────────────────────────────────────────────────────────────────
#define PTT_PIN        6    // push-to-talk, active HIGH (external pulldown via INPUT_PULLDOWN)
#define STATUS_LED_PIN 21   // XIAO S3 user LED, active LOW
#define STATUS_LED_ACTIVE_LOW 1

// ────────────────────────────────────────────────────────────────
//  Audio settings
// ────────────────────────────────────────────────────────────────
#define MIC_SAMPLE_RATE   16000
#define SPK_SAMPLE_RATE   24000   // OpenAI TTS native PCM rate — no resampling
#define SAMPLES_PER_CHUNK   512   // mic read granularity (1024 bytes @ 16-bit)

// Speaker volume attenuation (right-shift): 0 = full, 1 = -6dB, 2 = -12dB...
// MAX98357A has fixed 9dB gain; full-scale digital drives it into clipping.
#define SPK_VOL_SHIFT  0

// Mic DC-blocking high-pass: y[n] = x[n] - x[n-1] + R*y[n-1]
// R=0.97 → corner ≈ 80 Hz @ 16 kHz — removes handling/wind rumble.
#define MIC_DC_FILTER_R  0.97f
// NO software gain — raw PDM output is already 16-bit; leveling is done by
// the Android normalizer. Gain > 1 clipped loud speech → Whisper hallucinations.
#define MIC_GAIN  1

// ────────────────────────────────────────────────────────────────
//  Streaming playback (TTS from Android)
// ────────────────────────────────────────────────────────────────
// PSRAM ring buffer between BLE RX callback and the I2S feed loop.
// Sized so a long TTS response can pre-fill: BLE receive (~38 KB/s) is
// slower than playback (~48 KB/s), so the buffer absorbs the deficit.
#define RING_SIZE   (256 * 1024)
// Pre-buffer before playback starts. Larger = more latency, fewer cut-outs.
#define STREAM_START_THRESHOLD  70000   // bytes ≈ 1.4 s @ 48 KB/s
// Max time to wait for the ring to refill before declaring an underrun
#define STREAM_REFILL_WAIT_MS   300
// Stall watchdog: if a stream is open ('S' seen) but no audio has arrived for
// this long and no 'E' was received, assume the marker/stream was lost and
// finish/reset instead of waiting forever. Without this, a single lost 'E'
// soft-locks the device in PLAYING (button dead, mic never resumed).
#define STREAM_STALL_TIMEOUT_MS 4000

// ────────────────────────────────────────────────────────────────
//  Gesture timing (push-to-talk multi-tap)
// ────────────────────────────────────────────────────────────────
#define QUICK_TAP_MAX_MS   350   // press shorter than this = "quick tap"
#define TAP_WINDOW_MS      600   // inter-tap window for multi-tap accumulation
#define DEBOUNCE_MS         15   // button state must be stable this long

// ────────────────────────────────────────────────────────────────
//  Camera capture tuning
// ────────────────────────────────────────────────────────────────
#define CAM_WARMUP_FRAMES_BOOT     5   // discarded at init (AEC/AWB convergence)
#define CAM_WARMUP_FRAMES_CAPTURE  4   // discarded before each snapshot
#define CAM_CAPTURE_RETRIES        5

// Route-matched photo profiles (link.cpp switches them when the preferred
// image route changes). BLE budget is ~34 KB/s, so BLE photos are deliberately
// small; the WiFi socket moves 10x+ that, so capture accordingly. jpeg
// quality: LOWER number = better image, bigger file.
#define CAM_PHOTO_FRAMESIZE_BLE    FRAMESIZE_QVGA    // 320x240, ~8 KB
#define CAM_PHOTO_QUALITY_BLE      12
#define CAM_PHOTO_FRAMESIZE_WIFI   FRAMESIZE_SVGA    // 800x600, ~40-70 KB
#define CAM_PHOTO_QUALITY_WIFI     10
// Frame buffers are allocated for this size at init — must be ≥ the largest
// profile above or the sensor can't be switched up to it later.
#define CAM_INIT_FRAMESIZE         FRAMESIZE_SVGA

// ────────────────────────────────────────────────────────────────
//  Logging — set LOG_VERBOSE to 0 to silence per-chunk chatter
//  (keeps state transitions and errors)
// ────────────────────────────────────────────────────────────────
#define LOG_VERBOSE  1
#define LOGI(fmt, ...)  Serial.printf(fmt "\n", ##__VA_ARGS__)
#if LOG_VERBOSE
  #define LOGV(fmt, ...)  Serial.printf(fmt "\n", ##__VA_ARGS__)
#else
  #define LOGV(fmt, ...)  do {} while (0)
#endif
