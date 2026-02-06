# ESP32-C6 Voice Assistant - XIAO ESP32-C6

This is the ESP32-C6 port of the working ESP32-S3 voice assistant code.

## Pin Configuration

### INMP441 Microphone (I2S Input)
```
XIAO ESP32-C6 GPIO18 (MIC_BCLK)  -----> INMP441 SCK (Pin 1)
XIAO ESP32-C6 GPIO19 (MIC_LRCLK) -----> INMP441 WS (Pin 3)
XIAO ESP32-C6 GPIO21 (MIC_SD)    -----> INMP441 SD (Pin 2)
XIAO ESP32-C6 3.3V               -----> INMP441 VDD (Pin 6)
XIAO ESP32-C6 GND                -----> INMP441 GND (Pin 5)
XIAO ESP32-C6 GND                -----> INMP441 L/R (Pin 7) - for LEFT channel
XIAO ESP32-C6 3.3V or GPIOx      -----> INMP441 CHIPEN (Pin 8) - enable chip
```

### MAX98357A Speaker Amplifier (I2S Output)
```
XIAO ESP32-C6 GPIO01 (AMP_BCLK)  -----> MAX98357A BCLK (Pin 16)
XIAO ESP32-C6 GPIO02 (AMP_LRCLK) -----> MAX98357A LRCLK (Pin 14)
XIAO ESP32-C6 GPIO22 (AMP_DIN)   -----> MAX98357A DIN (Pin 1)
XIAO ESP32-C6 3.3V               -----> MAX98357A VIN (Power)
XIAO ESP32-C6 GND                -----> MAX98357A GND
MAX98357A GAIN_SLOT (Pin 2)      -----> GND (for 9dB gain) or configure as needed
XIAO ESP32-C6 GPIOy or 3.3V      -----> MAX98357A SD_MODE (Pin 4) - shutdown control
```

#### MAX98357A Gain Settings (GAIN_SLOT Pin 2):
- **GND**: 9dB gain (recommended for testing)
- **3.3V**: 12dB gain
- **Float**: 6dB gain
- **GPIO Control**: Dynamic gain adjustment

#### MAX98357A SD_MODE (Shutdown Pin 4):
- **GND**: Shutdown (amplifier off)
- **3.3V**: Normal operation
- **GPIO Control**: Software enable/disable

### Push-to-Talk Button
```
XIAO ESP32-C6 GPIO20 (PTT_PIN)   -----> Button -----> GND
```
- Uses internal pull-up resistor
- Pressed = LOW, Released = HIGH

## Important Notes

### ESP32-C6 Considerations

1. **I2S Driver**: The ESP32-C6 uses the same ESP-IDF I2S driver as ESP32-S3, so the code is compatible.

2. **Pin Selection**: The selected pins (GPIO18, 19, 21, 1, 2, 22) are general-purpose I/O pins on the XIAO ESP32-C6. Verify your specific board's pinout diagram to ensure these pins are available and not reserved.

3. **PTT Button Pin**: GPIO20 is suggested for the push-to-talk button. You can change this to any available GPIO that suits your hardware layout.

4. **Power Supply**: Ensure your power supply can handle both the ESP32-C6 and the MAX98357A amplifier (especially when driving a speaker at higher volumes).

5. **INMP441 CHIPEN Pin**:
   - Connect to 3.3V for always-on operation
   - Or connect to a GPIO (define `MIC_CHIPEN`) for software control

6. **MAX98357A SD_MODE Pin**:
   - Connect to 3.3V for always-on operation
   - Or connect to a GPIO (define `AMP_SD_MODE`) for software control
   - Connecting to GND will disable the amplifier

## Software Configuration

### WiFi Settings
Update these in the code:
```cpp
const char* WIFI_SSID = "Ayoub";
const char* WIFI_PASS = "12345678";
```

### WebSocket Server
Update the ngrok domain:
```cpp
const char* WS_HOST = "your-ngrok-domain.ngrok-free.dev";
```

### Audio Settings
- Sample Rate: 16000 Hz (16 kHz)
- Bits per Sample: 16-bit PCM
- Channel: Mono (LEFT channel)
- Chunk Size: 512 samples (1024 bytes)

## Required Libraries

Install these libraries in Arduino IDE:
1. **WebSockets** by Markus Sattler
2. **ESP32** board support (includes I2S driver)

### Arduino IDE Board Setup
1. Add ESP32 board manager URL in Preferences:
   ```
   https://espressif.github.io/arduino-esp32/package_esp32_index.json
   ```
2. Install "esp32" by Espressif Systems
3. Select Board: "XIAO_ESP32C6" or "ESP32C6 Dev Module"

## Usage

1. Wire the hardware according to the pin configuration above
2. Update WiFi credentials and ngrok host in the code
3. Upload the sketch to your XIAO ESP32-C6
4. Open Serial Monitor at 115200 baud
5. Wait for WiFi and WebSocket connection
6. Press and hold the PTT button to record audio
7. Release the button to send audio for transcription

## Server Setup

The Python server code (`server_stt.py`) doesn't need modification. Run it on your Mac:

```bash
source venv/bin/activate
python server_stt.py
```

Then tunnel with ngrok:
```bash
ngrok http --scheme=http 8765
```

## Troubleshooting

### I2S Microphone Issues
- Check wiring, especially BCLK, LRCLK, and SD pins
- Verify INMP441 is receiving 3.3V power
- Ensure L/R pin is connected to GND for LEFT channel
- Check CHIPEN is high (3.3V or controlled GPIO)

### WebSocket Connection Issues
- Verify WiFi credentials
- Check ngrok tunnel is active
- Confirm ngrok domain is correct in code
- Check server is running and listening

### No Audio or Garbled Audio
- Check I2S pin connections
- Verify sample rate matches (16kHz)
- Test with Serial Monitor to see if audio chunks are being sent
- Check DMA buffer settings if needed

### Speaker Not Working (Future Implementation)
- Verify MAX98357A wiring
- Check SD_MODE is HIGH (not in shutdown)
- Test with simple tone generation first
- Check speaker connections to MAX98357A output

## Pin Summary Table

| Function | ESP32-C6 GPIO | Component Pin | Notes |
|----------|---------------|---------------|-------|
| MIC_BCLK | GPIO18 | INMP441 SCK | Bit clock |
| MIC_LRCLK | GPIO19 | INMP441 WS | Word select |
| MIC_SD | GPIO21 | INMP441 SD | Serial data |
| MIC_CHIPEN | 3.3V or GPIOx | INMP441 CHIPEN | Enable (high) |
| AMP_BCLK | GPIO01 | MAX98357A BCLK | Bit clock |
| AMP_LRCLK | GPIO02 | MAX98357A LRCLK | Word select |
| AMP_DIN | GPIO22 | MAX98357A DIN | Audio data |
| AMP_SD_MODE | 3.3V or GPIOy | MAX98357A SD_MODE | Enable (high) |
| PTT_BUTTON | GPIO20 | Button to GND | Push to talk |

## Next Steps

Once basic voice recording and transcription works:
1. Implement I2S TX for speaker output
2. Add TTS audio playback functionality
3. Implement bi-directional voice conversation
4. Add optional features (wake word detection, local processing, etc.)
