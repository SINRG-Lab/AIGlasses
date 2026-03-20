#ifndef GEMINI_API_H
#define GEMINI_API_H

#include <Arduino.h>

// Send audio file to Gemini for transcription.
// Returns the transcription text (caller must not free — uses String).
// Returns empty string on failure.
String audioToText(const char* audioPath, const char* prompt);

// Send text to Gemini TTS and save the resulting WAV to outputPath.
// Returns true on success.
bool textToAudio(const char* text, const char* outputPath);

#endif
