# SVM Subtitles

Live subtitles for [Simple Voice Chat](https://modrinth.com/plugin/simple-voice-chat). This Fabric mod listens to what players say in voice chat, turns it into text with [OpenAI's Whisper](https://github.com/openai/whisper), and posts it in chat with the speaker's name:

```
[Voice] Steve: anyone got spare iron? I'm at the base
[Voice] Alex: yeah, coming over
```

Click a name and the chat box opens with `/msg Steve ` (or whatever private message command the server uses), ready for you to type a reply.

**Minecraft 26.2 · Fabric · requires Fabric API and Simple Voice Chat 2.6+**

## How it works

The same jar works in two places:

| Installed on | What happens | Who sees it |
| --- | --- | --- |
| **The server** (dedicated, or your singleplayer/LAN world) | The server transcribes everyone and sends the subtitles as normal chat messages. Players don't need this mod installed; they only need Simple Voice Chat to talk. | Everyone (configurable, see *Who sees what*) |
| **Your client**, on a server that doesn't run it | Your game transcribes the voices you hear. Nothing is sent to the server. | Only you |

If both have it, your client notices the server is already doing the work and stays idle, so nothing appears twice.

## Will it lag my (potato) PC?

Speech recognition runs **inside the game** when your machine can handle it, and **switches itself off** when it can't:

1. **Hardware check.** With fewer than 4 CPU threads or less than 4 GB of RAM it doesn't even try.
2. **Speed test.** Otherwise it downloads the speech model once (`base.en-q5_1`, 60 MB) and times an 11-second sample. If transcription takes longer than half the length of the audio (`maxRealtimeFactor` = 0.5), it turns off and tells you in chat.
3. **Kept off the game threads.** Whisper runs on low-priority background threads and uses at most half of your CPU threads (max 4). The game thread never waits for it.
4. **Drops instead of piling up.** If people talk faster than your PC can transcribe, old sentences are skipped rather than queued forever, and you get a one-time warning suggesting a smaller model.

For reference, a 2020 MacBook Pro (4-core i5) transcribes 11 s of speech in 1.7 s with `base.en-q5_1` (0.15× real time) and in 1.3 s with `tiny.en-q5_1`.

If your PC is too slow, you can still have subtitles: run the [companion server](#companion-whisper-server) on another computer, or point the mod at a cloud speech API. The game then only uploads short audio clips, which costs almost nothing.

## Installing

1. Install [Fabric Loader](https://fabricmc.net/use/) for Minecraft 26.2, [Fabric API](https://modrinth.com/mod/fabric-api) and [Simple Voice Chat](https://modrinth.com/plugin/simple-voice-chat).
2. Put `svm-subtitles-<version>.jar` in the `mods` folder (on the server, on your client, or both).
3. Start the game or server. The first time, it downloads the speech model (60 MB) and says in chat or the console when subtitles are ready.

Local recognition works on Windows (x64), Linux (x64, arm64, armv7) and macOS (Intel and Apple Silicon). On Windows it needs the [Visual C++ Redistributable](https://learn.microsoft.com/cpp/windows/latest-supported-vc-redist), which most gaming PCs already have.

## Commands

**Server mode** (anyone):

| Command | |
| --- | --- |
| `/subtitles` | Shows whether your voice is transcribed, plus these options |
| `/subtitles optout` / `optin` | Stop / resume turning **your** voice into chat messages |
| `/subtitles hide` / `show` | Stop / resume showing subtitles **to you** |

Operators can also use `/subtitles status` (backend, speed, skipped sentences), `/subtitles test` (transcribes the built-in sample) and `/subtitles reload` (re-reads the config).

**Client mode:** `/clientsubtitles on|off|status|test|reload`

## Who sees what (server mode)

Subtitles follow the same rules as hearing:

- **Normal speech** goes to everyone, or only to players in voice range with `"audience": "nearby"`.
- **Group voice** (non-open groups) only goes to members of that group.
- **Whispers** only go to players within whisper range.

Players who used `/subtitles optout` are never transcribed. Players who used `/subtitles hide` never receive subtitles.

## Clickable names

With `"privateMessageCommand": "auto"` the mod looks through the server's commands for `msg`, `tell`, `w`, `whisper`, `m`, `pm`, `dm` and `message`, and uses the first one it finds. In client mode it only considers commands you're allowed to use. To force one, use e.g. `"/tell {player} "` or `"/m {player} "`. Use `"none"` for names that aren't clickable. Shift-clicking a name inserts it into your message, like in vanilla chat.

## Configuration

`config/svm_subtitles/config.json` is created on first start. Edit it, then run `/subtitles reload` (server) or `/clientsubtitles reload` (client).

```jsonc
{
  "transcription": {
    "backend": "auto",           // auto | local | remote | off (see "Will it lag my PC?")
    "language": "en",            // "auto" or a code like "de"; needs a multilingual model (no ".en")
    "maxQueuedClips": 8,         // sentences allowed to wait; older ones are skipped beyond this
    "maxDelaySeconds": 20,       // sentences that waited longer are skipped instead of arriving late
    "local": {
      "model": "base.en-q5_1",   // tiny.en-q5_1 (faster), small.en-q5_1 (more accurate), base-q5_1 (multilingual), or a path to a .bin
      "threads": 0,              // 0 = half your CPU threads, max 4
      "autoDownload": true,
      "downloadUrl": "https://huggingface.co/ggerganov/whisper.cpp/resolve/main/ggml-{model}.bin",
      "maxRealtimeFactor": 0.5   // auto mode: turn local recognition off if it's slower than this
    },
    "remote": {
      "url": "",                 // e.g. http://192.168.1.50:8000/v1/audio/transcriptions
      "apiKey": "",
      "model": "whisper-1",
      "timeoutSeconds": 30,
      "parallelRequests": 2
    }
  },
  "segmentation": {
    "silenceThreshold": 250,     // how quiet counts as a pause (raise it if background noise keeps sentences open)
    "endOfSpeechMs": 700,        // a pause this long ends a sentence
    "maxClipSeconds": 15,        // long monologues are split into pieces this long
    "minSpeechMs": 300           // shorter sounds (coughs, clicks) are ignored
  },
  "chat": {
    "format": "&8[&3Voice&8] &b{player}&7: &f{text}",   // & colour codes and &#RRGGBB work
    "privateMessageCommand": "auto",
    "hoverText": "Click to message {player}",
    "ignoredPhrases": ["thanks for watching", "..."]     // Whisper invents these from noise
  },
  "server": {
    "enabled": true,
    "audience": "everyone",      // everyone | nearby
    "transcribeGroups": true,
    "transcribeWhispers": true,
    "logToConsole": true
  },
  "client": {
    "enabled": true,
    "includeOwnVoice": true,     // also show what you say
    "deferToServer": true        // stay idle when the server transcribes
  }
}
```

Model files are cached in `config/svm_subtitles/models/`. Server-mode opt-outs are stored in `config/svm_subtitles/players.json`.

## Companion Whisper server

`companion/whisper_server.py` runs Whisper on any computer you like (a gaming PC, a home server, a machine with a GPU) and lets the game send it audio over the network. It speaks the standard OpenAI `/v1/audio/transcriptions` protocol.

```bash
cd companion
python3 -m venv .venv
.venv/bin/pip install -r requirements.txt     # Windows: .venv\Scripts\pip install -r requirements.txt
.venv/bin/python whisper_server.py --model base.en
```

Then set this in the mod's config:

```json
"remote": { "url": "http://<that computer's IP>:8000/v1/audio/transcriptions" }
```

Useful options:
- `--model small.en`: better accuracy if the machine is fast.
- `--device cuda`: use an NVIDIA GPU.
- `--api-key SECRET`: require the same `apiKey` in the mod's config.
- `--engine openai-whisper`: use the original `openai-whisper` package instead of the faster `faster-whisper`.

The server listens on all network interfaces by default. Only expose it to networks you trust, or set an API key.

**Cloud APIs:** any OpenAI-compatible speech-to-text service works too. For example, OpenAI uses `"url": "https://api.openai.com/v1/audio/transcriptions"`, `"model": "whisper-1"` and your API key. Check your provider's docs for its URL, model names and pricing.

## Privacy

- Local mode never sends audio anywhere; it is processed in memory and discarded.
- Remote mode sends short clips of speech to the URL you configured, and nowhere else.
- Server mode posts what people say to chat (and to the server console unless `logToConsole` is off). Let your players know, and point them to `/subtitles optout`.

## Building

```bash
./gradlew build          # jar in build/libs/
./gradlew test           # unit tests
./gradlew runSelfTest    # opens a test world, plays a sample sentence through Simple Voice Chat in
                         # server mode and client mode, logs what arrives in chat, then quits
```

Optional integration tests:
- `SVM_TEST_MODEL=/path/to/ggml-base.en-q5_1.bin ./gradlew test` runs real whisper.cpp.
- `SVM_TEST_REMOTE_URL=http://127.0.0.1:8000/v1/audio/transcriptions ./gradlew test` tests a companion server.

Requires JDK 25.

## Credits

- [whisper.cpp](https://github.com/ggml-org/whisper.cpp) (MIT) through [whisper-jni](https://github.com/GiviMAD/whisper-jni) (Apache 2.0), bundled.
- [Whisper](https://github.com/openai/whisper) models by OpenAI (MIT).
- [Simple Voice Chat](https://github.com/henkelmax/simple-voice-chat) by henkelmax (required, not bundled).
- The speed-test sample is from President Kennedy's 1961 inaugural address (public domain), as shipped with whisper.cpp.

## License

MIT
