#ifndef AUDIO_PACKETIZER_H
#define AUDIO_PACKETIZER_H

#include <stdint.h>
#include <stddef.h>

/**
 * Audio Packetizer
 * Packs int16 audio samples into BLE notification payloads
 * Handles chunking to fit within MTU limits
 */
class AudioPacketizer {
public:
    AudioPacketizer(size_t chunk_size_bytes);
    ~AudioPacketizer();

    /**
     * Add samples to the packetizer buffer
     * @param samples Array of int16_t samples
     * @param num_samples Number of samples to add
     * @param output_buffer Buffer to write complete chunks to
     * @param output_buffer_size Size of output buffer
     * @param chunks_written Output parameter: number of complete chunks written
     * @return Number of samples successfully added
     */
    size_t addSamples(const int16_t* samples, size_t num_samples,
                     uint8_t* output_buffer, size_t output_buffer_size,
                     size_t& chunks_written);

    /**
     * Flush any remaining partial chunk
     * @param output_buffer Buffer to write the chunk to
     * @param output_buffer_size Size of output buffer
     * @return Number of bytes in the flushed chunk, or 0 if nothing to flush
     */
    size_t flush(uint8_t* output_buffer, size_t output_buffer_size);

    /**
     * Reset the internal buffer (discard any partial chunk)
     */
    void reset();

    /**
     * Get the configured chunk size in bytes
     */
    size_t getChunkSize() const { return chunk_size; }

private:
    size_t chunk_size;
    uint8_t* buffer;
    size_t buffer_pos;
};

#endif // AUDIO_PACKETIZER_H
