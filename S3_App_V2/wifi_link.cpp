#include <Arduino.h>
#include <WiFi.h>
#include <esp_mac.h>
#include <esp_netif.h>
#include <esp_wifi.h>
#include "config.h"
#include "wifi_link.h"
#include "link.h"
#include "ulaw.h"
#include "camera_ctl.h"
#include "playback.h"

// ────────────────────────────────────────────────────────────────
//  State
// ────────────────────────────────────────────────────────────────
static WiFiServer sServer(WIFI_TCP_PORT);
static WiFiClient sClient;
static SemaphoreHandle_t sMutex = nullptr;   // guards sClient handoff + writes
static TaskHandle_t sRxTask = nullptr;
static volatile bool sRunning  = false;      // SoftAP + server up
static volatile bool sClientUp = false;      // phone socket attached
static char sSsid[24] = {0};
static char sIp[16]   = {0};

// Liveness + stats
static volatile unsigned long sLastPingMs = 0;    // watchdog: app 'P' cadence
static volatile unsigned long sClientSinceMs = 0;
static volatile uint16_t sClientConnects = 0;

// Frame assembly buffer (main loop task only): [ch][len16] + inner packet
static uint8_t sTxBuf[3 + WIFI_FRAME_MAX];

bool wifiLinkRunning()         { return sRunning; }
bool wifiLinkClientConnected() { return sClientUp; }
const char* wifiLinkSsid()     { return sSsid; }
const char* wifiLinkPass()     { return WIFI_AP_PASS; }
const char* wifiLinkIp()       { return sIp; }
uint16_t    wifiLinkPort()     { return WIFI_TCP_PORT; }
uint16_t    wifiLinkClientConnects() { return sClientConnects; }

uint32_t wifiLinkClientUptimeMs() {
  return sClientUp ? (uint32_t)(millis() - sClientSinceMs) : 0;
}

int8_t wifiLinkClientRssi() {
  if (!sClientUp) return 0;
  wifi_sta_list_t list;
  if (esp_wifi_ap_get_sta_list(&list) != ESP_OK || list.num < 1) return 0;
  return list.sta[0].rssi;
}

// ────────────────────────────────────────────────────────────────
//  Socket write path (main loop task; RX task sends only hello/echo)
// ────────────────────────────────────────────────────────────────
static void closeClientLocked() {
  if (sClientUp) LOGI("[WIFI] Client disconnected");
  sClientUp = false;
  sClient.stop();
}

// Write the whole buffer or kill the client. TCP backpressure (a stalled
// phone) shows up as 0-byte writes; give it WIFI_WRITE_TIMEOUT_MS to move.
static bool writeAllLocked(const uint8_t* data, size_t len) {
  unsigned long lastProgress = millis();
  size_t off = 0;
  while (off < len) {
    if (!sClientUp || !sClient.connected()) return false;
    size_t w = sClient.write(data + off, len - off);
    if (w > 0) {
      off += w;
      lastProgress = millis();
    } else {
      if (millis() - lastProgress > WIFI_WRITE_TIMEOUT_MS) {
        LOGI("[WIFI] Write stalled >%d ms — dropping client", WIFI_WRITE_TIMEOUT_MS);
        closeClientLocked();
        return false;
      }
      delay(2);
    }
  }
  return true;
}

// Frame + send one inner packet to client generation `gen` (sClientConnects
// at the time the logical transfer started). The RX task can drop a dead
// client and adopt a fresh one BETWEEN two packets of a multi-packet transfer
// — sClientUp stays true throughout, so without the generation check the
// remaining image fragments would be written "successfully" to the NEW socket
// (whose app side has no open transfer and silently discards them), the send
// would be reported as a success, and the photo lost with no BLE fallback.
// hdr/hdrLen is the packet header ('H'+flags, 'I'+seq, …), payload may be
// null. Single memcpy into the frame buffer keeps this one client.write
// call — one TCP segment for small packets (NoDelay).
static bool sendPacketTo(uint16_t gen, uint8_t channel, const uint8_t* hdr, size_t hdrLen,
                         const uint8_t* payload, size_t payloadLen) {
  size_t inner = hdrLen + payloadLen;
  if (inner > WIFI_FRAME_MAX) return false;
  if (!sClientUp) return false;
  if (xSemaphoreTake(sMutex, pdMS_TO_TICKS(WIFI_WRITE_TIMEOUT_MS)) != pdTRUE) return false;
  bool ok = false;
  if (sClientUp && sClientConnects == gen) {   // both checked under the mutex
    sTxBuf[0] = channel;
    sTxBuf[1] = (uint8_t)(inner & 0xFF);
    sTxBuf[2] = (uint8_t)(inner >> 8);
    memcpy(sTxBuf + 3, hdr, hdrLen);
    if (payload && payloadLen) memcpy(sTxBuf + 3 + hdrLen, payload, payloadLen);
    ok = writeAllLocked(sTxBuf, 3 + inner);
  }
  xSemaphoreGive(sMutex);
  if (ok) linkStatsAddTx(LINK_TP_WIFI, 3 + inner);
  return ok;
}

// Single-packet send to whichever client is attached right now.
static bool sendPacket(uint8_t channel, const uint8_t* hdr, size_t hdrLen,
                       const uint8_t* payload, size_t payloadLen) {
  return sendPacketTo(sClientConnects, channel, hdr, hdrLen, payload, payloadLen);
}

static void sendControl2(uint8_t a, uint8_t b) {
  uint8_t pkt[2] = {a, b};
  sendPacket(WIFI_CH_CONTROL, pkt, sizeof(pkt), nullptr, 0);
}

// 'T' stats packet on the CONTROL channel — same bytes as the BLE notify, so
// a WiFi-only session (BLE down, socket dialed directly) still gets live
// firmware stats instead of a frozen last-BLE-era snapshot.
bool wifiLinkSendStats(const uint8_t* pkt, size_t len) {
  return sendPacket(WIFI_CH_CONTROL, pkt, len, nullptr, 0);
}

// ────────────────────────────────────────────────────────────────
//  RX task (core 0): accept the phone, parse frames, dispatch.
//  Mirrors what the NimBLE task does for BLE writes — playback and
//  link-mode entry points are already cross-task safe.
// ────────────────────────────────────────────────────────────────
static bool readExact(uint8_t* dst, size_t len) {
  unsigned long lastProgress = millis();
  size_t off = 0;
  while (off < len) {
    if (!sRunning || !sClient.connected()) return false;
    int n = sClient.read(dst + off, len - off);
    if (n > 0) {
      off += (size_t)n;
      lastProgress = millis();
    } else {
      // Mid-frame silence = protocol desync or a dead peer. Bail; the
      // caller drops the client and the app reconnects cleanly.
      if (millis() - lastProgress > WIFI_READ_TIMEOUT_MS) return false;
      vTaskDelay(pdMS_TO_TICKS(2));
    }
  }
  return true;
}

static void dispatchFrame(uint8_t channel, const uint8_t* p, size_t len) {
  linkStatsAddRx(LINK_TP_WIFI, 3 + len);
  if (channel == WIFI_CH_CONTROL && len >= 1) {
    switch (p[0]) {
      case 'S': playbackOnStart();          break;
      case 'E': playbackOnEndMarker();      break;
      case 'M': linkSetRealtimeMode(true);  break;
      case 'm': linkSetRealtimeMode(false); break;
      case 'f': linkRequestWifi(false);     break;   // app asks to shut WiFi down
      case 'P':
        // Ping: echo verbatim — the app measures live RTT with these, and
        // the firmware watchdog counts them as proof of life.
        sLastPingMs = millis();
        linkOnPingHeard(LINK_TP_WIFI);
        sendPacket(WIFI_CH_CONTROL, p, len, nullptr, 0);
        break;
      case 'K': break;   // keepalive: the app trickles these to hold the
                         // iPhone's WiFi radio out of power-save. No reply.
      default:  break;
    }
  } else if (channel == WIFI_CH_AUDIO && len > 2 && p[0] == 'A') {
    // TTS audio normally rides BLE (transport policy); tolerate it here so an
    // app that streams over the socket anyway still plays.
    const uint8_t* d = p + 2;
    size_t n = len - 2;
    if (linkRealtimeMode()) {
      // µ-law → PCM16 into the playback ring (24 kHz both ways)
      static int16_t dec[WIFI_MAX_PAYLOAD];   // RX task only
      if (n > (size_t)WIFI_MAX_PAYLOAD) n = WIFI_MAX_PAYLOAD;
      for (size_t i = 0; i < n; i++) dec[i] = ulawDecode(d[i]);
      playbackOnAudioData((const uint8_t*)dec, n * 2, p[1]);
    } else {
      playbackOnAudioData(d, n, p[1]);
    }
  }
}

static void rxTask(void*) {
  static uint8_t payload[WIFI_FRAME_MAX];   // task-only, keep off the stack
  for (;;) {
    if (!sRunning) { vTaskDelay(pdMS_TO_TICKS(100)); continue; }

    // ── Accept (single client; a newcomer replaces a dead one only) ──
    WiFiClient fresh = sServer.accept();
    if (fresh) {
      if (sClientUp && sClient.connected()) {
        LOGI("[WIFI] Second client rejected");
        fresh.stop();
      } else {
        fresh.setNoDelay(true);
        xSemaphoreTake(sMutex, portMAX_DELAY);
        sClient = fresh;
        sClientUp = true;
        sClientSinceMs = millis();
        sLastPingMs = millis();   // grace period until the first app ping
        sClientConnects++;
        xSemaphoreGive(sMutex);
        LOGI("[WIFI] Phone connected from %s", sClient.remoteIP().toString().c_str());
        // Hello: 'R' + protocol version — the app treats this as link-up.
        sendControl2('R', WIFI_PROTO_VERSION);
      }
    }

    if (!sClientUp) { vTaskDelay(pdMS_TO_TICKS(20)); continue; }

    // ── Drain complete frames ──
    bool dead = false;
    while (!dead && sClientUp && sClient.available() >= 3) {
      uint8_t hdr[3];
      if (!readExact(hdr, 3)) { dead = true; break; }
      uint16_t len = (uint16_t)hdr[1] | ((uint16_t)hdr[2] << 8);
      if (len > WIFI_FRAME_MAX) {
        LOGI("[WIFI] Oversized frame (%u B) — protocol desync, dropping client", len);
        dead = true;
        break;
      }
      if (!readExact(payload, len)) { dead = true; break; }
      dispatchFrame(hdr[0], payload, len);
    }

    // ── Watchdog: the app pings every 2 s; a socket with no ping for
    //    WIFI_PING_TIMEOUT_MS is wedged (phone left the AP, backgrounded,
    //    …) even if TCP hasn't noticed. Close it; the app redials. ──
    if (!dead && sClientUp &&
        (millis() - sLastPingMs) > WIFI_PING_TIMEOUT_MS) {
      LOGI("[WIFI] No app ping for %d s — closing socket", WIFI_PING_TIMEOUT_MS / 1000);
      dead = true;
    }

    if (dead || !sClient.connected()) {
      xSemaphoreTake(sMutex, portMAX_DELAY);
      closeClientLocked();
      xSemaphoreGive(sMutex);
      // The phone vanished mid-TTS: recover exactly like a BLE drop, but only
      // if BLE isn't attached to keep the stream alive (it normally is).
      if (!linkConnected()) playbackResetOnDisconnect();
    }
    vTaskDelay(pdMS_TO_TICKS(2));
  }
}

// ────────────────────────────────────────────────────────────────
//  Lifecycle (main loop task via linkTick)
// ────────────────────────────────────────────────────────────────
bool wifiLinkStart() {
  if (sRunning) return true;
  if (!sMutex) sMutex = xSemaphoreCreateMutex();

  uint8_t mac[6];
  esp_read_mac(mac, ESP_MAC_WIFI_SOFTAP);
  snprintf(sSsid, sizeof(sSsid), WIFI_AP_SSID_PREFIX "%02X%02X", mac[4], mac[5]);

  WiFi.persistent(false);
  WiFi.mode(WIFI_AP);
  if (!WiFi.softAP(sSsid, WIFI_AP_PASS, WIFI_AP_CHANNEL, 0 /*visible*/, 1 /*max conn*/)) {
    LOGI("[WIFI] softAP start FAILED");
    WiFi.mode(WIFI_OFF);
    return false;
  }
  snprintf(sIp, sizeof(sIp), "%s", WiFi.softAPIP().toString().c_str());

  // Honest no-internet AP, by construction: stop the DHCP server from
  // offering ourselves as router/DNS (the ESP-IDF default hands out
  // 192.168.4.1 as the gateway). With no gateway/DNS in the lease the
  // iPhone keeps cellular as its default route instead of relying on
  // captive-probe heuristics to figure out the AP is internet-less.
  if (esp_netif_t* ap = esp_netif_get_handle_from_ifkey("WIFI_AP_DEF")) {
    esp_netif_dhcps_stop(ap);
    uint8_t noOffer = 0;   // clear the router + DNS offer flags
    esp_netif_dhcps_option(ap, ESP_NETIF_OP_SET, ESP_NETIF_ROUTER_SOLICITATION_ADDRESS,
                           &noOffer, sizeof(noOffer));
    esp_netif_dhcps_option(ap, ESP_NETIF_OP_SET, ESP_NETIF_DOMAIN_NAME_SERVER,
                           &noOffer, sizeof(noOffer));
    esp_netif_dhcps_start(ap);
    LOGI("[WIFI] DHCP router/DNS offers suppressed (honest no-internet AP)");
  }

  // DTIM 1: let the phone's WiFi power-save wake every beacon (~100 ms)
  // instead of every 2-3. iOS dozes hard on internet-less APs — with the
  // default DTIM that shows up as 200-600 ms latency spikes on the socket.
  // (Also load-bearing for BLE/WiFi coexistence: the SoftAP stays honest
  // about its beacon cadence while NimBLE time-slices the shared radio.)
  wifi_config_t apCfg;
  if (esp_wifi_get_config(WIFI_IF_AP, &apCfg) == ESP_OK) {
    apCfg.ap.dtim_period = 1;
    apCfg.ap.beacon_interval = 100;
    esp_wifi_set_config(WIFI_IF_AP, &apCfg);
  }

  sServer.begin();
  sServer.setNoDelay(true);
  if (!sRxTask) {
    // Core 0 with the WiFi stack; core 1 keeps audio/camera timing clean.
    xTaskCreatePinnedToCore(rxTask, "wifi_rx", 8192, nullptr, 2, &sRxTask, 0);
  }
  sRunning = true;
  LOGI("[WIFI] SoftAP '%s' up — %s:%u (pass '%s')", sSsid, sIp, WIFI_TCP_PORT, WIFI_AP_PASS);
  return true;
}

void wifiLinkStop() {
  if (!sRunning) return;
  sRunning = false;
  xSemaphoreTake(sMutex, portMAX_DELAY);
  closeClientLocked();
  xSemaphoreGive(sMutex);
  sServer.stop();
  WiFi.softAPdisconnect(true);
  WiFi.mode(WIFI_OFF);
  LOGI("[WIFI] SoftAP down");
}

// ────────────────────────────────────────────────────────────────
//  Photo sender — inner packets identical to ble_link's, but one
//  frame per logical packet and no pacing delays: TCP is ordered,
//  reliable, and flow-controlled end to end.
// ────────────────────────────────────────────────────────────────
static bool sendJpeg(uint16_t gen, const uint8_t* jpeg, size_t len, uint8_t flags) {
  uint8_t hdr[6];
  hdr[0] = 'H';
  hdr[1] = flags;
  hdr[2] = (len >>  0) & 0xFF;
  hdr[3] = (len >>  8) & 0xFF;
  hdr[4] = (len >> 16) & 0xFF;
  hdr[5] = (len >> 24) & 0xFF;
  if (!sendPacketTo(gen, WIFI_CH_IMAGE, hdr, sizeof(hdr), nullptr, 0)) return false;

  uint8_t seq = 0;
  size_t sent = 0;
  while (sent < len) {
    size_t frag = min((size_t)WIFI_MAX_PAYLOAD, len - sent);
    uint8_t fh[2] = {'I', seq++};
    if (!sendPacketTo(gen, WIFI_CH_IMAGE, fh, sizeof(fh), jpeg + sent, frag)) return false;
    sent += frag;
  }
  return true;
}

bool wifiSendCapturedImage(uint8_t flags) {
  const uint8_t* jpeg = cameraJpeg();
  size_t len = cameraJpegLen();
  if (!jpeg || len == 0 || !sClientUp) return false;
  // Pin the whole 'H' + 'I'… + 'J' sequence to the client attached NOW: if
  // the RX task swaps in a fresh socket mid-image (phone redial racing a RST),
  // the remaining packets fail instead of landing on the new client as
  // orphans — the caller then falls back to BLE with the snapshot intact.
  uint16_t gen = sClientConnects;
  if (!sendJpeg(gen, jpeg, len, flags)) return false;   // 0x00 photo, 0x02 vision
  uint8_t endPkt[2] = {'J', 0};
  if (!sendPacketTo(gen, WIFI_CH_IMAGE, endPkt, sizeof(endPkt), nullptr, 0)) return false;
  cameraDiscardSnapshot();
  return true;
}
