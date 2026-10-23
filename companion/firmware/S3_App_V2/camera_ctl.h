#pragma once
#include <stddef.h>
#include <stdint.h>
#include "esp_camera.h"

// ════════════════════════════════════════════════════════════════
//  Camera control (OV2640/OV3660 on XIAO ESP32-S3 Sense).
//  Snapshots are copied into a module-owned PSRAM buffer so the
//  camera frame buffer can be returned immediately.
// ════════════════════════════════════════════════════════════════

bool cameraInit();                 // init driver + sensor defaults + warm-up
bool cameraAvailable();

// Snapshot (photo / vision requests)
bool cameraCaptureSnapshot();      // warm-up, capture w/ retries, copy to PSRAM
const uint8_t* cameraJpeg();       // nullptr if no snapshot held
size_t cameraJpegLen();
void   cameraDiscardSnapshot();    // free the held snapshot (safe if none)

// Route-matched quality profile: high-bandwidth (WiFi route) captures SVGA
// photos; low (BLE) keeps the QVGA size the ~34 KB/s budget can move in
// about a second. Called from linkTick() when the preferred route changes.
void cameraSetHighBandwidth(bool on);
