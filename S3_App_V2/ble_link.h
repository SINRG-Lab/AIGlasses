#pragma once
#include <stddef.h>
#include <stdint.h>

// ════════════════════════════════════════════════════════════════
//  BLE GATT link to the Android app.
//  Owns the NimBLE server, the four characteristics, fragmentation,
//  and flow control (notifyWithRetry). Incoming control/audio
//  packets are forwarded to the playback module.
//
//  Protocol (see docs/BLE_PROTOCOL.md for the full spec):
//    AUDIO_TX :  'A' + seq + 16-bit PCM         (mic → phone)
//    AUDIO_RX :  'A' + seq + 16-bit PCM         (phone TTS → speaker)
//    CONTROL  :  'S' start, 'E' end, 'I' image header, 'J' image end,
//                'V' video start, 'W' video end
//    IMAGE_TX :  'I' + seq + JPEG fragment, 'J' + frameIdx (video frame end)
// ════════════════════════════════════════════════════════════════

bool bleInit();
bool bleConnected();

// Mic streaming (recording)
void bleSendAudioStart();                              // 'S': phone flushes stale chunks
void bleSendAudioEnd();                                // 'E': utterance complete
void bleSendMicChunk(const uint8_t* pcm, size_t len);  // fragments + paces a PCM chunk
void bleResetAudioSeq();                               // call at the start of each utterance

// Playback barge-in: tell the app to stop streaming TTS ('X' on CONTROL).
// Old app versions ignore the tag — they just keep sending into the void,
// which is the pre-V2 behavior (harmless, the ESP32 discards it).
void bleNotifyPlaybackCancelled();

// Snapshot — sends the snapshot held by camera_ctl, then frees it.
// flags: 0x00 = standalone photo, 0x02 = vision photo (app injects it into the
// live GPT Realtime conversation as an image for the spoken question).
void bleSendCapturedImage(uint8_t flags = 0x00);

// True once the app has enabled realtime voice mode (wrote 'M'). Lets the
// gesture layer route a vision photo into the realtime conversation.
bool bleRealtimeMode();

// Video streaming
void bleSendVideoStart();                  // 'V'
void bleSendVideoFrame(uint8_t frameIdx);  // grab + stream one JPEG frame
void bleSendVideoEnd(uint8_t totalFrames); // 'W' + frame count
