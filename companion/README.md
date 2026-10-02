---
title: SVM Subtitles Speech Server
emoji: 🎙️
colorFrom: indigo
colorTo: green
sdk: docker
app_port: 7860
pinned: false
license: mit
short_description: Speech-to-text, live captions and TTS for SVM Subtitles
---

# SVM Subtitles speech server

This is the "companion server" for the [SVM Subtitles](https://github.com/harryforest2003/SVM-subtitles) Minecraft mod. It does the heavy work so your game doesn't have to:

- **Speech-to-text** with Whisper, using a bigger and more accurate model than a game PC could run.
- **Live captions** that update word by word while someone is talking.
- **Translation** from any language into English.
- **Text-to-speech** for `/tts`, using [Piper](https://github.com/OHF-Voice/piper1-gpl).

The game only uploads short audio clips, so this takes almost no CPU on your PC or Minecraft server.

## Option 1: free hosting on Hugging Face (no spare computer needed)

1. Make a free account at [huggingface.co](https://huggingface.co).
2. Click **New Space**. Pick any name, choose **Docker → Blank** and the free **CPU basic** hardware.
   Leave it **Public**. That only makes the code visible (it's open source anyway); nobody can use the server without your key.
3. In the Space, open **Settings → Variables and secrets → New secret**. Name it `SVM_API_KEY`. For the value, use a long random password, for example the output of:
   ```bash
   python3 -c "import secrets; print(secrets.token_urlsafe(32))"
   ```
4. Open **Files → Add file → Upload files** and upload everything in this folder: `Dockerfile`, `README.md`, `requirements.txt` and `whisper_server.py`.
5. Wait until the Space says **Running**. The first build takes about 5 minutes.
6. In the mod's settings (Mod Menu → SVM Subtitles → Speech recognition), or in `config/svm_subtitles/config.json`:
   - Speech server URL: `https://<your-name>-<space-name>.hf.space/v1/audio/transcriptions`
   - API key: the secret from step 3.

Good to know:
- Free Spaces have 2 shared CPUs. That's comfortable for a handful of players. If sentences start arriving late, change `MODEL=small` to `MODEL=base` in the Dockerfile.
- A free Space goes to sleep after 2 days without use and takes about a minute to wake up again.
- Hugging Face runs the hardware, so the audio passes through their servers while it's processed (it's encrypted on the way). This server never stores it. For complete privacy, use option 2.

## Option 2: any computer you own

This works on a gaming PC, a laptop or a home server, with or without an NVIDIA GPU.

```bash
cd companion
python3 -m venv .venv
.venv/bin/pip install -r requirements.txt          # Windows: .venv\Scripts\pip install -r requirements.txt
.venv/bin/python whisper_server.py
```

On first start it downloads the models and prints an **API key**, which is also saved in `.api_key` next to the script and readable only by you. In the mod, set:
- URL: `http://<that computer's local IP>:8000/v1/audio/transcriptions`
- API key: the printed key

Plain `http://` is only allowed on your own network. The mod refuses to send voices unencrypted over the internet.

Useful options:

| Option | What it does |
| --- | --- |
| `--model small` / `medium` / `large-v3` | More accurate, slower. The default `auto` picks `small` on a 4+ core CPU and `large-v3` on a GPU. |
| `--model small.en` | English only, slightly more accurate for English, no translation |
| `--live-model base` | The faster model used for live captions |
| `--device cuda` | Use an NVIDIA GPU |
| `--no-live`, `--no-tts` | Turn live captions or text-to-speech off |
| `--tts-voice en_GB-alan-medium` | Another [Piper voice](https://huggingface.co/rhasspy/piper-voices) |
| `--tls-cert cert.pem --tls-key key.pem` | Serve HTTPS directly, if you expose it to the internet yourself |
| `--log-text` | Also log what was said (off by default, for privacy) |

## Checking that it works

```bash
python3 test_server.py --url https://<your-space>.hf.space --key <your key>
python3 test_server.py --url ... --key ... --security    # also checks that bad requests are refused
```

## Security

- **API key.** Every request needs your API key, except a plain "is it up?" ping. When the server can be reached from other machines, it won't start without a key.
- **Key handling.** Keys are compared in constant time. After 10 wrong keys in a minute, that address is blocked for a minute.
- **Nothing is stored.** Audio and text are processed in memory and never written to disk. Live-caption audio is forgotten 20 seconds after the last update. What people say is not logged unless you pass `--log-text`.
- **Limits.** Uploads are capped at 8 MB, 2 minutes of audio, 500 characters of text-to-speech and 64 live sessions. Slow or stuck connections time out after 30 seconds.
- **No internal details leak.** Error replies are generic.
