#!/usr/bin/env python3
"""Always-on GPT Realtime voice over BLUETOOTH — no USB cable.

Desktop prototype of the real product architecture:
  glasses mic --BLE notify--> this script --WS--> GPT Realtime
  glasses spk <--BLE write--- this script <--WS--- audio deltas

Uses the same GATT layout as the production S3_App_V2 firmware
(service aa00: aa01 mic notify, aa02 speaker write, aa03 control),
so everything learned here transfers to the Android app.

Downlink audio runs at 16 kHz (downsampled from the API's 24 kHz) so the
required ~32 KB/s stays comfortably inside BLE throughput even at 1M PHY.

Run:  python3 realtime_ble.py          (Ctrl-C to stop)
macOS will ask for Bluetooth permission for your terminal on first run.
Requires: pip3 install bleak websockets
"""
import array
import asyncio
import base64
import collections
import json
import os
import ssl
import sys
import time
import wave

from realtime_bridge import (MODEL, EFFORT, VOICE, INSTRUCTIONS, WS_URL,
                             MicConditioner, resample_16k_to_24k)

try:
    import websockets
except ImportError:
    sys.exit("pip3 install websockets")
try:
    from bleak import BleakClient, BleakScanner
except ImportError:
    sys.exit("pip3 install bleak")

try:
    import certifi
    SSL_CTX = ssl.create_default_context(cafile=certifi.where())
except ImportError:
    SSL_CTX = ssl.create_default_context()

SVC = "0000aa00-1234-5678-abcd-0e5032c6b1e0"
CH_TX = "0000aa01-1234-5678-abcd-0e5032c6b1e0"    # mic -> here
CH_RX = "0000aa02-1234-5678-abcd-0e5032c6b1e0"    # here -> speaker
CH_CTRL = "0000aa03-1234-5678-abcd-0e5032c6b1e0"

# Playback rate over BLE. 24000 = native GPT Realtime audio, zero resampling
# (the uplink proved the radio sustains 48 KB/s). Fall back with:
#   REALTIME_BLE_RATE=16000 python3 realtime_ble.py
PLAY_RATE = int(os.environ.get("REALTIME_BLE_RATE", "24000"))
PREBUF_ULAW = PLAY_RATE * 3 // 4     # 750 ms of µ-law bytes (1 B/sample)
DL_BURST = PREBUF_ULAW + 6 * 1024    # fill the prebuffer fast + margin
DL_BPS = int(PLAY_RATE * 1.35)       # µ-law: 1 byte/sample; ceiling ~1.35x
LOG = os.path.join(os.path.dirname(__file__), "ble_latency_log.csv")


def load_api_key():
    key = os.environ.get("OPENAI_API_KEY", "").strip()
    if key:
        return key
    secrets = os.path.join(os.path.dirname(__file__), "..", "repo", "AIglasses", "secrets.properties")
    if os.path.exists(secrets):
        for line in open(secrets):
            if line.startswith("OPENAI_API_KEY"):
                return line.split("=", 1)[1].strip()
    sys.exit("OPENAI_API_KEY not set")


import numpy as np

# ── G.711 µ-law: BLE audio is 1 byte/sample both directions (halves the
#    packet rate — the macOS central drops notifications above ~40/s). ──
_ULAW_DECODE_LUT = None

def _ulaw_lut():
    global _ULAW_DECODE_LUT
    if _ULAW_DECODE_LUT is None:
        lut = np.empty(256, dtype=np.int16)
        for u in range(256):
            v = ~u & 0xFF
            t = ((v & 0x0F) << 3) + 0x84
            t <<= (v & 0x70) >> 4
            lut[u] = (0x84 - t) if (v & 0x80) else (t - 0x84)
        _ULAW_DECODE_LUT = lut
    return _ULAW_DECODE_LUT

def ulaw_decode(data: bytes) -> bytes:
    return _ulaw_lut()[np.frombuffer(data, dtype=np.uint8)].tobytes()

def ulaw_encode(pcm: bytes) -> bytes:
    x = np.frombuffer(pcm[: len(pcm) & ~1], dtype="<i2").astype(np.int32)
    sign = np.where(x < 0, 0x80, 0).astype(np.uint8)
    a = np.clip(np.abs(x), 0, 32635) + 0x84
    exp = np.clip(np.floor(np.log2(a)).astype(np.int32) - 7, 0, 7)
    mant = (a >> (exp + 3)) & 0x0F
    return (~(sign | (exp.astype(np.uint8) << 4) | mant.astype(np.uint8))
            ).astype(np.uint8).tobytes()


class Downsampler24to16:
    """24 kHz -> 16 kHz with a real anti-aliasing low-pass.

    A bare linear decimator folds 8-12 kHz content into the audible band —
    the gritty 'muffled + buzzy' artifact the repo's Android pipeline already
    documented and fixed with a windowed-sinc. 31-tap FIR (~7 kHz cutoff),
    stateful across chunks, exact 3:2 polyphase so there's no phase drift.
    """

    def __init__(self):
        n = np.arange(31) - 15
        cutoff = 7000.0 / 24000.0
        taps = 2 * cutoff * np.sinc(2 * cutoff * n) * np.hamming(31)
        self.taps = (taps / taps.sum()).astype(np.float32)
        self.tail = np.zeros(30, dtype=np.float32)   # filter history
        self.rem = np.zeros(0, dtype=np.float32)     # 0-2 filtered leftovers

    def process(self, pcm: bytes) -> bytes:
        x = np.frombuffer(pcm[: len(pcm) & ~1], dtype="<i2").astype(np.float32)
        if len(x) == 0:
            return b""
        buf = np.concatenate([self.tail, x])
        y = np.convolve(buf, self.taps, "valid")     # continuous filtered stream
        self.tail = buf[-30:]
        y = np.concatenate([self.rem, y])
        usable = (len(y) // 3) * 3
        self.rem = y[usable:]
        y = y[:usable].reshape(-1, 3)
        out = np.empty(len(y) * 2, dtype=np.float32)
        out[0::2] = y[:, 0]                          # t = 0.0
        out[1::2] = 0.5 * (y[:, 1] + y[:, 2])        # t = 1.5
        return np.clip(out, -32768, 32767).astype("<i2").tobytes()


class BleVoice:
    def __init__(self, key):
        self.key = key
        self.mic = MicConditioner()
        self.down = Downsampler24to16()
        self.mic_q = asyncio.Queue()
        self.client = None
        self.payload = 180                 # recomputed from MTU after connect
        self.seq = 0
        self._dl_t0 = time.time()
        self._dl_sent = 0
        self._t_speech_stopped = None
        self._ttfa_ms = None
        self._lat_pending = False
        self._turn = 0
        self._assistant = ""
        self._mic_seq = None
        self._mic_gaps = 0
        self._mic_dups = 0
        # per-2s telemetry + ground-truth capture of what we forward to OpenAI
        self._st_acc = 0
        self._st_dup = 0
        self._st_lost = 0
        self._st_recv = 0
        self._debug_pcm = collections.deque(maxlen=1500)   # accepted (post-filter)
        self._raw_pcm = collections.deque(maxlen=3000)     # everything, as it arrived
        self._raw_log = collections.deque(maxlen=20000)    # (t, seq, size) per notification

    # ── BLE side ──
    def on_mic_frame(self, _char, data: bytearray):
        if len(data) <= 2 or data[0] != ord("A"):
            return
        seq = data[1]
        self._st_recv += 1
        self._raw_log.append((time.monotonic(), seq, len(data)))
        self._raw_pcm.append(bytes(data[2:]))
        if self._mic_seq is not None:
            # macOS's stale double-subscription delivers the stream twice,
            # interleaved with a lag. Anything at-or-behind the last accepted
            # seq (delta 0 or "negative" mod 256) is a stale copy: drop it.
            delta = (seq - self._mic_seq) & 0xFF
            if delta == 0 or delta >= 200:
                self._mic_dups += 1
                self._st_dup += 1
                return
            gap = delta - 1
            if 0 < gap <= 8:
                # small real loss: zero-fill to keep VAD timing sane
                # (x2: lost payloads were µ-law, the queue carries PCM16)
                self._mic_gaps += gap
                self._st_lost += gap
                self.mic_q.put_nowait(bytes(2 * gap * (len(data) - 2)))
            elif gap > 8:
                self._mic_gaps += 1     # resync after a big jump, don't fill
                self._st_lost += 1
        self._mic_seq = seq
        self._st_acc += 1
        payload = ulaw_decode(bytes(data[2:]))    # µ-law -> PCM16
        self._debug_pcm.append(payload)
        self.mic_q.put_nowait(payload)

    async def ctrl(self, marker: bytes):
        """Control write, without-response (a response PDU can fail with ATT
        'Insufficient Resource' while the server's buffers are full of mic
        notifications). ATT is sequential, so ordering vs audio writes holds."""
        for _ in range(20):
            if not (self.client and self.client.is_connected):
                return False
            try:
                await self.client.write_gatt_char(self.ch_ctrl, marker, response=False)
                return True
            except Exception:
                await asyncio.sleep(0.05)
        print(f"control write {marker!r} kept failing")
        return False

    async def ble_manager(self):
        """Own the BLE link for the whole session: connect, and on ANY drop
        reconnect and resume — the OpenAI session (and conversation context)
        lives independently and survives radio hiccups. Without this, a BLE
        drop leaves the glasses in standalone mode where the button is the
        local record-and-playback parrot."""
        first = True
        while True:
            try:
                dev = await BleakScanner.find_device_by_filter(
                    lambda d, ad: SVC in (ad.service_uuids or []), timeout=15)
                if not dev:
                    print("glasses not found — still scanning...")
                    continue
                lost = asyncio.Event()
                client = BleakClient(dev, disconnected_callback=lambda c: lost.set())
                await client.connect()
                best = None
                for svc in client.services:
                    if svc.uuid.lower() == SVC:
                        chars = {c.uuid.lower(): c for c in svc.characteristics}
                        if all(u in chars for u in (CH_TX, CH_RX, CH_CTRL)):
                            if best is None or svc.handle > best[0]:
                                best = (svc.handle, chars)
                if not best:
                    print("service incomplete — retrying...")
                    await client.disconnect()
                    continue
                self.ch_tx, self.ch_rx, self.ch_ctrl = (
                    best[1][CH_TX], best[1][CH_RX], best[1][CH_CTRL])
                mtu = min(client.mtu_size or 23, 512)
                self.payload = max(20, mtu - 3 - 2)   # µ-law: no even-ness needed
                self.client = client
                self._mic_seq = None                  # fresh seq tracking
                await client.start_notify(self.ch_tx, self.on_mic_frame)
                await self.ctrl(b"M")
                print(("connected" if first else "RECONNECTED") +
                      f" — MTU {mtu}, hold the button to talk")
                first = False
                await lost.wait()
                print("\n*** BLE dropped — reconnecting (conversation continues) ***")
                self.client = None
            except Exception as e:
                print(f"BLE error: {e} — retrying in 2 s")
                self.client = None
                await asyncio.sleep(2)

    async def send_audio_down(self, pcm16k: bytes):
        for off in range(0, len(pcm16k), self.payload):
            chunk = pcm16k[off:off + self.payload]
            while (self._dl_sent + len(chunk) >
                   DL_BURST + (time.time() - self._dl_t0) * DL_BPS):
                await asyncio.sleep(0.01)
            pkt = bytes([ord("A"), self.seq & 0xFF]) + chunk
            for _ in range(20):
                if not (self.client and self.client.is_connected):
                    return                        # link down — drop this response
                try:
                    await self.client.write_gatt_char(self.ch_rx, pkt, response=False)
                    break
                except Exception:
                    await asyncio.sleep(0.02)
            self.seq += 1
            self._dl_sent += len(chunk)
            if self._lat_pending and self._dl_sent >= PREBUF_ULAW:
                self._lat_pending = False
                if self._t_speech_stopped:
                    spk = (time.time() - self._t_speech_stopped) * 1000 + 30
                    self._log(spk)

    def _log(self, spk_ms):
        self._turn += 1
        new = not os.path.exists(LOG)
        with open(LOG, "a") as f:
            if new:
                f.write("time,ttfa_ms,to_speaker_ms\n")
            f.write(f"{time.strftime('%H:%M:%S')},{self._ttfa_ms:.0f},{spk_ms:.0f}\n")
        print(f"--- turn {self._turn}: TTFA {self._ttfa_ms:.0f} ms, "
              f"to-speaker {spk_ms:.0f} ms (BLE)")

    async def stats_loop(self):
        """Honest link telemetry every 2 s: received off the radio vs accepted."""
        while True:
            await asyncio.sleep(2)
            print(f"    [link] {self._st_recv/2:.0f} recv/s | "
                  f"{self._st_acc/2:.0f} accepted/s | "
                  f"{self._st_dup/2:.0f} dup/s | {self._st_lost/2:.0f} lost/s")
            self._st_recv = self._st_acc = self._st_dup = self._st_lost = 0

    def dump_debug_wav(self):
        """Ground truth to disk: raw arrivals, filtered stream, seq log."""
        base = os.path.dirname(__file__)
        for name, buf in (("mic_debug.wav", self._debug_pcm),
                          ("mic_raw.wav", self._raw_pcm)):
            if buf:
                with wave.open(os.path.join(base, name), "wb") as w:
                    w.setnchannels(1)
                    w.setsampwidth(2)
                    w.setframerate(16000)
                    w.writeframes(b"".join(buf))
        if self._raw_log:
            with open(os.path.join(base, "seq_debug.csv"), "w") as f:
                f.write("t,seq,size\n")
                for t, s, n in self._raw_log:
                    f.write(f"{t:.4f},{s},{n}\n")
        print("\nwrote mic_debug.wav (filtered), mic_raw.wav (as-arrived), "
              "seq_debug.csv (every notification)")

    # ── OpenAI side ──
    async def pump_mic(self, ws):
        talking = False
        while True:
            try:
                pcm = await asyncio.wait_for(self.mic_q.get(), timeout=0.25)
            except asyncio.TimeoutError:
                # Button released (push-to-talk): the stream stops, but server
                # VAD needs to HEAR silence to close the turn — feed it 600 ms
                # of zeros once, then go quiet until the next press.
                if talking:
                    talking = False
                    await ws.send(json.dumps({
                        "type": "input_audio_buffer.append",
                        "audio": base64.b64encode(bytes(2 * 24000 * 6 // 10)).decode(),
                    }))
                continue
            talking = True
            conditioned = self.mic.process(pcm)
            await ws.send(json.dumps({
                "type": "input_audio_buffer.append",
                "audio": base64.b64encode(resample_16k_to_24k(conditioned)).decode(),
            }))

    async def pump_events(self, ws):
        resp_open = False
        async for raw in ws:
            ev = json.loads(raw)
            t = ev.get("type", "")
            if t == "response.output_audio.delta":
                audio = base64.b64decode(ev.get("delta", ""))
                if not resp_open:
                    resp_open = True
                    self._dl_t0 = time.time()
                    self._dl_sent = 0
                    self.seq = 0
                    if self._t_speech_stopped:
                        self._ttfa_ms = (self._dl_t0 - self._t_speech_stopped) * 1000
                        self._lat_pending = True
                    await self.ctrl(b"S2" if PLAY_RATE == 24000 else b"S1")
                    print("[speaking]")
                await self.send_audio_down(ulaw_encode(
                    audio if PLAY_RATE == 24000 else self.down.process(audio)))
            elif t == "response.done":
                if resp_open:
                    await self.ctrl(b"E")
                    resp_open = False
                if self._assistant:
                    print(f"assistant: {self._assistant}")
                    self._assistant = ""
                print("[listening]")
            elif t == "response.output_audio_transcript.delta":
                self._assistant += ev.get("delta", "")
            elif t == "conversation.item.input_audio_transcription.completed":
                print(f"you: {ev.get('transcript', '').strip()}")
            elif t == "input_audio_buffer.speech_started":
                print("[hearing you...]")
            elif t == "input_audio_buffer.speech_stopped":
                self._t_speech_stopped = time.time()
                print("[thinking]")
            elif t == "error":
                print(f"API error: {ev.get('error', {}).get('message', ev)}")

    async def run(self):
        # OpenAI session first — it owns the conversation and outlives any
        # number of BLE drops; ble_manager owns the radio and reconnects.
        headers = {"Authorization": f"Bearer {self.key}"}
        try:
            connect = websockets.connect(WS_URL, additional_headers=headers,
                                         ssl=SSL_CTX, max_size=1 << 24)
        except TypeError:
            connect = websockets.connect(WS_URL, extra_headers=headers,
                                         ssl=SSL_CTX, max_size=1 << 24)
        if True:
            async with connect as ws:
                await ws.send(json.dumps({
                    "type": "session.update",
                    "session": {
                        "type": "realtime",
                        "instructions": INSTRUCTIONS,
                        "reasoning": {"effort": EFFORT},
                        "output_modalities": ["audio"],
                        "audio": {
                            "input": {
                                "format": {"type": "audio/pcm", "rate": 24000},
                                "transcription": {"model": "gpt-4o-mini-transcribe"},
                                "turn_detection": {
                                    "type": "server_vad", "threshold": 0.5,
                                    "prefix_padding_ms": 300,
                                    "silence_duration_ms": 200,
                                    "create_response": True,
                                    "interrupt_response": True,
                                },
                            },
                            "output": {
                                "format": {"type": "audio/pcm", "rate": 24000},
                                "voice": VOICE,
                            },
                        },
                    },
                }))
                print(f"Voice ON — model {MODEL}, effort {EFFORT}. Scanning for glasses...")
                print("HOLD THE BUTTON on the glasses while speaking, release "
                      "when done (push-to-talk, like the real product). Ctrl-C to stop.")
                try:
                    await asyncio.gather(self.ble_manager(),
                                         self.pump_mic(ws), self.pump_events(ws),
                                         self.stats_loop())
                finally:
                    try:
                        await self.ctrl(b"m")
                    except Exception:
                        pass
                    self.dump_debug_wav()


if __name__ == "__main__":
    try:
        asyncio.run(BleVoice(load_api_key()).run())
    except KeyboardInterrupt:
        print("\nbye")
