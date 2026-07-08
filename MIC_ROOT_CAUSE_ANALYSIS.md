# Microphone Problem — Root-Cause Analysis

*July 7, 2026 · SINRG Lab AI Glasses · forensic code analysis (git history) + hardware research.*

**The question:** why did the glasses' microphone pipeline produce Whisper hallucinations
("What is it?", "Please be cautious.") — with the measured signature of a clean firmware
capture (peak 13,745 ≈ −7.5 dBFS) arriving at the app clipped to full scale (32,768)
and 2.9 s shorter?

---

## 1. Executive summary

The "microphone problem" was never one problem. It was **four independent defects
stacked on top of each other**, and the microphone itself was the *least* guilty party:

| # | Layer | Defect | Status |
|---|---|---|---|
| 1 | Firmware BLE TX | Mic fragments sent **fire-and-forget** (no retry) — silently dropped under load | ✅ fixed in V2 (`notifyWithRetry`) |
| 2 | Protocol framing | **507-byte (odd) fragments** + app had **no sequence tracking** → any drop byte-shifts all later int16 samples → ±32768 garbage | ✅ largely fixed in V2 (seq tracking + zero-fill); ⚠️ one residual edge (below) |
| 3 | Gesture / marker logic | `'S'` sent on **every press incl. quick taps**, weak 2-read debounce → app cleared its buffer mid-utterance → bulk audio loss | ✅ fixed in V2 (single 'S', debounced gestures) |
| 4 | Acoustic/electrical | Mic capture is genuinely **quiet** (peaks −15…−7.5 dBFS) with wide spread — low sensitivity headroom at temple distance | ⚠️ open — see §5 (hardware research) |

Whisper then did what Whisper documentedly does with quiet/truncated/garbled audio:
it hallucinated short generic filler phrases. The transcription model was the messenger,
not the cause.

**The decisive reframe:** Session 2's "clipping introduced after capture" was not a gain
stage at all — by Session 2 the firmware already had `MIC_GAIN = 1`. The full-scale peak
was **byte-misaligned PCM read as int16**: random bytes interpreted as samples produce
±32768 excursions. That is why the app saw exactly full scale while the firmware saw a
clean −7.5 dBFS on the same utterance.

---

## 2. The evidence (Session 2, run 3 — same utterance measured at both ends)

| Measurement point | Peak | Bytes / duration |
|---|---|---|
| Firmware, post-DC-filter, pre-BLE (M25) | 13,745 (−7.5 dBFS, clean) | 217,088 B / 6.78 s |
| Android, pre-normalizer (ASR) | **32,768 (full scale)** | 124,768 B / 3.90 s |

Loss: 92,320 bytes = **2.88 s** ≈ 182 fragments' worth. Logcat showed multiple
`Start marker → clearing buffer` events around the utterance. Both hallucinated runs
(1 and 3) share the app-side peak=32,768 signature.

---

## 3. Forensic findings (from git history, firmware `S3_App_imp` @ `e293e65` — the exact build Session 2 tested)

### Finding 1 — mic audio was the only BLE path *without* delivery protection

The old firmware had `notifyWithRetry()` and used it for control markers and **images** —
but the mic sender did not:

```cpp
// S3_App_imp.ino:911 (Session 2 build)
pAudioTxChar->setValue(pkt, BLE_HEADER_SIZE + fragSize);
pAudioTxChar->notify();          // ← return value ignored; false = fragment silently gone
...
delay(2);                        // (V2 uses 5 ms — one fragment per connection event)
```

When NimBLE's host TX pool filled during a burst, `notify()` returned `false` and the
fragment vanished. Worse for diagnostics: `perfAudioTxBytes += fragSize` counted
**unconditionally**, so the firmware's "217,088 bytes sent" included bytes that were
never accepted by the stack — "sent vs received" understated where the loss happened.

### Finding 2 — odd fragment size + zero receiver tracking = the "clipping" (mechanism of the ±32768 signature)

- Payload per fragment = MTU 512 − 3 (ATT) − 2 (header) = **507 bytes — an odd number**.
  A 1024-byte mic chunk ships as fragments of **507 + 507 + 10**.
- The Session-2 app's `handleAudioTx()` had **no sequence-number checking at all** — it
  just appended whatever arrived (verified in git: the function is 17 lines, no seq logic).
- Drop any odd-sized fragment and every subsequent byte lands at the wrong parity:
  the int16 little-endian stream is read one byte out of phase. Low-order bytes become
  high-order bytes → the waveform turns into full-scale noise. **Peak = 32,768 exactly.**

This single mechanism explains why "clipping" appeared *after* capture with no gain
stage anywhere in the path, and why it co-occurred with byte loss.

### Finding 3 — the 2.9 s bulk loss came from the app clearing its own buffer

The old gesture code set `isRecording = true` and sent `'S'` **at press time on every
press** — including the taps of a double/triple-tap pattern — with only a 2-consecutive-read
debounce. Each `'S'` makes the app **wipe every audio chunk received so far**
("`Start marker → clearing buffer`", observed in the run-3 logcat). A tap before the hold,
or a single contact bounce mid-hold, re-fired `'S'` and discarded the front of the
utterance — 2.88 s in run 3. The V2 rewrite emits exactly one `'S'` per utterance and
debounces holds properly (commit `0033e10` "fix gesture hold debounce").

### Finding 4 — the quiet-capture problem is real but was masked by the above

With the corruption removed, what remains is the *acoustic* fact: utterance peaks of
5,615–13,745 (−15.3 … −7.5 dBFS) at conversational distance, with wide spread. That is
low for reliable far-ish-field ASR and is why the app normalizes (now capped at 8×).
This part is the microphone/enclosure question — see §5.

### What actually got fixed, mapped to mechanism

| Mechanism | V2 fix (verified in current code) |
|---|---|
| Fire-and-forget drops | `notifyWithRetry` on every mic fragment + 5 ms pacing (`ble_link.cpp`) |
| Byte-shift on drop | App tracks seq per fragment, inserts zero-fill for gaps (`BleVoiceService.handleAudioTx`) |
| Mid-utterance buffer wipes | One `'S'` per utterance; debounced gestures; seq re-anchors on `'S'` |
| Whisper fed silence/noise | App trims head/tail silence, VAD guard (skip Whisper if avgAbs < 150), boost cap 12→8× |
| Quiet capture | Partially: normalization; hardware-level fixes still open (§5–6) |

---

## 4. Residual defect found during this analysis (not yet fixed)

**The zero-fill gap heuristic can still misalign.** V2's app fills a detected gap with
`ByteArray(payload.size)` — the size of the fragment that *revealed* the gap, not the
size of the fragment that was *lost*. Mic chunks ship as 507+507+10: if the **10-byte
tail fragment** is the one dropped, the app inserts 507 zero bytes (odd, and 497 too
many) and the stream is byte-shifted again — the old failure in miniature. Retries make
drops rare, so this is a low-probability edge, but it is the same class of bug.

**One-line hardening (recommended, not yet applied):** round the payload down to even —
`#define BLE_MAX_PAYLOAD (((BLE_MTU - 3 - BLE_HEADER_SIZE) / 2) * 2)` (507 → 506;
chunks become 506+506+12). With every fragment even-sized, no drop can ever flip byte
parity, whatever the fill size does to timing. (The app's TTS TX path already does
exactly this even-rounding — the lesson was learned in one direction only.)

---

## 5. The microphone itself — hardware research

*Deep-research run, July 7 2026: 19 sources fetched, 94 claims extracted, 25
adversarially verified (3 skeptic votes each) → 23 confirmed, 2 refuted. All
findings below are 3–0 confirmed against primary sources.*

### 5.1 MSM261D3526H1CPM (the part on the XIAO ESP32-S3 Sense) — datasheet facts

| Spec | Value (datasheet V1.2, Seeed-hosted) |
|---|---|
| Sensitivity | **−26 dBFS** typ @ 94 dB SPL / 1 kHz (−27…−25) |
| SNR | **64 dB(A)** typ in Standard mode (@ 2.4 MHz clock); **62 dB(A)** in Low-Power mode (@ 768 kHz) |
| Clock ranges | Sleep 0–50 kHz · **Low-Power 150–900 kHz** · **Standard 1.1–4.0 MHz** |

**The headline finding: our PDM clock sits in an uncharacterized gap.** The ESP-IDF
default config (`I2S_PDM_RX_CLK_DEFAULT_CONFIG` → `I2S_PDM_DSR_8S`) produces
PDM CLK = sample_rate × 64 = **1.024 MHz** at 16 kHz — between the 0.9 MHz top of
Low-Power mode and the 1.1 MHz bottom of Standard mode. The datasheet says nothing
about behavior there. `I2S_PDM_DSR_16S` doubles it to **2.048 MHz — squarely in-spec
Standard mode** — with the PCM rate unchanged. One field. *(Caveat: "uncharacterized"
≠ "proven broken" — only a bench A/B on this exact board confirms an audible gain.)*

### 5.2 The quiet capture is mostly physics, not a fault

At −26 dBFS @ 94 dB SPL, conversational speech (~60–70 dB SPL at the mic) lands tens
of dB below full scale **by design**. Vendor-acknowledged: Seeed's own XIAO Sense
recording example applies a **×4 digital gain** (`VOLUME_GAIN 2`, `<<= 2`) to every
sample. espressif/esp-idf **#8660** documents users of other −26 dBFS PDM mics on the
S3 seeing "VERY small amplitude," resolved with software gain (×8–×40), not driver
fixes. Our measured −15…−7.5 dBFS peaks at 10–15 cm are **consistent with a healthy
mic** plus insufficient gain; run-to-run spread is level/distance variation.
**Refuted (0–3):** the rumored `amplify_num` in-driver gain field — no such lever
exists in `i2s_pdm_rx_slot_config_t`; gain must be applied in application code
(ours lives in the Android normalizer, capped 8×).

### 5.3 SNR margin — the part sits at the floor of Espressif's own guidance

Espressif's Microphone Design Guidelines (ESP-SR) require SNR **≥ 62 dB, > 64 dB
recommended**. In Standard mode this mic *exactly* meets the recommended line; in/near
Low-Power mode it degrades to the bare minimum (and the two SNR specs use different
bandwidths, so real degradation is ≥ 2 dB) — one more reason not to clock it at 1.024 MHz,
and the hard limit on at-distance pickup with this part.

### 5.4 STT hallucinations are a documented model failure mode

- whisper-large-v3 produced hallucinated transcripts on **99.97 %** of 8,732 pure
  non-speech clips (Calm-Whisper, Interspeech 2025); corroborated by Koenecke et al.
  FAccT 2024. Short generic phrases are the classic signature — exactly our
  "What is it?" / "Please be cautious."
- OpenAI names **silence and background noise** as the trigger and reports
  **`gpt-4o-mini-transcribe-2025-12-15`** hallucinates **~90 % less than Whisper v2,
  ~70 % less than earlier gpt-4o-transcribe** (their internal eval — direction
  independently corroborated, magnitudes unaudited).
- Standard mitigations, in line with what V2 already does: VAD/energy gating before
  upload, minimum-duration threshold, silence trimming; plus (Whisper API only)
  `no_speech_prob`/`avg_logprob` filtering.

### 5.5 Enclosure acoustics & the commercial benchmark

- **Espressif's enclosure spec** (checkable against the printed temple): port aperture
  **> 1 mm**; depth : diameter **< 2 : 1**; **silicone/foam gasket** compressed between
  mic port and shell; 25–30 dB leakproofness. An unported or unsealed housing muffles
  pickup regardless of firmware — corroborated by Knowles/Infineon/TDK/Cirrus app notes.
- **Ray-Ban Meta (gen 2) uses a 5-mic array** — one near the **nose bridge** plus two
  temple pairs — and Meta's own ASR pipeline selects the nose mic as the primary
  channel purely because it is closest to the mouth (temple mics are beamformer
  reference channels; arXiv 2509.14430). A single temple mic is structurally
  disadvantaged; commercial designs solve mouth-to-temple distance with placement
  and arrays, not gain. *(Refuted 0–3: the "matched down/out-pointing temple pair
  for noise subtraction" detail from the EDN teardown.)*
- **Part-swap candidates** for the custom PCBs: **TDK T5838** (68 dB(A) SNR, 133 dB SPL
  AOP in High-Quality mode; needs a 2.0–3.7 MHz PDM clock → pair with DSR_16S) — primary
  recommendation. Infineon IM69D130 (64–69 dB(A) by clock) is the fallback, with two
  caveats: Infineon's MEMS mic line is being discontinued, and its −36 dBFS sensitivity
  needs ~10 dB more digital gain. General lesson: **at 16 kHz, drive PDM mics at 128×
  (2.048 MHz), never the ESP-IDF default 64×** — every vendor's high-performance window
  starts above 1.1 MHz.

---

## 6. Recommendations (ranked, cheapest first)

| # | Intervention | Cost | Status |
|---|---|---|---|
| 1 | **PDM clock → `I2S_PDM_DSR_16S`** (2.048 MHz, in-spec Standard mode) | 1 line | ✅ **applied** (`audio_io.cpp`) — A/B on bench during Session 3 |
| 2 | **STT model → `gpt-4o-mini-transcribe-2025-12-15`** (~70–90 % fewer hallucinations) | 1 line | ✅ **applied** (`OpenAIService.kt`) |
| 3 | Keep the existing app-side gain/trim/VAD chain (matches Seeed's ×4 precedent and #8660 resolutions); consider ESP-SR AFE's AGC on-device later | free | already in V2 |
| 4 | **Check the printed temple against the Espressif port spec**: > 1 mm aperture over the mic hole, depth:diameter < 2:1, foam/silicone gasket, port aimed at mouth | ~free | → Friday build (P4 in FRIDAY_BUILD_PLAN.md) |
| 5 | Even-payload BLE fragments (§4) to close the residual byte-shift edge | 1 line | recommended, not applied |
| 6 | **Custom-PCB part swap: TDK T5838** (68 dB SNR, +4–6 dB over MSM261) driven at ≥ 2 MHz | ~$2/board respin | next PCB revision (left PCB is already active work) |
| 7 | **Second mic near the nose bridge** (or a matched pair) for selection/beamforming via ESP-SR AFE — the commercial pattern | PCB + firmware | research track (roadmap Tier 4) |

**Bottom line:** the hallucinations were a transport bug (fixed), amplified by a model
known to hallucinate on bad audio (now swapped for the 2025-12-15 snapshot). The mic
itself is a mid-grade part being clocked in an unspecified mode (now fixed, pending
bench A/B) whose quiet output is expected physics — and whose at-distance ceiling is
real: it sits exactly at Espressif's minimum-recommended SNR. Getting Meta-class
far-field pickup eventually requires placement + a better part + more mics, in that order.

### Key sources

MSM261D3526H1CPM datasheet V1.2 (Seeed-hosted) · ESP-IDF `i2s_pdm.h` + I2S docs ·
espressif/esp-idf #8660 · Seeed XIAO Sense mic wiki · Espressif Microphone Design
Guidelines (ESP-SR) · OpenAI audio-models update (Dec 2025) · Calm-Whisper
(arXiv 2505.12969) · Koenecke et al. (arXiv 2501.11378) · Meta smart-glasses ASR
paper (arXiv 2509.14430) · TDK T5838 · Infineon IM69D130 datasheet.
