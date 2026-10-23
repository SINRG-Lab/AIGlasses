# AI Glasses — SINRG Lab, Northeastern

Voice-and-vision AI assistant glasses. The work splits into two designs:

| | **Companion** | **Standalone** |
|---|---|---|
| Idea | Glasses tether to a phone; the phone talks to the cloud | Glasses talk to the cloud directly over cellular |
| Hardware | XIAO ESP32-S3 Sense + custom PCB (camera, PDM mic, 2× MAX98357A) | Walter board (ESP32-S3 + Sequans GM02SP, **LTE-M**) |
| Link | BLE (primary) + WiFi SoftAP for photos | LTE-M |
| AI | GPT Realtime, from the Android/iOS app | Gemini or Deepgram Voice Agent, from the board |
| Status | Working end to end | Software pipeline works; speaker hardware still noisy |
| Folder | [`companion/`](companion/) | [`standalone/`](standalone/) |

```
companion/
  firmware/S3_App_V2/   current glasses firmware (Arduino, ESP32-S3)
  android/              Android app (Kotlin/Compose)
  ios/                  iOS app (SwiftUI)
  bench-rig/            hardware smoke test, BLE voice prototype, glasses simulator
  hardware/pcb/         schematic + V2 PCB fab files
  docs/                 parts & wiring, performance results, engineering handoff
  archive/              older docs/sketches kept for reference
standalone/
  arduino/              current standalone project (PlatformIO)
  micropython-prototype/ earlier MicroPython version (legacy)
mechanical/             3D-printed frame designs (STL/3MF/F3D), shared
```

## Older companion versions

Earlier firmware generations were removed from the tree but are tagged in git:

| Tag | What it is |
|---|---|
| `companion/c6-wifi-server` | ESP32-C6 → Wi-Fi WebSocket → Python server (`servert.py`). Pre-phone design. |
| `companion/c6-ble` | ESP32-C6 BLE firmware + original Android STT→chat→TTS app. No camera. |
| `companion/s3-v1` | First XIAO ESP32-S3 firmware (`S3_App_imp`), adds camera/vision. |
| `companion/s3-v2-ble` | `S3_App_V2` + GPT Realtime + live video + first iOS app, before "Transport V2" (WiFi photo lane). |

`git checkout companion/s3-v1` (or browse the tag on GitHub) to see one.

## Secrets

Never commit API keys. Use the templates (`standalone/*/config*.template*`) and the gitignored
`config.json` / `src/config.h` / `secrets.properties` files they describe.
