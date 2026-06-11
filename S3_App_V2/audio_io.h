#pragma once
#include <stddef.h>
#include <stdint.h>

// ════════════════════════════════════════════════════════════════
//  I2S hardware abstraction.
//  Mic:     built-in PDM mic, RX on I2S_NUM_0, always initialized,
//           paused while the speaker plays (its clock line crosstalks
//           with the speaker BCLK).
//  Speaker: MAX98357A pair, STD TX on I2S_NUM_1, created on demand
//           and torn down after each playback to keep the DIN line
//           quiet (residual I2S idle clocking causes amp hiss).
// ════════════════════════════════════════════════════════════════

// ── Microphone ──
bool   micInit();                 // install PDM RX driver + flush startup garbage
void   micWarmup();               // longer flush at boot (~500 ms stabilization)
void   micPause();                // stop PDM clock (call before speaker playback)
void   micResume();               // restart after speaker teardown
// Read up to maxBytes of 16-bit mono PCM. Returns bytes read (0 on timeout).
size_t micRead(int16_t* buf, size_t maxBytes, uint32_t timeoutMs);
// Drain stale audio sitting in the PDM DMA queue. Between utterances nobody
// reads the mic, so the DMA holds ~100+ ms of old audio that would otherwise
// be prepended to the next recording. Call at recording start.
void   micFlush();
// In-place DC-blocking high-pass (+ optional gain with saturation).
void   micFilter(int16_t* buf, size_t samples);

// ── Speaker ──
bool   speakerInit();             // create+enable STD TX channel, preload silence
void   speakerDeinit();           // drain, disable, delete channel, mute DIN line
bool   speakerReady();
// Blocking write of interleaved 16-bit stereo PCM.
bool   speakerWrite(const int16_t* samples, size_t bytes);
