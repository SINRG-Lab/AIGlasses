# Quick Start Guide - ESP32-C6 Voice Assistant

## Files Overview

1. **esp32c6_voice_assistant.ino** - Basic version (mic only, no speaker)
2. **esp32c6_voice_assistant_with_speaker.ino** - Full version (mic + speaker) ⭐ **USE THIS ONE**
3. **servert.py** - Enhanced server with detailed logging ⭐ **USE THIS ONE**

## Fast Setup (5 Steps)

### Step 1: Wire Hardware
```
Microphone (INMP441):
  GPIO18 -> SCK
  GPIO19 -> WS
  GPIO21 -> SD
  3.3V -> VDD, CHIPEN
  GND -> GND, L/R

Speaker (MAX98357A):
  GPIO01 -> BCLK
  GPIO02 -> LRCLK
  GPIO22 -> DIN
  GPIO03 -> SD_MODE
  3.3V -> VIN
  GND -> GND, GAIN_SLOT

Button:
  GPIO20 -> Button -> GND
```

### Step 2: Configure ESP32 Code
Open `esp32c6_voice_assistant_with_speaker.ino` and update:
```cpp
const char* WIFI_SSID = "YourWiFi";        // Line 6
const char* WIFI_PASS = "YourPassword";    // Line 7
const char* WS_HOST = "your.ngrok.dev";    // Line 12
```

### Step 3: Upload to ESP32-C6
1. Arduino IDE → Board: "XIAO_ESP32C6"
2. Install library: "WebSockets" by Markus Sattler
3. Upload the sketch
4. Open Serial Monitor (115200 baud)

### Step 4: Start Server
```bash
# Terminal 1: Run server
export OPENAI_API_KEY="sk-your-key-here"
python servert.py

# Terminal 2: Run ngrok
ngrok http --scheme=http 8765
```

Copy ngrok URL and update in ESP32 code (Step 2), then re-upload.

### Step 5: Test!
1. Wait for "✅ Connected to server" in Serial Monitor
2. Press and hold GPIO20 button
3. Speak your question
4. Release button
5. Listen for AI response from speaker!

## What You Should See

### Serial Monitor (ESP32):
```
[WS] ✅ Connected to server
[PTT] 🔴 Pressed -> Streaming audio...
[PTT] 🔴 Released -> Sent END marker
[AUDIO] 📥 Receiving... 10 chunks
[AUDIO] 🏁 End marker received
[AMP] Enabled
[AUDIO] Playing... 50.0%
[AUDIO] Playback complete!
[AMP] Disabled
```

### Server Terminal:
```
✅ ESP32 connected
==================================================
👤 USER SAID:
hello how are you
==================================================
🤖 AI RESPONSE:
I'm doing well, thank you for asking! How can I help you today?
==================================================
📤 Sending audio to ESP32...
✅ Complete!
```

## Troubleshooting Quick Fixes

| Problem | Solution |
|---------|----------|
| No WiFi connection | Check SSID/password, verify 2.4GHz WiFi |
| WebSocket won't connect | Update ngrok URL in code, verify server running |
| No audio from speaker | Check GPIO03 wire, verify speaker 4-8Ω |
| Distorted audio | Connect GAIN_SLOT to GND, check power supply |
| ESP32 crashes | Use better power supply (USB 3.0 or 5V 2A) |
| No AI response | Check OpenAI API key, verify internet |

## Pin Quick Reference

| Function | ESP32-C6 Pin | Component Pin |
|----------|--------------|---------------|
| MIC_BCLK | GPIO18 | INMP441 SCK |
| MIC_LRCLK | GPIO19 | INMP441 WS |
| MIC_SD | GPIO21 | INMP441 SD |
| AMP_BCLK | GPIO01 | MAX98357A BCLK |
| AMP_LRCLK | GPIO02 | MAX98357A LRCLK |
| AMP_DIN | GPIO22 | MAX98357A DIN |
| AMP_SD_MODE | GPIO03 | MAX98357A SD_MODE |
| PTT_BUTTON | GPIO20 | Button to GND |

## Important Notes

⚠️ **Use servert.py, not server_stt.py** - The new server has better logging and debugging

⚠️ **Speaker is 4-8Ω** - Don't use headphones directly (use 3.5mm jack breakout if needed)

⚠️ **Power matters** - USB 2.0 may not provide enough current for speaker at high volume

✅ **GAIN_SLOT to GND** - Recommended starting configuration (9dB gain)

✅ **Sample rates differ** - Mic: 16kHz, Speaker: 22.05kHz (this is correct!)

## Need More Help?

See detailed documentation:
- **README_WITH_SPEAKER.md** - Complete documentation
- **README.md** - Original documentation (mic only)

Check the server terminal and ESP32 serial monitor for detailed error messages!
