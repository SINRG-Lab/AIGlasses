#ifndef AUDIO_MANAGER_H
#define AUDIO_MANAGER_H

#include <Arduino.h>
#include "I2SManager.h"

class AudioManager {
public:
    AudioManager(I2SManager& i2s, int micSampleRate, int spkSampleRate, size_t maxBufferSize);
    ~AudioManager();

    // Microphone operations
    bool startMicrophone();
    size_t readMicrophoneChunk(int16_t* buffer, size_t bufferSize);

    // Speaker operations
    bool startSpeaker();
    bool appendAudioData(const uint8_t* data, size_t len);
    void playSpeakerBuffer();
    void clearSpeakerBuffer();

    // Buffer info
    size_t getBufferedAudioSize() const { return audioBufferLen; }
    bool isSpeakerBufferFull() const { return audioBufferLen >= maxBufferSize; }

private:
    I2SManager& i2s;
    const int micSampleRate;
    const int spkSampleRate;
    const size_t maxBufferSize;

    uint8_t* audioBuffer;
    volatile size_t audioBufferLen;
};

#endif // AUDIO_MANAGER_H
