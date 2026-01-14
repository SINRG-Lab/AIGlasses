#include "audio_packetizer.h"
#include "config.h"
#include <string.h>

AudioPacketizer::AudioPacketizer(size_t chunk_size_bytes) 
    : chunk_size(chunk_size_bytes), buffer(nullptr), buffer_pos(0) {
    // Allocate buffer for one chunk
    buffer = new uint8_t[chunk_size];
    reset();
}

AudioPacketizer::~AudioPacketizer() {
    if (buffer) {
        delete[] buffer;
    }
}

size_t AudioPacketizer::addSamples(const int16_t* samples, size_t num_samples,
                                   uint8_t* output_buffer, size_t output_buffer_size,
                                   size_t& chunks_written) {
    if (samples == nullptr || output_buffer == nullptr || num_samples == 0) {
        chunks_written = 0;
        return 0;
    }

    chunks_written = 0;
    size_t samples_processed = 0;
    size_t output_pos = 0;

    for (size_t i = 0; i < num_samples; ++i) {
        int16_t sample = samples[i];
        
        // Pack little-endian
        uint8_t low = (uint8_t)(sample & 0xFF);
        uint8_t high = (uint8_t)((sample >> 8) & 0xFF);

        // Check if adding 2 bytes would overflow chunk
        if (buffer_pos + 2 > chunk_size) {
            // Flush current chunk to output
            if (output_pos + chunk_size <= output_buffer_size) {
                memcpy(output_buffer + output_pos, buffer, chunk_size);
                output_pos += chunk_size;
                chunks_written++;
            }
            buffer_pos = 0;
        }

        // Add sample bytes to buffer
        buffer[buffer_pos++] = low;
        buffer[buffer_pos++] = high;
        samples_processed++;
    }

    return samples_processed;
}

size_t AudioPacketizer::flush(uint8_t* output_buffer, size_t output_buffer_size) {
    if (buffer_pos == 0 || output_buffer == nullptr) {
        return 0;
    }

    if (buffer_pos <= output_buffer_size) {
        memcpy(output_buffer, buffer, buffer_pos);
        size_t flushed = buffer_pos;
        buffer_pos = 0;
        return flushed;
    }

    return 0;
}

void AudioPacketizer::reset() {
    buffer_pos = 0;
    if (buffer) {
        memset(buffer, 0, chunk_size);
    }
}
