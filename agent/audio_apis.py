"""
Gemini audio API client for Walter ESP32-S3 MicroPython.
Provides audio-to-text and text-to-audio via Gemini over LTE-M sockets.
"""

import asyncio
import time
import ujson as json
import os
import struct

from walter_modem.coreStructs import WalterModemRsp
from walter_modem.mixins.socket import WalterModemSocketProtocol, WalterModemSocketState
from walter_modem.mixins.tls_certs import WalterModemTlsVersion, WalterModemTlsValidation

from helpers import (
    load_audio_file,
    decode_pcm_to_wav,
    _decode_chunked,
    _extract_b64_fallback,
    _Console, _console
)

class Gemini:
    """
    Gemini audio API client.

    Provides audio-to-text transcription and text-to-audio synthesis
    over TLS sockets using the Walter modem.

    Args:
        modem: Initialized Walter Modem instance (with SocketMixin, TLSCertsMixin).
        modem_rsp: Shared WalterModemRsp instance for command responses.
        api_key: Gemini API key string.
        verbosity: Console verbosity level (0-3).
        a2t_model: Model name for audio-to-text requests.
        tts_model: Model name for TTS requests.
        a2t_socket_id: Socket ID for audio-to-text requests.
        tts_socket_id: Socket ID for TTS requests.
    """

    SERVER = "generativelanguage.googleapis.com"
    PORT = 443

    def __init__(
            self,
            modem,
            modem_rsp: WalterModemRsp,
            api_key: str,
            verbosity: int = 1,
            a2t_model: str = "gemini-2.5-flash",
            tts_model: str = "gemini-2.5-flash-preview-tts",
            a2t_socket_id: int = 1,
            tts_socket_id: int = 2,
    ):
        self._modem = modem
        self._rsp = modem_rsp
        self._api_key = api_key
        self._a2t_model = a2t_model
        self._tts_model = tts_model
        self._a2t_sid = a2t_socket_id
        self._tts_sid = tts_socket_id

        _console.verbosity = verbosity

    # ============== PUBLIC API ==============

    async def audio_live(self):
        """Live audio streaming — not yet implemented."""
        raise NotImplementedError("audio_live is not yet implemented")

    async def a2t(
            self,
            audio_file: str,
            prompt: str,
            max_tokens: int = 512,
            temperature: float = 0.7,
    ) -> tuple:
        """
        Audio-to-text: send an audio file to Gemini and get a text response.

        Args:
            audio_file: Path to .wav or .mp3 file on the filesystem.
            prompt: Text prompt to accompany the audio.
            max_tokens: Maximum output tokens.
            temperature: Sampling temperature.

        Returns:
            (response_text, latency_ms) on success, (None, -1) on failure.
        """
        _console.print("\n--- Gemini Audio-to-Text ---")

        audio_b64, mime_type = load_audio_file(audio_file)
        if not audio_b64:
            _console.print(f"Cannot load {audio_file}")
            return None, -1

        uri = f"/v1beta/models/{self._a2t_model}:generateContent?key={self._api_key}"

        payload = json.dumps({
            "contents": [{
                "parts": [
                    {"inline_data": {"mime_type": mime_type, "data": audio_b64}},
                    {"text": prompt},
                ]
            }],
            "generationConfig": {
                "maxOutputTokens": max_tokens,
                "temperature": temperature,
            },
        })
        _console.print(f"Payload size: {len(payload)} bytes")

        http_req = self._build_http_request(uri, payload)
        sid = self._a2t_sid

        if not await self._setup_tls_socket(sid):
            return None, -1

        _console.print("Connected to Gemini, sending audio...")

        t0 = time.ticks_ms()
        result = await self._sti_send_and_receive(sid, http_req, timeout=120)
        latency = time.ticks_diff(time.ticks_ms(), t0)

        await self._safe_close(sid)

        if result:
            _console.print(f"Latency: {latency} ms")

        return result, latency

    async def t2a(
            self,
            text: str,
            voice: str = "Kore",
    ) -> tuple:
        """
        Text-to-audio: convert text to speech using Gemini TTS.

        Args:
            text: Text to synthesize.
            voice: Voice name (Aoede, Charon, Fenrir, Kore, Puck, etc.).

        Returns:
            (wav_bytes, latency_ms) on success, (None, -1) on failure.
        """
        _console.print("\n--- Gemini Text-to-Audio ---")
        _console.print(f"Voice: {voice}")

        uri = f"/v1beta/models/{self._tts_model}:generateContent?key={self._api_key}"

        payload = json.dumps({
            "contents": [{"parts": [{"text": text}]}],
            "generationConfig": {
                "responseModalities": ["AUDIO"],
                "speechConfig": {
                    "voiceConfig": {
                        "prebuiltVoiceConfig": {"voiceName": voice}
                    }
                },
            },
        })
        _console.print(f"TTS payload size: {len(payload)} bytes", 2)

        http_req = self._build_http_request(uri, payload)
        sid = self._tts_sid

        if not await self._setup_tls_socket(sid):
            return None, -1

        _console.print("Connected to Gemini TTS, sending request...")

        t0 = time.ticks_ms()
        audio_b64 = await self._tts_send_and_receive(sid, http_req)
        latency = time.ticks_diff(time.ticks_ms(), t0)

        await self._safe_close(sid)

        if audio_b64:
            _console.print(f"Received audio data: {len(audio_b64)} base64 chars", 2)
            wav_bytes = decode_pcm_to_wav(audio_b64)
            _console.print(f"WAV size: {len(wav_bytes)} bytes")
            return wav_bytes, latency

        return None, -1

    # ============== HTTP HELPERS ==============

    def _build_http_request(self, uri, body):
        """Build a complete HTTP/1.1 POST request string."""
        return (
            f"POST {uri} HTTP/1.1\r\n"
            f"Host: {self.SERVER}\r\n"
            f"Content-Type: application/json\r\n"
            f"Content-Length: {len(body)}\r\n"
            f"Connection: close\r\n"
            f"Accept: application/json\r\n"
            f"\r\n"
            f"{body}"
        )

    # ============== TLS / SOCKET SETUP ==============

    async def _setup_tls_socket(self, socket_id):
        """Configure and connect a TLS socket."""
        m = self._modem

        if not await m.tls_config_profile(
                profile_id=1,
                tls_version=WalterModemTlsVersion.TLS_VERSION_12,
                tls_validation=WalterModemTlsValidation.NONE,
        ):
            _console.print("TLS config failed")
            return False

        if not await m.socket_config(
                ctx_id=socket_id,
                pdp_ctx_id=1,
                mtu=1500,
                exchange_timeout=90,
                connection_timeout=30,
                send_delay_ms=0,
        ):
            _console.print("Socket config failed")
            return False

        if not await m.socket_config_secure(
                ctx_id=socket_id, enable=True, secure_profile_id=1,
        ):
            _console.print("Socket TLS config failed")
            return False

        _console.print(f"Connecting to {self.SERVER}:{self.PORT}...")
        if not await m.socket_dial(
                ctx_id=socket_id,
                remote_addr=self.SERVER,
                remote_port=self.PORT,
                protocol=WalterModemSocketProtocol.TCP,
        ):
            _console.print("Socket dial failed")
            return False

        return True

    async def _safe_close(self, socket_id):
        """Close socket, ignoring errors."""
        try:
            await self._modem.socket_close(ctx_id=socket_id)
        except Exception as e:
            _console.print(f"Failed to close socket: {e}", 2)

    # ============== A2T SOCKET I/O ==============

    async def _sti_send_and_receive(self, socket_id, http_request, timeout=120):
        """Send request and receive text response over socket."""
        if not await self._send_chunked(socket_id, http_request):
            return None

        _console.print("Request sent, waiting for response...")
        await asyncio.sleep(0.5)

        response_data = b''
        no_data = 0

        for _ in range(timeout):
            rings = self._modem.socket_context_states[socket_id].rings

            if rings:
                payload = await self._read_ring_data(socket_id, rings.pop(0))
                if payload:
                    response_data += payload
                    _console.print(f"  +{len(payload)} bytes (total: {len(response_data)})", 2)
                no_data = 0

                if b'\r\n0\r\n' in response_data:
                    result = _parse_sti_response(response_data)
                    if result:
                        return result
            else:
                no_data += 1

            if response_data and no_data > 2:
                result = _parse_sti_response(response_data)
                if result:
                    return result

            if no_data > 15 and response_data:
                break
            elif no_data > 30:
                _console.print("Timeout")
                break

            await asyncio.sleep(0.5)

        if response_data:
            _debug_raw_response(response_data)
            return _parse_sti_response(response_data)

        return None

    # ============== TTS SOCKET I/O ==============

    async def _tts_send_and_receive(self, socket_id, http_request, timeout=500):
        """Send request and receive TTS audio response over socket."""
        if not await self._send_chunked(socket_id, http_request, label="TTS"):
            return None

        await asyncio.sleep(0.5)

        response_buf = bytearray(300_000) # this creates a max response length of 300kb
        response_len = 0
        content_length = -1
        header_end_pos = -1
        no_data = 0
        recv_start = None

        data_wait = 0.00001
        no_data_wait = 0.3
        start = time.ticks_ms()
        last_status_print = time.ticks_ms()

        while time.ticks_diff(time.ticks_ms(), start) < (timeout * 1000):
            if time.ticks_diff(time.ticks_ms(), last_status_print) >= 5000:
                _console.print("    receiving TTS data...")
                last_status_print = time.ticks_ms()
            try:
                if not self._modem.socket_context_states[socket_id].rings:
                    no_data += 1
                    _console.print(f"No data count: {no_data}", 2)

                    if response_len and no_data > 5:
                        if not self._modem.socket_context_states[socket_id].connected:
                            _console.print("  Socket no longer connected", 2)
                            break
                        if no_data > int(10 / no_data_wait):
                            _console.print("TTS timeout - no more data received", 2)
                            break

                    await asyncio.sleep(no_data_wait)
                    continue

                payload = await self._read_ring_data_batched(socket_id)
                if payload:
                    _console.print(f"  Got {len(payload)} bytes", 2)
                    if recv_start is None:
                        recv_start = time.ticks_ms()

                    plen = len(payload)
                    response_buf[response_len:response_len + plen] = payload
                    response_len += plen

                # Parse content-length from headers (once, small copy)
                if content_length < 0 and header_end_pos < 0 and response_len > 4:
                    hdr_end = response_buf.find(b'\r\n\r\n', 0, min(response_len, 2048))
                    if hdr_end >= 0:
                        header_end_pos = hdr_end
                        headers = bytes(response_buf[:hdr_end]).decode('utf-8', 'ignore').lower()
                        for line in headers.split('\r\n'):
                            if line.startswith('content-length:'):
                                content_length = int(line.split(':')[1].strip())
                                _console.print(f"  Content-Length: {content_length}")
                                break

                # Check body completeness (no copy, just arithmetic)
                if content_length > 0 and header_end_pos >= 0:
                    body_received = response_len - (header_end_pos + 4)
                    _console.print(
                        f"  Body: {body_received}/{content_length} bytes", 3
                    )
                    if body_received >= content_length:
                        _console.print("  Full body received")
                        break

                no_data = 0
                await asyncio.sleep(data_wait)

            except Exception as e:
                _console.print(f"  TTS error: {e}")
                await asyncio.sleep(0.3)

        # --- Post-loop: metrics and parse ---
        if recv_start and response_len:
            recv_ms = time.ticks_diff(time.ticks_ms(), recv_start)
            if recv_ms > 0:
                _console.print(
                    f"Downloaded {response_len} bytes in {recv_ms}ms "
                    f"({response_len * 8e-3 / (recv_ms / 1000):.1f} kbps)"
                )

        if response_len:
            response_data = bytes(response_buf[:response_len])
            del response_buf
            return _parse_tts_response(response_data)

        del response_buf
        return None

    async def _tts_send_and_receive_http(self):
        pass

    # ============== SHARED SOCKET PRIMITIVES ==============

    async def _send_chunked(self, socket_id, http_request, chunk_size=4096, delay=0.005, label=""):
        """Send an HTTP request in chunks over a socket."""
        data = http_request if isinstance(http_request, bytes) else http_request.encode('utf-8')
        total = len(data)
        prefix = f"{label} " if label else ""
        _console.print(f"Sending {prefix}request {total} bytes...")

        for i in range(0, total, chunk_size):
            chunk = data[i:i + chunk_size]
            if not await self._modem.socket_send(ctx_id=socket_id, data=chunk):
                _console.print(f"Send failed at byte {i}")
                return False
            if i > 0 and i % 40000 == 0:
                _console.print(f"  Sent {i}/{total} bytes ({(i * 100) // total}%)...")
            await asyncio.sleep(delay)

        _console.print(f"{prefix}request sent, waiting for response...")
        return True

    async def _read_ring_data(self, socket_id, ring):
        """Read payload bytes from a single ring notification."""
        data_length = ring.length if ring.length else 1500
        _console.print(f"  Ring data_length={data_length}", 2)

        try:
            await self._modem.socket_receive_data(
                ctx_id=socket_id,
                length=data_length,
                max_bytes=min(data_length, 1500),
                rsp=self._rsp,
            )

            if self._rsp.socket_rcv_response and self._rsp.socket_rcv_response.payload:
                return bytes(self._rsp.socket_rcv_response.payload)

            if ring.data:
                return bytes(ring.data)
        except ValueError:
            _console.print("  ValueError in receive, using ring.data directly", 2)
            if ring.data:
                return bytes(ring.data)

        return None

    async def _read_ring_data_batched(self, socket_id, max_bytes=1500):
        """
        Consume as many pending rings as possible in a single AT+SQNSRECV call.

        Sums ring lengths until the next ring would exceed max_bytes (modem recv
        ceiling is 1500). Issues one socket_receive_data command for the whole
        batch, dramatically reducing UART round-trips.

        Args:
            socket_id: Socket context ID.
            max_bytes: Maximum bytes per recv call (modem limit is 1500).

        Returns:
            bytes payload on success, None if no data available.
        """
        rings = self._modem.socket_context_states[socket_id].rings

        if not rings:
            return None

        # Calculate how many rings we can collapse into one read
        batch_len = 0
        batch_count = 0

        for ring in rings:
            rlen = ring.length if ring.length else 0
            if rlen == 0:
                # If there's an unknown length ring take it solo with max_bytes
                if batch_count == 0:
                    batch_len = max_bytes
                    batch_count = 1
                break
            if batch_len + rlen > max_bytes:
                break
            batch_len += rlen
            batch_count += 1

        # Edge case: first ring is larger than max_bytes on its own
        if batch_count == 0:
            batch_len = max_bytes
            batch_count = 1

        # Pop consumed rings
        consumed = rings[:batch_count]
        del rings[:batch_count]

        request_bytes = min(batch_len, max_bytes)
        _console.print(
            f"  Batch read: {batch_count} rings, {request_bytes} bytes requested", 2
        )

        try:
            await self._modem.socket_receive_data(
                ctx_id=socket_id,
                length=request_bytes,
                max_bytes=request_bytes,
                rsp=self._rsp,
            )

            if self._rsp.socket_rcv_response and self._rsp.socket_rcv_response.payload:
                return bytes(self._rsp.socket_rcv_response.payload)

            # Fallback: check if any consumed ring carried inline data
            for ring in consumed:
                if ring.data:
                    return bytes(ring.data)

        except ValueError:
            _console.print("  ValueError in batch receive, checking ring data", 2)
            for ring in consumed:
                if ring.data:
                    return bytes(ring.data)

        return None

    # ============== TTS RESPONSE HELPERS ==============

    async def _check_socket_closed(self, socket_id, response_data):
        """Check if the server closed the connection; if so, try to parse."""
        try:
            status_rsp = WalterModemRsp()
            await self._modem.socket_status(ctx_id=socket_id, rsp=status_rsp)

            if status_rsp.socket_status:
                state = status_rsp.socket_status.state
                _console.print(f"  Socket status: {state}", 2)
                if state == WalterModemSocketState.CLOSED or state == 0:
                    _console.print("  Server closed connection, parsing response", 2)
                    return _parse_tts_response(response_data)

            if not self._modem.socket_context_states[socket_id].connected:
                _console.print("  Socket no longer connected", 2)
                return _parse_tts_response(response_data)
        except Exception as e:
            _console.print(f"  Socket status check error: {e}", 2)

        return None


class Deepgram:
    """
    Deepgram Voice Agent client.

    Provides audio-to-audio conversation via Deepgram's Voice Agent WebSocket
    API over TLS sockets using the Walter modem.

    The Voice Agent handles STT, LLM reasoning, and TTS in a single
    persistent WebSocket session — one API call for the full pipeline.

    Args:
        modem: Initialized Walter Modem instance (with SocketMixin, TLSCertsMixin).
        modem_rsp: Shared WalterModemRsp instance for command responses.
        api_key: Deepgram API key string.
        verbosity: Console verbosity level (0-3).
        socket_id: Socket ID for the WebSocket connection.
    """

    SERVER = "agent.deepgram.com"
    PORT = 443
    WS_PATH = "/v1/agent/converse"

    def __init__(
            self,
            modem,
            modem_rsp: WalterModemRsp,
            api_key: str,
            verbosity: int = 1,
            socket_id: int = 3,
    ):
        self._modem = modem
        self._rsp = modem_rsp
        self._api_key = api_key
        self._sid = socket_id

        # Persistent buffer for partial WebSocket frames across ring reads
        self._ws_buf = bytearray()

        _console.verbosity = verbosity

    # ================================================================
    #  PUBLIC API
    # ================================================================

    async def a2a(
            self,
            audio_file: str = None,
            audio_data: str = None,
            prompt: str = "You are a helpful voice assistant. Be concise.",
            voice: str = "aura-2-asteria-en",
            think_provider: str = "open_ai",
            think_model: str = None,
            input_sample_rate: int = 16000,
            output_sample_rate: int = 24000,
            output_encoding: str = "linear16",
            greeting: str = None,
    ) -> tuple:
        """
        Audio-to-audio: send an audio file and receive a spoken response
        via Deepgram's Voice Agent in a single WebSocket session.

        Args:
            audio_file: Path to a .wav file on the filesystem.
            prompt: System prompt for the LLM (think) stage.
            voice: Deepgram TTS voice model name.
            think_provider: LLM provider ("open_ai", "anthropic", "google", "groq").
            think_model: Specific model name (provider-dependent default if None).
            input_sample_rate: Sample rate of the input audio in Hz.
            output_sample_rate: Desired output sample rate in Hz.
            output_encoding: Output encoding ("linear16", "mulaw", "alaw").
            greeting: Optional agent greeting spoken at session start.

        Returns:
            (wav_bytes, latency_ms) on success, (None, -1) on failure.
        """
        _console.print("\n--- Deepgram Audio-to-Audio ---")
        if audio_file is None and audio_data is None:
            raise RuntimeError("Must pass audio file or audio data to Deepgram.")

        if audio_file is not None:
            audio_bytes = self._load_raw_pcm(audio_file)
        else:
            audio_bytes = audio_data

        if not audio_bytes:
            _console.print(f"Cannot load {audio_file}")
            return None, -1

        sid = self._sid
        self._ws_buf = bytearray()

        # Connect TLS socket
        if not await self._setup_tls_socket(sid):
            return None, -1

        # WebSocket upgrade handshake
        if not await self._ws_handshake(sid):
            await self._safe_close(sid)
            return None, -1
        _console.print("WebSocket connected")

        # Send Settings message
        settings = self._build_settings(
            prompt=prompt,
            voice=voice,
            think_provider=think_provider,
            think_model=think_model,
            input_encoding="linear16",
            input_sample_rate=input_sample_rate,
            output_encoding=output_encoding,
            output_sample_rate=output_sample_rate,
            greeting=greeting,
        )
        await self._ws_send_text(sid, json.dumps(settings))
        _console.print("Settings sent, waiting for SettingsApplied...")

        # Wait for SettingsApplied before streaming audio
        if not await self._wait_for_event(sid, "SettingsApplied", timeout=15):
            _console.print("Never received SettingsApplied")
            await self._safe_close(sid)
            return None, -1
        _console.print("SettingsApplied received")

        # Stream audio as binary WebSocket frames
        _console.print(f"Sending {len(audio_bytes)} bytes of audio...")
        t0 = time.ticks_ms()
        await self._ws_send_audio(sid, audio_bytes)
        _console.print("Audio sent, waiting for agent response...")

        # Collect response audio and transcripts
        result = await self._collect_agent_response(
            sid, output_sample_rate, output_encoding
        )
        latency = time.ticks_diff(time.ticks_ms(), t0)

        # Clean close
        await self._ws_send_close(sid)
        await asyncio.sleep(0.1)
        await self._safe_close(sid)

        if result:
            wav_bytes, transcript = result
            _console.print(f"Total latency: {latency} ms")
            if transcript:
                _console.print(f"Agent said: {transcript[:120]}...")
            return wav_bytes, latency

        return None, -1

    # ================================================================
    #  SETTINGS BUILDER
    # ================================================================

    def _build_settings(
            self, prompt, voice, think_provider, think_model,
            input_encoding, input_sample_rate,
            output_encoding, output_sample_rate, greeting,
    ):
        """Build the Settings JSON message for the Voice Agent API."""

        # --- Think (LLM) provider ---
        provider_defaults = {
            "open_ai":   ("open_ai",   "gpt-4o-mini"),
            "anthropic": ("anthropic", "claude-sonnet-4-20250514"),
            "google":    ("google",    "gemini-2.0-flash"),
            "groq":      ("groq",      "openai/gpt-oss-20b"),
        }
        ptype, default_model = provider_defaults.get(
            think_provider, ("open_ai", "gpt-4o-mini")
        )
        think_cfg = {
            "provider": {"type": ptype, "model": think_model or default_model},
            "prompt": prompt,
        }

        # --- Agent block ---
        agent_cfg = {
            "listen": {
                "provider": {"type": "deepgram", "model": "nova-3"}
            },
            "think": think_cfg,
            "speak": {
                "provider": {"type": "deepgram", "model": voice}
            },
        }
        if greeting:
            agent_cfg["greeting"] = greeting

        return {
            "type": "Settings",
            "audio": {
                "input": {
                    "encoding": input_encoding,
                    "sample_rate": input_sample_rate,
                },
                "output": {
                    "encoding": output_encoding,
                    "sample_rate": output_sample_rate,
                    "container": "none",
                },
            },
            "agent": agent_cfg,
        }

    # ================================================================
    #  AUDIO FILE LOADING
    # ================================================================

    @staticmethod
    def _load_raw_pcm(audio_file):
        """
        Load a WAV file and return raw PCM sample bytes
        (strips the RIFF/WAVE header).
        """
        try:
            with open(audio_file, "rb") as f:
                data = f.read()

            if data[:4] == b"RIFF" and data[8:12] == b"WAVE":
                # Walk chunks to find 'data'
                pos = 12
                while pos < len(data) - 8:
                    cid = data[pos : pos + 4]
                    csz = struct.unpack("<I", data[pos + 4 : pos + 8])[0]
                    if cid == b"data":
                        return data[pos + 8 : pos + 8 + csz]
                    pos += 8 + csz
                # Fallback: standard 44-byte header
                return data[44:]

            return data  # assume raw PCM
        except Exception as e:
            _console.print(f"Error loading audio: {e}")
            return None

    # ================================================================
    #  TLS / SOCKET SETUP  (mirrors Gemini class)
    # ================================================================

    async def _setup_tls_socket(self, socket_id):
        """Configure TLS and connect a TCP socket to Deepgram."""
        m = self._modem

        if not await m.tls_config_profile(
                profile_id=1,
                tls_version=WalterModemTlsVersion.TLS_VERSION_12,
                tls_validation=WalterModemTlsValidation.NONE,
        ):
            _console.print("TLS config failed")
            return False

        if not await m.socket_config(
                ctx_id=socket_id, pdp_ctx_id=1, mtu=1500,
                exchange_timeout=90, connection_timeout=30, send_delay_ms=0,
        ):
            _console.print("Socket config failed")
            return False

        if not await m.socket_config_extended(
                ctx_id=socket_id,
                ring_mode=1,
                recv_mode=0,
                keepalive=60,
                listen_auto_resp=False,
                send_mode=0,
        ):
            _console.print("Socket extended config failed")
            return False

        if not await m.socket_config_secure(
                ctx_id=socket_id, enable=True, secure_profile_id=1,
        ):
            _console.print("Socket TLS config failed")
            return False

        _console.print(f"Connecting to {self.SERVER}:{self.PORT}...")
        if not await m.socket_dial(
                ctx_id=socket_id,
                remote_addr=self.SERVER,
                remote_port=self.PORT,
                protocol=WalterModemSocketProtocol.TCP,
        ):
            _console.print("Socket dial failed")
            return False

        return True

    async def _safe_close(self, socket_id):
        """Close socket, ignoring errors."""
        try:
            await self._modem.socket_close(ctx_id=socket_id)
        except Exception as e:
            _console.print(f"Close socket error: {e}", 2)

    # ================================================================
    #  WEBSOCKET HANDSHAKE
    # ================================================================

    async def _ws_handshake(self, socket_id):
        """Perform the HTTP/1.1 → WebSocket upgrade."""
        import ubinascii
        ws_key = ubinascii.b2a_base64(os.urandom(16)).decode().strip()

        upgrade = (
                "GET " + self.WS_PATH + " HTTP/1.1\r\n"
                                        "Host: " + self.SERVER + "\r\n"
                                                                 "Upgrade: websocket\r\n"
                                                                 "Connection: Upgrade\r\n"
                                                                 "Sec-WebSocket-Key: " + ws_key + "\r\n"
                                                                                                  "Sec-WebSocket-Version: 13\r\n"
                                                                                                  "Authorization: Token " + self._api_key + "\r\n"
                                                                                                                                            "\r\n"
        )

        if not await self._modem.socket_send(
                ctx_id=socket_id, data=upgrade.encode()
        ):
            _console.print("Failed to send WS upgrade")
            return False

        # Read until we get the full HTTP response header
        hdr = await self._read_until(socket_id, b"\r\n\r\n", timeout=10)
        if not hdr:
            _console.print("No WS upgrade response")
            return False

        if b"101" not in hdr[:64]:
            _console.print(f"WS upgrade rejected: {hdr[:200]}")
            return False

        # Any bytes after the header boundary are the start of WS frames
        sep = hdr.find(b"\r\n\r\n")
        leftover = hdr[sep + 4 :]
        if leftover:
            self._ws_buf.extend(leftover)

        return True

    # ================================================================
    #  WEBSOCKET FRAME BUILDING  (client → server, always masked)
    # ================================================================

    @staticmethod
    def _build_ws_frame(opcode, payload):
        """
        Build a masked WebSocket frame (RFC 6455).
        Client-to-server frames MUST be masked.
        """
        length = len(payload)
        frame = bytearray()
        frame.append(0x80 | opcode)  # FIN + opcode

        if length < 126:
            frame.append(0x80 | length)
        elif length < 65536:
            frame.append(0x80 | 126)
            frame.extend(struct.pack(">H", length))
        else:
            frame.append(0x80 | 127)
            frame.extend(struct.pack(">Q", length))

        mask = os.urandom(4)
        frame.extend(mask)

        # XOR-mask the payload — process 4 bytes at a time to reduce
        # interpreter overhead on MicroPython
        masked = bytearray(payload)
        m0, m1, m2, m3 = mask[0], mask[1], mask[2], mask[3]
        tail = length & ~3
        for i in range(0, tail, 4):
            masked[i]     ^= m0
            masked[i + 1] ^= m1
            masked[i + 2] ^= m2
            masked[i + 3] ^= m3
        for i in range(tail, length):
            masked[i] ^= mask[i & 3]

        frame.extend(masked)
        return frame

    async def _ws_send_text(self, socket_id, text):
        """Send a text (opcode 0x1) WebSocket frame."""
        frame = self._build_ws_frame(0x1, text.encode("utf-8"))
        await self._modem.socket_send(ctx_id=socket_id, data=frame)

    async def _ws_send_binary(self, socket_id, data):
        """Send a binary (opcode 0x2) WebSocket frame."""
        frame = self._build_ws_frame(0x2, data)
        await self._modem.socket_send(ctx_id=socket_id, data=frame)

    async def _ws_send_close(self, socket_id):
        """Send a close (opcode 0x8) WebSocket frame."""
        try:
            frame = self._build_ws_frame(0x8, b"")
            await self._modem.socket_send(ctx_id=socket_id, data=frame)
        except Exception:
            pass

    async def _ws_send_keepalive(self, socket_id):
        """Send a Deepgram KeepAlive message."""
        await self._ws_send_text(
            socket_id, json.dumps({"type": "KeepAlive"})
        )

    # ================================================================
    #  WEBSOCKET FRAME PARSING  (server → client, never masked)
    # ================================================================

    def _parse_ws_frames(self):
        """
        Parse complete WebSocket frames out of ``self._ws_buf``.
        Leaves any incomplete trailing bytes in the buffer.
        Modifies buffer in-place to avoid reallocation.

        Returns a list of (opcode, payload_bytes) tuples.
        """
        frames = []
        buf = self._ws_buf
        buf_len = len(buf)
        pos = 0

        while pos < buf_len:
            frame_start = pos

            # Need at least 2 bytes for the header
            if pos + 2 > buf_len:
                break

            b0 = buf[pos]
            b1 = buf[pos + 1]
            opcode = b0 & 0x0F
            masked = (b1 & 0x80) != 0
            length = b1 & 0x7F
            pos += 2

            if length == 126:
                if pos + 2 > buf_len:
                    pos = frame_start
                    break
                length = struct.unpack(">H", bytes(buf[pos : pos + 2]))[0]
                pos += 2
            elif length == 127:
                if pos + 8 > buf_len:
                    pos = frame_start
                    break
                length = struct.unpack(">Q", bytes(buf[pos : pos + 8]))[0]
                pos += 8

            mask_key = None
            if masked:
                if pos + 4 > buf_len:
                    pos = frame_start
                    break
                mask_key = bytes(buf[pos : pos + 4])
                pos += 4

            if pos + length > buf_len:
                # Incomplete payload — rewind and wait for more data
                pos = frame_start
                break

            payload = bytes(buf[pos : pos + length])
            if mask_key:
                payload = bytes(
                    payload[i] ^ mask_key[i & 3] for i in range(len(payload))
                )
            pos += length

            frames.append((opcode, payload))

        # Trim consumed bytes in-place
        if pos > 0:
            self._ws_buf = self._ws_buf[pos:]

        return frames

    # ================================================================
    #  HIGHER-LEVEL RECEIVE HELPERS
    # ================================================================

    async def _read_ws_frames(self, socket_id, timeout=1):
        """
        Pull data from modem rings into ``_ws_buf``, then parse
        any complete WebSocket frames.
        """
        deadline = time.ticks_add(time.ticks_ms(), timeout * 1000)

        while time.ticks_diff(deadline, time.ticks_ms()) > 0:
            rings = self._modem.socket_context_states[socket_id].rings
            if rings:
                payload = await self._read_ring_data_batched(socket_id)
                if payload:
                    self._ws_buf.extend(payload)
                    # Drain all immediately-available rings before parsing
                    continue
            break

        return self._parse_ws_frames()

    async def _wait_for_event(self, socket_id, event_type, timeout=15):
        """
        Block until a specific JSON event ``type`` arrives, or timeout.
        Returns True if received, False on timeout or error.
        """
        deadline = time.ticks_add(time.ticks_ms(), timeout * 1000)

        while time.ticks_diff(deadline, time.ticks_ms()) > 0:
            frames = await self._read_ws_frames(socket_id, timeout=1)
            for opcode, data in frames:
                if opcode == 0x1:  # text
                    try:
                        msg = json.loads(data)
                        mt = msg.get("type", "")
                        _console.print(f"  WS event: {mt}", 2)
                        if mt == event_type:
                            return True
                        if mt == "Error":
                            _console.print(
                                "  Error: " + msg.get("description", "unknown")
                            )
                            return False
                    except Exception:
                        pass
            await asyncio.sleep(0.05)

        return False

    # ================================================================
    #  AUDIO STREAMING  (client → server)
    # ================================================================

    async def _ws_send_audio(self, socket_id, pcm_bytes, chunk_size=4000):
        """
        Stream raw PCM audio to the agent as binary WebSocket frames.
        Chunk size kept below modem's comfortable send window.
        """
        total = len(pcm_bytes)
        sent = 0
        mv = memoryview(pcm_bytes)

        while sent < total:
            end = min(sent + chunk_size, total)
            chunk = mv[sent:end]
            await self._ws_send_binary(socket_id, bytes(chunk))
            sent = end

            if sent % 40000 < chunk_size:
                _console.print(
                    "  Audio sent: %d/%d (%d%%)" % (sent, total, (sent * 100) // total), 2
                )
            await asyncio.sleep(0.005)

        _console.print("Audio streaming complete: %d bytes sent" % total)

    # ================================================================
    #  RESPONSE COLLECTION  (server → client)
    # ================================================================

    async def _collect_agent_response(
            self, socket_id, sample_rate, encoding, timeout=120
    ):
        """
        Collect binary audio frames and JSON events until AgentAudioDone.
        Uses a pre-allocated buffer to avoid heap fragmentation.

        Returns (wav_bytes, agent_transcript) or None.
        """
        audio_buf = bytearray(300_000)
        audio_len = 0
        agent_text = ""
        audio_done = False
        no_data_count = 0
        no_data_wait = 0.3
        last_keepalive = time.ticks_ms()
        last_status = time.ticks_ms()
        start = time.ticks_ms()

        while time.ticks_diff(time.ticks_ms(), start) < (timeout * 1000):
            # Periodic status log
            if time.ticks_diff(time.ticks_ms(), last_status) >= 5000:
                _console.print("  Collecting... %d audio bytes" % audio_len)
                last_status = time.ticks_ms()

            # Send KeepAlive every ~8 seconds to prevent idle disconnect
            if time.ticks_diff(time.ticks_ms(), last_keepalive) >= 8000:
                await self._ws_send_keepalive(socket_id)
                last_keepalive = time.ticks_ms()

            frames = await self._read_ws_frames(socket_id, timeout=1)

            if not frames:
                no_data_count += 1
                if audio_done:
                    break
                if no_data_count > 90:
                    _console.print("Timeout - no more data from agent")
                    break
                if (
                        not self._modem.socket_context_states[socket_id].connected
                        and audio_len > 0
                ):
                    _console.print("Socket disconnected, using collected audio")
                    break
                await asyncio.sleep(no_data_wait)
                continue

            no_data_count = 0

            for opcode, data in frames:
                # --- Binary: raw audio samples ---
                if opcode == 0x2:
                    dlen = len(data)
                    if audio_len + dlen <= len(audio_buf):
                        audio_buf[audio_len:audio_len + dlen] = data
                        audio_len += dlen
                    else:
                        _console.print("  Audio buffer full, truncating")

                # --- Text: JSON event ---
                elif opcode == 0x1:
                    try:
                        msg = json.loads(data)
                        mt = msg.get("type", "")

                        self._log_agent_event(msg, mt)

                        if mt == "ConversationText":
                            if msg.get("role", "") == "assistant":
                                agent_text = msg.get("content", "")

                        elif mt == "AgentAudioDone":
                            audio_done = True

                        elif mt == "Error":
                            if audio_len == 0:
                                del audio_buf
                                return None

                    except Exception:
                        pass

                # --- Close frame from server ---
                elif opcode == 0x8:
                    _console.print("  Server sent close frame", 2)
                    audio_done = True

        if audio_len == 0:
            _console.print("No audio received from agent")
            del audio_buf
            return None

        _console.print("  Total audio received: %d bytes" % audio_len)

        raw_pcm = bytes(audio_buf[:audio_len])
        del audio_buf

        wav = self._pcm_to_wav(raw_pcm, sample_rate, encoding)
        return wav, agent_text

    @staticmethod
    def _log_agent_event(msg, mt):
        """Log agent events at appropriate verbosity levels."""
        _console.print("  WS event: " + mt, 2)

        if mt == "ConversationText":
            role = msg.get("role", "")
            content = msg.get("content", "")
            if role == "assistant":
                _console.print("  Agent: " + content[:100])
            elif role == "user":
                _console.print("  User:  " + content[:100])

        elif mt == "AgentStartedSpeaking":
            lat = msg.get("total_latency", 0)
            _console.print("  Agent speaking (latency: %.2fs)" % lat)

        elif mt == "AgentAudioDone":
            _console.print("  Agent audio complete")

        elif mt == "UserStartedSpeaking":
            _console.print("  User started speaking", 2)

        elif mt == "AgentThinking":
            _console.print("  Agent thinking...", 2)

        elif mt == "Error":
            _console.print(
                "  Agent error: " + msg.get("description", "unknown")
            )

        elif mt == "Warning":
            _console.print(
                "  Warning: " + msg.get("description", ""), 1
            )

    # ================================================================
    #  RING READING  (same methodology as Gemini class)
    # ================================================================

    async def _read_ring_data_batched(self, socket_id, max_bytes=1500):
        """
        Consume as many pending rings as possible in a single
        AT+SQNSRECV call.  Batches ring lengths up to max_bytes
        (modem ceiling 1500) to minimize UART round-trips.

        Identical algorithm to the Gemini class.
        """
        rings = self._modem.socket_context_states[socket_id].rings
        if not rings:
            return None

        batch_len = 0
        batch_count = 0

        for ring in rings:
            rlen = ring.length if ring.length else 0
            if rlen == 0:
                if batch_count == 0:
                    batch_len = max_bytes
                    batch_count = 1
                break
            if batch_len + rlen > max_bytes:
                break
            batch_len += rlen
            batch_count += 1

        if batch_count == 0:
            batch_len = max_bytes
            batch_count = 1

        del rings[:batch_count]

        request_bytes = min(batch_len, max_bytes)
        _console.print(
            "  Batch: %d rings, %d bytes" % (batch_count, request_bytes), 3
        )

        try:
            await self._modem.socket_receive_data(
                ctx_id=socket_id,
                length=request_bytes,
                max_bytes=request_bytes,
                rsp=self._rsp,
            )
            if (
                    self._rsp.socket_rcv_response
                    and self._rsp.socket_rcv_response.payload
            ):
                return bytes(self._rsp.socket_rcv_response.payload)

        except ValueError:
            _console.print("  ValueError in batch receive", 2)

        return None

    async def _read_until(self, socket_id, sentinel, timeout=10):
        """
        Read from socket rings until ``sentinel`` is found in the
        accumulated buffer, or timeout.
        """
        buf = bytearray()
        deadline = time.ticks_add(time.ticks_ms(), timeout * 1000)

        while time.ticks_diff(deadline, time.ticks_ms()) > 0:
            rings = self._modem.socket_context_states[socket_id].rings
            if rings:
                payload = await self._read_ring_data_batched(socket_id)
                if payload:
                    buf.extend(payload)
                    if sentinel in buf:
                        return bytes(buf)
            else:
                await asyncio.sleep(0.1)

        return bytes(buf) if buf else None

    # ================================================================
    #  WAV CONVERSION
    # ================================================================

    @staticmethod
    def _pcm_to_wav(pcm_data, sample_rate, encoding="linear16"):
        """Wrap raw PCM sample bytes in a standard WAV (RIFF) header."""
        bps = 16 if encoding == "linear16" else 8  # bits per sample
        nch = 1
        byte_rate = sample_rate * nch * (bps // 8)
        block_align = nch * (bps // 8)
        data_size = len(pcm_data)

        # Audio format: 1 = PCM, 6 = A-law, 7 = µ-law
        if encoding == "alaw":
            fmt_code = 6
        elif encoding == "mulaw":
            fmt_code = 7
        else:
            fmt_code = 1

        header = struct.pack(
            "<4sI4s4sIHHIIHH4sI",
            b"RIFF", 36 + data_size, b"WAVE",
            b"fmt ", 16, fmt_code, nch,
            sample_rate, byte_rate, block_align, bps,
            b"data", data_size,
                     )
        return header + bytes(pcm_data)


def _parse_tts_response(response_data):
    """Parse HTTP response and extract base64 audio data from Gemini TTS."""
    try:
        header_end = response_data.find(b'\r\n\r\n')
        if header_end < 0:
            return None

        body = response_data[header_end + 4:]

        if b'Transfer-Encoding: chunked' in response_data[:header_end]:
            body = _decode_chunked(body)

        json_start = body.find(b'{')
        if json_start < 0:
            return None

        json_str = body[json_start:].decode('utf-8')

        # Try full JSON parse first
        try:
            data = json.loads(json_str)
            if "error" in data:
                print(f"Gemini TTS error: {data['error'].get('message', data['error'])}")
                return None
            return data["candidates"][0]["content"]["parts"][0]["inlineData"]["data"]
        except:
            pass

        # Fallback: extract base64 data via string search
        return _extract_b64_fallback(json_str)

    except Exception as e:
        print(f"Unexpected error parsing TTS response: {e}")
        return None


def _parse_sti_response(response_data):
    """Parse HTTP response with chunked encoding support. Returns text or None."""
    data = None
    try:
        header_end = response_data.find(b'\r\n\r\n')
        if header_end < 0:
            return None

        body = response_data[header_end + 4:]

        if b'Transfer-Encoding: chunked' in response_data[:header_end]:
            body = _decode_chunked(body)

        json_start = body.find(b'{')
        if json_start < 0:
            return None

        json_str = body[json_start:].decode('utf-8')
        data = json.loads(json_str)

        if "error" in data:
            print(f"Gemini error: {data['error'].get('message', data['error'])}")
            return None

        return data["candidates"][0]["content"]["parts"][0]["text"]

    except KeyError as e:
        print(f"Parse error - missing key: {e}")
        try:
            print(f"Response data: {json.dumps(data if data else response_data)[:500]}")
        except:
            pass
        return None
    except Exception as e:
        print(f"Parse error: {e}")
        return None

def _debug_raw_response(response_data):
    """Print raw response details at verbosity >= 2."""
    _console.print(f"Raw response ({len(response_data)} bytes):", 2)
    try:
        hdr_end = response_data.find(b'\r\n\r\n')
        if hdr_end > 0:
            _console.print(f"Headers:\n{response_data[:hdr_end].decode()}", 2)
            body = response_data[hdr_end + 4:]
            _console.print(f"Body preview: {body[:500]}", 2)
    except Exception as e:
        _console.print(f"Debug print error: {e}", 2)


def _extract_content_length(response_data, header_end_pos):
    """Parse Content-Length from HTTP headers."""
    if b'\r\n\r\n' not in response_data:
        return -1, -1

    hep = response_data.find(b'\r\n\r\n')
    headers = response_data[:hep].decode('utf-8', 'ignore').lower()

    for line in headers.split('\r\n'):
        if line.startswith('content-length:'):
            cl = int(line.split(':')[1].strip())
            _console.print(f"  Content-Length: {cl}")
            return cl, hep

    return -1, hep


def _check_body_complete(response_data, content_length, header_end_pos):
    """Check whether the full HTTP body has arrived and try to parse."""
    if content_length <= 0 or header_end_pos < 0:
        return None

    body_received = len(response_data) - (header_end_pos + 4)
    if body_received >= content_length:
        _console.print(f"  Full body received: {body_received}/{content_length}", 3)
        return _parse_tts_response(response_data)

    return None