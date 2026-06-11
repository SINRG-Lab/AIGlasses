// ════════════════════════════════════════════════════════════════
//  S3_App_V2 — AI Smart Glasses firmware (XIAO ESP32-S3 Sense)
//
//  Modular rewrite of S3_App_imp. Same BLE protocol as V1 — fully
//  compatible with the existing Android app (BleVoiceService).
//
//  Modules:
//    config.h      — every pin / UUID / tuning constant
//    ring_buffer   — PSRAM ring for TTS streaming
//    audio_io      — I2S mic (PDM RX) + speaker (STD TX)
//    camera_ctl    — OV2640/OV3660 snapshots & video frames
//    ble_link      — GATT server, framing, flow control
//    playback      — TTS streaming state machine
//    gestures      — push-to-talk multi-tap detector
//    status_led    — glanceable state on the user LED
//
//  Gestures:
//    1 press + hold        → voice question (photo from last 5 s auto-attaches)
//    quick double-tap      → standalone photo (stored; ask within 5 s to query it)
//    2 presses, hold 2nd   → photo + voice question bundled (vision AI)
//    quick triple-tap      → start video | any tap stops
//    press during playback → cancel TTS playback (barge-in, new in V2)
//
//  Docs: README.md and docs/ in this folder.
// ════════════════════════════════════════════════════════════════
#include "config.h"
#include "ring_buffer.h"
#include "audio_io.h"
#include "camera_ctl.h"
#include "ble_link.h"
#include "playback.h"
#include "gestures.h"
#include "status_led.h"

// ── Application state ──
static bool sRecording  = false;   // mic is streaming to the phone
static bool sVisionMode = false;   // current utterance carries a photo
static bool sVideoMode  = false;   // video frames are streaming
static int  sVideoFrameCount = 0;

// Standalone photo deferred-send (see gestures.cpp): hold the snapshot until
// the tap window closes so a third tap can upgrade the gesture to video.
static bool sPhotoPending = false;
static unsigned long sPhotoPendingAt = 0;

static int16_t sMicBuf[SAMPLES_PER_CHUNK];

// ────────────────────────────────────────────────────────────────
//  Helpers
// ────────────────────────────────────────────────────────────────
static void flushPendingPhoto() {
  if (!sPhotoPending) return;
  sPhotoPending = false;
  LOGI("[APP] Sending standalone photo");
  bleSendCapturedImage();
}

static void startRecording(bool withVision) {
  // Note: a pending standalone photo is deliberately NOT flushed here — a
  // press inside the tap window may still become a triple-tap (video), which
  // discards the photo. If this press turns into a real question, the photo
  // goes out in stopRecordingAndSend(), just before the audio 'E'.

  sVisionMode = withVision;
  sRecording = true;
  bleResetAudioSeq();
  micFlush();            // drop stale pre-roll the DMA collected while idle
  bleSendAudioStart();   // phone flushes stale audio chunks from quick taps

  if (withVision) {
    LOGI("[APP] Double-tap press → capturing photo");
    // If capture fails, fall back to voice-only instead of silently sending
    // audio with no image — otherwise Android waits for a JPEG that never
    // arrives.
    if (!cameraCaptureSnapshot()) {
      LOGI("[CAM] Capture failed → voice-only for this utterance");
      sVisionMode = false;
    }
  } else {
    LOGI("[APP] Press → voice recording");
  }
}

static void stopRecordingAndSend() {
  // A photo held from a quick double-tap goes out before the question's 'E'
  // marker, so Android sees photo-then-question and attaches it (the "ask
  // within 5 s" flow). Mutually exclusive with sVisionMode by construction.
  flushPendingPhoto();
  if (sVisionMode) {
    LOGI("[APP] Released → sending image + audio END");
    bleSendCapturedImage();
    delay(20);
  } else {
    LOGI("[APP] Released → sending audio END");
  }
  bleSendAudioEnd();
  sRecording = false;
  sVisionMode = false;
}

static void handleGestureEvent(GestureEvent ev) {
  switch (ev) {
    case GESTURE_PRESS_VOICE:
      startRecording(false);
      break;

    case GESTURE_PRESS_VISION:
      startRecording(true);
      break;

    case GESTURE_QUICK_PHOTO:
      // Capture now (startRecording is no longer called on quick taps, so we
      // must capture here). Hold the snapshot until the tap window expires —
      // a third tap within the window upgrades the gesture to video and
      // discards the photo.
      if (cameraCaptureSnapshot() && cameraJpeg()) {
        LOGI("[APP] Quick double-tap → photo pending (window %d ms)", TAP_WINDOW_MS);
        sPhotoPending = true;
        sPhotoPendingAt = millis();
      }
      sRecording = false;
      sVisionMode = false;
      break;

    case GESTURE_QUICK_VIDEO:
      LOGI("[APP] Quick triple-tap → VIDEO START");
      sPhotoPending = false;       // video supersedes the pending photo
      cameraDiscardSnapshot();
      sRecording = false;
      sVisionMode = false;
      sVideoMode = true;
      sVideoFrameCount = 0;
      bleSendVideoStart();
      break;

    case GESTURE_QUICK_DISCARD:
      if (sVisionMode) cameraDiscardSnapshot();   // stray capture
      LOGV("[APP] Quick tap (count=%d) — waiting for more taps", gestureTapCount());
      sRecording = false;
      sVisionMode = false;
      break;

    case GESTURE_HOLD_RELEASE:
      if (sRecording) stopRecordingAndSend();
      break;

    case GESTURE_VIDEO_STOP:
      LOGI("[APP] Video stopped → %d frames", sVideoFrameCount);
      bleSendVideoEnd((uint8_t)min(sVideoFrameCount, 255));
      sVideoMode = false;
      sVideoFrameCount = 0;
      break;

    case GESTURE_NONE:
    default:
      break;
  }
}

static void updateLed() {
  if (!bleConnected())        ledSetPattern(LED_SLOW_BLINK);
  else if (sVideoMode)        ledSetPattern(LED_DOUBLE_BLINK);
  else if (playbackActive())  ledSetPattern(LED_FAST_BLINK);
  else if (sRecording)        ledSetPattern(LED_SOLID);
  else                        ledSetPattern(LED_OFF);
  ledTick();
}

// ────────────────────────────────────────────────────────────────
//  Setup
// ────────────────────────────────────────────────────────────────
void setup() {
  Serial.begin(115200);
  delay(1000);

  LOGI("\n\n============================================================");
  LOGI("  ESP32-S3 AI GLASSES — S3_App_V2 (modular)");
  LOGI("  XIAO ESP32-S3 Sense | BLE + dual I2S + camera");
  LOGI("============================================================");
  LOGI("SPK: BCLK=GPIO%d LRC=GPIO%d DIN=GPIO%d", I2S_BCLK, I2S_WS, AMP_DIN);
  LOGI("MIC: built-in PDM (CLK=GPIO%d DATA=GPIO%d)", PDM_CLK, PDM_DATA);
  LOGI("CAM: XCLK=GPIO%d | PTT: GPIO%d | LED: GPIO%d", CAM_XCLK, PTT_PIN, STATUS_LED_PIN);
  LOGI("[SYS] PSRAM: %u KB, free heap: %u KB",
       ESP.getPsramSize() / 1024, ESP.getFreeHeap() / 1024);

  ledInit();

  // Camera FIRST — before any other PSRAM/DMA allocations. The camera driver
  // claims DMA channels and PSRAM frame buffers; if the mic or ring buffer
  // initialize first, DMA conflicts or PSRAM fragmentation can make
  // esp_camera_fb_get() return NULL.
  LOGI("[CAM] Initializing camera...");
  if (!cameraInit()) {
    LOGI("[CAM] FAILED — continuing in voice-only mode");
  }
  LOGI("[SYS] Free PSRAM after camera: %u KB", ESP.getFreePsram() / 1024);

  if (!ringInit(RING_SIZE)) {
    LOGI("[SYS] FATAL: could not allocate %u KB ring buffer", RING_SIZE / 1024);
  } else {
    LOGI("[SYS] Ring buffer: %u KB", RING_SIZE / 1024);
  }

  // Push-to-talk button (active HIGH: pressed = HIGH)
  pinMode(PTT_PIN, INPUT_PULLDOWN);
  delay(100);
  if (digitalRead(PTT_PIN) == HIGH) {
    LOGI("[PTT] WARNING: button reads HIGH at boot — check wiring. Waiting for release...");
    while (digitalRead(PTT_PIN) == HIGH) delay(100);
    LOGI("[PTT] Released, continuing");
  }

  if (!micInit()) {
    LOGI("[MIC] FAILED — recording will not work");
  } else {
    micWarmup();   // flush DMA garbage, let the PDM mic stabilize (~500 ms)
  }

  bleInit();

  LOGI("");
  LOGI("============================================================");
  LOGI("  READY");
  LOGI("  1 press + hold   → voice question");
  LOGI("  quick double-tap → photo (stored; ask within 5 s)");
  LOGI("  2 press + hold   → photo + voice (vision)");
  LOGI("  quick triple-tap → video start/stop");
  LOGI("  press during TTS → cancel playback");
  LOGI("============================================================\n");
}

// ────────────────────────────────────────────────────────────────
//  Loop
// ────────────────────────────────────────────────────────────────
void loop() {
  updateLed();

  // ── TTS playback in progress ──
  if (playbackActive()) {
    playbackTick();
    // Barge-in: a button press while the glasses are speaking cancels playback
    // AND flows into a new recording — the press must not be swallowed, or the
    // user holds a dead button and their whole question goes unheard.
    GestureEvent ev = gestureTick(digitalRead(PTT_PIN) == HIGH, false);
    if (ev == GESTURE_PRESS_DOWN) {
      LOGI("[APP] Barge-in: cancelling playback");
      playbackCancel("button press");
      bleNotifyPlaybackCancelled();   // 'X': app stops streaming the rest of the TTS
      playbackTick();                 // service the abort NOW: speaker down, mic back
      // Recording starts on the next GESTURE_PRESS_VOICE/VISION (350 ms into the hold).
      // This ensures the mic path is fully up before we start streaming.
    }
    return;   // don't read the mic while the speaker owns the audio path
  }

  if (!bleConnected()) {
    delay(50);
    return;
  }

  // ── Deferred standalone photo: tap window closed with no third tap ──
  // Only while idle: if a recording or video started meanwhile, the photo is
  // handled there (flushed before 'E', or discarded by video). Sending it
  // mid-recording would stall mic reads long enough to overflow the PDM DMA.
  if (sPhotoPending && !sRecording && !sVideoMode &&
      (millis() - sPhotoPendingAt) >= TAP_WINDOW_MS) {
    flushPendingPhoto();
  }

  // ── Video: capture+stream one frame per iteration while recording ──
  if (sVideoMode) {
    bleSendVideoFrame((uint8_t)(sVideoFrameCount & 0xFF));
    sVideoFrameCount++;
  }

  // ── Gesture handling ──
  handleGestureEvent(gestureTick(digitalRead(PTT_PIN) == HIGH, sVideoMode));

  // ── Mic streaming while the button is held ──
  if (sRecording && gesturePressed()) {
    size_t bytesRead = micRead(sMicBuf, sizeof(sMicBuf), 100);
    if (bytesRead > 0) {
      micFilter(sMicBuf, bytesRead / 2);   // DC-block high-pass
      bleSendMicChunk((uint8_t*)sMicBuf, bytesRead);
    }
    // micRead blocks on I2S DMA, so this path needs no extra delay
    return;
  }

  delay(5);
}
