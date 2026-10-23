# Standalone AI Glasses — MicroPython prototype (legacy)

> **Superseded by [`../arduino`](../arduino).** Kept for reference; not maintained.
> MicroPython was dropped because Walter's MicroPython library is less mature than its Arduino/ESP-IDF one
> (it could splice AT responses into socket data under fast ring pulls — see the Arduino README).
> Best response time reached here was ~45 s, after batching rings.

MicroPython scripts for the [Walter](https://www.quickspot.io/) board (ESP32-S3 + Sequans GM02SP modem)
that send recorded audio straight to Gemini / Deepgram over **LTE-M** — no phone in the loop.

The GM02SP is marketed as a "5G" modem but only supports LTE-M / NB-IoT; every script here forces LTE-M.

| File | What it does |
|---|---|
| `agent/` | Latest version: `audio_agent.py` main loop, `audio_apis.py` (`Gemini`, `Deepgram` clients), `helpers.py` |
| `gem_a2a_ltem.py` | Gemini audio-to-audio (STT+answer with `gemini-2.5-flash`, then TTS) |
| `gem_a2t_lte-m.py` | Gemini audio-to-text only |
| `sft_rst.py` | Soft-resets the board before running another script |
| `archive/` | Earlier iterations copied off the board |
| `data/` | Test audio clips |
| `walter_modem/` | Vendored copy of QuickSpot's Walter MicroPython modem library (see below) |

## Setup

1. Copy `config.template.json` to `config.json` and fill in your API keys and APN. `config.json` is gitignored —
   never put keys directly in the `.py` files.
2. Upload the scripts, `config.json`, and `data/*.wav` to the board.

Original getting-started reference: [Eduardo's demo](https://github.com/ebaenamar/walter-nbiot).

## Third-party code

`walter_modem/` comes from [QuickSpot/walter-micropython](https://github.com/QuickSpot/walter-micropython)
(see that repository for its license terms), with a local fix to the `core.py` response parser for fast ring
pulls (commit "Fixed parse error when delay between pulling rings is shorter than ~300ms").
`walter_modem/queue.py` is by Peter Hinch (MIT, header retained).
