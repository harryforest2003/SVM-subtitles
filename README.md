# SVM Subtitles

Live subtitles for [Simple Voice Chat](https://modrinth.com/plugin/simple-voice-chat). This Fabric mod listens to what players say in voice chat, turns it into text with [OpenAI's Whisper](https://github.com/openai/whisper), and posts it in chat with the speaker's name:

```
[Voice] Steve: anyone got spare iron? I'm at the base
[Voice] Alex: [FR] Yes, I'm coming
[TTS] Sam: on my way!
```

**Minecraft 26.2 · Fabric · requires Fabric API and Simple Voice Chat 2.6+**

- **Clickable names:** click a name to start a private message (`/msg Steve `, or whatever the server uses).
- **Live captions:** words appear while someone is still talking, as one chat line that fills in.
- **Name alerts:** a ding and a highlight when someone says your name.
- **Translation:** speech in other languages becomes English text, for free.
- **Text-to-speech:** type `/tts hello` to be heard in voice chat without a microphone.
- **Moderation history:** operators can look up what was said when handling reports.
- **Settings screen** in Mod Menu.

## How it works

The same jar works in two places:

| Installed on | What happens | Who sees it |
| --- | --- | --- |
| **The server** (dedicated, or your singleplayer/LAN world) | The server transcribes everyone and sends the subtitles. Players don't need this mod; they only need Simple Voice Chat to talk. | Everyone (see *Who sees what*) |
| **Your client**, on a server that doesn't run it | Your game transcribes the voices you hear. Nothing is sent to the Minecraft server. | Only you |

If both have it, your client notices the server is already doing the work and stays idle, so nothing appears twice.

## Keeping the game lag-free

The speech recognition can run in one of three places:

1. **A speech server: no work for your PC (recommended).** The [companion server](companion/README.md) runs Whisper somewhere else and the game only uploads short audio clips. Live captions, text-to-speech and the bigger, more accurate model only ever run there. You can host it for **free on Hugging Face** (no spare computer needed) or on any PC you own.
2. **Inside the game, if your PC can handle it.** With no speech server set, the mod checks your hardware and times an 11-second test clip.
   - **Too weak:** with fewer than 4 CPU threads, less than 4 GB of RAM, or recognition slower than half real time, it switches itself off and tells you how to use a speech server instead.
   - **Fast enough:** it runs on low-priority background threads, with at most half your CPU threads (max 4). The game never waits for it.
3. **Off.**

For reference, a 2020 MacBook Pro (4-core i5) needs about 1.5 s to transcribe 11 s of speech with the in-game model.

## Installing

1. Install [Fabric Loader](https://fabricmc.net/use/) for Minecraft 26.2, [Fabric API](https://modrinth.com/mod/fabric-api) and [Simple Voice Chat](https://modrinth.com/plugin/simple-voice-chat). For the settings screen, also install [Mod Menu](https://modrinth.com/mod/modmenu) and [Cloth Config](https://modrinth.com/mod/cloth-config).
2. Put `svm-subtitles-<version>.jar` in the `mods` folder (on the server, on your client, or both).
3. Optional but recommended: [set up the speech server](companion/README.md), then enter its URL and API key in the settings (Mod Menu → SVM Subtitles → Speech recognition).

In-game recognition works on Windows (x64), Linux (x64, arm64, armv7) and macOS (Intel and Apple Silicon). On Windows it needs the [Visual C++ Redistributable](https://learn.microsoft.com/cpp/windows/latest-supported-vc-redist), which most gaming PCs already have.

## Features

### Live captions
While someone talks, their words appear as they go. You choose where in the settings, or with `live.display` in the config:
- **In chat (default):** one line that fills in and then becomes the finished subtitle.
- **On screen:** a caption box above the hotbar.
- **Off:** finished sentences only.

Players without the mod see live captions in the action bar. They can turn that off with `/subtitles live off`, and server owners can turn it off for everyone (`live.actionBar`). Live captions need the companion server, so they never cost your PC anything.

### Name alerts
When someone says your name (or your name without trailing numbers, e.g. "Harry" for `Harry_2003`), the word is highlighted and a sound plays. Add nicknames or other words to watch for in the settings. Your own speech never triggers it.

### Translation into English
Turn on **Translate into English** and speech in other languages arrives as English, tagged with the original language (`[FR]`). This is built into Whisper and costs nothing extra. It needs a multilingual model: the companion's default `small` is one, and in-game you'd use `base-q5_1` instead of `base.en-q5_1`.

### Text-to-speech
`/tts <message>` speaks your text in voice chat with a natural voice ([Piper](https://github.com/OHF-Voice/piper1-gpl)). Nearby players hear it, or only your group if you're in one. It also shows in chat as `[TTS] Name: message`.
- Server owners can set the voice, a length limit and a cooldown.
- Players can change the text-to-speech volume separately in Simple Voice Chat's volume settings.
- It needs the server to run this mod and a companion server.

### Moderation history
Operators can run `/subtitles history [player] [count]` to see what was recently said.
- It's kept for 7 days by default, in files readable only by the server's account, and older days are deleted automatically.
- Each time someone looks at it, that's logged in the console.
- Players are told when they join, and anyone who uses `/subtitles optout` is never transcribed or recorded.

### Accuracy
Whisper is given the names of online players and a list of Minecraft words (creeper, Nether, ...) as hints, so it spells them right. You can add words in the settings. The companion server also uses a bigger model with beam search and a voice-activity filter.

## Commands

| Command | Who | |
| --- | --- | --- |
| `/subtitles` | everyone | Your status and options |
| `/subtitles optout` / `optin` | everyone | Stop / resume turning **your** voice into text |
| `/subtitles hide` / `show` | everyone | Stop / resume showing subtitles **to you** |
| `/subtitles live off` / `on` | everyone | Live captions in your action bar |
| `/tts <message>` | everyone | Speak typed text in voice chat |
| `/subtitles history [player] [count]` | operators | What was said recently |
| `/subtitles status`, `test`, `reload` | operators | Speech engine status, a test run, re-read the config |
| `/clientsubtitles on\|off\|status\|test\|reload` | you (client) | Subtitles your own PC makes |

## Who sees what (server mode)

Subtitles follow the same rules as hearing:
- **Normal speech** goes to everyone, or only to players in voice range with `"audience": "nearby"`.
- **Group voice** (non-open groups) only goes to members of that group.
- **Whispers** only go to players within whisper range.

If group or whisper transcription is turned off, those voices aren't even sent for recognition.

## Clickable names

With `"privateMessageCommand": "auto"` the mod looks through the server's commands for `msg`, `tell`, `w`, `whisper`, `m`, `pm`, `dm` and `message`, and uses the first one it finds. In client mode it only considers commands you're allowed to use. To force one, use e.g. `"/tell {player} "`. Clicking only fills in the chat box; you still decide whether to send it.

## Settings

Use **Mod Menu → SVM Subtitles**, or edit `config/svm_subtitles/config.json` and run `/subtitles reload` (server) or `/clientsubtitles reload` (client). The main options:

```jsonc
{
  "transcription": {
    "backend": "auto",              // auto | remote | local | off
    "language": "en",               // or "auto", "de", ...
    "translateToEnglish": false,
    "accuracyHints": true,          // player names + the words below as spelling hints
    "vocabulary": ["Minecraft", "creeper", "Enderman", "Nether", "redstone", "..."],
    "local":  { "model": "base.en-q5_1", "threads": 0, "maxRealtimeFactor": 0.5 },
    "remote": { "url": "", "apiKey": "", "allowInsecureHttp": false }
  },
  "live":    { "enabled": true, "intervalMs": 1000, "display": "chat", "actionBar": true },
  "alerts":  { "enabled": true, "ownName": true, "words": [], "sound": "minecraft:block.note_block.pling", "volume": 1.0 },
  "tts":     { "enabled": true, "voice": "", "maxLength": 200, "cooldownSeconds": 3 },
  "history": { "enabled": true, "keepDays": 7 },
  "chat":    { "format": "&8[&3Voice&8] &b{player}&7: &f{text}", "ttsFormat": "&8[&3TTS&8] &b{player}&7: &f{text}",
               "privateMessageCommand": "auto" },
  "server":  { "enabled": true, "audience": "everyone", "transcribeGroups": true, "transcribeWhispers": true,
               "joinNotice": true, "logToConsole": true },
  "client":  { "enabled": true, "includeOwnVoice": true, "deferToServer": true },
  "segmentation": { "silenceThreshold": 250, "endOfSpeechMs": 700, "maxClipSeconds": 15, "minSpeechMs": 300 }
}
```

## Security and privacy

Voice is personal data, so the mod and the companion server are built to keep it safe.

**In the mod:**
- **No unencrypted voice over the internet.** Audio is never sent over plain `http://` to anything outside your own network. Only `https://` is allowed (local network addresses and Tailscale are fine).
- **No redirects.** The mod never follows redirects, so a server can't bounce your audio and API key somewhere else.
- **Certificates are always checked.**
- **Your API key stays hidden.** It isn't shown again in the settings screen after you save it (so it can't leak on stream), is never logged, and the config file is made readable only by you.
- **Text can't mess with chat.** Text from a speech server, typed with `/tts`, or sent by a Minecraft server is stripped of formatting codes and control characters. A server's suggested private message command only fills in your chat box.

**On the companion server:**
- **API key required** whenever it's reachable from other machines. Keys are compared in constant time, and repeated wrong keys are throttled.
- **Nothing is stored.** Audio is never written to disk, and what was said isn't logged.
- **Limits** on upload size, audio length, text length and live sessions. Details in [companion/README.md](companion/README.md#security).

**On servers:**
- **Players are told.** A notice on join says voice is shown as text, kept for moderation, and (if so) processed by a separate speech server. `/subtitles optout` stops all of it for that player.
- **Group and whisper privacy.** Group and whisper subtitles only reach people who could hear them.
- **Locked-down history.** It's only for operators, every lookup is logged, its files are owner-only, and it expires.

**Who can hear the audio:**
- **In-game recognition:** nothing leaves your PC.
- **Your own companion server:** the audio stays on your machines.
- **A hosted service** (a Hugging Face Space, a cloud API): that company's servers process the audio. Your server's code doesn't store it, but their own privacy policy applies.

## Building

```bash
./gradlew build          # jar in build/libs/
./gradlew test           # unit tests
./gradlew runSelfTest    # opens a test world and checks every feature, logging what reaches chat, then quits
```

Optional integration tests:
- `SVM_TEST_MODEL=/path/to/ggml-base.en-q5_1.bin ./gradlew test` runs real whisper.cpp.
- `SVM_TEST_REMOTE_URL=http://127.0.0.1:8000/v1/audio/transcriptions SVM_TEST_REMOTE_KEY=... ./gradlew test` tests a companion server.
- `SVMDEV_REMOTE_URL=... SVMDEV_REMOTE_KEY=... ./gradlew runSelfTest` runs the self test with live captions and text-to-speech through a companion server.

Requires JDK 25.

## Credits

- [whisper.cpp](https://github.com/ggml-org/whisper.cpp) (MIT) through [whisper-jni](https://github.com/GiviMAD/whisper-jni) (Apache 2.0), bundled.
- [Whisper](https://github.com/openai/whisper) models by OpenAI (MIT).
- The companion server uses [faster-whisper](https://github.com/SYSTRAN/faster-whisper) (MIT) and [Piper](https://github.com/OHF-Voice/piper1-gpl) (GPL-3.0). These are installed by pip, not bundled.
- [Simple Voice Chat](https://github.com/henkelmax/simple-voice-chat) by henkelmax (required, not bundled).
- The speed-test sample is from President Kennedy's 1961 inaugural address (public domain), as shipped with whisper.cpp.

## License

MIT
