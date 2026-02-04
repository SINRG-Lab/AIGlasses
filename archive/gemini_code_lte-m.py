"""
Walter ESP32-S3 MicroPython - Gemini Audio-to-Text (LTE-M Optimized)
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
import ujson as json
from walter_modem import Modem
from walter_modem.mixins.default_sim_network import *
from walter_modem.mixins.default_pdp import *
from walter_modem.mixins.socket import *
from walter_modem.mixins.tls_certs import TLSCertsMixin, WalterModemTlsValidation, WalterModemTlsVersion
from walter_modem.coreEnums import *
from walter_modem.coreStructs import *

# ============== CONFIGURATION ==============
GEMINI_API_KEY = "AIzaSyCk26YpfMo2nYXirtuBtNjWvo_ISsT5nnc"
CELL_APN = ""
APN_USERNAME = ""
APN_PASSWORD = ""
SIM_PIN = None

NUM_ITERATIONS = 1
WAV_FILE = "/msg_tiny.wav"

# Prompt for audio processing
AUDIO_PROMPT = """Listen to this audio and respond to the user's question or request. 
If it's a question, answer it directly. If it's a statement, acknowledge and respond appropriately.
Keep your response concise and helpful."""

# ============== INITIALIZE MODEM ==============
modem = Modem(SocketMixin, TLSCertsMixin, load_default_power_saving_mixin=False)
modem_rsp = WalterModemRsp()

# ============== HELPER FUNCTIONS ==============
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
    
    print("Connected!")
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

# ============== RESPONSE PARSER ==============
def try_parse_response(response_data):
    """Parse HTTP response with chunked encoding support"""
    try:
        header_end = response_data.find(b'\r\n\r\n')
        if header_end < 0:
            return None
        
        body = response_data[header_end+4:]
        
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
                chunk_data = chunk_data[chunk_end+2:]
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
    delay = 0.005      # 5ms - minimal delay between chunks
    
    total_size = len(request_bytes)
    print(f"Sending {total_size} bytes (chunk={chunk_size}, delay={delay}s)...")
    
    for i in range(0, total_size, chunk_size):
        chunk = request_bytes[i:i+chunk_size]
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
        
        await asyncio.sleep(0.5)  # Faster polling (500ms instead of 1s)
    
    if response_data:
        print(f"Raw response ({len(response_data)} bytes):")
        try:
            header_end = response_data.find(b'\r\n\r\n')
            if header_end > 0:
                print(f"Headers:\n{response_data[:header_end].decode()}")
                body = response_data[header_end+4:]
                print(f"Body preview: {body[:500]}")
        except Exception as e:
            print(f"Debug print error: {e}")
        
        return try_parse_response(response_data)
    
    return None

async def setup_tls_socket(socket_id, server, port):
    """Configure and connect a TLS socket optimized for LTE-M latency"""
    
    if not await modem.tls_config_profile(
        profile_id=1,
        tls_version=WalterModemTlsVersion.TLS_VERSION_12,
        tls_validation=WalterModemTlsValidation.NONE
    ):
        print("TLS config failed")
        return False
    
    # Optimized socket config for minimum latency
    if not await modem.socket_config(
        ctx_id=socket_id,
        pdp_ctx_id=1,
        mtu=1500,
        exchange_timeout=90,
        connection_timeout=30,   # Faster connection timeout
        send_delay_ms=0          # No send delay - fastest possible
    ):
        print("Socket config failed")
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

# ============== SETUP ==============
async def setup():
    print("\n" + "="*50)
    print("Walter - Gemini Audio-to-Text (LTE-M Optimized)")
    print("="*50)
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
        
        # Load and encode WAV file once
        audio_b64, mime_type = load_wav_file(WAV_FILE)
        if not audio_b64:
            raise RuntimeError(f"Cannot load {WAV_FILE}")
        
        print(f"\nRunning {NUM_ITERATIONS} iterations...")
        
        latencies = []
        
        for i in range(NUM_ITERATIONS):
            print("\n" + "="*50)
            print(f"Gemini Audio Query - Iteration {i+1}/{NUM_ITERATIONS}")
            print("="*50)
            
            response, latency = await query_gemini_audio(audio_b64, mime_type, AUDIO_PROMPT)
            
            if response and latency > 0:
                latencies.append(latency)
                print(f"\n--- RESULT ---")
                print(f"Gemini: {response[:200]}..." if len(response) > 200 else f"Gemini: {response}")
                print(f"Latency: {latency} ms")
            else:
                print("Query FAILED")
            
            await asyncio.sleep(1)  # Reduced cooldown between iterations
        
        # Print results + CDF plotting code
        print("\n" + "="*50)
        print("BENCHMARK COMPLETE")
        print("="*50)
        print(f"Success: {len(latencies)}/{NUM_ITERATIONS}")
        
        print("\n" + "="*50)
        print("COPY EVERYTHING BELOW TO PLOT CDF ON LAPTOP:")
        print("="*50)
        print("""
import numpy as np
import matplotlib.pyplot as plt

# ESP32 Gemini Audio-to-Text Latencies (LTE-M) - in milliseconds
latencies = [""")
        print("    " + ", ".join(str(lat) for lat in latencies))
        print("""]

# Compute CDF
sorted_data = np.sort(latencies)
cdf = np.arange(1, len(sorted_data) + 1) / len(sorted_data)

# Plot
fig, ax = plt.subplots(figsize=(10, 6))
ax.plot(sorted_data, cdf, 'm-', linewidth=2, label='ESP32 (LTE-M) - Gemini Audio-to-Text')
ax.scatter(sorted_data, cdf, c='purple', s=30, alpha=0.6)
ax.axhline(y=0.5, color='gray', linestyle=':', alpha=0.5)
ax.axhline(y=0.95, color='gray', linestyle=':', alpha=0.5)
ax.set_xlabel('Latency (ms)', fontsize=12)
ax.set_ylabel('CDF', fontsize=12)
ax.set_title('ESP32 Gemini Audio-to-Text Latency CDF (over LTE-M)', fontsize=14)
ax.legend(loc='lower right')
ax.grid(True, alpha=0.3)
ax.set_ylim(0, 1.05)

# Stats
print(f"Mean: {np.mean(latencies):.1f} ms")
print(f"Median: {np.median(latencies):.1f} ms")
print(f"P95: {np.percentile(latencies, 95):.1f} ms")
print(f"Std: {np.std(latencies):.1f} ms")

plt.tight_layout()
plt.savefig('cdf_esp32_gemini_audio_ltem.png', dpi=150)
plt.show()
""")
        print("="*50)
        
        print("\nDone!")
        
    except Exception as err:
        print("ERROR:")
        sys.print_exception(err)

asyncio.run(main())