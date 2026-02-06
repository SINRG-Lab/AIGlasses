"""
Walter ESP32-S3 MicroPython - Gemini Audio-to-Audio (LTE-M Optimized)
Sends audio directly to Gemini API for transcription/response
Forces LTE-M connection and optimized for minimum latency
Includes latency measurement + CDF output
"""

import micropython

micropython.opt_level(1)

import asyncio
import sys
import time

import ubinascii
from machine import Pin
import ujson as json
from walter_modem import Modem
from walter_modem.mixins.default_sim_network import *
from walter_modem.mixins.default_pdp import *
from walter_modem.mixins.socket import *
from walter_modem.mixins.tls_certs import TLSCertsMixin, WalterModemTlsValidation, WalterModemTlsVersion
from walter_modem.coreEnums import *
from walter_modem.coreStructs import *
from walter_modem.mixins.socket import WalterModemSocketRingMode


# ============== INITIALIZE MODEM ==============
modem = Modem(SocketMixin, TLSCertsMixin, load_default_power_saving_mixin=False)
modem_rsp = WalterModemRsp()


# ============== HELPER FUNCTIONS ==============
def load_config(filename='config.json', api_key="majd"):
    """Load configuration from JSON file into global variables."""
    global GEMINI_API_KEY, CELL_APN, APN_USERNAME, APN_PASSWORD, SIM_PIN
    global MIC_SD, MIC_WS, MIC_SCK
    global SPK_RC, SPK_BCLK, SPK_DIN, SPK_SD
    global NUM_ITERATIONS, AUDIO_FILE, AUDIO_PROMPT

    with open(filename, 'r') as f:
        config = json.load(f)

    # API and network config
    majd_key = config['majd_gemini_api_key']
    ryder_key = config['ryder_gemini_api_key1']
    if api_key == "majd":
        GEMINI_API_KEY = majd_key
    else:
        GEMINI_API_KEY = ryder_key

    CELL_APN = config['cell_apn']
    APN_USERNAME = config['apn_username']
    APN_PASSWORD = config['apn_password']
    SIM_PIN = config['sim_pin']

    # Mic config
    MIC_SD = config['mic_sd']
    MIC_WS = config['mic_ws']
    MIC_SCK = config['mic_sck']

    # Speaker config
    SPK_RC = config['spk_rc']
    SPK_BCLK = config['spk_bclk']
    SPK_DIN = config['spk_din']
    SPK_SD = config['spk_sd']

    # Other settings
    NUM_ITERATIONS = config['num_iterations']
    AUDIO_FILE = config['audio_file']
    AUDIO_PROMPT = config['audio_prompt']

    print("Configuration loaded successfully")

async def wait_for_network(timeout=180):
    for _ in range(timeout):
        state = modem.get_network_reg_state()
        if state in (WalterModemNetworkRegState.REGISTERED_HOME,
                     WalterModemNetworkRegState.REGISTERED_ROAMING):
            return True
        await asyncio.sleep(1)
    return False


async def lte_connect():
    if modem.get_network_reg_state() in (
            WalterModemNetworkRegState.REGISTERED_HOME,
            WalterModemNetworkRegState.REGISTERED_ROAMING,
    ):
        # Check if already on LTE-M
        if await modem.get_rat(rsp=modem_rsp):
            if modem_rsp.rat == WalterModemRat.LTEM:
                print("Already connected to LTE-M")
                return True

    # Force LTE-M for better throughput/latency
    print("Setting RAT to LTE-M...")
    if not await modem.set_rat(WalterModemRat.LTEM):
        print("Failed to set LTE-M RAT")
        return False

    if not await modem.set_op_state(WalterModemOpState.FULL):
        return False

    if not await modem.set_network_selection_mode(WalterModemNetworkSelMode.AUTOMATIC):
        return False

    print("Waiting for LTE-M network...")
    if not await wait_for_network(180):
        print("LTE-M connection failed")
        return False

    # Verify we're on LTE-M
    if await modem.get_rat(rsp=modem_rsp):
        print(f"Connected on: {WalterModemRat.get_value_name(modem_rsp.rat)}")

    print("Connected")
    return True


def list_files(directory="/"):
    """List files in directory for debugging"""
    import os
    try:
        print(f"Files in '{directory}':")
        for f in os.listdir(directory):
            try:
                stat = os.stat(directory + "/" + f if directory != "/" else "/" + f)
                size = stat[6]
                is_dir = stat[0] & 0x4000
                print(f"  {'[DIR]' if is_dir else ''} {f} {'' if is_dir else f'({size} bytes)'}")
            except:
                print(f"  {f}")
    except Exception as e:
        print(f"Cannot list '{directory}': {e}")


def load_wav_file(filepath):
    """Load WAV file and return audio data with base64 encoding"""
    import os

    if filepath.startswith(".."):
        cwd = os.getcwd()
        print(f"Current directory: {cwd}")
        filepath = filepath.replace("../", "/")

    if not filepath.startswith("/"):
        filepath = "/" + filepath

    print(f"Attempting to load: {filepath}")

    try:
        stat = os.stat(filepath)
        file_size = stat[6]
        print(f"File found: {file_size} bytes")
    except OSError as e:
        print(f"File not found: {filepath}")
        print(f"Error: {e}")
        list_files("/")
        return None, None

    try:
        with open(filepath, 'rb') as f:
            wav_data = f.read()

        print(f"Read {len(wav_data)} bytes")

        if len(wav_data) < 44:
            print("File too small for WAV header")
            return None, None

        if wav_data[:4] != b'RIFF' or wav_data[8:12] != b'WAVE':
            print(f"Invalid WAV format. Header: {wav_data[:12]}")
            return None, None

        channels = int.from_bytes(wav_data[22:24], 'little')
        sample_rate = int.from_bytes(wav_data[24:28], 'little')
        bits_per_sample = int.from_bytes(wav_data[34:36], 'little')

        print(f"WAV: {sample_rate}Hz, {channels}ch, {bits_per_sample}bit")

        # Base64 encode for Gemini API
        audio_b64 = ubinascii.b2a_base64(wav_data).decode('utf-8').strip()
        print(f"Base64 encoded: {len(audio_b64)} chars")

        return audio_b64, "audio/wav"

    except Exception as e:
        print(f"Error reading WAV: {e}")
        sys.print_exception(e)
        return None, None

def load_mp3_file(filepath):
    """Load MP3 file and return audio data with base64 encoding"""
    import os

    if filepath.startswith(".."):
        cwd = os.getcwd()
        print(f"Current directory: {cwd}")
        filepath = filepath.replace("../", "/")

    if not filepath.startswith("/"):
        filepath = "/" + filepath

    print(f"Attempting to load: {filepath}")

    try:
        stat = os.stat(filepath)
        file_size = stat[6]
        print(f"File found: {file_size} bytes")
    except OSError as e:
        print(f"File not found: {filepath}")
        print(f"Error: {e}")
        list_files("/")
        return None, None

    try:
        with open(filepath, 'rb') as f:
            mp3_data = f.read()

        print(f"Read {len(mp3_data)} bytes")

        if len(mp3_data) < 4:
            print("File too small for MP3")
            return None, None

        # Validate MP3: check for ID3 tag or MPEG frame sync
        has_id3 = mp3_data[:3] == b'ID3'
        has_sync = (mp3_data[0] == 0xFF and (mp3_data[1] & 0xE0) == 0xE0)
        if not has_id3 and not has_sync:
            print(f"Invalid MP3 format. Header bytes: {mp3_data[:4].hex()}")
            return None, None

        print(f"MP3 detected ({'ID3 tag' if has_id3 else 'frame sync'})")

        audio_b64 = ubinascii.b2a_base64(mp3_data).decode('utf-8').strip()
        print(f"Base64 encoded: {len(audio_b64)} chars")

        return audio_b64, "audio/mpeg"

    except Exception as e:
        print(f"Error reading MP3: {e}")
        sys.print_exception(e)
        return None, None

# ============== RESPONSE PARSER ==============
def try_parse_response(response_data):
    """Parse HTTP response with chunked encoding support"""
    try:
        header_end = response_data.find(b'\r\n\r\n')
        if header_end < 0:
            return None

        body = response_data[header_end + 4:]

        if b'Transfer-Encoding: chunked' in response_data[:header_end]:
            decoded_body = b''
            chunk_data = body
            while chunk_data:
                newline_pos = chunk_data.find(b'\r\n')
                if newline_pos == -1:
                    break
                size_str = chunk_data[:newline_pos].decode().strip()
                if not size_str:
                    break
                try:
                    chunk_size = int(size_str, 16)
                except:
                    break
                if chunk_size == 0:
                    break
                chunk_start = newline_pos + 2
                chunk_end = chunk_start + chunk_size
                if chunk_end > len(chunk_data):
                    decoded_body += chunk_data[chunk_start:]
                    break
                decoded_body += chunk_data[chunk_start:chunk_end]
                chunk_data = chunk_data[chunk_end + 2:]
            body = decoded_body

        json_start = body.find(b'{')
        if json_start < 0:
            return None

        json_bytes = body[json_start:]
        json_str = json_bytes.decode('utf-8')
        data = json.loads(json_str)

        if "error" in data:
            print(f"Gemini error: {data['error'].get('message', data['error'])}")
            return None

        return data["candidates"][0]["content"]["parts"][0]["text"]

    except KeyError as e:
        print(f"Parse error - missing key: {e}")
        try:
            print(f"Response data: {json.dumps(data)[:500]}")
        except:
            pass
        return None
    except Exception as e:
        print(f"Parse error: {e}")
        return None


# ============== SOCKET COMMUNICATION ==============
async def send_and_receive(socket_id, http_request, timeout=120):
    """Socket send/receive optimized for minimum latency on LTE-M"""

    request_bytes = http_request if isinstance(http_request, bytes) else http_request.encode('utf-8')

    # Maximum chunk size and minimal delay for fastest upload
    chunk_size = 4096  # Larger chunks = fewer round trips
    delay = 0.005  # 5ms - minimal delay between chunks

    total_size = len(request_bytes)
    print(f"Sending {total_size} bytes (chunk={chunk_size}, delay={delay}s)...")

    for i in range(0, total_size, chunk_size):
        chunk = request_bytes[i:i + chunk_size]
        if not await modem.socket_send(ctx_id=socket_id, data=chunk):
            print(f"Send failed at byte {i}")
            return None
        if i > 0 and i % 40000 == 0:
            pct = (i * 100) // total_size
            print(f"  Sent {i}/{total_size} bytes ({pct}%)...")
        await asyncio.sleep(delay)

    print(f"Request sent ({total_size} bytes), waiting for response...")
    await asyncio.sleep(0.5)  # Minimal wait before polling

    response_data = b''
    no_data_count = 0

    for i in range(timeout):
        try:
            rings = modem.socket_context_states[socket_id].rings

            if rings:
                ring = rings.pop(0)
                data_length = ring.length if ring.length else 1500

                result = await modem.socket_receive_data(
                    ctx_id=socket_id,
                    length=data_length,
                    max_bytes=min(data_length, 1500),
                    rsp=modem_rsp
                )

                if modem_rsp.socket_rcv_response:
                    payload = modem_rsp.socket_rcv_response.payload
                    if payload:
                        response_data += bytes(payload)
                        print(f"  +{len(payload)} bytes (total: {len(response_data)})")
                elif ring.data:
                    response_data += bytes(ring.data)

                no_data_count = 0

                if b'\r\n0\r\n' in response_data:
                    result = try_parse_response(response_data)
                    if result:
                        return result
            else:
                no_data_count += 1

            if response_data and no_data_count > 2:
                result = try_parse_response(response_data)
                if result:
                    return result

            if no_data_count > 15 and len(response_data) > 0:
                break
            elif no_data_count > 30:
                print("Timeout")
                break

        except Exception as e:
            print(f"  Error: {e}")

        await asyncio.sleep(0.5)

    if response_data:
        print(f"Raw response ({len(response_data)} bytes):")
        try:
            header_end = response_data.find(b'\r\n\r\n')
            if header_end > 0:
                print(f"Headers:\n{response_data[:header_end].decode()}")
                body = response_data[header_end + 4:]
                print(f"Body preview: {body[:500]}")
        except Exception as e:
            print(f"Debug print error: {e}")

        return try_parse_response(response_data)

    return None


async def setup_tls_socket(socket_id, server, port, tts=False):
    """Configure and connect a TLS socket optimized for LTE-M latency"""

    # TODO Remove once done debugging
    tts=False

    if not await modem.tls_config_profile(
            profile_id=1,
            tls_version=WalterModemTlsVersion.TLS_VERSION_12,
            tls_validation=WalterModemTlsValidation.NONE
    ):
        print("TLS config failed")
        return False

    if not await modem.socket_config(
            ctx_id=socket_id,
            pdp_ctx_id=1,
            mtu=1500,
            exchange_timeout=90,
            connection_timeout=30,
            send_delay_ms=0
    ):
        print("Socket config failed")
        return False



    if tts:
        if not await modem.socket_config_extended(
            ctx_id=socket_id,
            ring_mode=WalterModemSocketRingMode.NORMAL,
            recv_mode=WalterModemSocketRecvMode.TEXT_OR_RAW,
            keepalive=60,
            listen_auto_resp=False,
            send_mode=WalterModemSocketSendMode.TEXT_OR_RAW
        ):
            print("Socket extended config failed")
            return False
    else:
        # Set extended config back to default settings.
        if not await modem.socket_config_extended(
                ctx_id=socket_id
        ):
            print("Socked extended config failed")
            return False

    if not await modem.socket_config_secure(
            ctx_id=socket_id,
            enable=True,
            secure_profile_id=1
    ):
        print("Socket TLS config failed")
        return False

    print(f"Connecting to {server}:{port}...")
    if not await modem.socket_dial(
            ctx_id=socket_id,
            remote_addr=server,
            remote_port=port,
            protocol=WalterModemSocketProtocol.TCP
    ):
        print("Socket dial failed")
        return False

    return True


# ============== GEMINI AUDIO API ==============
async def query_gemini_audio(audio_b64, mime_type, prompt, max_tokens=512):
    """Send audio to Gemini and get text response, returns (response, latency_ms)"""

    print("\n--- Gemini Audio API ---")

    server = "generativelanguage.googleapis.com"
    port = 443
    uri = f"/v1beta/models/gemini-2.5-flash:generateContent?key={GEMINI_API_KEY}"

    # Build multimodal payload with audio + text
    payload_dict = {
        "contents": [{
            "parts": [
                {
                    "inline_data": {
                        "mime_type": mime_type,
                        "data": audio_b64
                    }
                },
                {
                    "text": prompt
                }
            ]
        }],
        "generationConfig": {
            "maxOutputTokens": max_tokens,
            "temperature": 0.7
        }
    }

    body = json.dumps(payload_dict)
    print(f"Payload size: {len(body)} bytes")

    http_request = (
        f"POST {uri} HTTP/1.1\r\n"
        f"Host: {server}\r\n"
        f"Content-Type: application/json\r\n"
        f"Content-Length: {len(body)}\r\n"
        f"Connection: close\r\n"
        f"Accept: application/json\r\n"
        f"\r\n"
        f"{body}"
    )

    socket_id = 1

    if not await setup_tls_socket(socket_id, server, port):
        return None, -1

    print("Connected to Gemini, sending audio...")

    start_time = time.ticks_ms()
    response = await send_and_receive(socket_id, http_request, timeout=120)
    latency = time.ticks_diff(time.ticks_ms(), start_time)

    try:
        await modem.socket_close(ctx_id=socket_id)
    except:
        pass

    return response, latency


# ============== TTS RESPONSE PARSER ==============
def try_parse_tts_response(response_data):
    """Parse HTTP response and extract base64 audio data from Gemini TTS"""
    try:
        header_end = response_data.find(b'\r\n\r\n')
        if header_end < 0:
            return None

        body = response_data[header_end + 4:]

        # Handle chunked encoding
        if b'Transfer-Encoding: chunked' in response_data[:header_end]:
            decoded_body = b''
            chunk_data = body
            while chunk_data:
                newline_pos = chunk_data.find(b'\r\n')
                if newline_pos == -1:
                    break
                size_str = chunk_data[:newline_pos].decode().strip()
                if not size_str:
                    break
                try:
                    chunk_size = int(size_str, 16)
                except:
                    break
                if chunk_size == 0:
                    break
                chunk_start = newline_pos + 2
                chunk_end = chunk_start + chunk_size
                if chunk_end > len(chunk_data):
                    decoded_body += chunk_data[chunk_start:]
                    break
                decoded_body += chunk_data[chunk_start:chunk_end]
                chunk_data = chunk_data[chunk_end + 2:]
            body = decoded_body

        json_start = body.find(b'{')
        if json_start < 0:
            return None

        json_bytes = body[json_start:]
        json_str = json_bytes.decode('utf-8')
        data = json.loads(json_str)

        if "error" in data:
            print(f"Gemini TTS error: {data['error'].get('message', data['error'])}")
            return None

        # Extract base64 audio data from response
        audio_b64 = data["candidates"][0]["content"]["parts"][0]["inlineData"]["data"]
        return audio_b64

    except KeyError as e:
        print(f"TTS parse error - missing key: {e}")
        try:
            print(f"Response data: {json.dumps(data)[:500]}")
        except:
            pass
        return None
    except Exception as e:
        print(f"TTS parse error: {e}")
        return None

async def _tts_send_request(socket_id, http_request):
    """Send the HTTP request over the socket. Returns True on success."""
    request_bytes = http_request if isinstance(http_request, bytes) else http_request.encode('utf-8')
    chunk_size = 4096
    delay = 0.005
    total_size = len(request_bytes)

    print(f"Sending TTS request {total_size} bytes...")

    for i in range(0, total_size, chunk_size):
        chunk = request_bytes[i:i + chunk_size]

        if not await modem.socket_send(ctx_id=socket_id, data=chunk):
            print(f"Send failed at byte {i}")
            return False

        if i > 0 and i % 40000 == 0:
            pct = (i * 100) // total_size
            print(f"  Sent {i}/{total_size} bytes ({pct}%)...")

        await asyncio.sleep(delay)

    print(f"TTS request sent, waiting for audio response...")
    return True


def _extract_content_length(response_data, header_end_pos, verbose=False):
    """Parse Content-Length from headers. Returns (content_length, header_end_pos)."""
    if b'\r\n\r\n' not in response_data:
        return -1, -1

    header_end_pos = response_data.find(b'\r\n\r\n')
    headers = response_data[:header_end_pos].decode('utf-8', 'ignore').lower()

    for line in headers.split('\r\n'):
        if line.startswith('content-length:'):
            cl = int(line.split(':')[1].strip())

            if verbose:
                print(f"  Content-Length: {cl}")

            return cl, header_end_pos

    return -1, header_end_pos


def _check_body_complete(response_data, content_length, header_end_pos, verbose=False):
    """If Content-Length is known, check whether the full body has arrived and try to parse."""
    if content_length <= 0 or header_end_pos < 0:
        return None

    body_received = len(response_data) - (header_end_pos + 4)

    if body_received >= content_length:

        if verbose:
            print(f"  Full body received: {body_received}/{content_length}")

        return try_parse_tts_response(response_data)

    return None


async def _read_ring_data(socket_id, ring, verbose=False):
    """Read payload from a single ring notification. Returns bytes or None."""
    data_length = ring.length if ring.length else 1500

    if verbose:
        print(f"  Ring data_length={data_length}, ring.length={ring.length}")
        print(f"  Calling socket_receive_data for socket {socket_id} with {data_length} bytes...")

    try:
        await modem.socket_receive_data(
            ctx_id=socket_id,
            length=data_length,
            max_bytes=min(data_length, 1500),
            rsp=modem_rsp
        )

        if verbose:
            print(f"  socket_receive_data returned")

        if modem_rsp.socket_rcv_response and modem_rsp.socket_rcv_response.payload:
            return bytes(modem_rsp.socket_rcv_response.payload)

        if ring.data:
            return bytes(ring.data)
    except ValueError:
        print(f"  ValueError in receive, using ring.data directly")

        if ring.data:
            return bytes(ring.data)

    return None


async def _check_socket_closed(socket_id, response_data, verbose=False):
    """Actively query socket status; if closed, attempt to parse the response."""
    try:
        status_rsp = WalterModemRsp()
        await modem.socket_status(ctx_id=socket_id, rsp=status_rsp)

        if status_rsp.socket_status:
            sock_state = status_rsp.socket_status.state
            if verbose:
                print(f"  Socket status: {sock_state}")
            if sock_state == WalterModemSocketState.CLOSED or sock_state == 0:
                if verbose:
                    print(f"  Server closed connection, parsing response")
                return try_parse_tts_response(response_data)

        if not modem.socket_context_states[socket_id].connected:
            if verbose:
                print(f"  Socket no longer connected")
            return try_parse_tts_response(response_data)
    except Exception as e:
        if verbose:
            print(f"  Socket status check error: {e}")

    return None

async def tts_send_and_receive(socket_id, http_request, timeout=500, verbose=True):
    """Socket send/receive for TTS, returns base64 audio data."""
    if not await _tts_send_request(socket_id, http_request):
        return None

    await asyncio.sleep(0.5)

    response_data = b''
    content_length = -1
    header_end_pos = -1
    no_data_count = 0
    start = time.ticks_ms()

    while time.ticks_diff(time.ticks_ms(), start) < (timeout * 1000):
        try:
            rings = modem.socket_context_states[socket_id].rings
            print(f"Rings available: {len(rings) if rings else 0} | no_data_count: {no_data_count}")

            if not rings:
                no_data_count += 1

                if response_data and no_data_count > 5:
                    result = await _check_socket_closed(socket_id, response_data, verbose)

                    if result:
                        return result

                await asyncio.sleep(0.1)
                continue

            # Process one ring
            ring = rings.pop(0)
            payload = await _read_ring_data(socket_id, ring, verbose)
            if payload:
                response_data += payload
                print(f"  +{len(payload)} bytes (total: {len(response_data)})")

            # Detect content-length once headers arrive
            if content_length < 0:
                content_length, header_end_pos = _extract_content_length(
                    response_data, header_end_pos, verbose
                )

            # Check if full body received
            result = _check_body_complete(response_data, content_length, header_end_pos, verbose)
            if result:
                return result

            no_data_count = 0
            await asyncio.sleep(0.01)

        except Exception as e:
            print(f"  TTS error: {e}")
            await asyncio.sleep(0.5)

    if response_data:
        print(f"Raw TTS response ({len(response_data)} bytes)")
        print(f"Last 200 bytes: {response_data[-200:]}")
        return try_parse_tts_response(response_data)

    return None


def decode_pcm_to_wav(pcm_b64, sample_rate=24000, channels=1, bits_per_sample=16):
    """Convert base64 PCM data to WAV format bytes"""
    pcm_data = ubinascii.a2b_base64(pcm_b64)

    # WAV header parameters
    byte_rate = sample_rate * channels * (bits_per_sample // 8)
    block_align = channels * (bits_per_sample // 8)
    data_size = len(pcm_data)
    file_size = 36 + data_size

    # Build WAV header (44 bytes)
    wav_header = bytearray(44)

    # RIFF chunk
    wav_header[0:4] = b'RIFF'
    wav_header[4:8] = file_size.to_bytes(4, 'little')
    wav_header[8:12] = b'WAVE'

    # fmt subchunk
    wav_header[12:16] = b'fmt '
    wav_header[16:20] = (16).to_bytes(4, 'little')  # Subchunk1Size (16 for PCM)
    wav_header[20:22] = (1).to_bytes(2, 'little')  # AudioFormat (1 = PCM)
    wav_header[22:24] = channels.to_bytes(2, 'little')
    wav_header[24:28] = sample_rate.to_bytes(4, 'little')
    wav_header[28:32] = byte_rate.to_bytes(4, 'little')
    wav_header[32:34] = block_align.to_bytes(2, 'little')
    wav_header[34:36] = bits_per_sample.to_bytes(2, 'little')

    # data subchunk
    wav_header[36:40] = b'data'
    wav_header[40:44] = data_size.to_bytes(4, 'little')

    return bytes(wav_header) + pcm_data


# ============== GEMINI TTS API ==============
async def gemini_tts(text, voice="Kore", verbose=False):
    """
    Convert text to speech using Gemini TTS API

    Args:
        text: Text to convert to speech
        voice: Voice name (default "Kore")
               Available voices: Aoede, Charon, Fenrir, Kore, Puck, etc.

    Returns:
        tuple: (wav_bytes, latency_ms) or (None, -1) on failure
               wav_bytes is the complete WAV file as bytes
    """

    print("\n--- Gemini TTS API ---")
    print(f"Text: {text[:100]}..." if len(text) > 100 else f"Text: {text}")
    print(f"Voice: {voice}")

    server = "generativelanguage.googleapis.com"
    port = 443
    uri = f"/v1beta/models/gemini-2.5-flash-preview-tts:generateContent?key={GEMINI_API_KEY}"

    # Build TTS payload
    payload_dict = {
        "contents": [{
            "parts": [{
                "text": text
            }]
        }],
        "generationConfig": {
            "responseModalities": ["AUDIO"],
            "speechConfig": {
                "voiceConfig": {
                    "prebuiltVoiceConfig": {
                        "voiceName": voice
                    }
                }
            }
        }
    }

    body = json.dumps(payload_dict)
    print(f"TTS payload size: {len(body)} bytes")

    http_request = (
        f"POST {uri} HTTP/1.1\r\n"
        f"Host: {server}\r\n"
        f"Content-Type: application/json\r\n"
        f"Content-Length: {len(body)}\r\n"
        f"Connection: close\r\n"
        f"Accept: application/json\r\n"
        f"\r\n"
        f"{body}"
    )

    socket_id = 2

    if not await setup_tls_socket(socket_id, server, port, tts=True):
        return None, -1

    print("Connected to Gemini TTS, sending request...")

    start_time = time.ticks_ms()
    audio_b64 = await tts_send_and_receive(socket_id, http_request, timeout=1000, verbose=True)
    latency = time.ticks_diff(time.ticks_ms(), start_time)

    try:
        await modem.socket_close(ctx_id=socket_id)
    except:
        pass

    if audio_b64:
        print(f"Received audio data: {len(audio_b64)} base64 chars")
        # Convert PCM to WAV (Gemini returns 24kHz, 16-bit, mono PCM)
        wav_bytes = decode_pcm_to_wav(audio_b64, sample_rate=24000, channels=1, bits_per_sample=16)
        print(f"WAV size: {len(wav_bytes)} bytes")
        return wav_bytes, latency

    return None, -1


async def save_tts_audio(wav_bytes, filepath="/tts_output.wav"):
    """Save WAV bytes to file"""
    try:
        with open(filepath, 'wb') as f:
            f.write(wav_bytes)
        print(f"Audio saved to {filepath}")
        return True
    except Exception as e:
        print(f"Failed to save audio: {e}")
        return False


# ============== SETUP ==============
async def setup():
    print("Loading Config")
    load_config(api_key="ryder")

    print("\n" + "=" * 50)
    print("Walter - Gemini Audio-to-Audio (LTE-M Optimized)")
    print("=" * 50)
    print("Settings: chunk=4096, delay=5ms, send_delay=0ms")

    await modem.begin()

    if not await modem.check_comm():
        print("Modem failed!")
        return False
    print("Modem OK")

    if SIM_PIN and not await modem.unlock_sim(pin=SIM_PIN):
        return False

    if not await modem.create_PDP_context(apn=CELL_APN):
        print("PDP context failed")
        return False

    if APN_USERNAME:
        await modem.set_PDP_auth_params(
            protocol=WalterModemPDPAuthProtocol.PAP,
            user_id=APN_USERNAME,
            password=APN_PASSWORD
        )

    if not await lte_connect():
        return False

    return True


# ============== MAIN ==============
async def main():
    try:
        if not await setup():
            raise RuntimeError("Setup failed")

        print("\nReady for Gemini audio queries!")

        # Load audio file
        if AUDIO_FILE[-3:] == "mp3":
            audio_b64, mime_type = load_mp3_file(AUDIO_FILE)
        elif AUDIO_FILE[-3:] == "wav":
            audio_b64, mime_type = load_wav_file(AUDIO_FILE)
        else:
            raise RuntimeError(f"{AUDIO_FILE} is in an invalid format to be loaded as audio.")

        if not audio_b64:
            raise RuntimeError(f"Cannot load {AUDIO_FILE}")

        print(f"\nRunning {NUM_ITERATIONS} iterations...")

        latencies = []

        for i in range(NUM_ITERATIONS):
            print("\n" + "=" * 50)
            print(f"Gemini Audio Query - Iteration {i + 1}/{NUM_ITERATIONS}")
            print("=" * 50)

            response, latency = await query_gemini_audio(audio_b64, mime_type, AUDIO_PROMPT)

            if response and latency > 0:
                latencies.append(latency)
                print(f"\n--- RESULT ---")
                print(f"Gemini: {response[:200]}..." if len(response) > 200 else f"Gemini: {response}")
                print(f"Latency: {latency} ms")
                await asyncio.sleep(1)
                tts_audio, latency = await gemini_tts(response)
                if tts_audio is not None and latency > 1:
                    print(f"Audio recieved from Gemini")
                else:
                    print("TTS Query FAILED")
                    break
            else:
                print("Query FAILED")
                break

            await asyncio.sleep(1)  # Reduced cooldown between iterations

        if len(latencies)/NUM_ITERATIONS < 0.3:
            print("Fewer than 30% of runs were successful\nTest FAILED")
            return None


        # Print results + CDF plotting code
        print("\n" + "=" * 50)
        print("BENCHMARK COMPLETE")
        print("=" * 50)
        print(f"Success: {len(latencies)}/{NUM_ITERATIONS}")
        print("=" * 50)
        print("Exiting...")

    except Exception as err:
        print("ERROR:")
        sys.print_exception(err)


asyncio.run(main())

