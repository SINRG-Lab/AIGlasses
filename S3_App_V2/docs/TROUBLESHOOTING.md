# Troubleshooting

Failure modes seen during development of V1/V2 and what actually fixes them.

## Audio out (speaker)

**Buzzy / distorted playback.**
The MAX98357A has fixed 9 dB gain; full-scale digital audio clips it. Raise
`SPK_VOL_SHIFT` in `config.h` (1 = −6 dB, 2 = −12 dB). Check the 5 V supply —
USB-port brownouts distort before they disconnect.

**Hiss after playback ends.**
Means the speaker teardown didn't run (look for `[STREAM] Playback complete` in
serial). The firmware deletes the I2S channel and drives DIN low specifically to
kill idle-clock hiss.

**Rhythmic "bubbling" during long answers.**
Underruns — BLE delivery is trailing the 48 KB/s playback rate. Raise
`STREAM_START_THRESHOLD` (more pre-buffer latency, more runway). Check serial
for `Underrun #N` lines and `seqGaps` in the completion stats: gaps mean packet
loss (interference / range), not buffering.

**Transcription correct but playback completely silent.**
Signature of a lost `'S'` start marker: the app's control writes used to ignore
`writeCharacteristic()` returning false (GATT op slot busy), silently dropping
the marker — the firmware then discards the whole TTS stream. Both sides now
defend against it: the app retries marker writes (`writeControlMarker`) and
aborts the send with an error event if `'S'` truly can't be queued, and the
firmware logs `Audio with no open stream — lost 'S' marker?` when it happens.
If you see that serial line, the marker was lost in flight; check logcat for
`Control marker 'S' write FAILED`.

**Glasses stop responding after a response finished playing.**
Old failure mode of a lost `'E'`: the firmware sat in PLAYING forever with the
button dead. The stall watchdog (`STREAM_STALL_TIMEOUT_MS`, 4 s) now finishes
the stream when audio stops arriving with no end marker — look for
`Stalled in PLAYING` in serial to confirm it fired.

## Mic / ASR

**Transcripts are garbage or hallucinated.**
Almost always dropped BLE notifications byte-shifting the PCM stream. V2 retries
every mic fragment, so if this appears, check for `gave up` conditions: the phone
is too far away or the connection interval got renegotiated upward. Also confirm
the Android normalizer is active rather than re-adding `MIC_GAIN` — software gain
on the glasses clips loud speech.

**First word of an utterance clipped.**
Increase the mic warm-up flush or check that `'S'` precedes the first `'A'` chunk
(the 10 ms gap in `bleSendAudioStart` exists because CONTROL and AUDIO_TX are
independent notification streams).

## Camera

**`esp_camera_fb_get()` returns NULL forever.**
Init-order problem: the camera must initialize before the ring buffer or mic
claim DMA/PSRAM. Don't reorder `setup()`.

**Photos come out blank/dark ~half the time.**
AEC/AGC hasn't converged after idling. That's what `CAM_WARMUP_FRAMES_CAPTURE`
discard frames are for — raise to 6 if it still happens (costs ~40 ms each).

**JPEG won't decode on the phone.**
Dropped fragments. Verify every send path uses `notifyWithRetry` and the pacing
delays haven't been reduced (`BLE_IMG_FRAG_DELAY_MS`); the 40 ms header gap and
30 ms pre-`'J'` gap guard cross-characteristic reordering for snapshots.

## BLE

**Phone can't find the device.**
The glasses only advertise when disconnected (slow-flash LED). If the LED shows
connected-idle (off) but the app sees nothing, the phone holds a stale bond —
forget the device in Bluetooth settings.

**Disconnects during playback.**
Usually power, not radio: amp current spikes brown out the 3V3 rail. Battery or
bench supply, and watch for `reason=8` (supervision timeout) in serial.

**Random `SEQ gap` logs at the start of every response (V1 only).**
Stale `expectedSeq` — fixed in V2 (`playbackOnStart` resets sequence tracking).

## Gestures

**Triple-tap starts a photo instead of video (V1 only).**
Design flaw fixed in V2: the photo is held until the 600 ms tap window closes.

**Taps register twice / not at all.**
Tune `DEBOUNCE_MS` (15 ms default). If the button reads HIGH at boot, the
firmware blocks with a warning — that's a wiring or pulldown problem, not a
software one.

**Button does nothing while the glasses are speaking.**
That's V1. V2 cancels playback on press (barge-in).
