#include <Arduino.h>
#include "config.h"
#include "gestures.h"

static bool sStable = false;            // debounced state
static bool sLastRaw = false;           // last raw sample
static unsigned long sLastChange = 0;   // when the raw level last changed
static unsigned long sPressStart = 0;
static unsigned long sLastQuickTap = 0;
static int  sTapCount = 0;
static bool sHoldFired = false;         // true once PRESS_VOICE/VISION has been fired for this press

// gesturePressed() is only true after the hold is committed — mic streaming
// and sRecording checks should not run during quick-tap sequences.
bool gesturePressed()  { return sStable && sHoldFired; }
int  gestureTapCount() { return sTapCount; }

GestureEvent gestureTick(bool rawPressed, bool videoActive) {
  unsigned long now = millis();

  // Debounce: the raw level must hold steady for DEBOUNCE_MS before we
  // accept it as a real edge.
  if (rawPressed != sLastRaw) {
    sLastRaw = rawPressed;
    sLastChange = now;
    return GESTURE_NONE;
  }
  if ((now - sLastChange) < DEBOUNCE_MS) return GESTURE_NONE;

  // ── Held steady: check if we've passed the hold threshold ──
  // Fire PRESS_VOICE/VISION exactly once per hold, after QUICK_TAP_MAX_MS
  // has elapsed without a release. This prevents every quick tap in a
  // multi-tap sequence from sending a spurious 'S' start marker to the app
  // (which clears its buffer and discards the first ~200 ms of the real
  // utterance each time it fires).
  if (rawPressed == sStable) {
    if (sStable && !videoActive && !sHoldFired &&
        (now - sPressStart) >= QUICK_TAP_MAX_MS) {
      sHoldFired = true;
      return (sTapCount == 2) ? GESTURE_PRESS_VISION : GESTURE_PRESS_VOICE;
    }
    return GESTURE_NONE;
  }
  sStable = rawPressed;

  // ── Press edge ──
  if (sStable) {
    sPressStart = now;
    sHoldFired = false;
    if (videoActive) return GESTURE_NONE;   // release will stop the video

    bool withinWindow = (sTapCount > 0) && (now - sLastQuickTap < TAP_WINDOW_MS);
    sTapCount = withinWindow ? sTapCount + 1 : 1;

    // Fire the raw press edge immediately. The main loop ignores this, but the
    // barge-in path uses it to cancel TTS without waiting for hold confirmation —
    // the user expects instant TTS cancellation on any press during playback.
    // PRESS_VOICE/VISION fires later (in the "held steady" block) to start recording.
    return GESTURE_PRESS_DOWN;
  }

  // ── Release edge ──
  if (videoActive) {
    sTapCount = 0;
    sHoldFired = false;
    return GESTURE_VIDEO_STOP;
  }

  if (sHoldFired) {
    // Hold was committed before this release — normal voice/vision end.
    sTapCount = 0;
    sHoldFired = false;
    return GESTURE_HOLD_RELEASE;
  }

  // Quick release: hold never committed, classify by tap count.
  sLastQuickTap = now;
  if (sTapCount == 2) return GESTURE_QUICK_PHOTO;
  if (sTapCount == 3) { sTapCount = 0; return GESTURE_QUICK_VIDEO; }
  return GESTURE_QUICK_DISCARD;
}
