#pragma once
#include <stddef.h>
#include <stdint.h>

// ════════════════════════════════════════════════════════════════
//  Transport policy + statistics (Transport V2).
//
//  BLE is PRIMARY and always on: control markers and realtime voice
//  audio ride BLE at all times (the .ino calls ble_link directly for
//  those). WiFi (SoftAP + TCP, wifi_link.h) is an optional BULK data
//  plane the app enables over BLE ('F' → SoftAP → 'N' creds).
//
//  This module owns:
//    - the per-image route decision (linkRouteWifi): WiFi only when
//      the socket is up, recently pinged, AND measured faster than
//      BLE — when in doubt, BLE. Photos work over BLE alone.
//    - the shared realtime-voice flag ('M'/'m' on either transport)
//    - deferred WiFi start/stop (radio tasks only queue requests;
//      the SoftAP work happens in linkTick on the main loop)
//    - link statistics: [LINK-STATS] serial line + the 'T' stats
//      packet to the app, both every LINK_STATS_PERIOD_MS
// ════════════════════════════════════════════════════════════════

// Service pending WiFi start/stop, the 'N' credentials notify, camera
// profile switches, and the 5 s stats cadence. Call once per loop().
// Pass micStreaming = true while the mic is live (button held + recording):
// the camera reprogram (blocks up to a frame period) and the SoftAP bring-up
// (hundreds of ms) exceed the ~90 ms PDM DMA depth and would silently drop
// mic samples mid-utterance, so all tick work is deferred until release.
void linkTick(bool micStreaming = false);

bool linkConnected();     // phone reachable on either transport
bool linkWifiActive();    // WiFi socket attached
bool linkRouteWifi();     // true when the NEXT image should go over WiFi

// Realtime voice mode ('M'/'m' on CONTROL, either transport): audio runs
// as G.711 µ-law both directions. Single flag shared by both links.
void linkSetRealtimeMode(bool on);
bool linkRealtimeMode();

// WiFi lifecycle request ('F'/'f' on CONTROL, or WIFI_AP_AT_BOOT).
// Deferred to linkTick() — safe to call from any task.
void linkRequestWifi(bool on);

// ── Live link statistics ──
// Both radio paths report every byte that crosses the air here, plus each
// app ping they hear. Counters are volatile-add only (single writer per
// direction per transport in practice).
#define LINK_TP_BLE  0
#define LINK_TP_WIFI 1
void linkStatsAddTx(int transport, size_t bytes);
void linkStatsAddRx(int transport, size_t bytes);
void linkOnPingHeard(int transport);

// Photo send: picks the route (linkRouteWifi), falls back to BLE if the
// socket dies mid-send, records the transfer (route/bytes/duration/KB-s)
// for the [IMG] log, the throughput estimate, and the 'T' packet.
void linkSendCapturedImage(uint8_t flags = 0x00);
