# WiFi Link Specification

The optional **bulk data plane** next to the always-on BLE link: the glasses
run a **SoftAP + TCP server** (`wifi_link.cpp`), the phone joins the AP and
opens one socket. It exists for bandwidth — BLE tops out around 34 KB/s on
images; the WiFi socket moves hundreds of KB/s, which turns SVGA photo
delivery from seconds into a blink.

There is no cloud, no LAN, no pairing UI: the glasses *are* the network. The
SoftAP is deliberately honest about having **no internet**, enforced in
`wifiLinkStart()`: the DHCP server's router and DNS offers are suppressed
(`esp_netif_dhcps_option`), so clients get a lease with **no gateway and no
DNS** — the iPhone keeps using cellular for the cloud session while the
socket carries photos, by construction rather than by captive-probe
heuristics.

## Transport policy (V2 — BLE primary)

- **BLE stays connected at all times.** Control markers and realtime voice
  audio (µ-law both directions) always ride BLE. The firmware never drops the
  central or stops advertising because WiFi came up.
- **WiFi carries photos only, per-image, and only when it earns it.** Before
  each photo, `linkRouteWifi()` (link.cpp) picks the route:
  1. the socket must be attached **and healthy** — an app `'P'` ping heard in
     the last 6 s (3 missed 2 s pings = not a data plane);
  2. WiFi's **measured** image throughput (EMA over real transfers) must beat
     BLE's — when in doubt, BLE.
  An unmeasured (or > 60 s stale) WiFi link gets one probe photo to prove
  itself: TCP is reliable end to end and the ping watchdog bounds a dead
  socket, so the worst case is one slow photo. The probe is gated on the
  client's RSSI (`LINK_WIFI_PROBE_MIN_RSSI`, −80 dBm): an edge-of-range
  client can keep its pings flowing while the socket is far slower than BLE,
  and "when in doubt, BLE" applies. The app learns the route from
  which transport the `'H'` header arrives on.
- **Coexistence:** the S3's single 2.4 GHz radio time-slices between NimBLE
  and the SoftAP. The AP keeps `dtim_period=1` (see below), and by design mic
  audio (BLE) and image bulk (WiFi) don't stream simultaneously — photos go
  out between utterances.
- Photos still work over BLE alone whenever WiFi is off, unhealthy, or slower.

## Why not "real" Wi-Fi Direct

ESP-IDF has no Wi-Fi Direct (P2P GO negotiation) support, and iOS only speaks
Wi-Fi Direct to MFi accessories. SoftAP + a manual/assisted join is the
standard way an ESP32 and an iPhone form a direct link, and behaves identically
in practice: phone ↔ glasses with no infrastructure in between.

## Topology & credentials

| Parameter | Value | Where |
|---|---|---|
| SSID | `AIGlasses-XXXX` (last two SoftAP MAC bytes) | `WIFI_AP_SSID_PREFIX` |
| Password | `glasses-link` (WPA2) | `WIFI_AP_PASS` |
| Channel | 1 (lab RF scan 2026-07-20; rescan if bursty) | `WIFI_AP_CHANNEL` |
| Glasses address | `192.168.4.1` (SoftAP default) | — |
| TCP port | `5005` | `WIFI_TCP_PORT` |
| Protocol version | `1` (sent in the hello) | `WIFI_PROTO_VERSION` |

One client at a time; a second connection attempt is rejected while the first
socket is alive.

## Lifecycle

OFF by default — the SoftAP costs ~100 mA, real money on a glasses battery.

```
phone                      glasses (BLE CONTROL)         glasses (main loop)
  'F'  ────────────────────►  queue request ─────────────►  wifiLinkStart()
  ◄──────────────  'N' + "ssid\npass\nip\nport"  ◄────────  SoftAP + server up
  join AP (manual join in iOS Settings, or assisted)
  TCP connect 192.168.4.1:5005 ──────────────────────────►  accept
  ◄──────────────  CONTROL 'R' + version (hello)            socket ACTIVE
  'P' every 2 s on the socket ───────────────────────────►  echoed; feeds the
                                                            health gate + watchdog
```

- `'f'` (either transport) turns the AP off again.
- **Firmware watchdog:** no app `'P'` on the socket for `WIFI_PING_TIMEOUT_MS`
  (10 s) → the firmware closes the socket (the phone left the AP or
  backgrounded; TCP alone may never notice). The app's redial loop reopens it
  while its WiFi toggle is on. BLE is unaffected either way.
- `WIFI_AP_AT_BOOT 1` in `config.h` brings the AP up from boot (bench use).
  The app can also skip the `'N'` handshake entirely: join the AP manually in
  iOS Settings and dial `192.168.4.1:5005`.

## Framing

Every message on the socket, both directions:

```
[channel u8][len u16 LE][inner packet, `len` bytes]
```

`len` ≤ `WIFI_FRAME_MAX` (4096). An oversized length field means the stream
desynced — both ends respond by dropping the connection (there is no way to
resync a byte stream mid-flight; reconnecting takes milliseconds).

The **inner packet is byte-identical to the corresponding BLE characteristic
payload**, so the app reuses its BLE parsers nearly unchanged:

| Channel | # | BLE equivalent | Contents |
|---|---|---|---|
| AUDIO | 1 | AUDIO_RX | `'A'` + seq + audio — tolerated inbound; policy sends voice over BLE |
| CONTROL | 2 | CONTROL | `'S' 'E' 'M' 'm' 'f'` (tolerated inbound), `'R'` hello, `'P'` ping-echo, `'T'` stats packet (glasses → phone, every 5 s while attached), `'K'` keepalive (app → glasses, no reply — holds the iPhone's WiFi radio out of power-save) |
| IMAGE | 3 | IMAGE_TX | `'H'` header, `'I'` fragments, `'J'` end (glasses → phone photos) |

`'R'` is WiFi-only: `['R'][version u8]`, sent by the firmware the moment a
client attaches. The app must treat the socket as up only after `'R'` — a
socket that opens but never says hello is not our firmware.

Seq bytes are kept in the inner packets purely for format compatibility; over
TCP they never gap and are not checked.

## What TCP buys over BLE (for images)

- **One ordered stream.** The header-vs-fragment races the BLE image path
  guards against are structurally impossible — no guard delays, no pacing.
- **4 KB fragments, no pacing.** An SVGA photo is ~15 writes instead of ~120
  paced notifications.
- **Real flow control.** TCP backpressure replaces `notifyWithRetry`; a write
  stalled longer than `WIFI_WRITE_TIMEOUT_MS` (2 s) drops the client, and
  `link.cpp` falls the photo back to BLE (the snapshot is freed only on a
  fully successful socket send).

## Coexistence & the hardware

BLE and the SoftAP share the S3's single 2.4 GHz radio and the XIAO's u.FL
antenna; ESP-IDF coexistence time-slices them. Two mitigations keep this
livable: `dtim_period=1` + 100 ms beacons (iOS dozes hard on internet-less
APs — with the default DTIM that shows up as 200-600 ms latency spikes), and
the usage pattern itself (voice on BLE, photos on WiFi between utterances —
the two bulk flows never overlap). The `wifi_rx` FreeRTOS task is pinned to
**core 0** with the WiFi stack; audio, camera, and the main loop own core 1,
so mic reads and I2S playback keep their timing regardless of socket traffic.

Power: the AP+socket adds roughly 80–120 mA over BLE-only. That is why the
lifecycle is app-driven and off by default. Turn it on for photo-heavy
sessions or benchmarking; leave it off for all-day voice.

## Metrics

Every 5 s the firmware prints `[LINK-STATS]` (per-transport KB/s, cumulative
bytes, pings, RSSI, route, estimated throughputs, heap/PSRAM) and notifies the
binary `'T'` stats packet on BLE CONTROL — plus, while a client socket is
attached, the same packet on the WiFi CONTROL channel, so a WiFi-only session
still gets firmware stats. Exact layout in `docs/BLE_PROTOCOL.md`. Each photo
also logs an `[IMG]` line with route, bytes, duration, and effective KB/s.
