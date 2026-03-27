#include "deepgram_api.h"
#include "config.h"
#include "modem_setup.h"
#include "audio_utils.h"
#include <WalterModem.h>
#include <ArduinoJson.h>
#include <esp_system.h>   // esp_random()
#include <string.h>

namespace deepgram {

static const char* DG_HOST   = "agent.deepgram.com";
static const uint16_t DG_PORT = 443;
static const char* DG_WS_PATH = "/v1/agent/converse";

// How far into responseBuffer we have already parsed WS frames.
// Resets to 0 on each audioToAudio() call.
static size_t wsParsedOffset = 0;

// ================================================================
//  PCM LOADING  (strip RIFF/WAVE header, return raw samples)
// ================================================================

// outSampleRate may be nullptr; if provided, it is filled from the fmt chunk (default 16000).
static uint8_t* loadRawPcm(const char* path, size_t* outLen, uint32_t* outSampleRate = nullptr)
{
    if (outSampleRate) *outSampleRate = 16000; // default if not found

    uint8_t* buf = nullptr;
    size_t   len = 0;
    if (!loadAudioFile(path, &buf, &len)) return nullptr;

    // Walk RIFF chunks to find the 'fmt ' and 'data' chunks
    if (len > 12 &&
        buf[0] == 'R' && buf[1] == 'I' && buf[2] == 'F' && buf[3] == 'F' &&
        buf[8] == 'W' && buf[9] == 'A' && buf[10] == 'V' && buf[11] == 'E')
    {
        uint32_t pos = 12;
        while (pos + 8 <= len) {
            uint32_t chunkSize;
            memcpy(&chunkSize, buf + pos + 4, 4);

            if (buf[pos]=='f' && buf[pos+1]=='m' && buf[pos+2]=='t' && buf[pos+3]==' ') {
                // fmt chunk: sample rate is at offset +12 from chunk start (pos+8+4)
                if (outSampleRate && chunkSize >= 8 && pos + 16 <= len)
                    memcpy(outSampleRate, buf + pos + 12, 4);

            } else if (buf[pos]=='d' && buf[pos+1]=='a' &&
                       buf[pos+2]=='t' && buf[pos+3]=='a')
            {
                size_t pcmLen = (chunkSize < len - (pos + 8)) ? chunkSize : len - (pos + 8);
                uint8_t* pcm = (uint8_t*)ps_malloc(pcmLen);
                if (!pcm) { free(buf); return nullptr; }
                memcpy(pcm, buf + pos + 8, pcmLen);
                free(buf);
                *outLen = pcmLen;
                return pcm;
            }

            pos += 8 + chunkSize;
        }
        // Fallback: standard 44-byte header
        if (len > 44) {
            if (outSampleRate && len >= 28)
                memcpy(outSampleRate, buf + 24, 4); // sample rate at byte 24 in standard header
            size_t pcmLen = len - 44;
            uint8_t* pcm = (uint8_t*)ps_malloc(pcmLen);
            if (!pcm) { free(buf); return nullptr; }
            memcpy(pcm, buf + 44, pcmLen);
            free(buf);
            *outLen = pcmLen;
            return pcm;
        }
    }

    // Not a WAV file — treat as raw PCM
    *outLen = len;
    return buf;
}

// ================================================================
//  WEBSOCKET FRAME BUILDER  (client → server, always masked)
// ================================================================

// Writes a masked WebSocket frame into 'out'.
// Returns the number of bytes written.
// 'out' must be at least 14 + payloadLen bytes.
static size_t buildWsFrame(uint8_t* out, uint8_t opcode,
                           const uint8_t* payload, size_t payloadLen)
{
    size_t pos = 0;
    out[pos++] = 0x80 | (opcode & 0x0F); // FIN + opcode

    // Payload length field (with MASK bit set)
    if (payloadLen < 126) {
        out[pos++] = 0x80 | (uint8_t)payloadLen;
    } else if (payloadLen < 65536) {
        out[pos++] = 0x80 | 126;
        out[pos++] = (payloadLen >> 8) & 0xFF;
        out[pos++] =  payloadLen       & 0xFF;
    } else {
        out[pos++] = 0x80 | 127;
        for (int i = 7; i >= 0; i--)
            out[pos++] = (payloadLen >> (i * 8)) & 0xFF;
    }

    // 4-byte random mask key
    uint32_t maskVal = esp_random();
    uint8_t mask[4];
    memcpy(mask, &maskVal, 4);
    out[pos++] = mask[0];
    out[pos++] = mask[1];
    out[pos++] = mask[2];
    out[pos++] = mask[3];

    // XOR-masked payload
    for (size_t i = 0; i < payloadLen; i++)
        out[pos++] = payload[i] ^ mask[i & 3];

    return pos;
}

// ================================================================
//  LOW-LEVEL SEND  (splits into ≤1500-byte socketSend calls)
// ================================================================

static bool wsSocketSend(int socketId, const uint8_t* data, size_t len)
{
    const size_t chunk = 1500;
    for (size_t i = 0; i < len; i += chunk) {
        size_t n = (len - i < chunk) ? (len - i) : chunk;
        if (!WalterModem::socketSend(socketId, (uint8_t*)(data + i), (uint16_t)n)) {
            Serial.printf("WS socketSend failed at byte %u\n", (unsigned)i);
            return false;
        }
        if (i + n < len) delay(5);
    }
    return true;
}

// ================================================================
//  WS FRAME SEND HELPERS
// ================================================================

// Send a text (opcode 0x1) frame — for JSON control messages
static bool wsSendText(int socketId, const char* text)
{
    size_t  textLen  = strlen(text);
    size_t  frameMax = 14 + textLen;
    uint8_t* frame   = (uint8_t*)ps_malloc(frameMax);
    if (!frame) return false;

    size_t frameLen = buildWsFrame(frame, 0x1, (const uint8_t*)text, textLen);
    bool ok = wsSocketSend(socketId, frame, frameLen);
    free(frame);
    return ok;
}

// Send audio data as binary (opcode 0x2) WebSocket frames.
// Uses 1460-byte payload chunks so each frame fits in one socketSend call
// (1460 payload + 8 WS header = 1468 bytes, within the 1500-byte AT limit).
static bool wsSendBinaryChunked(int socketId, const uint8_t* data, size_t totalLen)
{
    const size_t payloadChunk = 1460;
    uint8_t frameBuf[1480]; // 1460 payload + 14 header bytes max, on stack

    size_t sent = 0;
    while (sent < totalLen) {
        size_t payloadLen = totalLen - sent;
        if (payloadLen > payloadChunk) payloadLen = payloadChunk;

        size_t frameLen = buildWsFrame(frameBuf, 0x2, data + sent, payloadLen);
        if (!WalterModem::socketSend(socketId, frameBuf, (uint16_t)frameLen)) {
            Serial.printf("WS audio send failed at byte %u\n", (unsigned)sent);
            return false;
        }
        sent += payloadLen;

        #if VERBOSITY >= 2
        if (sent % 40000 < payloadChunk) {
            Serial.printf("  Audio: %u/%u (%u%%)\n",
                          (unsigned)sent, (unsigned)totalLen,
                          (unsigned)(sent * 100 / totalLen));
        }
        #endif
        delay(5);
    }

    Serial.printf("Audio streaming complete: %u bytes sent\n", (unsigned)totalLen);
    return true;
}

// Send a close (opcode 0x8) frame
static bool wsSendClose(int socketId)
{
    uint8_t frame[6]; // 2 header + 4 mask, no payload
    size_t frameLen = buildWsFrame(frame, 0x8, nullptr, 0);
    return WalterModem::socketSend(socketId, frame, (uint16_t)frameLen);
}

// ================================================================
//  WS FRAME PARSER  (server → client, unmasked)
// ================================================================

// Tries to parse the next complete WebSocket frame from
// responseBuffer starting at wsParsedOffset.
// On success:
//   - *outOpcode, *outPayload, *outPayloadLen are filled
//   - *outPayload points directly into responseBuffer (no copy)
//   - wsParsedOffset is advanced past the consumed frame
// Returns false if not enough data is available yet.
static bool parseNextWsFrame(uint8_t* outOpcode,
                             const uint8_t** outPayload,
                             size_t* outPayloadLen)
{
    const uint8_t* buf       = responseBuffer + wsParsedOffset;
    size_t         available = responseLen - wsParsedOffset;

    if (available < 2) return false;

    uint8_t b0      = buf[0];
    uint8_t b1      = buf[1];
    uint8_t opcode  = b0 & 0x0F;
    bool    masked  = (b1 & 0x80) != 0;
    size_t  plen    = b1 & 0x7F;
    size_t  pos     = 2;

    if (plen == 126) {
        if (available < pos + 2) return false;
        plen = ((size_t)buf[pos] << 8) | buf[pos + 1];
        pos += 2;
    } else if (plen == 127) {
        if (available < pos + 8) return false;
        plen = 0;
        for (int i = 0; i < 8; i++)
            plen = (plen << 8) | buf[pos + i];
        pos += 8;
    }

    uint8_t maskKey[4] = {};
    if (masked) {
        if (available < pos + 4) return false;
        memcpy(maskKey, buf + pos, 4);
        pos += 4;
    }

    if (available < pos + plen) return false;

    // Unmask in-place if needed (per RFC 6455, servers must NOT mask, but be safe)
    if (masked) {
        uint8_t* p = responseBuffer + wsParsedOffset + pos;
        for (size_t i = 0; i < plen; i++)
            p[i] ^= maskKey[i & 3];
    }

    *outOpcode     = opcode;
    *outPayload    = responseBuffer + wsParsedOffset + pos;
    *outPayloadLen = plen;
    wsParsedOffset += pos + plen;
    return true;
}

// ================================================================
//  WEBSOCKET HANDSHAKE
// ================================================================

static bool wsHandshake(int socketId)
{
    // Build a random Sec-WebSocket-Key (16 random bytes, base64-encoded)
    uint8_t keyBytes[16];
    for (int i = 0; i < 4; i++) {
        uint32_t r = esp_random();
        memcpy(keyBytes + i * 4, &r, 4);
    }
    size_t keyB64Len = 0;
    char* keyB64 = base64Encode(keyBytes, 16, &keyB64Len);
    if (!keyB64) return false;

    // Build HTTP/1.1 upgrade request
    char upgrade[512];
    int upgradeLen = snprintf(upgrade, sizeof(upgrade),
        "GET %s HTTP/1.1\r\n"
        "Host: %s\r\n"
        "Upgrade: websocket\r\n"
        "Connection: Upgrade\r\n"
        "Sec-WebSocket-Key: %s\r\n"
        "Sec-WebSocket-Version: 13\r\n"
        "Authorization: Token %s\r\n"
        "\r\n",
        DG_WS_PATH, DG_HOST, keyB64, DEEPGRAM_API_KEY);
    free(keyB64);

    if (!WalterModem::socketSend(socketId, (uint8_t*)upgrade, (uint16_t)upgradeLen)) {
        Serial.println("WS upgrade send failed");
        return false;
    }

    // Wait for the HTTP 101 response (ring handler accumulates into responseBuffer)
    unsigned long deadline = millis() + 15000;
    while (millis() < deadline) {
        if (responseLen > 4) {
            void* sep = memmem(responseBuffer, responseLen, "\r\n\r\n", 4);
            if (sep) {
                if (memmem(responseBuffer, (size_t)((uint8_t*)sep - responseBuffer), "101", 3)) {
                    wsParsedOffset = (uint8_t*)sep - responseBuffer + 4;
                    #if VERBOSITY >= 1
                    Serial.println("WebSocket handshake OK");
                    #endif
                    return true;
                } else {
                    Serial.printf("WS upgrade rejected: %.120s\n", (char*)responseBuffer);
                    return false;
                }
            }
        }
        delay(100);
    }

    Serial.println("WS handshake timeout");
    return false;
}

// ================================================================
//  WAIT FOR A SPECIFIC WS JSON EVENT
// ================================================================

static bool waitForWsEvent(int socketId, const char* eventType, unsigned long timeoutMs)
{
    unsigned long deadline = millis() + timeoutMs;

    while (millis() < deadline) {
        // Flush any pending ring bytes that have aged out
        if (pendingRingBytes > 0 && lastRingMs > 0 &&
            millis() - lastRingMs > RING_CHUNK_TIMEOUT_MS) {
            flushPendingRing();
        }

        if (socketDisconnected) {
            Serial.println("Socket disconnected while waiting for WS event");
            return false;
        }

        uint8_t         opcode;
        const uint8_t*  payload;
        size_t          payloadLen;

        while (parseNextWsFrame(&opcode, &payload, &payloadLen)) {
            if (opcode == 0x1 && payloadLen > 0) { // text frame
                JsonDocument doc;
                DeserializationError err = deserializeJson(doc,
                    (const char*)payload, payloadLen);
                if (!err) {
                    const char* type = doc["type"] | "";
                    #if VERBOSITY >= 2
                    Serial.printf("  WS event: %s\n", type);
                    #endif
                    if (strcmp(type, eventType) == 0) return true;
                    if (strcmp(type, "Error") == 0) {
                        Serial.printf("Deepgram error: %s\n",
                                      doc["description"] | "unknown");
                        return false;
                    }
                }
            } else if (opcode == 0x8) { // close
                Serial.println("Server sent close frame during WS wait");
                return false;
            }
        }

        delay(50);
    }

    Serial.printf("Timeout waiting for WS event: %s\n", eventType);
    return false;
}

// ================================================================
//  SETTINGS MESSAGE BUILDER
// ================================================================

// Output format constants for Deepgram Voice Agent.
// mulaw (µ-law) at 8 kHz is the most compressed option available over WebSocket:
//   8-bit samples × 8000 Hz = 8 kB/s — 6× less data than linear16 at 24 kHz.
// WAV format code 7 = µ-law, 6 = A-law, 1 = PCM.
static const char*     DG_OUT_ENCODING    = "mulaw";
static const int       DG_OUT_SAMPLE_RATE = 8000;
static const uint16_t  DG_WAV_FORMAT      = 7;   // WAVE_FORMAT_MULAW
static const uint16_t  DG_WAV_BITS        = 8;

// Returns a PSRAM-allocated JSON string. Caller must free().
static char* buildSettingsJson(const char* prompt, const char* voice,
                               int inputSampleRate = 16000,
                               const char* inputEncoding = "mulaw")
{
    JsonDocument doc;
    doc["type"] = "Settings";

    JsonObject audio  = doc["audio"].to<JsonObject>();
    JsonObject ainput = audio["input"].to<JsonObject>();
    ainput["encoding"]    = inputEncoding;
    ainput["sample_rate"] = inputSampleRate;
    JsonObject aoutput = audio["output"].to<JsonObject>();
    aoutput["encoding"]    = DG_OUT_ENCODING;
    aoutput["sample_rate"] = DG_OUT_SAMPLE_RATE;
    aoutput["container"]   = "none";

    JsonObject agent  = doc["agent"].to<JsonObject>();

    JsonObject listen = agent["listen"].to<JsonObject>();
    JsonObject lp     = listen["provider"].to<JsonObject>();
    lp["type"]  = "deepgram";
    lp["model"] = "nova-3";
    lp["endpointing"] = 800;

    JsonObject think  = agent["think"].to<JsonObject>();
    JsonObject tp     = think["provider"].to<JsonObject>();
    tp["type"]  = "open_ai";
    tp["model"] = "gpt-4o-mini";
    think["prompt"] = prompt;

    JsonObject speak  = agent["speak"].to<JsonObject>();
    JsonObject sp     = speak["provider"].to<JsonObject>();
    sp["type"]  = "deepgram";
    sp["model"] = voice;

    size_t capacity = 1024 + strlen(prompt);
    char*  buf      = (char*)ps_malloc(capacity);
    if (!buf) return nullptr;

    size_t len = serializeJson(doc, buf, capacity);
    buf[len] = '\0';
    return buf;
}

// ================================================================
//  RESPONSE COLLECTION
// ================================================================

// Reads WS frames from responseBuffer until AgentAudioDone (or timeout/disconnect).
// Copies binary audio frames into pcmBuf.
// outFirstAudioMs is set to millis() when the first binary frame arrives (0 if none received).
// Returns the number of PCM bytes written to pcmBuf.
static size_t collectAgentResponse(int socketId,
                                   uint8_t* pcmBuf, size_t pcmCapacity,
                                   unsigned long* outFirstAudioMs)
{
    *outFirstAudioMs = 0;

    size_t        pcmLen        = 0;
    bool          audioDone     = false;
    unsigned long noDataCount   = 0;
    unsigned long lastKeepalive = millis();
    unsigned long lastStatus    = millis();
    unsigned long start         = millis();
    const unsigned long timeout = 120000; // 2 minutes

    while (millis() - start < timeout) {
        // Periodic status log
        #if VERBOSITY >= 2
        if (millis() - lastStatus >= 5000) {
            Serial.printf("  Collecting... %u audio bytes\n", (unsigned)pcmLen);
            lastStatus = millis();
        }
        #endif

        // Send KeepAlive every 8 seconds to prevent idle disconnect
        if (millis() - lastKeepalive >= 8000) {
            wsSendText(socketId, "{\"type\":\"KeepAlive\"}");
            lastKeepalive = millis();
        }

        // Flush pending ring bytes on idle timeout
        if (pendingRingBytes > 0 && lastRingMs > 0 &&
            millis() - lastRingMs > RING_CHUNK_TIMEOUT_MS) {
            flushPendingRing();
        }

        // Socket disconnect: flush remaining data and stop
        if (socketDisconnected) {
            while (pendingRingBytes > 0) {
                if (!flushPendingRing()) break;
            }
            #if VERBOSITY >= 1
            Serial.println("Socket disconnected, using collected audio");
            #endif
            break;
        }

        // Parse all available WS frames
        bool        gotData = false;
        uint8_t     opcode;
        const uint8_t* payload;
        size_t      payloadLen;

        while (parseNextWsFrame(&opcode, &payload, &payloadLen)) {
            gotData = true;

            if (opcode == 0x2) {
                // Binary: raw audio samples
                if (*outFirstAudioMs == 0)
                    *outFirstAudioMs = millis();
                if (pcmLen + payloadLen <= pcmCapacity) {
                    memcpy(pcmBuf + pcmLen, payload, payloadLen);
                    pcmLen += payloadLen;
                } else {
                    Serial.println("PCM buffer full, truncating audio");
                }

            } else if (opcode == 0x1) {
                // Text: JSON event
                JsonDocument doc;
                DeserializationError err = deserializeJson(doc,
                    (const char*)payload, payloadLen);
                if (!err) {
                    const char* type = doc["type"] | "";

                    #if VERBOSITY >= 2
                    Serial.printf("  WS event: %s\n", type);
                    #endif

                    if (strcmp(type, "ConversationText") == 0) {
                        #if VERBOSITY >= 1
                        const char* role    = doc["role"]    | "";
                        const char* content = doc["content"] | "";
                        Serial.printf("  [%s]: %.120s\n", role, content);
                        #endif

                    } else if (strcmp(type, "AgentStartedSpeaking") == 0) {
                        #if VERBOSITY >= 1
                        float lat = doc["total_latency"] | 0.0f;
                        Serial.printf("  Agent speaking (latency: %.2f s)\n", lat);
                        #endif

                    } else if (strcmp(type, "AgentAudioDone") == 0) {
                        Serial.println("  Agent audio complete");
                        audioDone = true;

                    } else if (strcmp(type, "Error") == 0) {
                        Serial.printf("  Agent error: %s\n",
                                      doc["description"] | "unknown");
                        if (pcmLen == 0) return 0;

                    } else if (strcmp(type, "Warning") == 0) {
                        #if VERBOSITY >= 1
                        Serial.printf("  Warning: %s\n",
                                      doc["description"] | "");
                        #endif
                    }
                }

            } else if (opcode == 0x8) {
                // Close frame from server
                #if VERBOSITY >= 1
                Serial.println("  Server sent close frame");
                #endif
                audioDone = true;
            }
        }

        if (audioDone) break;

        if (gotData) {
            noDataCount = 0;
        } else {
            noDataCount++;
            // 600 × 50 ms = 30 seconds without any new WS frame
            if (noDataCount > 600) {
                Serial.println("Deepgram collect timeout (no new data)");
                break;
            }
        }

        delay(50);
    }

    return pcmLen;
}

// ================================================================
//  PUBLIC API
// ================================================================

bool audioToAudio(const char* audioPath,
                  uint8_t** outWav, size_t* outWavLen,
                  const char* prompt, const char* voice,
                  Timing* outTiming, bool printBreakdown)
{
    Serial.println("\n--- Deepgram Audio-to-Audio ---");

    // 1. Load raw PCM from WAV (strip RIFF header), capture sample rate
    size_t   pcmInLen    = 0;
    uint32_t sampleRate  = 16000;
    uint8_t* pcmIn       = loadRawPcm(audioPath, &pcmInLen, &sampleRate);
    if (!pcmIn) {
        Serial.println("Failed to load audio file");
        return false;
    }
    Serial.printf("Input audio: %u bytes PCM @ %u Hz\n", (unsigned)pcmInLen, (unsigned)sampleRate);

    // Encode linear16 → mulaw (2× size reduction: each int16 sample → 1 byte)
    unsigned long encodeStart = millis();
    size_t   mulawInLen = 0;
    uint8_t* mulawIn    = pcm16ToMulaw((const int16_t*)pcmIn,
                                       pcmInLen / 2, &mulawInLen);
    unsigned long encodeMs = millis() - encodeStart;
    free(pcmIn);
    pcmIn = nullptr;
    if (!mulawIn) {
        Serial.println("Failed to encode PCM to mulaw");
        return false;
    }
    // Append trailing silence so endpointing can trigger even if the file
    // has very little quiet at the end. 1.5 s of mulaw silence = 0xFF bytes.
    size_t silenceBytes = (size_t)(sampleRate * 1.5f);
    uint8_t* padded = (uint8_t*)ps_realloc(mulawIn, mulawInLen + silenceBytes);
    if (padded) {
        memset(padded + mulawInLen, 0xFF, silenceBytes);
        mulawIn    = padded;
        mulawInLen += silenceBytes;
    }

    Serial.printf("Mulaw encoded: %u bytes (%.1fx reduction) in %lu ms\n",
                  (unsigned)mulawInLen, (float)pcmInLen / mulawInLen, encodeMs);

    // 2. Connect TLS socket; responseBuffer holds all incoming WS data
    wsParsedOffset = 0;
    resetResponseBuffer(512 * 1024); // 512 KB — holds handshake + events + audio frames

    if (!connectSocket(1, DG_HOST, DG_PORT)) {
        free(mulawIn);
        freeResponseBuffer();
        return false;
    }

    // 3. WebSocket upgrade handshake
    if (!wsHandshake(1)) {
        free(mulawIn);
        closeSocket(1);
        freeResponseBuffer();
        return false;
    }

    // 4. Build and send Settings message
    char* settingsJson = buildSettingsJson(prompt, voice, (int)sampleRate, "mulaw");
    if (!settingsJson) {
        Serial.println("PSRAM alloc failed for Settings JSON");
        free(mulawIn);
        closeSocket(1);
        freeResponseBuffer();
        return false;
    }

    #if VERBOSITY >= 2
    Serial.printf("Settings: %s\n", settingsJson);
    #endif

    if (!wsSendText(1, settingsJson)) {
        free(settingsJson);
        free(mulawIn);
        closeSocket(1);
        freeResponseBuffer();
        return false;
    }
    free(settingsJson);
    Serial.println("Settings sent, waiting for SettingsApplied...");

    // 5. Wait for SettingsApplied before streaming audio
    if (!waitForWsEvent(1, "SettingsApplied", 15000)) {
        Serial.println("Never received SettingsApplied");
        free(mulawIn);
        closeSocket(1);
        freeResponseBuffer();
        return false;
    }
    Serial.println("SettingsApplied received");

    // 6. Stream mulaw audio as binary WebSocket frames
    Serial.printf("Sending %u bytes of mulaw audio...\n", (unsigned)mulawInLen);
    firstRingAfterResetMs = 0; // reset so we capture when Deepgram's response first arrives
    unsigned long audioSendStart = millis();

    if (!wsSendBinaryChunked(1, mulawIn, mulawInLen)) {
        free(mulawIn);
        closeSocket(1);
        freeResponseBuffer();
        return false;
    }
    free(mulawIn);
    mulawIn = nullptr;

    unsigned long audioSendDone = millis();
    Serial.printf("[DG] Audio sent in %lu ms\n", audioSendDone - audioSendStart);

    // 7. Collect spoken response
    Serial.println("Waiting for agent response...");
    const size_t pcmOutCapacity = 300 * 1024; // 300 KB — ~6 s at 24 kHz 16-bit mono
    uint8_t* pcmOut = (uint8_t*)ps_malloc(pcmOutCapacity);
    if (!pcmOut) {
        Serial.println("PSRAM alloc failed for PCM output buffer");
        closeSocket(1);
        freeResponseBuffer();
        return false;
    }

    unsigned long firstAudioMs  = 0; // absolute millis() of first audio byte (from WS parsing)
    size_t pcmOutLen = collectAgentResponse(1, pcmOut, pcmOutCapacity, &firstAudioMs);
    unsigned long collectDone   = millis();

    // Use the ring-handler timestamp for first response — it captures when data
    // actually arrived at the modem, even if that happened during the upload phase.
    // Fall back to the WS-parse timestamp if no ring fired (shouldn't happen).
    unsigned long actualFirstMs = (firstRingAfterResetMs > 0) ? firstRingAfterResetMs : firstAudioMs;
    unsigned long firstResponseMs = (actualFirstMs > audioSendStart) ? actualFirstMs - audioSendStart : 0;
    unsigned long lastResponseMs  = collectDone - audioSendStart;

    // Decode output mulaw → PCM16 (for timing; speaker output will use this buffer later)
    unsigned long decodeMs = 0;
    if (pcmOutLen > 0) {
        size_t pcm16Len = 0;
        unsigned long decodeStart = millis();
        uint8_t* pcm16Out = mulawToPcm16(pcmOut, pcmOutLen, &pcm16Len);
        decodeMs = millis() - decodeStart;
        free(pcm16Out); // not yet wired to speaker; freed after timing
    }

    // 8. Clean close
    wsSendClose(1);
    delay(100);
    closeSocket(1);
    freeResponseBuffer();

    // Latency summary (suppressed when caller is aggregating)
    unsigned long uploadMs = audioSendDone - audioSendStart;
    if (printBreakdown) {
        Serial.println("\n==== Deepgram Latency Breakdown ====");
        Serial.printf("  1. Encode  (PCM->mulaw):   %5lu ms  (%u -> %u bytes)\n",
                      encodeMs, (unsigned)pcmInLen, (unsigned)mulawInLen);
        Serial.printf("  2. Upload  (send audio):   %5lu ms  (%u bytes)\n",
                      uploadMs, (unsigned)mulawInLen);
        Serial.printf("  3. First response:    t+%5lu ms  (from send start)\n", firstResponseMs);
        Serial.printf("  4. Last response:     t+%5lu ms  (%u bytes received)\n",
                      lastResponseMs, (unsigned)pcmOutLen);
        Serial.printf("  5. Decode  (mulaw->PCM16): %5lu ms  (%u -> %u bytes)\n",
                      decodeMs, (unsigned)pcmOutLen, (unsigned)(pcmOutLen * 2));
        Serial.printf("  ---\n");
        Serial.printf("  Wall-clock (encode..decode): %5lu ms\n",
                      encodeMs + lastResponseMs + decodeMs);
        Serial.println("====================================\n");
    }

    if (outTiming) {
        outTiming->encodeMs       = encodeMs;
        outTiming->uploadMs       = uploadMs;
        outTiming->firstResponseMs= firstResponseMs;
        outTiming->lastResponseMs = lastResponseMs;
        outTiming->decodeMs       = decodeMs;
        outTiming->uploadBytes    = mulawInLen;
        outTiming->downloadBytes  = pcmOutLen;
    }

    if (pcmOutLen == 0) {
        Serial.println("No audio received from Deepgram agent");
        free(pcmOut);
        return false;
    }

    // 9. Wrap samples in a WAV header and return to caller
    uint8_t* wavData = buildWavFile(pcmOut, pcmOutLen,
                                    DG_OUT_SAMPLE_RATE, DG_WAV_BITS,
                                    DG_WAV_FORMAT, outWavLen);
    free(pcmOut);

    if (!wavData) {
        Serial.println("Failed to build WAV");
        return false;
    }

    *outWav = wavData;
    return true;
}

} // namespace deepgram
