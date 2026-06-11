#pragma once

// ════════════════════════════════════════════════════════════════
//  Status LED patterns on the XIAO ESP32-S3 user LED (GPIO21,
//  active LOW). Glanceable device state without a serial monitor.
// ════════════════════════════════════════════════════════════════

enum LedPattern {
  LED_OFF,            // connected, idle
  LED_SLOW_BLINK,     // waiting for BLE connection (1 s period)
  LED_SOLID,          // recording (mic streaming)
  LED_FAST_BLINK,     // TTS playback in progress (200 ms period)
  LED_DOUBLE_BLINK,   // video recording (blink-blink-pause)
};

void ledInit();
void ledSetPattern(LedPattern p);
void ledTick();        // call every loop iteration; non-blocking
