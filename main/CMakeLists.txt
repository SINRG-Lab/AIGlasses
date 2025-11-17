idf_component_register(
    SRCS "walter_spiffs.cpp" "walter_com.cpp" "audio_agent.cpp" "walter_spiffs.h" "audio_agent.h" "walter_com.h" "main.cpp" 
    INCLUDE_DIRS "."
    REQUIRES 
        audio_pipeline
        esp-adf-libs
        spiffs
        json
    PRIV_REQUIRES
        esp_wifi
        nvs_flash
)