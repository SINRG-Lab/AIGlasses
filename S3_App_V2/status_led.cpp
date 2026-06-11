#include <Arduino.h>
#include "config.h"
#include "status_led.h"

static LedPattern sPattern = LED_OFF;
static bool sLit = false;

static void writeLed(bool on) {
  if (on == sLit) return;
  sLit = on;
#if STATUS_LED_ACTIVE_LOW
  digitalWrite(STATUS_LED_PIN, on ? LOW : HIGH);
#else
  digitalWrite(STATUS_LED_PIN, on ? HIGH : LOW);
#endif
}

void ledInit() {
  pinMode(STATUS_LED_PIN, OUTPUT);
  sLit = true;        // force the first writeLed() through
  writeLed(false);
}

void ledSetPattern(LedPattern p) { sPattern = p; }

void ledTick() {
  unsigned long t = millis();
  switch (sPattern) {
    case LED_OFF:        writeLed(false); break;
    case LED_SOLID:      writeLed(true);  break;
    case LED_SLOW_BLINK: writeLed((t % 1000) < 100); break;     // short flash each second
    case LED_FAST_BLINK: writeLed((t % 200) < 100); break;
    case LED_DOUBLE_BLINK: {
      unsigned long phase = t % 1000;                            // blink-blink-pause
      writeLed(phase < 80 || (phase >= 160 && phase < 240));
      break;
    }
  }
}
