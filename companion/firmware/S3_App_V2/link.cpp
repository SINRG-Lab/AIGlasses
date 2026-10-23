#include <Arduino.h>
#include "config.h"
#include "link.h"
#include "ble_link.h"
#include "wifi_link.h"
#include "camera_ctl.h"

// Realtime voice mode — one flag for both transports. volatile: written from
// the NimBLE task or the WiFi RX task, read everywhere.
static volatile bool sUlaw = false;

// Pending WiFi request from a radio task: -1 none, 0 stop, 1 start.
static volatile int sWifiReq = -1;

void linkSetRealtimeMode(bool on) {
  if (sUlaw == on) return;
  sUlaw = on;
  LOGI("[LINK] realtime voice mode %s (u-law)", on ? "ON" : "OFF");
}
bool linkRealtimeMode() { return sUlaw; }

void linkRequestWifi(bool on) { sWifiReq = on ? 1 : 0; }

bool linkConnected()  { return bleConnected() || wifiLinkClientConnected(); }
bool linkWifiActive() { return wifiLinkClientConnected(); }

// ────────────────────────────────────────────────────────────────
//  Statistics — bytes, pings, throughput estimates, photo records
// ────────────────────────────────────────────────────────────────
static volatile uint32_t sTxTotal[2] = {0, 0};   // cumulative air bytes
static volatile uint32_t sRxTotal[2] = {0, 0};
static volatile uint16_t sPings[2]   = {0, 0};   // app pings heard
static volatile unsigned long sLastPingMs[2] = {0, 0};

// Rolling-window snapshots for the KB/s rates
static uint32_t sTxSnap[2] = {0, 0};
static uint32_t sRxSnap[2] = {0, 0};
static unsigned long sStatsLastMs = 0;

// Measured image throughput per transport (KB/s, EMA over real transfers).
// 0 = never measured. BLE falls back to LINK_BLE_SEED_KBS in comparisons.
static float sKBs[2] = {0.0f, 0.0f};
static unsigned long sMeasuredAtMs[2] = {0, 0};

// Last photo transfer, for the 'T' packet (0 none, 1 BLE, 2 WiFi)
static uint8_t  sLastPhotoRoute = 0;
static uint32_t sLastPhotoBytes = 0;
static uint32_t sLastPhotoMs    = 0;

void linkStatsAddTx(int transport, size_t bytes) { sTxTotal[transport & 1] += bytes; }
void linkStatsAddRx(int transport, size_t bytes) { sRxTotal[transport & 1] += bytes; }

void linkOnPingHeard(int transport) {
  sPings[transport & 1]++;
  sLastPingMs[transport & 1] = millis();
}

// ────────────────────────────────────────────────────────────────
//  Per-image route decision — BLE unless WiFi has proven itself
// ────────────────────────────────────────────────────────────────
static bool wifiHealthy() {
  if (!wifiLinkClientConnected()) return false;
  // The app pings every 2 s on each active transport; a socket that hasn't
  // pinged for LINK_PING_STALE_MS (3 missed pings) is not a data plane.
  unsigned long lp = sLastPingMs[LINK_TP_WIFI];
  return lp != 0 && (millis() - lp) <= LINK_PING_STALE_MS;
}

// STA client RSSI for the probe gate, cached: linkRouteWifi() runs every
// loop() pass (camera profile follows it), and esp_wifi_ap_get_sta_list is
// too heavy for that cadence. 1 s staleness is fine for a sanity gate.
static int8_t cachedClientRssi() {
  static int8_t rssi = 0;
  static unsigned long atMs = 0;
  unsigned long now = millis();
  if (atMs == 0 || now - atMs >= 1000) {
    atMs = now;
    rssi = wifiLinkClientRssi();
  }
  return rssi;
}

bool linkRouteWifi() {
  if (!wifiHealthy()) return false;   // when in doubt, BLE
  float wifi = sKBs[LINK_TP_WIFI];
  // Unmeasured (or stale) WiFi gets ONE probe photo to prove itself: TCP is
  // reliable end to end and the ping watchdog bounds a dead socket, so the
  // worst case is one slow photo — without a probe WiFi could never win.
  // (Deliberate deviation from the spec's "compare ping RTT": the app owns
  // RTT measurement and no message carries it back to the firmware.)
  // Sanity gate on the probe: a client at the edge of range can keep its
  // 2 s pings flowing while the socket is far slower than BLE — don't hand
  // it probe photos on signal that weak ("when in doubt, BLE"). 0 = RSSI
  // unavailable → allow the probe as before.
  if (wifi <= 0.0f || (millis() - sMeasuredAtMs[LINK_TP_WIFI]) > LINK_WIFI_REMEASURE_MS) {
    int8_t rssi = cachedClientRssi();
    return rssi == 0 || rssi >= LINK_WIFI_PROBE_MIN_RSSI;
  }
  float ble = (sKBs[LINK_TP_BLE] > 0.0f) ? sKBs[LINK_TP_BLE] : LINK_BLE_SEED_KBS;
  return wifi > ble;                  // WiFi must actually beat BLE
}

static void recordTransfer(int transport, size_t bytes, unsigned long ms) {
  if (ms == 0) ms = 1;
  float kbs = (float)bytes * 1000.0f / (float)ms / 1024.0f;
  int tp = transport & 1;
  sKBs[tp] = (sKBs[tp] > 0.0f) ? (0.5f * sKBs[tp] + 0.5f * kbs) : kbs;
  sMeasuredAtMs[tp] = millis();
  sLastPhotoRoute = (uint8_t)(tp + 1);
  sLastPhotoBytes = (uint32_t)bytes;
  sLastPhotoMs    = (uint32_t)ms;
  LOGI("[IMG] %u B in %lu ms (%.1f KB/s) via %s (est BLE %.1f / WIFI %.1f KB/s)",
       (unsigned)bytes, ms, kbs, tp == LINK_TP_WIFI ? "WIFI" : "BLE",
       sKBs[LINK_TP_BLE], sKBs[LINK_TP_WIFI]);
}

void linkSendCapturedImage(uint8_t flags) {
  size_t len = cameraJpegLen();          // grab before the send frees it
  if (len == 0) return;
  unsigned long t0 = millis();
  if (linkRouteWifi()) {
    if (wifiSendCapturedImage(flags)) {
      recordTransfer(LINK_TP_WIFI, len, millis() - t0);
      return;
    }
    // Socket died mid-send: the snapshot is still held — resend over BLE.
    LOGI("[IMG] WiFi send failed — falling back to BLE");
    t0 = millis();
  }
  if (bleSendCapturedImage(flags)) {
    recordTransfer(LINK_TP_BLE, len, millis() - t0);
  }
}

// ────────────────────────────────────────────────────────────────
//  'T' stats packet — firmware-side truth for the app's Developer
//  view, every LINK_STATS_PERIOD_MS on the BLE CONTROL char AND,
//  when a client is attached, the WiFi CONTROL channel (so a
//  WiFi-only session isn't left with frozen stats).
//  Exact byte layout documented in docs/BLE_PROTOCOL.md — keep the
//  two in lockstep (version bumps on any change).
// ────────────────────────────────────────────────────────────────
static void putU16(uint8_t* p, uint16_t v) { p[0] = v & 0xFF; p[1] = v >> 8; }
static void putU32(uint8_t* p, uint32_t v) {
  p[0] = v & 0xFF; p[1] = (v >> 8) & 0xFF; p[2] = (v >> 16) & 0xFF; p[3] = v >> 24;
}

static void sendStatsPacket(uint32_t bleTxBps, uint32_t bleRxBps,
                            uint32_t wifiTxBps, uint32_t wifiRxBps) {
  uint8_t pkt[74];
  pkt[0] = 'T';
  pkt[1] = 1;                                    // layout version
  putU32(pkt + 2,  (uint32_t)millis());          // uptime ms
  putU32(pkt + 6,  (uint32_t)ESP.getFreeHeap());
  putU32(pkt + 10, (uint32_t)ESP.getFreePsram());
  putU16(pkt + 14, bleAttMtu());
  pkt[16] = blePhyTx();
  pkt[17] = blePhyRx();
  pkt[18] = (bleConnected()             ? 0x01 : 0) |
            (wifiLinkRunning()          ? 0x02 : 0) |
            (wifiLinkClientConnected()  ? 0x04 : 0) |
            (linkRouteWifi()            ? 0x08 : 0);
  pkt[19] = (uint8_t)wifiLinkClientRssi();       // i8 dBm, 0 = n/a
  putU16(pkt + 20, bleConnectCount());
  putU16(pkt + 22, wifiLinkClientConnects());
  putU16(pkt + 24, sPings[LINK_TP_BLE]);
  putU16(pkt + 26, sPings[LINK_TP_WIFI]);
  putU32(pkt + 28, bleTxBps);
  putU32(pkt + 32, bleRxBps);
  putU32(pkt + 36, wifiTxBps);
  putU32(pkt + 40, wifiRxBps);
  putU32(pkt + 44, sTxTotal[LINK_TP_BLE]);
  putU32(pkt + 48, sRxTotal[LINK_TP_BLE]);
  putU32(pkt + 52, sTxTotal[LINK_TP_WIFI]);
  putU32(pkt + 56, sRxTotal[LINK_TP_WIFI]);
  pkt[60] = sLastPhotoRoute;
  pkt[61] = 0;                                   // reserved
  putU32(pkt + 62, sLastPhotoBytes);
  putU32(pkt + 66, sLastPhotoMs);
  putU32(pkt + 70, wifiLinkClientUptimeMs());
  bleNotifyStats(pkt, sizeof(pkt));
  // Same packet on the WiFi CONTROL channel: the app decodes 'T' on both
  // transports, and a WiFi-only session (BLE down, socket dialed directly)
  // would otherwise never see firmware stats.
  if (wifiLinkClientConnected()) wifiLinkSendStats(pkt, sizeof(pkt));
}

// ────────────────────────────────────────────────────────────────
//  Tick — main loop only
// ────────────────────────────────────────────────────────────────
void linkTick(bool micStreaming) {
  // Never run blocking work on the live mic path: a camera reprogram
  // (applyPhotoMode + resettle blocks on esp_camera_fb_get, up to a frame
  // period) or a SoftAP bring-up (hundreds of ms) overruns the ~90 ms PDM
  // DMA depth and silently drops mic samples mid-utterance — the exact
  // "lost audio" failure the pacing code elsewhere exists to prevent.
  // Everything here is deferrable; it runs on the next idle loop pass.
  if (micStreaming) return;

  // ── Camera profile follows the route the NEXT photo would take ──
  // (no-ops unless the preference actually flips; see cameraSetHighBandwidth)
  cameraSetHighBandwidth(linkRouteWifi());

  // ── 5 s stats: serial summary + 'T' packet to the app ──
  unsigned long now = millis();
  if (sStatsLastMs == 0) sStatsLastMs = now;
  if (now - sStatsLastMs >= LINK_STATS_PERIOD_MS) {
    float secs = (now - sStatsLastMs) / 1000.0f;
    sStatsLastMs = now;
    uint32_t bt = sTxTotal[LINK_TP_BLE],  br = sRxTotal[LINK_TP_BLE];
    uint32_t wt = sTxTotal[LINK_TP_WIFI], wr = sRxTotal[LINK_TP_WIFI];
    uint32_t bleTxBps  = (uint32_t)((bt - sTxSnap[LINK_TP_BLE])  / secs);
    uint32_t bleRxBps  = (uint32_t)((br - sRxSnap[LINK_TP_BLE])  / secs);
    uint32_t wifiTxBps = (uint32_t)((wt - sTxSnap[LINK_TP_WIFI]) / secs);
    uint32_t wifiRxBps = (uint32_t)((wr - sRxSnap[LINK_TP_WIFI]) / secs);
    sTxSnap[LINK_TP_BLE]  = bt;  sRxSnap[LINK_TP_BLE]  = br;
    sTxSnap[LINK_TP_WIFI] = wt;  sRxSnap[LINK_TP_WIFI] = wr;

    if (linkConnected() || bleTxBps || bleRxBps || wifiTxBps || wifiRxBps) {
      LOGI("[LINK-STATS] BLE %s mtu=%u phy=%u/%u tx=%.1f rx=%.1f KB/s tot=%lu/%lu KB pings=%u"
           " | WIFI %s rssi=%d tx=%.1f rx=%.1f KB/s tot=%lu/%lu KB pings=%u up=%lus"
           " | route=%s est=%.1f/%.1f KB/s | heap=%u KB psram=%u KB",
           bleConnected() ? "up" : "down", bleAttMtu(), blePhyTx(), blePhyRx(),
           bleTxBps / 1024.0f, bleRxBps / 1024.0f,
           (unsigned long)(bt / 1024), (unsigned long)(br / 1024), sPings[LINK_TP_BLE],
           wifiLinkClientConnected() ? "client" : (wifiLinkRunning() ? "ap" : "off"),
           wifiLinkClientRssi(),
           wifiTxBps / 1024.0f, wifiRxBps / 1024.0f,
           (unsigned long)(wt / 1024), (unsigned long)(wr / 1024), sPings[LINK_TP_WIFI],
           (unsigned long)(wifiLinkClientUptimeMs() / 1000),
           linkRouteWifi() ? "WIFI" : "BLE",
           sKBs[LINK_TP_BLE], sKBs[LINK_TP_WIFI],
           ESP.getFreeHeap() / 1024, ESP.getFreePsram() / 1024);
      sendStatsPacket(bleTxBps, bleRxBps, wifiTxBps, wifiRxBps);
    }
  }

  // ── Deferred WiFi start/stop (SoftAP work must not run on radio tasks) ──
  int req = sWifiReq;
  if (req < 0) return;
  sWifiReq = -1;
  if (req == 1) {
    if (wifiLinkStart()) {
      // Tell the phone how to join — over BLE, the transport that asked.
      bleNotifyWifiInfo();
    }
  } else {
    wifiLinkStop();
  }
}
