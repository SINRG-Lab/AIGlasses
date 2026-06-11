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
    playbackOnAudioData(data + BLE_HEADER_SIZE, len - BLE_HEADER_SIZE, data[1]);
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
  size_t offset = 0;
  while (offset < len) {
    size_t fragSize = min((size_t)BLE_MAX_PAYLOAD, len - offset);
    sPkt[0] = 'A';
    sPkt[1] = sTxSeq++;          // wraps at 255 by design
    memcpy(sPkt + BLE_HEADER_SIZE, pcm + offset, fragSize);
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
    delay(BLE_IMG_FRAG_DELAY_MS);  // one fragment per connection event
  }
}

static void sendImageHeader(size_t len, uint8_t flags) {
  // 'I' + flags + 4-byte LE total size on the CONTROL characteristic
  uint8_t hdr[6];
  hdr[0] = 'I';
  hdr[1] = flags;                  // 0x00 = snapshot, 0x01 = video frame
  hdr[2] = (len >>  0) & 0xFF;
  hdr[3] = (len >>  8) & 0xFF;
  hdr[4] = (len >> 16) & 0xFF;
  hdr[5] = (len >> 24) & 0xFF;
  notifyWithRetry(sControl, hdr, sizeof(hdr));
}

// ────────────────────────────────────────────────────────────────
//  Snapshot (photo / vision)
// ────────────────────────────────────────────────────────────────
void bleSendCapturedImage() {
  const uint8_t* jpeg = cameraJpeg();
  size_t len = cameraJpegLen();
  if (!jpeg || len == 0) return;
  if (!sConnected || !sImageTx) return;

  sendImageHeader(len, 0x00);
  // Let the header (CONTROL char) land before the first fragment (IMAGE_TX
  // char). Too short a gap = Android resets its reassembly buffer after
  // fragments already arrived → corrupt image.
  delay(40);

  sendJpegFragments(jpeg, len);

  // Guard so the last IMAGE_TX fragments drain before the 'J' end marker on
  // the CONTROL characteristic — prevents the end marker racing ahead and
  // closing Android's reassembly buffer early.
  delay(30);
  uint8_t endPkt[2] = {'J', 0};
  notifyWithRetry(sControl, endPkt, sizeof(endPkt));

  cameraDiscardSnapshot();
}

// ────────────────────────────────────────────────────────────────
//  Video
// ────────────────────────────────────────────────────────────────
void bleSendVideoStart() {
  if (!sConnected || !sControl) return;
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

  sendImageHeader(fb->len, 0x01);   // 0x01 = video frame flag
  delay(10);

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
  uint8_t pkt[2] = {'W', totalFrames};
  notifyWithRetry(sControl, pkt, sizeof(pkt));
  delay(10);
}
