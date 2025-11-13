# For more information about build system see
# https://docs.espressif.com/projects/esp-idf/en/latest/api-guides/build-system.html
# The following five lines of boilerplate have to be in your project's
# CMakeLists in this exact order for cmake to work correctly
cmake_minimum_required(VERSION 3.5)

# Set all EXTRA_COMPONENT_DIRS before the include
set(ADF_PATH "C:/Users/ryder/esp/esp-adf")

# Only add the specific components you actually need
list(APPEND EXTRA_COMPONENT_DIRS 
    ${ADF_PATH}/components/audio_pipeline
    ${ADF_PATH}/components/audio_sal
    ${ADF_PATH}/components/esp-adf-libs
)

include($ENV{IDF_PATH}/tools/cmake/project.cmake)

set(PARTITION_CSV_PATH ${CMAKE_SOURCE_DIR}/partitions.csv)

project(5G-AI-Glasses)
spiffs_create_partition_image(storage spiffs_data FLASH_IN_PROJECT)