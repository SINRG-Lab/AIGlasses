# Quick Start - Modular Architecture

## 🚀 Get Started in 3 Minutes

### Step 1: Choose Your Project

```bash
cd "C6_App_imp"     # BLE version (no WiFi needed)
# OR
cd "C6Imp"          # WebSocket version (requires WiFi)
```

### Step 2: Configure (WebSocket Only)

If using WebSocket version, edit `include/config.h`:
```cpp
#define WIFI_SSID      "YOUR_WIFI_SSID"
#define WIFI_PASSWORD  "YOUR_WIFI_PASSWORD"
#define WS_HOST        "YOUR_NGROK_HOST"
```

### Step 3: Build & Upload

```bash
pio run --target upload && pio device monitor
```

Done! 🎉

---

## 📁 Quick File Reference

### Need to change pin assignments?
→ `include/config.h`

### Need to change audio settings?
→ `include/config.h`

### Need to modify BLE behavior?
→ `lib/BLEManager/BLEManager.cpp`

### Need to modify WiFi/WebSocket?
→ `lib/NetworkManager/NetworkManager.cpp`

### Need to change I2S settings?
→ `lib/I2SManager/I2SManager.cpp`

### Need to modify audio buffering?
→ `lib/AudioManager/AudioManager.cpp`

### Need to change application logic?
→ `src/main.cpp`

---

## 🎯 Common Tasks

### Change Microphone Sample Rate

**File**: `include/config.h`
```cpp
#define MIC_SAMPLE_RATE 8000  // Change from 16000
```

### Change Debug Level

**File**: `include/config.h`
```cpp
#define DEBUG_ENABLED false  // Disable all debug output
```

### Add New BLE Characteristic

**File**: `lib/BLEManager/BLEManager.cpp`

1. Add UUID in `include/config.h`
2. Create characteristic in `begin()`
3. Add callback class
4. Wire up in BLEManager

### Change Button Pin

**File**: `include/config.h`
```cpp
#define PTT_PIN 21  // Change from 23
```

### Increase Audio Buffer

**File**: `include/config.h`
```cpp
#define MAX_AUDIO_BUFFER_SIZE (500 * 1024)  // 500KB instead of 200KB
```

---

## 🐛 Debugging Tips

### Enable Verbose Logging

**platformio.ini**:
```ini
build_flags =
    -DCORE_DEBUG_LEVEL=5  # Change from 3
```

### Check Module Status

Add debug output in callbacks:
```cpp
void onBLEAudioReceived(const uint8_t* data, size_t len) {
    DEBUG_PRINTF("Received %u bytes\n", len);
    // ...
}
```

### Test Individual Modules

Comment out modules in `setup()`:
```cpp
void setup() {
    // audioManager.startMicrophone();  // Disable audio
    bleManager.begin();                 // Test only BLE
}
```

---

## 📊 Project Comparison

| Feature | BLE (C6_App_imp) | WebSocket (C6Imp) |
|---------|------------------|-------------------|
| **Transport** | Bluetooth LE | WiFi + WebSocket |
| **Range** | ~10-30m | WiFi range |
| **Setup** | Zero config | WiFi credentials |
| **Power** | Low | Higher |
| **Latency** | Low | Medium |
| **Android** | Built-in BLE | Network required |

---

## 🔧 Build Configurations

### Debug Build (Default)
```bash
pio run
```
- Optimized for speed (`-O2`)
- Debug symbols included
- Serial logging enabled

### Size-Optimized Build
Edit `platformio.ini`:
```ini
build_flags =
    -Os              # Optimize for size
build_type = release
```

### Disable All Logging
Edit `include/config.h`:
```cpp
#define DEBUG_ENABLED false
```

---

## 📦 Module Dependencies

```
main.cpp
  ├─→ AudioManager
  │     └─→ I2SManager
  ├─→ BLEManager (or NetworkManager)
  └─→ config.h
```

**All modules depend on**: `config.h`

---

## ⚙️ Configuration Hierarchy

1. **Hardware Config** → `include/config.h`
2. **Build Config** → `platformio.ini`
3. **Module Behavior** → `lib/*/` source files
4. **Application Logic** → `src/main.cpp`

---

## 🎨 Code Style

### Naming Conventions
- **Classes**: PascalCase (`I2SManager`)
- **Methods**: camelCase (`startMicrophone()`)
- **Constants**: UPPER_SNAKE (`MIC_SAMPLE_RATE`)
- **Variables**: camelCase (`audioBuffer`)

### File Organization
```cpp
// 1. Includes
#include "config.h"

// 2. Class declaration
class MyManager { ... };

// 3. Implementation
MyManager::MyManager() { ... }
```

---

## 🧪 Testing Modules

### Test I2S Alone
```cpp
void loop() {
    int16_t buffer[512];
    size_t read = i2sManager.readMicrophone(buffer, sizeof(buffer));
    Serial.printf("Read %u bytes\n", read);
}
```

### Test BLE Alone
```cpp
void setup() {
    bleManager.begin();
    bleManager.onConnectionChange([](bool connected) {
        Serial.printf("Connected: %d\n", connected);
    });
}
```

---

## 📚 Further Reading

- **Architecture Details**: `MODULAR_ARCHITECTURE.md`
- **PlatformIO Guide**: `PLATFORMIO_GUIDE.md`
- **Setup Complete**: `PLATFORMIO_SETUP_COMPLETE.md`

---

## 💡 Pro Tips

1. **Always edit `config.h` first** before modifying code
2. **Use callbacks** to extend functionality without modifying modules
3. **Keep `main.cpp` clean** - move complex logic to modules
4. **Test one module at a time** when debugging
5. **Backup before major changes** - original files are in `.backup`

---

## ⚡ Performance Tips

1. **Disable debug in production**:
   ```cpp
   #define DEBUG_ENABLED false
   ```

2. **Adjust DMA buffers** for your use case:
   ```cpp
   #define I2S_DMA_BUF_COUNT 4  // Reduce for lower latency
   ```

3. **Optimize sample rates**:
   ```cpp
   #define MIC_SAMPLE_RATE 8000  // Lower = less data
   ```

---

## 🎯 Next Steps

1. ✅ Build and test basic functionality
2. ✅ Modify `config.h` for your hardware
3. ✅ Add custom features via callbacks
4. ✅ Optimize for your use case
5. ✅ Read full architecture docs

**Happy coding!** 🚀
