#include "NetworkManager.h"
#include "config.h"

// Static instance for callback
NetworkManager* NetworkManager::instance = nullptr;

NetworkManager::NetworkManager(const char* ssid, const char* password,
                             const char* wsHost, uint16_t wsPort, const char* wsPath)
    : ssid(ssid), password(password), wsHost(wsHost), wsPort(wsPort), wsPath(wsPath) {
    instance = this;
}

bool NetworkManager::begin(uint32_t wifiTimeoutMs) {
    DEBUG_PRINTLN("[WiFi] Connecting...");

    WiFi.mode(WIFI_STA);
    WiFi.begin(ssid, password);

    uint32_t startTime = millis();
    while (WiFi.status() != WL_CONNECTED) {
        if (millis() - startTime > wifiTimeoutMs) {
            DEBUG_PRINTLN("[WiFi] Timeout!");
            return false;
        }
        delay(300);
        DEBUG_PRINT(".");
    }

    DEBUG_PRINTLN("\n[WiFi] Connected");
    DEBUG_PRINT("[WiFi] IP: ");
    DEBUG_PRINTLN(WiFi.localIP().toString().c_str());
    DEBUG_PRINTF("[WiFi] RSSI: %d dBm\n", WiFi.RSSI());

    // Initialize WebSocket
    DEBUG_PRINTF("[WS] Connecting to %s:%u%s\n", wsHost, wsPort, wsPath);
    ws.begin(wsHost, wsPort, wsPath);
    ws.onEvent(wsEventHandler);
    ws.setReconnectInterval(WS_RECONNECT_MS);

    return true;
}

void NetworkManager::loop() {
    ws.loop();
}

bool NetworkManager::sendBinary(const uint8_t* data, size_t len) {
    if (!isWSConnected()) {
        DEBUG_PRINTLN("[WS] Not connected, cannot send");
        return false;
    }
    ws.sendBIN(data, len);
    return true;
}

bool NetworkManager::sendText(const char* text) {
    if (!isWSConnected()) {
        DEBUG_PRINTLN("[WS] Not connected, cannot send");
        return false;
    }
    ws.sendTXT(text);
    return true;
}

void NetworkManager::wsEventHandler(WStype_t type, uint8_t* payload, size_t length) {
    if (!instance) return;

    switch (type) {
        case WStype_CONNECTED:
            DEBUG_PRINTLN("[WS] Connected");
            if (instance->connectionCallback) {
                instance->connectionCallback(true);
            }
            break;

        case WStype_DISCONNECTED:
            DEBUG_PRINTLN("[WS] Disconnected");
            if (instance->connectionCallback) {
                instance->connectionCallback(false);
            }
            break;

        case WStype_TEXT:
            DEBUG_PRINTF("[WS] Text: %s\n", (char*)payload);
            if (instance->textCallback) {
                instance->textCallback((char*)payload);
            }
            break;

        case WStype_BIN:
            if (instance->binaryCallback) {
                instance->binaryCallback(payload, length);
            }
            break;

        default:
            break;
    }
}
