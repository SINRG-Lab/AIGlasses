"""
Helper utilities for Gemini audio API integration on Walter ESP32-S3.
Pure data transformation functions with no modem dependency.
"""

import sys
import ubinascii
import ujson as json

class _Console:
    """
    Module-private console for controlling print verbosity.

    Levels:
        0 - Initial settings only
        1 - Periodic status messages (default)
        2 - Debug messages, ring polling details
        3 - UART-level debug output
    """

    def __init__(self, verbosity: int = 1):
        self.verbosity = verbosity

    def print(self, msg: str, verb_thr: int = 1):
        if verb_thr <= self.verbosity:
            print(msg)

_console = _Console()

def load_config(filename='config.json', api_key="majd") -> dict:
    """Load configuration from JSON file and return as dict."""
    with open(filename, 'r') as f:
        config = json.load(f)

    if api_key == "majd":
        config['gemini_api_key'] = config['majd_gemini_api_key']
    else:
        config['gemini_api_key'] = config['ryder_gemini_api_key1']

    return config


def list_files(directory="/"):
    """List files in directory for debugging."""
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
    """Load WAV file and return (base64_string, mime_type) or (None, None)."""
    import os

    filepath = _normalize_path(filepath)

    try:
        stat = os.stat(filepath)
        file_size = stat[6]
    except OSError as e:
        print(f"File not found: {filepath} ({e})")
        list_files("/")
        return None, None

    try:
        with open(filepath, 'rb') as f:
            wav_data = f.read()

        if len(wav_data) < 44:
            print("File too small for WAV header")
            return None, None

        if wav_data[:4] != b'RIFF' or wav_data[8:12] != b'WAVE':
            print(f"Invalid WAV format. Header: {wav_data[:12]}")
            return None, None

        audio_b64 = ubinascii.b2a_base64(wav_data).decode('utf-8').strip()
        return audio_b64, "audio/wav"

    except Exception as e:
        print(f"Error reading WAV: {e}")
        sys.print_exception(e)
        return None, None


def load_mp3_file(filepath):
    """Load MP3 file and return (base64_string, mime_type) or (None, None)."""
    import os

    filepath = _normalize_path(filepath)

    try:
        stat = os.stat(filepath)
        file_size = stat[6]
    except OSError as e:
        print(f"File not found: {filepath} ({e})")
        list_files("/")
        return None, None

    try:
        with open(filepath, 'rb') as f:
            mp3_data = f.read()

        if len(mp3_data) < 4:
            print("File too small for MP3")
            return None, None

        has_id3 = mp3_data[:3] == b'ID3'
        has_sync = (mp3_data[0] == 0xFF and (mp3_data[1] & 0xE0) == 0xE0)
        if not has_id3 and not has_sync:
            print(f"Invalid MP3 format. Header bytes: {mp3_data[:4].hex()}")
            return None, None

        audio_b64 = ubinascii.b2a_base64(mp3_data).decode('utf-8').strip()
        return audio_b64, "audio/mpeg"

    except Exception as e:
        print(f"Error reading MP3: {e}")
        sys.print_exception(e)
        return None, None


def load_audio_file(filepath):
    """Load audio file based on extension. Returns (base64_string, mime_type)."""
    if filepath.endswith("mp3"):
        return load_mp3_file(filepath)
    elif filepath.endswith("wav"):
        return load_wav_file(filepath)
    else:
        raise ValueError(f"Unsupported audio format: {filepath}")


def decode_pcm_to_wav(pcm_b64, sample_rate=24000, channels=1, bits_per_sample=16):
    """Convert base64 PCM data to WAV format bytes."""
    pcm_data = ubinascii.a2b_base64(pcm_b64)

    byte_rate = sample_rate * channels * (bits_per_sample // 8)
    block_align = channels * (bits_per_sample // 8)
    data_size = len(pcm_data)
    file_size = 36 + data_size

    hdr = bytearray(44)
    hdr[0:4] = b'RIFF'
    hdr[4:8] = file_size.to_bytes(4, 'little')
    hdr[8:12] = b'WAVE'
    hdr[12:16] = b'fmt '
    hdr[16:20] = (16).to_bytes(4, 'little')
    hdr[20:22] = (1).to_bytes(2, 'little')
    hdr[22:24] = channels.to_bytes(2, 'little')
    hdr[24:28] = sample_rate.to_bytes(4, 'little')
    hdr[28:32] = byte_rate.to_bytes(4, 'little')
    hdr[32:34] = block_align.to_bytes(2, 'little')
    hdr[34:36] = bits_per_sample.to_bytes(2, 'little')
    hdr[36:40] = b'data'
    hdr[40:44] = data_size.to_bytes(4, 'little')

    return bytes(hdr) + pcm_data


# ============== PRIVATE HELPERS ==============

def _normalize_path(filepath):
    """Normalize file path for MicroPython filesystem."""
    if filepath.startswith(".."):
        filepath = filepath.replace("../", "/")
    if not filepath.startswith("/"):
        filepath = "/" + filepath
    return filepath


def _decode_chunked(body):
    """Decode HTTP chunked transfer encoding."""
    decoded = b''
    chunk_data = body
    while chunk_data:
        nl = chunk_data.find(b'\r\n')
        if nl == -1:
            break
        size_str = chunk_data[:nl].decode().strip()
        if not size_str:
            break
        try:
            chunk_size = int(size_str, 16)
        except:
            break
        if chunk_size == 0:
            break
        start = nl + 2
        end = start + chunk_size
        if end > len(chunk_data):
            decoded += chunk_data[start:]
            break
        decoded += chunk_data[start:end]
        chunk_data = chunk_data[end + 2:]
    return decoded


def _extract_b64_fallback(json_str):
    """Extract base64 audio data from possibly truncated JSON via string search."""
    marker = '"data": "'
    idx = json_str.find(marker)
    if idx < 0:
        marker = '"data":"'
        idx = json_str.find(marker)
    if idx < 0:
        return None

    start = idx + len(marker)
    end = json_str.find('"', start)
    if end > start:
        return json_str[start:end]

    # Handle truncated response
    raw = json_str[start:]
    valid = 'ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789+/='
    while raw and raw[-1] not in valid:
        raw = raw[:-1]
    raw = raw[:len(raw) - (len(raw) % 4)]
    if len(raw) > 100:
        return raw

    return None


async def save_wav(wav_bytes, filepath="/tts_output.wav") -> bool:
    """Save WAV bytes to file."""
    try:
        with open(filepath, 'wb') as f:
            f.write(wav_bytes)
        _console.print(f"Audio saved to {filepath}")
        return True
    except Exception as e:
        _console.print(f"Failed to save audio: {e}")
        return False
