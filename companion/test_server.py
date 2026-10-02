#!/usr/bin/env python3
"""
Checks a running companion server, e.g. right after deploying it:

    python test_server.py --url https://you-svm-subtitles.hf.space --key YOUR_KEY

Uses only the standard library. --security also checks that bad requests are rejected (afterwards your
address is throttled for a minute, so run it last).
"""

import argparse
import io
import json
import math
import struct
import sys
import time
import urllib.error
import urllib.request
import uuid
import wave
from pathlib import Path

SAMPLE = Path(__file__).resolve().parent.parent / "src/main/resources/svm_subtitles/benchmark.wav"


def request(url, key, data=None, content_type=None, method=None):
    headers = {"User-Agent": "svm-test"}
    if key:
        headers["Authorization"] = "Bearer " + key
    if content_type:
        headers["Content-Type"] = content_type
    req = urllib.request.Request(url, data=data, headers=headers, method=method)
    try:
        with urllib.request.urlopen(req, timeout=120) as response:
            return response.status, response.headers.get("Content-Type", ""), response.read()
    except urllib.error.HTTPError as e:
        return e.code, e.headers.get("Content-Type", ""), e.read()
    except (urllib.error.URLError, ConnectionError) as e:
        # The server may hang up on an oversized upload before reading it, which also counts as rejected.
        return 0, "", str(e).encode()


def multipart(fields, file_field=None, file_bytes=None):
    boundary = "svmtest" + uuid.uuid4().hex
    out = io.BytesIO()
    for name, value in fields.items():
        out.write(f"--{boundary}\r\nContent-Disposition: form-data; name=\"{name}\"\r\n\r\n{value}\r\n".encode())
    if file_field:
        out.write(f"--{boundary}\r\nContent-Disposition: form-data; name=\"{file_field}\"; filename=\"a.wav\"\r\n"
                  f"Content-Type: audio/wav\r\n\r\n".encode())
        out.write(file_bytes)
        out.write(b"\r\n")
    out.write(f"--{boundary}--\r\n".encode())
    return out.getvalue(), "multipart/form-data; boundary=" + boundary


def wav(frames, rate=16000):
    out = io.BytesIO()
    with wave.open(out, "wb") as w:
        w.setnchannels(1)
        w.setsampwidth(2)
        w.setframerate(rate)
        w.writeframes(frames)
    return out.getvalue()


def check(name, condition, detail=""):
    print(("PASS " if condition else "FAIL ") + name + (f": {detail}" if detail else ""))
    return condition


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("--url", default="http://127.0.0.1:8000")
    parser.add_argument("--key", default="")
    parser.add_argument("--french", help="optional French WAV to test translation")
    parser.add_argument("--security", action="store_true", help="also test that bad requests are rejected")
    args = parser.parse_args()
    base = args.url.rstrip("/")
    ok = True

    status, _, body = request(base + "/health", args.key)
    health = json.loads(body) if status == 200 else {}
    ok &= check("health", status == 200, json.dumps(health))
    features = health.get("features", [])

    sample = SAMPLE.read_bytes()
    data, ctype = multipart({"model": "whisper-1", "language": "en", "prompt": "Minecraft voice chat."}, "file", sample)
    started = time.time()
    status, _, body = request(base + "/v1/audio/transcriptions", args.key, data, ctype)
    text = json.loads(body).get("text", "") if status == 200 else body.decode(errors="replace")
    ok &= check("transcribe", status == 200 and "your country" in text.lower(), f"{time.time() - started:.1f} s: {text}")

    if "translate" in features and args.french:
        data, ctype = multipart({"model": "whisper-1"}, "file", Path(args.french).read_bytes())
        status, _, body = request(base + "/v1/audio/translations", args.key, data, ctype)
        result = json.loads(body) if status == 200 else {}
        ok &= check("translate", status == 200 and result.get("language") != "en", json.dumps(result))

    if "stream" in features:
        with wave.open(io.BytesIO(sample)) as w:
            pcm = w.readframes(w.getnframes())
        session = str(uuid.uuid4())
        partials = []
        for offset in range(0, len(pcm), 32000 * 2):
            data, ctype = multipart({"session": session, "language": "en"}, "audio", wav(pcm[offset:offset + 64000]))
            status, _, body = request(base + "/svm/v1/stream", args.key, data, ctype)
            if status != 200:
                partials.append(f"HTTP {status}")
                break
            partials.append(json.loads(body).get("text"))
        ok &= check("live captions", len(partials) >= 4 and "your country" in (partials[-1] or "").lower(),
                    " | ".join(str(p) for p in partials))

    if "tts" in features:
        payload = json.dumps({"model": "piper", "input": "Hello, anyone got spare iron?", "response_format": "wav"})
        started = time.time()
        status, ctype, body = request(base + "/v1/audio/speech", args.key, payload.encode(), "application/json")
        seconds = 0.0
        if status == 200:
            with wave.open(io.BytesIO(body)) as w:
                seconds = w.getnframes() / w.getframerate()
        ok &= check("text-to-speech", status == 200 and seconds > 1, f"{seconds:.1f} s of audio in {time.time() - started:.1f} s")

    if args.security:
        status, _, _ = request(base + "/", None)
        ok &= check("public ping", status == 200)
        status, _, _ = request(base + "/health", None)
        ok &= check("no key rejected", status == 401)
        data, ctype = multipart({}, "file", b"definitely not audio")
        status, _, _ = request(base + "/v1/audio/transcriptions", args.key, data, ctype)
        ok &= check("bad audio rejected", status == 400)
        tone = b"".join(struct.pack("<h", int(8000 * math.sin(i / 10))) for i in range(16000))
        data, ctype = multipart({"session": "../../etc/passwd"}, "audio", wav(tone))
        status, _, _ = request(base + "/svm/v1/stream", args.key, data, ctype)
        ok &= check("bad session id rejected", status == 400)
        status, _, _ = request(base + "/v1/audio/transcriptions", args.key, b"x" * (9 * 1024 * 1024), ctype)
        ok &= check("huge upload rejected", status in (413, 0), f"HTTP {status}" if status else "connection closed")
        statuses = [request(base + "/health", "wrong-key")[0] for _ in range(12)]
        ok &= check("guessing keys gets throttled", statuses[-1] == 429, str(statuses))
        status, _, _ = request(base + "/health", args.key)
        ok &= check("throttled address is blocked even with the right key", status == 429)

    print("ALL PASSED" if ok else "SOME CHECKS FAILED")
    sys.exit(0 if ok else 1)


if __name__ == "__main__":
    main()
