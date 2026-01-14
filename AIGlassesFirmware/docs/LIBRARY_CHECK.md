# Library Dependencies Check

## Required Libraries (All Included with ESP32 Arduino Framework)

### BLE Libraries
- `<BLEDevice.h>` - ESP32 BLE device management
- `<BLEServer.h>` - BLE server functionality  
- `<BLEUtils.h>` - BLE utility functions
- **Status**: Included with `framework = arduino` in platformio.ini
- **Location**: `~/.platformio/packages/framework-arduinoespressif32/libraries/BLE/src/`

### I2S Driver
- `<driver/i2s.h>` - ESP-IDF I2S driver
- **Status**: Included with ESP32 Arduino framework (includes ESP-IDF)
- **Location**: Part of ESP-IDF, accessible via Arduino framework

### Arduino Core
- `<Arduino.h>` - Arduino framework core
- **Status**: Included with `framework = arduino`
- **Provides**: Serial, delay, basic Arduino functions

### ESP-IDF Headers
- `<esp_err.h>` - ESP error codes and `esp_err_to_name()`
- `<freertos/FreeRTOS.h>` - FreeRTOS includes (`portMAX_DELAY`)
- **Status**: Included with ESP32 Arduino framework

### Standard C++ Libraries
- `<string>` - std::string
- `<algorithm>` - std::transform, std::remove_if
- `<cctype>` - std::toupper, ::isspace
- `<functional>` - std::function
- `<stdint.h>` - Standard integer types
- `<stddef.h>` - Standard definitions (size_t)
- `<string.h>` - C string functions (memcpy, memset)
- **Status**: Standard C++ libraries, always available

## Current Include Structure

### Headers (include/)
- `ble_audio.h`: Includes Arduino.h, BLE headers, std headers
- `i2s_mic.h`: Includes driver/i2s.h, stdint.h
- `audio_packetizer.h`: Includes stdint.h, stddef.h
- `config.h`: Only defines, no includes

### Source Files (src/)
- `main.cpp`: Arduino.h, local headers
- `ble_audio.cpp`: ble_audio.h, config.h, Arduino.h, std headers
- `i2s_mic.cpp`: i2s_mic.h, config.h, Arduino.h, ESP-IDF headers
- `audio_packetizer.cpp`: audio_packetizer.h, config.h, string.h

## Verification

All libraries are provided by:
1. **ESP32 Arduino Framework** (platformio.ini: `framework = arduino`)
2. **Standard C++ Library** (compiler built-in)
3. **ESP-IDF** (included with Arduino framework)

**No external libraries need to be installed!**

## If You See Library Errors

1. **Build the project first**: `pio run`
   - This downloads the ESP32 Arduino framework
   - Libraries are included automatically

2. **Verify platformio.ini**:
   ```ini
   platform = espressif32
   board = esp32dev
   framework = arduino
   ```

3. **Check platform installation**:
   ```bash
   pio platform show espressif32
   ```

4. **Reinstall if needed**:
   ```bash
   pio platform install --reinstall espressif32
   ```

## Summary

All required libraries are included with the ESP32 Arduino framework
No `lib_deps` needed in platformio.ini
Standard C++ libraries are compiler built-in
Code structure is correct

If you see "no such file" errors, it's likely IntelliSense not finding the framework yet. Build the project first!
