#include <Arduino.h>

#include "config.h"
#include "modem_setup.h"
#include "audio_utils.h"
#include "deepgram_api.h"

// ================================================================
//  AUDIO TEST  — single file, full latency breakdown, serial dump
// ================================================================

void runAudioTest()
{
    Serial.println("\n==================================================");
    Serial.println("Walter - Audio Test");
    Serial.println("==================================================\n");

    if (!audioFsInit()) { Serial.println("FATAL: filesystem init failed"); return; }
    if (!modemInit())   { Serial.println("FATAL: modem init failed");      return; }

    uint8_t* wav = nullptr;
    size_t wavLen = 0;

    unsigned long pipelineStart = millis();
    bool ok = deepgram::audioToAudio(AUDIO_FILE, &wav, &wavLen,
                                     DG_AGENT_PROMPT, DG_AGENT_VOICE);
    unsigned long pipelineMs = millis() - pipelineStart;

    Serial.printf("\nTotal pipeline time: %.2f s\n", pipelineMs / 1000.0f);

    if (!ok || !wav) {
        Serial.println("FATAL: pipeline failed");
        if (wav) free(wav);
        return;
    }

    // Dump audio to serial for extraction via save_serial_wav.py
    size_t b64Len = 0;
    char*  b64    = base64Encode(wav, wavLen, &b64Len);
    free(wav);

    if (b64) {
        Serial.println("---BEGIN WAV BASE64---");
        Serial.println(b64);
        Serial.println("---END WAV BASE64---");
        free(b64);
    }
}

// ================================================================
//  LATENCY BENCHMARK  — n trials across 3 audio files, no dump
// ================================================================

static const char* BENCH_FILES[3] = {
    "/carwash_question.wav",
    "/msg_small.wav",
    "/msg_tiny.wav"
};

static const char* BENCH_LABELS[3] = {
    "carwash_question.wav",
    "msg_small.wav",
    "msg_tiny.wav"
};

void runLatencyBenchmark(int n)
{
    if (n % 3 != 0) {
        Serial.printf("\nERROR: n must be divisible by 3 (got %d)\n", n);
        return;
    }

    int trialsPerFile = n / 3;

    Serial.println("\n==================================================");
    Serial.printf("Walter - Latency Benchmark  (%d trials, %d per file)\n", n, trialsPerFile);
    Serial.println("==================================================\n");

    if (!audioFsInit()) { Serial.println("FATAL: filesystem init failed"); return; }
    if (!modemInit())   { Serial.println("FATAL: modem init failed");      return; }

    // Machine-parseable header (CSV)
    // MATLAB parses lines starting with "DATA," — everything else is ignored.
    // first_resp_ms / last_resp_ms are relative to audio-send-start, so they
    // capture the overlap between upload and Deepgram's streaming response.
    Serial.println("DATA,file,trial,enc_ms,up_ms,first_resp_ms,last_resp_ms,dec_ms,up_bytes,dl_bytes");

    int globalTrial = 0;

    for (int fi = 0; fi < 3; fi++) {
        Serial.printf("\n=== File %d/3: %s ===\n", fi + 1, BENCH_LABELS[fi]);

        for (int t = 0; t < trialsPerFile; t++) {
            globalTrial++;
            Serial.printf("\n[Trial %d/%d]\n", t + 1, trialsPerFile);

            uint8_t*         wav    = nullptr;
            size_t           wavLen = 0;
            deepgram::Timing timing;

            bool ok = deepgram::audioToAudio(BENCH_FILES[fi], &wav, &wavLen,
                                             DG_AGENT_PROMPT, DG_AGENT_VOICE,
                                             &timing, /*printBreakdown=*/false);

            if (wav) { free(wav); wav = nullptr; }

            if (!ok) {
                Serial.println("  [TRIAL FAILED]");
                continue;
            }

            // Machine-parseable data row
            Serial.printf("DATA,%s,%d,%lu,%lu,%lu,%lu,%lu,%u,%u\n",
                          BENCH_LABELS[fi], globalTrial,
                          timing.encodeMs, timing.uploadMs,
                          timing.firstResponseMs, timing.lastResponseMs,
                          timing.decodeMs,
                          (unsigned)timing.uploadBytes,
                          (unsigned)timing.downloadBytes);
        }
    }

    Serial.println("\nDATA,END");
    Serial.println("==================================================");
    Serial.println("Benchmark complete.");
    Serial.println("==================================================\n");
}

// ================================================================
//  ENTRY POINT
// ================================================================

void setup()
{
    Serial.begin(115200);
    delay(3000);

#if RUN_MODE == MODE_TEST
    runAudioTest();
#elif RUN_MODE == MODE_BENCHMARK
    runLatencyBenchmark(15);
#else
    Serial.println("ERROR: Unknown RUN_MODE in config.h");
#endif
}

void loop()
{
    delay(10000);
}
