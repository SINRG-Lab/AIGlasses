# Companion glasses

Glasses tethered to a phone. The ESP32-S3 firmware streams mic audio and photos to the
phone over BLE (photos can also go over a WiFi SoftAP lane); the phone app runs a
GPT Realtime session and streams the reply audio back to the glasses speakers.

| Folder | What |
|---|---|
| [`firmware/S3_App_V2/`](firmware/S3_App_V2/) | Glasses firmware. Start with its README; protocol in `docs/BLE_PROTOCOL.md` and `docs/WIFI_LINK.md`. |
| [`android/`](android/) | Android app (open this folder in Android Studio). |
| [`ios/`](ios/) | iOS app (open `iOSGlasses.xcodeproj`). |
| [`bench-rig/HardwareTest/`](bench-rig/HardwareTest/) | USB smoke-test sketch, BLE voice prototype, and `glasses_sim.py` to test the apps without hardware. |
| [`hardware/pcb/`](hardware/pcb/) | Schematic and V2 PCB fab files. |
| [`docs/`](docs/) | `PARTS_AND_CONNECTIONS.md`, performance results, June 2026 engineering handoff (`.docx`). |
| [`archive/`](archive/) | Out-of-date docs and test sketches, kept for reference. |

Both apps pair with `S3_App_V2` and get short-lived OpenAI Realtime keys from a Supabase Edge
Function (`realtime-session`). Earlier firmware generations are git tags; see the root README.
