#!/usr/bin/env python3
"""
Companion speech-to-text server for SVM Subtitles.

Run this on any computer with some CPU (or GPU) to spare, then point the mod at it with
transcription.remote.url = "http://<that computer>:8000/v1/audio/transcriptions".
The game then only uploads short clips, so a weak PC or server doesn't have to run Whisper itself.

It speaks the OpenAI /v1/audio/transcriptions protocol, so anything else that speaks it works too.

Engines (first one installed wins, or pick one with --engine):
  faster-whisper   pip install faster-whisper    (recommended: about 4x faster than openai-whisper on CPU)
  openai-whisper   pip install openai-whisper    (https://github.com/openai/whisper, needs PyTorch)
"""

import argparse
import io
import json
import logging
import threading
import time
import wave
from email.parser import BytesParser
from email.policy import HTTP
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer

import numpy as np

MAX_UPLOAD_BYTES = 25 * 1024 * 1024
TRANSCRIBE_PATHS = {"/v1/audio/transcriptions", "/audio/transcriptions", "/inference"}

log = logging.getLogger("whisper-server")


def load_engine(engine, model, device, compute_type, threads):
    """Returns a function (float32 16 kHz audio, language or None) -> text."""
    if engine in ("auto", "faster-whisper"):
        try:
            from faster_whisper import WhisperModel
        except ImportError:
            if engine == "faster-whisper":
                raise
        else:
            if compute_type == "auto":
                compute_type = "float16" if device == "cuda" else "int8"
            fw = WhisperModel(model, device=device if device != "auto" else "auto",
                              compute_type=compute_type, cpu_threads=threads)

            def run_faster(audio, language):
                segments, _ = fw.transcribe(audio, language=language, beam_size=1, temperature=0.0,
                                            condition_on_previous_text=False, vad_filter=False)
                return "".join(segment.text for segment in segments).strip()

            return "faster-whisper", run_faster

    import torch
    import whisper

    if device == "auto":
        device = "cuda" if torch.cuda.is_available() else "cpu"
    if threads > 0:
        torch.set_num_threads(threads)
    ow = whisper.load_model(model, device=device)

    def run_openai(audio, language):
        result = ow.transcribe(audio, language=language, fp16=(device == "cuda"), temperature=0.0,
                               condition_on_previous_text=False)
        return result["text"].strip()

    return "openai-whisper", run_openai


def read_wav(data):
    """Decodes a 16-bit PCM WAV into float32 mono 16 kHz, which both engines accept directly (no ffmpeg needed)."""
    with wave.open(io.BytesIO(data)) as wav:
        if wav.getsampwidth() != 2:
            raise ValueError("only 16-bit PCM WAV is supported")
        rate = wav.getframerate()
        channels = wav.getnchannels()
        frames = wav.readframes(wav.getnframes())
    audio = np.frombuffer(frames, dtype="<i2").astype(np.float32) / 32768.0
    if channels > 1:
        audio = audio.reshape(-1, channels).mean(axis=1)
    if rate != 16000 and len(audio) > 0:
        target = np.linspace(0, len(audio) - 1, int(len(audio) * 16000 / rate))
        audio = np.interp(target, np.arange(len(audio)), audio).astype(np.float32)
    return audio


def parse_multipart(content_type, body):
    """Returns (fields, file bytes) from a multipart/form-data request body."""
    message = BytesParser(policy=HTTP).parsebytes(
        b"Content-Type: " + content_type.encode("latin-1") + b"\r\n\r\n" + body)
    if not message.is_multipart():
        raise ValueError("expected multipart/form-data")
    fields, upload = {}, None
    for part in message.iter_parts():
        name = part.get_param("name", header="content-disposition")
        payload = part.get_payload(decode=True) or b""
        if part.get_filename() is not None or name == "file":
            upload = payload
        elif name:
            fields[name] = payload.decode("utf-8", "replace").strip()
    return fields, upload


def make_handler(transcribe, engine_name, default_language, api_key):
    lock = threading.Lock()  # the models are not thread-safe; requests take turns

    class Handler(BaseHTTPRequestHandler):
        server_version = "SVMSubtitlesWhisper/1.0"

        def do_GET(self):
            if self.path.rstrip("/") in ("", "/health"):
                self.reply(200, {"status": "ok", "engine": engine_name})
            else:
                self.reply(404, {"error": {"message": "not found"}})

        def do_POST(self):
            if self.path.split("?")[0].rstrip("/") not in TRANSCRIBE_PATHS:
                return self.reply(404, {"error": {"message": "not found"}})
            if api_key and self.headers.get("Authorization", "") != "Bearer " + api_key:
                return self.reply(401, {"error": {"message": "invalid api key"}})
            length = int(self.headers.get("Content-Length") or 0)
            if length <= 0 or length > MAX_UPLOAD_BYTES:
                return self.reply(413, {"error": {"message": "missing or too large upload"}})
            try:
                fields, upload = parse_multipart(self.headers.get("Content-Type", ""), self.rfile.read(length))
                if not upload:
                    raise ValueError("no file in the request")
                audio = read_wav(upload)
            except Exception as e:  # noqa: BLE001 - report any bad upload to the client
                return self.reply(400, {"error": {"message": str(e)}})

            language = fields.get("language") or default_language
            if language in ("", "auto"):
                language = None
            started = time.perf_counter()
            with lock:
                text = transcribe(audio, language)
            took = time.perf_counter() - started
            log.info("%.1f s of audio in %.2f s: %s", len(audio) / 16000, took, text)

            if fields.get("response_format") == "text":
                return self.reply_text(200, text)
            return self.reply(200, {"text": text})

        def reply(self, status, payload):
            self.reply_text(status, json.dumps(payload), "application/json")

        def reply_text(self, status, text, content_type="text/plain; charset=utf-8"):
            data = text.encode("utf-8")
            self.send_response(status)
            self.send_header("Content-Type", content_type)
            self.send_header("Content-Length", str(len(data)))
            self.end_headers()
            self.wfile.write(data)

        def log_message(self, fmt, *args):
            log.debug("%s - %s", self.address_string(), fmt % args)

    return Handler


def main():
    parser = argparse.ArgumentParser(description="Whisper speech-to-text server for SVM Subtitles")
    parser.add_argument("--host", default="0.0.0.0", help="address to listen on (default: all interfaces)")
    parser.add_argument("--port", type=int, default=8000)
    parser.add_argument("--model", default="base.en",
                        help="tiny.en, base.en, small.en, medium.en, large-v3, ... (.en models are English only)")
    parser.add_argument("--language", default="en", help="default language, or 'auto' to detect it")
    parser.add_argument("--engine", default="auto", choices=["auto", "faster-whisper", "openai-whisper"])
    parser.add_argument("--device", default="auto", help="cpu, cuda or auto")
    parser.add_argument("--compute-type", default="auto", help="faster-whisper only: int8, float16, ...")
    parser.add_argument("--threads", type=int, default=0, help="CPU threads (0 = engine default)")
    parser.add_argument("--api-key", default="", help="require 'Authorization: Bearer <key>' if set")
    args = parser.parse_args()

    logging.basicConfig(level=logging.INFO, format="%(asctime)s %(levelname)s %(message)s")
    log.info("Loading %s (first start downloads it)...", args.model)
    engine_name, transcribe = load_engine(args.engine, args.model, args.device, args.compute_type, args.threads)
    transcribe(np.zeros(16000, dtype=np.float32), None if args.language == "auto" else args.language)  # warm up

    server = ThreadingHTTPServer((args.host, args.port),
                                 make_handler(transcribe, engine_name, args.language, args.api_key))
    log.info("%s is listening on http://%s:%d/v1/audio/transcriptions", engine_name, args.host, args.port)
    try:
        server.serve_forever()
    except KeyboardInterrupt:
        pass


if __name__ == "__main__":
    main()
