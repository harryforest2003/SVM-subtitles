#!/usr/bin/env python3
"""
Companion server for SVM Subtitles: speech-to-text, live captions, translation and text-to-speech.

Run it on any computer with CPU (or GPU) to spare, or on a free Hugging Face Space, and point the mod at it.
The game then only uploads short audio clips, so recognition never slows Minecraft down.

Endpoints (all except GET / need "Authorization: Bearer <api key>"):
  POST /v1/audio/transcriptions   OpenAI-compatible speech-to-text
  POST /v1/audio/translations     same, translated into English
  POST /svm/v1/stream             live captions: append audio to a session, get the text so far
  POST /v1/audio/speech           OpenAI-compatible text-to-speech (Piper), returns WAV
  GET  /health                    engine, models and features

Security:
  * When reachable from other machines it refuses to run without an API key. If none is given, a random one is
    generated and stored in .api_key next to this script (readable only by you).
  * Keys are compared in constant time; repeated wrong keys from one address are throttled.
  * Audio is processed in memory and never written to disk. Transcripts are not logged unless --log-text.
  * Upload sizes, audio length, text length and live sessions are all capped. Use --tls-cert/--tls-key (or a
    host that provides HTTPS, like Hugging Face) when the server is reachable over the internet.
"""

import argparse
import hmac
import io
import ipaddress
import json
import logging
import os
import secrets
import shutil
import ssl
import stat
import tempfile
import threading
import time
import uuid
import wave
from collections import OrderedDict, defaultdict, deque
from concurrent.futures import Future
from email.parser import BytesParser
from email.policy import HTTP
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from pathlib import Path

import numpy as np

SAMPLE_RATE = 16000
MAX_UPLOAD_BYTES = 8 * 1024 * 1024          # ~4 minutes of 16 kHz WAV
MAX_STREAM_CHUNK_BYTES = 1024 * 1024
MAX_JSON_BYTES = 16 * 1024
MAX_AUDIO_SECONDS = 120
MAX_SESSION_SECONDS = 30
MAX_SESSIONS = 64
SESSION_TTL_SECONDS = 20
MAX_TTS_CHARS = 500
MAX_PROMPT_CHARS = 1000
AUTH_FAILURES_PER_MINUTE = 10
REQUEST_TIMEOUT_SECONDS = 30

FINAL, SPEECH, PARTIAL = 0, 1, 2  # job priorities: finished sentences first, live captions last

log = logging.getLogger("svm-companion")
SCRIPT_DIR = Path(__file__).resolve().parent


class ClientError(Exception):
    def __init__(self, status, message):
        super().__init__(message)
        self.status = status


# ---------------------------------------------------------------- speech recognition

def has_cuda(device):
    if device != "auto":
        return device == "cuda"
    try:
        import ctranslate2
        return ctranslate2.get_cuda_device_count() > 0
    except Exception:  # noqa: BLE001 - no GPU support installed
        return False


def pick_model(requested, device):
    """'auto' picks the most accurate model this machine can run comfortably."""
    if requested != "auto":
        return requested
    if has_cuda(device):
        return "large-v3"
    return "small" if (os.cpu_count() or 2) >= 4 else "base"


class Recognizer:
    """Wraps faster-whisper (preferred) or openai-whisper behind one interface."""

    def __init__(self, engine, model, device, compute_type, threads):
        self.model_name = model
        self.multilingual = not model.endswith(".en")
        if engine in ("auto", "faster-whisper"):
            try:
                from faster_whisper import WhisperModel
            except ImportError:
                if engine == "faster-whisper":
                    raise
            else:
                if compute_type == "auto":
                    compute_type = "float16" if device == "cuda" else "int8"
                self._model = WhisperModel(model, device=device, compute_type=compute_type, cpu_threads=threads)
                self.engine = "faster-whisper"
                return

        import torch
        import whisper

        if device == "auto":
            device = "cuda" if torch.cuda.is_available() else "cpu"
        if threads > 0:
            torch.set_num_threads(threads)
        self._device = device
        self._model = whisper.load_model(model, device=device)
        self.engine = "openai-whisper"

    def transcribe(self, audio, language, task, prompt, quality):
        """Returns (text, detected language). quality=True: beam search and voice activity filter."""
        if not self.multilingual:
            language, task = "en", "transcribe"
        if self.engine == "faster-whisper":
            segments, info = self._model.transcribe(
                audio, language=language, task=task, initial_prompt=prompt or None,
                beam_size=5 if quality else 1, temperature=0.0, condition_on_previous_text=False,
                vad_filter=quality, without_timestamps=True)
            text = "".join(segment.text for segment in segments).strip()
            return text, info.language
        result = self._model.transcribe(
            audio, language=language, task=task, initial_prompt=prompt or None,
            beam_size=5 if quality else None, temperature=0.0, condition_on_previous_text=False,
            fp16=(self._device == "cuda"))
        return result["text"].strip(), result.get("language")


class Synthesizer:
    """Piper text-to-speech. Voices download on first use from rhasspy/piper-voices."""

    def __init__(self, default_voice, voices_dir):
        from piper import PiperVoice  # noqa: F401  (fail early if piper-tts is missing)

        self.default_voice = default_voice
        self.voices_dir = Path(voices_dir)
        self.voices_dir.mkdir(parents=True, exist_ok=True)
        self._voices = {}
        self._espeak_dir = self._short_espeak_path()
        self.load(default_voice)

    @staticmethod
    def _short_espeak_path():
        # espeak-ng silently truncates data paths longer than ~160 characters, which deep virtualenvs exceed.
        import piper
        data = Path(piper.__file__).parent / "espeak-ng-data"
        if len(str(data)) < 120:
            return data
        copy = Path(tempfile.gettempdir()) / "svm-espeak-ng-data"
        if not (copy / "phontab").exists():
            shutil.copytree(data, copy, dirs_exist_ok=True)
        return copy

    def load(self, name):
        if name in self._voices:
            return self._voices[name]
        if not name.replace("_", "").replace("-", "").isalnum():
            raise ClientError(400, "invalid voice name")
        from piper import PiperVoice
        from piper.download_voices import download_voice

        model = self.voices_dir / f"{name}.onnx"
        if not model.exists():
            log.info("Downloading voice %s...", name)
            try:
                download_voice(name, self.voices_dir)
            except Exception as e:  # noqa: BLE001 - unknown voice names end up here
                raise ClientError(400, f"unknown voice '{name}'") from e
        voice = PiperVoice.load(str(model), espeak_data_dir=self._espeak_dir)
        self._voices[name] = voice
        return voice

    def synthesize(self, text, voice_name):
        voice = self.load(voice_name or self.default_voice)
        chunks = list(voice.synthesize(text))
        if not chunks:
            return np.zeros(0, dtype=np.int16), voice.config.sample_rate
        audio = np.concatenate([chunk.audio_int16_array for chunk in chunks])
        return audio, chunks[0].sample_rate


# ---------------------------------------------------------------- scheduling

class Scheduler:
    """One worker runs every model call (the models aren't thread-safe and would only compete for CPU).
    Finished sentences go first, then speech synthesis, then live captions. A newer live-caption request
    for the same session replaces an older one that hasn't started yet."""

    def __init__(self):
        self._cv = threading.Condition()
        self._queues = {FINAL: deque(), SPEECH: deque(), PARTIAL: OrderedDict()}
        threading.Thread(target=self._run, name="model-worker", daemon=True).start()

    def submit(self, priority, fn, key=None):
        future = Future()
        with self._cv:
            if priority == PARTIAL:
                old = self._queues[PARTIAL].pop(key, None)
                if old is not None:
                    old[1].set_result(None)  # superseded
                self._queues[PARTIAL][key] = (fn, future)
            else:
                self._queues[priority].append((fn, future))
            self._cv.notify()
        return future

    def _next(self):
        for priority in (FINAL, SPEECH):
            if self._queues[priority]:
                return self._queues[priority].popleft()
        if self._queues[PARTIAL]:
            return self._queues[PARTIAL].popitem(last=False)[1]
        return None

    def _run(self):
        while True:
            with self._cv:
                job = self._next()
                while job is None:
                    self._cv.wait()
                    job = self._next()
            fn, future = job
            if not future.set_running_or_notify_cancel():
                continue
            try:
                future.set_result(fn())
            except Exception as e:  # noqa: BLE001 - delivered to the waiting request
                future.set_exception(e)


class Sessions:
    """Audio received so far for each live caption session, kept only briefly and only in memory."""

    def __init__(self):
        self._lock = threading.Lock()
        self._sessions = {}

    def append(self, session_id, audio):
        now = time.monotonic()
        with self._lock:
            for key in [k for k, (_, seen) in self._sessions.items() if now - seen > SESSION_TTL_SECONDS]:
                del self._sessions[key]
            buffered, _ = self._sessions.get(session_id, (np.zeros(0, dtype=np.float32), now))
            if session_id not in self._sessions and len(self._sessions) >= MAX_SESSIONS:
                raise ClientError(503, "too many live sessions")
            buffered = np.concatenate([buffered, audio])[-MAX_SESSION_SECONDS * SAMPLE_RATE:]
            self._sessions[session_id] = (buffered, now)
            return buffered

    def drop(self, session_id):
        with self._lock:
            self._sessions.pop(session_id, None)


class AuthThrottle:
    def __init__(self):
        self._lock = threading.Lock()
        self._failures = defaultdict(deque)

    def blocked(self, address):
        with self._lock:
            failures = self._failures[address]
            while failures and time.monotonic() - failures[0] > 60:
                failures.popleft()
            return len(failures) >= AUTH_FAILURES_PER_MINUTE

    def fail(self, address):
        with self._lock:
            self._failures[address].append(time.monotonic())


# ---------------------------------------------------------------- request parsing

def read_wav(data):
    """Decodes a 16-bit PCM WAV into float32 mono 16 kHz (both engines take arrays directly, no ffmpeg)."""
    try:
        with wave.open(io.BytesIO(data)) as wav:
            if wav.getsampwidth() != 2:
                raise ClientError(400, "only 16-bit PCM WAV is supported")
            rate = wav.getframerate()
            channels = wav.getnchannels()
            if not 1 <= channels <= 8 or not 1000 <= rate <= 192000:
                raise ClientError(400, "unsupported WAV format")
            if wav.getnframes() > rate * MAX_AUDIO_SECONDS:
                raise ClientError(413, f"audio longer than {MAX_AUDIO_SECONDS} s")
            frames = wav.readframes(wav.getnframes())
    except (wave.Error, EOFError) as e:
        raise ClientError(400, "invalid WAV file") from e
    audio = np.frombuffer(frames, dtype="<i2").astype(np.float32) / 32768.0
    if channels > 1:
        audio = audio[: len(audio) // channels * channels].reshape(-1, channels).mean(axis=1)
    if rate != SAMPLE_RATE and len(audio) > 0:
        target = np.linspace(0, len(audio) - 1, int(len(audio) * SAMPLE_RATE / rate))
        audio = np.interp(target, np.arange(len(audio)), audio).astype(np.float32)
    return audio


def parse_multipart(content_type, body):
    """Returns (fields, uploaded file) from a multipart/form-data body."""
    if not content_type.startswith("multipart/form-data"):
        raise ClientError(400, "expected multipart/form-data")
    message = BytesParser(policy=HTTP).parsebytes(
        b"Content-Type: " + content_type.encode("latin-1", "replace") + b"\r\n\r\n" + body)
    fields, upload = {}, None
    for part in message.iter_parts():
        name = part.get_param("name", header="content-disposition")
        payload = part.get_payload(decode=True) or b""
        if part.get_filename() is not None or name in ("file", "audio"):
            upload = payload
        elif name and len(fields) < 20:
            fields[name] = payload.decode("utf-8", "replace").strip()[:MAX_PROMPT_CHARS]
    return fields, upload


def clean_language(value, default):
    value = (value or default or "").strip().lower()
    if value in ("", "auto"):
        return None
    if not value.isalpha() or len(value) > 8:
        raise ClientError(400, "invalid language")
    return value


def wav_bytes(audio_int16, rate):
    out = io.BytesIO()
    with wave.open(out, "wb") as wav:
        wav.setnchannels(1)
        wav.setsampwidth(2)
        wav.setframerate(rate)
        wav.writeframes(audio_int16.astype("<i2").tobytes())
    return out.getvalue()


# ---------------------------------------------------------------- HTTP

def make_handler(app):
    class Handler(BaseHTTPRequestHandler):
        server_version = "SVMCompanion"
        sys_version = ""
        timeout = REQUEST_TIMEOUT_SECONDS
        protocol_version = "HTTP/1.1"

        def do_GET(self):
            path = self.path.split("?")[0].rstrip("/")
            if path == "":
                return self.send_json(200, {"status": "ok"})
            if not self.authorized():
                return None
            if path == "/health":
                return self.send_json(200, app.health())
            return self.send_json(404, {"error": {"message": "not found"}})

        def do_POST(self):
            path = self.path.split("?")[0].rstrip("/")
            if not self.authorized():
                return None
            try:
                if path in ("/v1/audio/transcriptions", "/audio/transcriptions", "/inference"):
                    return self.transcribe("transcribe")
                if path in ("/v1/audio/translations", "/audio/translations"):
                    return self.transcribe("translate")
                if path == "/svm/v1/stream":
                    return self.stream()
                if path in ("/v1/audio/speech", "/audio/speech"):
                    return self.speech()
                return self.send_json(404, {"error": {"message": "not found"}})
            except ClientError as e:
                return self.send_json(e.status, {"error": {"message": str(e)}})
            except Exception:  # noqa: BLE001 - never leak internals to the client
                log.exception("Request failed")
                return self.send_json(500, {"error": {"message": "internal error"}})

        def client_key(self):
            # Behind a proxy (e.g. Hugging Face) every request comes from the proxy's address, so throttle by
            # the forwarded address instead; otherwise one attacker could lock everyone out.
            if app.behind_proxy:
                return self.headers.get("X-Forwarded-For", "") or self.client_address[0]
            return self.client_address[0]

        def authorized(self):
            address = self.client_key()
            if app.throttle.blocked(address):
                self.send_json(429, {"error": {"message": "too many failed attempts, try again later"}})
                return False
            if not app.api_key:
                return True
            supplied = self.headers.get("Authorization", "")
            if supplied.startswith("Bearer "):
                supplied = supplied[7:]
            supplied = supplied.strip() or self.headers.get("X-Api-Key", "").strip()
            if hmac.compare_digest(supplied.encode(), app.api_key.encode()):
                return True
            app.throttle.fail(address)
            log.warning("Rejected a request with a wrong API key")
            self.send_json(401, {"error": {"message": "invalid api key"}})
            return False

        def read_body(self, limit):
            try:
                length = int(self.headers.get("Content-Length") or 0)
            except ValueError:
                raise ClientError(400, "bad Content-Length") from None
            if length <= 0:
                raise ClientError(400, "empty request")
            if length > limit:
                raise ClientError(413, "request too large")
            return self.rfile.read(length)

        def transcribe(self, task):
            fields, upload = parse_multipart(self.headers.get("Content-Type", ""), self.read_body(MAX_UPLOAD_BYTES))
            if not upload:
                raise ClientError(400, "no audio file in the request")
            audio = read_wav(upload)
            language = clean_language(fields.get("language"), app.language)
            started = time.perf_counter()
            text, detected = app.run(FINAL, lambda: app.recognizer.transcribe(
                audio, language, task, fields.get("prompt"), quality=True))
            app.log_result("final", len(audio), started, text)
            if fields.get("session"):
                app.sessions.drop(fields["session"])
            if fields.get("response_format") == "text":
                return self.send_bytes(200, text.encode("utf-8"), "text/plain; charset=utf-8")
            return self.send_json(200, {"text": text, "language": detected})

        def stream(self):
            fields, upload = parse_multipart(self.headers.get("Content-Type", ""), self.read_body(MAX_STREAM_CHUNK_BYTES))
            session = fields.get("session", "")
            try:
                session = str(uuid.UUID(session))
            except ValueError:
                raise ClientError(400, "session must be a UUID") from None
            buffered = app.sessions.append(session, read_wav(upload) if upload else np.zeros(0, dtype=np.float32))
            language = clean_language(fields.get("language"), app.language)
            task = "translate" if fields.get("task") == "translate" else "transcribe"
            started = time.perf_counter()
            result = app.run(PARTIAL, lambda: app.live_recognizer.transcribe(
                buffered, language, task, fields.get("prompt"), quality=False), key=session)
            if result is None:
                return self.send_json(200, {"text": None, "skipped": True})
            app.log_result("live", len(buffered), started, result[0])
            return self.send_json(200, {"text": result[0], "language": result[1]})

        def speech(self):
            if app.speaker is None:
                raise ClientError(404, "text-to-speech is turned off on this server")
            try:
                request = json.loads(self.read_body(MAX_JSON_BYTES))
            except json.JSONDecodeError:
                raise ClientError(400, "expected a JSON body") from None
            text = str(request.get("input", "")).strip()
            text = "".join(ch for ch in text if ch.isprintable())[:MAX_TTS_CHARS]
            if not text:
                raise ClientError(400, "input is empty")
            voice = str(request.get("voice") or "")
            if voice in ("", "default", "alloy"):
                voice = None
            started = time.perf_counter()
            audio, rate = app.run(SPEECH, lambda: app.speaker.synthesize(text, voice))
            log.info("speech: %d characters in %.2f s", len(text), time.perf_counter() - started)
            return self.send_bytes(200, wav_bytes(audio, rate), "audio/wav")

        def send_json(self, status, payload):
            self.send_bytes(status, json.dumps(payload).encode("utf-8"), "application/json")

        def send_bytes(self, status, data, content_type):
            if status >= 400:
                # The request body may not have been read; don't reuse this connection.
                self.close_connection = True
            self.send_response(status)
            self.send_header("Content-Type", content_type)
            self.send_header("Content-Length", str(len(data)))
            self.send_header("Cache-Control", "no-store")
            self.send_header("X-Content-Type-Options", "nosniff")
            if self.close_connection:
                self.send_header("Connection", "close")
            self.end_headers()
            self.wfile.write(data)

        def log_message(self, fmt, *args):
            log.debug("%s - %s", self.address_string(), fmt % args)

    return Handler


class App:
    def __init__(self, args, api_key):
        self.api_key = api_key
        self.language = args.language
        self.log_text = args.log_text
        self.behind_proxy = args.behind_proxy or bool(os.environ.get("SPACE_ID"))
        self.throttle = AuthThrottle()
        self.sessions = Sessions()
        self.scheduler = Scheduler()

        device = args.device
        model = pick_model(args.model, device)
        log.info("Loading speech model %s (the first start downloads it)...", model)
        self.recognizer = Recognizer(args.engine, model, device, args.compute_type, args.threads)
        live_model = args.live_model or ("base.en" if model.endswith(".en") else "base")
        if model in ("tiny", "tiny.en", "base", "base.en"):
            live_model = args.live_model or model
        if args.no_live:
            self.live_recognizer = None
        elif live_model == model:
            self.live_recognizer = self.recognizer
        else:
            log.info("Loading live caption model %s...", live_model)
            self.live_recognizer = Recognizer(args.engine, live_model, device, args.compute_type, args.threads)

        self.speaker = None
        if not args.no_tts:
            try:
                self.speaker = Synthesizer(args.tts_voice, args.voices_dir)
            except ImportError:
                log.warning("piper-tts is not installed, so text-to-speech is off (pip install piper-tts)")

        silence = np.zeros(SAMPLE_RATE, dtype=np.float32)
        self.recognizer.transcribe(silence, None if self.language == "auto" else self.language, "transcribe", None, False)

    def health(self):
        features = ["transcribe"]
        if self.recognizer.multilingual:
            features.append("translate")
        if self.live_recognizer is not None:
            features.append("stream")
        if self.speaker is not None:
            features.append("tts")
        return {
            "status": "ok",
            "engine": self.recognizer.engine,
            "model": self.recognizer.model_name,
            "live_model": self.live_recognizer.model_name if self.live_recognizer else None,
            "voice": self.speaker.default_voice if self.speaker else None,
            "features": features,
        }

    def run(self, priority, fn, key=None):
        if priority == PARTIAL and self.live_recognizer is None:
            raise ClientError(404, "live captions are turned off on this server")
        return self.scheduler.submit(priority, fn, key).result(timeout=120)

    def log_result(self, kind, samples, started, text):
        took = time.perf_counter() - started
        if self.log_text:
            log.info("%s: %.1f s of audio in %.2f s: %s", kind, samples / SAMPLE_RATE, took, text)
        else:
            log.info("%s: %.1f s of audio in %.2f s", kind, samples / SAMPLE_RATE, took)


def is_loopback(host):
    if host in ("localhost", ""):
        return host == "localhost"
    try:
        return ipaddress.ip_address(host).is_loopback
    except ValueError:
        return False


def resolve_api_key(args):
    """Explicit key > SVM_API_KEY > stored key. Anything reachable from other machines must have one."""
    key = args.api_key or os.environ.get("SVM_API_KEY", "")
    if key or is_loopback(args.host):
        return key.strip()
    if os.environ.get("SPACE_ID"):
        raise SystemExit("Set an SVM_API_KEY secret in the Space settings first (any long random string).")
    key_file = SCRIPT_DIR / ".api_key"
    if key_file.exists():
        return key_file.read_text().strip()
    key = secrets.token_urlsafe(32)
    key_file.write_text(key + "\n")
    os.chmod(key_file, stat.S_IRUSR | stat.S_IWUSR)
    log.warning("Generated an API key and saved it to %s", key_file)
    return key


def main():
    parser = argparse.ArgumentParser(description="Speech server for SVM Subtitles")
    parser.add_argument("--host", default="0.0.0.0", help="address to listen on (default: all interfaces)")
    parser.add_argument("--port", type=int, default=int(os.environ.get("PORT", 8000)))
    parser.add_argument("--model", default="auto",
                        help="speech model: auto, tiny, base, small, medium, large-v3 (add .en for English-only)")
    parser.add_argument("--live-model", default="", help="faster model for live captions (default: base)")
    parser.add_argument("--language", default="auto", help="default language, or 'auto' to detect it")
    parser.add_argument("--engine", default="auto", choices=["auto", "faster-whisper", "openai-whisper"])
    parser.add_argument("--device", default="auto", help="cpu, cuda or auto")
    parser.add_argument("--compute-type", default="auto", help="faster-whisper only: int8, float16, ...")
    parser.add_argument("--threads", type=int, default=0, help="CPU threads (0 = engine default)")
    parser.add_argument("--tts-voice", default="en_US-lessac-medium", help="Piper voice, see rhasspy/piper-voices")
    parser.add_argument("--voices-dir", default=str(SCRIPT_DIR / "voices"))
    parser.add_argument("--no-tts", action="store_true", help="turn text-to-speech off")
    parser.add_argument("--no-live", action="store_true", help="turn live captions off")
    parser.add_argument("--api-key", default="", help="required key (default: $SVM_API_KEY or a generated one)")
    parser.add_argument("--tls-cert", default="", help="certificate file to serve HTTPS")
    parser.add_argument("--tls-key", default="", help="private key file to serve HTTPS")
    parser.add_argument("--log-text", action="store_true", help="also log what was said (off for privacy)")
    parser.add_argument("--behind-proxy", action="store_true",
                        help="running behind a reverse proxy (automatic on Hugging Face)")
    args = parser.parse_args()

    logging.basicConfig(level=logging.INFO, format="%(asctime)s %(levelname)s %(message)s")
    logging.getLogger("httpx").setLevel(logging.WARNING)
    api_key = resolve_api_key(args)
    app = App(args, api_key)

    server = ThreadingHTTPServer((args.host, args.port), make_handler(app))
    server.daemon_threads = True
    scheme = "http"
    if args.tls_cert:
        context = ssl.SSLContext(ssl.PROTOCOL_TLS_SERVER)
        context.minimum_version = ssl.TLSVersion.TLSv1_2
        context.load_cert_chain(args.tls_cert, args.tls_key or None)
        server.socket = context.wrap_socket(server.socket, server_side=True)
        scheme = "https"

    log.info("Features: %s", ", ".join(app.health()["features"]))
    log.info("Listening on %s://%s:%d/v1/audio/transcriptions", scheme, args.host, args.port)
    if api_key and not os.environ.get("SPACE_ID"):
        log.info("API key for the mod's config (transcription.remote.apiKey): %s", api_key)
    elif not api_key:
        log.info("No API key: only programs on this computer can connect")
    try:
        server.serve_forever()
    except KeyboardInterrupt:
        pass


if __name__ == "__main__":
    main()
