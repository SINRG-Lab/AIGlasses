# ESP32-C6 WebSocket Voice Assistant - PlatformIO Project

This is a PlatformIO version of the Arduino IDE project for the ESP32-C6 WebSocket Voice Assistant.

## Features
- WiFi connectivity
- WebSocket communication with server
- I2S audio input from INMP441 microphone
- I2S audio output to MAX98357A amplifier
- Push-to-talk button interface
- Shared I2S bus for mic and speaker

## Hardware
- **Board**: ESP32-C6-DevKitC-1
- **Microphone**: INMP441 (I2S)
- **Amplifier**: MAX98357A (I2S)
- **Push-to-Talk**: GPIO23 button (active LOW)

## Pin Configuration
- BCLK: GPIO18 (shared)
- WS/LRCLK: GPIO22 (shared)
- MIC_DIN: GPIO16 (data from mic)
- AMP_DOUT: GPIO17 (data to speaker)
- PTT: GPIO23 (push-to-talk button)

## Getting Started

### Prerequisites
- Install [PlatformIO](https://platformio.org/install) via VS Code extension or CLI
- ESP32-C6-DevKitC-1 board

### Configuration
Edit `src/main.cpp` to update your WiFi and WebSocket server details:
```cpp
const char* WIFI_SSID = "YourWiFiSSID";
const char* WIFI_PASS = "YourPassword";
const char* WS_HOST = "your-server.ngrok-free.dev";
```

### Building & Uploading
```bash
# Build the project
pio run

# Upload to ESP32-C6
pio run --target upload

# Open serial monitor
pio device monitor

# Build, upload, and monitor in one command
pio run --target upload && pio device monitor
```

### VS Code
1. Open this folder in VS Code
2. PlatformIO will automatically detect the project
3. Use the PlatformIO toolbar buttons to build/upload/monitor

## Audio Settings
- Microphone sample rate: 16000 Hz
- Speaker sample rate: 22050 Hz
- Samples per chunk: 512
- Max audio buffer: 200 KB

## Dependencies
- WebSockets library by Links2004 (automatically installed by PlatformIO)

## Serial Monitor
The serial monitor runs at 115200 baud with ESP32 exception decoder enabled for easier debugging.
