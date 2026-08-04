#include <Arduino.h>
#include "config.h"
#include "camera_ctl.h"

static bool     sCameraOk = false;
static uint8_t* sJpeg = nullptr;
static size_t   sJpegLen = 0;

// Route-matched quality profile (see cameraSetHighBandwidth)
static bool sHighBw = false;

bool cameraAvailable() { return sCameraOk; }

// Let AEC/AGC resettle after a sensor mode switch (first frames come out
// dark/garbled otherwise).
static void resettle(int frames) {
  for (int i = 0; i < frames; i++) {
    camera_fb_t* w = esp_camera_fb_get();
    if (w) esp_camera_fb_return(w);
  }
}

static void applyPhotoMode() {
  sensor_t* s = esp_camera_sensor_get();
  if (!s) return;
  s->set_framesize(s, sHighBw ? CAM_PHOTO_FRAMESIZE_WIFI : CAM_PHOTO_FRAMESIZE_BLE);
  s->set_quality(s,   sHighBw ? CAM_PHOTO_QUALITY_WIFI   : CAM_PHOTO_QUALITY_BLE);
}

bool cameraInit() {
  camera_config_t config = {};
  config.ledc_channel = LEDC_CHANNEL_0;
  config.ledc_timer   = LEDC_TIMER_0;
  config.pin_d0       = CAM_Y2;
  config.pin_d1       = CAM_Y3;
  config.pin_d2       = CAM_Y4;
  config.pin_d3       = CAM_Y5;
  config.pin_d4       = CAM_Y6;
  config.pin_d5       = CAM_Y7;
  config.pin_d6       = CAM_Y8;
  config.pin_d7       = CAM_Y9;
  config.pin_xclk     = CAM_XCLK;
  config.pin_pclk     = CAM_PCLK;
  config.pin_vsync    = CAM_VSYNC;
  config.pin_href     = CAM_HREF;
  config.pin_sscb_sda = CAM_SIOD;
  config.pin_sscb_scl = CAM_SIOC;
  config.pin_pwdn     = -1;
  config.pin_reset    = -1;
  config.xclk_freq_hz = 20000000;
  config.pixel_format = PIXFORMAT_JPEG;
  config.grab_mode    = CAMERA_GRAB_LATEST;
  // Init at the LARGEST profile size so the PSRAM frame buffers can hold it;
  // the sensor is immediately switched down to the active profile below.
  config.frame_size   = CAM_INIT_FRAMESIZE;
  config.jpeg_quality = CAM_PHOTO_QUALITY_BLE;
  config.fb_count     = 2;
  config.fb_location  = CAMERA_FB_IN_PSRAM;

  esp_err_t err = esp_camera_init(&config);
  if (err != ESP_OK) {
    LOGI("[CAM] Init FAILED: 0x%x", err);
    sCameraOk = false;
    return false;
  }

  sensor_t* s = esp_camera_sensor_get();
  if (s) {
    LOGI("[CAM] Sensor PID: 0x%04X", s->id.PID);
    s->set_brightness(s, 0);
    s->set_contrast(s, 0);
    s->set_saturation(s, 0);
    s->set_whitebal(s, 1);
    s->set_awb_gain(s, 1);
    s->set_exposure_ctrl(s, 1);
    s->set_gain_ctrl(s, 1);
    s->set_lenc(s, 1);          // lens shading correction — helps corners a lot
  }
  applyPhotoMode();             // drop from init size to the active profile

  // Warm up the sensor — the OV2640/OV3660 outputs garbage or NULL frames
  // until the AEC/AWB loops have converged (~300–500 ms after clock start).
  for (int i = 0; i < CAM_WARMUP_FRAMES_BOOT; i++) {
    camera_fb_t* warm = esp_camera_fb_get();
    if (warm) esp_camera_fb_return(warm);
    delay(100);
  }

  sCameraOk = true;
  return true;
}

bool cameraCaptureSnapshot() {
  if (!sCameraOk) return false;

  // After idling, auto-exposure/gain hasn't converged and the first frames
  // come back blank/dark. Discard a few so exposure settles before the real
  // capture — this is the fix for "image comes out blank ~half the time".
  for (int i = 0; i < CAM_WARMUP_FRAMES_CAPTURE; i++) {
    camera_fb_t* warm = esp_camera_fb_get();
    if (!warm) break;            // sensor not producing — let the retry loop handle it
    esp_camera_fb_return(warm);
    delay(20);                   // ~one frame interval for AEC/AGC to step
  }

  camera_fb_t* fb = nullptr;
  for (int attempt = 1; attempt <= CAM_CAPTURE_RETRIES; attempt++) {
    fb = esp_camera_fb_get();
    if (fb) break;
    LOGV("[CAM] Capture attempt %d failed, retrying...", attempt);
    delay(150);
  }
  if (!fb) {
    LOGI("[CAM] Capture FAILED after %d attempts", CAM_CAPTURE_RETRIES);
    return false;
  }

  // Copy into our own PSRAM buffer so the frame buffer can go back to the driver
  cameraDiscardSnapshot();
  sJpeg = (uint8_t*)ps_malloc(fb->len);
  if (!sJpeg) {
    LOGI("[CAM] PSRAM alloc failed for JPEG!");
    esp_camera_fb_return(fb);
    return false;
  }
  memcpy(sJpeg, fb->buf, fb->len);
  sJpegLen = fb->len;
  esp_camera_fb_return(fb);
  return true;
}

const uint8_t* cameraJpeg()    { return sJpeg; }
size_t         cameraJpegLen() { return sJpegLen; }

void cameraDiscardSnapshot() {
  if (sJpeg) {
    free(sJpeg);
    sJpeg = nullptr;
    sJpegLen = 0;
  }
}

void cameraSetHighBandwidth(bool on) {
  if (sHighBw == on) return;
  sHighBw = on;
  if (!sCameraOk) return;
  LOGI("[CAM] photo profile → %s", on ? "HIGH (WiFi, SVGA)" : "LOW (BLE, QVGA)");
  applyPhotoMode();
  resettle(1);
}
