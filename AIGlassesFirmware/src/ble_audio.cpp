#include "ble_audio.h"
#include "config.h"

#include <algorithm>
#include <cctype>
#include <cstring>
#include <string>

#include "esp_err.h"
#include "esp_log.h"
#include "esp_nimble_hci.h"
#include "nvs_flash.h"

#include "host/ble_hs.h"
#include "host/ble_hs_mbuf.h"
#include "host/util/util.h"
#include "nimble/nimble_port.h"
#include "nimble/nimble_port_freertos.h"
#include "services/gap/ble_svc_gap.h"
#include "services/gatt/ble_svc_gatt.h"
#include "store/config/ble_store_config.h"
#include "os/os_mbuf.h"

namespace {
const char* kTag = "ble_audio";

BleAudio* g_instance = nullptr;
uint8_t g_addr_type = 0;
uint16_t g_chr_val_handle = 0;

const ble_uuid128_t kServiceUuid = BLE_UUID128_INIT(
    0x4b, 0x91, 0x31, 0xc3, 0xc9, 0xc5, 0xcc, 0x8f,
    0x9e, 0x45, 0xb5, 0x1f, 0x01, 0xc2, 0xaf, 0x4f
);

const ble_uuid128_t kCharUuid = BLE_UUID128_INIT(
    0xa8, 0x26, 0x1b, 0x36, 0x07, 0xea, 0xf5, 0xb7,
    0x88, 0x46, 0xe1, 0x36, 0x3e, 0x48, 0xb5, 0xbe
);

int gatt_svr_chr_access_cb(uint16_t conn_handle, uint16_t attr_handle,
                           struct ble_gatt_access_ctxt* ctxt, void* arg);

const struct ble_gatt_svc_def gatt_svr_svcs[] = {
    {
        .type = BLE_GATT_SVC_TYPE_PRIMARY,
        .uuid = &kServiceUuid.u,
        .characteristics = (struct ble_gatt_chr_def[]) {
            {
                .uuid = &kCharUuid.u,
                .access_cb = gatt_svr_chr_access_cb,
                .flags = BLE_GATT_CHR_F_READ |
                         BLE_GATT_CHR_F_WRITE |
                         BLE_GATT_CHR_F_NOTIFY,
                .val_handle = &g_chr_val_handle,
            },
            {0}
        },
    },
    {0}
};

void host_task(void* param) {
    nimble_port_run();
    nimble_port_freertos_deinit();
}

int gap_event_cb(struct ble_gap_event* event, void* arg) {
    if (!g_instance) {
        return 0;
    }

    switch (event->type) {
    case BLE_GAP_EVENT_CONNECT:
        if (event->connect.status == 0) {
            g_instance->deviceConnected = true;
            g_instance->connHandle = event->connect.conn_handle;
#if DEBUG_ENABLED
            ESP_LOGI(kTag, "BLE client connected");
#endif
        } else {
#if DEBUG_ENABLED
            ESP_LOGW(kTag, "BLE connect failed; restarting advertising");
#endif
            g_instance->startAdvertising();
        }
        return 0;

    case BLE_GAP_EVENT_DISCONNECT:
        g_instance->deviceConnected = false;
        g_instance->connHandle = BLE_HS_CONN_HANDLE_NONE;
        g_instance->notifyEnabled = false;
#if DEBUG_ENABLED
        ESP_LOGI(kTag, "BLE client disconnected");
#endif
        g_instance->startAdvertising();
        return 0;

    case BLE_GAP_EVENT_SUBSCRIBE:
        g_instance->notifyEnabled = event->subscribe.cur_notify;
#if DEBUG_ENABLED
        ESP_LOGI(kTag, "Notify %s",
                 g_instance->notifyEnabled ? "enabled" : "disabled");
#endif
        return 0;

    case BLE_GAP_EVENT_ADV_COMPLETE:
#if DEBUG_ENABLED
        ESP_LOGW(kTag, "Advertising complete; restarting");
#endif
        g_instance->startAdvertising();
        return 0;

    default:
        return 0;
    }
}

void on_reset(int reason) {
#if DEBUG_ENABLED
    ESP_LOGE(kTag, "Resetting state; reason=%d", reason);
#endif
}

void on_sync(void) {
    if (!g_instance) {
        return;
    }

    int rc = ble_hs_id_infer_auto(0, &g_addr_type);
    if (rc != 0) {
#if DEBUG_ENABLED
        ESP_LOGE(kTag, "Failed to infer address type; rc=%d", rc);
#endif
        return;
    }

    g_instance->startAdvertising();
}
} // namespace

BleAudio::BleAudio()
    : deviceConnected(false),
      connHandle(BLE_HS_CONN_HANDLE_NONE),
      notifyEnabled(false) {
}

BleAudio::~BleAudio() = default;

bool BleAudio::init() {
    if (g_instance && g_instance != this) {
        ESP_LOGE(kTag, "BleAudio already initialized");
        return false;
    }
    g_instance = this;

    esp_err_t ret = nvs_flash_init();
    if (ret == ESP_ERR_NVS_NO_FREE_PAGES || ret == ESP_ERR_NVS_NEW_VERSION_FOUND) {
        ESP_ERROR_CHECK(nvs_flash_erase());
        ret = nvs_flash_init();
    }
    if (ret != ESP_OK) {
        ESP_LOGE(kTag, "NVS init failed: %s", esp_err_to_name(ret));
        return false;
    }

    ret = esp_nimble_hci_and_controller_init();
    if (ret != ESP_OK) {
        ESP_LOGE(kTag, "NimBLE controller init failed: %s", esp_err_to_name(ret));
        return false;
    }

    nimble_port_init();
    ble_svc_gap_init();
    ble_svc_gatt_init();

    ble_svc_gap_device_name_set(BLE_DEVICE_NAME);

    int rc = ble_gatts_count_cfg(gatt_svr_svcs);
    if (rc != 0) {
        ESP_LOGE(kTag, "GATT count failed: %d", rc);
        return false;
    }

    rc = ble_gatts_add_svcs(gatt_svr_svcs);
    if (rc != 0) {
        ESP_LOGE(kTag, "GATT add services failed: %d", rc);
        return false;
    }

    ble_hs_cfg.reset_cb = on_reset;
    ble_hs_cfg.sync_cb = on_sync;
    ble_hs_cfg.store_status_cb = ble_store_util_status_rr;
    ble_store_config_init();

    nimble_port_freertos_init(host_task);

#if DEBUG_ENABLED
    ESP_LOGI(kTag, "BLE service initialized");
#endif
    return true;
}

void BleAudio::startAdvertising() {
    struct ble_hs_adv_fields fields;
    memset(&fields, 0, sizeof(fields));

    fields.flags = BLE_HS_ADV_F_DISC_GEN | BLE_HS_ADV_F_BREDR_UNSUP;
    fields.name = reinterpret_cast<const uint8_t*>(BLE_DEVICE_NAME);
    fields.name_len = strlen(BLE_DEVICE_NAME);
    fields.name_is_complete = 1;
    fields.uuids128 = const_cast<ble_uuid128_t*>(&kServiceUuid);
    fields.num_uuids128 = 1;
    fields.uuids128_is_complete = 1;

    int rc = ble_gap_adv_set_fields(&fields);
    if (rc != 0) {
#if DEBUG_ENABLED
        ESP_LOGE(kTag, "Advertising fields set failed: %d", rc);
#endif
        return;
    }

    struct ble_gap_adv_params adv_params;
    memset(&adv_params, 0, sizeof(adv_params));
    adv_params.conn_mode = BLE_GAP_CONN_MODE_UND;
    adv_params.disc_mode = BLE_GAP_DISC_MODE_GEN;

    rc = ble_gap_adv_start(g_addr_type, nullptr, BLE_HS_FOREVER,
                           &adv_params, gap_event_cb, nullptr);
    if (rc != 0) {
#if DEBUG_ENABLED
        ESP_LOGE(kTag, "Advertising start failed: %d", rc);
#endif
        return;
    }

#if DEBUG_ENABLED
    ESP_LOGI(kTag, "BLE advertising started");
#endif
}

bool BleAudio::notify(const uint8_t* data, size_t length) {
    if (!deviceConnected || !notifyEnabled || data == nullptr || length == 0) {
        return false;
    }

    struct os_mbuf* om = ble_hs_mbuf_from_flat(data, length);
    if (!om) {
        return false;
    }

    int rc = ble_gatts_notify_custom(connHandle, g_chr_val_handle, om);
    return rc == 0;
}

void BleAudio::setStreamingControlCallback(StreamingControlCallback callback) {
    streamingCallback = callback;
}

bool BleAudio::isConnected() const {
    return deviceConnected;
}

void BleAudio::handleCommand(const std::string& command) {
    std::string cmdUpper = command;
    std::transform(cmdUpper.begin(), cmdUpper.end(), cmdUpper.begin(),
                   [](unsigned char c) { return std::toupper(c); });
    cmdUpper.erase(std::remove_if(cmdUpper.begin(), cmdUpper.end(),
                                  [](unsigned char c) { return std::isspace(c); }),
                   cmdUpper.end());

    if (cmdUpper == "START") {
        if (streamingCallback) {
            streamingCallback(true);
        }
        notify(reinterpret_cast<const uint8_t*>("OK:START"), strlen("OK:START"));
#if DEBUG_ENABLED
        ESP_LOGI(kTag, "Streaming START requested");
#endif
    } else if (cmdUpper == "STOP") {
        if (streamingCallback) {
            streamingCallback(false);
        }
        notify(reinterpret_cast<const uint8_t*>("OK:STOP"), strlen("OK:STOP"));
#if DEBUG_ENABLED
        ESP_LOGI(kTag, "Streaming STOP requested");
#endif
    } else if (cmdUpper == "PING") {
        notify(reinterpret_cast<const uint8_t*>("PONG"), strlen("PONG"));
#if DEBUG_ENABLED
        ESP_LOGI(kTag, "PING received, sent PONG");
#endif
    } else if (cmdUpper == "INFO") {
        std::string info = getInfoString();
        notify(reinterpret_cast<const uint8_t*>(info.data()), info.size());
#if DEBUG_ENABLED
        ESP_LOGI(kTag, "INFO requested: %s", info.c_str());
#endif
    } else {
        std::string error = "ERROR:Unknown command: " + command;
        notify(reinterpret_cast<const uint8_t*>(error.data()), error.size());
#if DEBUG_ENABLED
        ESP_LOGW(kTag, "Unknown command: %s", command.c_str());
#endif
    }
}

std::string BleAudio::getInfoString() const {
    char buffer[256];
    snprintf(buffer, sizeof(buffer),
             "sample_rate=%d,bits=%d,channel=LEFT,buffer_len=%d,pins=WS:%d,SD:%d,SCK:%d",
             AUDIO_SAMPLE_RATE,
             AUDIO_BITS_PER_SAMPLE,
             AUDIO_BUFFER_LEN,
             I2S_WS_PIN,
             I2S_SD_PIN,
             I2S_SCK_PIN);
    return std::string(buffer);
}
namespace {
int gatt_svr_chr_access_cb(uint16_t conn_handle, uint16_t attr_handle,
                           struct ble_gatt_access_ctxt* ctxt, void* arg) {
    (void)conn_handle;
    (void)attr_handle;
    (void)arg;

    if (!g_instance) {
        return BLE_ATT_ERR_UNLIKELY;
    }

    switch (ctxt->op) {
    case BLE_GATT_ACCESS_OP_READ_CHR: {
        const char* msg = "OpenGlasses Audio Ready";
        int rc = os_mbuf_append(ctxt->om, msg, strlen(msg));
        return rc == 0 ? 0 : BLE_ATT_ERR_INSUFFICIENT_RES;
    }
    case BLE_GATT_ACCESS_OP_WRITE_CHR: {
        char value[128];
        size_t len = 0;
        int rc = ble_hs_mbuf_to_flat(ctxt->om, value, sizeof(value) - 1, &len);
        if (rc != 0) {
            return BLE_ATT_ERR_UNLIKELY;
        }
        value[len] = '\0';

#if DEBUG_ENABLED
        ESP_LOGI(kTag, "Received write: %s", value);
#endif
        g_instance->handleCommand(std::string(value, len));
        return 0;
    }
    default:
        return BLE_ATT_ERR_UNLIKELY;
    }
}
} // namespace
