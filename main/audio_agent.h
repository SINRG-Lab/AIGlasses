
#include <stdint.h>
#include <stddef.h>
#include <vector>
#include <optional>

namespace audio_agent {

/**
 * @brief Send and receive audio from OpenAI's realtime API. 
 * 
 * Audio can be included either as raw audio data or a file path to read audio from.
 * The format of the file can be a .wav or .mp3.
 * 
 * @param openai_api_key OpenAI API key
 * @param openai_model What model from OpenAI to use
 * @param audio_data_stream A stream of audio data (can be nullptr if using file_path)
 * @param audio_data_len Length of audio_data_stream in bytes
 * @param file_path Path to audio file (can be nullptr if using audio_data_stream)
 * @param print_response Boolean of whether to print out detailed information about 
 *                       the JSON audio response from the API.  
 * @return Optional containing raw PCM audio data from OpenAI if successful, std::nullopt on failure
 */
std::optional<std::vector<uint8_t>> SendAndRecieveAudio(
    const char* openai_api_key, 
    const char* openai_model, 
    const uint8_t* audio_data_stream,
    size_t audio_data_len,
    const char* file_path,
    bool print_response
);
}