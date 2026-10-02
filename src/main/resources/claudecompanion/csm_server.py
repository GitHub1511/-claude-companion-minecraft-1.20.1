"""
Claude Companion's Sesame CSM-1B voice (https://huggingface.co/sesame/csm-1b).

The mod starts this automatically. It needs Python 3.10 to 3.12 and sets the rest up the first time in its own
folder: a private virtual environment, PyTorch (CUDA build when an NVIDIA GPU is present), Hugging Face
transformers, and the CSM-1B weights. CSM-1B is gated, so a Hugging Face token whose account has accepted
the model's terms is required (passed in the HF_TOKEN environment variable).

By default it also applies Tachyeon/csm-1b-conversational-finetuned, a decoder-only fine-tune of CSM-1B on
Expresso speaker ex04 (a warm, expressive female voice), and prompts it with a real ex04 clip from Expresso
(CC BY-NC 4.0) so the voice stays consistent.

CSM-1B ships no fixed voices, so a consistent voice comes from a voice prompt: a short recording plus its
transcript that the model continues in the same voice. The default, "warm", is an original upbeat American
female voice bundled with the mod. Sesame's examples ("conversational_a", "conversational_b") or any WAV and
transcript of your own can be used instead.

Endpoints (same shape as the Kokoro server):
  GET  /health
  POST /v1/audio/speech   {"input": "..."}  -> audio/wav (24 kHz, 16-bit mono)
"""
import argparse
import io
import json
import os
import shutil
import subprocess
import sys
import threading

MODEL_ID = "sesame/csm-1b"

# Sesame's official example prompts (from github.com/SesameAILabs/csm, run_csm.py).
PROMPTS = {
    # Original warm, upbeat American voice made for this mod (bundled; generated with Kokoro af_heart).
    "warm": (
        "voice_prompt_warm.wav",
        "Oh, hey! Okay, so I was just looking around, and honestly? This place is kind of amazing. "
        "Like, we could build something really cool right over there, maybe up on that hill. "
        "I don't know, what do you think? I'm kind of excited about it.",
    ),
    "conversational_a": (
        "prompts/conversational_a.wav",
        "like revising for an exam I'd have to try and like keep up the momentum because I'd "
        "start really early I'd be like okay I'm gonna start revising now and then like "
        "you're revising for ages and then I just like start losing steam I didn't do that "
        "for the exam we had recently to be fair that was a more of a last minute scenario "
        "but like yeah I'm trying to like yeah I noticed this yesterday that like Mondays I "
        "sort of start the day with this not like a panic but like a",
    ),
    "conversational_b": (
        "prompts/conversational_b.wav",
        "like a super Mario level. Like it's very like high detail. And like, once you get "
        "into the park, it just like, everything looks like a computer game and they have all "
        "these, like, you know, if, if there's like a, you know, like in a Mario game, they "
        "will have like a question block. And if you like, you know, punch it, a coin will "
        "come out. So like everyone, when they come into the park, they get like this little "
        "bracelet and then you can go punching question blocks around.",
    ),
}

PACKAGES = ["transformers>=4.52.1,<5", "accelerate", "huggingface_hub", "fsspec", "pyarrow", "soundfile", "numpy", "scipy"]


def status(msg):
    print("STATUS " + msg, flush=True)


def venv_python(venv):
    return os.path.join(venv, "Scripts", "python.exe") if os.name == "nt" else os.path.join(venv, "bin", "python")


def nvidia_gpu():
    if not shutil.which("nvidia-smi"):
        return ""
    try:
        return subprocess.check_output(["nvidia-smi", "--query-gpu=name", "--format=csv,noheader"], text=True, timeout=15).strip()
    except Exception:
        return ""


def torch_index(args):
    if args.torch_index:
        return args.torch_index
    gpu = nvidia_gpu()
    if gpu:
        # RTX 50-series (Blackwell) needs CUDA 12.8 builds; 12.6 covers older cards.
        return "https://download.pytorch.org/whl/cu128" if "RTX 50" in gpu.upper() else "https://download.pytorch.org/whl/cu126"
    if sys.platform.startswith("linux"):
        return "https://download.pytorch.org/whl/cpu"
    return ""  # Windows and macOS: the default PyPI build


def bootstrap(args):
    venv = os.path.join(args.dir, "venv")
    if os.path.abspath(sys.prefix) == os.path.abspath(venv):
        return
    py = venv_python(venv)
    marker = os.path.join(venv, ".installed")
    if not os.path.exists(marker):
        status("Setting up the Sesame voice (first time only). This downloads PyTorch, a few GB, so it can take a while...")
        if not os.path.exists(py):
            subprocess.check_call([sys.executable, "-m", "venv", venv])
        pip = [py, "-m", "pip", "install", "--disable-pip-version-check", "-q"]
        subprocess.check_call(pip + ["--upgrade", "pip"])
        idx = torch_index(args)
        subprocess.check_call(pip + ["torch"] + (["--index-url", idx] if idx else []))
        subprocess.check_call(pip + PACKAGES)
        open(marker, "w").close()
    sys.exit(subprocess.call([py, os.path.abspath(__file__)] + sys.argv[1:]))


def watch_parent():
    try:
        sys.stdin.read()
    except Exception:
        pass
    os._exit(0)


# ---------------------------------------------------------------- fine-tune

# Original Sesame checkpoint names -> Hugging Face transformers names (from transformers' convert_csm.py).
KEY_MAP = [
    (r"backbone\.layers\.(\d+)", r"backbone_model.layers.\1"),
    (r"decoder\.layers\.(\d+)", r"depth_decoder.model.layers.\1"),
    (r"attn", r"self_attn"),
    (r"output_proj", r"o_proj"),
    (r"w1", r"gate_proj"),
    (r"w2", r"down_proj"),
    (r"w3", r"up_proj"),
    (r"text_embeddings", r"embed_text_tokens"),
    (r"audio_embeddings", r"backbone_model.embed_tokens.embed_audio_tokens"),
    (r"codebook0_head", r"lm_head"),
    (r"audio_head", r"depth_decoder.codebooks_head.weight"),
    (r"projection", r"depth_decoder.model.inputs_embeds_projector"),
    (r"sa_norm.scale", r"input_layernorm.weight"),
    (r"mlp_norm.scale", r"post_attention_layernorm.weight"),
    (r"decoder.norm.scale", r"depth_decoder.model.norm.weight"),
    (r"backbone.norm.scale", r"backbone_model.norm.weight"),
]


def _strip(key, extra=()):
    changed = True
    while changed:
        changed = False
        for pre in ("_orig_mod.", "module.", "model.") + tuple(extra):
            if key.startswith(pre):
                key, changed = key[len(pre):], True
    return key


def _as_state_dict(obj):
    import torch
    if torch.is_tensor(obj):
        return {"": obj}
    if isinstance(obj, dict):
        for wrapper in ("state_dict", "model", "model_state_dict"):
            if wrapper in obj and isinstance(obj[wrapper], dict):
                return obj[wrapper]
        return obj
    raise ValueError("unexpected checkpoint type %s" % type(obj).__name__)


def _convert(original, config):
    """Rename original Sesame keys and undo the rotary-embedding layout difference for q/k projections."""
    import re
    import torch
    out = {}
    for key, value in original.items():
        if not torch.is_tensor(value):
            continue
        new = key
        for pattern, repl in KEY_MAP:
            new = re.sub(pattern, repl, new)
        if re.search(r"(k|q)_proj\.weight", new):
            c = config.depth_decoder_config if new.startswith("depth_decoder") else config
            heads = c.num_attention_heads if "q_proj" in new else c.num_key_value_heads
            head_dim = getattr(c, "head_dim", None) or c.hidden_size // c.num_attention_heads
            dim1, dim2 = heads * head_dim, c.hidden_size
            value = value.reshape(dim1, dim2).view(heads, dim1 // heads // 2, 2, dim2).transpose(1, 2).reshape(dim1, dim2)
        out[new] = value
    return out


def apply_finetune(model, repo, token):
    """Load a decoder-only CSM fine-tune (Sesame-format .pt files) onto the transformers model."""
    import torch
    from huggingface_hub import hf_hub_download

    def load(name):
        return torch.load(hf_hub_download(repo, name, token=token or None), map_location="cpu", weights_only=True)

    target = model.state_dict()

    def check(sd):
        good = {k: v for k, v in sd.items() if k in target and tuple(v.shape) == tuple(target[k].shape)}
        bad = [k for k in sd if k not in good]
        decoder_keys = [k for k in good if k.startswith("depth_decoder.model.layers.")]
        return good, bad, decoder_keys

    try:
        status("Applying the voice fine-tune (" + repo + ")...")
        original = {}
        for k, v in _as_state_dict(load("decoder.pt")).items():
            original["decoder." + _strip(k, ("decoder.",))] = v
        head = _as_state_dict(load("audio_head.pt"))
        original["audio_head"] = next(v for v in head.values() if torch.is_tensor(v))
        proj = _as_state_dict(load("projection.pt"))
        for k, v in proj.items():
            leaf = _strip(k, ("projection.",)) or "weight"
            original["projection." + leaf] = v
        good, bad, decoder_keys = check(_convert(original, model.config))
        if bad or not decoder_keys or "depth_decoder.codebooks_head.weight" not in good:
            raise ValueError("unrecognised keys %s" % bad[:5])
    except Exception as e:
        status("Fine-tune parts weren't in the expected layout (%s). Downloading the merged model instead (about 3 GB)..." % e)
        merged = {_strip(k): v for k, v in _as_state_dict(load("model_merged.pt")).items()}
        good, bad, decoder_keys = check(_convert(merged, model.config))
        if not decoder_keys:
            raise ValueError("couldn't match the fine-tune's weights to CSM-1B")

    for k in good:
        good[k] = good[k].to(target[k].dtype)
    model.load_state_dict(good, strict=False)
    status("Fine-tuned voice loaded (%d tensors)." % len(good))


def expresso_prompt(dirpath, speaker, token):
    """Fetch one clean 6 to 16 second clip of an Expresso speaker (the voice the fine-tune learned) to use as the prompt."""
    import io as _io
    import soundfile as sf
    wav = os.path.join(dirpath, "prompt_%s.wav" % speaker)
    txt = os.path.join(dirpath, "prompt_%s.txt" % speaker)
    if os.path.exists(wav) and os.path.exists(txt):
        return wav, open(txt, encoding="utf-8").read()
    import pyarrow.parquet as pq
    from huggingface_hub import HfFileSystem
    status("Fetching a short sample of the " + speaker + " voice from the Expresso dataset (one time)...")
    fs = HfFileSystem(token=token or None)
    files = sorted(fs.glob("datasets/ylacombe/expresso/read/*.parquet"))
    for preferred in (("happy",), ("default",)):
        for f in files:
            with fs.open(f, "rb") as fh:
                pf = pq.ParquetFile(fh)
                for rg in range(pf.num_row_groups):
                    meta = pf.read_row_group(rg, columns=["speaker_id", "style", "text"])
                    speakers = meta.column("speaker_id").to_pylist()
                    styles = meta.column("style").to_pylist()
                    rows = [i for i in range(len(speakers)) if speakers[i] == speaker and styles[i] in preferred]
                    if not rows:
                        continue
                    texts = meta.column("text").to_pylist()
                    audios = pf.read_row_group(rg, columns=["audio"]).column("audio").to_pylist()
                    for i in rows:
                        data = audios[i].get("bytes") if isinstance(audios[i], dict) else None
                        if not data:
                            continue
                        samples, sr = sf.read(_io.BytesIO(data), dtype="float32", always_2d=True)
                        if 6.0 <= len(samples) / sr <= 16.0 and texts[i].strip():
                            sf.write(wav, samples, sr, subtype="PCM_16")
                            with open(txt, "w", encoding="utf-8") as t:
                                t.write(texts[i].strip())
                            return wav, texts[i].strip()
    raise ValueError("no suitable %s clip found" % speaker)


def serve(args):
    token = os.environ.get("HF_TOKEN", "").strip()
    os.environ.setdefault("HF_HOME", os.path.join(args.dir, "hf"))
    import numpy as np
    import soundfile as sf
    import torch
    from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
    from huggingface_hub import hf_hub_download
    from scipy.signal import resample_poly
    from transformers import AutoProcessor, CsmForConditionalGeneration

    if args.device != "auto":
        device = args.device
    else:
        device = "cuda" if torch.cuda.is_available() else "cpu"
    if device == "cpu":
        status("No NVIDIA GPU found, so the Sesame voice will run on the CPU and be slow (it may lag several seconds per sentence).")
    dtype = torch.bfloat16 if device.startswith("cuda") else torch.float32

    status("Loading Sesame CSM-1B (downloads several GB the first time)...")
    try:
        processor = AutoProcessor.from_pretrained(MODEL_ID, token=token)
        model = CsmForConditionalGeneration.from_pretrained(MODEL_ID, token=token, torch_dtype=dtype).to(device)
    except Exception as e:
        text = str(e)
        if "401" in text or "403" in text or "gated" in text.lower() or "access" in text.lower():
            status("Your Hugging Face token can't download sesame/csm-1b yet. Open huggingface.co/sesame/csm-1b, accept the terms "
                   "with the same account, then restart Minecraft. Using the Kokoro voice until then.")
            sys.exit(4)
        raise
    model.eval()
    rate = 24000

    if args.finetune:
        try:
            apply_finetune(model, args.finetune, token)
        except Exception as e:
            status("Couldn't apply the voice fine-tune (%s). Using plain CSM-1B." % e)

    # Voice prompt
    if args.prompt.startswith("ex0"):
        try:
            prompt_path, prompt_text = expresso_prompt(args.dir, args.prompt, token)
        except Exception as e:
            status("Couldn't get the %s sample (%s). Using the bundled warm voice prompt instead." % (args.prompt, e))
            args.prompt = "warm"
    if args.prompt.startswith("ex0"):
        pass
    elif args.prompt in PROMPTS:
        filename, prompt_text = PROMPTS[args.prompt]
        if filename.startswith("prompts/"):
            prompt_path = hf_hub_download(MODEL_ID, filename, token=token)
        else:
            prompt_path = os.path.join(os.path.dirname(os.path.abspath(__file__)), filename)
    else:
        prompt_path, prompt_text = args.prompt, args.prompt_text
        if not os.path.exists(prompt_path) or not prompt_text.strip():
            status("Custom Sesame voice prompt needs an existing WAV file and its exact transcript (csmVoicePromptText).")
            sys.exit(5)
    audio, sr = sf.read(prompt_path, dtype="float32", always_2d=True)
    audio = audio.mean(axis=1)
    if sr != rate:
        from math import gcd
        g = gcd(int(sr), rate)
        audio = resample_poly(audio, rate // g, int(sr) // g).astype(np.float32)
    prompt_audio = audio

    lock = threading.Lock()

    def synth(text):
        conversation = [
            {"role": "0", "content": [{"type": "text", "text": prompt_text}, {"type": "audio", "path": prompt_audio}]},
            {"role": "0", "content": [{"type": "text", "text": text}]},
        ]
        with lock, torch.inference_mode():
            inputs = processor.apply_chat_template(conversation, tokenize=True, return_dict=True).to(device)
            for k, v in list(inputs.items()):
                if torch.is_tensor(v) and torch.is_floating_point(v):
                    inputs[k] = v.to(dtype)
            # Mimi runs at 12.5 frames per second; allow plenty of room for the sentence.
            max_frames = min(750, int(len(text) * 1.4) + 30)
            out = model.generate(**inputs, output_audio=True, max_new_tokens=max_frames)
        wav = out[0].float().cpu().numpy()
        buf = io.BytesIO()
        sf.write(buf, np.clip(wav, -1.0, 1.0), rate, format="WAV", subtype="PCM_16")
        return buf.getvalue()

    synth("Hi.")  # warm up

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
                self.send(200, json.dumps({"status": "ok", "device": device}).encode(), "application/json")
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
                self.send(200, synth(text), "audio/wav")
            except Exception as e:
                self.send(500, str(e).encode(), "text/plain")

    server = ThreadingHTTPServer(("127.0.0.1", args.port), Handler)
    status("Sesame voice ready (" + device + ")")
    server.serve_forever()


def main():
    p = argparse.ArgumentParser()
    p.add_argument("--port", type=int, default=8890)
    p.add_argument("--dir", default=os.path.dirname(os.path.abspath(__file__)))
    p.add_argument("--prompt", default="ex04", help="ex04 (Expresso speaker, matches the fine-tune), warm, conversational_a, conversational_b, or a WAV path")
    p.add_argument("--finetune", default="Tachyeon/csm-1b-conversational-finetuned", help="Hugging Face repo of a decoder-only CSM fine-tune, or empty for plain CSM-1B")
    p.add_argument("--prompt-text", default="", help="exact transcript of a custom prompt WAV")
    p.add_argument("--device", default="auto")
    p.add_argument("--torch-index", default="")
    p.add_argument("--no-watch", action="store_true")
    args = p.parse_args()
    os.makedirs(args.dir, exist_ok=True)
    if sys.version_info < (3, 10):
        status("The Sesame voice needs Python 3.10 or newer")
        sys.exit(2)
    if not os.environ.get("HF_TOKEN", "").strip():
        # Checked before installing anything, so nobody downloads gigabytes they can't use yet.
        status("Sesame CSM-1B needs a Hugging Face token. Make a free account at huggingface.co, open huggingface.co/sesame/csm-1b "
               "and accept the terms, create a Read token in Settings, Access Tokens, then paste it into huggingfaceToken in "
               "config/claudecompanion.json and restart Minecraft. Using the Kokoro voice until then.")
        sys.exit(3)
    bootstrap(args)
    if not args.no_watch:
        threading.Thread(target=watch_parent, daemon=True).start()
    serve(args)


if __name__ == "__main__":
    main()
