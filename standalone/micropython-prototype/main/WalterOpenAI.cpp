/**
 * @file OpenAIRealtime.cpp
 * @author Ryder Paulson <paulson.r@northeastern.edu>
 * @brief Implementation of OpenAI Realtime API integration using WebRTC
 */

#include "WalterOpenAI.h"
#include <cJSON.h>
#include <string.h>
#include "esp_log.h"
#include "esp_websocket_client.h"
#include "freertos/FreeRTOS.h"
#include "freertos/task.h"
#include "freertos/event_groups.h"

static const char* TAG = "OPENAI-REALTIME";

namespace openai {

// Internal state management
struct RealtimeState {
    esp_websocket_client_handle_t ws_client;
    RealtimeConnectionState connection_state;
    RealtimeConfig config;
    
    // Callbacks
    audio_received_callback_t audio_callback;
    void* audio_callback_user_data;
    text_received_callback_t text_callback;
    void* text_callback_user_data;
    error_callback_t error_callback;
    void* error_callback_user_data;
    
    // Audio buffer for base64 encoding
    char* audio_base64_buffer;
    size_t audio_base64_buffer_size;
};

static RealtimeState state = {
    .ws_client = nullptr,
    .connection_state = REALTIME_DISCONNECTED,
    .config = {},
    .audio_callback = nullptr,
    .audio_callback_user_data = nullptr,
    .text_callback = nullptr,
    .text_callback_user_data = nullptr,
    .error_callback = nullptr,
    .error_callback_user_data = nullptr,
    .audio_base64_buffer = nullptr,
    .audio_base64_buffer_size = 0
};

// Base64 encoding lookup table
static const char base64_chars[] = R"EOF(
ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789+/"
)EOF";

/**
 * @brief Encode binary data to base64
 */
static size_t base64_encode(const uint8_t* input, size_t input_len, char* output) {
    size_t output_len = 0;
    uint32_t val = 0;
    int valb = -6;
    
    for (size_t i = 0; i < input_len; i++) {
        val = (val << 8) + input[i];
        valb += 8;
        while (valb >= 0) {
            output[output_len++] = base64_chars[(val >> valb) & 0x3F];
            valb -= 6;
        }
    }
    
    if (valb > -6) {
        output[output_len++] = base64_chars[((val << 8) >> (valb + 8)) & 0x3F];
    }
    
    while (output_len % 4) {
        output[output_len++] = '=';
    }
    
    output[output_len] = '\0';
    return output_len;
}

/**
 * @brief Decode base64 to binary data
 */
static size_t base64_decode(const char* input, size_t input_len, uint8_t* output) {
    // Build decode table at runtime for C++ compatibility
    static uint8_t decode_table[256];
    static bool table_initialized = false;
    
    if (!table_initialized) {
        memset(decode_table, 0, sizeof(decode_table));
        for (int i = 0; i < 26; i++) {
            decode_table['A' + i] = i;
            decode_table['a' + i] = i + 26;
        }
        for (int i = 0; i < 10; i++) {
            decode_table['0' + i] = i + 52;
        }
        decode_table['+'] = 62;
        decode_table['/'] = 63;
        table_initialized = true;
    }
    
    size_t output_len = 0;
    uint32_t val = 0;
    int valb = -8;
    
    for (size_t i = 0; i < input_len; i++) {
        if (input[i] == '=') break;
        val = (val << 6) + decode_table[(uint8_t)input[i]];
        valb += 6;
        if (valb >= 0) {
            output[output_len++] = (val >> valb) & 0xFF;
            valb -= 8;
        }
    }
    
    return output_len;
}

/**
 * @brief Create a JSON event for the Realtime API
 */
static cJSON* createEvent(const char* event_type)
{
    cJSON* event = cJSON_CreateObject();
    cJSON_AddStringToObject(event, "type", event_type);
    return event;
}

/**
 * @brief Send a JSON event to the WebSocket
 */
static bool sendEvent(cJSON* event)
{
    if (state.ws_client == nullptr || state.connection_state != REALTIME_CONNECTED) {
        ESP_LOGE(TAG, "Cannot send event: not connected");
        cJSON_Delete(event);
        return false;
    }
    
    char* json_string = cJSON_PrintUnformatted(event);
    if (json_string == nullptr) {
        ESP_LOGE(TAG, "Failed to serialize JSON event");
        cJSON_Delete(event);
        return false;
    }
    
    ESP_LOGD(TAG, "Sending event: %s", json_string);
    
    int ret = esp_websocket_client_send_text(state.ws_client, json_string, 
                                              strlen(json_string), portMAX_DELAY);
    
    free(json_string);
    cJSON_Delete(event);
    
    if (ret < 0) {
        ESP_LOGE(TAG, "Failed to send WebSocket message");
        return false;
    }
    
    return true;
}

/**
 * @brief Handle incoming WebSocket events
 */
static void websocket_event_handler(void* handler_args, 
    esp_event_base_t base,
                                     
    int32_t event_id, 
    void* event_data)
{
    esp_websocket_event_data_t* data = (esp_websocket_event_data_t*)event_data;
    
    switch (event_id) {
        case WEBSOCKET_EVENT_CONNECTED:
            ESP_LOGI(TAG, "WebSocket connected");
            state.connection_state = REALTIME_CONNECTED;
            break;
            
        case WEBSOCKET_EVENT_DISCONNECTED:
            ESP_LOGI(TAG, "WebSocket disconnected");
            state.connection_state = REALTIME_DISCONNECTED;
            break;
            
        case WEBSOCKET_EVENT_DATA:
            if (data->op_code == 0x01) { // Text frame
                ESP_LOGD(TAG, "Received WebSocket data: %.*s", data->data_len, (char*)data->data_ptr);
                
                // Parse JSON response
                cJSON* json = cJSON_ParseWithLength((const char*)data->data_ptr, data->data_len);
                if (json == nullptr) {
                    ESP_LOGE(TAG, "Failed to parse JSON response");
                    break;
                }
                
                cJSON* type_item = cJSON_GetObjectItem(json, "type");
                if (type_item == nullptr || !cJSON_IsString(type_item)) {
                    cJSON_Delete(json);
                    break;
                }
                
                const char* type = type_item->valuestring;
                ESP_LOGI(TAG, "Received event type: %s", type);
                
                // Handle different event types
                if (strcmp(type, "response.audio.delta") == 0) {
                    cJSON* delta = cJSON_GetObjectItem(json, "delta");
                    if (delta != nullptr && cJSON_IsString(delta)) {
                        const char* audio_base64 = delta->valuestring;
                        size_t audio_len = strlen(audio_base64);
                        
                        // Decode base64 audio
                        uint8_t* audio_data = (uint8_t*)malloc(audio_len);
                        if (audio_data != nullptr) {
                            size_t decoded_len = base64_decode(audio_base64, audio_len, audio_data);
                            
                            if (state.audio_callback != nullptr) {
                                state.audio_callback(audio_data, decoded_len, 
                                                    state.audio_callback_user_data);
                            }
                            
                            free(audio_data);
                        }
                    }
                }
                else if (strcmp(type, "response.text.delta") == 0) {
                    cJSON* delta = cJSON_GetObjectItem(json, "delta");
                    if (delta != nullptr && cJSON_IsString(delta)) {
                        if (state.text_callback != nullptr) {
                            state.text_callback(delta->valuestring, state.text_callback_user_data);
                        }
                    }
                }
                else if (strcmp(type, "error") == 0) {
                    cJSON* error = cJSON_GetObjectItem(json, "error");
                    if (error != nullptr) {
                        cJSON* message = cJSON_GetObjectItem(error, "message");
                        if (message != nullptr && cJSON_IsString(message)) {
                            ESP_LOGE(TAG, "API Error: %s", message->valuestring);
                            if (state.error_callback != nullptr) {
                                state.error_callback(message->valuestring, 
                                                    state.error_callback_user_data);
                            }
                        }
                    }
                }
                
                cJSON_Delete(json);
            }
            break;
            
        case WEBSOCKET_EVENT_ERROR:
            ESP_LOGE(TAG, "WebSocket error");
            state.connection_state = REALTIME_ERROR;
            if (state.error_callback != nullptr) {
                state.error_callback("WebSocket connection error", state.error_callback_user_data);
            }
            break;
            
        default:
            break;
    }
}

bool initialize(const RealtimeConfig& config)
{
    if (config.api_key == nullptr) {
        ESP_LOGE(TAG, "API key is required");
        return false;
    }
    
    // Store configuration
    state.config = config;
    
    // Set defaults if not specified
    if (state.config.model == nullptr) {
        state.config.model = OPENAI_REALTIME_MODEL;
    }
    if (state.config.voice == nullptr) {
        // Set the voice of the model
        state.config.voice = "alloy";
    }
    if (state.config.input_audio_format == nullptr) {
        // Set the input audio format of the model
        state.config.input_audio_format = "pcm16";
    }
    if (state.config.output_audio_format == nullptr) {
        // Set the output audio foramt of the model
        state.config.output_audio_format = "pcm16";
    }
    
    // Allocate base64 buffer (4/3 size of max expected audio chunk)
    state.audio_base64_buffer_size = 65536; // 64KB should be enough
    state.audio_base64_buffer = (char*)malloc(state.audio_base64_buffer_size);
    if (state.audio_base64_buffer == nullptr) {
        ESP_LOGE(TAG, "Failed to allocate base64 buffer");
        return false;
    }
    
    ESP_LOGI(TAG, "OpenAI Realtime API initialized");
    return true;
}

bool connect()
{
    if (state.config.api_key == nullptr) {
        ESP_LOGE(TAG, "Not initialized");
        return false;
    }
    
    // Build WebSocket URL with query parameters
    char url[512];
    snprintf(url, sizeof(url), "%s?model=%s", OPENAI_REALTIME_WS_URL, state.config.model);
    
    // Configure WebSocket client
    esp_websocket_client_config_t ws_cfg = {};
    ws_cfg.uri = url;
    ws_cfg.task_stack = 8192;
    ws_cfg.buffer_size = 4096;
    
    // Add authorization header
    char auth_header[512];
    snprintf(auth_header, sizeof(auth_header), "Bearer %s", state.config.api_key);
    
    esp_websocket_client_handle_t client = esp_websocket_client_init(&ws_cfg);
    if (client == nullptr) {
        ESP_LOGE(TAG, "Failed to initialize WebSocket client");
        return false;
    }
    
    esp_websocket_register_events(client, WEBSOCKET_EVENT_ANY, 
                                   websocket_event_handler, nullptr);
    
    // Set authorization header
    esp_websocket_client_set_headers(client, auth_header);
    esp_websocket_client_set_headers(client, "realtime=v1");
    
    state.ws_client = client;
    state.connection_state = REALTIME_CONNECTING;
    
    esp_err_t ret = esp_websocket_client_start(client);
    if (ret != ESP_OK) {
        ESP_LOGE(TAG, "Failed to start WebSocket client: %s", esp_err_to_name(ret));
        state.connection_state = REALTIME_ERROR;
        return false;
    }
    
    // Wait for connection
    int timeout = 10000; // 10 seconds
    int elapsed = 0;
    while (state.connection_state == REALTIME_CONNECTING && elapsed < timeout) {
        vTaskDelay(pdMS_TO_TICKS(100));
        elapsed += 100;
    }
    
    if (state.connection_state != REALTIME_CONNECTED) {
        ESP_LOGE(TAG, "Connection timeout");
        return false;
    }
    
    // Send session configuration
    return updateSession(state.config);
}

void disconnect()
{
    if (state.ws_client != nullptr) {
        esp_websocket_client_stop(state.ws_client);
        esp_websocket_client_destroy(state.ws_client);
        state.ws_client = nullptr;
    }
    
    if (state.audio_base64_buffer != nullptr) {
        free(state.audio_base64_buffer);
        state.audio_base64_buffer = nullptr;
    }
    
    state.connection_state = REALTIME_DISCONNECTED;
    ESP_LOGI(TAG, "Disconnected from OpenAI Realtime API");
}

RealtimeConnectionState getConnectionState()
{
    return state.connection_state;
}

bool sendAudio(const uint8_t* audio_data, size_t length)
{
    if (state.connection_state != REALTIME_CONNECTED) {
        ESP_LOGE(TAG, "Not connected");
        return false;
    }
    
    // Encode audio to base64
    size_t base64_len = base64_encode(audio_data, length, state.audio_base64_buffer);
    
    // Create event
    cJSON* event = createEvent("input_audio_buffer.append");
    cJSON_AddStringToObject(event, "audio", state.audio_base64_buffer);
    
    return sendEvent(event);
}

bool sendWavFile(const uint8_t* wav_data, size_t wav_size)
{
    // Skip WAV header (typically 44 bytes for standard PCM WAV)
    const size_t wav_header_size = 44;
    
    if (wav_size <= wav_header_size) {
        ESP_LOGE(TAG, "WAV file too small");
        return false;
    }
    
    const uint8_t* pcm_data = wav_data + wav_header_size;
    size_t pcm_size = wav_size - wav_header_size;
    
    ESP_LOGI(TAG, "Sending WAV file: %d bytes of PCM data", pcm_size);
    
    // Send in chunks to avoid overwhelming the buffer
    const size_t chunk_size = 8192; // 8KB chunks
    size_t offset = 0;
    
    while (offset < pcm_size) {
        size_t send_size = (pcm_size - offset > chunk_size) ? chunk_size : (pcm_size - offset);
        
        if (!sendAudio(pcm_data + offset, send_size)) {
            ESP_LOGE(TAG, "Failed to send audio chunk at offset %d", offset);
            return false;
        }
        
        offset += send_size;
        vTaskDelay(pdMS_TO_TICKS(10)); // Small delay between chunks
    }
    
    ESP_LOGI(TAG, "WAV file sent successfully");
    return true;
}

bool commitAudioBuffer()
{
    cJSON* event = createEvent("input_audio_buffer.commit");
    return sendEvent(event);
}

bool clearAudioBuffer()
{
    cJSON* event = createEvent("input_audio_buffer.clear");
    return sendEvent(event);
}

bool sendText(const char* text)
{
    cJSON* event = createEvent("conversation.item.create");
    
    cJSON* item = cJSON_CreateObject();
    cJSON_AddStringToObject(item, "type", "message");
    cJSON_AddStringToObject(item, "role", "user");
    
    cJSON* content_array = cJSON_CreateArray();
    cJSON* content = cJSON_CreateObject();
    cJSON_AddStringToObject(content, "type", "input_text");
    cJSON_AddStringToObject(content, "text", text);
    cJSON_AddItemToArray(content_array, content);
    
    cJSON_AddItemToObject(item, "content", content_array);
    cJSON_AddItemToObject(event, "item", item);
    
    return sendEvent(event);
}

bool updateSession(const RealtimeConfig& config)
{
    cJSON* event = createEvent("session.update");
    cJSON* session = cJSON_CreateObject();
    
    // Voice
    if (config.voice != nullptr) {
        cJSON_AddStringToObject(session, "voice", config.voice);
    }
    
    // Audio formats
    if (config.input_audio_format != nullptr) {
        cJSON_AddStringToObject(session, "input_audio_format", config.input_audio_format);
    }
    if (config.output_audio_format != nullptr) {
        cJSON_AddStringToObject(session, "output_audio_format", config.output_audio_format);
    }
    
    // Instructions
    if (config.instructions != nullptr) {
        cJSON_AddStringToObject(session, "instructions", config.instructions);
    }
    
    // Turn detection
    if (config.turn_detection_enabled) {
        cJSON* turn_detection = cJSON_CreateObject();
        cJSON_AddStringToObject(turn_detection, "type", "server_vad");
        cJSON_AddNumberToObject(turn_detection, "threshold", config.vad_threshold);
        cJSON_AddNumberToObject(turn_detection, "prefix_padding_ms", config.vad_prefix_padding_ms);
        cJSON_AddNumberToObject(turn_detection, "silence_duration_ms", config.vad_silence_duration_ms);
        cJSON_AddItemToObject(session, "turn_detection", turn_detection);
    }
    
    cJSON_AddItemToObject(event, "session", session);
    
    return sendEvent(event);
}

void setAudioReceivedCallback(audio_received_callback_t callback, void* user_data)
{
    state.audio_callback = callback;
    state.audio_callback_user_data = user_data;
}

void setTextReceivedCallback(text_received_callback_t callback, void* user_data)
{
    state.text_callback = callback;
    state.text_callback_user_data = user_data;
}

void setErrorCallback(error_callback_t callback, void* user_data)
{
    state.error_callback = callback;
    state.error_callback_user_data = user_data;
}

void processEvents()
{
    // Events are handled asynchronously by the WebSocket event handler
    // This function can be used for periodic maintenance if needed
    vTaskDelay(pdMS_TO_TICKS(1));
}

bool cancelResponse()
{
    cJSON* event = createEvent("response.cancel");
    return sendEvent(event);
}

bool createResponse(const char* instructions)
{
    cJSON* event = createEvent("response.create");
    
    if (instructions != nullptr) {
        cJSON* response = cJSON_CreateObject();
        cJSON_AddStringToObject(response, "instructions", instructions);
        cJSON_AddItemToObject(event, "response", response);
    }
    
    return sendEvent(event);
}

} // namespace openai