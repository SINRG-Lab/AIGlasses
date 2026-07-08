#!/usr/bin/env python3
"""AI Glasses bench webapp — camera viewfinder + voice loop over USB serial.

One-shot (default): grabs a frame, saves photo_<timestamp>.jpg, opens it.
Live mode:          view_camera.py --live   -> http://localhost:8000
                    ~1 fps viewfinder + a "Talk" button that runs the voice
                    loop: glasses mic -> gpt-4o-mini-transcribe ->
                    gpt-4o-mini-tts -> glasses speakers.

Requires: pip3 install pyserial, and OPENAI_API_KEY in the environment
          (or in repo/AIglasses/secrets.properties) for the Talk button.
NOTE: close the Arduino Serial Monitor first — only one program owns the port.
"""
import argparse
import base64
import glob
import http.server
import json
import os
import ssl
import struct
import subprocess
import sys
import tempfile
import threading
import time
import urllib.request
import uuid
from datetime import datetime

try:
    import serial
except ImportError:
    sys.exit("pyserial is missing — run: pip3 install pyserial")

# macOS python.org builds ship without linked CA certs — use certifi's bundle
# so the OpenAI HTTPS calls verify properly.
try:
    import certifi
    SSL_CTX = ssl.create_default_context(cafile=certifi.where())
except ImportError:
    SSL_CTX = ssl.create_default_context()

B64_CHARS = set("ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789+/=")
STT_MODEL = "gpt-4o-mini-transcribe-2025-12-15"
TTS_MODEL = "gpt-4o-mini-tts"
TTS_VOICE = "alloy"

SER = None                      # the one serial connection
SER_LOCK = threading.Lock()     # frame loop and /talk share the port

# Always-on GPT Realtime session (realtime_bridge.py)
import realtime_bridge
VOICE_STATUS = {"on": False, "state": "off", "user": "", "assistant": ""}
VOICE_SESSION = None


# ── serial helpers ──────────────────────────────────────────────

def find_port(explicit):
    if explicit:
        return explicit
    ports = sorted(glob.glob("/dev/cu.usbmodem*")) + sorted(glob.glob("/dev/ttyACM*"))
    if not ports:
        sys.exit("No USB serial port found — is the XIAO plugged in? (or pass --port)")
    if len(ports) > 1:
        print(f"Multiple ports, using {ports[0]} (others: {ports[1:]})")
    return ports[0]


def read_framed(ser, start_tag, timeout_s):
    """Collect base64 between <<<TAG and >>>END, return decoded bytes."""
    chunks, collecting = [], False
    deadline = time.time() + timeout_s
    while time.time() < deadline:
        line = ser.readline().decode(errors="replace").strip()
        if not line:
            continue
        if line.startswith(start_tag):
            collecting, chunks = True, []
            continue
        if line.startswith(">>>END"):
            try:
                return base64.b64decode("".join(chunks))
            except Exception as e:
                print(f"decode error: {e}")
                return None
        if collecting and all(c in B64_CHARS for c in line):
            chunks.append(line)
    return None


def grab_frame(ser, timeout_s=15):
    ser.reset_input_buffer()
    ser.write(b"p")
    data = read_framed(ser, "<<<JPEG", timeout_s)
    if data and data[:2] != b"\xff\xd8":
        print("warning: payload is not a JPEG (bad SOI)")
    return data


def record_from_glasses(ser):
    """'R' -> 4 s of 16 kHz 16-bit mono PCM from the glasses mic."""
    ser.reset_input_buffer()
    ser.write(b"R")
    return read_framed(ser, "<<<PCM", timeout_s=25)


def play_on_glasses(ser, pcm24k):
    """'P' + u32 LE length + raw 24 kHz PCM -> plays on the speakers.

    Paced in 8 KB chunks: the ESP's USB-CDC RX ring (32 KB after the firmware
    fix, 256 bytes stock!) drops bytes silently if we outrun it.
    """
    ser.write(b"P" + struct.pack("<I", len(pcm24k)))
    for off in range(0, len(pcm24k), 8192):
        ser.write(pcm24k[off:off + 8192])
        ser.flush()
        time.sleep(0.02)
    deadline = time.time() + len(pcm24k) / 48000 + 25
    while time.time() < deadline:
        line = ser.readline().decode(errors="replace").strip()
        if not line:
            continue
        print(f"glasses: {line}")
        if "[PLAY] done" in line:
            return True
        if "[PLAY]" in line and ("timeout" in line or "bad" in line or "FAILED" in line):
            return False
    print("gave up waiting for [PLAY] done")
    return False


# ── OpenAI (stdlib urllib, no extra deps) ───────────────────────

def load_api_key():
    key = os.environ.get("OPENAI_API_KEY", "").strip()
    if key:
        return key
    secrets = os.path.join(os.path.dirname(__file__), "..", "repo", "AIglasses", "secrets.properties")
    if os.path.exists(secrets):
        for line in open(secrets):
            if line.startswith("OPENAI_API_KEY"):
                return line.split("=", 1)[1].strip()
    return None


def wav_wrap(pcm, rate):
    return (b"RIFF" + struct.pack("<I", 36 + len(pcm)) + b"WAVEfmt " +
            struct.pack("<IHHIIHH", 16, 1, 1, rate, rate * 2, 2, 16) +
            b"data" + struct.pack("<I", len(pcm)) + pcm)


def openai_stt(key, pcm16k):
    """gpt-4o-mini-transcribe on a WAV, multipart/form-data by hand."""
    boundary = "----glasses" + uuid.uuid4().hex
    wav = wav_wrap(pcm16k, 16000)
    body = b"".join([
        f"--{boundary}\r\nContent-Disposition: form-data; name=\"model\"\r\n\r\n{STT_MODEL}\r\n".encode(),
        f"--{boundary}\r\nContent-Disposition: form-data; name=\"file\"; filename=\"mic.wav\"\r\n"
        f"Content-Type: audio/wav\r\n\r\n".encode(),
        wav,
        f"\r\n--{boundary}--\r\n".encode(),
    ])
    req = urllib.request.Request(
        "https://api.openai.com/v1/audio/transcriptions", data=body,
        headers={"Authorization": f"Bearer {key}",
                 "Content-Type": f"multipart/form-data; boundary={boundary}"})
    with urllib.request.urlopen(req, timeout=60, context=SSL_CTX) as r:
        return json.loads(r.read()).get("text", "").strip()


def openai_tts(key, text):
    """gpt-4o-mini-tts -> raw 24 kHz 16-bit mono PCM."""
    req = urllib.request.Request(
        "https://api.openai.com/v1/audio/speech",
        data=json.dumps({"model": TTS_MODEL, "voice": TTS_VOICE,
                         "input": text, "response_format": "pcm"}).encode(),
        headers={"Authorization": f"Bearer {key}", "Content-Type": "application/json"})
    with urllib.request.urlopen(req, timeout=60, context=SSL_CTX) as r:
        return r.read()


def voice_toggle():
    """Start/stop the always-on GPT Realtime session."""
    global VOICE_SESSION
    if VOICE_STATUS["on"]:
        VOICE_STATUS["on"] = False          # camera loop may resume after stop
        if VOICE_SESSION:
            VOICE_SESSION.stop()
            VOICE_SESSION = None
        VOICE_STATUS["state"] = "off"
        return {"ok": True, "on": False}
    key = load_api_key()
    if not key:
        return {"ok": False, "error": "OPENAI_API_KEY not set"}
    with SER_LOCK:                          # wait out any in-flight camera grab
        VOICE_STATUS.update(on=True, state="starting", user="", assistant="")
        VOICE_SESSION = realtime_bridge.VoiceSession(SER, key, SSL_CTX, VOICE_STATUS)
        VOICE_SESSION.start()
    return {"ok": True, "on": True}


def talk_cycle():
    """Record on glasses -> STT -> TTS -> play on glasses. Returns dict for the UI."""
    if VOICE_STATUS["on"]:
        return {"ok": False, "error": "voice mode is on — Talk is for one-shot testing only"}
    key = load_api_key()
    if not key:
        return {"ok": False, "error": "OPENAI_API_KEY not set (env or secrets.properties)"}
    with SER_LOCK:
        try:
            pcm = record_from_glasses(SER)
        except Exception as e:
            return {"ok": False, "error": f"serial lost: {e} (unplugged? Serial Monitor open?)"}
        if not pcm:
            return {"ok": False, "error": "no audio from glasses (is the new firmware flashed?)"}
        peak = max(abs(int.from_bytes(pcm[i:i+2], "little", signed=True))
                   for i in range(0, min(len(pcm), 64000), 2))
        try:
            text = openai_stt(key, pcm)
        except Exception as e:
            return {"ok": False, "error": f"STT failed: {e}"}
        if not text:
            return {"ok": False, "error": f"heard nothing (mic peak={peak}) — speak louder / closer"}
        try:
            speech = openai_tts(key, text)
        except Exception as e:
            return {"ok": False, "error": f"TTS failed: {e}", "text": text}
        played = play_on_glasses(SER, speech)
        return {"ok": played, "text": text, "peak": peak,
                **({} if played else {"error": "playback on glasses failed"})}


# ── web UI ──────────────────────────────────────────────────────

LIVE_PAGE = """<!doctype html><meta charset="utf-8"><title>AI Glasses bench</title>
<body style="margin:0;background:#111;display:grid;place-items:center;height:100vh;font-family:monospace">
<div style="text-align:center">
<img id=f src=frame.jpg style="width:640px;max-width:95vw">
<p style="color:#888" id=s>viewfinder</p>
<button id=vc style="font-size:1.3em;padding:.5em 2em;border-radius:8px;cursor:pointer;background:#2a2;color:#fff;border:none">Voice: OFF</button>
<button id=t style="font-size:1em;padding:.5em 1em;border-radius:8px;cursor:pointer;margin-left:1em">Talk once (4 s)</button>
<p style="color:#ff6" id=vs></p>
<p style="color:#9cf;max-width:640px" id=vu></p>
<p style="color:#6f6;max-width:640px" id=va></p>
<p style="color:#6f6;max-width:640px" id=r></p>
</div>
<script>
const img=document.getElementById('f'),s=document.getElementById('s'),
      btn=document.getElementById('t'),out=document.getElementById('r'),
      vbtn=document.getElementById('vc'),vs=document.getElementById('vs'),
      vu=document.getElementById('vu'),va=document.getElementById('va');
let times=[],busy=false,voiceOn=false;
setInterval(()=>{if(busy||voiceOn)return;busy=true;const i=new Image();
 i.onload=()=>{img.src=i.src;const now=Date.now();times.push(now);
  times=times.filter(t=>now-t<2000);
  s.textContent=(times.length/2).toFixed(1)+' fps - '+new Date().toLocaleTimeString();busy=false;};
 i.onerror=()=>{busy=false;};
 i.src='frame.jpg?'+Date.now();},120);
setInterval(async()=>{try{const j=await(await fetch('/status')).json();
 voiceOn=j.on;
 vbtn.textContent='Voice: '+(j.on?'ON':'OFF');
 vbtn.style.background=j.on?'#c33':'#2a2';
 vs.textContent=j.on?('['+j.state+(j.mic_gain?(' | mic gain x'+j.mic_gain):'')
   +(j.lat_n?(' | turn '+j.lat_n+': '+j.lat_last+' ms'):'')+']'):'';
 vu.textContent=j.user?('you: '+j.user):'';
 va.textContent=j.assistant?('assistant: '+j.assistant):'';
 if(j.on)s.textContent='viewfinder paused - voice mode owns the wire';
}catch(e){}},400);
vbtn.onclick=async()=>{vbtn.disabled=true;
 try{const j=await(await fetch('/voice',{method:'POST'})).json();
  if(!j.ok){vs.textContent='error: '+(j.error||'?');}}
 catch(e){vs.textContent='request failed: '+e;}
 vbtn.disabled=false;};
btn.onclick=async()=>{btn.disabled=true;out.style.color='#6f6';
 out.textContent='listening - SPEAK NOW (4 s)... then transcribe + speak takes ~10 s';
 try{const r=await fetch('/talk',{method:'POST'});const j=await r.json();
  if(j.ok){out.textContent='heard: "'+j.text+'" (mic peak '+j.peak+') - spoken back on glasses';}
  else{out.style.color='#f66';out.textContent='error: '+(j.error||'?')+(j.text?' - heard: "'+j.text+'"':'');}
 }catch(e){out.style.color='#f66';out.textContent='request failed: '+e;}
 btn.disabled=false;};
</script>"""


def open_in_default_app(path):
    if sys.platform == "darwin":
        subprocess.run(["open", path], check=False)
    elif sys.platform.startswith("linux"):
        subprocess.run(["xdg-open", path], check=False)


def main():
    global SER
    ap = argparse.ArgumentParser(description=__doc__)
    ap.add_argument("--port", help="serial port (default: auto-detect /dev/cu.usbmodem*)")
    ap.add_argument("--live", action="store_true", help="viewfinder + Talk button at http://localhost:8000")
    ap.add_argument("--vga", action="store_true", help="switch the camera to VGA 640x480 first (focus checking)")
    args = ap.parse_args()

    port = find_port(args.port)
    print(f"Opening {port} ...")
    SER = serial.Serial(port, 115200, timeout=2)
    time.sleep(1.0)
    # Knock the firmware out of a stale voice-streaming mode (a killed
    # previous webapp may never have sent 'T') — harmless when already idle.
    SER.write(b"T")
    SER.flush()
    time.sleep(0.3)
    SER.reset_input_buffer()
    if args.vga:
        SER.write(b"v")
        time.sleep(0.3)

    if not args.live:
        data = grab_frame(SER)
        if not data:
            sys.exit(1)
        name = f"photo_{datetime.now():%H%M%S}.jpg"
        with open(name, "wb") as f:
            f.write(data)
        print(f"saved {name} ({len(data)} bytes)")
        open_in_default_app(name)
        return

    if not load_api_key():
        print("NOTE: OPENAI_API_KEY not found — viewfinder will work, Talk button won't.")

    workdir = tempfile.mkdtemp(prefix="glasses_cam_")
    with open(os.path.join(workdir, "index.html"), "wb") as f:
        f.write(LIVE_PAGE.encode("utf-8"))

    class Handler(http.server.SimpleHTTPRequestHandler):
        def __init__(self, *a, **k):
            super().__init__(*a, directory=workdir, **k)

        def _json(self, obj):
            body = json.dumps(obj).encode()
            self.send_response(200)
            self.send_header("Content-Type", "application/json")
            self.send_header("Content-Length", str(len(body)))
            self.end_headers()
            self.wfile.write(body)

        def do_POST(self):
            if self.path == "/talk":
                self._json(talk_cycle())
            elif self.path == "/voice":
                self._json(voice_toggle())
            else:
                self.send_error(404)

        def do_GET(self):
            if self.path.startswith("/status"):
                self._json(VOICE_STATUS)
            else:
                super().do_GET()

        def log_message(self, *a, **k):
            pass

    httpd = http.server.ThreadingHTTPServer(("127.0.0.1", 8000), Handler)
    threading.Thread(target=httpd.serve_forever, daemon=True).start()
    print("Bench webapp at http://localhost:8000  (Ctrl-C to stop)")
    open_in_default_app("http://localhost:8000")

    frame_path = os.path.join(workdir, "frame.jpg")
    try:
        while True:
            if VOICE_STATUS["on"]:          # voice owns the serial link entirely
                time.sleep(0.5)
                continue
            try:
                with SER_LOCK:
                    data = grab_frame(SER, timeout_s=5)
            except Exception as e:   # SerialException, termios.error, OSError —
                                      # any of them means the port went away
                # Board reset, cable bump, or the Arduino Serial Monitor stole
                # the port (close its tab in the IDE!). Reconnect and carry on.
                print(f"\nserial lost ({e}) — reconnecting...")
                try:
                    SER.close()
                except Exception:
                    pass
                data = None
                while True:
                    time.sleep(2)
                    try:
                        SER = serial.Serial(find_port(args.port), 115200, timeout=2)
                        time.sleep(1.5)   # let the CDC device settle before first use
                        SER.write(b"T")   # clear any stale voice-stream mode too
                        SER.flush()
                        time.sleep(0.3)
                        SER.reset_input_buffer()
                        print("reconnected")
                        break
                    except (Exception, SystemExit):
                        print("still waiting for the port (unplugged? Arduino Serial Monitor open?)")
            if data:
                tmp = frame_path + ".tmp"
                with open(tmp, "wb") as f:
                    f.write(data)
                os.replace(tmp, frame_path)  # atomic — no half-written frames served
            else:
                time.sleep(0.3)              # talk cycle may be holding the glasses busy
    except KeyboardInterrupt:
        print("\nbye")


if __name__ == "__main__":
    main()
