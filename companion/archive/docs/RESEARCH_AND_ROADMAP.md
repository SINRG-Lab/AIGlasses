# AI Smart Glasses — Research & Roadmap

*Prepared July 2026 · SINRG Lab, Northeastern · orientation + market/tech research + prioritized backlog for the ESP32-S3 AI glasses project.*

This document ties together (a) where the project stands, (b) how it fits the mid-2026 smart-glasses market, (c) technical state-of-the-art findings that change the plan, and (d) a prioritized "what needs to be done" roadmap. It builds on the existing `AI_Smart_Glasses_Engineering_Reference.docx` and the `Preformance Tests/` results.

---

## 1. Project snapshot

A voice **+** vision AI assistant in an eyeglasses form factor — the Ray-Ban Meta job-to-be-done, built on **custom ESP32-S3 hardware + an Android app**, with **no backend server** (the phone calls OpenAI APIs directly).

**Data flow:** glasses capture audio (PDM mic) and images/video (OV3660) → **BLE** → phone runs `transcribe (gpt-4o-transcribe) → chat (gpt-4o-mini) → TTS (tts-1)`, plus `gpt-4o` vision → BLE back → speaker (2× MAX98357A).

**Input:** PTT button tap-language (hold = voice, double-tap = photo, double-tap+hold = vision, triple-tap = video, press-during-playback = barge-in). Capacitive touchpad being ported to native S3 GPIOs for the next PCB.

**Maturity by subsystem:**

| Subsystem | Status |
|---|---|
| Firmware (`S3_App_V2`, 7 modules) | Active, well-structured |
| Android app (Compose, 5 screens) | Working pipeline |
| Right PCB | Fabricated, assembled, **working** |
| **Left PCB** | **Active** — needs camera connector + capacitive trackpad |
| CAD | Right arm + frame print-ready; **left arm active** |
| Performance testing | Instrumented (`[PERF-Mxx]`); Session 2 done, **Session 3 pending** |
| Display (HUD) | **Research only** — no hardware yet |

**Already-learned key lesson:** C6 → S3 migration was measurement-driven (dual I2S removes the ~50 ms mic/speaker mode-switch; `WRITE_NR` took BLE 14 → 49 KB/s; plus the camera vision mode depends on).

---

## 2. Competitive positioning (mid-2026 market)

The market has split into **four segments**. This project sits in **segment 1** (camera + audio AI glasses) with an optional path to a cheap **segment 2** glanceable HUD.

| Segment | Camera | Display | Compute | Examples | Price |
|---|---|---|---|---|---|
| **1. Camera + audio AI** (volume sweet spot) | ✔ | ✗ | phone/cloud | Ray-Ban Meta Gen 2, Oakley Meta | $299–499 |
| **2. Glanceable HUD** (fast-growing) | sometimes | ✔ mono | phone/cloud + some on-device | Even G2, Brilliant Halo, Meta RB Display, Rokid Glasses | $349–799 |
| **3. AR/XR viewers** (big screen) | rarely | ✔ large | tethered | Xreal One, RayNeo X3 | $269–1,299 |
| **4. Audio-only** (invisible) | ✗ | ✗ | phone/cloud | Echo Frames, Solos, Lucyd | $99–299 |

**Direct peers / benchmarks:**

- **Ray-Ban Meta Gen 2** (the category benchmark, $379): Snapdragon AR1, 12 MP / 3K video, **5-mic array**, open-ear speakers, **~8 h** + 48 h case, ~49 g, **no display**, cloud Meta AI. This is the bar for capture quality, mic count, battery, and styling.
- **Brilliant Labs Frame / Halo** — *the closest open/hackable analogues.* **Frame**: fully open (CERN-OHL + code), 0.23" micro-OLED on a prism, 640×400, OV9734 camera, nRF52840+FPGA, BLE-only, $349 (now delisted). **Halo** (shipping 2026, ~$349–399): 0.2" full-color micro-OLED, bone conduction, **Alif B1 (M55+NPU) for hybrid on-device vision** (Liquid AI LFM2-VL), open source. Halo's camera is **AI-inference-only, no user capture, no LED** — a deliberate privacy bet.
- **Even Realities G1/G2** ($499/$599) — **camera-free by design** (the explicit anti-Meta privacy stance); green micro-LED + waveguide, all compute on phone. G2 is 36 g, ~2-day battery, has a third-party app store (Even Hub / MentraOS).
- **Rokid Glasses** ($499) — the strongest lightweight *display* rival to Meta: ~48 g, dual green micro-LED waveguide, 12 MP camera, Snapdragon AR1 + NXP RT600.

**Most directly reusable prior art (same platform!):**

- **OpenGlass / OmiGlass** (Based Hardware, **MIT**) — DIY AI glasses on the **exact XIAO ESP32-S3 Sense + OV2640**. Capture image every few seconds → cloud vision (GPT-4o/Groq) or local Ollama+Moondream. **The single most liftable codebase for this project.** `github.com/BasedHardware/omi` (omiGlass).
- **LILYGO T-Glass** — ESP32-S3 + **1.1" AMOLED via prism combiner + LVGL** (no camera). Best open **display** template for an S3 build. ~$40–60.
- **TPGmini v2** — hacker AR glasses on XIAO ESP32-S3 Sense + OV2640, plus a dirt-cheap **OLED-reflected-off-a-mirror** combiner and a mini-OS with a Gemini assistant. Great architecture + cheap-display reference.
- **SDK design pattern to copy:** Brilliant's **typed BLE object protocol** (image/audio/IMU/text objects) with Python/Flutter host libs.

**Market trends that matter to strategy (2025–26):**

- **Waveguide display share jumped from ~13% → ~38% YoY** — display glasses are going mainstream, but everyday-tier displays are **small, monochrome/low-color, ~20–30° FOV**.
- **Platform war forming:** **Android XR + Gemini** (Google/Samsung/Qualcomm, fall 2026, AR1 silicon, Warby Parker/Gentle Monster frames) turns "which AI assistant" into the defining choice.
- **Table-stakes** now: 12 MP + 3K video with a **hardware capture LED**, 5–6-mic **beamforming array**, open-ear audio, **all-day via charging case**, **hands-free wake-word multimodal AI**, ~40–50 g, prescription support, a companion app / SDK.
- **A real strategic fork exists on privacy:** two credible players (Even Realities; Halo's inference-only camera) bet that **omitting the outward camera** is a durable differentiator.

**The hard problems everyone faces:** battery/power (the dominant constraint), weight/thermal (<50 g target), display optics (waveguide FOV & full-color yield), **at-distance mic pickup**, latency, and **hands-free input** (hence Meta's EMG band, Even's R1 ring).

---

## 3. Technical SOTA findings that change the plan

**A. BLE 49 KB/s is soft, not a ceiling — biggest cheap win.** MTU 247 + Data Length Extension + **2M PHY** + short connection interval realistically reaches **~120–160 KB/s (~3×)** on the *same hardware*. Pure firmware/app work (`gatt.setPreferredPhy` / `esp_ble_gap_set_prefered_phy`, `CONNECTION_PRIORITY_HIGH`). This directly fixes the "ring buffer runs thin on long TTS" problem (OTA 38 < 48 KB/s playback) and cuts latency. *2M PHY halves range — irrelevant glasses-to-pocket.*

**B. Capture at VGA/SVGA, not 3 MP.** GPT-4o/Claude/Gemini vision all downscale to ~768 px shortest side and tile at 512×512 — anything past ~768–1024 px buys **zero** accuracy but wastes DVP bandwidth, PSRAM, JPEG encode, BLE airtime, upload latency, and tokens. VGA JPEG ≈ 15–40 KB transfers **2–5× faster** than a 3 MP frame.

**C. On-device wake word gates everything.** **microWakeWord** (free, custom-trainable, ESP32-S3-native, <10 ms, ~0.19 false-accepts/hr) means **no audio leaves the glasses until intent** — a simultaneous win for privacy, power, latency-to-first-token, and cloud cost. (Whisper/LLM/general-TTS do **not** fit on the S3; only wake-word + VAD + small command grammars do — ESP-SR WakeNet/MultiNet.)

**D. The realtime-API shift can collapse latency.** Current sequential pipeline is ~10.5 s end-to-end. **Cloud speech-to-speech** — **Gemini Live** (cheap audio ~$0.037/min + native vision) or **OpenAI `gpt-realtime-mini`** (~$0.06–0.10/min) — gives ~300–600 ms turn latency and folds STT+LLM+TTS into one stream. Big UX change; the phone bridges it.

**E. Power budget realities.** Untethered camera-glasses temples hold **~100–260 mAh**. With DFS + auto light-sleep + gating the OV3660 rail and MAX98357A SD pin: idle+BLE ≈ 13–40 h, but continuous mic+BLE ≈ 2–3 h and camera streaming ≈ 1–1.3 h. **All-day only works with >90% sleep + burst interactions + a charging case** — the universal commercial pattern. Lab-feasible parts: MCP73831 charger (tiny, thin-frame-friendly), MAX17048 fuel gauge, 150–350 mAh cells.

**F. At-distance mic pickup is the known weak point (doc confirms).** Realistic lab path: **2× matched digital-MEMS mics ~5 cm apart → ESP-SR AFE on the S3** (AEC + BSS directional + NS + wake word, ~22% CPU). *Note:* the current INMP441 is 61 dBA SNR — slightly below the ≥62–64 dB Espressif recommends for arrays; higher-SNR MEMS improves distance. Far-field/interlocutor capture (Meta's "Conversation Focus" class) needs a dedicated DSP (XMOS XVF3800) — prototype-only, too big for a slim temple.

**G. Display: the interface is the real problem, not the optics.** The **ESP32-S3 has no MIPI-DSI**, and essentially every AR-grade micro-OLED speaks MIPI/LVDS/parallel-BT.656 — never SPI. Three realistic paths:
1. **Fast PoC (days):** micro-OLED panel + off-the-shelf HDMI-to-MIPI board (e.g. YX 0.23" kit ~$175, or a **GZOT** panel + driver board) fed by a **Pi/phone/mini-PC**, through a birdbath or small prism; keep the S3 as sensor/AI/BLE co-processor.
2. **Self-contained (weeks):** move to **ESP32-P4** (2-lane MIPI-DSI + MIPI-CSI + JPEG codec) to drive a MIPI-DSI micro-OLED directly.
3. **"Invisible lens" (hard, buy):** JBD green-microLED projector + diffractive-waveguide dev kit. *Waveguide fabrication, full-color microLED, laser/retinal = industrial only.*
   - **GZOT specifics:** sells single units to hobbyists (Tindie/Taobao/AliExpress); 0.2–1.3" OLEDoS panels; **but most expose parallel/BT.656, not MIPI or SPI** — you pair with an HDMI-to-panel board. ~$150–300/panel+board. `XGA039HW03G` = 4000 nit typ / 8000 max mono-green (sunlight-usable).

**H. Audio output: keep what you have.** Open-ear micro-speaker + your **MAX98357A** is exactly the industry choice (sounds better/louder than bone conduction; leakage acceptable at normal volume). The same amp drives an 8 Ω bone-conduction transducer if you want to A/B. (xMEMS solid-state speakers are OEM-only — needs a high-voltage piezo driver; track, don't adopt.)

**I. Privacy/capture LED is table-stakes and increasingly legal.** Ray-Ban Meta's front capture LED was hardened under regulatory pressure (1→2 mm, constant, obstruction detection). US state bills trending toward mandates (PA June 2025; **CA SB 1130, Feb 2026**); EU GDPR + AI Act (fully applicable **Aug 2026**). **Add a front LED wired in hardware to the camera power rail** (~$0.02, can't be silently disabled) — ethically/IRB-defensible and future-proof.

---

## 4. Prioritized roadmap — what needs to be done

### Tier 0 — Correctness bugs (firmware/app only, from Session 2 perf report)

> **Status check (July 7, 2026):** the Session 2 report was measured on the *old*
> `S3_App_imp` firmware. The V2 rewrite (commits `7726dab`, `0033e10`, `f17d713`)
> had already fixed most of Tier 0 before this roadmap was written; the rest was
> implemented today. All of it needs **hardware validation** — see
> `repo/Preformance Tests/S3/S3_Session3_TestPlan.md`.

1. ✅ **Mic hallucinations** — landed in repo: `MIC_GAIN=1` (fw gain removed), app-side silence trim + VAD guard + normalize capped at 8× (`VoiceAssistantPipeline`), `notifyWithRetry` on every mic notification + seq-gap silence insertion (BLE loss made benign). *Remaining: validate on hardware (Session 3 T3).*
2. ✅ **Image/video JPEG corruption** — video `'J'` was already in-band; **today the whole image path moved in-band on IMAGE_TX** (`'H'` header / `'I'` fragments / `'J'` end for stills *and* video), all guard delays removed, 'W'-overtakes-last-frame race closed (fw 50 ms guard + app salvage). App stays backward-compatible with old firmware.
3. ✅ **Ring-buffer headroom** — already 256 KB / 70 KB threshold in `config.h`.

### Tier 1 — High-ROI, no new hardware

4. ✅ **BLE throughput** — **2M PHY now requested from both sides** (fw `ble_gap_set_prefered_le_phy` + app `setPreferredPhy`, negotiated PHY logged as `[PERF-M35]`); MTU was already 512 + high-priority interval. *Next tuning knob after 2M PHY is verified: the per-fragment pacing delays (`BLE_FRAG_DELAY_MS` 5 / `BLE_IMG_FRAG_DELAY_MS` 15) — they cap throughput at ~100/34 KB/s regardless of PHY.*
5. ✅ **Capture resolution** — already QVGA + `jpeg_quality` 12 in `camera_ctl.cpp`. (If vision accuracy ever feels limited, there's headroom *up* to VGA once 2M PHY lands — GPT-4o resolves ~768 px shortest side.)
6. **Complete Performance Session 3** — plan written (`S3_Session3_TestPlan.md`); firmware `[PERF-M]` markers (M7/M10/M13/M25) restored to V2 today (the rewrite had dropped them). Marker numbering resolved: fw keeps M-low, app pipeline is M30+, fw/app pairs (M10) intentionally share a number for the two ends of the same transfer. **← next hardware session**
7. **Latency: evaluate a realtime speech-to-speech API** (§3-D) — spike **Gemini Live** or **`gpt-realtime-mini`** on the phone; compare to the current ~10.5 s pipeline. Potentially the single biggest UX improvement. **← next app-only workstream**

### Tier 2 — Hardware currently in progress (per the reference doc)

8. **Left PCB** — mirror right-side core circuitry **+ camera connector + native capacitive trackpad**.
9. **Left CAD arm** — route the camera connector, host the trackpad; keep Prusa/Ultimaker exports.
10. **Port capacitive-touch gestures** from the TK43-TP223 proof-of-concept to native S3 touch GPIOs.

### Tier 3 — Power & privacy (needed for a wearable product)

11. **Power management** (§3-E): `esp_pm_configure()` DFS + auto light-sleep; gate OV3660 rail + MAX98357A SD pin; longer BLE interval + slave latency when idle. Add **MCP73831 charger + MAX17048 fuel gauge + charging cradle**.
12. **On-device wake word** (§3-C): integrate **microWakeWord** so nothing streams until intent — privacy + power + latency + cost, all at once.
13. **Hardware capture LED** (§3-I) wired to the camera rail; define a bystander consent/notification story before any always-on audio path.

### Tier 4 — Bigger bets / research tracks (parallel, not near-term)

14. **Mic array for at-distance pickup** (§3-F): 2× matched MEMS ~5 cm apart → **ESP-SR AFE**. Addresses the doc's stated weak point. (Bench-prototype far-field with reSpeaker XVF3800.)
15. **Display track** (§3-G): decide the platform fork early —
    - **Option A:** keep S3, add a display **co-processor** (Pi Zero / phone-DP source) — fastest PoC.
    - **Option B:** migrate the display path to **ESP32-P4** (MIPI-DSI) for a self-contained build.
    - Start with a GZOT/YX micro-OLED + combiner PoC before committing to frame integration. Keep it a **glanceable monocular HUD** (matches the everyday tier).

---

## 5. Key strategic decisions to make

1. **Display or not?** The market is bifurcating, not converging. No-display (segment 1) is the volume sweet spot and a real **power/weight advantage**; Even Realities and Halo prove **camera-less** and **display-first** are both viable differentiators. Decide whether the HUD is a product goal or a research demo.
2. **AI backend + interaction model.** Stay with the sequential OpenAI pipeline, or move to **realtime S2S** (Gemini Live = cheaper audio + native vision; `gpt-realtime-mini` = best tool ecosystem). This drives latency, cost, and privacy posture.
3. **Platform for the display path.** ESP32-S3 **cannot** drive an AR-grade panel directly — commit to a co-processor vs an **ESP32-P4** move before designing the left arm around a display.
4. **Privacy stance.** Capture LED + consent is table-stakes and trending legal; also an IRB consideration for a university lab.

---

## 6. Reference

**Reusable open codebases:** OmiGlass `github.com/BasedHardware/omi` (omiGlass) · TPGmini `github.com/MinhHixn/TPGmini` · LILYGO T-Glass (LVGL) · Brilliant Frame SDK `docs.brilliant.xyz/frame/frame-sdk`.

**On-device building blocks:** microWakeWord `microwakeword.com` · ESP-SR (WakeNet/MultiNet/AFE) `docs.espressif.com/projects/esp-sr` · ESP-IDF power mgmt + BLE throughput guides.

**Realtime AI:** OpenAI gpt-realtime `openai.com/index/introducing-gpt-realtime` · Gemini Live `ai.google.dev/gemini-api/docs/live-api`.

**Display supply:** GZOT `gzot.com/en` (+ Tindie) · YX 0.23" HDMI kit · DisplayModule MIPI panels · ESP32-P4 DSI docs.

**Competitors (specs):** Ray-Ban Meta `en.wikipedia.org/wiki/Ray-Ban_Meta` · Brilliant Halo `brilliant.xyz/products/halo` · Even G1/G2 `evenrealities.com` · Rokid Glasses `global.rokid.com`.

*Full source lists and the raw competitive/technical research briefs are available on request — this file condenses them against the project's actual state and backlog.*
