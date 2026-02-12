#include <Arduino.h>
#include "config.h"
#include "I2SManager.h"
#include "AudioManager.h"
#include "BLEManager.h"

// ════════════════════════════════════════════════════════════════
// Global Objects
// ════════════════════════════════════════════════════════════════

I2SManager i2sManager(I2S_BCLK, I2S_WS, MIC_SD, AMP_DIN);
AudioManager audioManager(i2sManager, MIC_SAMPLE_RATE, SPEAKER_SAMPLE_RATE, MAX_AUDIO_BUFFER_SIZE);
BLEManager bleManager(BLE_DEVICE_NAME, BLE_MTU);

// ════════════════════════════════════════════════════════════════
// State Variables
// ════════════════════════════════════════════════════════════════

volatile bool playAudio = false;
unsigned int audioChunkCount = 0;
bool pttPressed = false;

// ════════════════════════════════════════════════════════════════
// BLE Callbacks
// ════════════════════════════════════════════════════════════════

void onBLEAudioReceived(const uint8_t* data, size_t len) {
    if (audioManager.appendAudioData(data, len)) {
        audioChunkCount++;

        // Progress every 20 chunks
        if (audioChunkCount % 20 == 0) {
            DEBUG_PRINTF("[BLE-RX] Received %u chunks, %u bytes\n",
                        audioChunkCount, audioManager.getBufferedAudioSize());
        }
    } else {
        DEBUG_PRINTLN("[BLE-RX] Audio buffer full!");
    }
}

void onBLEControlReceived(uint8_t tag) {
    if (tag == 'E') {
        DEBUG_PRINTF("[BLE-CTRL] End marker - %u bytes buffered (%u chunks)\n",
                    audioManager.getBufferedAudioSize(), audioChunkCount);
        if (audioManager.getBufferedAudioSize() > 0) {
            playAudio = true;
        }
    } else if (tag == 'S') {
        DEBUG_PRINTLN("[BLE-CTRL] Start marker - clearing buffer");
        audioManager.clearSpeakerBuffer();
        audioChunkCount = 0;
    }
}

void onBLEConnectionChange(bool connected) {
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
            bleManager.sendControl('E');
            DEBUG_PRINTLN("[PTT] Released -> Sent END marker");
        }
        pttPressed = false;
        delay(20);
        return;
    }

    // Button pressed
    if (!pttPressed) {
        DEBUG_PRINTLN("[PTT] Pressed -> Streaming audio via BLE...");
    }
    pttPressed = true;

    // Read and send microphone audio
    int16_t micBuffer[SAMPLES_PER_CHUNK];
    size_t bytesRead = audioManager.readMicrophoneChunk(micBuffer, sizeof(micBuffer));

    if (bytesRead > 0) {
        bleManager.sendAudioData((uint8_t*)micBuffer, bytesRead);
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
    DEBUG_PRINTLN("  ESP32-C6 VOICE ASSISTANT (Modular BLE Architecture)");
    DEBUG_PRINTLN("============================================================");
    DEBUG_PRINTF("BCLK=GPIO%d, WS=GPIO%d (shared)\n", I2S_BCLK, I2S_WS);
    DEBUG_PRINTF("MIC_SD=GPIO%d, AMP_DIN=GPIO%d\n", MIC_SD, AMP_DIN);
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

    // Initialize BLE
    if (!bleManager.begin()) {
        DEBUG_PRINTLN("[ERROR] Failed to initialize BLE!");
    }

    // Set BLE callbacks
    bleManager.onAudioReceived(onBLEAudioReceived);
    bleManager.onControlReceived(onBLEControlReceived);
    bleManager.onConnectionChange(onBLEConnectionChange);

    DEBUG_PRINTLN();
    DEBUG_PRINTLN("============================================================");
    DEBUG_PRINTF("  READY! Hold GPIO%d to talk, release to send\n", PTT_PIN);
    DEBUG_PRINTLN("  Open AIGlasses app and tap 'Scan & Connect'");
    DEBUG_PRINTLN("============================================================\n");
}

// ════════════════════════════════════════════════════════════════
// Loop
// ════════════════════════════════════════════════════════════════

void loop() {
    // Play audio if ready
    if (playAudio) {
        audioManager.playSpeakerBuffer();
        audioManager.clearSpeakerBuffer();
        audioChunkCount = 0;
        playAudio = false;
    }

    // Wait for BLE connection
    if (!bleManager.isConnected()) {
        delay(100);
        return;
    }

    // Handle push-to-talk
    handlePTT();
}
