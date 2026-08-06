#pragma once
#include <stddef.h>
#include <stdint.h>

// ════════════════════════════════════════════════════════════════
//  WiFi link to the phone app — the optional high-bandwidth BULK
//  data plane next to the always-on BLE link. The glasses run a
//  SoftAP ("AIGlasses-XXXX") and a TCP server; the phone joins the
//  AP and opens one socket.
//
//  Framing (see docs/WIFI_LINK.md): every message is
//      [channel u8][len u16 LE][inner packet]
//  where the inner packet is byte-identical to the corresponding
//  BLE characteristic payload:
//      channel 1 AUDIO   : 'A' + seq + audio        (tolerated inbound)
//      channel 2 CONTROL : 'S' 'E' 'M' 'm' 'f' 'P' 'K' 'R' 'T'   (both)
//      channel 3 IMAGE   : 'H' hdr, 'I' fragments, 'J' end (to phone)
//  One ordered TCP stream carries all three channels, so every
//  cross-characteristic race the BLE path has to guard against
//  (header vs fragment) is impossible here.
//
//  Policy (Transport V2): BLE stays connected the whole time —
//  control markers and realtime voice audio always ride BLE. The
//  socket carries photos only, and only when link.cpp measures it
//  faster than BLE (see linkRouteWifi).
//
//  Lifecycle: OFF by default (SoftAP costs ~100 mA). The app sends
//  'F' over BLE CONTROL → main loop calls wifiLinkStart() → the
//  glasses answer with 'N' + credentials over BLE → the phone joins
//  and connects. 'f' (either transport) tears it back down. A
//  client that goes silent (no 'P' ping for WIFI_PING_TIMEOUT_MS)
//  is dropped by the firmware-side watchdog.
// ════════════════════════════════════════════════════════════════

// Frame channels
#define WIFI_CH_AUDIO   1
#define WIFI_CH_CONTROL 2
#define WIFI_CH_IMAGE   3

// SoftAP + TCP server + RX task. Safe to call repeatedly.
// Call from the main loop only (via linkTick), never from a radio task.
bool wifiLinkStart();
void wifiLinkStop();
bool wifiLinkRunning();            // SoftAP + server up
bool wifiLinkClientConnected();    // phone socket attached

// Credentials for the 'N' notify (valid after wifiLinkStart)
const char* wifiLinkSsid();
const char* wifiLinkPass();
const char* wifiLinkIp();          // dotted quad of the SoftAP interface
uint16_t    wifiLinkPort();

// Stats for the 'T' packet / [LINK-STATS] (see link.cpp)
uint16_t wifiLinkClientConnects(); // sockets accepted since boot
uint32_t wifiLinkClientUptimeMs(); // 0 when no client attached
int8_t   wifiLinkClientRssi();     // STA client RSSI in dBm, 0 if unavailable

// 'T' stats packet on the CONTROL channel (same bytes as the BLE notify) so
// a WiFi-only session still gets firmware stats. Main loop task only.
bool wifiLinkSendStats(const uint8_t* pkt, size_t len);

// Photo sender — same inner packets as bleSendCapturedImage ('H'/'I'/'J' on
// the IMAGE channel). Returns false if the client vanished mid-send; the
// snapshot is only freed on success so the caller can retry over BLE.
// Runs on the main loop task.
bool wifiSendCapturedImage(uint8_t flags = 0x00);
