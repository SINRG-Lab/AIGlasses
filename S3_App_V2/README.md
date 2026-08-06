# S3_App_V2 — AI Smart Glasses Firmware

Modular firmware for the **Seeed XIAO ESP32-S3 Sense**. Voice + vision
assistant glasses: push-to-talk mic streaming, camera snapshots, and streaming
TTS playback to the companion iOS app (`iOSGlasses`).

**Transport policy (V2):** BLE is the primary, always-on link — control
markers and realtime voice audio ride it at all times. An app-enabled **WiFi
SoftAP + TCP socket** ([docs/WIFI_LINK.md](docs/WIFI_LINK.md)) is an optional
bulk plane: photos route over it per image, and only when it is measurably
faster than BLE. Photos work over BLE alone whenever WiFi is off. The BLE
protocol stays backward-compatible ([docs/BLE_PROTOCOL.md](docs/BLE_PROTOCOL.md)).

## Highlights

- **Modular code** — single-purpose modules (see [docs/ARCHITECTURE.md](docs/ARCHITECTURE.md)).
- **Barge-in** — pressing the button while the glasses are speaking cancels TTS playback.
- **Realtime voice mode** — G.711 µ-law both directions for GPT Realtime speech-to-speech.
- **Per-image transport routing** — measured-speed decision, BLE by default;
  `[IMG]` / `[LINK-STATS]` serial logs plus a binary `'T'` stats packet to the app every 5 s.
- **Liveness** — `'P'` ping echo on both transports; a WiFi socket with no ping
  for 10 s is closed by the firmware watchdog.
- **Status LED** — glanceable state on the XIAO's user LED (no serial monitor needed).
- **Non-blocking input** — millis()-based debounce replaces blocking double-reads.

The live-video feature was removed in Transport V2 (triple-tap is a no-op).

## Gestures

| Gesture | Action |
|---|---|
| 1 press + **hold** | Voice question (a photo taken in the last 5 s auto-attaches) |
| Quick **double-tap** | Standalone photo (stored on phone; ask within 5 s to query it) |
| 2 presses, **hold** the 2nd | Photo + voice question bundled (vision AI) |
| Quick **triple-tap** | No-op (video feature removed) |
| Press **during playback** | Cancel TTS playback (barge-in) |

## Status LED (GPIO21, user LED)

| Pattern | Meaning |
|---|---|
| Short flash every second | Advertising — waiting for the phone to connect |
| Off | Connected, idle |
| Solid | Recording (mic streaming) |
| Fast blink | Speaking (TTS playback) |

## Hardware

XIAO ESP32-S3 Sense (built-in PDM mic + OV2640/OV3660 camera), 2× MAX98357A I2S amps
(hardware-panned L/R), push-to-talk button on GPIO6 (active HIGH). Full pinout and
wiring notes in [docs/HARDWARE.md](docs/HARDWARE.md).

## Building

### Arduino IDE
1. Install **esp32 by Espressif Systems ≥ 3.0** (board manager). The new I2S driver
   API (`driver/i2s_std.h`) requires core 3.x.
2. Install **NimBLE-Arduino** (h2zero) ≥ 2.x from the library manager.
3. Board: **XIAO_ESP32S3** · PSRAM: **OPI PSRAM** · open `S3_App_V2.ino` → upload.

### PlatformIO
```ini
[env:seeed_xiao_esp32s3]
platform = https://github.com/pioarduino/platform-espressif32/releases/download/51.03.07/platform-espressif32.zip
board = seeed_xiao_esp32s3
framework = arduino
lib_deps = h2zero/NimBLE-Arduino
build_flags = -DBOARD_HAS_PSRAM
```
Copy the `.h`/`.cpp` files plus the `.ino` (renamed `main.cpp` with `#include <Arduino.h>`
prepended) into `src/`. Verified compiling with this exact configuration.

## Tuning

Everything tunable lives in [config.h](config.h):

| Constant | Default | Effect |
|---|---|---|
| `SPK_VOL_SHIFT` | 0 | Speaker attenuation: 1 = −6 dB, 2 = −12 dB… raise if buzzy/clipping |
| `STREAM_START_THRESHOLD` | 70000 | Pre-buffer bytes before playback. Larger = more latency, fewer cut-outs |
| `RING_SIZE` | 256 KB | TTS ring buffer (PSRAM) |
| `QUICK_TAP_MAX_MS` | 350 | Press shorter than this = quick tap |
| `TAP_WINDOW_MS` | 600 | Multi-tap accumulation window (also the standalone-photo send delay) |
| `MIC_DC_FILTER_R` | 0.97 | High-pass corner ≈ 80 Hz; lower R = more rumble removed |
| `LOG_VERBOSE` | 1 | Set 0 to silence per-chunk serial chatter |

## Documentation

| File | Contents |
|---|---|
| [docs/ARCHITECTURE.md](docs/ARCHITECTURE.md) | Module map, data flow, task/concurrency model |
| [docs/BLE_PROTOCOL.md](docs/BLE_PROTOCOL.md) | Complete GATT protocol spec (UUIDs, framing, `'T'` stats layout) |
| [docs/WIFI_LINK.md](docs/WIFI_LINK.md) | WiFi bulk transport (SoftAP + TCP, `'F'`/`'N'` bootstrap, routing policy) |
| [docs/HARDWARE.md](docs/HARDWARE.md) | Pinout, wiring, audio-path notes |
| [docs/CHANGES_FROM_V1.md](docs/CHANGES_FROM_V1.md) | Every change vs `S3_App_imp`, with rationale |
| [docs/TROUBLESHOOTING.md](docs/TROUBLESHOOTING.md) | Known failure modes and their fixes |
