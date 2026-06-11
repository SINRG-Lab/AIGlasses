#include <Arduino.h>
#include <driver/i2s_std.h>
#include <driver/i2s_pdm.h>
#include "config.h"
#include "audio_io.h"

static i2s_chan_handle_t sPdmRx = NULL;   // mic
static i2s_chan_handle_t sStdTx = NULL;   // speaker

// ────────────────────────────────────────────────────────────────
//  Microphone (PDM RX, I2S_NUM_0)
// ────────────────────────────────────────────────────────────────
bool micInit() {
  if (sPdmRx != NULL) return true;   // already installed

  i2s_chan_config_t chanCfg = I2S_CHANNEL_DEFAULT_CONFIG(MIC_I2S_PORT, I2S_ROLE_MASTER);
  esp_err_t e = i2s_new_channel(&chanCfg, NULL, &sPdmRx);
  if (e != ESP_OK) { LOGI("[MIC] new_channel failed: %d", (int)e); return false; }

  i2s_pdm_rx_config_t pdmCfg = {
    .clk_cfg  = I2S_PDM_RX_CLK_DEFAULT_CONFIG(MIC_SAMPLE_RATE),
    .slot_cfg = I2S_PDM_RX_SLOT_DEFAULT_CONFIG(I2S_DATA_BIT_WIDTH_16BIT, I2S_SLOT_MODE_MONO),
    .gpio_cfg = {
      .clk = (gpio_num_t)PDM_CLK,
      .din = (gpio_num_t)PDM_DATA,
      .invert_flags = { .clk_inv = false },
    },
  };

  e = i2s_channel_init_pdm_rx_mode(sPdmRx, &pdmCfg);
  if (e != ESP_OK) { LOGI("[MIC] init_pdm_rx failed: %d", (int)e); return false; }

  e = i2s_channel_enable(sPdmRx);
  if (e != ESP_OK) { LOGI("[MIC] channel_enable failed: %d", (int)e); return false; }

  // Flush a few DMA buffers — PDM mic outputs garbage on clock startup
  int16_t flushBuf[256];
  size_t dummy = 0;
  for (int i = 0; i < 5; i++) {
    i2s_channel_read(sPdmRx, flushBuf, sizeof(flushBuf), &dummy, 20 / portTICK_PERIOD_MS);
  }
  LOGI("[MIC] Built-in PDM mic enabled");
  return true;
}

void micWarmup() {
  if (sPdmRx == NULL) return;
  int16_t buf[SAMPLES_PER_CHUNK];
  size_t dummy = 0;
  for (int i = 0; i < 10; i++) {
    i2s_channel_read(sPdmRx, buf, sizeof(buf), &dummy, 50 / portTICK_PERIOD_MS);
  }
}

void micPause() {
  if (sPdmRx != NULL) i2s_channel_disable(sPdmRx);
}

void micResume() {
  if (sPdmRx != NULL) i2s_channel_enable(sPdmRx);
}

void micFlush() {
  if (sPdmRx == NULL) return;
  int16_t buf[256];
  size_t got = 0;
  // Zero timeout: take only what's already in the DMA queue, never block.
  // Bounded so a (theoretical) read that keeps pace with the mic can't loop.
  for (int i = 0; i < 8; i++) {
    if (i2s_channel_read(sPdmRx, buf, sizeof(buf), &got, 0) != ESP_OK || got == 0) break;
  }
}

size_t micRead(int16_t* buf, size_t maxBytes, uint32_t timeoutMs) {
  if (sPdmRx == NULL) return 0;
  size_t bytesRead = 0;
  esp_err_t e = i2s_channel_read(sPdmRx, buf, maxBytes, &bytesRead,
                                 timeoutMs / portTICK_PERIOD_MS);
  return (e == ESP_OK) ? bytesRead : 0;
}

void micFilter(int16_t* buf, size_t samples) {
  // DC-blocking high-pass: y[n] = x[n] - x[n-1] + R*y[n-1]
  // Filter state persists across chunks so there's no per-chunk transient.
  static float prevX = 0.0f;
  static float prevY = 0.0f;
  for (size_t i = 0; i < samples; i++) {
    float x = (float)buf[i];
    float y = x - prevX + MIC_DC_FILTER_R * prevY;
    prevX = x;
    prevY = y;
    int32_t s = (int32_t)(y * MIC_GAIN);
    if (s >  32767) s =  32767;
    if (s < -32768) s = -32768;
    buf[i] = (int16_t)s;
  }
}

// ────────────────────────────────────────────────────────────────
//  Speaker (STD TX, I2S_NUM_1)
// ────────────────────────────────────────────────────────────────
bool speakerInit() {
  if (sStdTx != NULL) {       // stale channel from an aborted run
    i2s_channel_disable(sStdTx);
    i2s_del_channel(sStdTx);
    sStdTx = NULL;
    delay(20);
  }

  // Pause the PDM mic so GPIO42 (PDM_CLK) stops toggling — eliminates
  // crosstalk between the always-on mic clock and the speaker BCLK line.
  micPause();

  i2s_chan_config_t chanCfg = I2S_CHANNEL_DEFAULT_CONFIG(SPK_I2S_PORT, I2S_ROLE_MASTER);
  chanCfg.dma_desc_num  = 8;
  chanCfg.dma_frame_num = 512;   // 8 x 512 frames ≈ 186 ms of TX headroom @ 24 kHz
  esp_err_t e = i2s_new_channel(&chanCfg, &sStdTx, NULL);
  if (e != ESP_OK) { LOGI("[SPK] new_channel failed: %d", (int)e); sStdTx = NULL; return false; }

  i2s_std_config_t stdCfg = {
    .clk_cfg  = I2S_STD_CLK_DEFAULT_CONFIG(SPK_SAMPLE_RATE),
    .slot_cfg = I2S_STD_PHILIPS_SLOT_DEFAULT_CONFIG(I2S_DATA_BIT_WIDTH_16BIT, I2S_SLOT_MODE_STEREO),
    .gpio_cfg = {
      .mclk = I2S_GPIO_UNUSED,
      .bclk = (gpio_num_t)I2S_BCLK,
      .ws   = (gpio_num_t)I2S_WS,
      .dout = (gpio_num_t)AMP_DIN,
      .din  = I2S_GPIO_UNUSED,
      .invert_flags = { .mclk_inv = false, .bclk_inv = false, .ws_inv = false },
    },
  };

  e = i2s_channel_init_std_mode(sStdTx, &stdCfg);
  if (e != ESP_OK) {
    LOGI("[SPK] init_std failed: %d", (int)e);
    i2s_del_channel(sStdTx);
    sStdTx = NULL;
    return false;
  }

  e = i2s_channel_enable(sStdTx);
  if (e != ESP_OK) {
    LOGI("[SPK] enable failed: %d", (int)e);
    i2s_del_channel(sStdTx);
    sStdTx = NULL;
    return false;
  }

  // Preload DMA with silence so the first frames out are clean,
  // not whatever uninitialized memory the driver allocated.
  int16_t silence[256] = {0};
  size_t wrote = 0;
  for (int i = 0; i < 8; i++) {
    i2s_channel_write(sStdTx, silence, sizeof(silence), &wrote, 100 / portTICK_PERIOD_MS);
  }

  LOGI("[SPK] Speaker enabled");
  return true;
}

bool speakerReady() { return sStdTx != NULL; }

bool speakerWrite(const int16_t* samples, size_t bytes) {
  if (sStdTx == NULL) return false;
  size_t written = 0;
  return i2s_channel_write(sStdTx, samples, bytes, &written, portMAX_DELAY) == ESP_OK;
}

void speakerDeinit() {
  if (sStdTx != NULL) {
    i2s_channel_disable(sStdTx);
    i2s_del_channel(sStdTx);
    sStdTx = NULL;
  }
  // Drive the data line low so the amp sees silence, not a floating pin.
  pinMode(AMP_DIN, OUTPUT);
  digitalWrite(AMP_DIN, LOW);
  // Mic clock can run again now that the speaker bus is quiet.
  micResume();
}
