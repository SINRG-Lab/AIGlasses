#include <Arduino.h>
#include <FFat.h>
#include <FS.h>

#include "config.h"
#include "modem_setup.h"
#include "audio_utils.h"
#include "gemini_api.h"

void setup()
{
    Serial.begin(115200);
    delay(3000);
    Serial.println("\n==================================================");
    Serial.println("Walter - Gemini Audio-to-Audio Pipeline (Arduino)");
    Serial.println("==================================================\n");

    // Mount filesystem
    if (!audioFsInit()) {
        Serial.println("FATAL: filesystem init failed");
        return;
    }

    // Initialize modem and connect to LTE-M
    if (!modemInit()) {
        Serial.println("FATAL: modem init failed");
        return;
    }

    unsigned long pipelineStart = millis();

    // Audio -> Text
    String transcription = audioToText(AUDIO_FILE, AUDIO_PROMPT);
    if (transcription.isEmpty()) {
        Serial.println("FATAL: audio-to-text failed");
        return;
    }

    Serial.printf("\nTranscription: %s\n\n", transcription.c_str());

    // Text -> Audio (TTS)
    String ttsPrompt = String("Say the following in an extremely fast manner:\n") + transcription;
    if (!textToAudio(ttsPrompt.c_str(), "/tts_output.wav")) {
        Serial.println("FATAL: text-to-audio failed");
        return;
    }

    unsigned long pipelineMs = millis() - pipelineStart;
    Serial.println("\n==================================================");
    Serial.println("Pipeline complete!");
    Serial.printf("Total pipeline time: %.2f s\n", pipelineMs / 1000.0f);
    Serial.println("==================================================");

    // Dump wav data to serial
    // TODO Remove once done debugging.
    File f = FFat.open("/tts_output.wav", "r");
    if (f) {
        size_t len = f.size();
        uint8_t* buf = (uint8_t*)ps_malloc(len);
        f.read(buf, len);
        f.close();
        size_t b64Len = 0;
        char* b64 = base64Encode(buf, len, &b64Len);
        free(buf);
        Serial.println("---BEGIN WAV BASE64---");
        Serial.println(b64);
        Serial.println("---END WAV BASE64---");
        free(b64);
    }

}

void loop()
{
    delay(10000);
}
