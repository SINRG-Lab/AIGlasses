# Changes from V1 (`S3_App_imp.ino`)

V1 worked, but it was a single 1,174-line file that had accreted fixes. V2 keeps
every hard-won behavior (the comments documenting *why* are preserved next to the
code they explain) and restructures around it. **The BLE protocol is byte-for-byte
identical** — no Android changes needed.

## Structural

| V1 | V2 |
|---|---|
| One `.ino`, all concerns interleaved | 7 modules + a thin application sketch |
| Constants scattered through the file | Single `config.h` |
| Magic numbers (retry counts, pacing delays) inline | Named constants with units in comments |
| `goto finish` in the playback loop | `finishPlayback()` helper, early returns |

## Behavioral fixes

1. **Triple-tap video was nearly unreachable.** V1 sent the standalone photo
   immediately on the 2nd quick tap and reset the tap counter, so a quick
   *triple*-tap usually fired a photo instead of starting video (it only worked
   when the photo capture happened to fail). V2 defers the photo send until the
   tap window (600 ms) closes; a 3rd tap inside the window upgrades the gesture
   to video and discards the photo. If a voice question starts while a photo is
   pending, the photo is flushed first so the phone's 5-second attach window
   still works.

2. **Stale sequence tracking (phantom packet-loss).** V1's `expectedSeq` lived in
   the RX callback object and was never reset between TTS streams, so the first
   chunk of every stream after the first could log a fake "SEQ gap" and skew the
   loss stats. V2 resets sequence state on every `'S'` marker.

3. **Video markers had no flow control.** V1's `sendVideoStartMarker` /
   `sendVideoEndMarker` used fire-and-forget `notify()` — the same pattern that
   caused the historic dropped-fragment bugs everywhere else. A dropped `'W'`
   leaves the phone waiting forever for a video that already ended. V2 routes
   every notification through `notifyWithRetry`.

4. **VLA stack buffers.** V1 built packets in variable-length stack arrays
   (`uint8_t pkt[BLE_HEADER_SIZE + fragSize]`) inside the mic/image/video send
   loops — up to ~509 bytes of stack per call, in functions reached from
   different depths. V2 uses one static scratch buffer (safe: all sends happen
   on the main loop task).

5. **Blocking debounce.** V1 debounced with `digitalRead → delay(10) →
   digitalRead` every loop iteration, stealing 10 ms from mic pacing and video
   frame rate. V2 uses an edge-filtered millis() debounce (15 ms stability
   requirement) that costs nothing per iteration.

6. **Disconnect during playback could race I2S teardown.** V1's `onDisconnect`
   (NimBLE task) reset playback state while the main loop might be inside
   `i2s_channel_write`. V2 only sets an abort flag from the BLE task; the
   speaker teardown always runs in main-loop context on the next tick.

## New features

7. **Barge-in.** A button press during TTS playback cancels it (drains, tears
   down the speaker, resumes the mic). V1 ignored the button until playback
   finished — annoying for long answers. The glasses also notify the app with a
   new `'X'` control tag so it stops streaming the rest of the TTS (matching
   change in `BleVoiceService.kt`); without the app update, barge-in still works
   locally and the leftover stream is discarded. Audio writes arriving with no
   active stream (no `'S'`) are now ignored rather than buffered.

8. **Status LED.** Advertising = slow flash, recording = solid, playback = fast
   blink, video = double-blink. Debugging connection state no longer requires a
   serial cable.

9. **Log verbosity switch.** `LOG_VERBOSE 0` in `config.h` silences per-chunk
   chatter while keeping state transitions and errors.

10. **Stall watchdog.** If a TTS stream opens but data/markers stop arriving
    (lost `'E'`, app crash mid-send), the firmware finishes or resets after
    `STREAM_STALL_TIMEOUT_MS` (4 s) instead of soft-locking in PLAYING with a
    dead button. Audio arriving with no open stream (lost `'S'`) is logged —
    that's the "transcription fine, playback silent" signature. The matching
    app-side fix retries the `'S'`/`'E'` control writes, which previously
    ignored `writeCharacteristic()` failures and could drop markers silently.

11. **Barge-in flows into the new recording.** The press that cancels playback
    also starts recording (same hold), rather than being consumed — otherwise
    the user holds a dead button and the whole question goes unheard.

12. **Mic DMA flush at recording start.** Between utterances nobody reads the
    PDM mic, so its DMA queue holds 100+ ms of stale audio that V1 prepended to
    every utterance — a possible contributor to off transcriptions. V2 drains
    it (`micFlush`) before the `'S'` marker.

## Inherited V1 fixes (kept, now documented in place)

These were discovered the hard way during V1 development and are preserved:

- Camera initializes **before** PSRAM/DMA allocations (NULL-frame conflicts).
- Warm-up frames discarded at init and before every snapshot (blank-photo fix).
- `notifyWithRetry` everywhere (dropped-fragment JPEG corruption, byte-shifted
  PCM → Whisper hallucinations).
- Per-frame video `'J'` marker on IMAGE_TX, not CONTROL (cross-characteristic
  ordering race, ~25 % frame corruption).
- Mic paused during playback (PDM clock ↔ speaker BCLK crosstalk).
- Speaker channel deleted + DIN driven low after playback (idle-clock hiss).
- Underrun policy: let DMA drain instead of splicing silence ("bubbling" artifact).
- `MIC_GAIN = 1` — software gain clipped loud speech and fed ASR hallucinations;
  leveling belongs to the Android normalizer.
- 70 KB pre-buffer: BLE receive (~38 KB/s) is slower than playback (~48 KB/s),
  so long TTS responses need runway.

## Verification

Compiles clean with pioarduino espressif32 51.3.7 (Arduino core 3.x),
`board = seeed_xiao_esp32s3`, NimBLE-Arduino 2.x: RAM 11.7 %, flash 18.6 %.
On-hardware behaviors to re-verify after flashing: gesture timing feel,
barge-in teardown pop, and LED visibility through the frame.
