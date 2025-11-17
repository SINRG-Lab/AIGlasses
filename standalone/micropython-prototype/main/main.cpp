/**
 * @file main.cpp
 * @author Ryder Paulson <paulson.r@northeastern.edu>
 * @brief 
 *
 * @section DESCRIPTION
 *
 * 
 */

// Standard C components
#include <stdio.h>
#include <string.h>
#include <dirent.h>
#include <sys/stat.h>

// FreeRTOS components
#include "freertos/FreeRTOS.h"
#include "freertos/task.h"

// ESP components
#include "esp_system.h"
#include "esp_log.h"
#include "esp_websocket_client.h"
#include "esp_spiffs.h"
#include "driver/uart.h"
#include "audio_pipeline.h"
#include "audio_element.h"
#include "mp3_decoder.h"
#include "wav_decoder.h"
#include "cJSON.h"

// Walter modem component from 
#include "WalterModem.h"

// Unique component to this project
#include "WalterCOMM.h"

// Include local WiFi credentials file (not tracked by git)
#include "WiFiCredentials.h"

// The TLS profile to use for the application
static const int HTTPS_TLS_PROFILE = 2;

// The HTTPS Profile
static const int MODEM_HTTPS_PROFILE = 1;

static const char* TAG = "MAIN";

/**
 * @brief Inititializes the on board storage for the config and audio file.
 */
void init_spiffs(void) 
{
    esp_vfs_spiffs_conf_t conf = {
        .base_path = "/spiffs",
        .partition_label = "storage",
        .max_files = 5,
        .format_if_mount_failed = true
    };
    
    esp_err_t ret = esp_vfs_spiffs_register(&conf);
    
    // Check that the spiffs partition was registered correctly
    if (ret != ESP_OK) {
        if (ret == ESP_FAIL) {
            ESP_LOGE(TAG, "Failed to mount or format filesystem");
        } else if (ret == ESP_ERR_NOT_FOUND) {
            ESP_LOGE(TAG, "Failed to find SPIFFS partition");
        } else {
            ESP_LOGE(TAG, "Failed to initialize SPIFFS (%s)", esp_err_to_name(ret));
        }
        return;
    }
    
    size_t total = 0, used = 0;
    ret = esp_spiffs_info("storage", &total, &used);
    if (ret == ESP_OK) {
        ESP_LOGI(TAG, "Partition size: total: %d, used: %d", total, used);
    }
}


void list_spiffs_files(void)
{
    DIR *dir = opendir("/spiffs");
    if (dir == NULL) {
        ESP_LOGE(TAG, "Failed to open directory");
        return;
    }
    
    struct dirent *entry;
    ESP_LOGI(TAG, "Files in SPIFFS:");
    while ((entry = readdir(dir)) != NULL) {
        ESP_LOGI(TAG, "  - %s", entry->d_name);
    }
    closedir(dir);
}

/**
 * @brief Custom SPIFFS stream element for ESP-ADF pipeline
 */

// Stream element callback for reading from SPIFFS
static int _spiffs_read(audio_element_handle_t el, char *buffer, int len, 
                        TickType_t wait_time, void *ctx)
{
    FILE* file = (FILE*)audio_element_getdata(el);
    if (!file) {
        return AEL_IO_FAIL;
    }
    
    int bytes_read = fread(buffer, 1, len, file);
    if (bytes_read == 0) {
        if (feof(file)) {
            return AEL_IO_DONE; // End of file
        }
        return AEL_IO_FAIL; // Error
    }
    
    return bytes_read;
}

// Stream element callback for opening file
static esp_err_t _spiffs_open(audio_element_handle_t el)
{
    const char* uri = audio_element_get_uri(el);
    if (!uri) {
        ESP_LOGE(TAG, "No URI provided");
        return ESP_FAIL;
    }
    
    ESP_LOGI(TAG, "Opening SPIFFS file: %s", uri);
    FILE* file = fopen(uri, "rb");
    if (!file) {
        ESP_LOGE(TAG, "Failed to open file: %s", uri);
        return ESP_FAIL;
    }
    
    audio_element_setdata(el, file);
    return ESP_OK;
}

// Stream element callback for closing file
static esp_err_t _spiffs_close(audio_element_handle_t el)
{
    FILE* file = (FILE*)audio_element_getdata(el);
    if (file) {
        fclose(file);
        audio_element_setdata(el, NULL);
    }
    return ESP_OK;
}

/**
 * @brief Create a SPIFFS stream reader element
 */
audio_element_handle_t spiffs_stream_init()
{
    audio_element_cfg_t cfg = DEFAULT_AUDIO_ELEMENT_CONFIG();
    cfg.open = _spiffs_open;
    cfg.close = _spiffs_close;
    cfg.read = _spiffs_read;
    cfg.process = NULL;
    cfg.destroy = NULL;
    cfg.task_stack = 2048;
    cfg.task_prio = 5;
    cfg.task_core = 0;
    cfg.out_rb_size = 8 * 1024;
    cfg.tag = "spiffs";
    
    audio_element_handle_t el = audio_element_init(&cfg);
    if (!el) {
        ESP_LOGE(TAG, "Failed to create SPIFFS stream element");
        return NULL;
    }
    
    return el;
}

/**
 * @brief Read and decode audio file using ESP-ADF pipeline with SPIFFS
 * 
 * @param file_path Path to audio file in SPIFFS (e.g., "/spiffs/audio.mp3")
 * @return true on success, false on error
 */
bool send_audio_file(const char* file_path)
{
    ESP_LOGI(TAG, "Creating audio pipeline for file: %s", file_path);

    // Create audio pipeline
    audio_pipeline_cfg_t pipeline_cfg = DEFAULT_AUDIO_PIPELINE_CONFIG();
    audio_pipeline_handle_t pipeline = audio_pipeline_init(&pipeline_cfg);
    if (!pipeline) {
        ESP_LOGE(TAG, "Failed to create pipeline");
        return false;
    }

    // Create SPIFFS stream reader
    audio_element_handle_t spiffs_reader = spiffs_stream_init();
    if (!spiffs_reader) {
        ESP_LOGE(TAG, "Failed to create SPIFFS reader");
        audio_pipeline_deinit(pipeline);
        return false;
    }

    // Determine file type and create decoder
    audio_element_handle_t decoder = NULL;
    const char* file_ext = strrchr(file_path, '.');
    
    if (file_ext && strcmp(file_ext, ".mp3") == 0) {
        mp3_decoder_cfg_t mp3_cfg = DEFAULT_MP3_DECODER_CONFIG();
        decoder = mp3_decoder_init(&mp3_cfg);
        ESP_LOGI(TAG, "Using MP3 decoder");
    } 
    else if (file_ext && strcmp(file_ext, ".wav") == 0) {
        wav_decoder_cfg_t wav_cfg = DEFAULT_WAV_DECODER_CONFIG();
        decoder = wav_decoder_init(&wav_cfg);
        ESP_LOGI(TAG, "Using WAV decoder");
    }
    else {
        ESP_LOGE(TAG, "Unsupported file format: %s", file_ext ? file_ext : "unknown");
        audio_element_deinit(spiffs_reader);
        audio_pipeline_deinit(pipeline);
        return false;
    }

    if (!decoder) {
        ESP_LOGE(TAG, "Failed to create decoder");
        audio_element_deinit(spiffs_reader);
        audio_pipeline_deinit(pipeline);
        return false;
    }

    // Register elements to pipeline
    audio_pipeline_register(pipeline, spiffs_reader, "spiffs");
    audio_pipeline_register(pipeline, decoder, "decoder");

    // Link elements: spiffs -> decoder
    const char *link_tag[2] = {"spiffs", "decoder"};
    audio_pipeline_link(pipeline, &link_tag[0], 2);

    // Set the file URI
    audio_element_set_uri(spiffs_reader, file_path);

    // Start the pipeline
    audio_pipeline_run(pipeline);

    // Read decoded PCM data and send to OpenAI
    // 0.1s of 24kHz mono PCM16
    const size_t chunk_size = 4800;
    uint8_t* buffer = (uint8_t*)malloc(chunk_size);
    if (!buffer) {
        ESP_LOGE(TAG, "Failed to allocate buffer");
        audio_pipeline_stop(pipeline);
        audio_pipeline_wait_for_stop(pipeline);
        audio_pipeline_terminate(pipeline);
        audio_pipeline_unregister(pipeline, spiffs_reader);
        audio_pipeline_unregister(pipeline, decoder);
        audio_element_deinit(spiffs_reader);
        audio_element_deinit(decoder);
        audio_pipeline_deinit(pipeline);
        return false;
    }

    bool success = true;
    size_t total_sent = 0;
    int bytes_read;

    ESP_LOGI(TAG, "Reading and sending decoded audio...");

    // Read decoded audio from the decoder element
    while ((bytes_read = audio_element_output(decoder, (char*)buffer, chunk_size)) > 0) {
        // Send chunk to OpenAI
        if (!comm::realtimeSendAudio(buffer, bytes_read)) {
            ESP_LOGE(TAG, "Failed to send audio chunk");
            success = false;
            break;
        }

        total_sent += bytes_read;
        if (total_sent % 48000 == 0) { // Log every second
            ESP_LOGI(TAG, "Sent %u bytes", total_sent);
        }

        vTaskDelay(pdMS_TO_TICKS(10));
    }

    // Commit audio buffer
    if (success) {
        ESP_LOGI(TAG, "Audio complete (%u bytes). Committing...", total_sent);
        if (!comm::realtimeCommitAudio()) {
            ESP_LOGE(TAG, "Failed to commit audio");
            success = false;
        } else {
            ESP_LOGI(TAG, "Audio committed successfully");
        }
    }

    // Cleanup
    free(buffer);
    audio_pipeline_stop(pipeline);
    audio_pipeline_wait_for_stop(pipeline);
    audio_pipeline_terminate(pipeline);
    
    audio_pipeline_unregister(pipeline, spiffs_reader);
    audio_pipeline_unregister(pipeline, decoder);
    
    audio_element_deinit(spiffs_reader);
    audio_element_deinit(decoder);
    audio_pipeline_deinit(pipeline);

    ESP_LOGI(TAG, "Pipeline cleaned up");
    return success;
}



/**
 * @brief Main application entry point
 */
extern "C" void app_main(void) 
{
    ESP_LOGI(TAG, "\n\n=== Networked-5G-AI-Glasses ===\n");

    // Wait for system to stabilize
    vTaskDelay(pdMS_TO_TICKS(2000));

    // Initilize and verify on-board storage
    init_spiffs();
    list_spiffs_files();

    const int https_port = 443;

    // Test routing info
    const char* example_https_host = "quickspot.io";
    const char* example_https_post_endpoint = "/hello/post";

    // Local modem response object
    static WalterModemRsp rsp = {};

    // Start the modem - Using UART2 (same as Arduino Serial2)
    if (WalterModem::begin(static_cast<uart_port_t>(UART_NUM_2))) {
        ESP_LOGI(TAG, "Successfully initialized the modem");
    } else {
        ESP_LOGE(TAG, "Could not initialize the modem");
        return;
    }

    // Network connection characteristics 
    const char* network_ssid = WIFI_SSID;
    const char* network_password = WIFI_PASSWORD;
    const int timeout_ms = 30000;

    // Connect to internet
    if(!comm::wifi_connect(network_ssid, network_password, timeout_ms)) {
        ESP_LOGE(TAG, "Could not connect to WiFi");
        ESP_LOGE(TAG, "Attempting to connect to LTE");
        if(!comm::lteConnect()) {
            ESP_LOGE(TAG, "Could not connect to LTE");
            return;
        }
    }

    // Check the quality of the network connection
    if(comm::modem.getCellInformation(WALTER_MODEM_SQNMONI_REPORTS_SERVING_CELL, &rsp)) {
        WalterModemCellInformation &cellInfo = rsp.data.cellInformation;

        ESP_LOGI(TAG, "Cell Information:");
        ESP_LOGI(TAG, "-> netName: %s", cellInfo.netName);
        ESP_LOGI(TAG, "-> cc: %u", cellInfo.cc);
        ESP_LOGI(TAG, "-> nc: %u", cellInfo.nc);
        ESP_LOGI(TAG, "-> rsrp: %.2f", cellInfo.rsrp);
        ESP_LOGI(TAG, "-> cinr: %.2f", cellInfo.cinr);
        ESP_LOGI(TAG, "-> rsrq: %.2f", cellInfo.rsrq);
        ESP_LOGI(TAG, "-> tac: %u", cellInfo.tac);
        ESP_LOGI(TAG, "-> pci: %u", cellInfo.pci);
        ESP_LOGI(TAG, "-> earfcn: %u", cellInfo.earfcn);
        ESP_LOGI(TAG, "-> rssi: %.2f", cellInfo.rssi);
        ESP_LOGI(TAG, "-> paging: %u", cellInfo.paging);
        ESP_LOGI(TAG, "-> cid: %u", cellInfo.cid);
        ESP_LOGI(TAG, "-> band: %u", cellInfo.band);
        ESP_LOGI(TAG, "-> bw: %u", cellInfo.bw);
        ESP_LOGI(TAG, "-> ceLevel: %u", cellInfo.ceLevel);
    } else {
        ESP_LOGI(TAG, "Failed to get cell information.");
    }

    // Set up the TLS profile
    if(comm::setupTLSProfile(HTTPS_TLS_PROFILE)) {
        ESP_LOGI(TAG, "TLS Profile setup succeeded");
    } else {
        ESP_LOGE(TAG, "TLS Profile setup failed");
        return;
    }

    // Configure the HTTPS profile
    if(comm::modem.httpConfigProfile(MODEM_HTTPS_PROFILE, example_https_host, https_port, HTTPS_TLS_PROFILE)) {
        ESP_LOGI(TAG, "Successfully configured the HTTPS profile");
    } else {
        ESP_LOGE(TAG, "Failed to configure HTTPS profile");
    }

    // Open WebSocket connection
    ESP_LOGI(TAG, "Setting up WebSocket TLS profile...");
    if (!comm::setupWebSocketTLS(HTTPS_TLS_PROFILE)) {
        ESP_LOGE(TAG, "Failed to setup WebSocket TLS");
        return;
    }

    const char* openai_api_key = "sk-your-api-key-here"; // Replace with your actual API key
    const char* openai_model = "gpt-4o-realtime-preview-2024-12-17";

    ESP_LOGI(TAG, "Connecting to OpenAI Realtime API...");
    if (!comm::realtimeConnect(openai_api_key, openai_model)) {
        ESP_LOGE(TAG, "Failed to connect to OpenAI Realtime API");
        return;
    }

    ESP_LOGI(TAG, "Successfully connected to OpenAI Realtime API");

    // Send audio to OpenAI in Chunks to reduce memory usage.
    const char* audio_file = "/spiffs/msg.wav"; // Or .wav file

    ESP_LOGI(TAG, "Sending audio file: %s", audio_file);
    if (send_audio_file(audio_file)) {
        ESP_LOGI(TAG, "Audio file sent successfully");
        
        // Request response generation
        ESP_LOGI(TAG, "Requesting response generation...");
        if (comm::realtimeGenerateResponse()) {
            ESP_LOGI(TAG, "Response generation requested");
            
            // Poll for response from OpenAI
            uint8_t response_buffer[4096];
            size_t response_len;
            bool response_received = false;
            bool audio_received = false;
            int audio_chunk_count = 0;
            size_t total_audio_bytes = 0;
            
            ESP_LOGI(TAG, "Waiting for response from OpenAI...");
            for (int i = 0; i < 60; i++) {  // Poll for up to 60 seconds
                if (comm::wsReceive(response_buffer, sizeof(response_buffer) - 1, &response_len)) {
                    response_buffer[response_len] = '\0'; // Null terminate
                    
                    ESP_LOGI(TAG, "Received message (%u bytes)", response_len);
                    
                    // Parse JSON response
                    cJSON *json = cJSON_Parse((const char*)response_buffer);
                    if (json == NULL) {
                        const char *error_ptr = cJSON_GetErrorPtr();
                        if (error_ptr != NULL) {
                            ESP_LOGE(TAG, "JSON parse error before: %s", error_ptr);
                        }
                        ESP_LOGW(TAG, "Failed to parse JSON, raw data: %s", response_buffer);
                        continue;
                    }
                    
                    // Get message type
                    cJSON *type = cJSON_GetObjectItem(json, "type");
                    if (type == NULL || !cJSON_IsString(type)) {
                        ESP_LOGW(TAG, "Message missing 'type' field");
                        cJSON_Delete(json);
                        continue;
                    }
                    
                    const char* type_str = type->valuestring;
                    
                    // Handle different message types
                    if (strcmp(type_str, "session.created") == 0) {
                        ESP_LOGI(TAG, "✓ Session created successfully");
                        
                        cJSON *session = cJSON_GetObjectItem(json, "session");
                        if (session) {
                            cJSON *id = cJSON_GetObjectItem(session, "id");
                            if (id && cJSON_IsString(id)) {
                                ESP_LOGI(TAG, "  Session ID: %s", id->valuestring);
                            }
                            
                            cJSON *model = cJSON_GetObjectItem(session, "model");
                            if (model && cJSON_IsString(model)) {
                                ESP_LOGI(TAG, "  Model: %s", model->valuestring);
                            }
                            
                            cJSON *modalities = cJSON_GetObjectItem(session, "modalities");
                            if (modalities && cJSON_IsArray(modalities)) {
                                ESP_LOGI(TAG, "  Modalities:");
                                cJSON *modality = NULL;
                                cJSON_ArrayForEach(modality, modalities) {
                                    if (cJSON_IsString(modality)) {
                                        ESP_LOGI(TAG, "    - %s", modality->valuestring);
                                    }
                                }
                            }
                        }
                    }
                    else if (strcmp(type_str, "input_audio_buffer.committed") == 0) {
                        ESP_LOGI(TAG, "✓ Audio buffer committed");
                        
                        cJSON *item_id = cJSON_GetObjectItem(json, "item_id");
                        if (item_id && cJSON_IsString(item_id)) {
                            ESP_LOGI(TAG, "  Item ID: %s", item_id->valuestring);
                        }
                    }
                    else if (strcmp(type_str, "conversation.item.created") == 0) {
                        ESP_LOGI(TAG, "✓ Conversation item created");
                        
                        cJSON *item = cJSON_GetObjectItem(json, "item");
                        if (item) {
                            cJSON *role = cJSON_GetObjectItem(item, "role");
                            if (role && cJSON_IsString(role)) {
                                ESP_LOGI(TAG, "  Role: %s", role->valuestring);
                            }
                            
                            cJSON *id = cJSON_GetObjectItem(item, "id");
                            if (id && cJSON_IsString(id)) {
                                ESP_LOGI(TAG, "  Item ID: %s", id->valuestring);
                            }
                            
                            cJSON *type_item = cJSON_GetObjectItem(item, "type");
                            if (type_item && cJSON_IsString(type_item)) {
                                ESP_LOGI(TAG, "  Type: %s", type_item->valuestring);
                            }
                        }
                    }
                    else if (strcmp(type_str, "response.created") == 0) {
                        ESP_LOGI(TAG, "✓ Response generation started");
                        
                        cJSON *response = cJSON_GetObjectItem(json, "response");
                        if (response) {
                            cJSON *id = cJSON_GetObjectItem(response, "id");
                            if (id && cJSON_IsString(id)) {
                                ESP_LOGI(TAG, "  Response ID: %s", id->valuestring);
                            }
                            
                            cJSON *status = cJSON_GetObjectItem(response, "status");
                            if (status && cJSON_IsString(status)) {
                                ESP_LOGI(TAG, "  Status: %s", status->valuestring);
                            }
                        }
                    }
                    else if (strcmp(type_str, "response.output_item.added") == 0) {
                        ESP_LOGI(TAG, "✓ Output item added");
                        
                        cJSON *item = cJSON_GetObjectItem(json, "item");
                        if (item) {
                            cJSON *content_type = cJSON_GetObjectItem(item, "type");
                            if (content_type && cJSON_IsString(content_type)) {
                                ESP_LOGI(TAG, "  Content type: %s", content_type->valuestring);
                            }
                        }
                    }
                    else if (strcmp(type_str, "response.audio.delta") == 0) {
                        if (!audio_received) {
                            ESP_LOGI(TAG, "✓ Receiving audio response...");
                            audio_received = true;
                        }
                        
                        audio_chunk_count++;
                        
                        cJSON *delta = cJSON_GetObjectItem(json, "delta");
                        if (delta && cJSON_IsString(delta)) {
                            size_t base64_len = strlen(delta->valuestring);
                            size_t decoded_estimate = (base64_len * 3) / 4;
                            total_audio_bytes += decoded_estimate;
                            
                            ESP_LOGI(TAG, "  Audio chunk #%d: %u bytes base64 (~%u bytes PCM)",
                                    audio_chunk_count, base64_len, decoded_estimate);
                        }
                        
                        cJSON *content_index = cJSON_GetObjectItem(json, "content_index");
                        if (content_index && cJSON_IsNumber(content_index)) {
                            ESP_LOGI(TAG, "  Content index: %d", content_index->valueint);
                        }
                    }
                    else if (strcmp(type_str, "response.audio.done") == 0) {
                        ESP_LOGI(TAG, "✓ Audio response complete");
                        ESP_LOGI(TAG, "  Total chunks: %d", audio_chunk_count);
                        ESP_LOGI(TAG, "  Total audio: ~%u bytes PCM", total_audio_bytes);
                    }
                    else if (strcmp(type_str, "response.content_part.done") == 0) {
                        ESP_LOGI(TAG, "✓ Content part complete");
                        
                        cJSON *part = cJSON_GetObjectItem(json, "part");
                        if (part) {
                            cJSON *part_type = cJSON_GetObjectItem(part, "type");
                            if (part_type && cJSON_IsString(part_type)) {
                                ESP_LOGI(TAG, "  Part type: %s", part_type->valuestring);
                            }
                            
                            // If it's text, show the transcript
                            if (part_type && strcmp(part_type->valuestring, "text") == 0) {
                                cJSON *text = cJSON_GetObjectItem(part, "text");
                                if (text && cJSON_IsString(text)) {
                                    ESP_LOGI(TAG, "  Text: %s", text->valuestring);
                                }
                            }
                            
                            // If it's audio, show the transcript
                            if (part_type && strcmp(part_type->valuestring, "audio") == 0) {
                                cJSON *transcript = cJSON_GetObjectItem(part, "transcript");
                                if (transcript && cJSON_IsString(transcript)) {
                                    ESP_LOGI(TAG, "  Transcript: %s", transcript->valuestring);
                                }
                            }
                        }
                    }
                    else if (strcmp(type_str, "response.done") == 0) {
                        ESP_LOGI(TAG, "✓ Response generation complete");
                        
                        cJSON *response = cJSON_GetObjectItem(json, "response");
                        if (response) {
                            cJSON *status = cJSON_GetObjectItem(response, "status");
                            if (status && cJSON_IsString(status)) {
                                ESP_LOGI(TAG, "  Status: %s", status->valuestring);
                            }
                            
                            // Extract usage statistics
                            cJSON *usage = cJSON_GetObjectItem(response, "usage");
                            if (usage) {
                                ESP_LOGI(TAG, "Usage statistics:");
                                
                                cJSON *input_tokens = cJSON_GetObjectItem(usage, "input_tokens");
                                if (input_tokens && cJSON_IsNumber(input_tokens)) {
                                    ESP_LOGI(TAG, "  Input tokens: %d", input_tokens->valueint);
                                }
                                
                                cJSON *output_tokens = cJSON_GetObjectItem(usage, "output_tokens");
                                if (output_tokens && cJSON_IsNumber(output_tokens)) {
                                    ESP_LOGI(TAG, "  Output tokens: %d", output_tokens->valueint);
                                }
                                
                                cJSON *total_tokens = cJSON_GetObjectItem(usage, "total_tokens");
                                if (total_tokens && cJSON_IsNumber(total_tokens)) {
                                    ESP_LOGI(TAG, "  Total tokens: %d", total_tokens->valueint);
                                }
                                
                                cJSON *input_token_details = cJSON_GetObjectItem(usage, "input_token_details");
                                if (input_token_details) {
                                    cJSON *cached = cJSON_GetObjectItem(input_token_details, "cached_tokens");
                                    cJSON *text = cJSON_GetObjectItem(input_token_details, "text_tokens");
                                    cJSON *audio = cJSON_GetObjectItem(input_token_details, "audio_tokens");
                                    
                                    if (cached && cJSON_IsNumber(cached)) {
                                        ESP_LOGI(TAG, "    Cached tokens: %d", cached->valueint);
                                    }
                                    if (text && cJSON_IsNumber(text)) {
                                        ESP_LOGI(TAG, "    Text tokens: %d", text->valueint);
                                    }
                                    if (audio && cJSON_IsNumber(audio)) {
                                        ESP_LOGI(TAG, "    Audio tokens: %d", audio->valueint);
                                    }
                                }
                                
                                cJSON *output_token_details = cJSON_GetObjectItem(usage, "output_token_details");
                                if (output_token_details) {
                                    cJSON *text = cJSON_GetObjectItem(output_token_details, "text_tokens");
                                    cJSON *audio = cJSON_GetObjectItem(output_token_details, "audio_tokens");
                                    
                                    if (text && cJSON_IsNumber(text)) {
                                        ESP_LOGI(TAG, "    Text tokens: %d", text->valueint);
                                    }
                                    if (audio && cJSON_IsNumber(audio)) {
                                        ESP_LOGI(TAG, "    Audio tokens: %d", audio->valueint);
                                    }
                                }
                            }
                        }
                        
                        response_received = true;
                        cJSON_Delete(json);
                        break;  // Exit polling loop
                    }
                    else if (strcmp(type_str, "response.text.delta") == 0) {
                        cJSON *delta = cJSON_GetObjectItem(json, "delta");
                        if (delta && cJSON_IsString(delta)) {
                            ESP_LOGI(TAG, "  Text delta: %s", delta->valuestring);
                        }
                    }
                    else if (strcmp(type_str, "error") == 0) {
                        ESP_LOGE(TAG, "✗ Error from OpenAI:");
                        
                        cJSON *error = cJSON_GetObjectItem(json, "error");
                        if (error) {
                            cJSON *type_err = cJSON_GetObjectItem(error, "type");
                            if (type_err && cJSON_IsString(type_err)) {
                                ESP_LOGE(TAG, "  Error type: %s", type_err->valuestring);
                            }
                            
                            cJSON *code = cJSON_GetObjectItem(error, "code");
                            if (code && cJSON_IsString(code)) {
                                ESP_LOGE(TAG, "  Error code: %s", code->valuestring);
                            }
                            
                            cJSON *message = cJSON_GetObjectItem(error, "message");
                            if (message && cJSON_IsString(message)) {
                                ESP_LOGE(TAG, "  Message: %s", message->valuestring);
                            }
                            
                            cJSON *param = cJSON_GetObjectItem(error, "param");
                            if (param && cJSON_IsString(param)) {
                                ESP_LOGE(TAG, "  Parameter: %s", param->valuestring);
                            }
                        }
                        
                        cJSON_Delete(json);
                        break;
                    }
                    else {
                        // Unknown message type - log for debugging
                        ESP_LOGD(TAG, "Unknown message type: %s", type_str);
                        ESP_LOGD(TAG, "Full message: %s", response_buffer);
                    }
                    
                    cJSON_Delete(json);
                }
                vTaskDelay(pdMS_TO_TICKS(1000));
            }
            
            if (!response_received) {
                ESP_LOGW(TAG, "No complete response received within timeout period");
            } else {
                ESP_LOGI(TAG, "=== Response Summary ===");
                ESP_LOGI(TAG, "Audio chunks received: %d", audio_chunk_count);
                ESP_LOGI(TAG, "Total audio size: ~%u bytes PCM", total_audio_bytes);
                ESP_LOGI(TAG, "Successfully received complete response from OpenAI");
            }
        } else {
            ESP_LOGE(TAG, "Failed to request response generation");
        }
    } else {
        ESP_LOGE(TAG, "Failed to send audio file");
    }

    // Disconnect from OpenAI
    ESP_LOGI(TAG, "Disconnecting from OpenAI Realtime API...");
    comm::realtimeDisconnect();
    ESP_LOGI(TAG, "Disconnected from OpenAI");
}