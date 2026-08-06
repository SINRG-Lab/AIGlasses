#!/usr/bin/env python3
"""Glasses simulator — impersonates the ESP32-S3 firmware over BLE so the
phone apps (Android/iOS) can be tested end-to-end with NO hardware.

The Mac advertises the same GATT service as S3_App_V2 and speaks the real
protocol: µ-law mic streaming (real speech, so OpenAI transcribes it),
TTS sink (decoded and played through the Mac speakers), 'P' ping echo,
'T' stats packets, photo transfer with in-band 'H'/'I'/'J'.

Setup (once):
    python3 -m pip install --user bless
    say -o /tmp/q.aiff "What is the capital of France?"
    afconvert -f WAVE -d LEI16@16000 -c 1 /tmp/q.aiff HardwareTest/sim_question.wav

Run:
    python3 HardwareTest/glasses_sim.py
Commands while running:
    t  = press-and-talk (streams sim_question.wav as the mic)
    p  = send a photo (sim_photo.jpg)
    v  = vision gesture (photo flags 0x02, then talk)
    q  = quit

WiFi ('F') requests are logged and IGNORED by default (no SoftAP to offer);
run with --wifi-n to answer with fake credentials and exercise the app's
join-failure path instead.
"""
import argparse
import asyncio
import struct
import sys
import threading
import time
import wave
from pathlib import Path

from bless import (
    BlessServer,
    BlessGATTCharacteristic,
    GATTAttributePermissions,
    GATTCharacteristicProperties,
)

HERE = Path(__file__).parent
SERVICE = "0000AA00-1234-5678-ABCD-0E5032C6B1E0"
AUDIO_TX = "0000AA01-1234-5678-ABCD-0E5032C6B1E0"   # sim -> phone (notify)
AUDIO_RX = "0000AA02-1234-5678-ABCD-0E5032C6B1E0"   # phone -> sim (write)
CONTROL = "0000AA03-1234-5678-ABCD-0E5032C6B1E0"    # both (write + notify)
IMAGE_TX = "0000AA04-1234-5678-ABCD-0E5032C6B1E0"   # sim -> phone (notify)

FRAG = 244            # payload bytes per notification (safe under any negotiated MTU >= 247)
MIC_RATE = 16000      # µ-law bytes/s when streaming the question


# ── µ-law codec (ported from S3_App_V2/ulaw.h) ──────────────────────────────
def ulaw_encode(pcm: int) -> int:
    CLIP = 32635
    sign = 0x80 if pcm < 0 else 0
    if pcm < 0:
        pcm = -pcm
    if pcm > CLIP:
        pcm = CLIP
    pcm += 0x84
    exp = 7
    mask = 0x4000
    while (pcm & mask) == 0 and exp > 0:
        exp -= 1
        mask >>= 1
    return ~(sign | (exp << 4) | ((pcm >> (exp + 3)) & 0x0F)) & 0xFF


def ulaw_decode(u: int) -> int:
    u = ~u & 0xFF
    t = ((u & 0x0F) << 3) + 0x84
    t <<= (u & 0x70) >> 4
    return (0x84 - t) if (u & 0x80) else (t - 0x84)


class GlassesSim:
    def __init__(self, wifi_n: bool):
        self.wifi_n = wifi_n
        self.server: BlessServer | None = None
        self.loop: asyncio.AbstractEventLoop | None = None
        self.voice_ulaw = False
        self.mic_seq = 0
        self.boot = time.monotonic()
        self.ble_tx = 0
        self.ble_rx = 0
        self.pings = 0
        self.tts_buf = bytearray()
        self.tts_last_rx = 0.0
        self.last_photo = (0, 0, 0)  # route, bytes, ms

    # ── notify helpers ──────────────────────────────────────────────────
    def _notify(self, char_uuid: str, data: bytes):
        char = self.server.get_characteristic(char_uuid)
        char.value = bytearray(data)
        self.server.update_value(SERVICE, char_uuid)
        self.ble_tx += len(data)

    async def notify(self, char_uuid: str, data: bytes):
        self._notify(char_uuid, data)

    # ── phone → sim ─────────────────────────────────────────────────────
    def on_write(self, characteristic: BlessGATTCharacteristic, value, **kwargs):
        data = bytes(value)
        self.ble_rx += len(data)
        uuid = str(characteristic.uuid).upper()
        if uuid == AUDIO_RX.upper():
            self._on_tts_audio(data)
        elif uuid == CONTROL.upper():
            self._on_control(data)

    def _on_control(self, data: bytes):
        if not data:
            return
        tag = chr(data[0])
        if tag == "M":
            self.voice_ulaw = True
            print("[SIM] realtime voice mode ON (µ-law)")
        elif tag == "m":
            self.voice_ulaw = False
            print("[SIM] realtime voice mode OFF")
        elif tag == "P":
            self._notify(CONTROL, data)          # echo verbatim
            self.pings += 1
        elif tag == "S":
            print(f"[SIM] response START ({data[1:2]!r})")
            self.tts_buf.clear()
        elif tag == "E":
            print(f"[SIM] response END — {len(self.tts_buf)} µ-law bytes received")
            self._play_tts()
        elif tag == "X":
            print("[SIM] stop-TTS ('X')")
            self.tts_buf.clear()
        elif tag == "F":
            if self.wifi_n:
                info = b"N" + b"AIGlasses-SIM\nglasses-link\n192.168.4.1\n5005"
                self._notify(CONTROL, info)
                print("[SIM] 'F' → sent fake 'N' credentials (join will fail — that's the test)")
            else:
                print("[SIM] 'F' WiFi request — ignored (no SoftAP; run with --wifi-n to fake it)")
        elif tag == "f":
            print("[SIM] 'f' WiFi off")
        else:
            print(f"[SIM] control 0x{data[0]:02x} ({len(data)} B)")

    def _on_tts_audio(self, data: bytes):
        if len(data) < 3 or data[0:1] != b"A":
            return
        self.tts_buf.extend(data[2:])
        now = time.monotonic()
        if now - self.tts_last_rx > 1.0:
            print(f"[SIM] TTS audio flowing… {len(self.tts_buf)} B buffered")
            self.tts_last_rx = now

    def _play_tts(self):
        if not self.tts_buf:
            return
        pcm = b"".join(struct.pack("<h", ulaw_decode(b)) for b in self.tts_buf)
        out = Path("/tmp/sim_tts.wav")
        with wave.open(str(out), "wb") as w:
            w.setnchannels(1)
            w.setsampwidth(2)
            w.setframerate(24000)
            w.writeframes(pcm)
        print(f"[SIM] response saved to {out} — playing…")
        import subprocess
        subprocess.Popen(["afplay", str(out)])

    # ── sim → phone actions ─────────────────────────────────────────────
    async def talk(self):
        wav_path = HERE / "sim_question.wav"
        if not wav_path.exists():
            print(f"[SIM] missing {wav_path} — see header for setup")
            return
        with wave.open(str(wav_path), "rb") as w:
            pcm = w.readframes(w.getnframes())
        samples = struct.unpack(f"<{len(pcm)//2}h", pcm)
        payload = (
            bytes(ulaw_encode(s) for s in samples)
            if self.voice_ulaw
            else pcm
        )
        rate = MIC_RATE if self.voice_ulaw else MIC_RATE * 2
        mode = "µ-law" if self.voice_ulaw else "PCM16"
        print(f"[SIM] TALK: streaming {len(payload)} B as {mode} mic audio…")
        self.mic_seq = 0
        await self.notify(CONTROL, b"S\x00")
        await asyncio.sleep(0.02)
        sent = 0
        t0 = time.monotonic()
        while sent < len(payload):
            frag = payload[sent : sent + FRAG]
            pkt = bytes([ord("A"), self.mic_seq & 0xFF]) + frag
            self.mic_seq += 1
            await self.notify(AUDIO_TX, pkt)
            sent += len(frag)
            # pace to real time so server VAD timing is realistic
            target = t0 + sent / rate
            delay = target - time.monotonic()
            if delay > 0:
                await asyncio.sleep(delay)
        await asyncio.sleep(0.02)
        await self.notify(CONTROL, b"E\x00")
        print(f"[SIM] TALK done ({sent} B in {time.monotonic()-t0:.1f} s) — 'E' sent")

    async def photo(self, flags: int = 0x00):
        jpg_path = HERE / "sim_photo.jpg"
        if not jpg_path.exists():
            print(f"[SIM] missing {jpg_path}")
            return
        jpeg = jpg_path.read_bytes()
        t0 = time.monotonic()
        hdr = struct.pack("<BBI", ord("H"), flags, len(jpeg))
        await self.notify(IMAGE_TX, hdr)
        await asyncio.sleep(0.015)
        seq = 0
        sent = 0
        while sent < len(jpeg):
            frag = jpeg[sent : sent + FRAG]
            await self.notify(IMAGE_TX, bytes([ord("I"), seq & 0xFF]) + frag)
            seq += 1
            sent += len(frag)
            await asyncio.sleep(0.015)   # BLE_IMG_FRAG_DELAY_MS
        await self.notify(IMAGE_TX, b"J\x00")
        ms = int((time.monotonic() - t0) * 1000)
        self.last_photo = (1, len(jpeg), ms)
        print(f"[SIM] photo sent: {len(jpeg)} B in {ms} ms (flags 0x{flags:02x})")

    async def vision(self):
        await self.photo(flags=0x02)
        await self.talk()

    # ── 'T' stats every 5 s ─────────────────────────────────────────────
    async def stats_loop(self):
        while True:
            await asyncio.sleep(5)
            route, pbytes, pms = self.last_photo
            pkt = struct.pack(
                "<BBIIIHBBBbHHHHIIIIIIIIBBIII",
                ord("T"), 1,
                int((time.monotonic() - self.boot) * 1000) & 0xFFFFFFFF,
                115 * 1024,            # "free heap"
                7 * 1024 * 1024,       # "free PSRAM"
                247,                   # MTU (the sim can't know the real one)
                2, 2,                  # PHY tx/rx pretend-2M
                0b0001,                # flags: BLE connected
                0,                     # RSSI n/a
                1, 0,                  # ble connects, wifi accepts
                self.pings & 0xFFFF, 0,
                self.ble_tx // 5, self.ble_rx // 5, 0, 0,
                self.ble_tx & 0xFFFFFFFF, self.ble_rx & 0xFFFFFFFF, 0, 0,
                route, 0, pbytes, pms,
                0,                     # wifi socket uptime
            )
            assert len(pkt) == 74, len(pkt)
            try:
                self._notify(CONTROL, pkt)
            except Exception:
                pass                    # no subscriber yet
            self.ble_tx = 0
            self.ble_rx = 0

    # ── server lifecycle ────────────────────────────────────────────────
    async def run(self):
        self.loop = asyncio.get_running_loop()
        self.server = BlessServer(name="AIGlasses-SIM", loop=self.loop)
        self.server.write_request_func = self.on_write

        await self.server.add_new_service(SERVICE)
        props_n = GATTCharacteristicProperties.notify
        props_w = (GATTCharacteristicProperties.write
                   | GATTCharacteristicProperties.write_without_response)
        props_c = props_w | GATTCharacteristicProperties.notify
        perm = GATTAttributePermissions.readable | GATTAttributePermissions.writeable
        # value=None is mandatory: CoreBluetooth only allows a cached initial
        # value on READ-ONLY characteristics — anything writable/notify must
        # be created value-less or addService throws NSInternalInconsistency.
        await self.server.add_new_characteristic(SERVICE, AUDIO_TX, props_n, None, perm)
        await self.server.add_new_characteristic(SERVICE, AUDIO_RX, props_w, None, perm)
        await self.server.add_new_characteristic(SERVICE, CONTROL, props_c, None, perm)
        await self.server.add_new_characteristic(SERVICE, IMAGE_TX, props_n, None, perm)
        await self.server.start()
        print("[SIM] advertising as 'AIGlasses-SIM' (service aa00) — connect from the app")
        print("[SIM] commands: t=talk  p=photo  v=vision  q=quit")

        asyncio.create_task(self.stats_loop())

        cmd_q: asyncio.Queue[str] = asyncio.Queue()

        def stdin_reader():
            for line in sys.stdin:
                asyncio.run_coroutine_threadsafe(cmd_q.put(line.strip()), self.loop)

        threading.Thread(target=stdin_reader, daemon=True).start()

        while True:
            cmd = await cmd_q.get()
            if cmd == "t":
                await self.talk()
            elif cmd == "p":
                await self.photo()
            elif cmd == "v":
                await self.vision()
            elif cmd == "q":
                break
        await self.server.stop()
        print("[SIM] stopped")


if __name__ == "__main__":
    ap = argparse.ArgumentParser()
    ap.add_argument("--wifi-n", action="store_true",
                    help="answer 'F' with fake WiFi credentials (tests the join-failure path)")
    args = ap.parse_args()
    try:
        asyncio.run(GlassesSim(args.wifi_n).run())
    except KeyboardInterrupt:
        pass
