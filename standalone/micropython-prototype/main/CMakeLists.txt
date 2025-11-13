idf_component_register(
    SRCS "WalterCOMM.h" "WalterCOMM.cpp" "main.cpp" 
    INCLUDE_DIRS "."
    REQUIRES 
        audio_pipeline
        esp-adf-libs
        spiffs
        json
)