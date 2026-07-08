# Parts & Connections Reference

*July 7, 2026 · compiled from the KiCad schematic (`PCB/Smart_Glasses_V1.pdf`), firmware
`config.h`, `docs/HARDWARE.md`, and the prototype photos. Cross-check against your
physical boards — the schematic is V1 and the fabricated boards may have deviations
(the photos show at least one bodge wire).*

---

## 1. System at a glance

```
                                  ┌────────────────────────── GLASSES ──────────────────────────┐
                                  │                                                              │
┌─────────────┐    BLE 2.4 GHz    │  ┌───────────────── XIAO carrier board (custom) ─────────┐  │
│ Android app │◄═════════════════►│  │  XIAO ESP32-S3 Sense ──B2B──► Sense expansion board   │  │
│  (custom)   │   GATT: audio/    │  │   ├─ u.FL ► 2.4 GHz antenna    ├─ PDM mic (onboard)   │  │
└──────┬──────┘   images/control  │  │   ├─ USB-C (flash/debug)       ├─ OV2640/3660 camera  │  │
       │ HTTPS                    │  │   ├─ GPIO6 ◄─ PTT button        └─ (microSD, unused)  │  │
       ▼                          │  │   └─ I2S bus (GPIO9/5/8) ─► MAX98357A #1 ─► Speaker 1 │  │
  OpenAI API                      │  └──────────────────────────┬─────────────────────────────┘  │
  (STT/LLM/TTS/vision)            │                        8-pin FFC (power + I2S + amp ctrl)    │
                                  │  ┌─────────────────── power board (custom) ─┴────────────┐  │
                                  │  │  USB-C (charge) ─► TP4056 charger ─► LiPo (JST-PH)    │  │
                                  │  │  slide switch ─ SS24 diode-OR ─► 5V rail ─► XIAO 5V   │  │
                                  │  │  I2S bus (from FFC) ─► MAX98357A #2 ─► Speaker 2      │  │
                                  │  └────────────────────────────────────────────────────────┘  │
                                  │  all inside 3D-printed frame (custom, V3 arm + hinges)       │
                                  └──────────────────────────────────────────────────────────────┘
```

---

## 2. Off-the-shelf parts (buy/scavenge, no design work)

| Part | Exact type | Role | Have it? (from photos) |
|---|---|---|---|
| **XIAO ESP32-S3 Sense** | Seeed module (schematic symbol 113991254) | The computer: BLE, dual I2S, camera DVP, firmware host | ✅ soldered on carrier |
| **Sense expansion board** | ships with the XIAO Sense | Carries the **PDM mic (MSM261D3526H1CPM)**, the **camera connector**, microSD (unused) | ❌ **not attached — no mic/camera without it** |
| **Camera module** | OV2640 or OV3660 on DVP FPC | Photos + video frames | ❌ not visible |
| **2.4 GHz antenna** | u.FL, XIAO stamp antenna or equivalent | BLE range | ❌ u.FL socket bare |
| **Speakers ×2** | SP-1511S-3, 8 Ω 3 W micro speaker | TTS playback (L/R, hardware-panned) | ✅ wired (stacked pair in photo) |
| **LiPo battery** | 1S, JST-PH connector | Power | ❌ not visible — **see charge-current warning §5** |
| **FFC cable** | 8-pin, pitch per board connectors | Carrier ↔ power board link through the temple | partly (connectors visible) |
| **USB-C cables** | data-capable | Flash (carrier) + charge (power board) | lab stock |
| **Android phone** | BLE 5 capable (for 2M PHY) | Runs the companion app | yours |
| OpenAI API key | — | STT (`gpt-4o-mini-transcribe-2025-12-15`), chat (`gpt-4o-mini`), TTS (`tts-1`), vision (`gpt-4o`) | verify credit |

## 3. Custom parts (designed in this project)

| Part | Source in repo | Contents / notes |
|---|---|---|
| **XIAO carrier PCB** ("right main board", photo 2) | `PCB/` KiCad (`Smart_Glasses_V1.kicad_sch`) | XIAO socket, **MAX98357A amp #1** (U1), **PTT push button** (SW1, GPIO6, 100K pulldown R6), FFC connector, speaker solder pads, ferrite beads (FB1/2) + 220 pF caps on speaker lines (EMI) |
| **Power PCB** ("right power board", photos 1 & 3) | same schematic | **TP4056 charger** (U4) + charge/standby LEDs (D1/D2), **JST-PH battery conn** (J12), **SPDT slide power switch** (SW2), **SS24 Schottky diode-OR** (D3/D4, battery vs USB 5V), USB-C (charge input), **MAX98357A amp #2** (U2), FFC connector |
| **Amp config jumpers** (on-board) | schematic J1/J4 (L/R select), J8–J10 (gain), J11 (**amp supply: 5V vs 3.3V**), J2/J3 (SD_MODE) | This is how L/R panning + 9 dB gain are set in hardware — solder-jumper positions, check them if a speaker is silent/quiet |
| **INMP441 I2S mic** (MK1 + J5/J6) | on the schematic | ⚠️ **Present in the V1 schematic, wired to spare XIAO pins — but the current firmware does not use it** (it reads the onboard PDM mic). Check whether it's even populated on your boards. Not needed Friday; it's the 61 dB-SNR part the old C6 build used. |
| **3D-printed frame** | `Cad Designs/V3/` (RightArm, ArmCover, hinge system, UltiMaker `.ufp`); front frame + left arm only in `Older/V1` | Mind the mic port + camera aperture requirements (§6) |
| **Firmware** | `S3_App_V2/` | ESP32-S3, Arduino + NimBLE ≥ 2.x, **PSRAM=OPI required** |
| **Android app** | `AIglasses/` | Kotlin/Compose, BLE central, OpenAI pipeline; needs `secrets.properties` |
| **BLE GATT protocol** | `S3_App_V2/docs/BLE_PROTOCOL.md` | Custom service `aa00` with 4 characteristics (below) |

---

## 4. How everything connects

### Inside the XIAO ESP32-S3 Sense stack (no wiring — connector-mated)
| Link | Carries |
|---|---|
| OV2640/3660 FPC → Sense expansion board | camera DVP |
| Sense expansion board → XIAO (B2B connector) | camera DVP (GPIOs 10–18, 38–40, 47–48), **PDM mic: CLK=GPIO42, DATA=GPIO41**, SD card (unused) |
| u.FL socket → antenna | 2.4 GHz RF |

### XIAO ↔ custom boards (firmware-verified pins, `config.h`)
| Signal | XIAO pin (GPIO) | Goes to |
|---|---|---|
| I2S BCLK | D10 (GPIO9) | both MAX98357A `BCLK` (amp2 via FFC) |
| I2S LRC/WS | D4 (GPIO5) | both MAX98357A `LRCLK` |
| I2S DIN | D9 (GPIO8) | both MAX98357A `DIN` (shared data, L/R split by SD_MODE jumpers) |
| PTT button | D5 (GPIO6), active-HIGH, 100K pulldown | SW1 on carrier |
| Status LED | GPIO21 (XIAO user LED, active-LOW) | on the XIAO itself |
| 5V in | XIAO `5V` pin | 5V rail from power board (via FFC) |
| 3V3 out | XIAO `3V3` pin | logic supply for amps' control pins etc. |

### Audio out
| Link | Notes |
|---|---|
| MAX98357A #1 OUT± → Speaker 1 (twisted pair, carrier board) | ferrite beads + 220 pF caps in line |
| MAX98357A #2 OUT± → Speaker 2 (twisted pair, power board) | same |
| Amp gain | GAIN pin jumper — GND = 9 dB (current setting per HARDWARE.md) |
| Amp supply | J11 jumper selects 5V or 3.3V rail — 5V = louder, more brownout risk |

### Power tree
```
USB-C (power board) ──► TP4056 charger ──► LiPo (JST-PH J12)
                                              │
LiPo ──► SPDT slide switch ──► SS24 diode-OR ◄── USB 5V
                                    │
                                    ▼
                              5V rail ──► XIAO 5V ──► XIAO onboard reg ──► 3V3
                                    └───► amps (if J11 = 5V)
```
Charge/standby LEDs (D1/D2) show charging state. The XIAO's own USB-C also powers the
stack when flashing.

### Radio + cloud
| Link | Carries |
|---|---|
| XIAO ↔ phone, BLE GATT service `0000aa00-…` | `aa01` mic audio (notify) · `aa02` TTS audio (write) · `aa03` control markers · `aa04` images in-band ('H' header / 'I' fragments / 'J' end) |
| Phone ↔ OpenAI | HTTPS: transcription, chat, TTS PCM, vision |

---

## 5. ⚠️ Flags found while compiling this

1. **TP4056 PROG resistor is 1.2 kΩ → ~1 A charge current.** Safe only for cells
   ≥ ~1000 mAh (1C). If you fit the small 150–500 mAh cell that actually fits a temple,
   **swap R7 first** (10 kΩ → ~130 mA, 4.7 kΩ → ~250 mA) or you're fast-charging a tiny
   cell at 2–7C — a genuine fire risk, not a nicety.
2. **JST polarity**: verify with a multimeter against board silk before first plug.
3. **The INMP441 on the schematic is dead weight for now** — firmware reads the PDM mic
   on the Sense board. Don't chase it when debugging "no mic": no Sense board = no mic.
4. **Both amps always-on** (SD_MODE strapped): firmware drives DIN low after playback to
   kill idle hiss — if you hear hiss anyway, check the J2/J3 SD_MODE jumpers.
5. Speaker current bursts can brown out weak USB supplies → BLE disconnects. Test
   speaker volume on battery or a solid 5 V bench supply.

## 6. Enclosure interfaces (for the printed parts)

- **Mic port**: > 1 mm hole in the arm/cover aligned with the Sense board's mic hole,
  depth:diameter < 2:1, foam/silicone gasket between board and shell (Espressif spec —
  see `MIC_ROOT_CAUSE_ANALYSIS.md` §5.5).
- **Camera aperture** in the frame front; FPC routed through the hinge area.
- Openings: PTT button, slide switch, both USB-C ports, speaker grille (not sealed).
- Antenna placed away from the XIAO shield and battery.
