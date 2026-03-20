#ifndef AUDIO_UTILS_H
#define AUDIO_UTILS_H

#include <Arduino.h>

// Initialize the FFAT filesystem
bool audioFsInit();

// Load an audio file from FFAT into a PSRAM buffer.
// Sets *outBuf and *outLen. Caller must free(*outBuf).
// Returns true on success.
bool loadAudioFile(const char* path, uint8_t** outBuf, size_t* outLen);

// Detect MIME type from file content ("audio/wav" or "audio/mpeg")
const char* detectMimeType(const uint8_t* buf, size_t len);

// Base64 encode using mbedtls. Returns PSRAM-allocated null-terminated string.
// Caller must free() the result.
char* base64Encode(const uint8_t* src, size_t srcLen, size_t* outLen);

// Base64 decode using mbedtls. Returns PSRAM-allocated buffer.
// Caller must free() the result.
uint8_t* base64Decode(const char* src, size_t srcLen, size_t* outLen);

// Build a WAV file (header + PCM data) for 24kHz 16-bit mono.
// Returns PSRAM-allocated buffer. Caller must free().
uint8_t* buildWavFromPcm(const uint8_t* pcmData, size_t pcmLen, size_t* outLen);

// Save raw data to a file on FFAT
bool saveWavFile(const char* path, const uint8_t* data, size_t len);

#endif
