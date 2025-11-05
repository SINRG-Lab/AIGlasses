/**
 * @file OpenAIRealtime.h
 * @author Ryder Paulson <paulson.r@northeastern.edu>
 * @brief Header for OpenAI Realtime API integration using WebRTC
 * 
 * @section DESCRIPTION
 * 
 * This module handles communication with OpenAI's Realtime API over WebRTC,
 * enabling bidirectional audio streaming for conversational AI interactions.
 */

#ifndef OPENAI_REALTIME_H
#define OPENAI_REALTIME_H

#include <stdint.h>
#include <stddef.h>
#include "esp_websocket_client.h"

namespace openai {

// Configuration constants
static const char* OPENAI_REALTIME_WS_URL = "wss://api.openai.com/v1/realtime";
static const char* OPENAI_REALTIME_MODEL = "gpt-4o-realtime-preview-2024-12-17";

// WebRTC connection states
enum RealtimeConnectionState {
    REALTIME_DISCONNECTED,
    REALTIME_CONNECTING,
    REALTIME_CONNECTED,
    REALTIME_ERROR
};

// Event types for the Realtime API
enum RealtimeEventType {
    EVENT_SESSION_UPDATE,
    EVENT_INPUT_AUDIO_BUFFER_APPEND,
    EVENT_INPUT_AUDIO_BUFFER_COMMIT,
    EVENT_INPUT_AUDIO_BUFFER_CLEAR,
    EVENT_RESPONSE_CREATE,
    EVENT_RESPONSE_CANCEL,
    EVENT_CONVERSATION_ITEM_CREATE,
    EVENT_CONVERSATION_ITEM_TRUNCATE,
    EVENT_CONVERSATION_ITEM_DELETE
};

// Response event types (received from API)
enum RealtimeResponseType {
    RESPONSE_SESSION_CREATED,
    RESPONSE_SESSION_UPDATED,
    RESPONSE_CONVERSATION_CREATED,
    RESPONSE_INPUT_AUDIO_BUFFER_COMMITTED,
    RESPONSE_INPUT_AUDIO_BUFFER_CLEARED,
    RESPONSE_INPUT_AUDIO_BUFFER_SPEECH_STARTED,
    RESPONSE_INPUT_AUDIO_BUFFER_SPEECH_STOPPED,
    RESPONSE_AUDIO_DELTA,
    RESPONSE_AUDIO_DONE,
    RESPONSE_TEXT_DELTA,
    RESPONSE_TEXT_DONE,
    RESPONSE_RESPONSE_CREATED,
    RESPONSE_RESPONSE_DONE,
    RESPONSE_ERROR
};

/**
 * @brief Configuration structure for the Realtime API session
 */
struct RealtimeConfig {
    const char* api_key;
    const char* model;
    const char* voice;              // alloy, echo, fable, onyx, nova, shimmer
    const char* input_audio_format;  // pcm16, g711_ulaw, g711_alaw
    const char* output_audio_format; // pcm16, g711_ulaw, g711_alaw
    bool turn_detection_enabled;
    float vad_threshold;            // Voice activity detection threshold
    int32_t vad_prefix_padding_ms;
    int32_t vad_silence_duration_ms;
    const char* instructions;       // System instructions for the model
};

/**
 * @brief Callback function type for receiving audio chunks from the API
 * 
 * @param audio_data Pointer to the audio data buffer
 * @param length Length of the audio data in bytes
 * @param user_data User-defined data pointer
 */
typedef void (*audio_received_callback_t)(const uint8_t* audio_data, size_t length, void* user_data);

/**
 * @brief Callback function type for receiving text responses from the API
 * 
 * @param text Pointer to the text string
 * @param user_data User-defined data pointer
 */
typedef void (*text_received_callback_t)(const char* text, void* user_data);

/**
 * @brief Callback function type for error handling
 * 
 * @param error_message Error message string
 * @param user_data User-defined data pointer
 */
typedef void (*error_callback_t)(const char* error_message, void* user_data);

/**
 * @brief Initialize the OpenAI Realtime API client
 * 
 * @param config Configuration structure with API key and session settings
 * @return true if initialization successful, false otherwise
 */
bool initialize(const RealtimeConfig& config);

/**
 * @brief Connect to the OpenAI Realtime API WebSocket
 * 
 * @return true if connection successful, false otherwise
 */
bool connect();

/**
 * @brief Disconnect from the OpenAI Realtime API
 */
void disconnect();

/**
 * @brief Get the current connection state
 * 
 * @return Current RealtimeConnectionState
 */
RealtimeConnectionState getConnectionState();

/**
 * @brief Send audio data to the API (streaming input)
 * 
 * @param audio_data Pointer to PCM16 audio data buffer
 * @param length Length of audio data in bytes
 * @return true if audio sent successfully, false otherwise
 */
bool sendAudio(const uint8_t* audio_data, size_t length);

/**
 * @brief Send audio data from a WAV file
 * 
 * @param wav_data Complete WAV file data including header
 * @param wav_size Size of the WAV file in bytes
 * @return true if audio sent successfully, false otherwise
 */
bool sendWavFile(const uint8_t* wav_data, size_t wav_size);

/**
 * @brief Commit the audio buffer and trigger a response
 * 
 * This signals to the API that you've finished sending audio input
 * and want it to generate a response.
 * 
 * @return true if successful, false otherwise
 */
bool commitAudioBuffer();

/**
 * @brief Clear the input audio buffer
 * 
 * @return true if successful, false otherwise
 */
bool clearAudioBuffer();

/**
 * @brief Send a text message to the conversation
 * 
 * @param text Text message to send
 * @return true if successful, false otherwise
 */
bool sendText(const char* text);

/**
 * @brief Update session configuration
 * 
 * @param config New configuration settings
 * @return true if successful, false otherwise
 */
bool updateSession(const RealtimeConfig& config);

/**
 * @brief Register callback for receiving audio responses
 * 
 * @param callback Function to call when audio is received
 * @param user_data Optional user data to pass to callback
 */
void setAudioReceivedCallback(audio_received_callback_t callback, void* user_data = nullptr);

/**
 * @brief Register callback for receiving text responses
 * 
 * @param callback Function to call when text is received
 * @param user_data Optional user data to pass to callback
 */
void setTextReceivedCallback(text_received_callback_t callback, void* user_data = nullptr);

/**
 * @brief Register callback for error handling
 * 
 * @param callback Function to call when an error occurs
 * @param user_data Optional user data to pass to callback
 */
void setErrorCallback(error_callback_t callback, void* user_data = nullptr);

/**
 * @brief Process incoming events (call regularly in main loop)
 * 
 * This function should be called periodically to process incoming
 * WebSocket messages and trigger appropriate callbacks.
 */
void processEvents();

/**
 * @brief Cancel the current response generation
 * 
 * @return true if successful, false otherwise
 */
bool cancelResponse();

/**
 * @brief Create a response manually (without committing audio buffer)
 * 
 * @param instructions Optional instructions for this specific response
 * @return true if successful, false otherwise
 */
bool createResponse(const char* instructions = nullptr);

} // namespace openai

#endif // OPENAI_REALTIME_H