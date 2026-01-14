# Library Issues - Fixed

## Issues Found and Fixed

### 1. Missing ESP-IDF Headers
**Problem**: The code was using `esp_err_to_name()` and `portMAX_DELAY` without including the necessary headers.

**Fixed in**: `src/i2s_mic.cpp`
- Added `#include <esp_err.h>` for `esp_err_to_name()` function
- Added `#include <freertos/FreeRTOS.h>` for `portMAX_DELAY` constant

### 2. PlatformIO Configuration
**Updated**: `platformio.ini`
- Added build flag `-DARDUINO_USB_CDC_ON_BOOT=1` for proper serial communication
- Added comment clarifying that ESP32 BLE Arduino is included with the framework

### 3. 'Serial' was not declared in this scope
**Problem**: Build fails with errors like `'Serial' was not declared in this scope` when compiling multiple translation units.

**Cause**: When `ARDUINO_USB_CDC_ON_BOOT` is enabled (via `-DARDUINO_USB_CDC_ON_BOOT=1`), the Arduino core exposes the USB CDC global serial as `Serial0` instead of the usual `Serial`. Code that expects the global `Serial` symbol will fail to link/compile.

**Fix applied in this project**:
- Removed the `-DARDUINO_USB_CDC_ON_BOOT=1` build flag from `platformio.ini` so the core defines the global `Serial` as expected.
- Added safe includes and a compatibility macro to `include/config.h`:
	- `#include <Arduino.h>`
	- `#include <HardwareSerial.h>`
	- A small `#define Serial Serial0` mapping guarded by `#if defined(ARDUINO_USB_CDC_ON_BOOT) && (ARDUINO_USB_CDC_ON_BOOT)` so the code remains compatible if the flag is later re-enabled.

**Notes / Recommendations**:
- The compatibility macro is intentionally kept for safety across different build environments; you can remove it if you prefer to keep code strictly aligned with a single core configuration.
- After these changes, rebuild with:
```bash
pio run
```


## No Additional Libraries Required

The ESP32 Arduino framework includes:
-  **BLE Library**: `BLEDevice.h`, `BLEServer.h`, `BLEUtils.h` are all included
-  **I2S Driver**: `driver/i2s.h` is part of ESP-IDF (included with Arduino framework)
-  **FreeRTOS**: Included automatically
-  **ESP-IDF Components**: All necessary headers are available

## If You Still See Errors

### 1. Clean and Rebuild
```bash
pio run -t clean
pio run
```

### 2. Verify PlatformIO Installation
Make sure PlatformIO is properly installed:
```bash
pio --version
```

### 3. Update Platform
If errors persist, try updating the ESP32 platform:
```bash
pio platform update espressif32
```

### 4. Check PlatformIO Core Version
Ensure you're using a recent version of PlatformIO Core (5.0+):
```bash
pio upgrade
```

### 5. Common Error Messages and Solutions

#### "BLEDevice.h: No such file or directory"
- **Solution**: This should not happen as BLE is included with ESP32 Arduino framework
- Try: `pio platform update espressif32`

#### "driver/i2s.h: No such file or directory"
- **Solution**: Ensure `framework = arduino` is set in platformio.ini
- The I2S driver is part of ESP-IDF which is included with Arduino framework

#### "esp_err.h: No such file or directory"
- **Solution**: Already fixed - check that `src/i2s_mic.cpp` includes `<esp_err.h>`

#### "portMAX_DELAY was not declared"
- **Solution**: Already fixed - check that `src/i2s_mic.cpp` includes `<freertos/FreeRTOS.h>`

## Verification

After fixes, your project should compile with:
```bash
pio run
```

If you encounter specific error messages, please share them and we can address them individually.
