# ESP32-C6 Voice Assistant with Speaker Output

This implementation includes **full audio input AND output** for the XIAO ESP32-C6.

## What's New

### Enhanced Server (`servert.py`)
- **Detailed logging** of all audio transactions
- Shows incoming audio statistics (chunks, bytes, duration)
- Displays AI responses clearly
- Logs outgoing audio data with progress updates
- Better error handling and debugging info

### Enhanced ESP32-C6 Code (`esp32c6_voice_assistant_with_speaker.ino`)
- **Dual I2S**: I2S0 for microphone, I2S1 for speaker
- **Audio buffering**: Receives and buffers TTS audio from server
- **Automatic playback**: Plays AI response through MAX98357A speaker
- **Amplifier control**: Software control of MAX98357A SD_MODE pin
- **Detailed statistics**: Shows audio reception progress and playback status
- **Better debugging**: Extensive serial output for troubleshooting

## Pin Configuration

### INMP441 Microphone (I2S0 - Input)
```
XIAO ESP32-C6 GPIO18 (MIC_BCLK)  -----> INMP441 SCK (Pin 1)
XIAO ESP32-C6 GPIO19 (MIC_LRCLK) -----> INMP441 WS (Pin 3)
XIAO ESP32-C6 GPIO21 (MIC_SD)    -----> INMP441 SD (Pin 2)
XIAO ESP32-C6 3.3V               -----> INMP441 VDD (Pin 6)
XIAO ESP32-C6 GND                -----> INMP441 GND (Pin 5)
XIAO ESP32-C6 GND                -----> INMP441 L/R (Pin 7) - LEFT channel
XIAO ESP32-C6 3.3V               -----> INMP441 CHIPEN (Pin 8) - enable
```

### MAX98357A Speaker (I2S1 - Output)
```
XIAO ESP32-C6 GPIO01 (AMP_BCLK)  -----> MAX98357A BCLK (Pin 16)
XIAO ESP32-C6 GPIO02 (AMP_LRCLK) -----> MAX98357A LRCLK (Pin 14)
XIAO ESP32-C6 GPIO22 (AMP_DIN)   -----> MAX98357A DIN (Pin 1)
XIAO ESP32-C6 GPIO03 (AMP_SD_MODE)----> MAX98357A SD_MODE (Pin 4)
XIAO ESP32-C6 3.3V               -----> MAX98357A VIN (Power)
XIAO ESP32-C6 GND                -----> MAX98357A GND
MAX98357A GAIN_SLOT (Pin 2)      -----> GND (9dB gain recommended)
```

**Speaker Connection:**
```
MAX98357A Speaker+ (Pin 15) -------> Speaker Red/+
MAX98357A Speaker- (Pin 13) -------> Speaker Black/-
```

### Push-to-Talk Button
```
XIAO ESP32-C6 GPIO20 (PTT_PIN)   -----> Button -----> GND
```

## Audio Flow

### 1. Recording Phase
```
User presses button
  ↓
ESP32 records from INMP441 @ 16kHz
  ↓
Sends audio chunks with 'A' tag to server
  ↓
User releases button → sends 'E' tag
```

### 2. Processing Phase (Server)
```
Server receives audio chunks
  ↓
Saves as utterance.wav
  ↓
Whisper transcribes speech to text
  ↓
OpenAI generates response
  ↓
macOS 'say' converts text to speech @ 22.05kHz
  ↓
Sends audio back with 'A' chunks + 'E' end marker
```

### 3. Playback Phase (ESP32)
```
ESP32 receives audio chunks with 'A' tag
  ↓
Buffers all audio data
  ↓
Receives 'E' end marker
  ↓
Enables MAX98357A amplifier (GPIO03 HIGH)
  ↓
Plays audio through I2S1 @ 22.05kHz
  ↓
Disables amplifier when done (GPIO03 LOW)
```

## Setup Instructions

### 1. Hardware Assembly
1. Wire INMP441 microphone according to pin configuration above
2. Wire MAX98357A amplifier according to pin configuration above
3. Connect a 4-8Ω speaker to MAX98357A output (Speaker+/-)
4. Wire push-to-talk button between GPIO20 and GND

### 2. Arduino IDE Setup
1. Install ESP32 board support in Arduino IDE
2. Install **WebSockets** library by Markus Sattler
3. Select Board: **XIAO_ESP32C6** or **ESP32C6 Dev Module**
4. Update WiFi credentials in the code:
   ```cpp
   const char* WIFI_SSID = "YOUR_WIFI_SSID";
   const char* WIFI_PASS = "YOUR_WIFI_PASSWORD";
   ```
5. Update ngrok domain:
   ```cpp
   const char* WS_HOST = "YOUR_NGROK_HOST";
   ```
6. Upload `esp32c6_voice_assistant_with_speaker.ino`

### 3. Server Setup

#### Install Python dependencies (if not already done):
```bash
pip install websockets faster-whisper numpy soundfile openai
```

#### Set OpenAI API key:
```bash
export OPENAI_API_KEY="your-api-key-here"
```

#### Run the enhanced server:
```bash
python servert.py
```

You should see:
```
============================================================
🎙️  WebSocket Voice Assistant Server
📡 Listening on ws://0.0.0.0:8765
🔊 TTS Sample Rate: 22050 Hz
📦 Chunk Size: 4096 bytes
============================================================
```

#### Start ngrok tunnel (in another terminal):
```bash
ngrok http --scheme=http 8765
```

Copy the ngrok domain and update it in the ESP32 code.

## Expected Serial Monitor Output

### On Connection:
```
============================================================
🎙️  ESP32-C6 VOICE ASSISTANT WITH SPEAKER
============================================================

[WiFi] Connecting...
[WiFi] ✅ Connected
[WiFi] 📡 IP: 192.168.1.100
[WiFi] 📶 RSSI: -45 dBm
[I2S] Installing MIC driver...
[I2S] Setting MIC pins...
[I2S] MIC pins OK
[I2S] Installing SPEAKER driver...
[I2S] Setting SPEAKER pins...
[I2S] SPEAKER pins OK
[WS] Connecting to your-domain.ngrok-free.dev:80/
[WS] ✅ Connected to server

============================================================
🎤 PTT: Hold GPIO20 to talk, release to send
🔊 Speaker: Audio will play automatically after AI response
============================================================
```

### When Recording:
```
[PTT] 🔴 Pressed -> Streaming audio...
[PTT] 🔴 Released -> Sent END marker
```

### When Receiving Audio:
```
[AUDIO] 📥 Receiving... 10 chunks, 40960 bytes total
[AUDIO] 📥 Receiving... 20 chunks, 81920 bytes total
[AUDIO] 🏁 End marker received
[AUDIO] 📊 Total received: 35 chunks, 143360 bytes
[AUDIO] 🔊 Ready to play!
```

### During Playback:
```
[AMP] Enabled
[AUDIO] Playing 143360 bytes (3.25 seconds @ 22050 Hz)
[AUDIO] Playing... 20.0% (28672/143360 bytes)
[AUDIO] Playing... 40.0% (57344/143360 bytes)
[AUDIO] Playing... 60.0% (86016/143360 bytes)
[AUDIO] Playing... 80.0% (114688/143360 bytes)
[AUDIO] Playback complete! (143360 bytes, 70 chunks)
[AMP] Disabled
```

## Server Output Example

```
✅ ESP32 connected
🎤 Receiving audio... 50 chunks (51200 bytes)
🎤 Received complete utterance: 96000 bytes (3.00 seconds)
🎧 Saved: utterance.wav
🧠 Transcribing...

==================================================
👤 USER SAID:
what is the weather today
==================================================

🤖 Getting AI response...
==================================================
🤖 AI RESPONSE:
I don't have access to current weather data. Please check a weather app or website for today's forecast.
==================================================

🔊 Converting text to speech...
📊 Total audio size: 143360 bytes (3.25 seconds)
📤 Sending audio to ESP32...
📤 Sent chunk 10 (28.6% complete)
📤 Sent chunk 20 (57.1% complete)
📤 Sent chunk 30 (85.7% complete)
✅ Sent 35 audio chunks + END marker
✅ Complete! Ready for next utterance.
```

## Troubleshooting

### No Audio Output from Speaker

1. **Check wiring**: Verify all MAX98357A connections
2. **Check amplifier enable**: Ensure GPIO03 is connected to SD_MODE
3. **Check serial monitor**: Look for "AMP Enabled" message
4. **Check speaker**: Test with a known working speaker (4-8Ω)
5. **Check GAIN_SLOT**: Try connecting to GND for 9dB gain
6. **Check power**: MAX98357A needs sufficient power for speaker

### Distorted or Garbled Audio

1. **Check sample rate**: Server sends at 22050 Hz (SPK_SR in code)
2. **Check DMA buffer**: Try increasing `dma_buf_len` in `i2sSpkInit()`
3. **Check power supply**: Insufficient power can cause distortion
4. **Lower volume**: Set GAIN_SLOT to GND (9dB instead of 12dB)

### Audio Cutting Out

1. **Check WiFi signal**: Weak signal can cause packet loss
2. **Increase buffer size**: Modify `MAX_AUDIO_BUFFER_SIZE` if needed
3. **Check delays**: May need to adjust `asyncio.sleep()` in server

### No Audio Received

1. **Check WebSocket connection**: Ensure ESP32 shows "Connected"
2. **Check server logs**: Server should show "Sending audio to ESP32"
3. **Check OpenAI API key**: Server needs valid API key for responses
4. **Check server script**: Make sure you're running `servert.py` (not `server_stt.py`)

### ESP32 Crashes or Resets

1. **Power supply**: Ensure adequate power (use USB 3.0 or external 5V)
2. **Buffer overflow**: Check serial for "Buffer overflow" warnings
3. **Reduce buffer size**: Lower `MAX_AUDIO_BUFFER_SIZE` if memory issues

## Audio Configuration

### Microphone Settings (can be adjusted):
```cpp
static const int MIC_SR = 16000;           // 16 kHz sample rate
cfg.dma_buf_count = 8;                      // 8 DMA buffers
cfg.dma_buf_len = 256;                      // 256 samples per buffer
static const int SAMPLES_PER_CHUNK = 512;  // Send 512 samples at a time
```

### Speaker Settings (can be adjusted):
```cpp
static const int SPK_SR = 22050;           // 22.05 kHz (matches TTS)
cfg.dma_buf_count = 8;                      // 8 DMA buffers
cfg.dma_buf_len = 512;                      // 512 samples per buffer
#define MAX_AUDIO_BUFFER_SIZE (200 * 1024) // 200KB audio buffer
```

### MAX98357A Gain Control:
- **GND**: 9dB gain (quieter, less distortion)
- **3.3V**: 12dB gain (louder, may distort)
- **Float**: 6dB gain (quietest)

## System Requirements

### ESP32-C6
- XIAO ESP32-C6 or compatible board
- At least 520KB RAM (for audio buffers)
- Arduino framework with ESP-IDF v5.0+

### Server (Mac/Linux)
- Python 3.8+
- OpenAI API key with credits
- macOS (for 'say' command) or modify for other TTS
- Stable internet connection

## Performance Notes

- **Latency**: ~2-5 seconds from button release to audio playback start
- **Audio quality**: 22.05 kHz 16-bit mono PCM
- **Buffer size**: Up to 200KB (~4.5 seconds of audio)
- **Memory usage**: ~250KB for buffers + ~100KB code/stack

## Next Steps / Enhancements

1. **Wake word detection**: Add voice activation instead of button
2. **Streaming playback**: Start playing while receiving audio
3. **Volume control**: Add physical volume control knob
4. **LED indicators**: Show recording/playing status
5. **Battery operation**: Add power management features
6. **Local TTS**: Replace cloud TTS with on-device synthesis
7. **Duplex mode**: Allow interruption during playback

## License & Credits

Based on the working ESP32-S3 implementation.
Modified for ESP32-C6 with full speaker support.
