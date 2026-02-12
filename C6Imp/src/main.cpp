#include <Arduino.h>
#include "config.h"
#include "I2SManager.h"
#include "AudioManager.h"
#include "NetworkManager.h"

// ════════════════════════════════════════════════════════════════
// Global Objects
// ════════════════════════════════════════════════════════════════

I2SManager i2sManager(I2S_BCLK, I2S_WS, MIC_DIN, AMP_DOUT);
AudioManager audioManager(i2sManager, MIC_SAMPLE_RATE, SPEAKER_SAMPLE_RATE, MAX_AUDIO_BUFFER_SIZE);
NetworkManager networkManager(WIFI_SSID, WIFI_PASSWORD, WS_HOST, WS_PORT, WS_PATH);

// ════════════════════════════════════════════════════════════════
// State Variables
// ════════════════════════════════════════════════════════════════

volatile bool playAudio = false;
unsigned int audioChunkCount = 0;
bool pttPressed = false;

// ════════════════════════════════════════════════════════════════
// Network Callbacks
// ════════════════════════════════════════════════════════════════

void onWSBinaryReceived(uint8_t* payload, size_t length) {
    if (length < 1) return;

    uint8_t tag = payload[0];

    if (tag == 'A') {
        // Audio data
        size_t audioLen = length - 1;
        if (audioManager.appendAudioData(payload + 1, audioLen)) {
            audioChunkCount++;

            // Progress every 10 chunks
            if (audioChunkCount % 10 == 0) {
                DEBUG_PRINTF("[WS-RX] Received %u chunks, %u bytes total\n",
                            audioChunkCount, audioManager.getBufferedAudioSize());
            }
        } else {
            DEBUG_PRINTLN("[WS-RX] Audio buffer full!");
        }
    } else if (tag == 'E') {
        // End marker
        DEBUG_PRINTF("[WS-RX] End marker - %u bytes total\n",
                    audioManager.getBufferedAudioSize());
        if (audioManager.getBufferedAudioSize() > 0) {
            playAudio = true;
        }
    }
}

void onWSTextReceived(const char* text) {
    DEBUG_PRINTF("[WS-RX] Text: %s\n", text);
}

void onWSConnectionChange(bool connected) {
    if (!connected) {
        audioManager.clearSpeakerBuffer();
        audioChunkCount = 0;
    }
}

// ════════════════════════════════════════════════════════════════
// Push-to-Talk Handler
// ════════════════════════════════════════════════════════════════

bool readPTTButton() {
    // Debounced read
    bool raw1 = (digitalRead(PTT_PIN) == (PTT_ACTIVE_LOW ? LOW : HIGH));
    delay(10);
    bool raw2 = (digitalRead(PTT_PIN) == (PTT_ACTIVE_LOW ? LOW : HIGH));
    return raw1 && raw2;
}

void handlePTT() {
    bool pressed = readPTTButton();

    if (!pressed) {
        if (pttPressed) {
            // Button released - send END marker
            const uint8_t endMarker = 'E';
            networkManager.sendBinary(&endMarker, 1);
            DEBUG_PRINTLN("[PTT] Released -> Sent END marker");
        }
        pttPressed = false;
        delay(20);
        return;
    }

    // Button pressed
    if (!pttPressed) {
        DEBUG_PRINTLN("[PTT] Pressed -> Streaming audio");
    }
    pttPressed = true;

    // Read and send microphone audio
    int16_t micBuffer[SAMPLES_PER_CHUNK];
    size_t bytesRead = audioManager.readMicrophoneChunk(micBuffer, sizeof(micBuffer));

    if (bytesRead > 0) {
        // Prepend 'A' tag
        static uint8_t packet[1 + sizeof(micBuffer)];
        packet[0] = 'A';
        memcpy(packet + 1, micBuffer, bytesRead);
        networkManager.sendBinary(packet, 1 + bytesRead);
    }

    delay(1);
}

// ════════════════════════════════════════════════════════════════
// Setup
// ════════════════════════════════════════════════════════════════

void setup() {
    Serial.begin(SERIAL_BAUD_RATE);
    delay(1000);

    DEBUG_PRINTLN("\n\n============================================================");
    DEBUG_PRINTLN("  ESP32-C6 VOICE ASSISTANT (Modular WebSocket Architecture)");
    DEBUG_PRINTLN("============================================================");
    DEBUG_PRINTF("BCLK=GPIO%d, WS=GPIO%d (shared)\n", I2S_BCLK, I2S_WS);
    DEBUG_PRINTF("MIC_DIN=GPIO%d, AMP_DOUT=GPIO%d\n", MIC_DIN, AMP_DOUT);
    DEBUG_PRINTF("PTT Button=GPIO%d\n", PTT_PIN);
    DEBUG_PRINTLN("============================================================\n");

    // Initialize PTT button
    pinMode(PTT_PIN, INPUT_PULLUP);
    delay(100);

    int btnState = digitalRead(PTT_PIN);
    DEBUG_PRINTF("[PTT] Initial state: %s\n",
                btnState == HIGH ? "HIGH (not pressed)" : "LOW (pressed!)");

    if (btnState == LOW && PTT_ACTIVE_LOW) {
        DEBUG_PRINTLN("[PTT] WARNING: Button pressed at boot! Waiting for release...");
        while (digitalRead(PTT_PIN) == LOW) {
            delay(100);
        }
        DEBUG_PRINTLN("[PTT] Button released");
    }

    // Initialize audio (microphone first)
    if (!audioManager.startMicrophone()) {
        DEBUG_PRINTLN("[ERROR] Failed to initialize microphone!");
    }

    // Initialize network (WiFi + WebSocket)
    if (!networkManager.begin(WIFI_TIMEOUT_MS)) {
        DEBUG_PRINTLN("[ERROR] Failed to initialize network!");
    }

    // Set network callbacks
    networkManager.onBinaryReceived(onWSBinaryReceived);
    networkManager.onTextReceived(onWSTextReceived);
    networkManager.onConnectionChange(onWSConnectionChange);

    DEBUG_PRINTLN();
    DEBUG_PRINTLN("============================================================");
    DEBUG_PRINTF("  READY! Hold GPIO%d to talk, release to send\n", PTT_PIN);
    DEBUG_PRINTLN("============================================================\n");
}

// ════════════════════════════════════════════════════════════════
// Loop
// ════════════════════════════════════════════════════════════════

void loop() {
    // Process WebSocket events
    networkManager.loop();

    // Play audio if ready
    if (playAudio) {
        audioManager.playSpeakerBuffer();
        audioManager.clearSpeakerBuffer();
        audioChunkCount = 0;
        playAudio = false;
    }

    // Wait for WebSocket connection
    if (!networkManager.isWSConnected()) {
        delay(10);
        return;
    }

    // Handle push-to-talk
    handlePTT();
}
