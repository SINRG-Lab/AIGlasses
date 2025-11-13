/**
 * === Communications library for the Walter Board ===
 * @file WalterCOMM.cpp
 * @author Ryder Paulson <paulson.r@northeastern.edu>
 * @brief A set of communications tools for the Walter Board
 */
#include "esp_system.h"
#include "esp_log.h"
#include "esp_random.h"
#include "freertos/FreeRTOS.h"
#include "freertos/task.h"
#include "driver/uart.h"
#include "mbedtls/base64.h"
#include "mbedtls/sha1.h"
#include "WalterModem.h"
#include "WalterCOMM.h"
#include <string.h>

// Local response object for Modem
static WalterModemRsp rsp = {};

// Local buffer for incoming HTTPS response
static uint8_t incomingBuf[1024] = { 0 };

static const char *TAG = "COM";

/**
 * === Start of code from example ===
 * @file WalterHTTPS.h
 * @author Arnoud Devoogdt <arnoud@dptechnics.com>
 * @date 11 September 2025
 * @copyright DPTechnics bv
 * @brief HTTPS Functions for Walter Modem
 *
 * @section LICENSE
 *
 * Copyright (C) 2025, DPTechnics bv
 * All rights reserved.
 *
 * Redistribution and use in source and binary forms, with or without
 * modification, are permitted provided that the following conditions are met:
 *
 *   1. Redistributions of source code must retain the above copyright notice,
 *      this list of conditions and the following disclaimer.
 *
 *   2. Redistributions in binary form must reproduce the above copyright
 *      notice, this list of conditions and the following disclaimer in the
 *      documentation and/or other materials provided with the distribution.
 *
 *   3. Neither the name of DPTechnics bv nor the names of its contributors may
 *      be used to endorse or promote products derived from this software
 *      without specific prior written permission.
 *
 *   4. This software, with or without modification, must only be used with a
 *      Walter board from DPTechnics bv.
 *
 *   5. Any software provided in binary form under this license must not be
 *      reverse engineered, decompiled, modified and/or disassembled.
 *
 * THIS SOFTWARE IS PROVIDED BY DPTECHNICS BV "AS IS" AND ANY EXPRESS OR IMPLIED
 * WARRANTIES, INCLUDING, BUT NOT LIMITED TO, THE IMPLIED WARRANTIES OF
 * MERCHANTABILITY, NONINFRINGEMENT, AND FITNESS FOR A PARTICULAR PURPOSE ARE
 * DISCLAIMED. IN NO EVENT SHALL DPTECHNICS BV OR CONTRIBUTORS BE LIABLE FOR ANY
 * DIRECT, INDIRECT, INCIDENTAL, SPECIAL, EXEMPLARY, OR CONSEQUENTIAL DAMAGES
 * (INCLUDING, BUT NOT LIMITED TO, PROCUREMENT OF SUBSTITUTE GOODS OR SERVICES;
 * LOSS OF USE, DATA, OR PROFITS; OR BUSINESS INTERRUPTION) HOWEVER CAUSED AND
 * ON ANY THEORY OF LIABILITY, WHETHER IN CONTRACT, STRICT LIABILITY, OR TORT
 * (INCLUDING NEGLIGENCE OR OTHERWISE) ARISING IN ANY WAY OUT OF THE USE OF THIS
 * SOFTWARE, EVEN IF ADVISED OF THE POSSIBILITY OF SUCH DAMAGE.
 *
 * @section DESCRIPTION
 *
 * This file contains HTTPS POST functions extracted from the Walter Modem
 * library example code.
 * 
 * @note This code was originally derived from an example of how to connect to 
 * send HTTPS POST and GET requests within the Arduino IDE. It has been been 
 * modified to work with ESP-IDF in VSCode. 
 */

// ========================================
// CA CERTIFICATE - INSERT YOUR CERTIFICATE HERE
// ========================================
static const char ca_cert[] = R"EOF(
-----BEGIN CERTIFICATE-----
MIIFazCCA1OgAwIBAgIRAIIQz7DSQONZRGPgu2OCiwAwDQYJKoZIhvcNAQELBQAw
TzELMAkGA1UEBhMCVVMxKTAnBgNVBAoTIEludGVybmV0IFNlY3VyaXR5IFJlc2Vh
cmNoIEdyb3VwMRUwEwYDVQQDEwxJU1JHIFJvb3QgWDEwHhcNMTUwNjA0MTEwNDM4
WhcNMzUwNjA0MTEwNDM4WjBPMQswCQYDVQQGEwJVUzEpMCcGA1UEChMgSW50ZXJu
ZXQgU2VjdXJpdHkgUmVzZWFyY2ggR3JvdXAxFTATBgNVBAMTDElTUkcgUm9vdCBY
MTCCAiIwDQYJKoZIhvcNAQEBBQADggIPADCCAgoCggIBAK3oJHP0FDfzm54rVygc
h77ct984kIxuPOZXoHj3dcKi/vVqbvYATyjb3miGbESTtrFj/RQSa78f0uoxmyF+
0TM8ukj13Xnfs7j/EvEhmkvBioZxaUpmZmyPfjxwv60pIgbz5MDmgK7iS4+3mX6U
A5/TR5d8mUgjU+g4rk8Kb4Mu0UlXjIB0ttov0DiNewNwIRt18jA8+o+u3dpjq+sW
T8KOEUt+zwvo/7V3LvSye0rgTBIlDHCNAymg4VMk7BPZ7hm/ELNKjD+Jo2FR3qyH
B5T0Y3HsLuJvW5iB4YlcNHlsdu87kGJ55tukmi8mxdAQ4Q7e2RCOFvu396j3x+UC
B5iPNgiV5+I3lg02dZ77DnKxHZu8A/lJBdiB3QW0KtZB6awBdpUKD9jf1b0SHzUv
KBds0pjBqAlkd25HN7rOrFleaJ1/ctaJxQZBKT5ZPt0m9STJEadao0xAH0ahmbWn
OlFuhjuefXKnEgV4We0+UXgVCwOPjdAvBbI+e0ocS3MFEvzG6uBQE3xDk3SzynTn
jh8BCNAw1FtxNrQHusEwMFxIt4I7mKZ9YIqioymCzLq9gwQbooMDQaHWBfEbwrbw
qHyGO0aoSCqI3Haadr8faqU9GY/rOPNk3sgrDQoo//fb4hVC1CLQJ13hef4Y53CI
rU7m2Ys6xt0nUW7/vGT1M0NPAgMBAAGjQjBAMA4GA1UdDwEB/wQEAwIBBjAPBgNV
HRMBAf8EBTADAQH/MB0GA1UdDgQWBBR5tFnme7bl5AFzgAiIyBpY9umbbjANBgkq
hkiG9w0BAQsFAAOCAgEAVR9YqbyyqFDQDLHYGmkgJykIrGF1XIpu+ILlaS/V9lZL
ubhzEFnTIZd+50xx+7LSYK05qAvqFyFWhfFQDlnrzuBZ6brJFe+GnY+EgPbk6ZGQ
3BebYhtF8GaV0nxvwuo77x/Py9auJ/GpsMiu/X1+mvoiBOv/2X/qkSsisRcOj/KK
NFtY2PwByVS5uCbMiogziUwthDyC3+6WVwW6LLv3xLfHTjuCvjHIInNzktHCgKQ5
ORAzI4JMPJ+GslWYHb4phowim57iaztXOoJwTdwJx4nLCgdNbOhdjsnvzqvHu7Ur
TkXWStAmzOVyyghqpZXjFaH3pO3JLF+l+/+sKAIuvtd7u+Nxe5AW0wdeRlN8NwdC
jNPElpzVmbUq4JUagEiuTDkHzsxHpFKVK7q4+63SM1N95R1NbdWhscdCb+ZAJzVc
oyi3B43njTOQ5yOf+1CceWxG1bQVs5ZufpsMljq4Ui0/1lvh+wjChP4kqKOJ2qxq
4RgqsahDYVvTH9w7jXbyLeiNdd8XM2w9U/t7y0Ff/9yi0GE44Za4rF2LN9d11TPA
mRGunUHBcnWEvgJBQl9nJEiU0Zsnvgc/ubhPgXRR4Xq37Z0j4r7g1SgEEzwxA57d
emyPxgcYxn/eR44/KJ4EBs+lVDR3veyJm+kXQ99b21/+jh5Xos1AnX5iItreGCc=
-----END CERTIFICATE-----
)EOF";

/**
 * @brief Common routine to wait for and print an HTTP response.
 */
static bool waitForHttpsResponse(uint8_t profile, const char* contentType) 
{
  ESP_LOGI(TAG, "Waiting for reply...");
  const uint16_t maxPolls = 30;
  for(uint16_t i = 0; i < maxPolls; i++) {
    if(comm::modem.httpDidRing(profile, incomingBuf, sizeof(incomingBuf), &rsp)) {
      ESP_LOGI(TAG, "HTTPS status code (Modem): %d", rsp.data.httpResponse.httpStatus);
      ESP_LOGI(TAG, "Content type: %s", contentType);
      ESP_LOGI(TAG, "Payload:\n%s", incomingBuf);
      return true;
    }
    vTaskDelay(pdMS_TO_TICKS(1000));
  }
  ESP_LOGE(TAG, "HTTPS response timeout");
  return false;
}

namespace comm{
  WalterModem modem;

  // ========================================
  // LTE Network Functions
  // ========================================

  /**
   * @brief This function checks if we are connected to the LTE network
   *
   * @return true when connected, false otherwise
   */
  bool lteConnected() {
    WalterModemNetworkRegState regState = modem.getNetworkRegState();
    return (regState == WALTER_MODEM_NETWORK_REG_REGISTERED_HOME ||
            regState == WALTER_MODEM_NETWORK_REG_REGISTERED_ROAMING);
  }

  /**
   * @brief This function waits for the modem to be connected to the LTE network.
   *
   * @param timeout_sec The amount of seconds to wait before returning a time-out.
   *
   * @return true if connected, false on time-out.
   */
  bool waitForNetwork(int timeout_sec) 
  {
    ESP_LOGI(TAG, "Connecting to the network...");
    int time = 0;
    while(!lteConnected()) {
      vTaskDelay(pdMS_TO_TICKS(1000));
      time++;
      if(time > timeout_sec) {
        return false;
      }
    }
    ESP_LOGI(TAG, "Connected to the network");
    return true;
  }

  /**
   * @brief Disconnect from the LTE network.
   *
   * This function will disconnect the modem from the LTE network and block until
   * the network is actually disconnected. After the network is disconnected the
   * GNSS subsystem can be used.
   *
   * @return true on success, false on error.
   */
  bool lteDisconnect() 
  {
    // Set the operational state to minimum
    if(modem.setOpState(WALTER_MODEM_OPSTATE_MINIMUM)) {
      ESP_LOGI(TAG, "Successfully set operational state to MINIMUM");
    } else {
      ESP_LOGE(TAG, "Could not set operational state to MINIMUM");
      return false;
    }

    // Wait for the network to become available
    WalterModemNetworkRegState regState = modem.getNetworkRegState();
    while(regState != WALTER_MODEM_NETWORK_REG_NOT_SEARCHING) {
      vTaskDelay(pdMS_TO_TICKS(100));
      regState = modem.getNetworkRegState();
    }

    ESP_LOGI(TAG, "Disconnected from the network");
    return true;
  }

  /**
   * @brief This function tries to connect the modem to the cellular network.
   *
   * @return true on success, false on error.
   */
  bool lteConnect() 
  {
    // Set the operational state to NO RF
    if(modem.setOpState(WALTER_MODEM_OPSTATE_NO_RF)) {
      ESP_LOGI(TAG, "Successfully set operational state to NO RF");
    } else {
      ESP_LOGE(TAG, "Could not set operational state to NO RF");
      return false;
    }

    // Create PDP context
    if(modem.definePDPContext()) {
      ESP_LOGI(TAG, "Created PDP context");
    } else {
      ESP_LOGE(TAG, "Could not create PDP context");
      return false;
    }

    // Set the operational state to full
    if(modem.setOpState(WALTER_MODEM_OPSTATE_FULL)) {
      ESP_LOGI(TAG, "Successfully set operational state to FULL");
    } else {
      ESP_LOGE(TAG, "Could not set operational state to FULL");
      return false;
    }

    // Set the network operator selection to automatic
    if(modem.setNetworkSelectionMode(WALTER_MODEM_NETWORK_SEL_MODE_AUTOMATIC)) {
      ESP_LOGI(TAG, "Network selection mode was set to automatic");
    } else {
      ESP_LOGE(TAG, "Could not set the network selection mode to automatic");
      return false;
    }

    return waitForNetwork(300);
  }

  // ========================================
  // HTTPS Functions
  // ========================================

  /**
   * @brief Writes TLS credentials to the modem's NVS and configures the TLS profile.
   *
   * This function stores the provided TLS certificates and private keys into the modem's
   * non-volatile storage (NVS), and then sets up a TLS profile for secure communication.
   * These configuration changes are persistent across reboots.
   *
   * @note
   * - Certificate indexes 0-10 are reserved for Sequans and BlueCherry internal usage.
   * - Private key index 1 is reserved for BlueCherry internal usage.
   * - Do not attempt to override or use these reserved indexes.
   *
   * @return
   * - true if the credentials were successfully written and the profile configured.
   * - false otherwise.
   */
  bool setupTLSProfile(int https_tls_profile) 
  {
    if(!modem.tlsWriteCredential(false, 12, ca_cert)) {
      ESP_LOGE(TAG, "CA cert upload failed");
      return false;
    }

    if(modem.tlsConfigProfile(https_tls_profile, WALTER_MODEM_TLS_VALIDATION_CA,
                              WALTER_MODEM_TLS_VERSION_12, 12)) {
      ESP_LOGI(TAG, "TLS profile configured");
    } else {
      ESP_LOGE(TAG, "TLS profile configuration failed");
      return false;
    }

    return true;
  }

  /**
   * @brief Perform an HTTPS POST request with a body.
   */
  bool httpsPost(
      const char* path, 
      const uint8_t* body, 
      size_t bodyLen, 
      const char* mimeType, 
      int modem_https_profile, 
      const char* https_host) 
  {
    char ctBuf[32] = { 0 };

    ESP_LOGI(TAG, "Sending HTTPS POST to %s%s (%u bytes, type %s)", https_host, path,
                  (unsigned) bodyLen, mimeType);
    if(!modem.httpSend(modem_https_profile, path, (uint8_t*) body, (uint16_t) bodyLen,
                      WALTER_MODEM_HTTP_SEND_CMD_POST, WALTER_MODEM_HTTP_POST_PARAM_JSON, ctBuf,
                      sizeof(ctBuf))) {
      ESP_LOGE(TAG, "HTTPS POST failed");
      return false;
    }
    ESP_LOGI(TAG, "HTTPS POST successfully sent");
    return waitForHttpsResponse(modem_https_profile, ctBuf);
  }

  // ========================================
  // WebSocket Implementation
  // ========================================

  // WebSocket constants
  static const char* WS_MAGIC_STRING = "258EAFA5-E914-47DA-95CA-C5AB0DC85B11";
  static const int WS_SOCKET_ID = 1; // Use socket ID 1 for WebSocket
  static const int WS_TLS_PROFILE = 2; // TLS profile for WebSocket
  
  // WebSocket opcodes
  enum WsOpcode {
    WS_OP_CONTINUATION = 0x0,
    WS_OP_TEXT = 0x1,
    WS_OP_BINARY = 0x2,
    WS_OP_CLOSE = 0x8,
    WS_OP_PING = 0x9,
    WS_OP_PONG = 0xA
  };

  // WebSocket frame structure
  struct WsFrame {
    bool fin;
    uint8_t opcode;
    bool masked;
    uint64_t payload_len;
    uint8_t mask_key[4];
    uint8_t* payload;
  };

  // OpenAI Realtime session state
  struct RealtimeWsSession {
    bool connected;
    char session_id[64];
    uint8_t recv_buffer[8192];
    size_t recv_buffer_len;
  };
  
  static RealtimeWsSession wsSession = {false, {0}, {0}, 0};

  /**
   * @brief Generate WebSocket accept key from client key
   */
  static bool generateAcceptKey(const char* client_key, char* accept_key, size_t accept_key_size)
  {
    char concat[128];
    snprintf(concat, sizeof(concat), "%s%s", client_key, WS_MAGIC_STRING);
    
    uint8_t hash[20];
    mbedtls_sha1((uint8_t*)concat, strlen(concat), hash);
    
    size_t olen;
    if(mbedtls_base64_encode((uint8_t*)accept_key, accept_key_size, &olen, 
                             hash, sizeof(hash)) != 0) {
      return false;
    }
    accept_key[olen] = '\0';
    return true;
  }

  /**
   * @brief Generate random WebSocket key for handshake
   */
  static void generateWebSocketKey(char* key, size_t key_size)
  {
    uint8_t random_bytes[16];
    for(int i = 0; i < 16; i++) {
      random_bytes[i] = esp_random() & 0xFF;
    }
    
    size_t olen;
    mbedtls_base64_encode((uint8_t*)key, key_size, &olen, random_bytes, 16);
    key[olen] = '\0';
  }

  /**
   * @brief Mask WebSocket payload data
   */
  static void maskPayload(uint8_t* payload, size_t len, const uint8_t* mask_key)
  {
    for(size_t i = 0; i < len; i++) {
      payload[i] ^= mask_key[i % 4];
    }
  }

  /**
   * @brief Create a WebSocket frame header
   */
  static size_t createWsFrameHeader(uint8_t* buffer, uint8_t opcode, size_t payload_len, bool mask)
  {
    size_t header_len = 2;
    
    // Byte 0: FIN bit + opcode
    buffer[0] = 0x80 | (opcode & 0x0F);
    
    // Byte 1: MASK bit + payload length
    if(payload_len < 126) {
      buffer[1] = (mask ? 0x80 : 0x00) | (uint8_t)payload_len;
    } else if(payload_len < 65536) {
      buffer[1] = (mask ? 0x80 : 0x00) | 126;
      buffer[2] = (payload_len >> 8) & 0xFF;
      buffer[3] = payload_len & 0xFF;
      header_len = 4;
    } else {
      buffer[1] = (mask ? 0x80 : 0x00) | 127;
      for(int i = 0; i < 8; i++) {
        buffer[2 + i] = (payload_len >> (56 - i * 8)) & 0xFF;
      }
      header_len = 10;
    }
    
    // Add masking key if needed
    if(mask) {
      for(int i = 0; i < 4; i++) {
        buffer[header_len + i] = esp_random() & 0xFF;
      }
      header_len += 4;
    }
    
    return header_len;
  }

  /**
   * @brief Parse incoming WebSocket frame
   */
  static bool parseWsFrame(const uint8_t* data, size_t data_len, WsFrame* frame)
  {
    if(data_len < 2) return false;
    
    frame->fin = (data[0] & 0x80) != 0;
    frame->opcode = data[0] & 0x0F;
    frame->masked = (data[1] & 0x80) != 0;
    
    size_t offset = 2;
    uint8_t len_code = data[1] & 0x7F;
    
    if(len_code < 126) {
      frame->payload_len = len_code;
    } else if(len_code == 126) {
      if(data_len < 4) return false;
      frame->payload_len = (data[2] << 8) | data[3];
      offset = 4;
    } else {
      if(data_len < 10) return false;
      frame->payload_len = 0;
      for(int i = 0; i < 8; i++) {
        frame->payload_len = (frame->payload_len << 8) | data[2 + i];
      }
      offset = 10;
    }
    
    if(frame->masked) {
      if(data_len < offset + 4) return false;
      memcpy(frame->mask_key, data + offset, 4);
      offset += 4;
    }
    
    if(data_len < offset + frame->payload_len) return false;
    
    frame->payload = (uint8_t*)(data + offset);
    return true;
  }

  /**
   * @brief Setup TLS for WebSocket connection
   */
  bool setupWebSocketTLS(int tls_profile)
  {
    // Configure TLS profile for api.openai.com
    // Note: You need to upload the appropriate CA certificate first
    if(modem.tlsConfigProfile(tls_profile, WALTER_MODEM_TLS_VALIDATION_CA,
                              WALTER_MODEM_TLS_VERSION_12, 12)) {
      ESP_LOGI(TAG, "WebSocket TLS profile configured");
      return true;
    } else {
      ESP_LOGE(TAG, "WebSocket TLS profile configuration failed");
      return false;
    }
  }

  /**
   * @brief Connect to OpenAI Realtime API via WebSocket
   * 
   * @param api_key Your OpenAI API key
   * @param model Model name (e.g., "gpt-4o-realtime-preview-2024-12-17")
   * @return true on success, false on error
   */
  bool realtimeConnect(const char* api_key, const char* model)
  {
    WalterModemRsp rsp = {};
    
    // Configure socket
    if(!modem.socketConfig(&rsp, NULL, NULL, -1, 1500, 90, 60, 5000)) {
      ESP_LOGE(TAG, "Failed to configure WebSocket");
      return false;
    }
    
    // Enable TLS on socket
    if(!modem.socketConfigSecure(true, WS_TLS_PROFILE, WS_SOCKET_ID)) {
      ESP_LOGE(TAG, "Failed to enable TLS on WebSocket");
      return false;
    }
    
    // Connect to OpenAI
    if(!modem.socketDial("api.openai.com", 443, 0, &rsp, NULL, NULL, 
                        WALTER_MODEM_SOCKET_PROTO_TCP, 
                        WALTER_MODEM_ACCEPT_ANY_REMOTE_DISABLED, 
                        WS_SOCKET_ID)) {
      ESP_LOGE(TAG, "Failed to connect to OpenAI");
      return false;
    }
    
    ESP_LOGI(TAG, "TCP connection established");
    
    // Generate WebSocket key
    char ws_key[32];
    generateWebSocketKey(ws_key, sizeof(ws_key));
    
    // Build WebSocket handshake request
    char handshake[1024];
    int handshake_len = snprintf(handshake, sizeof(handshake),
      "GET /v1/realtime?model=%s HTTP/1.1\r\n"
      "Host: api.openai.com\r\n"
      "Authorization: Bearer %s\r\n"
      "Upgrade: websocket\r\n"
      "Connection: Upgrade\r\n"
      "Sec-WebSocket-Key: %s\r\n"
      "Sec-WebSocket-Version: 13\r\n"
      "OpenAI-Beta: realtime=v1\r\n"
      "\r\n",
      model, api_key, ws_key);
    
    // Send handshake
    if(!modem.socketSend((uint8_t*)handshake, handshake_len, &rsp, NULL, NULL, 
                        WALTER_MODEM_RAI_NO_INFO, WS_SOCKET_ID)) {
      ESP_LOGE(TAG, "Failed to send WebSocket handshake");
      modem.socketClose(&rsp, NULL, NULL, WS_SOCKET_ID);
      return false;
    }
    
    ESP_LOGI(TAG, "WebSocket handshake sent");
    
    // Wait for handshake response
    vTaskDelay(pdMS_TO_TICKS(2000));
    
    uint8_t response[512];
    uint16_t available = modem.socketAvailable(WS_SOCKET_ID);
    if(available > 0) {
      if(modem.socketReceive(available, sizeof(response), response, WS_SOCKET_ID, &rsp)) {
        // Check for "101 Switching Protocols"
        if(strstr((char*)response, "101") != NULL) {
          ESP_LOGI(TAG, "WebSocket connected to OpenAI Realtime API");
          wsSession.connected = true;
          return true;
        }
      }
    }
    
    ESP_LOGE(TAG, "WebSocket handshake failed");
    modem.socketClose(&rsp, NULL, NULL, WS_SOCKET_ID);
    return false;
  }

  /**
   * @brief Send a WebSocket message
   * 
   * @param payload Message payload (JSON string)
   * @param payload_len Length of payload
   * @param opcode WebSocket opcode (default: TEXT)
   * @return true on success, false on error
   */
  bool wsSend(const uint8_t* payload, size_t payload_len, uint8_t opcode)
  {
    if(!wsSession.connected) {
      ESP_LOGE(TAG, "WebSocket not connected");
      return false;
    }
    
    // Create frame header
    uint8_t header[14];
    size_t header_len = createWsFrameHeader(header, opcode, payload_len, true);
    
    // Get mask key from header
    uint8_t mask_key[4];
    memcpy(mask_key, header + header_len - 4, 4);
    
    // Create masked payload
    uint8_t* masked_payload = (uint8_t*)malloc(payload_len);
    if(!masked_payload) {
      ESP_LOGE(TAG, "Failed to allocate payload buffer");
      return false;
    }
    memcpy(masked_payload, payload, payload_len);
    maskPayload(masked_payload, payload_len, mask_key);
    
    // Send header
    WalterModemRsp rsp = {};
    if(!modem.socketSend(header, header_len, &rsp, NULL, NULL, 
                        WALTER_MODEM_RAI_NO_INFO, WS_SOCKET_ID)) {
      ESP_LOGE(TAG, "Failed to send frame header");
      free(masked_payload);
      return false;
    }
    
    // Send payload
    if(!modem.socketSend(masked_payload, payload_len, &rsp, NULL, NULL, 
                        WALTER_MODEM_RAI_NO_INFO, WS_SOCKET_ID)) {
      ESP_LOGE(TAG, "Failed to send frame payload");
      free(masked_payload);
      return false;
    }
    
    free(masked_payload);
    return true;
  }

  /**
   * @brief Receive WebSocket message
   * 
   * @param buffer Buffer to store received message
   * @param buffer_size Size of buffer
   * @param received_len Pointer to store actual received length
   * @return true if message received, false otherwise
   */
  bool wsReceive(uint8_t* buffer, size_t buffer_size, size_t* received_len)
  {
    if(!wsSession.connected) {
      return false;
    }
    
    uint16_t available = modem.socketAvailable(WS_SOCKET_ID);
    if(available == 0) {
      return false;
    }
    
    uint8_t frame_data[2048];
    WalterModemRsp rsp = {};
    
    if(!modem.socketReceive(available, sizeof(frame_data), frame_data, WS_SOCKET_ID, &rsp)) {
      ESP_LOGE(TAG, "Failed to receive data");
      return false;
    }
    
    WsFrame frame;
    if(!parseWsFrame(frame_data, available, &frame)) {
      ESP_LOGE(TAG, "Failed to parse WebSocket frame");
      return false;
    }
    
    // Handle different opcodes
    switch(frame.opcode) {
      case WS_OP_TEXT:
      case WS_OP_BINARY:
        if(frame.payload_len > buffer_size) {
          ESP_LOGE(TAG, "Received payload too large");
          return false;
        }
        memcpy(buffer, frame.payload, frame.payload_len);
        *received_len = frame.payload_len;
        buffer[*received_len] = '\0'; // Null terminate for text
        return true;
        
      case WS_OP_PING:
        // Respond with pong
        wsSend(frame.payload, frame.payload_len, WS_OP_PONG);
        return false;
        
      case WS_OP_CLOSE:
        ESP_LOGI(TAG, "WebSocket close frame received");
        wsSession.connected = false;
        return false;
        
      default:
        return false;
    }
  }

  // ========================================
  // OpenAI Realtime API Functions
  // ========================================

  /**
   * @brief Send audio to OpenAI Realtime API
   * 
   * @param audio_data PCM16 audio data (mono, 24kHz)
   * @param audio_len Length of audio data
   * @return true on success, false on error
   */
  bool realtimeSendAudio(const uint8_t* audio_data, size_t audio_len)
  {
    // Base64 encode audio
    size_t base64_len;
    mbedtls_base64_encode(NULL, 0, &base64_len, audio_data, audio_len);
    
    uint8_t* base64_audio = (uint8_t*)malloc(base64_len + 1);
    if(!base64_audio) {
      ESP_LOGE(TAG, "Failed to allocate base64 buffer");
      return false;
    }
    
    mbedtls_base64_encode(base64_audio, base64_len + 1, &base64_len, audio_data, audio_len);
    base64_audio[base64_len] = '\0';
    
    // Create JSON message
    size_t json_size = base64_len + 128;
    char* json = (char*)malloc(json_size);
    if(!json) {
      free(base64_audio);
      return false;
    }
    
    snprintf(json, json_size,
             "{\"type\":\"input_audio_buffer.append\",\"audio\":\"%s\"}",
             base64_audio);
    
    bool result = wsSend((uint8_t*)json, strlen(json), WS_OP_TEXT);
    
    free(base64_audio);
    free(json);
    
    return result;
  }

  /**
   * @brief Commit audio and trigger response
   */
  bool realtimeCommitAudio()
  {
    const char* msg = "{\"type\":\"input_audio_buffer.commit\"}";
    return wsSend((uint8_t*)msg, strlen(msg), WS_OP_TEXT);
  }

  /**
   * @brief Send text message
   */
  bool realtimeSendText(const char* text)
  {
    char json[1024];
    snprintf(json, sizeof(json),
             "{\"type\":\"conversation.item.create\",\"item\":{"
             "\"type\":\"message\",\"role\":\"user\",\"content\":["
             "{\"type\":\"input_text\",\"text\":\"%s\"}]}}",
             text);
    return wsSend((uint8_t*)json, strlen(json), WS_OP_TEXT);
  }

  /**
   * @brief Request response generation
   */
  bool realtimeGenerateResponse()
  {
    const char* msg = "{\"type\":\"response.create\"}";
    return wsSend((uint8_t*)msg, strlen(msg), WS_OP_TEXT);
  }

  /**
   * @brief Disconnect from Realtime API
   */
  void realtimeDisconnect()
  {
    if(wsSession.connected) {
      // Send close frame
      wsSend(NULL, 0, WS_OP_CLOSE);
      
      WalterModemRsp rsp = {};
      modem.socketClose(&rsp, NULL, NULL, WS_SOCKET_ID);
      
      wsSession.connected = false;
      ESP_LOGI(TAG, "WebSocket disconnected");
    }
  }

} // namespace comm

/**
 * === End of implementation ===
 */