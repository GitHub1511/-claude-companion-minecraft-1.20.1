"""
Claude Companion's local Kokoro voice.

The mod starts this automatically. It needs any Python 3.10 to 3.13 and sets everything else up by
itself the first time: a private virtual environment, the kokoro-onnx package, and the voice model.
Nothing is installed system-wide and nothing leaves your computer.

Endpoints (OpenAI-compatible, same as Kokoro-FastAPI, so either can be used):
  GET  /health
  GET  /v1/audio/voices
  POST /v1/audio/speech   {"input": "...", "voice": "bf_emma", "speed": 1.0}  -> audio/wav
"""
import argparse
import io
import json
import os
import subprocess
import sys
import threading
import urllib.request

MODEL_BASE = "https://github.com/thewh1teagle/kokoro-onnx/releases/download/model-files-v1.1/"
PACKAGES = ["kokoro-onnx>=0.4.0", "soundfile"]


def status(msg):
    print("STATUS " + msg, flush=True)


def venv_python(venv):
    return os.path.join(venv, "Scripts", "python.exe") if os.name == "nt" else os.path.join(venv, "bin", "python")


def bootstrap(args):
    """Re-launch inside our own virtual environment, creating it on first run."""
    venv = os.path.join(args.dir, "venv")
    if os.path.abspath(sys.prefix) == os.path.abspath(venv):
        return
    py = venv_python(venv)
    marker = os.path.join(venv, ".installed")
    if not os.path.exists(marker):
        status("Setting up Kokoro voice (first time only, a few minutes)...")
        if not os.path.exists(py):
            subprocess.check_call([sys.executable, "-m", "venv", venv])
        subprocess.check_call([py, "-m", "pip", "install", "--disable-pip-version-check", "-q", "--upgrade", "pip"])
        subprocess.check_call([py, "-m", "pip", "install", "--disable-pip-version-check", "-q"] + PACKAGES)
        open(marker, "w").close()
    sys.exit(subprocess.call([py, os.path.abspath(__file__)] + sys.argv[1:]))


def download(name, dest_dir):
    path = os.path.join(dest_dir, name)
    if os.path.exists(path):
        return path
    tmp = path + ".part"
    status("Downloading Kokoro voice model " + name + " ...")
    with urllib.request.urlopen(MODEL_BASE + name, timeout=60) as r, open(tmp, "wb") as f:
        total = int(r.headers.get("Content-Length") or 0)
        done, last = 0, -1
        while True:
            chunk = r.read(1 << 20)
            if not chunk:
                break
            f.write(chunk)
            done += len(chunk)
            if total:
                pct = done * 100 // total
                if pct // 25 != last:
                    last = pct // 25
                    status("Kokoro model %s: %d%%" % (name, pct))
    os.replace(tmp, path)
    return path


def watch_parent():
    """Exit when Minecraft closes (it holds our stdin open)."""
    try:
        sys.stdin.read()
    except Exception:
        pass
    os._exit(0)


def serve(args):
    import numpy as np
    import soundfile as sf
    from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer

    # Point espeak-ng (used for pronunciation) at the data bundled with espeakng-loader. Without this some setups
    # fall back to a path baked in at build time and fail with "Error processing file .../phontab".
    try:
        import espeakng_loader
        os.environ["ESPEAK_DATA_PATH"] = espeakng_loader.get_data_path()
        os.environ["PHONEMIZER_ESPEAK_LIBRARY"] = espeakng_loader.get_library_path()
    except Exception as e:
        print("espeak setup warning: %s" % e, flush=True)
    from kokoro_onnx import Kokoro

    model = download(args.model, args.dir)
    voices_file = download("voices-v1.0.bin", args.dir)
    kokoro = Kokoro(model, voices_file)
    lock = threading.Lock()
    voices = sorted(kokoro.get_voices())

    def synth(text, voice, speed):
        if voice not in voices:
            voice = "bf_emma" if "bf_emma" in voices else voices[0]
        lang = "en-gb" if voice[:1] == "b" else "en-us"
        with lock:
            samples, rate = kokoro.create(text, voice=voice, speed=float(speed), lang=lang)
        buf = io.BytesIO()
        sf.write(buf, np.asarray(samples, dtype=np.float32), rate, format="WAV", subtype="PCM_16")
        return buf.getvalue()

    synth("Ready.", args.voice, 1.0)  # warm up so the first real sentence is quick

    class Handler(BaseHTTPRequestHandler):
        def log_message(self, *a):
            pass

        def send(self, code, body, ctype):
            self.send_response(code)
            self.send_header("Content-Type", ctype)
            self.send_header("Content-Length", str(len(body)))
            self.end_headers()
            self.wfile.write(body)

        def do_GET(self):
            if self.path.startswith("/health"):
                self.send(200, b'{"status":"ok"}', "application/json")
            elif self.path.startswith("/v1/audio/voices"):
                self.send(200, json.dumps({"voices": voices}).encode(), "application/json")
            else:
                self.send(404, b"not found", "text/plain")

        def do_POST(self):
            if not self.path.startswith("/v1/audio/speech"):
                return self.send(404, b"not found", "text/plain")
            try:
                req = json.loads(self.rfile.read(int(self.headers.get("Content-Length") or 0)) or b"{}")
                text = (req.get("input") or "").strip()
                if not text:
                    return self.send(400, b"empty input", "text/plain")
                wav = synth(text, req.get("voice") or args.voice, req.get("speed") or 1.0)
                self.send(200, wav, "audio/wav")
            except Exception as e:
                self.send(500, str(e).encode(), "text/plain")

    server = ThreadingHTTPServer(("127.0.0.1", args.port), Handler)
    status("Kokoro voice ready")
    server.serve_forever()


def main():
    p = argparse.ArgumentParser()
    p.add_argument("--port", type=int, default=8880)
    p.add_argument("--dir", default=os.path.dirname(os.path.abspath(__file__)))
    p.add_argument("--model", default="kokoro-v1.0.onnx")
    p.add_argument("--voice", default="bf_emma")
    p.add_argument("--no-watch", action="store_true", help="keep running even if stdin closes")
    args = p.parse_args()
    os.makedirs(args.dir, exist_ok=True)
    if sys.version_info < (3, 10):
        status("Kokoro needs Python 3.10 or newer")
        sys.exit(2)
    bootstrap(args)
    if not args.no_watch:
        threading.Thread(target=watch_parent, daemon=True).start()
    serve(args)


if __name__ == "__main__":
    main()
