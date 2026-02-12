#include "AudioManager.h"
#include "config.h"

AudioManager::AudioManager(I2SManager& i2s, int micSampleRate, int spkSampleRate, size_t maxBufferSize)
    : i2s(i2s), micSampleRate(micSampleRate), spkSampleRate(spkSampleRate),
      maxBufferSize(maxBufferSize), audioBufferLen(0) {

    audioBuffer = new uint8_t[maxBufferSize];
    if (!audioBuffer) {
        DEBUG_PRINTLN("[AUDIO] ERROR: Failed to allocate audio buffer!");
    }
}

AudioManager::~AudioManager() {
    delete[] audioBuffer;
}

bool AudioManager::startMicrophone() {
    return i2s.initMicrophone(micSampleRate, I2S_DMA_BUF_COUNT, I2S_MIC_DMA_BUF_LEN);
}

size_t AudioManager::readMicrophoneChunk(int16_t* buffer, size_t bufferSize) {
    return i2s.readMicrophone(buffer, bufferSize, 20);
}

bool AudioManager::startSpeaker() {
    return i2s.initSpeaker(spkSampleRate, I2S_DMA_BUF_COUNT, I2S_SPK_DMA_BUF_LEN);
}

bool AudioManager::appendAudioData(const uint8_t* data, size_t len) {
    if (audioBufferLen + len > maxBufferSize) {
        DEBUG_PRINTLN("[AUDIO] Buffer full, cannot append");
        return false;
    }

    memcpy(audioBuffer + audioBufferLen, data, len);
    audioBufferLen += len;
    return true;
}

void AudioManager::playSpeakerBuffer() {
    if (audioBufferLen == 0) {
        DEBUG_PRINTLN("[AUDIO] No audio to play");
        return;
    }

    float duration = (float)audioBufferLen / (spkSampleRate * 2);
    DEBUG_PRINTF("[AUDIO] Playing %u bytes (%.2fs @ %dHz)\n",
                 audioBufferLen, duration, spkSampleRate);

    // Switch to speaker mode
    startSpeaker();
    delay(50);

    // Play audio in chunks
    size_t offset = 0;
    size_t totalChunks = 0;
    const size_t chunkSize = 2048;

    while (offset < audioBufferLen) {
        size_t chunk = min(chunkSize, audioBufferLen - offset);
        size_t written = i2s.writeSpeaker(audioBuffer + offset, chunk);
        offset += written;
        totalChunks++;

        // Progress reporting every 20%
        if (totalChunks % ((audioBufferLen / chunkSize / 5) + 1) == 0) {
            float progress = (float)offset / audioBufferLen * 100.0f;
            DEBUG_PRINTF("[AUDIO] Playing... %.1f%% (%u/%u bytes)\n",
                        progress, offset, audioBufferLen);
        }
    }

    delay(100);
    DEBUG_PRINTF("[AUDIO] Playback complete! (%u bytes, %u chunks)\n",
                 audioBufferLen, totalChunks);

    // Switch back to microphone
    startMicrophone();
}

void AudioManager::clearSpeakerBuffer() {
    audioBufferLen = 0;
}
