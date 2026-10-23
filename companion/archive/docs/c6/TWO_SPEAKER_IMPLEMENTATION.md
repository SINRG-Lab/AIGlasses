## ESP32-C6 BLE Voice Assistant – Dual Speaker Implementation

This document describes the **two-speaker (dual MAX98357A) implementation** used by the `C6_App_imp` PlatformIO project.  
The code lives in `C6_App_imp.ino` and drives **two speakers from a single I2S peripheral** on the ESP32‑C6.

---

## 1. Confirmation: This build is dual-speaker

The current implementation explicitly targets **two speakers driven from one I2S bus**:

- The I2S speaker configuration uses **stereo slots**:
  - `cfg.channel_format = I2S_CHANNEL_FMT_RIGHT_LEFT;`
- The comments describe two amplifiers with different LR/GAIN wiring:
  - AMP1: `LR/GAIN = GND` → listens to the **LEFT** I2S slot  
  - AMP2: `LR/GAIN = 3.3V` → listens to the **RIGHT** I2S slot
- The `playSpeaker()` function **interleaves mono samples into L and R** so both amps get the same audio:
  - For each mono sample: `stereoChunk[2*i] = L`, `stereoChunk[2*i+1] = R (same value)`

Together this confirms the firmware is for a **two-speaker / dual-amplifier** setup on a shared bus.

---

## 2. End-to-End System Overview (ESP32 + Android App)

This project consists of **two main pieces** inside this folder:

- **Firmware**: `C6_App_imp` (PlatformIO / `C6_App_imp.ino`) running on ESP32‑C6 with **dual speakers**.
- **Android app**: `AIglasses` (Kotlin, Jetpack Compose) which:
  - Scans and connects to the ESP32‑C6 over BLE (`BleVoiceService.kt`).
  - Streams mic audio from ESP32 to Android.
  - Uses OpenAI APIs for transcription, chat, and TTS (`OpenAIService.kt`).
  - Streams AI voice responses back to ESP32 for playback on **both speakers**.

High-level flow:

1. You hold the physical PTT button on the ESP32‑C6 board.
2. ESP32 records mic audio and sends it to the Android app over BLE.
3. Android app:
   - Uses Whisper to transcribe speech.
   - Uses Chat (GPT) to generate a reply.
   - Uses TTS to synthesize the reply audio.
4. Android app sends the synthesized audio back to ESP32 via BLE.
5. ESP32 plays the audio on **two speakers** using the dual MAX98357A setup described below.

---

## 3. Hardware Overview

- **MCU Board**: ESP32‑C6 DevKitC‑1
- **Microphone**: INMP441 (I2S)
- **Amplifiers**: 2 × MAX98357A (I2S, mono each)
- **Speakers**: 2 × 4–8 Ω speakers (one per MAX98357A)
- **Input**: Push‑to‑talk button
- **Transport**: Bluetooth Low Energy (BLE) to Android `AIGlasses` app

The ESP32‑C6 has **one I2S peripheral** which is time‑shared between:

- **Mic mode (RX)** – reads audio from INMP441
- **Speaker mode (TX)** – sends audio to both MAX98357A amplifiers

The firmware switches safely between these modes as needed.

---

## 4. Pinout – Shared I2S Bus + Dual Amps

### 4.1 ESP32‑C6 Core I/O

- **BCLK (I2S bit clock)**: `GPIO18` (shared between mic & amps)
- **WS / LRCLK (word select)**: `GPIO22` (shared between mic & amps)
- **MIC_SD (data in)**: `GPIO16` → INMP441 `SD`
- **AMP_DIN (data out)**: `GPIO20` → both MAX98357A `DIN`
- **PTT (push‑to‑talk button)**: `GPIO23` → button → GND (active LOW)

Power and ground:

- 3.3V → INMP441 `VDD`, MAX98357A `VIN`, logic pins as needed
- GND → INMP441 `GND`, MAX98357A `GND`, button GND, speaker returns

### 4.2 INMP441 Microphone Wiring

Typical wiring:

- `GPIO18` → INMP441 `SCK`
- `GPIO22` → INMP441 `WS`
- `GPIO16` → INMP441 `SD`
- `GND` → INMP441 `GND`
- `3.3V` → INMP441 `VDD`
- `GND` → INMP441 `L/R` (select LEFT channel output)

### 4.3 Two × MAX98357A Amplifiers

Both amplifiers share the **same I2S signals**:

- `GPIO18` → both MAX98357A `BCLK`
- `GPIO22` → both MAX98357A `LRCLK`
- `GPIO20` → both MAX98357A `DIN`

Per‑amplifier configuration:

- **AMP1 (Speaker 1 – LEFT slot)**
  - `GAIN/LR` → **GND** (listens to LEFT channel)
  - `VIN` → 3.3V
  - `GND` → board GND
  - `SD_MODE` → tied **enabled** (always on; no separate GPIO in this build)
  - `Speaker+` / `Speaker-` → first speaker

- **AMP2 (Speaker 2 – RIGHT slot)**
  - `GAIN/LR` → **3.3V** (listens to RIGHT channel)
  - `VIN` → 3.3V
  - `GND` → board GND
  - `SD_MODE` → tied **enabled** (always on)
  - `Speaker+` / `Speaker-` → second speaker

> **Important**  
> There is **no separate SD_MODE control pin** in this implementation; both amps are always enabled.  
> The firmware simply stops sending I2S data when playback is done.

---

## 4. Firmware Architecture (Dual Speaker Aspects)

### 4.1 I2S Mode Switching

- `i2sMicInit()` configures:
  - Mode: `I2S_MODE_MASTER | I2S_MODE_RX`
  - Sample rate: `MIC_SR = 16000`
  - 32‑bit slots (`I2S_BITS_PER_SAMPLE_32BIT`) for INMP441’s 24‑bit data
  - Channel format: `I2S_CHANNEL_FMT_ONLY_LEFT`
  - Pins: `BCLK=18`, `WS=22`, `data_in=16`

- `i2sSpkInit()` configures:
  - Mode: `I2S_MODE_MASTER | I2S_MODE_TX`
  - Sample rate: `SPK_SR = 22050`
  - 16‑bit samples (`I2S_BITS_PER_SAMPLE_16BIT`)
  - **Stereo format**: `I2S_CHANNEL_FMT_RIGHT_LEFT`
  - Pins: `BCLK=18`, `WS=22`, `data_out=20`
  - Larger DMA buffers with APLL enabled for smoother playback

Whenever switching between mic and speaker, the driver is **uninstalled then re‑installed** on `I2S_NUM_0` to avoid conflicts on the single peripheral.

### 4.2 Dual-Speaker Playback Logic

- Incoming TTS audio from Android is buffered in `audioBuffer` as **16‑bit mono PCM** at 22.05 kHz.
- `playSpeaker()`:
  1. Calls `i2sSpkInit()` to switch I2S to TX/stereo mode.
  2. Converts mono samples into an interleaved stereo buffer:
     - `L = sample`, `R = sample` → both speakers play the same audio.
  3. Writes each interleaved chunk to I2S via `i2s_write()`.
  4. Prints progress and timing information over serial.
  5. When done, switches back to mic mode via `i2sMicInit()`.

Because AMP1 listens to the LEFT slot and AMP2 to the RIGHT slot, interleaving mono as **L = R** guarantees **both speakers are active**.

---

## 5. BLE / Audio Flow (High Level)

- **ESP32‑C6 → Android**
  - Records mic at 16 kHz while the PTT button is held.
  - Chops audio into chunks and sends them over BLE using the audio TX characteristic.

- **Android → ESP32‑C6**
  - Android app converts AI text responses to 16‑bit mono PCM at 22.05 kHz.
  - Sends audio chunks over BLE using the audio RX characteristic with tag `'A'`.
  - Sends an `'E'` control marker when the response is complete.

- **On `'E'` control marker**:
  - ESP32‑C6 sets a flag and then calls `playSpeaker()` in `loop()`.
  - Audio is played out of **both speakers** via dual MAX98357A amps.

---

## 6. Android App (`AIglasses`) – How It Fits

The `AIglasses` folder contains the **Android companion app**. Its key pieces are:

- `MainActivity.kt`
  - Jetpack Compose UI.
  - Lets you enter / store your OpenAI API key.
  - Provides a **“Scan & Connect”** button to start BLE discovery.
  - Shows live status (BLE, ESP32 connection) and a scrolling **conversation log**:
    - `USER`: what you said (after transcription).
    - `AI`: the model’s answer text.
    - `BLE` / `AUDIO` / `TTS`: connection and audio events.

- `BleVoiceService.kt`
  - Runs as a BLE **GATT client (central)**.
  - Scans for the ESP32‑C6 advertising the service UUID
    `0000aa00-1234-5678-abcd-0e5032c6b1e0` (must match the firmware).
  - Negotiates a large MTU (target 512) for efficient audio streaming.
  - Subscribes to:
    - Audio TX characteristic: receives mic audio from ESP32.
    - Control characteristic: receives start/end markers.
  - Buffers an utterance of mic audio, then triggers the voice pipeline when it sees an `'E'` (end) marker.
  - After processing, sends TTS audio back over the Audio RX characteristic with the same `[TAG][SEQ][PCM]` framing used on ESP32.

- `OpenAIService.kt`
  - Wraps **three OpenAI endpoints**:
    - Whisper (`/v1/audio/transcriptions`) to turn mic WAV into text.
    - Chat (`/v1/chat/completions`) with a smart‑glasses‑optimized system prompt.
    - TTS (`/v1/audio/speech`) to generate MP3 speech from the chat reply.
  - The higher‑level `VoiceAssistantPipeline` class (in `VoiceAssistantPipeline.kt`) coordinates:
    - Saving BLE audio to WAV.
    - Calling `OpenAIService.transcribe`, `chat`, and `speak`.
    - Converting returned audio into 16‑bit PCM for the ESP32 speakers.

This Android app is therefore the **brains and network connection** of the system:

- ESP32‑C6 handles **I2S audio IO + BLE transport + dual‑speaker playback**.
- Android `AIglasses` app handles **cloud AI logic and TTS**, then returns audio for playback.

---

## 7. Wiring Checklist for Two Speakers

To verify a correct dual‑speaker build:

1. **I2S Signals**
   - `GPIO18` → both MAX98357A `BCLK`
   - `GPIO22` → both MAX98357A `LRCLK`
   - `GPIO20` → both MAX98357A `DIN`

2. **Left / Right Selection**
   - AMP1 `GAIN/LR` → **GND**
   - AMP2 `GAIN/LR` → **3.3V**

3. **Power**
   - Both amps `VIN` → 3.3V
   - Both amps `GND` → board GND

4. **Speakers**
   - Speaker 1 on AMP1 `SPK+` / `SPK-`
   - Speaker 2 on AMP2 `SPK+` / `SPK-`

5. **Mic and Button**
   - INMP441 wired to `GPIO18/22/16` as above.
   - PTT button between `GPIO23` and GND.

If **only one speaker** is working:

- Confirm that both amps see `BCLK`, `LRCLK`, and `DIN`.
- Double‑check `GAIN/LR` wiring: one **must** be GND, the other **must** be 3.3V.
- Inspect solder joints on the silent amp and its speaker.

---

## 8. Notes and Limitations

- Only one I2S peripheral (I2S0) is used; **full‑duplex (simultaneous record + play) is not supported**.
- Both speakers always play **the same mono audio** (duplicated to L & R).
- There is **no software mute / SD_MODE** pin in this build; muting is achieved by stopping I2S output.
- BLE MTU is configured to 512 bytes for efficient audio streaming.

This document should be used as the reference for any **hardware wiring, debugging, or review of the new two‑speaker ESP32‑C6 implementation**.

