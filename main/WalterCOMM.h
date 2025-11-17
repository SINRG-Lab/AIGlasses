/**
 * === Communications library for the Walter Board ===
 * @file WalterCOMM.h
 * @author Ryder Paulson <paulson.r@northeastern.edu>
 * @brief Header file for communications tools for the Walter Board
 */

#ifndef WALTER_COMM_H
#define WALTER_COMM_H

#include "WalterModem.h"
#include <stdint.h>
#include <stddef.h>

namespace comm {

    // Global modem instance
    extern WalterModem modem;

    // ========================================
    // LTE Network Functions
    // ========================================

    /**
     * @brief Check if connected to the LTE network
     * @return true when connected, false otherwise
     */
    bool lteConnected();

    /**
     * @brief Wait for the modem to connect to the LTE network
     * @param timeout_sec Maximum seconds to wait before timeout
     * @return true if connected, false on timeout
     */
    bool waitForNetwork(int timeout_sec);

    /**
     * @brief Connect the modem to the cellular network
     * @return true on success, false on error
     */
    bool lteConnect();

    /**
     * @brief Disconnect from the LTE network
     * @return true on success, false on error
     */
    bool lteDisconnect();

    // ========================================
    // WiFi Functions
    // ========================================

    /**
     * @brief Connect to WiFi network
     * 
     * @param ssid WiFi network name (SSID)
     * @param password WiFi password (pass NULL or empty string for open networks)
     * @param timeout_ms Maximum time to wait for connection in milliseconds (0 = wait forever)
     * 
     * @return true if connection successful, false otherwise
     */
    bool wifi_connect(const char *ssid, const char *password, uint32_t timeout_ms);

    /**
     * @brief Disconnect from WiFi and cleanup
     */
    void wifi_disconnect(void);

    // ========================================
    // HTTPS Functions
    // ========================================

    /**
     * @brief Setup TLS profile for HTTPS communication
     * @param https_tls_profile TLS profile number to configure
     * @return true on success, false on error
     */
    bool setupTLSProfile(int https_tls_profile);

    /**
     * @brief Perform an HTTPS POST request
     * @param path URL path for the request
     * @param body Request body data
     * @param bodyLen Length of body data
     * @param mimeType MIME type of the body (e.g., "application/json")
     * @param modem_https_profile HTTPS profile ID to use
     * @param https_host Host name for the request
     * @return true on success, false on error
     */
    bool httpsPost(
        const char* path,
        const uint8_t* body,
        size_t bodyLen,
        const char* mimeType,
        int modem_https_profile,
        const char* https_host);

    // ========================================
    // WebSocket Functions
    // ========================================

    /**
     * @brief Setup TLS profile for WebSocket connections
     * @param tls_profile TLS profile number to configure
     * @return true on success, false on error
     */
    bool setupWebSocketTLS(int tls_profile);

    /**
     * @brief Send a WebSocket message
     * @param payload Message payload
     * @param payload_len Length of payload
     * @param opcode WebSocket opcode (default: TEXT frame)
     * @return true on success, false on error
     */
    bool wsSend(const uint8_t* payload, size_t payload_len, uint8_t opcode = 0x1);

    /**
     * @brief Receive a WebSocket message
     * @param buffer Buffer to store received message
     * @param buffer_size Size of receive buffer
     * @param received_len Pointer to store actual received length
     * @return true if message received, false otherwise
     */
    bool wsReceive(uint8_t* buffer, size_t buffer_size, size_t* received_len);

    // ========================================
    // OpenAI Realtime API Functions
    // ========================================

    /**
     * @brief Connect to OpenAI Realtime API via WebSocket
     * @param api_key Your OpenAI API key
     * @param model Model name (e.g., "gpt-4o-realtime-preview-2024-12-17")
     * @return true on success, false on error
     * 
     * @note This establishes a WebSocket connection to api.openai.com
     * @note Requires TLS to be configured via setupWebSocketTLS()
     */
    bool realtimeConnect(const char* api_key, const char* model);

    /**
     * @brief Send audio data to OpenAI Realtime API
     * @param audio_data PCM16 audio data buffer (mono, 24kHz)
     * @param audio_len Length of audio data in bytes
     * @return true on success, false on error
     * 
     * @note Audio format: PCM16, mono channel, 24kHz sample rate
     * @note Audio is automatically base64 encoded before transmission
     */
    bool realtimeSendAudio(const uint8_t* audio_data, size_t audio_len);

    /**
     * @brief Commit the audio buffer and trigger response generation
     * @return true on success, false on error
     * 
     * @note Call this after sending all audio chunks to signal completion
     */
    bool realtimeCommitAudio();

    /**
     * @brief Send a text message to the Realtime API
     * @param text Text message to send
     * @return true on success, false on error
     */
    bool realtimeSendText(const char* text);

    /**
     * @brief Request a response generation from the API
     * @return true on success, false on error
     * 
     * @note Call this after sending text or committing audio
     */
    bool realtimeGenerateResponse();

    /**
     * @brief Disconnect from OpenAI Realtime API
     * 
     * @note Sends a WebSocket close frame and closes the socket
     */
    void realtimeDisconnect();

} // namespace comm

#endif // WALTER_COMM_H