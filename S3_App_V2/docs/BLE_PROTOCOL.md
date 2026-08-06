# BLE Protocol Specification

BLE is the **primary, always-on transport**: control markers, realtime voice
audio, and photo fallback all ride it. Same GATT layout as V1 (`S3_App_imp`)
with backward-compatible additions:

1. The `'X'` playback-cancelled notification (barge-in). An un-updated app
   still works — it ignores the unknown tag and simply keeps streaming TTS the
   glasses discard, which is the pre-V2 behavior.
2. **In-band image framing**: the image header (now `'H'`) and the image end
   marker (`'J'`) ride the IMAGE_TX characteristic together with the data
   fragments, instead of CONTROL. The app still accepts the legacy CONTROL-channel
   `'I'` header / `'J'` end marker, so it works with old firmware; old apps need
   the update to receive images from this firmware.
3. **Transport V2** (2026-08): `'P'` ping echo, the `'F'`/`'f'`/`'N'` WiFi-link
   bootstrap, and the `'T'` binary stats packet — all on CONTROL, all ignored
   by older apps. The video feature (`'V'`/`'W'`) was removed.

## GATT layout

Device name: **`AIGlasses-ESP32S3`** · MTU: 512 requested (payload = MTU − 3 ATT bytes)

| Characteristic | UUID | Properties | Direction / purpose |
|---|---|---|---|
| AUDIO_TX | `0000aa01-1234-5678-abcd-0e5032c6b1e0` | NOTIFY | Mic PCM → phone |
| AUDIO_RX | `0000aa02-…` | WRITE, WRITE_NR | TTS PCM → glasses |
| CONTROL | `0000aa03-…` | WRITE, WRITE_NR, NOTIFY | Markers & headers, both ways |
| IMAGE_TX | `0000aa04-…` | NOTIFY | JPEG fragments → phone |

Service UUID: `0000aa00-1234-5678-abcd-0e5032c6b1e0`

After connect the glasses tune the link, **staggered** from the main loop
(`bleTick()`) so the three LL procedures never collide with the central's own
MTU exchange + service discovery (a known early-drop trigger on iOS):

| When | Procedure |
|---|---|
| +300 ms | Connection parameters: interval 7.5–15 ms, latency 0, supervision timeout 5 s |
| +600 ms | **2M PHY** request (1M+2M mask; falls back to 1M on phones without 2M) |
| +900 ms | Data Length Extension: 251 bytes / 2120 µs |

The negotiated PHY is logged on the firmware serial (`[BLE] PHY updated`) and
reported to the app in the `'T'` stats packet.

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

## Video — removed (Transport V2)

The live-video feature (triple-tap gesture, `'V'`/`'W'` session markers,
`flags=0x01` video frames) was **removed**. This firmware never sends video
packets; a triple-tap logs and does nothing. Apps should tolerate (ignore with
one log line) `'V'`/`'W'`/video-flagged frames from older firmware.

## Pings & liveness (`'P'`)

The app writes **`'P'` + seq + t0** (its own timestamp encoding, opaque to the
firmware) on CONTROL every 2 s per active transport; the firmware **echoes the
packet verbatim** as a CONTROL notification (bounded retry — up to 3 tries,
because the app declares the link wedged after 3 consecutive lost echoes and a
sustained notification burst can hit `BLE_HS_ENOMEM` repeatedly on a healthy
link). The app computes per-transport RTT from the echo
and declares a transport dead after 3 consecutive misses. Firmware-side, BLE
liveness rides the 5 s supervision timeout; the WiFi socket has its own
watchdog (no `'P'` for 10 s → socket closed, see `docs/WIFI_LINK.md`).

## WiFi link bootstrap (`'F'` / `'f'` / `'N'`)

BLE is the control plane for the optional WiFi bulk transport
(`docs/WIFI_LINK.md`). The phone writes **`'F'`** on CONTROL; the firmware's
main loop brings up its SoftAP + TCP server and answers with an **`'N'`**
notification carrying `ssid\npass\nip\nport` (newline-separated ASCII). BLE
**stays connected and advertising-capable the whole time** — it keeps carrying
control markers and realtime voice audio; only photos may route over the
socket, and only when it is measurably faster (the route is visible to the app
as which transport the `'H'` header arrives on). **`'f'`** (either transport)
tears the AP down. Un-updated apps never send `'F'`, so nothing changes for
them.

## Link stats packet (`'T'`, glasses → phone)

Every 5 s (`LINK_STATS_PERIOD_MS`) the firmware notifies a compact binary
stats packet on CONTROL alongside its serial `[LINK-STATS]` line, so the app
can display firmware-side truth. When a WiFi client is attached the same
packet is also framed onto the WiFi CONTROL channel, so a WiFi-only session
(BLE down, socket dialed directly) still gets live stats. All multi-byte
fields **little-endian**;
layout version 1 (byte 1 bumps on any change). Total size: **74 bytes**.

| Offset | Size | Type | Field |
|---|---|---|---|
| 0 | 1 | u8 | `'T'` (0x54) |
| 1 | 1 | u8 | layout version = 1 |
| 2 | 4 | u32 | uptime, ms since boot |
| 6 | 4 | u32 | free heap, bytes |
| 10 | 4 | u32 | free PSRAM, bytes |
| 14 | 2 | u16 | negotiated ATT MTU (23 until the exchange) |
| 16 | 1 | u8 | BLE TX PHY: 0 unknown, 1 = 1M, 2 = 2M, 3 = coded |
| 17 | 1 | u8 | BLE RX PHY (same encoding) |
| 18 | 1 | u8 | flags: bit0 BLE connected, bit1 WiFi AP up, bit2 WiFi client attached, bit3 next image routes WiFi |
| 19 | 1 | i8 | WiFi client RSSI, dBm (0 = unavailable) |
| 20 | 2 | u16 | BLE connects since boot |
| 22 | 2 | u16 | WiFi client sockets accepted since boot |
| 24 | 2 | u16 | app pings heard on BLE |
| 26 | 2 | u16 | app pings heard on WiFi |
| 28 | 4 | u32 | BLE TX bytes/s (rolling 5 s window) |
| 32 | 4 | u32 | BLE RX bytes/s |
| 36 | 4 | u32 | WiFi TX bytes/s |
| 40 | 4 | u32 | WiFi RX bytes/s |
| 44 | 4 | u32 | BLE TX bytes, cumulative |
| 48 | 4 | u32 | BLE RX bytes, cumulative |
| 52 | 4 | u32 | WiFi TX bytes, cumulative |
| 56 | 4 | u32 | WiFi RX bytes, cumulative |
| 60 | 1 | u8 | last photo route: 0 none yet, 1 = BLE, 2 = WiFi |
| 61 | 1 | u8 | reserved (0) |
| 62 | 4 | u32 | last photo size, bytes |
| 66 | 4 | u32 | last photo transfer duration, ms |
| 70 | 4 | u32 | WiFi socket uptime, ms (0 = no client attached) |

Byte counts are whole-frame air bytes (BLE: ATT payloads; WiFi: 3-byte frame
header + inner packet). The packet is built in `link.cpp`
(`sendStatsPacket`) — keep this table in lockstep with it.

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
| `'A'` | AUDIO_TX / AUDIO_RX | Audio fragment (PCM16, or µ-law in realtime mode) |
| `'H'` | IMAGE_TX | Image header (`flags`: 0x00 photo, 0x02 vision photo) + u32 LE size |
| `'I'` | IMAGE_TX | JPEG fragment |
| `'J'` | IMAGE_TX | Image end |
| `'X'` | CONTROL (glasses → phone) | Playback cancelled (barge-in) — stop streaming TTS |
| `'M'` / `'m'` | CONTROL (phone → glasses) | Realtime voice mode on/off (µ-law audio both ways) |
| `'P'` | CONTROL (phone → glasses, echoed back) | Ping — app measures per-transport RTT |
| `'F'` / `'f'` | CONTROL (phone → glasses) | WiFi link on/off — start/stop the SoftAP + TCP server |
| `'N'` | CONTROL (glasses → phone) | Answer to `'F'`: `"ssid\npass\nip\nport"` — join and connect |
| `'T'` | CONTROL (glasses → phone) | Link stats packet, every 5 s (layout above) |
| `'V'` / `'W'` | CONTROL *(removed)* | Old video session markers — apps tolerate, this firmware never sends |
| `'I'` | CONTROL *(legacy, app-accepted)* | Old image header from pre-in-band firmware |
| `'J'` | CONTROL *(legacy, app-accepted)* | Old still-image end from pre-in-band firmware |
