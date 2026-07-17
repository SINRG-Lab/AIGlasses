# BLE Protocol Specification

Identical to V1 (`S3_App_imp`) with **two backward-compatible additions**:

1. The `'X'` playback-cancelled notification (barge-in). An un-updated Android app
   still works — it ignores the unknown tag and simply keeps streaming TTS the
   glasses discard, which is the pre-V2 behavior.
2. **In-band image framing**: the image header (now `'H'`) and every image/frame
   end marker (`'J'`) ride the IMAGE_TX characteristic together with the data
   fragments, instead of CONTROL. The app still accepts the legacy CONTROL-channel
   `'I'` header / `'J'` end marker, so it works with old firmware; old apps need
   the update to receive images from this firmware.

## GATT layout

Device name: **`AIGlasses-ESP32S3`** · MTU: 512 requested (payload = MTU − 3 ATT bytes)

| Characteristic | UUID | Properties | Direction / purpose |
|---|---|---|---|
| AUDIO_TX | `0000aa01-1234-5678-abcd-0e5032c6b1e0` | NOTIFY | Mic PCM → phone |
| AUDIO_RX | `0000aa02-…` | WRITE, WRITE_NR | TTS PCM → glasses |
| CONTROL | `0000aa03-…` | WRITE, WRITE_NR, NOTIFY | Markers & headers, both ways |
| IMAGE_TX | `0000aa04-…` | NOTIFY | JPEG fragments → phone |

Service UUID: `0000aa00-1234-5678-abcd-0e5032c6b1e0`

On connect the glasses request fast connection parameters: interval 7.5–15 ms,
latency 0, supervision timeout 5 s. Both sides also request the **2M PHY**
(BLE 5, double the 1M symbol rate); the link falls back to 1M automatically on
phones without 2M support. The negotiated PHY is logged on the firmware serial
(`[BLE] PHY updated`) and in Android logcat (`[PERF-M35]`).

## Packet framing

Every data packet: `[TAG: 1 byte][SEQ: 1 byte][payload ≤ 507 bytes]`.
SEQ increments per fragment and wraps at 255; the receiver uses gaps for loss stats.

## Audio

PCM is 16-bit little-endian mono. **Mic → phone: 16 kHz**. **Phone → glasses: 24 kHz**
(OpenAI TTS native rate — no resampling on either side).

### Glasses → phone (recording)
```
CONTROL  notify  'S' 0x00            recording starts — phone flushes stale chunks
AUDIO_TX notify  'A' seq <pcm>...    repeated while button held (seq starts at 0)
CONTROL  notify  'E' 0x00            utterance complete — phone runs ASR
```

### Phone → glasses (TTS playback)
```
CONTROL  write   'S'                 reset ring buffer, enter BUFFERING
AUDIO_RX write   'A' seq <pcm>...    streamed TTS audio
CONTROL  write   'E'                 no more data — glasses drain and stop
```
Playback starts once ~70 KB (≈1.4 s) is buffered, or immediately on `'E'` for
short responses.

### Barge-in (V2 addition)
```
CONTROL  notify  'X' 0x00            user pressed the button during playback
```
The glasses tear down the speaker, return to idle, and treat the same press as the
start of a new recording (the user shouldn't have to press twice). On receiving
`'X'` the app aborts its TTS transmit loop. The app deliberately does **not** send
the trailing `'E'` after a cancel — a late `'E'` could land after the `'S'` of the
user's next question and terminate that stream early. The glasses also ignore `'A'`
audio writes while idle (no `'S'` seen), so cancelled-stream stragglers are discarded.

### Realtime voice mode (V3, opt-in — GPT Realtime speech-to-speech)

The phone writes **`'M'`** on CONTROL to enable, **`'m'`** to disable. Without
`'M'` the firmware behaves exactly as V2 (PCM16) — old apps are unaffected.

When enabled, all `'A'` audio payloads in BOTH directions are **G.711 µ-law**
(1 byte/sample): mic → phone at 16 kHz, response → glasses at 24 kHz (GPT
Realtime's native rate — the playback path is unchanged, samples are expanded
to PCM16 as they enter the ring). Rationale, bench-measured: phone-side BLE
stacks drop notifications above ~40/s; continuous PCM16 mic audio needs ~63/s
and lost ~40% of frames, while µ-law needs ~32/s and lost none. The phone-side
seq handling must drop duplicate/stale frames (seq delta 0 or ≥200 mod 256 —
stale GATT-cache double-subscriptions duplicate deliveries) and zero-fill only
small gaps (1–8 frames).

The interaction model stays push-to-talk: mic frames flow only while the
button is held; the phone appends ~600 ms of silence to the cloud session
after frames stop so server-side VAD closes the turn.

### Marker reliability
Control markers are write-with-response and the app retries queue-busy rejections
(`writeCharacteristic()` returning false) just like audio fragments; a send is
aborted with an error event if `'S'` cannot be queued at all. As a second line of
defense the firmware runs a 4 s stall watchdog: a stream that stops receiving data
with no `'E'` finishes cleanly, and one that never received data resets to idle.

## Images (snapshot / vision)

The whole image path is **in-band on IMAGE_TX** — header, fragments, and end
marker share one characteristic, whose notifications BLE delivers strictly in
order. Header-after-data and end-before-data races are therefore structurally
impossible, and no guard delays are needed.

```
IMAGE_TX notify  'H' 0x00 <len: u32 LE>   image header (total JPEG size)
IMAGE_TX notify  'I' seq <jpeg frag>...   507-byte fragments, 15 ms pacing
IMAGE_TX notify  'J' 0x00                 image complete — phone reassembles
```

A standalone photo (quick double-tap) is the same sequence *without* a following
audio `'E'`; the phone stores it and attaches it to a voice question asked within 5 s.
A vision request (double-tap + hold) sends the image after recording ends, then `'E'`.

**Legacy framing** (pre-in-band firmware): header `'I' flags <len u32 LE>` on
CONTROL, 40 ms guard gap, fragments, 30 ms gap, end `'J'` on CONTROL. The app
still accepts this, so it remains compatible with old firmware. The guard delays
only papered over the cross-characteristic race — under load the header could
still land after fragments (the phone reset its reassembly buffer mid-image) and
the end marker could still overtake the tail fragments (truncated JPEG).

## Video

```
CONTROL  notify  'V' 0x00                 video session start
  per frame:
    IMAGE_TX notify  'H' 0x01 <len u32 LE>   header, flags=0x01 marks video frame
    IMAGE_TX notify  'I' seq <jpeg frag>...
    IMAGE_TX notify  'J' frameIdx            frame end
CONTROL  notify  'W' <totalFrames>        video session end
```

Everything per-frame rides IMAGE_TX for the ordering guarantee above — the V1
fix that moved the per-frame `'J'` there eliminated a structural race that
corrupted ~25 % of frames; V2 moved the header in-band as well. Only the
session-level markers (`'V'`/`'W'`) stay on CONTROL. Because `'W'` crosses
characteristics with the last frame's `'J'`, the firmware waits 50 ms before
sending it, and the app additionally salvages a fully-received in-flight frame
if `'W'` arrives early.

## Flow control

`notify()` returns false when NimBLE's host TX buffer is full. Every notification
in this firmware goes through `notifyWithRetry()` — retry every 5 ms, up to 50
tries (~250 ms), aborting on disconnect. Fire-and-forget notifies were the root
cause of two historic bugs:
- **JPEGs that wouldn't decode** — silently dropped fragments.
- **ASR hallucinations** — a dropped mic fragment byte-shifts every subsequent
  int16 sample, turning clean speech into full-scale noise.

Pacing between fragments (5 ms audio / 15 ms image) keeps to roughly one
notification per connection event, which is what the Android stack reliably accepts.

## Tag summary

| Tag | Channel | Meaning |
|---|---|---|
| `'S'` | CONTROL (both directions) | Stream start (recording / TTS) |
| `'E'` | CONTROL (both directions) | Stream end |
| `'A'` | AUDIO_TX / AUDIO_RX | PCM audio fragment |
| `'H'` | IMAGE_TX | Image header (`flags`: 0x00 photo, 0x01 video frame) + u32 LE size |
| `'I'` | IMAGE_TX | JPEG fragment |
| `'J'` | IMAGE_TX | Image / video-frame end |
| `'V'` | CONTROL | Video session start |
| `'W'` | CONTROL | Video session end (+ frame count) |
| `'X'` | CONTROL (glasses → phone) | Playback cancelled (barge-in) — stop streaming TTS |
| `'M'` / `'m'` | CONTROL (phone → glasses) | Realtime voice mode on/off (µ-law audio both ways) |
| `'I'` | CONTROL *(legacy, app-accepted)* | Old image header from pre-in-band firmware |
| `'J'` | CONTROL *(legacy, app-accepted)* | Old still-image end from pre-in-band firmware |
