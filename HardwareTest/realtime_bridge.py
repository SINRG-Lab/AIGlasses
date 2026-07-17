#!/usr/bin/env python3
"""Always-on voice bridge: glasses mic <-> OpenAI GPT Realtime <-> glasses speakers.

Runs over the single USB serial connection using the firmware's 'S' streaming
mode (see HardwareTest.ino):
  up:   [0xA5 0x5A][len u16 LE][PCM16 @16 kHz]   continuous mic
  down: [0xBA][len u16 LE][PCM16 @24 kHz]        response audio chunks
        [0xBE]                                    end of response
        'T'                                       leave streaming mode

Latency design (why it's built this way):
  - gpt-realtime-mini over WebSocket: the fast/cheap realtime model; audio is
    generated faster than realtime, so time-to-first-audio dominates.
  - Server VAD with a short silence window: the turn ends ~300 ms after you
    stop talking instead of the ~700 ms default.
  - Mic audio streams in 32 ms frames as you speak (nothing is batched).
  - Response audio streams to the glasses as it arrives; the firmware
    pre-buffers only ~200 ms before the speaker starts.
  - The downlink is paced (burst 2 s, then ~1.3x realtime) so the ESP's
    PSRAM ring never overflows on long answers.
  - Half-duplex by construction: the glasses mute the mic while speaking
    (no acoustic echo cancellation exists on this hardware), so the model
    never hears itself.
"""
import array
import asyncio
import base64
import json
import os
import threading
import time

import serial as pyserial

try:
    import websockets
except ImportError:
    websockets = None

# Flagship realtime model: much stronger than -mini at ~3x audio-token cost.
# Override without editing:  REALTIME_MODEL=gpt-realtime-2.1-mini  (fast/cheap)
#                            REALTIME_EFFORT=medium                (thinks longer)
MODEL = os.environ.get("REALTIME_MODEL", "gpt-realtime-2.1")
EFFORT = os.environ.get("REALTIME_EFFORT", "low")   # minimal|low|medium|high
VOICE = "marin"                    # docs-recommended voice (marin/cedar)
INSTRUCTIONS = (
    "You are a voice assistant built into a pair of smart glasses. "
    "Keep answers to one or two spoken sentences — never lists or formatting. "
    "Answer factual questions directly and accurately. "
    "The microphone is imperfect: if you did not clearly understand the user, "
    "say so and ask them to repeat — NEVER guess at what they said, and never "
    "agree with or confirm a statement you only partially heard."
)
WS_URL = f"wss://api.openai.com/v1/realtime?model={MODEL}"

MIC_RATE, OUT_RATE = 16000, 24000
FRAME_DOWN = 1024                      # bytes per downlink serial frame
# Downlink flow control. The ESP drains its 32 KB USB RX ring only between
# 32 ms mic reads / 10 ms speaker writes, and the ring DROPS on overflow —
# so the burst must stay well under 32 KB and the sustained rate just above
# the 48 KB/s playback consumption. (An unpaced burst desyncs the frame
# parser: no audio + a swallowed end marker = session stuck muted.)
DL_BURST = 12 * 1024                   # fast start: covers the 9.6 KB prebuffer
DL_BPS = 56000                         # ~1.17x realtime consumption


class MicConditioner:
    """High-pass + slow AGC for the quiet onboard mic, applied before upload.

    Per MIC_ROOT_CAUSE_ANALYSIS.md: the MSM261D3526H1CPM is a −26 dBFS
    sensitivity part — speech at temple distance peaks around −15 dBFS, far
    below what VAD/ASR want, and Seeed's own examples apply ×4 digital gain.
    One-pole HPF (~80 Hz) kills DC offset and handling rumble so the AGC
    doesn't amplify it; gain adapts slowly toward a −6 dBFS peak target,
    fast-attacks down on loud input, and is capped at 12× so silence never
    becomes white noise.
    """

    def __init__(self):
        self.prev_x = 0.0
        self.prev_y = 0.0
        self.gain = 4.0                    # start at Seeed's ×4

    def process(self, pcm: bytes) -> bytes:
        src = array.array("h")
        src.frombytes(pcm[: len(pcm) & ~1])
        n = len(src)
        if n == 0:
            return b""
        R = 0.97                            # ~80 Hz corner @ 16 kHz
        peak = 1
        for i in range(n):
            x = float(src[i])
            y = x - self.prev_x + R * self.prev_y
            self.prev_x, self.prev_y = x, y
            v = int(y)
            v = -32768 if v < -32768 else (32767 if v > 32767 else v)
            src[i] = v
            a = -v if v < 0 else v
            if a > peak:
                peak = a
        if peak * self.gain > 30000:        # fast attack: never clip
            self.gain = max(1.0, 30000.0 / peak)
        elif peak > 300:                    # speech present: slow release toward target
            desired = min(12.0, 16000.0 / peak)
            self.gain += (desired - self.gain) * 0.05
        g = self.gain
        for i in range(n):
            v = int(src[i] * g)
            src[i] = -32768 if v < -32768 else (32767 if v > 32767 else v)
        return src.tobytes()


def resample_16k_to_24k(pcm: bytes) -> bytes:
    """Linear-interpolation upsample, pure stdlib (audioop is gone in 3.13)."""
    src = array.array("h")
    src.frombytes(pcm[: len(pcm) & ~1])
    n = len(src)
    if n < 2:
        return b""
    m = (n * 3) // 2
    out = array.array("h", bytes(2 * m))
    for j in range(m):
        x = j * 2 / 3
        i = int(x)
        if i + 1 >= n:
            out[j] = src[n - 1]
        else:
            f = x - i
            out[j] = int(src[i] + (src[i + 1] - src[i]) * f)
    return out.tobytes()


class VoiceSession:
    """One always-on realtime session. start() spawns a thread; stop() ends it."""

    def __init__(self, ser, api_key, ssl_ctx, status: dict):
        self.ser = ser
        self.key = api_key
        self.ssl_ctx = ssl_ctx
        self.status = status          # shared with the web UI
        self.stop_event = threading.Event()
        self.write_lock = threading.Lock()
        self.thread = None
        self._dl_t0 = time.time()     # downlink token bucket (reset per response)
        self._dl_sent = 0
        self.mic = MicConditioner()
        # latency instrumentation (written to latency_log.csv per turn)
        self._t_speech_stopped = None
        self._lat_ttfa_ms = None      # speech_stopped -> first audio delta
        self._lat_pending = False     # waiting for prebuffer fill mark
        self._turn_count = 0

    # ── lifecycle ──
    def start(self):
        self.thread = threading.Thread(target=self._run, daemon=True)
        self.thread.start()

    def stop(self):
        self.stop_event.set()
        if self.thread:
            self.thread.join(timeout=10)

    def _run(self):
        try:
            asyncio.run(self._main())
        except Exception as e:
            print(f"voice session crashed: {e}")
            self.status.update(state=f"crashed: {e}", on=False)
        finally:
            self.stop_event.set()   # kill the reader thread on EVERY exit path,
                                    # or it would keep eating the camera's bytes
            try:
                with self.write_lock:
                    self.ser.write(b"T")      # firmware leaves streaming mode
                    self.ser.flush()
            except Exception:
                pass
            self.status.update(on=False, state="off")

    # ── serial uplink: parse mic frames + log lines ──
    def _serial_reader(self, loop, mic_q):
        buf = bytearray()
        ser = self.ser
        while not self.stop_event.is_set():
            try:
                chunk = ser.read(max(1, ser.in_waiting))
            except Exception as e:
                self.status["state"] = f"serial lost: {e}"
                self.stop_event.set()
                return
            if chunk:
                buf += chunk
            while buf:
                if buf[0] == 0xA5:
                    if len(buf) < 4:
                        break
                    if buf[1] != 0x5A:
                        del buf[:1]
                        continue
                    ln = buf[2] | (buf[3] << 8)
                    if ln == 0 or ln > 4096:
                        del buf[:2]
                        continue
                    if len(buf) < 4 + ln:
                        break
                    frame = bytes(buf[4:4 + ln])
                    del buf[:4 + ln]
                    loop.call_soon_threadsafe(mic_q.put_nowait, frame)
                else:
                    nl = buf.find(b"\n")
                    a5 = buf.find(0xA5)
                    end = min(x for x in (nl + 1 if nl >= 0 else -1,
                                          a5 if a5 > 0 else -1) if x > 0) \
                        if (nl >= 0 or a5 > 0) else (len(buf) if len(buf) > 512 else -1)
                    if end < 0:
                        break
                    text = buf[:end].decode(errors="replace").strip()
                    del buf[:end]
                    if text:
                        print(f"glasses: {text}")

    # ── main async orchestration ──
    async def _main(self):
        if websockets is None:
            self.status.update(state="pip3 install websockets", on=False)
            return
        self.status.update(state="connecting", user="", assistant="")

        headers = {"Authorization": f"Bearer {self.key}"}
        try:
            connect = websockets.connect(WS_URL, additional_headers=headers,
                                         ssl=self.ssl_ctx, max_size=1 << 24)
        except TypeError:   # websockets < 13 uses extra_headers
            connect = websockets.connect(WS_URL, extra_headers=headers,
                                         ssl=self.ssl_ctx, max_size=1 << 24)

        async with connect as ws:
            await ws.send(json.dumps({
                "type": "session.update",
                "session": {
                    "type": "realtime",
                    "instructions": INSTRUCTIONS,
                    # 2.1 models can think before speaking; "low" barely adds
                    # latency, "medium"/"high" think longer for harder tasks
                    "reasoning": {"effort": EFFORT},
                    "output_modalities": ["audio"],
                    "audio": {
                        "input": {
                            "format": {"type": "audio/pcm", "rate": 24000},
                            "transcription": {"model": "gpt-4o-mini-transcribe"},
                            # server_vad with a short silence window is the
                            # verified lowest-latency turn detection: the turn
                            # ends 200 ms after you stop speaking (default 500).
                            "turn_detection": {
                                "type": "server_vad",
                                "threshold": 0.5,
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

            # enter firmware streaming mode only once the socket is live
            with self.write_lock:
                self.ser.reset_input_buffer()
                self.ser.write(b"S")
                self.ser.flush()

            loop = asyncio.get_running_loop()
            mic_q = asyncio.Queue()
            reader = threading.Thread(target=self._serial_reader,
                                      args=(loop, mic_q), daemon=True)
            reader.start()
            self.status["state"] = "listening"

            await asyncio.gather(self._pump_mic(ws, mic_q),
                                 self._pump_events(ws),
                                 self._watch_stop(ws))

    async def _watch_stop(self, ws):
        while not self.stop_event.is_set():
            await asyncio.sleep(0.2)
        await ws.close()

    async def _pump_mic(self, ws, mic_q):
        while not self.stop_event.is_set():
            try:
                pcm16k = await asyncio.wait_for(mic_q.get(), timeout=0.5)
            except asyncio.TimeoutError:
                continue
            conditioned = self.mic.process(pcm16k)
            self.status["mic_gain"] = round(self.mic.gain, 1)
            await ws.send(json.dumps({
                "type": "input_audio_buffer.append",
                "audio": base64.b64encode(resample_16k_to_24k(conditioned)).decode(),
            }))

    def _send_down(self, payload: bytes):
        """Write audio frames with a token bucket so the ESP RX ring never
        overflows: DL_BURST up front, then DL_BPS sustained. Runs in an
        executor thread, so time.sleep() is fine."""
        with self.write_lock:
            for off in range(0, len(payload), FRAME_DOWN):
                if self.stop_event.is_set():
                    return
                chunk = payload[off:off + FRAME_DOWN]
                while (self._dl_sent + len(chunk) >
                       DL_BURST + (time.time() - self._dl_t0) * DL_BPS):
                    time.sleep(0.01)
                    if self.stop_event.is_set():
                        return
                self.ser.write(bytes([0xBA, len(chunk) & 0xFF, len(chunk) >> 8]))
                self.ser.write(chunk)
                self.ser.flush()
                self._dl_sent += len(chunk)
                # 9600 B = the firmware's prebuffer: speaker starts right here
                if self._lat_pending and self._dl_sent >= 9600:
                    self._lat_pending = False
                    if self._t_speech_stopped:
                        spk_ms = (time.time() - self._t_speech_stopped) * 1000 + 30
                        self._log_latency(spk_ms)

    def _log_latency(self, spk_ms):
        self._turn_count += 1
        ttfa = self._lat_ttfa_ms or 0
        line = (f"{time.strftime('%H:%M:%S')},{ttfa:.0f},{spk_ms:.0f},"
                f"\"{self.status.get('user', '')[:80]}\"\n")
        try:
            import os.path
            path = os.path.join(os.path.dirname(__file__), "latency_log.csv")
            new = not os.path.exists(path)
            with open(path, "a") as f:
                if new:
                    f.write("time,ttfa_ms,to_speaker_ms,user_text\n")
                f.write(line)
        except Exception as e:
            print(f"latency log failed: {e}")
        self.status["lat_n"] = self._turn_count
        self.status["lat_last"] = f"{ttfa:.0f}/{spk_ms:.0f}"
        print(f"turn {self._turn_count}: TTFA {ttfa:.0f} ms, to-speaker {spk_ms:.0f} ms")

    async def _pump_events(self, ws):
        resp_t0 = None
        assistant_text = ""
        try:
            async for raw in ws:
                if self.stop_event.is_set():
                    break
                ev = json.loads(raw)
                t = ev.get("type", "")

                if t == "response.output_audio.delta" or t == "response.audio.delta":
                    audio = base64.b64decode(ev.get("delta", ""))
                    if resp_t0 is None:
                        resp_t0 = time.time()
                        self._dl_t0 = resp_t0     # token bucket resets per response
                        self._dl_sent = 0
                        self.status["state"] = "speaking"
                        if self._t_speech_stopped:
                            self._lat_ttfa_ms = (resp_t0 - self._t_speech_stopped) * 1000
                            self._lat_pending = True
                    await asyncio.get_running_loop().run_in_executor(
                        None, self._send_down, audio)

                elif t in ("response.done", "response.output_audio.done",
                           "response.audio.done"):
                    if t == "response.done":
                        with self.write_lock:
                            self.ser.write(bytes([0xBE]))
                            self.ser.flush()
                        resp_t0 = None
                        if assistant_text:
                            self.status["assistant"] = assistant_text
                        assistant_text = ""
                        self.status["state"] = "listening"

                elif t in ("response.output_audio_transcript.delta",
                           "response.audio_transcript.delta"):
                    assistant_text += ev.get("delta", "")
                    self.status["assistant"] = assistant_text

                elif t == "conversation.item.input_audio_transcription.completed":
                    self.status["user"] = ev.get("transcript", "").strip()

                elif t == "input_audio_buffer.speech_started":
                    self.status["state"] = "hearing you..."

                elif t == "input_audio_buffer.speech_stopped":
                    self._t_speech_stopped = time.time()
                    self.status["state"] = "thinking"

                elif t == "error":
                    msg = ev.get("error", {}).get("message", str(ev))
                    print(f"realtime error: {msg}")
                    self.status["state"] = f"API error: {msg[:120]}"
        except websockets.ConnectionClosed:
            self.status["state"] = "connection closed"
            self.stop_event.set()
