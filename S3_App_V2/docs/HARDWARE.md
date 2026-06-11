# Hardware Reference

Target board: **Seeed XIAO ESP32-S3 Sense** (8 MB PSRAM, built-in PDM mic,
OV2640/OV3660 camera module).

## Pinout

### Speaker — 2× MAX98357A, shared I2S bus (STD TX on I2S_NUM_1)

| Signal | GPIO | MAX98357A pin |
|---|---|---|
| BCLK | 9 | BCLK |
| LRC / WS | 5 | LRC |
| DIN | 8 | DIN |

Both amps share BCLK/LRC/DIN; left/right panning is done in hardware via each
amp's SD_MODE channel-select resistor. There is no software SD_MODE control —
the amps are always on, which is why the firmware drives DIN low and deletes the
I2S channel after playback (idle clocking = audible hiss).

MAX98357A gain is fixed by the GAIN pin (GND = 9 dB). Full-scale digital audio
drives it into clipping — if output is buzzy, raise `SPK_VOL_SHIFT` in `config.h`
before suspecting wiring.

### Microphone — built-in PDM mic (MSM261D3526H1CPM, PDM RX on I2S_NUM_0)

| Signal | GPIO |
|---|---|
| PDM_CLK | 42 |
| PDM_DATA | 41 |

Fixed by the Sense board layout. Note: the PDM clock crosstalks into the speaker
BCLK trace, so the firmware pauses the mic during playback.

> **Future hardware note (no firmware support yet):** at-distance speech pickup
> is the current weak point. A planned PCB revision will add external mic
> capsule(s) with proper acoustic porting — mic placement at the bottom edge of
> the frame near a hinge, aimed toward the mouth, matters more than capsule
> count. Firmware changes for that wait until the hardware revision is settled.

### Camera (OV2640/OV3660 on the Sense expansion board)

| Signal | GPIO | Signal | GPIO |
|---|---|---|---|
| XCLK | 10 | Y9 | 48 |
| SIOD | 40 | Y8 | 11 |
| SIOC | 39 | Y7 | 12 |
| VSYNC | 38 | Y6 | 14 |
| HREF | 47 | Y5 | 16 |
| PCLK | 13 | Y4 | 18 |
| | | Y3 | 17 |
| | | Y2 | 15 |

Capture config: QVGA (320×240) JPEG, quality 12, 2 frame buffers in PSRAM,
`CAMERA_GRAB_LATEST`. QVGA is deliberate — it keeps each frame to ~25–30 BLE
fragments, which bounds packet loss and per-frame latency.

### Inputs / outputs

| Function | GPIO | Notes |
|---|---|---|
| Push-to-talk | 6 | Active **HIGH**, `INPUT_PULLDOWN`; firmware warns if HIGH at boot |
| Status LED | 21 | XIAO user LED, active **LOW** |

## Power notes

- Two MAX98357A amps at volume can draw bursts well above what a laptop USB port
  sags through the XIAO's regulator — brownouts show up as BLE disconnects during
  playback. Use a solid 5 V supply or battery for speaker testing.
- PSRAM budget: camera frame buffers (2× QVGA JPEG) + 256 KB ring buffer + one
  held snapshot — comfortably inside 8 MB, but the **camera must init first**
  (see ARCHITECTURE.md, setup ordering).
