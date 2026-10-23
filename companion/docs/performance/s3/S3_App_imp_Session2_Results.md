# ESP32-S3 AI Glasses — Performance Test Session 2

**Branch:** `performance-tests`
**Date:** June 9, 2026
**Tester:** Ibrahim Ayoub
**Firmware:** `S3_App_imp/S3_App_imp.ino` — commit `e293e65` + video/gesture patches
**App build:** `performance-tests` branch (commit `e293e65` + uncommitted video support)

> Methodology: two terminals in parallel.
> - ESP32 serial: Arduino Serial Monitor @ 115200 baud (XIAO ESP32-S3 Sense on `/dev/cu.usbmodem1101`)
> - Android logcat: Android Studio logcat filtered to `BleVoiceService`, `VoicePipeline`
> Metrics filtered with `grep "PERF-M"`.
>
> **Session note:** PSRAM was not detected on first boot (`[SYS] PSRAM: 0 KB`).
> Root cause: Arduino IDE board settings had PSRAM disabled. Fixed by setting
> `Tools → PSRAM → OPI PSRAM` and reflashing. Camera would not function without this.
> All data below was collected after the fix.

---

## 1. Boot / Memory Baseline (M13 — firmware)

| Stage | Free heap (KB) | Free PSRAM (KB) |
|---|---|---|
| At boot | 297 | 8,189 |
| After camera init | 275 | 8,158 |
| After ring buffer alloc | 275 | 7,994 |
| After BLE init | 209 | 7,994 |
| Min-ever heap (during run) | 207 | 7,994 |

> Camera consumed **31 KB PSRAM** (OV3660 frame buffers × 2, QVGA JPEG).
> Ring buffer consumed **164 KB PSRAM** (160 KB alloc + ~4 KB overhead).
> BLE stack consumed **66 KB heap** (NimBLE GATT server + advertising).
> Min-ever heap of 207 KB observed from `[PERF-M4]` log during Voice Run 2 (TTS incoming).

---

## 2. End-to-End Round-Trip (M7)

| Run | Mode | Button→playback (fw M7, ms) | Pipeline total (app M7, ms) |
|---|---|---|---|
| 1 | voice | 10,570 | 5,885 |
| 2 | voice | 10,232 | — |
| 3 | voice | 18,088 | 9,997 |
| 4 | vision | — | 6,524 |
| 5 | vision | — | — |

> **Note:** Firmware M7 captured for all 3 voice runs. Vision firmware M7 still not captured —
> the intended vision+voice run was executed as voice-only (single press instead of
> double-tap+hold). Vision app-side M7 (6,524 ms) remains the only vision timing.
> Firmware M7 for vision deferred to Session 3.
>
> **Voice run 3 M7 (18,088 ms) is notably higher than runs 1–2 (~10,400 ms).** Root cause:
> TTS response was 190,800 bytes — 31% larger than run 1 (146,400 bytes). The larger payload
> takes longer to receive OTA and extends the ring buffer pre-fill wait before playback starts.
> App M7 = 9,997 ms (matched by TTS size 190,800 bytes / 378 chunks in both logs).
>
> **Voice run 3 breakdown:** Whisper=1,628 ms | GPT=1,838 ms | TTS=6,495 ms → 9,997 ms pipeline
> Note: firmware mic TX was 217,088 bytes (6.78s) but Android reported 124,768 bytes (3.90s)
> for the same utterance — **~2.9s of audio lost in transfer.** This run's transcription was a
> hallucination; the byte loss is a likely contributor. See Section 8a for full analysis.
>
> **Voice run 1 breakdown:** recording+TX=3,479 ms | Whisper=1,701 ms | GPT=1,945 ms |
> TTS=2,100 ms | BLE TX=1,605 ms | buffer pre-fill=1,140 ms → total 10,570 ms
>
> **Vision run 1 breakdown:** Whisper=891 ms | Vision API=1,526 ms | TTS=4,059 ms → 6,524 ms pipeline

---

## 3. OpenAI Pipeline (app M1 / M2 / M3 / M27)

> **Marker note:** Android-side markers use M1=Whisper, M2=GPT, M3=TTS, M27=Vision API.
> These overlap with firmware marker numbering — renumbering recommended for Session 3.

| Run | Mode | Whisper M1 (ms) | GPT M2 (ms) | Vision M27 (ms) | TTS M3 (ms) | TTS bytes |
|---|---|---|---|---|---|---|
| 1 | voice | 1,701 | 1,945 | — | 2,100 | 146,400 |
| 2 | voice | — | — | — | — | 137,400 |
| 3 | voice | 1,628 | 1,838 | — | 6,495 | 190,800 |
| 4 | vision | 891 | — | 1,526 | 4,059 | 668,400 |

> **Run 2 (voice) gaps:** Android logcat was not captured for this run — only firmware serial
> was recorded. Whisper, GPT, and TTS breakdown timings are unavailable. TTS byte count is
> known from firmware M10 RX log (137,400 bytes / 272 chunks). App-side M7 pipeline total
> also not captured. To close this gap in Session 3: save Android logcat before starting runs.

**Transcription accuracy:**
- Run 1 (voice): Whisper output `"What is it?"` → ✗ **HALLUCINATION** (user did not say this; app ASR peak=32,768 clipped)
- Run 2 (voice): Whisper output not captured (no Android logcat)
- Run 3 (voice): Whisper output `"Please be cautious."` → ✗ **HALLUCINATION** (user did not say this; firmware raw peak=13,745 but app ASR peak=32,768 — clipping introduced after capture; ~2.9s of audio also lost in transfer)
- Run 4 (vision): Whisper output `"Hello, what do you see in the image?"` → ✓ **CORRECT** (confirmed by tester)
- **Net: 2 of 3 captured transcriptions were hallucinations (both voice-only); the one correct result was the vision run. See Section 8a for the full root-cause analysis.**

> **Observation:** Voice pipeline total (5,885 ms) is significantly faster than the previous S3
> session (14,663 ms). The app no longer uses MP3 encoding/decoding — TTS now returns
> raw PCM at 24 kHz directly, eliminating the MP3 decode step (~694 ms) and reducing
> TTS response size. Whisper is also substantially faster (1,701 ms vs 8,582 ms prior).
> Vision GPT M2 timing is not applicable — vision uses the Vision API directly (M27), not
> a separate GPT call after transcription.

---

## 4. BLE Audio Transmission

### Mic → Android (firmware M1, app M11)

| Run | Utterance bytes | Duration (s) | Chunks | Throughput (KB/s) |
|---|---|---|---|---|
| 1 (voice) | 85,489 | 2.67 | ~220 | 24.0 |
| 2 (voice) | 164,864 | 5.15 | 483 | 31.6 |
| 3 (voice) | 217,088 (fw) / 124,768 (app) | 6.78 / 3.90 | 636 (fw) / 350 (app) | 31.5 |
| 4 (vision) | 124,918 | 3.90 | ~357 | 31.2 |

### Android → ESP32 TTS (app M4/M10, firmware M10)

| Run | Bytes | Chunks | Dropped | App throughput (KB/s) | FW throughput (KB/s) |
|---|---|---|---|---|---|
| 1 (voice) | 146,400 | 290 | 0 | 89.1 | 33.8 |
| 2 (voice) | 137,400 | 272 | 0 | — | 30.9 |
| 3 (voice) | 190,800 | 378 | 0 | 77.0 | 35.8 |
| 4 (vision) | 668,400 | 1,321 | 0 | 79.9 | 38.1 |

**Real-time requirement @ 24kHz/16-bit mono = ~46.9 KB/s.**
- App-side throughput: 89.1 KB/s voice / 79.9 KB/s vision → **190% / 170% of requirement** ✓
- FW-side (over-the-air): 33.8 KB/s voice / 38.1 KB/s vision → **72% / 81% of requirement** ⚠

> **Voice run 2 app-side throughput not captured** — Android logcat was not saved for that run.
> Only firmware-side OTA rate (30.9 KB/s) is known. App-side likely similar to run 1 (~89 KB/s).
>
> **Important discrepancy:** App-side throughput measures time to hand off to BLE stack
> (~1,600 ms for voice). Firmware-side measures actual over-the-air receive rate
> (~4,200 ms for voice / ~17,100 ms for vision). The firmware's OTA rate is below
> the 46.9 KB/s real-time playback requirement, which is what caused the critically
> thin ring buffer and 1 underrun in the vision run (see Section 5).
>
> **Why OTA rate is lower than app-side rate:** The Android BLE stack queues all notifications
> immediately (fast) but the radio can only transmit one packet per connection interval
> (15 ms at the current 12-interval setting). At MTU 512 with 507-byte payloads, theoretical
> max is 507 B / 15 ms = 33.8 KB/s — which matches the observed voice OTA rate exactly.
> The vision run's slightly higher 38.1 KB/s suggests the connection interval was briefly
> tightened or the scheduler was more favorable during that run.

---

## 5. Ring Buffer & Playback (M5 / M6 / M8 / M19)

| Run | Ring fill at start M5 (%) | 'S'→playback M6 (ms) | Spk init M8 (ms) | Underruns M19 | Dropped bytes | Seq gaps |
|---|---|---|---|---|---|---|
| 1 (voice, 3.05s TTS) | 29.3 | 1,140 | 1 | 0 | 0 | 0 |
| 2 (voice, 2.93s TTS) | 29.3 | 1,413 | 1 | 0 | 0 | 0 |
| 3 (voice, 3.97s TTS) | 29.3 | 1,176 | 1 | 0 | 0 | 0 |
| 4 (vision, 13.93s TTS) | — | — | — | 1 | 0 | 0 |

> **Vision run M5/M6/M8 not captured** — the `'S'` start-of-playback firmware log line was
> not recorded for the vision run (serial monitor was not saved). Underrun count (1) is
> known from a separate log excerpt. M5/M6/M8 to be captured in Session 3.

> **Voice run 1:** Ring started at 29.3% (48,070/163,840 bytes). M6=1,140 ms. Zero underruns. Clean playback.
> Speaker I2S init was only 1 ms (significantly improved vs prior session's 32 ms).
>
> **Voice run 2:** Ring started at 29.3% again. M6=1,413 ms (273 ms longer pre-fill than run 1).
> Zero underruns. Clean playback. The extra fill time reflects the larger TTS response (137 KB vs 146 KB).
>
> **Vision run 3:** Ring buffer ran critically thin throughout the entire 13.93s playback,
> consistently 500–1,500 bytes remaining (out of 163,840). One end-of-stream underrun
> fired at `ring=752` bytes — benign (no audible corruption), but margin was very thin.
> Root cause: OTA receive rate (~38 KB/s) is below playback consumption rate (~48 KB/s),
> so the pre-fill slowly erodes across long TTS responses.
>
> **Recommendation:** Increase `STREAM_START_THRESHOLD` or ring buffer size to handle
> longer TTS responses without risk of mid-stream underruns.

---

## 6. MTU (M9 — app)

**Negotiated MTU:** 512 bytes  |  **Effective payload/pkt:** 507 bytes

> 512-byte MTU minus 3-byte ATT header = 509 bytes. Android BLE stack reserves 2 additional
> bytes for L2CAP framing, leaving 507 bytes of usable payload per notification.
> At the 15 ms connection interval (interval=12 × 1.25 ms), theoretical maximum OTA
> throughput = 507 B × (1000 ms / 15 ms) = 33.8 KB/s — consistent with observed rates.
> MTU negotiated successfully on first connect; no renegotiation observed across all runs.

---

## 7. Camera Performance (M21 firmware, M17/M18/M24 app)

> **Session note:** Camera failed on first flash (`[PERF-M21] Camera capture FAILED after 5 attempts`).
> Root cause was PSRAM = 0 KB (camera driver allocates frame buffers in PSRAM;
> allocation fails silently, `esp_camera_fb_get()` returns NULL on every attempt).
> Fixed by enabling OPI PSRAM in Arduino IDE board settings and reflashing.

| Run | Capture attempts (M21) | Capture time (ms) | JPEG size (bytes) | FW TX time M23 (ms) | FW TX KB/s | App RX M18 (KB/s) | Reassembly M24 | JPEG decode M24 |
|---|---|---|---|---|---|---|---|---|
| 1 (standalone) | 1 | 143 | 4,350 | 205 | 20.7 | 20.4 | OK (seqGaps=0) | SUCCESS (320×240) |
| 2 (vision bundled) | 1 | — | 4,107 | 188 | 21.3 | 21.3 | OK (seqGaps=0) | SUCCESS (320×240) |
| 3 (standalone) | 1 | — | 4,795 | 220 | 21.3 | 14.8 | OK (seqGaps=0) | SUCCESS (320×240) |
| 4 (standalone) | 1 | — | 7,205 | 355 | 19.8 | 19.8 | OK (seqGaps=0) | SUCCESS (320×240) |
| 5 (standalone) | 1 | — | 4,668 | 237 | 19.2 | 19.2 | OK (seqGaps=0) | SUCCESS (320×240) |

**Capture failure rate (after PSRAM fix):** 0 / 5 attempts failed  
**Reassembly mismatch rate:** 0 / 5  
**JPEG decode failure rate:** 0 / 5

**Standalone photo end-to-end (M28):** Run 1 = 376 ms | Run 3 = 359 ms (avg ~368 ms, button press → TX complete)

Observations: Both captures succeeded on first attempt after PSRAM fix. Sensor warm-up
(4 frames discarded before capture) appears effective — no blank/dark frames observed.
JPEG sizes (4,107–4,350 bytes) are on the small side for QVGA, suggesting a low-detail
or evenly-lit scene; expected range is 6–12 KB for varied content.

---

## 7b. Video Performance (M26 / M27 firmware)

> New section — video capture was added and tested this session.
> Gesture: quick single tap to start, quick single tap to stop.

| Run | Frames | Session duration (ms) | Total bytes | FPS | Throughput (KB/s) | JPEG errors |
|---|---|---|---|---|---|---|
| 1 (30 ms guard) | 29 | 6,219 | 132,647 | 4.7 | 20.8 | ~7–8 frames corrupt (27/29 encoded) |
| 2 (60 ms guard) | 32 | 8,938 | 184,346 | 3.6 | 20.1 | ~8–9 frames corrupt (32/32 encoded) |
| 3 (Android only) | 7 | — | — | — | — | 2 JPEG errors (7/7 encoded, 25 KB MP4) |
| 4 (Android only) | 6 | ~1,058 | ~17,100 | 5.7 | ~15.9 | 1 JPEG error (6/6 encoded, 22 KB MP4) |
| 5 (Android only) | 31 | ~6,039 | ~88,400 | 5.1 | ~14.2 | 2 JPEG errors (31/31 encoded, 141 KB MP4) |

**Per-frame stats Run 1 (M26):** Capture time consistently 1 ms | TX time 135–180 ms per frame  
**Per-frame stats Run 2 (M26):** Capture time consistently 1 ms | TX time 225–255 ms per frame (60 ms guard visible in TX overhead)  
**Per-frame stats Runs 3–5 (Android only, no firmware log):** Frame sizes ~2,800–3,200 bytes (significantly smaller than runs 1–2 at 5,200–6,200 bytes — different scene/lighting). Higher FPS (5.1–5.7) due to smaller frames transmitting faster. Runs 3–4 were short (6–7 frames) — likely accidental triggers during gesture testing. Corruption persists across all runs (60 ms guard), confirming the fix is insufficient.

**JPEG corruption root cause (confirmed):** The `'J'` end marker is sent on the *control*
characteristic (`0x aa03`) while frame data is sent on the *image TX* characteristic
(`0x aa04`). BLE gives no delivery-order guarantee across different characteristics —
the `'J'` notification can be delivered to Android before the last few data notifications
from `aa04` regardless of the order they were submitted by the firmware. When this happens,
Android closes the frame buffer early and the trailing data fragments contaminate the
next frame's buffer, or the JPEG is written incomplete (truncated before `0xFFD9`).

**Attempted fix — Run 1 → Run 2:** Guard delay before `'J'` increased 30 ms → 60 ms.
Result: frame count improved (27/29 → 32/32 received and submitted to encoder), but JPEG
decode errors persisted (`error 117` ×7, `error 105` ×2). The extra delay reduced dropped
frames but could not eliminate the cross-characteristic race — the problem is structural,
not timing-dependent. No amount of delay on a single characteristic can guarantee ordering
relative to notifications on a different characteristic.

**Recommended fix (deferred to next session):** Move the `'J'` end marker from the control
characteristic onto the *image TX* characteristic (`aa04`) so it shares the same
notification queue as the frame data. BLE delivers notifications on a single characteristic
in strict submission order, so `'J'` physically cannot arrive before the last data packet.
This eliminates the race entirely with no guard delay needed.

**Required changes:**
- **Firmware `sendVideoFrame()`:** Send `'J'` via `imageCharacteristic->notify()` instead
  of `controlCharacteristic->notify()`. Remove the guard delay.
- **Android `BleVoiceService`:** In the image TX characteristic callback, detect the single
  byte `0x4A` (`'J'`) as the end-of-frame signal instead of watching the control
  characteristic for it.

**Expected outcome:** Zero JPEG decode errors, no FPS penalty. FPS should return to ~4.7
(possibly higher without the 60 ms delay).

Run 1 (30 ms guard): Android encoded **210 KB** MP4 (27/29 frames), H.264 c2.exynos.h264.encoder, 500 kbps, 3 FPS target, 320×240.  
Run 2 (60 ms guard): Android encoded **397 KB** MP4 (32/32 frames), H.264 c2.exynos.h264.encoder, 500 kbps, 3 FPS target, 320×240.

---

## 8. Microphone Quality (M25 firmware, M1 app)

| Run | Mic min | Mic max (raw) | App ASR peak | avgAbs (M25) | Samples | Whisper output (M1) | Hallucination? |
|---|---|---|---|---|---|---|---|
| 1 (voice) | — | — | 32,768 (clipped) | — | — | "What is it?" | ✗ **HALLUCINATION** |
| 2 (voice) | -5,615 | 4,827 | — | 219 | 82,432 | — | (no app log) |
| 3 (voice) | -13,745 | 13,467 | 32,768 (clipped) | 540 | 108,544 | "Please be cautious." | ✗ **HALLUCINATION** |
| 4 (vision) | — | — | — | — | — | "Hello, what do you see in the image?" | ✓ CORRECT (confirmed) |

> **⚠ CORRECTION (per tester):** Runs 1 and 3 were **HALLUCINATIONS** — the user did NOT
> say "What is it?" or "Please be cautious." Whisper generated these from corrupted/garbled
> audio. Both are classic Whisper hallucination outputs (short, generic, grammatically
> complete phrases it emits when given non-speech, silence, or corrupted input). This means
> we **did capture real failures this session**, and they share a clear measured signature.
>
> **THE KEY FINDING — clipping is introduced AFTER capture, not at the microphone:**
> - **Run 3:** firmware measured the raw mic at `min=-13,745 max=13,467` (M25) — **well below
>   full scale, NOT clipping at capture.** But the Android ASR log for the *same utterance*
>   reported `peak=32768 already loud enough — no boost` — **full-scale hard clipping.**
>   The signal went from 13,745 (raw, at the mic) → 32,768 (full scale, at Whisper). The
>   clipping was introduced **somewhere between the M25 measurement and the ASR normalizer.**
> - **Run 3 also lost ~2.9 s of audio:** firmware sent 217,088 bytes (6.78 s) but Android
>   logged only 124,768 bytes (3.90 s) as the utterance. ~92,000 bytes unaccounted for.
> - Both hallucinated runs (1 & 3) show **app-side peak = 32,768.** This full-scale signature
>   is the common factor in both captured failures.
>
> **Interpretation:** The hallucinations are not caused by the user speaking too loudly
> (run 3's raw audio peaked at only 13,745 / ~42% of scale). They are caused by **corruption
> or a clipping/gain stage between mic capture and Whisper** that drives samples to full
> scale, combined with **audio loss during BLE transfer.** Garbled PCM (random bytes
> interpreted as int16) naturally produces full-scale (±32,768) values, which is exactly the
> peak=32,768 the ASR normalizer reported. Whisper, fed this corrupted/truncated audio,
> emitted generic filler phrases.
>
> **Run 2 (voice):** M25 raw peak = 5,615, avgAbs=219 (quiet). No Android log captured, so
> transcription result unknown.
>
> **Run 4 (vision):** "Hello, what do you see in the image?" — **confirmed correct by tester**
> (this is what was actually said). This is the one clean transcription this session.
>
> **Notable contrast:** the only CORRECT transcription (run 4) was the **vision** run; both
> **voice-only** runs (1 & 3) hallucinated. This may be coincidence (small sample), or it may
> hint that the corruption/clipping path is worse on the voice-only flow. Worth watching in
> Session 3 — capture firmware raw peak AND app ASR peak for both flows to see if vision
> consistently avoids the 13,745→32,768 jump that the voice runs showed.

---

### 8a. Microphone Hallucination / Mis-Transcription Analysis

> **TWO HALLUCINATIONS CAPTURED THIS SESSION (confirmed by tester).** Runs 1 ("What is it?")
> and 3 ("Please be cautious.") were both hallucinations — the user did not speak those
> phrases. These are real, captured failures with a shared measured signature, not inferred
> conditions. Both are classic Whisper hallucination outputs: short, generic, grammatically
> complete phrases the model emits when fed silence, noise, or corrupted/truncated audio.

**PRIMARY ROOT CAUSE — full-scale clipping/corruption introduced AFTER mic capture:**

The decisive evidence is run 3, where we have both firmware and app measurements of the
*same* utterance:

| Measurement point | Peak amplitude | Bytes / duration |
|---|---|---|
| Firmware raw mic (M25) | 13,745 (~42% of scale, **clean**) | 217,088 B / 6.78 s |
| Android ASR normalizer | **32,768 (full scale, CLIPPED)** | 124,768 B / 3.90 s |

Between the microphone and Whisper, the signal's peak **doubled to full scale** and ~2.9 s
of audio (~92,000 bytes) **disappeared.** The user did not speak loudly enough to clip
(raw peak was only 13,745) — so the clipping is **not acoustic.** It is introduced by a
processing/gain stage in the firmware *after* the M25 measurement, or by **corruption/loss
during BLE transfer**, or both. Random/garbled PCM bytes interpreted as int16 naturally
produce ±32,768 full-scale values — which is exactly the peak the ASR normalizer reported.
Whisper, handed this corrupted and truncated audio, output a generic filler phrase.

Run 1 shows the same app-side signature (peak = 32,768) and also hallucinated. We lack
firmware M25 for run 1, so we cannot prove the raw capture was clean there — but the
identical full-scale signature across both captured failures points to the same mechanism.

**Contributing factors (also measured, secondary to the above):**

1. **Audio loss in BLE transfer (DIRECTLY OBSERVED, run 3).** 217 KB sent → 125 KB received.
   Truncated/dropped audio both removes words and (if garbled) injects full-scale noise.
   Note the multiple `Start marker → clearing buffer` events in the logcat right before the
   utterance — a mistimed buffer clear could discard the front of the speech.

2. **Leading/trailing silence in every recording (DIRECTLY OBSERVED).** Run 3's first
   512-sample chunk was avgAmp=65 (near-silent) before speech. The firmware starts recording
   the instant the button is pressed, always capturing a silence window. Whisper hallucinates
   on silence; the normalizer then amplifies that silence into audible noise.

3. **Low SNR + aggressive normalizer boost (DIRECTLY OBSERVED).** Run 2 had avgAbs=219 vs an
   ASR target peak of 22,000 → up to ~4–12× gain, which amplifies the noise floor equally
   with speech.

4. **Inconsistent pickup.** Raw peak ranged 5,615–13,745 across runs with M25 data (and the
   app saw 32,768 on the corrupted runs) — a very wide spread.

**Proposed fixes (priority order — revised now that the root cause is identified):**

| # | Fix | Where | Evidence | Effort |
|---|---|---|---|---|
| 1 | **Find where peak goes 13,745 → 32,768.** Audit every stage between the M25 measurement and BLE send (DC blocker, any gain/normalization, int16 packing) AND verify BLE bytes aren't being corrupted. This is the PRIMARY fix — the clipping is the common signature of both captured hallucinations. | Firmware DSP + BLE path | **Direct, decisive** (run 3: 13,745 raw → 32,768 at ASR) | Medium |
| 2 | **Fix the BLE audio loss** — 217 KB → 125 KB. Investigate the `Start marker → clearing buffer` events firing mid/pre-utterance; ensure the buffer isn't cleared after speech starts. Truncated audio directly causes hallucination. | Android buffer / start-marker handling | **Direct** (217 KB fw vs 125 KB app, run 3) | Medium |
| 3 | **Trim leading/trailing silence** before Whisper — drop \|x\|<500 samples from both ends. | Android, before `normalizeForAsr()` | Direct (run 3 first chunk avgAmp=65) | Low |
| 4 | **VAD / "too quiet" guard** — if whole-utterance avgAbs < ~150–200, skip Whisper entirely (return nothing rather than hallucinate). | Android | Direct (run 2 avgAbs=219) | Low |
| 5 | **Cap normalizer max boost** 12× → ~4–6× so near-silent audio doesn't amplify noise. | Android `normalizeForAsr()` (`ASR_MAX_BOOST`) | Direct (wide amplitude spread) | Low |
| 6 | **Raise DC-blocker corner** to ~80–100 Hz to remove low-freq handling noise. | Firmware high-pass | Inferred | Low |

**Recommended Session 3 starting point:** fixes #1 and #2 — they target the actual captured
failure signature (full-scale clipping + audio loss between mic and Whisper). Fixes #3–#6
are good hygiene but secondary; #1 and #2 are where the two real hallucinations came from.
**Validation:** re-run voice tests capturing BOTH firmware M25 (raw peak) and Android ASR
peak for every run, and confirm they now match (no 13,745→32,768 jump) and bytes are
conserved (firmware bytes ≈ Android bytes).

---

## 9. Key Findings & Insights

1. **PSRAM must be explicitly enabled in Arduino IDE (OPI PSRAM).** Default board settings leave PSRAM at 0 KB, which silently breaks the camera — `esp_camera_fb_get()` returns NULL on every attempt. Added as a mandatory flash prerequisite for all future sessions.

2. **OpenAI pipeline is ~2.5× faster than previous S3 session.** App now sends PCM directly (24 kHz) instead of MP3, eliminating the ~700 ms MediaCodec decode step. Whisper latency also improved significantly (1,701 ms vs 8,582 ms). Total voice pipeline: 5,885 ms vs prior 14,663 ms.

3. **Ring buffer runs critically thin for long TTS responses.** OTA BLE receive rate (~38 KB/s) is below the 48 KB/s playback consumption rate. For short responses (~3 s) this is fine; for the 13.93 s vision response the buffer was 500–1,500 bytes for the full duration. One end-of-stream underrun occurred. Pre-fill threshold or buffer size should be increased.

4. **Camera works reliably once PSRAM is active** — 2/2 captures on first attempt, clean reassembly (seqGaps=0), successful JPEG decode both times. The sensor warm-up and retry fixes from prior sessions are effective.

5. **Video JPEG corruption persists even at 60 ms guard delay — root cause is structural.**
   Both 30 ms and 60 ms guard delays produced ~25% frame corruption. The delay approach cannot
   work because `'J'` is sent on a different BLE characteristic than the frame data — BLE
   gives no cross-characteristic ordering guarantee. Fix identified: move `'J'` to the image
   TX characteristic. See Section 7b and Recommendation 1 for full details and required code changes.

6. **Microphone hallucinations — TWO captured this session, with a decisive root-cause signature.**
   Runs 1 ("What is it?") and 3 ("Please be cautious.") were both hallucinations (confirmed
   by tester — the user did not speak those phrases). Both are classic Whisper filler outputs
   from corrupted/truncated audio. **The decisive finding:** in run 3 the firmware measured
   the raw mic at peak=13,745 (clean, ~42% scale) but the Android ASR normalizer saw
   peak=32,768 (full-scale clipping) on the *same utterance* — so the clipping is introduced
   **after capture**, in the firmware DSP or the BLE path, NOT by loud speech. Run 3 also lost
   ~2.9 s of audio (217 KB firmware → 125 KB Android). Both hallucinated runs share the
   app-side peak=32,768 signature. This reframes the fix: the priority is finding where the
   signal goes 13,745 → 32,768 and stopping the BLE audio loss — not just acoustic gain
   tuning. See Section 8a for the full analysis and revised 6-item fix plan.

---

## 10. Recommended Optimizations

1. **Fix video JPEG corruption — move `'J'` end marker to image TX characteristic.**
   Root cause confirmed: cross-characteristic BLE delivery order is not guaranteed. The
   `'J'` sent on the control characteristic races the last data notifications on the image
   TX characteristic. Fix: send `'J'` on the image TX characteristic so it shares the same
   submission queue as the data. Matching change required in Android `BleVoiceService` to
   detect `0x4A` in the image TX callback. Remove the guard delay once fixed. Expected
   outcome: zero corruption, FPS returns to ~4.7+. See Section 7b for full details.

2. **Increase `STREAM_START_THRESHOLD`** (or ring buffer size) to provide more headroom
   for long TTS responses. Current 29.3% pre-fill (~48 KB) with a 13.93 s response
   depleted the buffer to near-zero throughout playback because OTA receive rate
   (~38 KB/s) is below the playback consumption rate (~48 KB/s). A threshold of ~60–70 KB
   (~40% of the 163,840-byte ring) would give ~1.4 s of runway before the buffer runs dry.
   Alternatively, increase the ring buffer allocation from 160 KB to 256 KB in PSRAM
   (8 MB available, currently using only 0.2%).

3. **Renumber Android `[PERF-M]` markers to avoid collision with firmware markers.**
   Android currently uses M1=Whisper, M2=GPT, M3=TTS, M27=Vision API — all of which
   overlap with firmware marker numbers. Suggested new range: Android markers shift to
   M30+ (M30=Whisper, M31=GPT, M32=TTS, M33=Vision API, M34=pipeline total).

4. **Capture firmware M7 for the vision run.** Only app-side pipeline M7 (6,524 ms) was
   recorded this session. The firmware M7 (button press → speaker start) was not logged.
   Ensure serial monitor is open and scrolled to bottom before triggering a vision run.

5. **Increase video FPS.** Current 3.6 FPS (with 60 ms guard) / 4.7 FPS (with 30 ms guard)
   is limited primarily by BLE notification throughput (~20 KB/s at ~5–6 KB per frame).
   Once the `'J'` characteristic fix is applied and the guard delay removed, FPS will
   improve. Further gains possible by reducing JPEG quality (`s->set_quality(camera, N)`
   below the current value) to shrink frame size, or by requesting a shorter BLE connection
   interval (currently 12 × 1.25 ms = 15 ms).

6. **Fix microphone hallucinations (TWO captured this session — see Section 8a).**
   Root cause identified from run 3: clipping is introduced *after* mic capture (firmware raw
   peak=13,745 → Android ASR peak=32,768) and ~2.9 s of audio is lost in BLE transfer
   (217 KB → 125 KB). Both hallucinated runs share the app-side peak=32,768 signature. Fix
   plan in priority order:
   1. **Find where peak goes 13,745 → 32,768 (PRIMARY).** Audit every firmware stage between
      the M25 measurement and BLE send (DC blocker, gain, int16 packing) and verify BLE bytes
      aren't corrupted. This is the captured-failure signature. *Medium effort.*
   2. **Fix the BLE audio loss** (217 KB → 125 KB) — investigate the `Start marker → clearing
      buffer` events firing pre/mid-utterance; ensure the buffer isn't cleared after speech starts. *Medium effort.*
   3. **Trim leading/trailing silence** on Android before `normalizeForAsr()` (drop
      samples below |x|<500 from both ends). *Low effort.*
   4. **Add a "too quiet" guard** — skip the Whisper call if whole-utterance avgAbs < ~150–200. *Low effort.*
   5. **Cap `ASR_MAX_BOOST`** from 12× → ~4–6× so near-silent audio doesn't amplify noise. *Low effort.*
   6. **Raise the firmware DC-blocker corner** to ~80–100 Hz to remove low-freq handling noise. *Low effort.*

   **Recommended starting point:** #1 + #2 — they target the actual captured failure signature
   (full-scale clipping + audio loss between mic and Whisper). #3–#6 are good hygiene but
   secondary. **Validation:** log BOTH firmware M25 raw peak AND Android ASR peak on every run;
   confirm they match (no 13,745→32,768 jump) and that firmware bytes ≈ Android bytes (no loss).
