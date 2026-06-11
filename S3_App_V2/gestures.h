#pragma once
#include <stdint.h>

// ════════════════════════════════════════════════════════════════
//  Push-to-talk multi-tap gesture detector.
//
//  Gesture map (same semantics as V1):
//    1 press + hold        → voice question (record while held)
//    quick double-tap      → standalone photo (stored on phone, no question)
//    2 presses, hold 2nd   → photo + voice question bundled (vision)
//    quick triple-tap      → start video recording
//    any tap during video  → stop video recording
//
//  The detector is purely mechanical: it debounces the pin, tracks
//  tap counts and press durations, and emits one event per edge.
//  What each event *does* (capture, send markers, stream mic audio)
//  is decided by the application in the .ino — the detector never
//  touches BLE, camera, or audio.
// ════════════════════════════════════════════════════════════════

enum GestureEvent {
  GESTURE_NONE = 0,
  // Immediate press edge (fires on every debounced press):
  GESTURE_PRESS_DOWN,     // raw press edge — used only for barge-in detection
  // Hold events (fire after QUICK_TAP_MAX_MS of continuous pressing):
  GESTURE_PRESS_VOICE,    // 1st (or 3rd+) press held: begin voice-only recording
  GESTURE_PRESS_VISION,   // 2nd press in window held: capture photo + begin recording
  // Release edges (fire when the button comes up):
  GESTURE_QUICK_PHOTO,    // quick release of 2nd tap: standalone photo
  GESTURE_QUICK_VIDEO,    // quick release of 3rd tap: start video
  GESTURE_QUICK_DISCARD,  // quick release, no recognized pattern: discard
  GESTURE_HOLD_RELEASE,   // long hold ended: send recording (+ photo if vision)
  GESTURE_VIDEO_STOP,     // any release while video is active
};

// Call every loop iteration with the raw pin level and whether video
// recording is currently active. Returns at most one event per call.
GestureEvent gestureTick(bool rawPressed, bool videoActive);

bool gesturePressed();   // debounced button state
int  gestureTapCount();  // taps accumulated in the current window
