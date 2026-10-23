#include <Arduino.h>
#include <string.h>
#include "ring_buffer.h"

static uint8_t* sBuf = nullptr;
static size_t   sSize = 0;
static volatile size_t sHead = 0;   // write position (BLE task)
static volatile size_t sTail = 0;   // read position  (main loop)

bool ringInit(size_t size) {
  // PSRAM first at full size. Without PSRAM the full 256 KB can never come
  // from internal RAM (~240 KB heap total), so step down until something
  // fits — a small ring degrades TTS buffering instead of disabling it.
  for (size_t trySize = size; trySize >= 32 * 1024; trySize /= 2) {
    sBuf = (uint8_t*)ps_malloc(trySize);
    if (!sBuf) sBuf = (uint8_t*)malloc(trySize);
    if (sBuf) {
      sSize = trySize;
      sHead = sTail = 0;
      return true;
    }
  }
  // Total failure: sSize MUST be 0 — a non-zero size with a null buffer
  // makes ringWrite memcpy into NULL on the first TTS byte (hard crash).
  sSize = 0;
  sHead = sTail = 0;
  return false;
}

size_t ringCapacity() { return sSize; }

size_t ringAvailable() {
  size_t h = sHead, t = sTail;
  return (h >= t) ? (h - t) : (sSize - t + h);
}

size_t ringFree() {
  if (!sBuf) return 0;   // no ring — also avoids sSize-1 underflow when sSize==0
  return sSize - 1 - ringAvailable();
}

size_t ringWrite(const uint8_t* data, size_t len) {
  if (!sBuf) return 0;
  size_t space = ringFree();
  if (len > space) len = space;
  if (len == 0) return 0;
  size_t h = sHead;
  size_t firstPart = sSize - h;
  if (firstPart >= len) {
    memcpy(sBuf + h, data, len);
  } else {
    memcpy(sBuf + h, data, firstPart);
    memcpy(sBuf, data + firstPart, len - firstPart);
  }
  sHead = (h + len) % sSize;
  return len;
}

size_t ringRead(uint8_t* dst, size_t maxLen) {
  if (!sBuf) return 0;
  size_t avail = ringAvailable();
  size_t len = (maxLen < avail) ? maxLen : avail;
  if (len == 0) return 0;
  size_t t = sTail;
  size_t firstPart = sSize - t;
  if (firstPart >= len) {
    memcpy(dst, sBuf + t, len);
  } else {
    memcpy(dst, sBuf + t, firstPart);
    memcpy(dst + firstPart, sBuf, len - firstPart);
  }
  sTail = (t + len) % sSize;
  return len;
}

void ringReset() {
  sHead = 0;
  sTail = 0;
}
