#ifndef I2S_MIC_H
#define I2S_MIC_H

#include <driver/i2s.h>
#include <stdint.h>
#include <stddef.h>

/**
 * I2S Microphone Interface
 * Handles initialization, reading, and stopping of I2S microphone (INMP441)
 */
class I2SMic {
public:
    I2SMic();
    ~I2SMic();

    /**
     * Initialize I2S driver and pins
     * @return true if successful, false otherwise
     */
    bool init();

    /**
     * Read audio samples from I2S
     * @param buffer Buffer to store samples (int16_t array)
     * @param buffer_len Number of samples to read
     * @return Number of samples actually read, or 0 on error
     */
    size_t read(int16_t* buffer, size_t buffer_len);

    /**
     * Stop I2S driver (cleanup)
     */
    void stop();

    /**
     * Check if I2S is initialized
     * @return true if initialized
     */
    bool isInitialized() const { return initialized; }

private:
    bool initialized;
    i2s_port_t port;
};

#endif // I2S_MIC_H
