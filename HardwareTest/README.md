# HardwareTest — bench smoke test

One-file Arduino sketch that exercises every subsystem on the assembled prototype
over plain USB serial. No BLE, no app, no API key.

## Flash it

1. Arduino IDE → open `HardwareTest.ino` (folder name must stay `HardwareTest`).
2. Board: **XIAO_ESP32S3** · Tools → **PSRAM: OPI PSRAM** (mandatory) · port = the
   XIAO's own USB-C.
3. No libraries needed beyond the esp32 core (camera driver is bundled).

## What you should see (Serial Monitor @ 115200)

```
[CAM] sensor PID 0x3660 (OV3660=0x3660, OV2640=0x2642)
[CAM] init: OK
[MIC] init: OK (PDM @ 2.048 MHz)
[INFO] PSRAM: 8192 KB (OK) | free heap: ... | free PSRAM: ...
MIC peak= 1250 avg=  180 |####                    |
```

- **Mic meter** lines stream continuously — talk or tap the little hole on the Sense
  board and the bar must jump. That is "data out the wire."
- Press the **PTT button** → `[BTN] DOWN` / `[BTN] UP`.

## Commands (type in the monitor, no Enter needed)

| Key | Action | Pass looks like |
|---|---|---|
| **hold button** | **record while held → playback on release** | you hear yourself through the glasses speakers |
| `r` | same, fixed 3-second recording | same |
| `b` | 1 kHz beep, 400 ms, moderate volume | clean tone from the speakers |
| `B` | louder 800 Hz beep | use battery/solid 5 V — can brown out weak USB |
| `c` | camera capture (metadata only) | `captured ... (valid JPEG SOI ✓)` |
| `p` | capture + base64 dump of the JPEG | consumed by `view_camera.py` (below) |
| `v` | toggle QVGA 320×240 ↔ VGA 640×480 | VGA for checking lens focus |
| `m` | mic meter on/off | — |
| `i` | PSRAM / heap / camera / uptime | `PSRAM: 8192 KB (OK)` |
| `h` | help | — |

## Always-on voice (GPT Realtime)

The webapp's **Voice: ON** button opens a `gpt-realtime-2.1-mini` session over
WebSocket and bridges it to the glasses through the USB wire (firmware 'S'
streaming mode): mic streams up continuously in 32 ms frames, OpenAI's server
VAD ends your turn 200 ms after you stop talking, and the reply streams down
and starts playing after only ~200 ms of pre-buffer. No button presses — just
talk. Half-duplex: the mic mutes while the glasses speak (no echo canceller on
this hardware), then listening resumes automatically.

While voice is ON the viewfinder pauses (audio owns the serial link).
Latency knobs live at the top of `realtime_bridge.py` (model, voice, VAD
`silence_duration_ms`, instructions).

## Hearing what the mic hears

Hold the PTT button, talk, release — the recording plays back through the speakers,
normalized the same way the real app normalizes for speech-to-text (gain capped at
16×; the serial log prints the raw peak in dBFS and the gain used). Listen for:
clean speech = mic path good; loud hiss = gain maxed on silence (mic port blocked?);
crackle/dropouts = power problem (try battery instead of laptop USB).

## Seeing the camera output

`view_camera.py` grabs frames over the same USB cable (no WiFi, no app):

```bash
pip3 install pyserial          # once
cd HardwareTest

python3 view_camera.py        # one shot: saves photo_HHMMSS.jpg and opens it
python3 view_camera.py --live # ~1 fps viewfinder at http://localhost:8000
```

**Close the Arduino Serial Monitor first** — only one program can hold the port.
`--live` is the useful one for the frame build: point the glasses at things and
watch the browser to aim the camera and check the OV3660's focus (the lens barrel
screws in/out if the image is soft — adjust it BEFORE gluing anything).

## If something fails

| Symptom | First check |
|---|---|
| `PSRAM: 0 KB (MISSING...)` | Tools → PSRAM → OPI PSRAM, reflash |
| `[CAM] init: FAILED` | PSRAM setting first; then FPC seating (both ends), camera ribbon orientation |
| `capture FAILED (fb NULL)` | same as above |
| Mic meter flat at ~0 even when shouting | Sense expansion board seated? (mic lives there) |
| No beep | FFC seated (amp #2 is across it)? Speaker wires? Try `B`. Both amps share one I2S bus — if ONE speaker works the bus is fine, check the other board's amp/jumpers |
| Beep causes reboot/disconnect | USB supply sagging — use battery or a bench 5 V |
| `[BTN]` never fires | button wiring; firmware warns if the pin reads HIGH at boot |

Green across the board here = electronics done, move to the real firmware
(`repo/S3_App_V2/`) and the app — P2 of `FRIDAY_BUILD_PLAN.md`.
