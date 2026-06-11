#pragma once
#include <stddef.h>
#include <stdint.h>

// ════════════════════════════════════════════════════════════════
//  Streaming TTS playback state machine.
//
//  BLE RX callback (NimBLE task)        Main loop
//  ─────────────────────────────        ─────────────────────────
//  playbackOnStart()     'S'      →     playbackTick() drives:
//  playbackOnAudioData() 'A'...   →       BUFFERING → PLAYING → DRAINING → IDLE
//  playbackOnEndMarker() 'E'      →
//
//  Data flows through the PSRAM ring buffer (ring_buffer.h); this
//  module owns the state machine, stats, and mono→stereo interleave.
// ════════════════════════════════════════════════════════════════

// Called from the BLE task (characteristic callbacks):
void playbackOnStart();                                        // 'S' control marker
void playbackOnAudioData(const uint8_t* data, size_t len, uint8_t seq);  // 'A' packet payload
void playbackOnEndMarker();                                    // 'E' control marker

// Called from the main loop:
void playbackTick();          // advance the state machine (no-op when idle)
bool playbackActive();        // true in BUFFERING/PLAYING/DRAINING
void playbackCancel(const char* reason);  // barge-in: stop now, tear down speaker
void playbackResetOnDisconnect();         // BLE dropped: clear state without touching I2S
