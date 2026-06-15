# PlatformIO Projects for ESP32-C6

This repository now contains PlatformIO configurations for both Arduino projects.

## Projects

### 1. C6_App_imp - BLE Voice Assistant
**Location**: `C6_App_imp/`

Uses Bluetooth Low Energy (BLE) for communication with Android app. No WiFi required.

**Quick start:**
```bash
cd "C6_App_imp"
pio run --target upload && pio device monitor
```

### 2. C6Imp - WebSocket Voice Assistant
**Location**: `C6Imp/`

Uses WiFi and WebSocket for server communication.

**Quick start:**
```bash
cd "C6Imp"
# Edit src/main.cpp to update WiFi credentials first!
pio run --target upload && pio device monitor
```

## Installation

### Install PlatformIO

#### Option 1: VS Code Extension (Recommended)
1. Install [Visual Studio Code](https://code.visualstudio.com/)
2. Install the [PlatformIO IDE extension](https://marketplace.visualstudio.com/items?itemName=platformio.platformio-ide)
3. Open either project folder in VS Code

#### Option 2: PlatformIO CLI
```bash
# Install via pip
pip install platformio

# Or via Homebrew on macOS
brew install platformio
```

## PlatformIO vs Arduino IDE

### Advantages of PlatformIO
- **Better dependency management**: Libraries are defined in `platformio.ini`
- **Multiple environments**: Easy to switch between different boards/configs
- **Professional IDE**: Better code completion, debugging, and testing
- **Build system**: Faster incremental builds
- **Version control friendly**: Project structure is cleaner for git
- **CLI support**: Can build/upload from command line

### File Structure Differences
```
Arduino IDE:
C6_App_imp/
  └── C6_App_imp.ino

PlatformIO:
C6_App_imp/
  ├── platformio.ini    (project configuration)
  ├── src/
  │   └── main.cpp      (renamed from .ino)
  ├── lib/              (custom libraries)
  └── include/          (header files)
```

## Common Commands

```bash
# Build project
pio run

# Upload to board
pio run --target upload

# Clean build files
pio run --target clean

# Serial monitor
pio device monitor

# List connected devices
pio device list

# Update platforms and libraries
pio pkg update

# Install specific library
pio pkg install "h2zero/NimBLE-Arduino@^1.4.1"
```

## Board Configuration

Both projects use:
- **Platform**: espressif32
- **Board**: esp32-c6-devkitc-1
- **Framework**: arduino
- **Upload speed**: 921600 baud
- **Monitor speed**: 115200 baud

## Troubleshooting

### Upload Issues
```bash
# List available ports
pio device list

# Specify port manually
pio run --target upload --upload-port /dev/cu.usbserial-*
```

### Library Issues
```bash
# Clean and rebuild
pio run --target clean
pio run
```

### ESP32-C6 Not Recognized
Make sure you have the latest ESP32 platform:
```bash
pio pkg update
```

## Switching Between Arduino IDE and PlatformIO

Both the original `.ino` files and PlatformIO `src/main.cpp` files are kept in the repository:

- **Arduino IDE**: Open the `.ino` file directly
- **PlatformIO**: Open the project folder containing `platformio.ini`

The files are synchronized, so changes in one should be reflected in the other.

## Next Steps

1. Choose your preferred project (BLE or WebSocket)
2. Open it in VS Code with PlatformIO
3. Update configuration if needed (WiFi credentials for C6Imp)
4. Build and upload: `pio run --target upload`
5. Monitor serial output: `pio device monitor`

## Resources

- [PlatformIO Documentation](https://docs.platformio.org/)
- [ESP32-C6 Documentation](https://docs.espressif.com/projects/esp-idf/en/latest/esp32c6/)
- [NimBLE-Arduino Library](https://github.com/h2zero/NimBLE-Arduino)
- [WebSockets Library](https://github.com/Links2004/arduinoWebSockets)
