# Architecture

## Module map

```
S3_App_V2.ino          Application: setup order, gesture → action mapping, mic loop
│
├── config.h           Every pin, UUID, and tuning constant (no logic)
│
├── gestures.{h,cpp}   Multi-tap detector. Pure input → event; never touches
│                      BLE/camera/audio, so it's testable in isolation.
├── status_led.{h,cpp} Non-blocking LED patterns (connection/recording/playback state)
│
├── ble_link.{h,cpp}   NimBLE GATT server (PRIMARY transport, always on),
│                      packet framing, fragmentation, notifyWithRetry flow
│                      control, staggered post-connect LL tuning (bleTick)
├── wifi_link.{h,cpp}  SoftAP + TCP server: optional bulk plane for photos
│                      (app-enabled via 'F'; see docs/WIFI_LINK.md)
├── link.{h,cpp}       Transport policy: per-image route decision (measured
│                      speed, BLE default), shared realtime-mode flag,
│                      [LINK-STATS] + 'T' stats packet; ulaw.h codec
├── playback.{h,cpp}   TTS streaming state machine (BUFFERING→PLAYING→DRAINING)
├── ring_buffer.{h,cpp} Lock-free SPSC ring in PSRAM (radio task → main loop)
│
├── audio_io.{h,cpp}   I2S drivers: PDM mic RX (I2S_NUM_0), STD speaker TX (I2S_NUM_1)
└── camera_ctl.{h,cpp} Camera init, snapshot-to-PSRAM, route-matched quality profile
```

Dependency direction is strictly downward: the `.ino` orchestrates (audio and
control markers straight to `ble_link`, photos through the `link` route
decision), both `ble_link` and `wifi_link` forward inbound packets to
`playback`, `playback` consumes `ring_buffer` and `audio_io`. No module
reaches back up.

## Data flows

### Recording (button held)
```
PDM mic ──I2S DMA──▶ micRead() ──▶ micFilter()        [main loop]
                                      │  DC-block high-pass (~80 Hz corner)
                                      ▼
                       bleSendMicChunk(): 'A'+seq framing, 509-byte fragments,
                       notifyWithRetry + 5 ms pacing ──BLE──▶ Android ASR
```

### TTS playback (Android → speaker)
```
Android ──BLE WRITE──▶ AudioRxCallbacks::onWrite()     [NimBLE task]
                          │ playbackOnAudioData()
                          ▼
                     ringWrite()  ◀── 256 KB PSRAM ring, lock-free SPSC
                          │
                     ringRead()   ──▶ mono→stereo interleave ──▶ speakerWrite()
                     [main loop: playbackTick()]                  I2S DMA → MAX98357A
```

State machine: `IDLE → BUFFERING` (on `'S'`) `→ PLAYING` (≥ 70 KB buffered, or `'E'`
arrived) `→ DRAINING` (on `'E'`) `→ IDLE`. An abort flag (barge-in / disconnect) is
set from any task but always **serviced in the main loop**, so I2S teardown never
races a concurrent `i2s_channel_write`.

### Photos
Snapshots are copied to a module-owned PSRAM buffer so the camera frame buffer
returns to the driver immediately. `linkSendCapturedImage()` picks the route
per image (WiFi only when the socket is healthy AND measured faster than BLE;
falls back to BLE if the socket dies mid-send) and records the transfer
(route, bytes, duration, KB/s) for the `[IMG]` log and the `'T'` stats packet.
Both transports send the same in-band sequence (`'H'` header, `'I'`+seq
fragments, `'J'` end marker), so no marker can be reordered against the data
it frames. (The live-video feature was removed in Transport V2.)

## Concurrency model

Three contexts touch shared state:

| Context | Runs | Touches |
|---|---|---|
| Main loop (Arduino task, core 1) | gestures, mic, camera, I2S feed, all *sends* (BLE + WiFi), bleTick/linkTick | everything |
| NimBLE host task | characteristic callbacks | ring buffer (write side), playback flags, link stats/pings |
| `wifi_rx` task (core 0) | socket accept/read, frame dispatch, ping watchdog | same surface as the NimBLE task, plus the client mutex |

The WiFi RX task is a second producer *in the same role* as the NimBLE task —
by policy only BLE carries TTS audio (WiFi audio is merely tolerated), so the
SPSC ring-buffer contract holds in practice. The WiFi client handle itself is
guarded by a mutex because the main loop writes photos to the socket the RX
task may be tearing down. SoftAP start/stop is deferred to `linkTick()` in the
main loop — radio callbacks only queue a request. Post-connect BLE LL tuning
(conn params → 2M PHY → DLE) is likewise deferred to `bleTick()`, staggered at
+300/+600/+900 ms so the procedures never collide with the central's own MTU
exchange and discovery (a known early-drop trigger on iOS).

Safety comes from three rules:
1. The ring buffer is single-producer/single-consumer with `volatile` indices —
   aligned 32-bit loads/stores are atomic on the ESP32, so no locks are needed.
2. Playback control variables are `volatile` flags written by one side, read by the
   other; the only compound action (speaker teardown) happens exclusively in the
   main loop via the abort flag.
3. All *fragmenting* BLE notifications (mic audio, images) originate from the
   main loop, so one shared packet scratch buffer (`sPkt`) is safe and replaces
   V1's variable-length stack arrays. The only NimBLE-task notify is the `'P'`
   ping echo, which never touches `sPkt`. Every notify also passes its payload
   directly (`notify(value, len)`, which snapshots into the PDU at the call)
   instead of `setValue()` + no-arg `notify()` — the latter reads the
   characteristic's *current* value when the host task builds the PDU, so a
   NimBLE-task ping echo interleaving with a main-loop CONTROL send
   (`'S'`/`'E'`/`'X'`/`'N'`/`'T'`) could silently replace the packet.

## Why the I2S ports are split (and the mic still pauses)

The S3 has two independent I2S controllers: PDM mic RX runs on `I2S_NUM_0`,
speaker STD TX on `I2S_NUM_1`. They *could* run simultaneously, but the always-on
PDM clock (GPIO42) crosstalks into the speaker BCLK line on the current board
layout, so the mic is paused during playback and resumed at teardown. The speaker
channel is created on demand and deleted after each playback, with the DIN line
driven low — residual I2S idle clocking otherwise produces audible amp hiss.

## Setup ordering (load-bearing)

1. **Camera first.** It claims DMA channels and PSRAM frame buffers; if the ring
   buffer or mic initialize first, `esp_camera_fb_get()` can permanently return NULL.
2. Ring buffer (PSRAM).
3. Button (with stuck-at-boot check), then mic init + warm-up flush.
4. BLE last — nothing can arrive before the receive path exists.
