# OpenGlasses Audio Firmware

ESP32-based BLE audio streaming firmware for INMP441 I2S microphone. Streams raw PCM audio data over Bluetooth Low Energy (BLE) notifications.

## Features

- **I2S Audio Input**: Supports INMP441 MEMS microphone
- **BLE Audio Streaming**: Streams raw PCM samples via BLE notifications
- **Control Protocol**: Simple text-based commands (START, STOP, PING, INFO)
- **Modular Architecture**: Clean separation of I2S, BLE, and packetization logic
- **Configurable**: All parameters tunable via `include/config.h`

## Hardware Requirements

- ESP32 development board (tested with ESP32-DevKitC)
- INMP441 I2S MEMS microphone module

## Wiring

Connect the INMP441 to ESP32 as follows:

| INMP441 Pin | ESP32 Pin | Description |
|------------|-----------|-------------|
| VDD        | 3.3V      | Power       |
| GND        | GND       | Ground      |
| WS (LRCL)  | GPIO 23   | Word Select |
| SCK (BCLK) | GPIO 19   | Bit Clock   |
| SD (DOUT)  | GPIO 22   | Serial Data |

**Note**: The default pins can be changed in `include/config.h`.

## Building and Uploading

### Prerequisites

- [PlatformIO](https://platformio.org/) installed
- USB cable to connect ESP32 to your computer

### Build and Upload

```bash
# Build the project
pio run

# Upload to ESP32
pio run -t upload

# Monitor serial output
pio device monitor
```

Or use the PlatformIO IDE/VSCode extension for a GUI experience.

## Usage

1. **Power on** the ESP32 board
2. **Connect** via BLE using a BLE scanner app (e.g., LightBlue, nRF Connect)
3. **Look for** device named "OpenGlasses-Audio"
4. **Connect** to the service UUID: `4fafc201-1fb5-459e-8fcc-c5c9c331914b`
5. **Subscribe** to notifications on characteristic: `beb5483e-36e1-4688-b7f5-ea07361b26a8`

### Control Commands

Write the following commands (case-insensitive) to the characteristic to control streaming:

- **START**: Begin audio streaming
- **STOP**: Stop audio streaming (I2S continues running, but no data sent)
- **PING**: Test connection (responds with "PONG")
- **INFO**: Get device configuration info (sample rate, pins, etc.)

### Audio Packet Format

BLE notifications contain **little-endian signed 16-bit PCM samples**:

- **Sample Rate**: 44,100 Hz (configurable)
- **Bit Depth**: 16 bits
- **Channels**: Mono (left channel only)
- **Endianness**: Little-endian
- **Chunk Size**: 20 bytes by default (10 samples per chunk, configurable)

Each notification payload is a raw byte array of int16 samples. To decode:

```python
import struct

# Example: decode a 20-byte notification
data = b'\x12\x34\x56\x78...'  # 20 bytes from BLE
samples = struct.unpack('<10h', data)  # '<10h' = 10 little-endian int16
```

## Configuration

All configuration is in `include/config.h`:

```cpp
// BLE settings
#define BLE_DEVICE_NAME "OpenGlasses-Audio"
#define BLE_CHUNK_BYTES 20

// I2S pins
#define I2S_WS_PIN 23
#define I2S_SD_PIN 22
#define I2S_SCK_PIN 19

// Audio settings
#define AUDIO_SAMPLE_RATE 44100
#define AUDIO_BITS_PER_SAMPLE 16
#define AUDIO_BUFFER_LEN 64

// Debug
#define DEBUG_ENABLED 1
```

## Project Structure

```
.
├── platformio.ini          # PlatformIO configuration
├── include/
│   ├── config.h            # All tunable parameters
│   ├── i2s_mic.h           # I2S microphone interface
│   ├── ble_audio.h         # BLE service and control protocol
│   └── audio_packetizer.h  # Audio chunking/packetization
├── src/
│   ├── main.cpp            # Main application loop
│   ├── i2s_mic.cpp         # I2S implementation
│   ├── ble_audio.cpp       # BLE implementation
│   └── audio_packetizer.cpp # Packetizer implementation
└── README.md               # This file
```

## Architecture

The firmware is organized into three main modules:

1. **I2SMic**: Handles I2S driver initialization, reading samples, and cleanup
2. **BleAudio**: Manages BLE device, service, characteristic, advertising, and control protocol
3. **AudioPacketizer**: Packs int16 samples into BLE notification payloads with proper chunking

All modules are instantiated in `main.cpp` with no global state except the module instances themselves.

## Roadmap / Future Enhancements

- [ ] **MTU Negotiation**: Increase BLE MTU for larger packet sizes (currently limited to ~20 bytes)
- [ ] **Buffering**: Add ring buffer to handle BLE notification backpressure
- [ ] **Compression**: Implement audio compression (e.g., ADPCM, Opus) to reduce bandwidth
- [ ] **Button Toggle**: Add hardware button to start/stop streaming
- [ ] **LED Indicators**: Visual feedback for connection and streaming status
- [ ] **Multi-channel**: Support stereo or multiple microphones
- [ ] **Sample Rate Selection**: Runtime sample rate switching
- [ ] **Power Management**: Low-power modes when not streaming
- [ ] **OTA Updates**: Over-the-air firmware updates
- [ ] **Web Interface**: Simple web server for configuration

## Troubleshooting

### No audio data received
- Verify BLE connection is established
- Send `START` command after connecting
- Check serial monitor for debug messages (if `DEBUG_ENABLED=1`)

### I2S initialization fails
- Check wiring connections
- Verify INMP441 is powered (3.3V)
- Ensure pins match configuration in `config.h`

### BLE connection issues
- Ensure ESP32 BLE is not already connected to another device
- Try resetting the ESP32
- Check that your BLE scanner supports notifications

### Compilation errors
- Ensure you're using PlatformIO with `espressif32` platform
- Check that all dependencies are installed: `pio lib install`

## License

Open source - feel free to modify and extend!

## Contributing

Contributions welcome! Please ensure code follows the existing modular architecture and includes appropriate error handling.
