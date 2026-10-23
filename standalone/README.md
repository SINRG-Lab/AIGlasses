# Standalone glasses

Glasses that reach the cloud directly over cellular, with no phone. Built on the
[Walter](https://www.quickspot.io/) board: ESP32-S3 + Sequans GM02SP modem.

The GM02SP is sold as a "5G" modem, but it supports **LTE-M / NB-IoT only**, and all code here uses LTE-M.

| Folder | Status |
|---|---|
| [`arduino/`](arduino/) | **Current.** PlatformIO/Arduino. Gemini STT→TTS and Deepgram Voice Agent pipelines work; the speaker hardware is the open problem. See its README. |
| [`micropython-prototype/`](micropython-prototype/) | Legacy MicroPython version, superseded by `arduino/`. |

Main limitation found: throughput is bounded by the ESP32↔modem transfer (1500 bytes per ring), not by the cellular link.
