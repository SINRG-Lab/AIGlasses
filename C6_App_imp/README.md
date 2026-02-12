# ESP32-C6 BLE Voice Assistant - PlatformIO Project

This is a PlatformIO version of the Arduino IDE project for the ESP32-C6 BLE Voice Assistant.

## Features
- Bluetooth Low Energy (BLE) communication with Android app
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
- MIC_SD: GPIO16 (data from mic)
- AMP_DIN: GPIO20 (data to speaker)
- PTT: GPIO23 (push-to-talk button)

## Getting Started

### Prerequisites
- Install [PlatformIO](https://platformio.org/install) via VS Code extension or CLI
- ESP32-C6-DevKitC-1 board

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

## Configuration
The BLE service UUIDs are defined in `src/main.cpp` and must match your Android app:
- Service UUID: `0000aa00-1234-5678-abcd-0e5032c6b1e0`
- Audio TX (ESP32→Android): `0000aa01-1234-5678-abcd-0e5032c6b1e0`
- Audio RX (Android→ESP32): `0000aa02-1234-5678-abcd-0e5032c6b1e0`
- Control: `0000aa03-1234-5678-abcd-0e5032c6b1e0`

## Dependencies
- NimBLE-Arduino (automatically installed by PlatformIO)

## Serial Monitor
The serial monitor runs at 115200 baud with ESP32 exception decoder enabled for easier debugging.
