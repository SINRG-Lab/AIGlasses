import asyncio
import websockets
import numpy as np
import soundfile as sf
from faster_whisper import WhisperModel
from openai import OpenAI
import os
import subprocess
import tempfile
import wave

PORT = 8765
SAMPLE_RATE = 22050  # TTS output sample rate

# STT (local)
whisper = WhisperModel("base", device="cpu", compute_type="int8")

# AI (cloud)
client = OpenAI(api_key=os.environ.get("OPENAI_API_KEY"))

SYSTEM_PROMPT = (
    "You are a helpful voice assistant for smart glasses. "
    "Keep answers short (1-2 sentences). "
    "If the user asks for current facts (like who is president), say you may be outdated "
    "unless you were provided updated info."
)

CHUNK_BYTES = 4096  # audio chunk size to send back (smaller chunks for better streaming)


def text_to_pcm_s16le_mono_22050(text: str) -> bytes:
    """
    Uses macOS 'say' (free/offline) -> AIFF, then 'afconvert' -> WAV 16-bit mono 22050Hz.
    Then extracts PCM frames from WAV.
    Also saves a copy as response.wav for playback.
    """
    aiff_path = "response.aiff"
    wav_path = "response.wav"

    # 1) TTS -> AIFF
    subprocess.check_call(["say", text, "-o", aiff_path])

    # 2) Convert to WAV, 16-bit LE, mono, 22050Hz
    subprocess.check_call([
        "afconvert", aiff_path,
        "-f", "WAVE",
        "-d", f"LEI16@{SAMPLE_RATE}",
        "-c", "1",
        wav_path
    ])

    # 3) Read WAV PCM
    with wave.open(wav_path, "rb") as wf:
        assert wf.getnchannels() == 1
        assert wf.getsampwidth() == 2
        assert wf.getframerate() == SAMPLE_RATE
        pcm = wf.readframes(wf.getnframes())

    # Clean up AIFF
    os.remove(aiff_path)

    print(f"💾 Saved TTS output: {wav_path}")

    return pcm


async def send_audio_to_client(ws, pcm: bytes):
    """Send audio chunks to ESP32 with detailed logging"""
    total_bytes = len(pcm)
    print(f"📊 Total audio size: {total_bytes} bytes ({total_bytes / (SAMPLE_RATE * 2):.2f} seconds)")

    chunk_count = 0
    for i in range(0, len(pcm), CHUNK_BYTES):
        chunk = pcm[i:i+CHUNK_BYTES]
        msg = b"A" + chunk
        await ws.send(msg)
        chunk_count += 1

        # Log progress every 10 chunks
        if chunk_count % 10 == 0:
            progress = (i / total_bytes) * 100
            print(f"📤 Sent chunk {chunk_count} ({progress:.1f}% complete)")

        await asyncio.sleep(0.01)  # Small delay to prevent overwhelming ESP32

    await ws.send(b"E")  # End marker
    print(f"✅ Sent {chunk_count} audio chunks + END marker")


async def handler(ws):
    print("✅ ESP32 connected")
    audio_chunks = []

    try:
        async for msg in ws:
            if not isinstance(msg, (bytes, bytearray)) or len(msg) < 1:
                continue

            tag = msg[0:1]
            payload = msg[1:]

            if tag == b"A":
                # Receiving audio from ESP32
                audio_chunks.append(payload)
                # Log incoming audio periodically
                if len(audio_chunks) % 50 == 0:
                    total_bytes = sum(len(c) for c in audio_chunks)
                    print(f"🎤 Receiving audio... {len(audio_chunks)} chunks ({total_bytes} bytes)")

            elif tag == b"E":
                # End of utterance - process speech
                raw = b"".join(audio_chunks)
                total_bytes = len(raw)
                duration = total_bytes / (16000 * 2)
                print(f"🎤 Received complete utterance: {total_bytes} bytes ({duration:.2f} seconds)")
                audio_chunks.clear()

                if len(raw) < 16000 * 2:
                    print("⚠️ Too short, ignoring")
                    continue

                # Convert to float32 and save
                pcm = np.frombuffer(raw, dtype=np.int16).astype(np.float32) / 32768.0
                sf.write("utterance.wav", pcm, 16000)
                print("🎧 Saved: utterance.wav")

                # Transcribe
                print("🧠 Transcribing...")
                segments, _info = whisper.transcribe("utterance.wav", language="en")
                user_text = "".join(seg.text for seg in segments).strip()

                print("\n" + "="*50)
                print("👤 USER SAID:")
                print(user_text)
                print("="*50 + "\n")

                # Get AI response
                print("🤖 Getting AI response...")
                ai = client.responses.create(
                    model="gpt-4.1-mini",
                    input=[
                        {"role": "system", "content": SYSTEM_PROMPT},
                        {"role": "user", "content": user_text},
                    ],
                )
                answer = ai.output_text.strip()

                print("="*50)
                print("🤖 AI RESPONSE:")
                print(answer)
                print("="*50 + "\n")

                # Convert AI answer to speech
                print("🔊 Converting text to speech...")
                tts_pcm = text_to_pcm_s16le_mono_22050(answer)

                # Send audio back to ESP32
                print("📤 Sending audio to ESP32...")
                await send_audio_to_client(ws, tts_pcm)
                print("✅ Complete! Ready for next utterance.\n")

    except websockets.exceptions.ConnectionClosed:
        print("❌ ESP32 disconnected")
    except Exception as e:
        print(f"❌ Error: {e}")
        import traceback
        traceback.print_exc()


async def main():
    print("="*60)
    print(f"🎙️  WebSocket Voice Assistant Server")
    print(f"📡 Listening on ws://0.0.0.0:{PORT}")
    print(f"🔊 TTS Sample Rate: {SAMPLE_RATE} Hz")
    print(f"📦 Chunk Size: {CHUNK_BYTES} bytes")
    print("="*60 + "\n")

    async with websockets.serve(handler, "0.0.0.0", PORT, max_size=20_000_000):
        await asyncio.Future()


if __name__ == "__main__":
    asyncio.run(main())
