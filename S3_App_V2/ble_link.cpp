#include <Arduino.h>
#include <NimBLEDevice.h>
#include "config.h"
#include "ble_link.h"
#include "link.h"
#include "wifi_link.h"
#include "ulaw.h"
#include "camera_ctl.h"
#include "playback.h"

static NimBLEServer*         sServer = nullptr;
static NimBLECharacteristic* sAudioTx = nullptr;
static NimBLECharacteristic* sAudioRx = nullptr;
static NimBLECharacteristic* sControl = nullptr;
static NimBLECharacteristic* sImageTx = nullptr;
static volatile bool sConnected = false;
static volatile uint16_t sConnHandle = 0xFFFF;   // BLE_HS_CONN_HANDLE_NONE
static uint8_t sTxSeq = 0;

// Link facts surfaced in the 'T' stats packet (see link.cpp)
static volatile uint16_t sAttMtu = 23;           // pre-exchange ATT default
static volatile uint8_t  sPhyTx = 0, sPhyRx = 0; // 0 = not yet reported
static volatile uint16_t sConnectCount = 0;

// Post-connect LL tuning schedule (serviced by bleTick from the main loop):
// bit0 conn params, bit1 2M PHY, bit2 DLE. Set in onConnect, cleared as each
// procedure fires at its offset from sConnectedAtMs.
static volatile uint8_t sTunePending = 0;
static volatile unsigned long sConnectedAtMs = 0;

// Shared packet scratch buffer. All senders run on the main loop task, so a
// single static buffer is safe — and replaces the V1 variable-length stack
// arrays (VLAs), which risked stack overflow at MTU-sized fragments.
static uint8_t sPkt[BLE_MTU];

bool bleConnected()          { return sConnected; }
uint16_t bleAttMtu()         { return sAttMtu; }
uint8_t  blePhyTx()          { return sPhyTx; }
uint8_t  blePhyRx()          { return sPhyRx; }
uint16_t bleConnectCount()   { return sConnectCount; }

// Realtime voice mode (opt-in, 'M'/'m' on CONTROL): audio runs as G.711
// µ-law, 1 byte/sample both directions. Halves the BLE packet rate —
// bench-measured centrals drop notifications above ~40/s, which continuous
// PCM16 mic audio exceeds. Without 'M' everything behaves exactly as V2
// (PCM16 bursts) — old apps keep working. The flag itself lives in link.cpp
// (shared with the WiFi transport); the codec is ulaw.h.

// ────────────────────────────────────────────────────────────────
//  notify() with flow control.
//  NimBLE's notify() returns false when the host TX buffer is full
//  (BLE_HS_ENOMEM during a burst). Ignoring that silently drops the
//  fragment → missing bytes → JPEGs that won't decode and PCM that
//  shifts by a byte (full-scale garbage → ASR hallucinations).
//  Retry with backoff until the stack drains.
//
//  All sends use notify(value, len) — NOT setValue()+notify(). The
//  no-arg notify() reads the characteristic's CURRENT value when the
//  host task builds the PDU, so a 'P' ping echo (NimBLE task) landing
//  between a main-loop setValue and the PDU build silently replaced
//  the packet ('X'/'N'/'T' lost, echo delivered twice). notify(value,
//  len) snapshots the payload at the call, closing the race.
// ────────────────────────────────────────────────────────────────
static int notifyWithRetry(NimBLECharacteristic* ch, const uint8_t* data, size_t len) {
  if (ch->notify(data, len)) { linkStatsAddTx(LINK_TP_BLE, len); return 0; }
  int tries = 0;
  while (tries < BLE_NOTIFY_MAX_TRIES && sConnected) {
    delay(5);                   // let the BLE stack drain its TX buffers
    tries++;
    if (ch->notify(data, len)) { linkStatsAddTx(LINK_TP_BLE, len); return tries; }
  }
  return -1;  // gave up / disconnected
}

// ────────────────────────────────────────────────────────────────
//  Post-connect LL tuning — bleTick(), main loop
//  The parameter values are unchanged from V2; only the timing moved
//  out of onConnect (see ble_link.h).
// ────────────────────────────────────────────────────────────────
void bleTick() {
  if (!sConnected || !sTunePending || sConnHandle == 0xFFFF) return;
  unsigned long dt = millis() - sConnectedAtMs;

  if ((sTunePending & 0x01) && dt >= BLE_TUNE_CONN_PARAMS_MS) {
    sTunePending &= ~0x01;
    // Fast connection parameters for audio streaming:
    // min interval = 7.5 ms (6), max = 15 ms (12), latency = 0, timeout = 5 s (500)
    sServer->updateConnParams(sConnHandle, 6, 12, 0, 500);
    LOGI("[BLE] +%lums conn params requested (7.5-15 ms, timeout 5 s)", dt);
    return;   // one LL procedure per tick
  }
  if ((sTunePending & 0x02) && dt >= BLE_TUNE_PHY_MS) {
    sTunePending &= ~0x02;
    // Request 2M PHY (BLE 5): double the raw symbol rate of the default 1M —
    // more notification throughput and less airtime (= power) per packet.
    // Controllers negotiate; falls back to 1M on phones without 2M support.
    int rc = ble_gap_set_prefered_le_phy(sConnHandle,
                                         BLE_GAP_LE_PHY_1M_MASK | BLE_GAP_LE_PHY_2M_MASK,
                                         BLE_GAP_LE_PHY_1M_MASK | BLE_GAP_LE_PHY_2M_MASK,
                                         0 /* no coded-PHY preference */);
    if (rc != 0) LOGI("[BLE] 2M PHY request failed (rc=%d) — staying on 1M", rc);
    else         LOGI("[BLE] +%lums 2M PHY requested", dt);
    return;
  }
  if ((sTunePending & 0x04) && dt >= BLE_TUNE_DLE_MS) {
    sTunePending &= ~0x04;
    // LL Data Length Extension: without it every radio packet carries 27 B and
    // an MTU-sized notification fragments into ~19 packets across 2-3
    // connection events — measured on the bench as ~2/3 of continuous mic
    // audio dropped. With DLE one packet carries 251 B.
    int rc = ble_gap_set_data_len(sConnHandle, 251, 2120);
    if (rc != 0) LOGI("[BLE] data-length extension request failed (rc=%d)", rc);
    else         LOGI("[BLE] +%lums DLE requested (251/2120)", dt);
  }
}

// ────────────────────────────────────────────────────────────────
//  Server / characteristic callbacks
// ────────────────────────────────────────────────────────────────
class ServerCallbacks : public NimBLEServerCallbacks {
  void onConnect(NimBLEServer* server, NimBLEConnInfo& connInfo) override {
    sConnected = true;
    sConnHandle = connInfo.getConnHandle();
    sConnectCount++;
    sPhyTx = sPhyRx = 0;
    LOGI("[BLE] Central connected");
    // LL tuning (conn params / 2M PHY / DLE) is deliberately NOT issued here:
    // three back-to-back LL procedures while the central runs its own MTU
    // exchange + discovery is a known early-drop trigger. bleTick() staggers
    // them at +300/+600/+900 ms instead.
    sConnectedAtMs = millis();
    sTunePending = 0x07;
  }

  void onPhyUpdate(NimBLEConnInfo& connInfo, uint8_t txPhy, uint8_t rxPhy) override {
    // 1 = 1M, 2 = 2M, 3 = coded — surfaced to the app in the 'T' stats packet
    sPhyTx = txPhy;
    sPhyRx = rxPhy;
    LOGI("[BLE] PHY updated: tx=%u rx=%u (1=1M, 2=2M, 3=coded)", txPhy, rxPhy);
  }

  void onDisconnect(NimBLEServer* server, NimBLEConnInfo& connInfo, int reason) override {
    sConnected = false;
    sConnHandle = 0xFFFF;
    sTunePending = 0;
    // BLE is the realtime-audio plane, so a BLE drop always tears down an
    // in-flight TTS stream (WiFi carries photos only).
    playbackResetOnDisconnect();
    LOGI("[BLE] Disconnected (reason=%d) — advertising again", reason);
    NimBLEDevice::startAdvertising();
  }

  void onMTUChange(uint16_t mtu, NimBLEConnInfo& connInfo) override {
    sAttMtu = mtu;
    LOGI("[BLE] MTU changed to %u (payload: %u bytes)", mtu, mtu - 3);
  }
};

// Phone → ESP32 speaker data
class AudioRxCallbacks : public NimBLECharacteristicCallbacks {
  void onWrite(NimBLECharacteristic* ch, NimBLEConnInfo& connInfo) override {
    NimBLEAttValue val = ch->getValue();
    const uint8_t* data = val.data();
    size_t len = val.size();
    linkStatsAddRx(LINK_TP_BLE, len);
    if (len < BLE_HEADER_SIZE) return;
    if (data[0] != 'A') return;
    if (linkRealtimeMode()) {
      // µ-law → PCM16 as it enters the playback ring (rate is 24 kHz
      // either way — GPT Realtime's native output matches SPK_SAMPLE_RATE)
      static int16_t dec[BLE_MAX_PAYLOAD];      // NimBLE task only
      size_t n = len - BLE_HEADER_SIZE;
      if (n > (size_t)BLE_MAX_PAYLOAD) n = BLE_MAX_PAYLOAD;
      const uint8_t* p = data + BLE_HEADER_SIZE;
      for (size_t i = 0; i < n; i++) dec[i] = ulawDecode(p[i]);
      playbackOnAudioData((const uint8_t*)dec, n * 2, data[1]);
    } else {
      playbackOnAudioData(data + BLE_HEADER_SIZE, len - BLE_HEADER_SIZE, data[1]);
    }
  }
};

// Phone → ESP32 control commands
class ControlCallbacks : public NimBLECharacteristicCallbacks {
  void onWrite(NimBLECharacteristic* ch, NimBLEConnInfo& connInfo) override {
    NimBLEAttValue val = ch->getValue();
    if (val.size() < 1) return;
    linkStatsAddRx(LINK_TP_BLE, val.size());
    switch (val.data()[0]) {
      case 'S': playbackOnStart();     break;
      case 'E': playbackOnEndMarker(); break;
      case 'P':
        // Ping: echo verbatim on CONTROL — the app measures live RTT with
        // these. notify(value, len) snapshots the payload, so this NimBLE-task
        // send can't race the main loop's CONTROL notifies. A short bounded
        // retry rides out BLE_HS_ENOMEM during notification bursts: the app
        // force-reconnects a "wedged" link after 3 unanswered pings, so three
        // single-try failures during a long mic+photo burst would tear down a
        // perfectly healthy connection mid-utterance.
        linkOnPingHeard(LINK_TP_BLE);
        if (sControl && sConnected) {
          for (int tries = 0; tries < 3 && sConnected; tries++) {
            if (sControl->notify(val.data(), val.size())) {
              linkStatsAddTx(LINK_TP_BLE, val.size());
              break;
            }
            delay(5);   // brief — this runs on the NimBLE host task
          }
        }
        break;
      case 'M': linkSetRealtimeMode(true);  break;
      case 'm': linkSetRealtimeMode(false); break;
      // WiFi link bootstrap: SoftAP work can't run on the NimBLE task, so
      // these only queue a request; linkTick() (main loop) does the start
      // and answers with the 'N' credentials notify.
      case 'F': LOGI("[BLE] app requests WiFi link ON");  linkRequestWifi(true);  break;
      case 'f': LOGI("[BLE] app requests WiFi link OFF"); linkRequestWifi(false); break;
      default:  break;
    }
  }
};

// ────────────────────────────────────────────────────────────────
//  Init
// ────────────────────────────────────────────────────────────────
bool bleInit() {
  NimBLEDevice::init(BLE_DEVICE_NAME);
  NimBLEDevice::setMTU(BLE_MTU);
  NimBLEDevice::setPower(ESP_PWR_LVL_P9);
  // Advertise 1M+2M as our preferred PHYs for all future connections; the
  // per-connection request in bleTick() does the actual negotiation.
  ble_gap_set_prefered_default_le_phy(BLE_GAP_LE_PHY_1M_MASK | BLE_GAP_LE_PHY_2M_MASK,
                                      BLE_GAP_LE_PHY_1M_MASK | BLE_GAP_LE_PHY_2M_MASK);

  sServer = NimBLEDevice::createServer();
  sServer->setCallbacks(new ServerCallbacks());

  NimBLEService* service = sServer->createService(SERVICE_UUID);

  sAudioTx = service->createCharacteristic(CHAR_AUDIO_TX_UUID, NIMBLE_PROPERTY::NOTIFY);

  sAudioRx = service->createCharacteristic(
      CHAR_AUDIO_RX_UUID, NIMBLE_PROPERTY::WRITE | NIMBLE_PROPERTY::WRITE_NR);
  sAudioRx->setCallbacks(new AudioRxCallbacks());

  sControl = service->createCharacteristic(
      CHAR_CONTROL_UUID,
      NIMBLE_PROPERTY::WRITE | NIMBLE_PROPERTY::WRITE_NR | NIMBLE_PROPERTY::NOTIFY);
  sControl->setCallbacks(new ControlCallbacks());

  sImageTx = service->createCharacteristic(CHAR_IMAGE_TX_UUID, NIMBLE_PROPERTY::NOTIFY);

  service->start();

  NimBLEAdvertising* adv = NimBLEDevice::getAdvertising();
  adv->addServiceUUID(SERVICE_UUID);
  adv->start();

  LOGI("[BLE] GATT server started, advertising as '%s'", BLE_DEVICE_NAME);
  return true;
}

// ────────────────────────────────────────────────────────────────
//  Mic audio → phone
// ────────────────────────────────────────────────────────────────
void bleResetAudioSeq() { sTxSeq = 0; }

void bleSendAudioStart() {
  if (!sConnected || !sControl) return;
  uint8_t pkt[BLE_HEADER_SIZE] = {'S', 0};
  notifyWithRetry(sControl, pkt, sizeof(pkt));
  // Let the marker (CONTROL char) land before the first mic chunk (AUDIO_TX
  // char) — the two are separate notification streams and can reorder.
  delay(10);
}

void bleNotifyPlaybackCancelled() {
  if (!sConnected || !sControl) return;
  uint8_t pkt[BLE_HEADER_SIZE] = {'X', 0};
  notifyWithRetry(sControl, pkt, sizeof(pkt));
}

void bleNotifyWifiInfo() {
  if (!sConnected || !sControl) return;
  // 'N' + "ssid\npass\nip\nport" — everything the phone needs to join the
  // SoftAP and open the socket. Fits one MTU-512 notification with room to spare.
  char pkt[96];
  int n = snprintf(pkt, sizeof(pkt), "N%s\n%s\n%s\n%u",
                   wifiLinkSsid(), wifiLinkPass(), wifiLinkIp(), wifiLinkPort());
  if (n <= 0) return;
  notifyWithRetry(sControl, (const uint8_t*)pkt, (size_t)n);
  LOGI("[BLE] → 'N' WiFi credentials sent");
}

void bleNotifyStats(const uint8_t* pkt, size_t len) {
  if (!sConnected || !sControl) return;
  // Single try, no retry loop: stats must never stall the main loop, and a
  // lost sample is replaced 5 s later anyway. notify(value, len) snapshots
  // the payload so a concurrent ping echo can't swap it.
  if (sControl->notify(pkt, len)) linkStatsAddTx(LINK_TP_BLE, len);
}

void bleSendAudioEnd() {
  if (!sConnected || !sControl) return;
  uint8_t pkt[BLE_HEADER_SIZE] = {'E', 0};
  notifyWithRetry(sControl, pkt, sizeof(pkt));
}

void bleSendMicChunk(const uint8_t* pcm, size_t len) {
  if (!sConnected || !sAudioTx) return;

  // Realtime voice mode: µ-law halves both the bytes and the packet count
  // (a 1024 B PCM chunk becomes 512 B ≈ one notification instead of three).
  static uint8_t enc[BLE_MTU];
  const uint8_t* src = pcm;
  if (linkRealtimeMode()) {
    const int16_t* s = (const int16_t*)pcm;
    size_t samples = min(len / 2, sizeof(enc));
    for (size_t i = 0; i < samples; i++) enc[i] = ulawEncode(s[i]);
    src = enc;
    len = samples;
  }

  size_t offset = 0;
  while (offset < len) {
    size_t fragSize = min((size_t)BLE_MAX_PAYLOAD, len - offset);
    sPkt[0] = 'A';
    sPkt[1] = sTxSeq++;          // wraps at 255 by design
    memcpy(sPkt + BLE_HEADER_SIZE, src + offset, fragSize);
    // Retried, not fire-and-forget: a dropped mic notification both loses
    // audio and byte-shifts every later int16 sample — the exact signature
    // behind earlier Whisper hallucinations.
    notifyWithRetry(sAudioTx, sPkt, BLE_HEADER_SIZE + fragSize);
    offset += fragSize;
    delay(BLE_FRAG_DELAY_MS);    // one fragment per connection event
  }
}

// ────────────────────────────────────────────────────────────────
//  Snapshot (photo / vision) — in-band on IMAGE_TX
// ────────────────────────────────────────────────────────────────
static bool sendJpegFragments(const uint8_t* jpeg, size_t len) {
  uint8_t imgSeq = 0;
  size_t sent = 0;
  while (sent < len) {
    size_t fragSize = min((size_t)BLE_MAX_PAYLOAD, len - sent);
    sPkt[0] = 'I';
    sPkt[1] = imgSeq++;
    memcpy(sPkt + BLE_HEADER_SIZE, jpeg + sent, fragSize);
    if (notifyWithRetry(sImageTx, sPkt, BLE_HEADER_SIZE + fragSize) < 0) return false;
    sent += fragSize;
    delay(BLE_IMG_FRAG_DELAY_MS);  // one fragment per connection event
  }
  return true;
}

static bool sendImageHeader(size_t len, uint8_t flags) {
  // 'H' + flags + 4-byte LE total size — in-band on IMAGE_TX. BLE guarantees
  // delivery order only within a single characteristic, so riding the same
  // queue as the fragments makes it impossible for the header to arrive after
  // data (the old CONTROL-channel header needed a 40 ms guard delay and still
  // raced under load — the phone reset its reassembly buffer mid-image).
  uint8_t hdr[6];
  hdr[0] = 'H';
  hdr[1] = flags;                  // 0x00 = photo, 0x02 = vision photo
  hdr[2] = (len >>  0) & 0xFF;
  hdr[3] = (len >>  8) & 0xFF;
  hdr[4] = (len >> 16) & 0xFF;
  hdr[5] = (len >> 24) & 0xFF;
  if (notifyWithRetry(sImageTx, hdr, sizeof(hdr)) < 0) return false;
  // Pace the header into its OWN connection event, exactly like every
  // fragment below. Without this the header and the first fragment share one
  // event and the header is the one that gets dropped — the receiver then
  // orphans every fragment ("no open transfer").
  delay(BLE_IMG_FRAG_DELAY_MS);
  return true;
}

bool bleSendCapturedImage(uint8_t flags) {
  const uint8_t* jpeg = cameraJpeg();
  size_t len = cameraJpegLen();
  if (!jpeg || len == 0) return false;
  if (!sConnected || !sImageTx) return false;

  bool ok = sendImageHeader(len, flags) && sendJpegFragments(jpeg, len);

  // End marker rides IMAGE_TX too — strictly ordered behind the last data
  // fragment, so it can't close the phone's reassembly buffer early.
  if (ok) {
    uint8_t endPkt[BLE_HEADER_SIZE] = {'J', 0};
    ok = notifyWithRetry(sImageTx, endPkt, sizeof(endPkt)) >= 0;
  }

  cameraDiscardSnapshot();
  return ok;
}
