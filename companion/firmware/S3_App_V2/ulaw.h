#pragma once
#include <stdint.h>

// ════════════════════════════════════════════════════════════════
//  G.711 µ-law codec — shared by ble_link and wifi_link.
//  Realtime voice mode runs audio as µ-law (1 byte/sample) in both
//  directions; which transport carries it makes no difference.
// ════════════════════════════════════════════════════════════════

static inline uint8_t ulawEncode(int16_t pcm) {
  // Widen to 32 bits BEFORE negating: -(-32768) wraps back to -32768 in
  // int16, which skipped the clip and encoded full-scale-negative samples
  // as 0x7F (= silence) — full-amplitude crackle on clipped-loud speech.
  const int32_t CLIP = 32635;
  int32_t v = pcm;
  uint8_t sign = 0;
  if (v < 0) { sign = 0x80; v = -v; }
  if (v > CLIP) v = CLIP;
  v += 0x84;
  uint8_t exp = 7;
  for (int32_t mask = 0x4000; (v & mask) == 0 && exp > 0; mask >>= 1) exp--;
  return ~(sign | (exp << 4) | ((v >> (exp + 3)) & 0x0F));
}

static inline int16_t ulawDecode(uint8_t u) {
  u = ~u;
  int16_t t = (((int16_t)(u & 0x0F)) << 3) + 0x84;
  t <<= (u & 0x70) >> 4;
  return (u & 0x80) ? (0x84 - t) : (t - 0x84);
}
