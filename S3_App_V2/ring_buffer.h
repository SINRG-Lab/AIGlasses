#pragma once
#include <stddef.h>
#include <stdint.h>

// ════════════════════════════════════════════════════════════════
//  PSRAM-backed ring buffer for streaming TTS playback.
//  Single producer (NimBLE task: BLE write callback) /
//  single consumer (main loop: I2S feed) — lock-free with
//  volatile indices, safe on ESP32 (aligned 32-bit loads/stores
//  are atomic).
// ════════════════════════════════════════════════════════════════

// Allocate the buffer (PSRAM preferred, internal RAM fallback).
// Returns false if no memory could be allocated.
bool   ringInit(size_t size);

size_t ringAvailable();                          // bytes ready to read
size_t ringFree();                               // bytes of space left
size_t ringWrite(const uint8_t* data, size_t len);  // returns bytes accepted
size_t ringRead(uint8_t* dst, size_t maxLen);       // returns bytes copied
void   ringReset();                              // drop all buffered data
size_t ringCapacity();
