#pragma once
#include <stddef.h>
#include <stdint.h>
#include "esp_camera.h"

// ════════════════════════════════════════════════════════════════
//  Camera control (OV2640/OV3660 on XIAO ESP32-S3 Sense).
//  Snapshots are copied into a module-owned PSRAM buffer so the
//  camera frame buffer can be returned immediately; video frames
//  are grabbed/returned directly for streaming.
// ════════════════════════════════════════════════════════════════

bool cameraInit();                 // init driver + sensor defaults + warm-up
bool cameraAvailable();

// Snapshot (photo / vision requests)
bool cameraCaptureSnapshot();      // warm-up, capture w/ retries, copy to PSRAM
const uint8_t* cameraJpeg();       // nullptr if no snapshot held
size_t cameraJpegLen();
void   cameraDiscardSnapshot();    // free the held snapshot (safe if none)

// Video streaming — caller must return every grabbed frame
camera_fb_t* cameraGrabFrame(int retries);
void         cameraReturnFrame(camera_fb_t* fb);
