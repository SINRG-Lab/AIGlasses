# Friday Build Plan — Minimum Professional Glasses (Mic + Camera + App)

*Tuesday → Friday sprint. Goal: wearable, Meta-looking glasses that capture voice +
photos and talk to the companion app, on battery, demo-ready. Everything else is
next week.*

**Definition of done (Friday demo script):**
1. Put on glasses, open app → auto-connects (BLE), status shows Connected.
2. Hold button, ask a question → transcription + answer appear in app, answer plays
   through the glasses speaker.
3. Quick double-tap → photo lands in the app gallery.
4. Double-tap + hold, ask "what am I looking at?" → vision answer.
5. All wireless, on battery, in a 3D-printed frame that photographs well.

---

## The problems, in dependency order

### P1 — Parts gap (TODAY, first hour — everything else waits on this)

From the photos, the prototype is missing, and you must find in the lab or overnight-order:

| Part | Why | Photo evidence |
|---|---|---|
| **XIAO Sense expansion board** | The camera **and the PDM microphone are both on it** — the base XIAO has neither. Without it there is no mic and no camera, period. | B2B connector empty in photo 2 |
| **OV2640/OV3660 camera module** (FPC) | Snaps onto the expansion board. Check FPC length vs where the camera sits in the frame (see P4). | not visible |
| **2.4 GHz u.FL antenna** (the XIAO's stamp antenna or any u.FL 2.4 GHz whip) | u.FL socket is bare in photo 2 → BLE will be weak/flaky without it. Demo killer. | photo 2 |
| **1S LiPo, JST-PH** (~150–500 mAh, whatever fits the arm) | No battery in photos. **Verify polarity with a multimeter against the board silk before plugging — JST polarity is not standardized and reversed packs kill boards.** | photo 1/3 |
| 8-pin FFC cable (correct length for the arm) | Board-to-board link through the temple | connectors visible both boards |
| USB-C cables ×2, charged power bank | flashing + fallback demo power | — |

Also confirm: an **OpenAI API key** with credit (goes in `AIglasses/secrets.properties`
as `OPENAI_API_KEY=...`), an Android phone you can install on, Android Studio on a lab
machine, Arduino IDE, and which 3D printer you have (the print files are UltiMaker
`.ufp` for S3/S5; Prusa gcode exists only in older CAD versions).

### P2 — Bench bring-up: electronics alive on the desk (Tuesday)

Order of operations — do not skip steps:

1. **Attach Sense expansion board + camera + antenna** to the XIAO.
2. **Flash baseline firmware** (`S3_App_V2/`, repo HEAD) via the XIAO's own USB-C.
   Arduino IDE settings that have burned this project before:
   - Board: **XIAO_ESP32S3**
   - **Tools → PSRAM → OPI PSRAM** ← camera silently dead without this (Session 2 lost a run to it; boot log must say `PSRAM: 8192 KB`, not 0)
   - Library: **NimBLE-Arduino ≥ 2.x** (h2zero), esp32 core per `S3_App_V2/README.md`
3. **Build the app** with the updated `BleVoiceService` (it's backward-compatible with
   this firmware) + your API key. Install on the phone.
4. **Battery check before glasses**: multimeter the JST polarity, connect, verify the
   charge LED behaves, verify the slide switch powers the stack, then run on battery.
5. **Green-light checklist** (all on the bench): connect → voice question round trip →
   double-tap photo decodes in gallery → vision query → TTS through speaker →
   ~10 min battery soak without a disconnect.

**Fallback:** if any custom board misbehaves, the demo can run on a bare
XIAO + Sense + battery tucked in the arm — the firmware is identical. Don't let PCB
debugging eat Wednesday.

### P3 — Firmware/app upgrade + validation (Wednesday morning)

The repo working tree has my fixes (uncommitted): in-band image framing (kills the
remaining photo-corruption race), 2M PHY (~2× BLE), restored perf markers, plus the
matching app. **These have never been compiled** (no toolchain on this Mac).

1. Flash the updated firmware, reinstall the updated app (do it as a pair).
2. Known compile risk: `onPhyUpdate` override in `ble_link.cpp` — if your installed
   NimBLE-Arduino version rejects it, delete that one function; everything else stands.
3. Run the mini validation from `Preformance Tests/S3/S3_Session3_TestPlan.md`:
   **T2** (PHY negotiated, logcat `[PERF-M35]`), **T3** (5 voice runs — fw bytes = app
   bytes, no hallucinations), **T5** (10 photos — 10/10 decode). ~45 min total.
4. Anything red → roll back firmware only (app is compatible with both) and demo on
   baseline. Record which version Friday runs on.

### P4 — The physical glasses (start prints WEDNESDAY MORNING — prints are the long pole)

This is the schedule risk. What's in CAD:

- `Cad Designs/V3/`: **RightArm.stl, ArmCover.stl, hinge system** (+ UltiMaker-ready `.ufp`) — current, print-ready.
- **V3 has no front frame and no left arm.** Those exist only in `Older/V1`
  (`FrontFrames v2.stl`, `LeftArm v1.stl`) — **verify the V3 hinge actually mates with
  the V1 front frame** (check `Hero Models/ExplodedView.pdf` for the intended assembly)
  before committing a long print. If they don't mate, adapt in Fusion (the `.f3d`
  sources are in the repo) — this is Wednesday's judgment call, and the reason prints
  start Wednesday, not Thursday.
- Left arm carries no electronics this week — it's cosmetic + counterweight. V1
  LeftArm or a mirrored RightArm shell is fine.

Physical problems to solve while printing:
1. **Camera placement**: it must face forward. Check the CAD's intended pocket; if the
   stock FPC is too short to reach the frame corner, options are (a) longer OV2640 FPC
   (order today), or (b) camera at the front tip of the arm angled forward — weakest-
   version acceptable.
2. **Mic port**: the PDM mic is a tiny hole on the Sense board — the arm cover needs a
   ≥1 mm opening over it (tape-test position before printing the cover). A sealed mic
   = muffled capture = hallucination territory; this ties directly into the mic
   root-cause work.
3. Button access, slide-switch access, both USB-C ports reachable (charge + reflash
   without disassembly), speaker not sealed dead, antenna routed away from the metal
   shield/battery.
4. Looks: black/dark filament, finest layer height the deadline allows, one pass of
   sanding on visible faces. Print a spare of every piece — hinges snap.

### P5 — Integration + dress rehearsal (Thursday)

1. Assemble electronics into the arm; re-run the P2 checklist **fully assembled** —
   enclosures change antenna behavior and mic/speaker acoustics; this is where you
   find it, not Friday.
2. Wear it and test: BLE range with phone in pocket (2M PHY shortens range slightly —
   confirm pocket-distance is solid), button reachable by feel, nothing hot.
3. Battery math: full charge, measure minutes of demo use; charge again overnight.
4. **Record a backup video of the full working demo on Thursday.** If anything dies
   Friday morning, you still have a demo.

### P6 — Friday

- Morning: one full rehearsal, phone DND on, brightness up, charged battery + power
  bank + USB-C cable in pocket as plan B (glasses run fine on wired power).
- Demo the P0 script. Keep questions short (long TTS answers take longer to stream).
- Afternoon: write down every flaw noticed during demo week — that list is next week's
  backlog (speaker quality, mic distance pickup, latency, left-side electronics).

---

## Split of labor

**You (physical, lab):** parts hunt, soldering/attaching, flashing, printing, assembly,
phone testing, the demo.

**Me (code/docs, on demand):** firmware & app changes and fix-ups when the build errors
(paste me any compile error), test scripts, tuning constants, README/demo script, the
mic research report (already running — feeds next week's audio quality work), next
week's roadmap.

## Known landmines (each has bitten this project before)

| Landmine | Guard |
|---|---|
| PSRAM disabled in IDE → camera silently dead | OPI PSRAM setting; check boot log |
| Reversed JST battery | Multimeter before first plug |
| No u.FL antenna → flaky BLE | Attach antenna before judging any BLE problem |
| Button reads HIGH at boot (wiring) | Firmware warns on serial — watch first boot |
| Speaker bursts brown out laptop USB power | Use battery or a solid 5 V supply when testing speaker |
| Long TTS answer during demo → slow start | Keep demo questions crisp; 2M PHY helps |
| Print fails overnight | Start Wednesday, print spares, have the V1 files as backup geometry |
