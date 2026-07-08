# ESP32-S3 AI Glasses — Performance Test Session 3 (Plan)

**Prepared:** July 7, 2026
**Firmware:** `S3_App_V2/` (modular) — first perf session on the V2 rewrite
**App:** `AIglasses/` current main

Session 2 measured the *old* `S3_App_imp` firmware (commit `e293e65`). Everything
below validates the fixes that landed since, and closes the measurement gaps the
Session 2 report called out.

## What changed since Session 2 (under test here)

| Change | Where | Session 2 symptom it addresses |
|---|---|---|
| `MIC_GAIN = 1` (no fw software gain) + app-side trim/VAD/normalize (`ASR_MAX_BOOST` 8×) | `config.h`, `VoiceAssistantPipeline` | Hallucinations: fw peak 13,745 → app peak 32,768 (clipping added after capture) |
| `notifyWithRetry` on every notification + seq-gap silence insertion | `ble_link.cpp`, `BleVoiceService` | ~2.9 s / 92 KB of mic audio lost in BLE |
| **In-band image framing**: header `'H'`, fragments `'I'`, end `'J'` all on IMAGE_TX (no CONTROL markers, no guard delays) | `ble_link.cpp`, `BleVoiceService` | JPEG corruption from cross-characteristic races |
| **2M PHY requested from both sides** | `ble_link.cpp`, `BleVoiceService` | OTA throughput (33.8 KB/s) below playback rate (46.9 KB/s) → thin ring buffer |
| Ring buffer 160 → 256 KB, start threshold 70 KB | `config.h` | Underruns on long TTS |
| Firmware `[PERF-M]` markers restored (M7/M10/M13/M25) | `S3_App_V2.ino`, `playback.cpp` | V2 firmware had no instrumentation |

## Setup checklist (Session 2 lessons)

- [ ] Arduino IDE: `Tools → PSRAM → OPI PSRAM` **before flashing** (Session 2 lost a run to this; camera dead without it)
- [ ] Verify boot log shows `PSRAM: 8192 KB`, not `0 KB`
- [ ] Android Studio logcat filtered to `BleVoiceService|VoicePipeline`, **saving to file from the start** (Session 2 lost all app metrics for voice run 2)
- [ ] Serial monitor @ 115200 logging to file
- [ ] Record firmware + app commit hashes in the results doc
- [ ] Post-run: `grep "PERF-M"` both logs

## Tests

### T1 — Boot / memory baseline (fw M13)
Capture the four `[PERF-M13]` lines (boot, after camera, after ring, after BLE).
**Compare:** Session 2 table (ring was 160 KB then; expect ~96 KB less free PSRAM now).
**Pass:** free heap after BLE ≥ ~180 KB; no allocation failures.

### T2 — Link setup: PHY + MTU
On connect, capture app `[PERF-M35] PHY updated` and fw `[BLE] PHY updated`.
**Pass:** tx=2 rx=2 on a 2M-capable phone (fallback to 1 is acceptable but note the phone model); MTU 512 (app `[PERF-M9]`).
If PHY stays at 1M: check phone BLE 5 support before treating as regression.

### T3 — Mic integrity (the Session 2 headline bug) — 5 voice runs
Per run, record:
- fw `[PERF-M25]` bytes + post-filter peak
- app `[PERF-M11]` bytes + seqGaps, and `[ASR]` pre-normalization peak + gain
- Whisper output vs what was actually said (say a different, noted phrase each run)

**Pass:** fw bytes = app bytes (seqGaps 0); fw M25 peak = app pre-normalize peak
(BLE path bit-transparent); normalized peak ≤ 22,000 (no clipping at 32,767);
≥ 4/5 transcriptions correct. *Session 2 baseline: 2/3 hallucinated.*

**T3b — PDM clock A/B (5 min).** The firmware now runs the mic at 2.048 MHz
(`I2S_PDM_DSR_16S`) instead of the driver-default 1.024 MHz, which fell in an
uncharacterized gap between the mic's datasheet modes (see
`MIC_ROOT_CAUSE_ANALYSIS.md` §5.1). Record two identical utterances at fixed
distance, note fw M25 peak + audible quality; if time allows, temporarily revert
to `I2S_PDM_DSR_8S` and repeat to quantify the difference. Also note the STT model
is now `gpt-4o-mini-transcribe-2025-12-15` — hallucination comparisons against
Session 2 must account for both changes.

### T4 — TTS throughput + ring headroom (fw M10 vs app M10)
One short answer, one deliberately long answer (ask for a ~60 s explanation).
Record fw `[PERF-M10]` RX throughput, app `[PERF-M10]` TX throughput, underrun
count in the `[STREAM] Playback complete` line.
**Pass:** OTA ≥ 48 KB/s (2M PHY should clear this easily; Session 2: 33.8), zero underruns on the long run.

### T5 — Still image integrity (in-band framing) — 10 snapshots
Quick double-tap ×10, varied scenes. Record app `[PERF-M17]/[PERF-M18]/[PERF-M24]` per image.
**Pass:** 10/10 `JPEG decode: SUCCESS`, 10/10 size match, seqGaps 0, no
"Stray image fragment" warnings. *Session 2 baseline: recurring corruption.*

### T6 — Video — 3 recordings ≈ 15 s each
Record frames-sent (fw `[VID]`/`'W'` count) vs frames-received (app "Video end
marker: N frames"), FPS, and any "Video end overtook last frame-end marker" warnings.
**Pass:** received = sent; all frames decode; salvage warning rare (≤1 per session — the fw sends 'W' 50 ms after the last frame precisely to avoid it).

### T7 — Vision end-to-end — 3 runs (closes the Session 2 gap)
Double-tap + hold (bundled photo + question). **This time confirm the gesture** —
Session 2 accidentally ran it as voice-only and never captured fw vision timing.
Record fw `[PERF-M7]`, app `[PERF-M30]/[PERF-M33]/[PERF-M32]/[PERF-M34]`, and
that the answer is about the image (photo attached, not voice-only fallback).
**Pass:** fw M7 captured for all 3; no "Photo too old" / voice-only fallback.

### T8 — End-to-end round trip (fw M7) — from the T3 voice runs
**Baseline:** Session 2 ≈ 10.2–10.6 s (18.1 s on a long answer).
**Expect:** faster BLE (2M PHY) cuts the mic-TX and TTS-OTA + pre-fill legs; record the new split.

## Marker reference

| Marker | Side | Meaning |
|---|---|---|
| M7 | fw | Button release → playback start (round trip) |
| M10 | fw / app | TTS BLE transfer: RX (fw) / TX (app) — same stream, both ends |
| M13 | fw | Memory baseline at boot stages |
| M25 | fw | Mic utterance: bytes sent, post-filter peak |
| M4/M9/M11 | app | TTS TX stats / MTU / mic utterance received |
| M17/M18/M24 | app | Image transfer timing / throughput / reassembly integrity |
| M30–M34 | app | Whisper / GPT / TTS / Vision / pipeline total |
| M35 | app | Negotiated PHY |

## Results

Copy this file to `S3_Session3_Results.md`, fill in per-test tables, and keep the
raw serial + logcat files next to it.
