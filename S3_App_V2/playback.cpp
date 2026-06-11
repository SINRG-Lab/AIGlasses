#include <Arduino.h>
#include "config.h"
#include "ring_buffer.h"
#include "audio_io.h"
#include "playback.h"

enum StreamState { STREAM_IDLE, STREAM_BUFFERING, STREAM_PLAYING, STREAM_DRAINING };

static volatile StreamState sState = STREAM_IDLE;
static volatile bool sEndMarker = false;
static volatile bool sAbort = false;       // set by cancel/disconnect, serviced in tick()

// RX statistics (written by BLE task, read/reset by main loop between streams)
static volatile unsigned sRxChunks = 0;
static volatile unsigned sRxBytes = 0;
static volatile unsigned sRxDropped = 0;
static volatile unsigned sRxSeqGaps = 0;
static unsigned sUnderruns = 0;

// Sequence tracking — reset on every 'S' so a stale value from the previous
// stream can't log a phantom gap on the first chunk (V1 bug).
static volatile bool    sSeqValid = false;
static volatile uint8_t sExpectedSeq = 0;

// Stall watchdog: when the last audio packet (or 'S') arrived. If the app dies
// mid-stream or a marker is lost, this lets the state machine recover.
static volatile unsigned long sLastRxMs = 0;
// Audio arriving with no open stream — the signature of a lost 'S' marker.
static volatile unsigned sIdleDropBytes = 0;

// Mono→stereo working buffers (main loop only)
#define CHUNK_MONO_SAMPLES 512
static int16_t sStereo[CHUNK_MONO_SAMPLES * 2];
static uint8_t sMono[CHUNK_MONO_SAMPLES * 2];

bool playbackActive() { return sState != STREAM_IDLE; }

// ────────────────────────────────────────────────────────────────
//  BLE-task entry points
// ────────────────────────────────────────────────────────────────
void playbackOnStart() {
  LOGI("[STREAM] Start marker — buffering audio");
  ringReset();
  sEndMarker = false;
  sSeqValid = false;
  sRxChunks = sRxBytes = sRxDropped = sRxSeqGaps = 0;
  sIdleDropBytes = 0;
  sLastRxMs = millis();
  sState = STREAM_BUFFERING;
}

void playbackOnAudioData(const uint8_t* data, size_t len, uint8_t seq) {
  if (len == 0) return;
  // Ignore audio with no active stream — stale TTS in flight after a barge-in
  // cancel, or (the bad case) the app's 'S' marker was lost. Log it loudly:
  // "transcription fine, playback silent" is exactly what a lost 'S' looks like.
  if (sState == STREAM_IDLE) {
    bool first = (sIdleDropBytes == 0);
    sIdleDropBytes += len;
    if (first) {
      LOGI("[STREAM] Audio with no open stream — lost 'S' marker? (dropping)");
    }
    return;
  }
  sLastRxMs = millis();

  if (sSeqValid && seq != sExpectedSeq) {
    uint8_t gap = (uint8_t)(seq - sExpectedSeq);
    sRxSeqGaps += gap;
    LOGV("[STREAM] SEQ gap: expected %u got %u (lost ~%u pkts)", sExpectedSeq, seq, gap);
  }
  sExpectedSeq = seq + 1;
  sSeqValid = true;

  size_t written = ringWrite(data, len);
  sRxChunks++;
  sRxBytes += written;
  if (written < len) {
    sRxDropped += (len - written);
  }

  if ((sRxChunks % 50) == 0) {
    LOGV("[STREAM] rx %u chunks, %u bytes, ring %u/%u",
         sRxChunks, sRxBytes, (unsigned)ringAvailable(), (unsigned)ringCapacity());
  }
}

void playbackOnEndMarker() {
  LOGI("[STREAM] End marker — %u bytes received (%u chunks)", sRxBytes, sRxChunks);
  sEndMarker = true;
}

// ────────────────────────────────────────────────────────────────
//  Main-loop side
// ────────────────────────────────────────────────────────────────
static void resetState() {
  sState = STREAM_IDLE;
  sEndMarker = false;
  sAbort = false;
  ringReset();
  sUnderruns = 0;
}

static void finishPlayback() {
  LOGI("[STREAM] Playback complete (rx %u bytes, %u chunks, ringDrop %u, seqGaps %u, underruns %u)",
       sRxBytes, sRxChunks, sRxDropped, sRxSeqGaps, sUnderruns);

  // Flush trailing silence so the amp doesn't pop on teardown
  memset(sStereo, 0, sizeof(sStereo));
  for (int i = 0; i < 8; i++) {
    speakerWrite(sStereo, sizeof(sStereo));
  }
  delay(200);   // let the DMA play out
  speakerDeinit();
  resetState();
}

void playbackCancel(const char* reason) {
  if (sState == STREAM_IDLE) return;
  LOGI("[STREAM] Cancelled (%s)", reason ? reason : "user");
  sAbort = true;   // serviced on the next tick, in main-loop context
}

void playbackResetOnDisconnect() {
  if (sState == STREAM_IDLE) return;
  sAbort = true;
}

void playbackTick() {
  // ── Abort requested (barge-in or disconnect) ──
  if (sAbort) {
    if (speakerReady()) speakerDeinit();   // also resumes the mic
    resetState();
    return;
  }

  // ── BUFFERING: wait for enough data before spinning up the speaker ──
  if (sState == STREAM_BUFFERING) {
    size_t avail = ringAvailable();
    // Stall: stream opened but the data/end-marker never came (app crashed or
    // a marker was lost). Play what we have, or give up if there's nothing.
    bool stalled = (millis() - sLastRxMs) > STREAM_STALL_TIMEOUT_MS;
    if (stalled && avail == 0) {
      LOGI("[STREAM] Stalled in BUFFERING with no data — resetting");
      resetState();
      return;
    }
    if (avail >= STREAM_START_THRESHOLD || ((sEndMarker || stalled) && avail > 0)) {
      LOGI("[STREAM] Starting playback (%u bytes buffered)", (unsigned)avail);
      if (!speakerInit()) {
        LOGI("[STREAM] Speaker init failed — aborting playback");
        micResume();   // speakerInit paused the mic before failing
        resetState();
        return;
      }
      delay(30);
      sState = STREAM_PLAYING;
    }
    return;
  }

  if (sState != STREAM_PLAYING && sState != STREAM_DRAINING) return;

  // ── PLAYING / DRAINING: feed ring buffer data to I2S ──
  if (sState == STREAM_PLAYING && sEndMarker) {
    sState = STREAM_DRAINING;
    LOGV("[STREAM] Draining %u remaining bytes", (unsigned)ringAvailable());
  }

  size_t avail = ringAvailable();
  size_t wantBytes = CHUNK_MONO_SAMPLES * 2;

  if (avail >= wantBytes) {
    ringRead(sMono, wantBytes);
  } else if (sState == STREAM_DRAINING) {
    size_t got = avail & ~(size_t)1;   // whole 16-bit samples only
    if (got == 0) { finishPlayback(); return; }
    ringRead(sMono, got);
    wantBytes = got;
  } else {
    // PLAYING but the ring is low → wait for more data rather than splicing
    // in silence. The DMA TX buffer has ~186 ms of headroom and keeps playing
    // while we wait, which avoids the rhythmic "bubbling" that silence
    // insertion produces on repeated brief underruns.
    unsigned long waitStart = millis();
    while (ringAvailable() < wantBytes && (millis() - waitStart) < STREAM_REFILL_WAIT_MS) {
      if (sAbort || sEndMarker) break;
      delay(2);
    }
    if (ringAvailable() >= wantBytes) {
      ringRead(sMono, wantBytes);
    } else {
      // Stall: ring is dry and nothing has arrived for a while with no 'E' —
      // the end marker was probably lost. Finish cleanly (plays out, tears
      // down the speaker, resumes the mic) instead of waiting forever with
      // the button dead.
      if (!sEndMarker && (millis() - sLastRxMs) > STREAM_STALL_TIMEOUT_MS) {
        LOGI("[STREAM] Stalled in PLAYING (%lu ms without data, no end marker) — finishing",
             (unsigned long)(millis() - sLastRxMs));
        finishPlayback();
        return;
      }
      if ((++sUnderruns % 20) == 1) {
        LOGV("[STREAM] Underrun #%u (ring=%u) — letting DMA drain",
             sUnderruns, (unsigned)ringAvailable());
      }
      return;   // skip this tick; DMA keeps playing its existing buffer
    }
  }

  // Interleave mono → stereo (L=R). MAX98357A expects standard stereo I2S;
  // mono-slot mode produces a non-standard waveform some amps decode as clicks.
  const int16_t* monoSrc = (const int16_t*)sMono;
  size_t monoSamples = wantBytes / 2;
  for (size_t i = 0; i < monoSamples; i++) {
    int16_t s = monoSrc[i] >> SPK_VOL_SHIFT;
    sStereo[2 * i]     = s;
    sStereo[2 * i + 1] = s;
  }
  speakerWrite(sStereo, monoSamples * 2 * sizeof(int16_t));

  if (sState == STREAM_DRAINING && ringAvailable() == 0) {
    finishPlayback();
  }
}
