#include <Arduino.h>
#include <NimBLEDevice.h>
#include "config.h"
#include "ble_link.h"
#include "camera_ctl.h"
#include "playback.h"

static NimBLEServer*         sServer = nullptr;
static NimBLECharacteristic* sAudioTx = nullptr;
static NimBLECharacteristic* sAudioRx = nullptr;
static NimBLECharacteristic* sControl = nullptr;
static NimBLECharacteristic* sImageTx = nullptr;
static volatile bool sConnected = false;
static uint8_t sTxSeq = 0;

// Shared packet scratch buffer. All senders run on the main loop task, so a
// single static buffer is safe — and replaces the V1 variable-length stack
// arrays (VLAs), which risked stack overflow at MTU-sized fragments.
static uint8_t sPkt[BLE_MTU];

bool bleConnected() { return sConnected; }
bool bleRealtimeMode();   // defined after sVoiceUlaw below

// ────────────────────────────────────────────────────────────────
//  Realtime voice mode (opt-in, 'M'/'m' on CONTROL): audio runs as
//  G.711 µ-law, 1 byte/sample both directions. Halves the BLE packet
//  rate — bench-measured centrals drop notifications above ~40/s,
//  which continuous PCM16 mic audio exceeds. Without 'M' everything
//  behaves exactly as V2 (PCM16 bursts) — old apps keep working.
// ────────────────────────────────────────────────────────────────
static volatile bool sVoiceUlaw = false;

bool bleRealtimeMode() { return sVoiceUlaw; }

static uint8_t ulawEncode(int16_t pcm) {
  const int16_t CLIP = 32635;
  uint8_t sign = (pcm >> 8) & 0x80;
  if (sign) pcm = -pcm;
  if (pcm > CLIP) pcm = CLIP;
  pcm += 0x84;
  uint8_t exp = 7;
  for (uint16_t mask = 0x4000; (pcm & mask) == 0 && exp > 0; mask >>= 1) exp--;
  return ~(sign | (exp << 4) | ((pcm >> (exp + 3)) & 0x0F));
}

static int16_t ulawDecode(uint8_t u) {
  u = ~u;
  int16_t t = (((int16_t)(u & 0x0F)) << 3) + 0x84;
  t <<= (u & 0x70) >> 4;
  return (u & 0x80) ? (0x84 - t) : (t - 0x84);
}

// ────────────────────────────────────────────────────────────────
//  notify() with flow control.
//  NimBLE's notify() returns false when the host TX buffer is full
//  (BLE_HS_ENOMEM during a burst). Ignoring that silently drops the
//  fragment → missing bytes → JPEGs that won't decode and PCM that
//  shifts by a byte (full-scale garbage → ASR hallucinations).
//  Retry with backoff until the stack drains.
// ────────────────────────────────────────────────────────────────
static int notifyWithRetry(NimBLECharacteristic* ch, const uint8_t* data, size_t len) {
  ch->setValue(data, len);
  if (ch->notify()) return 0;
  int tries = 0;
  while (tries < BLE_NOTIFY_MAX_TRIES && sConnected) {
    delay(5);                   // let the BLE stack drain its TX buffers
    tries++;
    if (ch->notify()) return tries;
  }
  return -1;  // gave up / disconnected
}

// ────────────────────────────────────────────────────────────────
//  Server / characteristic callbacks
// ────────────────────────────────────────────────────────────────
class ServerCallbacks : public NimBLEServerCallbacks {
  void onConnect(NimBLEServer* server, NimBLEConnInfo& connInfo) override {
    sConnected = true;
    LOGI("[BLE] Android connected");
    // Fast connection parameters for audio streaming:
    // min interval = 7.5 ms (6), max = 15 ms (12), latency = 0, timeout = 5 s (500)
    server->updateConnParams(connInfo.getConnHandle(), 6, 12, 0, 500);
    // Request 2M PHY (BLE 5): double the raw symbol rate of the default 1M —
    // more notification throughput and less airtime (= power) per packet.
    // Controllers negotiate; falls back to 1M on phones without 2M support.
    // 2M's shorter range is irrelevant at glasses-to-pocket distance.
    int rc = ble_gap_set_prefered_le_phy(connInfo.getConnHandle(),
                                         BLE_GAP_LE_PHY_1M_MASK | BLE_GAP_LE_PHY_2M_MASK,
                                         BLE_GAP_LE_PHY_1M_MASK | BLE_GAP_LE_PHY_2M_MASK,
                                         0 /* no coded-PHY preference */);
    if (rc != 0) LOGI("[BLE] 2M PHY request failed (rc=%d) — staying on 1M", rc);
    // LL Data Length Extension: without it every radio packet carries 27 B and
    // an MTU-sized notification fragments into ~19 packets across 2-3
    // connection events — measured on the bench as ~2/3 of continuous mic
    // audio dropped. With DLE one packet carries 251 B.
    rc = ble_gap_set_data_len(connInfo.getConnHandle(), 251, 2120);
    if (rc != 0) LOGI("[BLE] data-length extension request failed (rc=%d)", rc);
  }

  void onPhyUpdate(NimBLEConnInfo& connInfo, uint8_t txPhy, uint8_t rxPhy) override {
    // 1 = 1M, 2 = 2M, 3 = coded
    LOGI("[BLE] PHY updated: tx=%u rx=%u (1=1M, 2=2M, 3=coded)", txPhy, rxPhy);
  }

  void onDisconnect(NimBLEServer* server, NimBLEConnInfo& connInfo, int reason) override {
    sConnected = false;
    playbackResetOnDisconnect();
    LOGI("[BLE] Disconnected (reason=%d) — advertising again", reason);
    NimBLEDevice::startAdvertising();
  }

  void onMTUChange(uint16_t mtu, NimBLEConnInfo& connInfo) override {
    LOGI("[BLE] MTU changed to %u (payload: %u bytes)", mtu, mtu - 3);
  }
};

// Android → ESP32 speaker data
class AudioRxCallbacks : public NimBLECharacteristicCallbacks {
  void onWrite(NimBLECharacteristic* ch, NimBLEConnInfo& connInfo) override {
    NimBLEAttValue val = ch->getValue();
    const uint8_t* data = val.data();
    size_t len = val.size();
    if (len < BLE_HEADER_SIZE) return;
    if (data[0] != 'A') return;
    if (sVoiceUlaw) {
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

// Android → ESP32 control commands
class ControlCallbacks : public NimBLECharacteristicCallbacks {
  void onWrite(NimBLECharacteristic* ch, NimBLEConnInfo& connInfo) override {
    NimBLEAttValue val = ch->getValue();
    if (val.size() < 1) return;
    switch (val.data()[0]) {
      case 'S': playbackOnStart();     break;
      case 'E': playbackOnEndMarker(); break;
      case 'M': sVoiceUlaw = true;  LOGI("[BLE] realtime voice mode ON (u-law)");  break;
      case 'm': sVoiceUlaw = false; LOGI("[BLE] realtime voice mode OFF"); break;
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
  // per-connection request in onConnect() does the actual negotiation.
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
//  Mic audio → Android
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
  if (sVoiceUlaw) {
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
//  JPEG fragmentation helper (snapshot + video share this)
// ────────────────────────────────────────────────────────────────
// Per-fragment pacing: BLE_IMG_FRAG_DELAY_MS for one-shot photos, tighter
// BLE_VID_FRAG_DELAY_MS during live video (set in bleSendVideoStart/End).
static int sImgFragDelay = BLE_IMG_FRAG_DELAY_MS;

static void sendJpegFragments(const uint8_t* jpeg, size_t len) {
  uint8_t imgSeq = 0;
  size_t sent = 0;
  while (sent < len) {
    size_t fragSize = min((size_t)BLE_MAX_PAYLOAD, len - sent);
    sPkt[0] = 'I';
    sPkt[1] = imgSeq++;
    memcpy(sPkt + BLE_HEADER_SIZE, jpeg + sent, fragSize);
    notifyWithRetry(sImageTx, sPkt, BLE_HEADER_SIZE + fragSize);
    sent += fragSize;
    delay(sImgFragDelay);  // one fragment per connection event
  }
}

static void sendImageHeader(size_t len, uint8_t flags) {
  // 'H' + flags + 4-byte LE total size — in-band on IMAGE_TX. BLE guarantees
  // delivery order only within a single characteristic, so riding the same
  // queue as the fragments makes it impossible for the header to arrive after
  // data (the old CONTROL-channel header needed a 40 ms guard delay and still
  // raced under load — Android reset its reassembly buffer mid-image).
  uint8_t hdr[6];
  hdr[0] = 'H';
  hdr[1] = flags;                  // 0x00 = snapshot, 0x01 = video frame
  hdr[2] = (len >>  0) & 0xFF;
  hdr[3] = (len >>  8) & 0xFF;
  hdr[4] = (len >> 16) & 0xFF;
  hdr[5] = (len >> 24) & 0xFF;
  notifyWithRetry(sImageTx, hdr, sizeof(hdr));
  // Pace the header into its OWN connection event, exactly like every
  // fragment below. Without this the header and the first fragment share one
  // event and the header is the one that gets dropped — the receiver then
  // orphans every fragment ("no open transfer").
  delay(sImgFragDelay);
}

// ────────────────────────────────────────────────────────────────
//  Snapshot (photo / vision)
// ────────────────────────────────────────────────────────────────
void bleSendCapturedImage(uint8_t flags) {
  const uint8_t* jpeg = cameraJpeg();
  size_t len = cameraJpegLen();
  if (!jpeg || len == 0) return;
  if (!sConnected || !sImageTx) return;

  sendImageHeader(len, flags);   // 0x00 photo, 0x02 vision photo
  sendJpegFragments(jpeg, len);

  // End marker rides IMAGE_TX too — strictly ordered behind the last data
  // fragment, so it can't close Android's reassembly buffer early. Same fix
  // that eliminated the ~25% video-frame corruption; no guard delays needed.
  uint8_t endPkt[BLE_HEADER_SIZE] = {'J', 0};
  notifyWithRetry(sImageTx, endPkt, sizeof(endPkt));

  cameraDiscardSnapshot();
}

// ────────────────────────────────────────────────────────────────
//  Video
// ────────────────────────────────────────────────────────────────
void bleSendVideoStart() {
  if (!sConnected || !sControl) return;
  sImgFragDelay = BLE_VID_FRAG_DELAY_MS;   // tighter pacing for the frame burst
  uint8_t pkt[2] = {'V', 0};
  notifyWithRetry(sControl, pkt, sizeof(pkt));
  delay(10);
}

void bleSendVideoFrame(uint8_t frameIdx) {
  if (!sConnected || !sImageTx) return;

  camera_fb_t* fb = cameraGrabFrame(CAM_VIDEO_FRAME_RETRIES);
  if (!fb) {
    LOGV("[VID] Frame capture failed");
    return;
  }

  sendImageHeader(fb->len, 0x01);   // 0x01 = video frame flag (in-band on IMAGE_TX)
  sendJpegFragments(fb->buf, fb->len);

  // Frame end: 'J' + frameIdx — sent on IMAGE_TX (same notification queue as
  // the frame data), NOT on CONTROL. BLE guarantees delivery order only
  // within a single characteristic, so 'J' here physically cannot arrive
  // before the last data fragment. This eliminates the cross-characteristic
  // race that corrupted ~25% of frames in earlier builds.
  uint8_t endPkt[BLE_HEADER_SIZE] = {'J', frameIdx};
  notifyWithRetry(sImageTx, endPkt, sizeof(endPkt));

  cameraReturnFrame(fb);
}

void bleSendVideoEnd(uint8_t totalFrames) {
  if (!sConnected || !sControl) return;
  // 'W' rides CONTROL while the last frame's data + 'J' ride IMAGE_TX — a
  // cross-characteristic pair, so 'W' could overtake the final frame. Give
  // the IMAGE_TX queue a moment to drain (the app additionally salvages a
  // complete in-flight frame when 'W' arrives early, belt-and-braces).
  delay(50);
  uint8_t pkt[2] = {'W', totalFrames};
  notifyWithRetry(sControl, pkt, sizeof(pkt));
  delay(10);
  sImgFragDelay = BLE_IMG_FRAG_DELAY_MS;   // restore photo pacing
}
