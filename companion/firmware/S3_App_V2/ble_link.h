#pragma once
#include <stddef.h>
#include <stdint.h>

// ════════════════════════════════════════════════════════════════
//  BLE GATT link to the phone app — the PRIMARY transport, always
//  connected. Owns the NimBLE server, the four characteristics,
//  fragmentation, and flow control (notifyWithRetry). Incoming
//  control/audio packets are forwarded to the playback module.
//
//  Protocol (see docs/BLE_PROTOCOL.md for the full spec):
//    AUDIO_TX :  'A' + seq + audio               (mic → phone)
//    AUDIO_RX :  'A' + seq + audio               (phone TTS → speaker)
//    CONTROL  :  'S' 'E' 'X' markers, 'P' ping echo, 'M'/'m' realtime
//                mode, 'F'/'f' WiFi on/off, 'N' WiFi creds, 'T' stats
//    IMAGE_TX :  'H' header, 'I' + seq JPEG fragments, 'J' end
// ════════════════════════════════════════════════════════════════

bool bleInit();
bool bleConnected();

// Post-connect LL tuning (conn params → 2M PHY → DLE), staggered at
// +300/+600/+900 ms from loop() instead of back-to-back inside onConnect —
// three simultaneous LL procedures during the central's own MTU/discovery
// is a known early-drop trigger on iOS. Call every loop iteration.
void bleTick();

// Mic streaming (recording)
void bleSendAudioStart();                              // 'S': phone flushes stale chunks
void bleSendAudioEnd();                                // 'E': utterance complete
void bleSendMicChunk(const uint8_t* pcm, size_t len);  // fragments + paces a chunk
void bleResetAudioSeq();                               // call at the start of each utterance

// Playback barge-in: tell the app to stop streaming TTS ('X' on CONTROL).
void bleNotifyPlaybackCancelled();

// WiFi bootstrap answer: 'N' + "ssid\npass\nip\nport" on CONTROL, sent after
// linkTick() brings the SoftAP up in response to the app's 'F'.
void bleNotifyWifiInfo();

// Link-stats packet ('T', built by link.cpp): best-effort single notify on
// CONTROL — a lost stats packet is just a lost sample, no retry loop.
void bleNotifyStats(const uint8_t* pkt, size_t len);

// Snapshot — sends the snapshot held by camera_ctl; frees it on success.
// flags: 0x00 = standalone photo, 0x02 = vision photo (app injects it into the
// live GPT Realtime conversation as an image for the spoken question).
// Returns false if the link dropped mid-send.
bool bleSendCapturedImage(uint8_t flags = 0x00);

// Link facts for the 'T' stats packet / [LINK-STATS] line
uint16_t bleAttMtu();        // negotiated ATT MTU (23 until the exchange)
uint8_t  blePhyTx();         // 0 unknown, 1 = 1M, 2 = 2M, 3 = coded
uint8_t  blePhyRx();
uint16_t bleConnectCount();  // centrals accepted since boot
