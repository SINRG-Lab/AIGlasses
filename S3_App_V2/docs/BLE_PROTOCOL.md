# BLE Protocol Specification

Identical to V1 (`S3_App_imp`) with **one backward-compatible addition**: the `'X'`
playback-cancelled notification (barge-in). An un-updated Android app still works —
it ignores the unknown tag and simply keeps streaming TTS the glasses discard,
which is the pre-V2 behavior.

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
latency 0, supervision timeout 5 s.

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

### Marker reliability
Control markers are write-with-response and the app retries queue-busy rejections
(`writeCharacteristic()` returning false) just like audio fragments; a send is
aborted with an error event if `'S'` cannot be queued at all. As a second line of
defense the firmware runs a 4 s stall watchdog: a stream that stops receiving data
with no `'E'` finishes cleanly, and one that never received data resets to idle.

## Images (snapshot / vision)

```
CONTROL  notify  'I' 0x00 <len: u32 LE>   image header (total JPEG size)
         — 40 ms gap (cross-characteristic ordering guard) —
IMAGE_TX notify  'I' seq <jpeg frag>...   507-byte fragments, 15 ms pacing
         — 30 ms gap —
CONTROL  notify  'J' 0x00                 image complete — phone reassembles
```

A standalone photo (quick double-tap) is the same sequence *without* a following
audio `'E'`; the phone stores it and attaches it to a voice question asked within 5 s.
A vision request (double-tap + hold) sends the image after recording ends, then `'E'`.

## Video

```
CONTROL  notify  'V' 0x00                 video session start
  per frame:
    CONTROL  notify  'I' 0x01 <len u32 LE>   header, flags=0x01 marks video frame
    IMAGE_TX notify  'I' seq <jpeg frag>...
    IMAGE_TX notify  'J' frameIdx            frame end — ON IMAGE_TX, not CONTROL
CONTROL  notify  'W' <totalFrames>        video session end
```

The per-frame `'J'` rides the **IMAGE_TX** characteristic deliberately: BLE
guarantees ordering only *within* a characteristic, so this makes it impossible
for the end marker to overtake the last data fragment. Moving it there (V1 fix)
eliminated a structural race that corrupted ~25 % of frames; the session-level
markers (`'V'`/`'W'`) stay on CONTROL.

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
| `'I'` | CONTROL | Image header (`flags`: 0x00 photo, 0x01 video frame) + u32 LE size |
| `'I'` | IMAGE_TX | JPEG fragment |
| `'J'` | CONTROL (photo) / IMAGE_TX (video frame) | Image / frame end |
| `'V'` | CONTROL | Video session start |
| `'W'` | CONTROL | Video session end (+ frame count) |
| `'X'` | CONTROL (glasses → phone) | Playback cancelled (barge-in) — stop streaming TTS |
