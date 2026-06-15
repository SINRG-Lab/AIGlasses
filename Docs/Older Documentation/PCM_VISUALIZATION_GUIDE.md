# PCM Data Visualization Guide

## What You'll See in Serial Monitor

The updated code now displays **PCM (audio) data** as it's received from the server. This helps you verify that audio is being transmitted correctly.

## Example Output

### When Audio Chunks Arrive:

```
[AUDIO] 📥 Chunk #1 received (4096 bytes)

[PCM DATA] ========================================
Chunk size: 4096 bytes (2048 samples)
Showing first 16 samples:

Sample[ 0]:    245 | ██
Sample[ 1]:   -180 | ░
Sample[ 2]:    512 | █████
Sample[ 3]:  -1024 | ░░░░░░░░░░
Sample[ 4]:   2048 | ████████████████████
Sample[ 5]:  -3200 | ░░░░░░░░░░░░░░░░░░░░░░░░░░░░░░░░
Sample[ 6]:   1500 | ███████████████
Sample[ 7]:   -890 | ░░░░░░░░
Sample[ 8]:    120 | █
Sample[ 9]:   -450 | ░░░░
Sample[10]:   3840 | ██████████████████████████████████████
Sample[11]:  -2100 | ░░░░░░░░░░░░░░░░░░░░░
Sample[12]:    780 | ███████
Sample[13]:   -990 | ░░░░░░░░░
Sample[14]:   1260 | ████████████
Sample[15]:   -330 | ░░░

Min: -3200 | Max: 3840 | Avg Amplitude: 982
==================================================

[AUDIO] 📥 Chunk #2 received (4096 bytes)
...
```

## Understanding the Display

### PCM Sample Values
- **Positive values**: Represent the positive part of the audio waveform
- **Negative values**: Represent the negative part of the audio waveform
- **Range**: -32768 to +32767 (16-bit signed integer)

### Visualization Bars
- **Solid blocks (█)**: Positive samples
- **Light blocks (░)**: Negative samples
- **Bar length**: Proportional to sample amplitude (longer = louder)

### Statistics
- **Min**: Minimum (most negative) sample value in chunk
- **Max**: Maximum (most positive) sample value in chunk
- **Avg Amplitude**: Average absolute value (overall loudness)

## What to Look For

### Good Audio Signal:
```
Sample values ranging from -3000 to +3000
Avg Amplitude: 800-2000
Mix of positive and negative values
Varying bar lengths (dynamic audio)
```

### Problems to Watch For:

#### 1. **Silent/Zero Audio**
```
Sample[ 0]:      0 |
Sample[ 1]:      0 |
Sample[ 2]:      0 |
...
Avg Amplitude: 0
```
**Means**: No audio data, check server TTS generation

#### 2. **Clipping (Too Loud)**
```
Sample[ 0]:  32767 | (max bars)
Sample[ 1]: -32768 | (max bars)
Sample[ 2]:  32767 | (max bars)
...
Max: 32767 | Min: -32768
```
**Means**: Audio is clipping, reduce volume/gain

#### 3. **Low Amplitude**
```
Sample[ 0]:     12 | (tiny bars)
Sample[ 1]:    -15 | (tiny bars)
Sample[ 2]:     18 | (tiny bars)
...
Avg Amplitude: 15
```
**Means**: Audio too quiet, check gain settings

## Configuration Options

You can adjust these settings at the top of the code:

```cpp
#define SHOW_PCM_DATA true           // Set to false to disable visualization
#define PCM_SAMPLES_TO_SHOW 16       // How many samples to show (1-50)
#define PCM_VISUALIZATION_SCALE 100  // Larger = shorter bars
```

### Adjusting Display:

**Show more samples:**
```cpp
#define PCM_SAMPLES_TO_SHOW 32  // Show 32 samples instead of 16
```

**Make bars longer (more sensitive):**
```cpp
#define PCM_VISUALIZATION_SCALE 50  // Bars will be 2x longer
```

**Make bars shorter (less sensitive):**
```cpp
#define PCM_VISUALIZATION_SCALE 200  // Bars will be 2x shorter
```

**Disable visualization (if too much output):**
```cpp
#define SHOW_PCM_DATA false  // Only show summary statistics
```

## When PCM Data is Shown

The code displays PCM data:
1. **First 3 chunks** - To see initial audio
2. **Every 10th chunk** - To monitor ongoing reception
3. At the **end** - Summary statistics

This prevents overwhelming the serial output while still providing good visibility.

## Typical Values for Speech

### Normal Speech (TTS):
- **Min**: -8000 to -15000
- **Max**: +8000 to +15000
- **Avg Amplitude**: 800 to 3000
- **Sample Rate**: 22050 Hz (in the code as SPK_SR)

### Loud Speech/Music:
- **Min**: -20000 to -30000
- **Max**: +20000 to +30000
- **Avg Amplitude**: 3000 to 8000

### Whisper/Quiet:
- **Min**: -2000 to -5000
- **Max**: +2000 to +5000
- **Avg Amplitude**: 200 to 1000

## Full Example Session

```
[WS] ✅ Connected to server
[PTT] 🔴 Pressed -> Streaming audio...
[PTT] 🔴 Released -> Sent END marker

[AUDIO] 📥 Chunk #1 received (4096 bytes)

[PCM DATA] ========================================
Chunk size: 4096 bytes (2048 samples)
Showing first 16 samples:

Sample[ 0]:    245 | ██
Sample[ 1]:   -180 | ░
Sample[ 2]:   2048 | ████████████████████
Sample[ 3]:  -3200 | ░░░░░░░░░░░░░░░░░░░░░░░░░░░░░░░░
...
Min: -3200 | Max: 3840 | Avg Amplitude: 982
==================================================

[AUDIO] 📥 Chunk #2 received (4096 bytes)
[PCM DATA] ... (similar output)

[AUDIO] 📊 Progress: 10 chunks, 40960 bytes total

[AUDIO] 📥 Chunk #10 received (4096 bytes)
[PCM DATA] ... (shows data)

[AUDIO] 📊 Progress: 20 chunks, 81920 bytes total

[AUDIO] 🏁 End marker received
[AUDIO] 📊 Total received: 35 chunks, 143360 bytes
[AUDIO] 🔊 Ready to play!

[AMP] Enabled
[AUDIO] Playing 143360 bytes (3.25 seconds @ 22050 Hz)
[AUDIO] Playing... 50.0% (71680/143360 bytes)
[AUDIO] Playback complete! (143360 bytes, 70 chunks)
[AMP] Disabled
```

## Troubleshooting with PCM Data

### Problem: No bars showing
- Check if audio is actually being received
- Verify server is sending audio ('A' tags)
- Check SHOW_PCM_DATA is set to true

### Problem: All samples are zero
- Server TTS is not generating audio
- Check OpenAI API key in server
- Verify server shows "Converting to speech" message

### Problem: Bars too small to see
- Reduce PCM_VISUALIZATION_SCALE (try 50)
- Or increase PCM_SAMPLES_TO_SHOW to see more samples

### Problem: Too much output, can't read
- Reduce PCM_SAMPLES_TO_SHOW (try 8)
- Or set SHOW_PCM_DATA to false temporarily

## Additional Notes

- **PCM format**: 16-bit signed little-endian mono audio
- **Sample rate**: 22050 Hz (matches macOS 'say' command output)
- **Each sample**: 2 bytes (16 bits)
- **Typical chunk**: 4096 bytes = 2048 samples ≈ 0.093 seconds of audio

This visualization helps you verify that:
1. ✅ Audio is being transmitted from server
2. ✅ Audio has reasonable amplitude (not silent or clipping)
3. ✅ Audio data looks like speech (varying values)
4. ✅ Data is being buffered correctly before playback
