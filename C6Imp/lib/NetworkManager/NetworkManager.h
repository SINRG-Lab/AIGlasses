#ifndef NETWORK_MANAGER_H
#define NETWORK_MANAGER_H

#include <Arduino.h>
#include <WiFi.h>
#include <WebSocketsClient.h>
#include <functional>

// Callback types
using BinaryDataCallback = std::function<void(uint8_t*, size_t)>;
using TextDataCallback = std::function<void(const char*)>;
using ConnectionCallback = std::function<void(bool)>;

class NetworkManager {
public:
    NetworkManager(const char* ssid, const char* password,
                  const char* wsHost, uint16_t wsPort, const char* wsPath);

    // Initialize WiFi and WebSocket
    bool begin(uint32_t wifiTimeoutMs = 20000);

    // WebSocket loop (call in main loop)
    void loop();

    // Send data
    bool sendBinary(const uint8_t* data, size_t len);
    bool sendText(const char* text);

    // Set callbacks
    void onBinaryReceived(BinaryDataCallback callback) { binaryCallback = callback; }
    void onTextReceived(TextDataCallback callback) { textCallback = callback; }
    void onConnectionChange(ConnectionCallback callback) { connectionCallback = callback; }

    // Status
    bool isWiFiConnected() const { return WiFi.status() == WL_CONNECTED; }
    bool isWSConnected() const { return ws.isConnected(); }
    IPAddress getLocalIP() const { return WiFi.localIP(); }
    int getRSSI() const { return WiFi.RSSI(); }

private:
    const char* ssid;
    const char* password;
    const char* wsHost;
    const uint16_t wsPort;
    const char* wsPath;

    WebSocketsClient ws;

    BinaryDataCallback binaryCallback;
    TextDataCallback textCallback;
    ConnectionCallback connectionCallback;

    static void wsEventHandler(WStype_t type, uint8_t* payload, size_t length);
    static NetworkManager* instance;  // For static callback
};

#endif // NETWORK_MANAGER_H
