/**
 * @file walter_spiffs.h
 * @brief SPIFFS filesystem utilities for Walter modem applications
 *
 * @section DESCRIPTION
 *
 * Provides functions for initializing SPIFFS, listing files, loading
 * configuration from JSON, and creating custom SPIFFS stream elements
 * for ESP-ADF audio pipelines.
 */

#ifndef WALTER_SPIFFS_H
#define WALTER_SPIFFS_H

#include <cstddef>
#include "audio_element.h"

namespace spiffs {

/**
 * @brief Initialize the SPIFFS filesystem
 * 
 * Sets up SPIFFS with the following configuration:
 * - Base path: /spiffs
 * - Partition label: storage
 * - Max files: 5
 * - Format if mount fails: true
 */
void Init();

/**
 * @brief List all files in the SPIFFS partition
 * 
 * Prints the list of files to the console via ESP_LOGI
 */
void ListFiles();

/**
 * @brief Load OpenAI configuration from JSON file
 * 
 * Reads /spiffs/config.json and extracts OpenAI API key and model name.
 * Expected JSON format:
 * {
 *   "openai": {
 *     "api_key": "sk-...",
 *     "model": "gpt-4o-realtime-preview-2024-12-17"
 *   }
 * }
 * 
 * @param api_key Buffer to store API key (should be at least 256 bytes)
 * @param api_key_size Size of api_key buffer
 * @param model Buffer to store model name (should be at least 64 bytes)
 * @param model_size Size of model buffer
 * @return true on success, false on error
 */
bool LoadConfig(char* api_key, size_t api_key_size, 
                char* model, size_t model_size);

/**
 * @brief Create a SPIFFS stream reader element for ESP-ADF
 * 
 * Creates a custom audio element that can read audio files from SPIFFS
 * for use in ESP-ADF audio pipelines. The element handles file opening,
 * reading, and closing automatically.
 * 
 * Usage example:
 *   audio_element_handle_t spiffs_stream = spiffs::StreamInit();
 *   audio_element_set_uri(spiffs_stream, "/spiffs/audio.wav");
 *   audio_pipeline_register(pipeline, spiffs_stream, "spiffs");
 * 
 * @return Handle to the SPIFFS stream element, or NULL on error
 */
audio_element_handle_t StreamInit();

}

#endif  // WALTER_SPIFFS_H