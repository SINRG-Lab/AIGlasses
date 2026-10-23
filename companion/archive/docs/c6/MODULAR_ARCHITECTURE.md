# Modular Architecture Guide

## 🎯 Overview

Both ESP32-C6 projects have been restructured with a **clean, modular architecture** that separates concerns and makes the code maintainable, testable, and reusable.

## 📂 Project Structure

### C6_App_imp (BLE Version)
```
C6_App_imp/
├── platformio.ini              # Build configuration with optimizations
├── include/
│   └── config.h               # Hardware & software configuration
├── lib/
│   ├── I2SManager/            # I2S hardware abstraction
│   │   ├── I2SManager.h
│   │   └── I2SManager.cpp
│   ├── AudioManager/          # Audio buffering & playback
│   │   ├── AudioManager.h
│   │   └── AudioManager.cpp
│   └── BLEManager/            # BLE communication
│       ├── BLEManager.h
│       └── BLEManager.cpp
├── src/
│   ├── main.cpp               # Application logic (modular)
│   └── main.cpp.backup        # Original monolithic version
└── C6_App_imp.ino             # Original Arduino file
```

### C6Imp (WebSocket Version)
```
C6Imp/
├── platformio.ini              # Build configuration with optimizations
├── include/
│   └── config.h               # Hardware & software configuration
├── lib/
│   ├── I2SManager/            # I2S hardware abstraction (shared)
│   │   ├── I2SManager.h
│   │   └── I2SManager.cpp
│   ├── AudioManager/          # Audio buffering & playback (shared)
│   │   ├── AudioManager.h
│   │   └── AudioManager.cpp
│   └── NetworkManager/        # WiFi + WebSocket
│       ├── NetworkManager.h
│       └── NetworkManager.cpp
├── src/
│   ├── main.cpp               # Application logic (modular)
│   └── main.cpp.backup        # Original monolithic version
└── C6_imp/
    └── C6_imp.ino             # Original Arduino file
```

## 🧩 Module Breakdown

### 1. Configuration Module (`include/config.h`)

**Purpose**: Centralize all configuration in one place

**Contains**:
- Hardware pin definitions
- Audio parameters (sample rates, buffer sizes)
- Communication settings (BLE UUIDs, WiFi credentials)
- Debug macros

**Benefits**:
- Easy to modify settings without hunting through code
- Compile-time configuration
- Debug output can be disabled in one line

**Example**:
```cpp
#define I2S_BCLK        18
#define MIC_SAMPLE_RATE 16000
#define DEBUG_ENABLED   true
```

### 2. I2S Manager (`lib/I2SManager/`)

**Purpose**: Abstract I2S hardware control

**Features**:
- Mode switching (microphone ↔ speaker)
- DMA buffer management
- Error handling
- Clean API

**Key Methods**:
```cpp
bool initMicrophone(int sampleRate, ...);
bool initSpeaker(int sampleRate, ...);
size_t readMicrophone(int16_t* buffer, size_t size);
size_t writeSpeaker(const uint8_t* data, size_t len);
```

**Benefits**:
- Hides ESP32 I2S complexity
- Reusable across projects
- Easy to test

### 3. Audio Manager (`lib/AudioManager/`)

**Purpose**: Handle audio buffering and playback logic

**Features**:
- Speaker audio buffering
- Automatic mode switching
- Progress reporting
- Buffer overflow protection

**Key Methods**:
```cpp
bool startMicrophone();
size_t readMicrophoneChunk(int16_t* buffer, size_t size);
bool appendAudioData(const uint8_t* data, size_t len);
void playSpeakerBuffer();
```

**Benefits**:
- Separates audio logic from hardware
- Memory-safe operations
- Clear API

### 4. BLE Manager (`lib/BLEManager/`)

**Purpose**: Handle Bluetooth Low Energy communication

**Features**:
- GATT service management
- Callback-based architecture
- Automatic reconnection
- Packet fragmentation

**Key Methods**:
```cpp
bool begin();
void sendAudioData(const uint8_t* data, size_t len);
void sendControl(uint8_t tag);
void onAudioReceived(AudioDataCallback callback);
```

**Callbacks**:
```cpp
onAudioReceived()      // When audio arrives from Android
onControlReceived()    // When control message arrives
onConnectionChange()   // When connection state changes
```

**Benefits**:
- Decouples BLE from application logic
- Event-driven design
- Easy to add new characteristics

### 5. Network Manager (`lib/NetworkManager/`)

**Purpose**: Handle WiFi and WebSocket communication

**Features**:
- WiFi connection management
- WebSocket auto-reconnect
- Callback-based architecture
- Connection monitoring

**Key Methods**:
```cpp
bool begin(uint32_t wifiTimeout);
void loop();
bool sendBinary(const uint8_t* data, size_t len);
void onBinaryReceived(BinaryDataCallback callback);
```

**Benefits**:
- Abstracts network complexity
- Handles reconnection automatically
- Event-driven design

### 6. Main Application (`src/main.cpp`)

**Purpose**: Orchestrate modules and implement business logic

**Structure**:
```cpp
// 1. Create manager instances
I2SManager i2sManager(...);
AudioManager audioManager(...);
BLEManager bleManager(...);  // or NetworkManager

// 2. Define callbacks
void onBLEAudioReceived(const uint8_t* data, size_t len) { ... }
void onBLEControlReceived(uint8_t tag) { ... }

// 3. Setup: Initialize modules & register callbacks
void setup() {
    audioManager.startMicrophone();
    bleManager.begin();
    bleManager.onAudioReceived(onBLEAudioReceived);
}

// 4. Loop: Handle events
void loop() {
    if (playAudio) audioManager.playSpeakerBuffer();
    if (bleManager.isConnected()) handlePTT();
}
```

**Benefits**:
- Clean separation of concerns
- Easy to understand flow
- Testable components

## ⚡ Optimizations in platformio.ini

### Compiler Optimizations
```ini
build_flags =
    -O2                      # Optimize for speed (not size)
    -Wall                    # Enable all warnings
    -ffunction-sections      # Allow dead code elimination
    -fdata-sections          # Allow dead data elimination
```

### Memory Optimizations
```ini
-DCONFIG_ARDUINO_LOOP_STACK_SIZE=8192    # Adequate stack for audio
```

### Serial Monitor Enhancements
```ini
monitor_filters =
    esp32_exception_decoder  # Decode crash dumps
    colorize                 # Color output
    time                     # Timestamps
```

## 🎨 Design Patterns Used

### 1. **Facade Pattern** (Managers)
Each manager provides a simple interface to complex subsystems:
- `I2SManager` → ESP-IDF I2S driver
- `BLEManager` → NimBLE stack
- `NetworkManager` → WiFi + WebSocket

### 2. **Observer Pattern** (Callbacks)
Managers use callbacks to notify application of events:
```cpp
bleManager.onAudioReceived([](const uint8_t* data, size_t len) {
    audioManager.appendAudioData(data, len);
});
```

### 3. **Singleton Pattern** (Static Instance)
NetworkManager uses static instance for C-style callbacks:
```cpp
static NetworkManager* instance;  // For static callback bridge
```

### 4. **Dependency Injection**
AudioManager receives I2SManager reference:
```cpp
AudioManager audioManager(i2sManager, ...);
```

## 🔄 Code Reusability

### Shared Modules
`I2SManager` and `AudioManager` are **identical** in both projects:
```bash
C6_App_imp/lib/I2SManager/    ←→  C6Imp/lib/I2SManager/
C6_App_imp/lib/AudioManager/  ←→  C6Imp/lib/AudioManager/
```

**Benefits**:
- Fix once, fixes everywhere
- Consistent behavior
- Reduced maintenance

### Project-Specific Modules
- **BLE Project**: `BLEManager`
- **WebSocket Project**: `NetworkManager`

## 🧪 Testing & Debugging

### Module Isolation
Each module can be tested independently:
```cpp
// Test I2S without BLE
I2SManager i2s(18, 22, 16, 20);
i2s.initMicrophone(16000);
// Read samples...
```

### Debug Macros
Control debug output via config:
```cpp
#define DEBUG_ENABLED true    // Enable in config.h

DEBUG_PRINTLN("[I2S] Init OK");   // Only prints if enabled
```

### Compile-Time Configuration
Change settings without modifying source:
```cpp
// In config.h
#define MIC_SAMPLE_RATE 8000  // Was 16000
```

## 📊 Memory Efficiency

### Before (Monolithic)
- Global variables scattered throughout
- Hard to track memory usage
- Difficult to optimize

### After (Modular)
- Clear ownership (managers own their data)
- Easy to see memory allocation
- Can use RAII (constructors/destructors)

### Audio Buffer
```cpp
// AudioManager.cpp
audioBuffer = new uint8_t[maxBufferSize];  // Clear allocation
~AudioManager() { delete[] audioBuffer; }   // Automatic cleanup
```

## 🚀 Performance Benefits

1. **Compiler Optimizations**: `-O2` flag for speed
2. **Dead Code Elimination**: `-ffunction-sections` removes unused code
3. **Inlining**: Small functions can be inlined by compiler
4. **Cache Locality**: Related data grouped in manager classes

## 📈 Scalability

### Adding New Features

**Example**: Add LED status indicator

1. Create `lib/LEDManager/`
2. Implement in isolation
3. Integrate via callbacks:
```cpp
bleManager.onConnectionChange([](bool connected) {
    ledManager.setState(connected ? LED_CONNECTED : LED_DISCONNECTED);
});
```

### Modifying Existing Features

**Example**: Change sample rate

1. Edit `include/config.h`:
```cpp
#define MIC_SAMPLE_RATE 8000  // Changed from 16000
```

2. Rebuild - no code changes needed!

## 🔧 Maintenance Advantages

### Bug Fixes
- Isolated modules limit bug scope
- Easy to identify which module has the issue
- Fix in one place

### Code Reviews
- Reviewers can focus on one module at a time
- Clear interfaces make changes obvious

### Documentation
- Each module is self-contained
- Headers show public API
- Implementation details hidden

## 💡 Best Practices Demonstrated

1. **Single Responsibility**: Each module does one thing well
2. **DRY (Don't Repeat Yourself)**: Shared code in reusable modules
3. **Encapsulation**: Hide implementation details
4. **Loose Coupling**: Modules communicate via clean interfaces
5. **High Cohesion**: Related functionality grouped together

## 🎓 Learning Resources

To understand the architecture better:
1. Read `config.h` to see all settings
2. Examine header files (`.h`) for public APIs
3. Study `main.cpp` to see how modules interact
4. Review implementation files (`.cpp`) for details

## 🔄 Migration from Original Code

Original files are preserved:
- `src/main.cpp.backup` - Original implementation
- `*.ino` files - Original Arduino sketches

Compare to see transformation:
```bash
diff src/main.cpp.backup src/main.cpp
```

## 📝 Summary

**Before**: 470 lines of monolithic code, everything in one file

**After**:
- **Main app**: ~180 lines (clean, readable)
- **Modules**: ~600 lines (reusable, testable)
- **Total**: More code, but infinitely more maintainable

**Trade-off**: Slight increase in code size for massive gains in:
- ✅ Maintainability
- ✅ Testability
- ✅ Reusability
- ✅ Clarity
- ✅ Scalability

---

**This architecture follows professional embedded systems practices and makes the codebase production-ready.**
