# ESP32-C6 I2S Limitation - Important!

## The Problem

The compilation error you saw:
```
error: 'I2S_NUM_1' was not declared in this scope
```

This is because **ESP32-C6 only has ONE I2S peripheral** (I2S_NUM_0), while ESP32-S3 has two (I2S_NUM_0 and I2S_NUM_1).

## The Solution

We modified the code to use **time-sharing** on the single I2S peripheral:
- **During recording**: I2S_NUM_0 is configured for microphone input
- **During playback**: I2S_NUM_0 is reconfigured for speaker output
- The code automatically switches between modes

## How It Works

### Recording Phase:
1. I2S is configured for **RX mode** (microphone)
2. Pins: GPIO18 (BCLK), GPIO19 (LRCLK), GPIO21 (SD)
3. You press button → audio streams to server

### Playback Phase:
1. Server sends audio back
2. Code **uninstalls** mic driver
3. Code **reinstalls** I2S in **TX mode** (speaker)
4. Pins: GPIO01 (BCLK), GPIO02 (LRCLK), GPIO22 (DIN)
5. Audio plays through MAX98357A
6. Code switches back to **RX mode** (mic ready)

## What This Means

✅ **Advantages:**
- Works on ESP32-C6 hardware
- Full duplex not needed (you talk, then AI responds)
- Same functionality as ESP32-S3 version

⚠️ **Limitations:**
- **Cannot record and play simultaneously** (but this isn't needed for voice assistant)
- Small delay (~50-100ms) when switching between mic and speaker modes
- Must wait for playback to finish before recording again

## Code Changes Made

### Added I2S Mode Tracking:
```cpp
enum I2SMode { MODE_NONE, MODE_MIC, MODE_SPEAKER };
static I2SMode currentI2SMode = MODE_NONE;
```

### Modified Initialization Functions:
Both `i2sMicInit()` and `i2sSpkInit()` now:
1. Uninstall existing I2S driver if present
2. Reinstall with new configuration
3. Update mode tracker

### Modified Playback Function:
```cpp
void playAudioBuffer() {
  i2sSpkInit();        // Switch to speaker mode
  enableAmp();
  // ... play audio ...
  disableAmp();
  i2sMicInit();        // Switch back to mic mode
}
```

## Files to Use

### ✅ Use This File (Fixed):
```
esp32c6_voice_assistant_with_speaker_fixed.ino
```

### ❌ Old File (Won't Compile):
```
esp32c6_voice_assistant_with_speaker.ino  (has I2S_NUM_1 error)
```

## Verification

When you upload the fixed code, you should see in Serial Monitor:

```
🎙️  ESP32-C6 VOICE ASSISTANT (SINGLE I2S)
============================================================

[I2S] Installing MIC driver...
[I2S] MIC configured OK

... (recording happens) ...

[AUDIO] 🏁 End marker received
[AUDIO] 🔊 Ready to play!
[I2S] Installing SPEAKER driver...
[I2S] SPEAKER configured OK
[AMP] Enabled
[AUDIO] Playing... 50.0%
[AUDIO] Playback complete!
[AMP] Disabled
[I2S] Installing MIC driver...
[I2S] MIC configured OK
```

Notice the driver being reinstalled when switching modes!

## ESP32 Variant Comparison

| Feature | ESP32-S3 | ESP32-C6 |
|---------|----------|----------|
| I2S Peripherals | 2 (I2S0, I2S1) | 1 (I2S0 only) |
| Simultaneous mic+speaker | Yes | No (time-share) |
| Voice assistant usage | Full duplex | Half duplex |
| Our solution | Uses both I2S | Switches single I2S |

## Why This Works for Voice Assistants

In a typical voice assistant flow:
1. **User talks** → mic records (I2S in RX mode)
2. User stops talking
3. **Server processes** → ESP32 receives audio data
4. **AI responds** → speaker plays (I2S switches to TX mode)
5. AI finishes
6. **Ready for next question** → I2S switches back to RX mode

Since you're not talking and listening at the same time, the single I2S peripheral is sufficient!

## Technical Notes

- **Switch time**: ~50-100ms (mostly delay for amp stabilization)
- **Buffer size**: 200KB (enough for ~4.5 seconds of audio @ 22.05kHz)
- **Sample rates**: Mic: 16kHz, Speaker: 22.05kHz (different rates work fine)
- **Mode tracking**: Prevents unnecessary reinstallation

## Future Enhancements (Optional)

If you later need true simultaneous audio:
1. Use external I2S codec with multiple channels
2. Use ESP32-S3 instead (has dual I2S)
3. Use PDM microphone + I2S speaker (different interfaces)

For now, the time-sharing solution is perfect for voice assistant applications! 🎤🔊
