# Claude Companion (Fabric, Minecraft 1.20.1)

Claude as a person in your world. Talk to it out loud, it talks back, walks around with you, builds with any block (vanilla or modded), breaks blocks, fights mobs, hands you items, looks through your eyes, and can run any command. Powered by the Claude API.

Plays fine alongside Sodium, Iris, Controlify, Kappa shaders and Patrix (it adds no mixins and touches no rendering or input code).

## 1. Get the .jar

**Easiest, no Java install needed (GitHub builds it for you)**
1. Make a free GitHub account, click New repository, make it private, create it.
2. Click "uploading an existing file" and drag in everything inside this folder (including the hidden `.github` folder; on a Mac press Cmd+Shift+. in Finder to see it). Commit.
3. Open the Actions tab. The "Build Claude Companion jar" run takes about 5 minutes.
4. Open the finished run, download `claude-companion-jar` at the bottom, unzip it. That's your `claude-companion-1.0.0.jar`.

**Or build locally**
Install JDK 21 (Adoptium Temurin), open a terminal in this folder, run `./gradlew build` (Windows: `gradlew.bat build`). The jar appears in `build/libs/`.

## 2. Install
Put these in `.minecraft/mods` (Fabric Loader for 1.20.1):
- `claude-companion-1.0.0.jar`
- Fabric API for 1.20.1 (you likely already have it)

## 2b. Voice setup (one-time)
Install **Python 3.12** from python.org (on Windows, tick "Add python.exe to PATH"). Everything else installs itself into `config/claudecompanion/` the first time you launch, with progress shown in chat.

**Sesame CSM-1B (default voice).** This is the most natural voice. On top of Sesame's base model, the mod applies [Tachyeon/csm-1b-conversational-finetuned](https://huggingface.co/Tachyeon/csm-1b-conversational-finetuned), a fine-tune trained on a single warm, expressive female speaker (Expresso speaker ex04). It also grabs a real clip of that speaker from the [Expresso dataset](https://huggingface.co/datasets/ylacombe/expresso) (CC BY-NC 4.0, fine for personal use) as the voice prompt, so the voice stays consistent. If either step fails, it falls back to the bundled `warm` voice prompt on plain CSM-1B. Setup:
1. Make a free account at huggingface.co, open huggingface.co/sesame/csm-1b and accept the terms.
2. In Settings, Access Tokens, create a **Read** token and paste it into `"huggingfaceToken"` in `config/claudecompanion.json`.
3. Restart Minecraft. The first run downloads PyTorch, the model and the fine-tune (several GB), so give it a while.

Sesame really wants an **NVIDIA GPU**. It uses roughly 4 to 5 GB of video memory, which competes with heavy shaders and 128x textures, and on CPU alone it lags. While Sesame installs or loads, or if it can't run, Claude speaks with **Kokoro (Emma)**. Kokoro is light (about 350 MB) and needs no account. Set `"ttsEngine": "kokoro"` to use only Kokoro.

Logs: `config/claudecompanion/csm/server.log` and `config/claudecompanion/kokoro/server.log`.

## 3. API key
Launch once, quit, open `.minecraft/config/claudecompanion.json` and paste your key from console.anthropic.com into `"apiKey"`. API usage is billed separately from a Claude.ai subscription. (Or set the `ANTHROPIC_API_KEY` environment variable.)

## 4. Play
Open a singleplayer world and just talk. Claude appears beside you on your first sentence. The first launch downloads a 40 MB offline speech model automatically.

- **K** mutes or unmutes the mic (rebindable in Controls, and bindable to a controller through Controlify)
- Typing in chat also talks to Claude
- `/claude summon`, `/claude dismiss`, `/claude follow`, `/claude stay`, `/claude reset` (clears memory), `/claude reload` (re-reads config), `/claude ask <message>`
- Say "stop" or "wait" to interrupt whatever it's doing

## Config (`config/claudecompanion.json`)
| Setting | What it does |
|---|---|
| `model` | `claude-sonnet-5-5` default. `claude-haiku-4-5-20251001` is faster and cheaper, `claude-opus-5-5` is smarter and slower |
| `requireWakeWord` | `true` makes Claude ignore speech that doesn't include "Claude" |
| `respondToAllChat` | `false` makes Claude only answer chat that mentions "Claude" |
| `ttsEngine` | `csm` (Sesame, default), `kokoro`, or `system` (your OS's built-in voice) |
| `huggingfaceToken` | Needed for Sesame (see Voice setup) |
| `csmFinetune` | Fine-tune applied to CSM-1B. Default `Tachyeon/csm-1b-conversational-finetuned`; blank for plain CSM-1B |
| `csmVoicePrompt` | `ex04` (default, a clip of the fine-tune's own speaker), `warm` (original upbeat American female), `conversational_a` or `conversational_b` (Sesame's examples), or a path to your own clear 10 to 20 second WAV |
| `csmVoicePromptText` | Exact transcript of your own WAV (only for a custom file) |
| `csmDevice` | `auto`, `cuda` or `cpu` |
| `kokoroVoice` | `bf_emma` (British) default. American: `af_heart`, `af_bella`, `af_nicole`, `af_sarah`, `am_michael`, `am_adam`, `am_eric`, `am_liam`, `am_onyx`, `am_puck`, `am_fenrir`. British: `bf_isabella`, `bm_george`, `bm_lewis`, `bm_daniel`, `bm_fable` |
| `kokoroModel` | `kokoro-v1.0.onnx` (best, and faster on most PCs) or `kokoro-v1.0.int8.onnx` (smaller download, slower) |
| `pythonCommand` | Leave blank to auto-detect. Set it if Python lives somewhere odd, for example `py -3.12` or `/opt/homebrew/bin/python3.12` |
| `ttsSpeed` | 0.5 to 2 |
| `ttsVoice` | Only for the system-voice fallback (Windows example `Microsoft Zira Desktop`, Mac example `Samantha`) |
| `invulnerable` | Claude can't die (default true) |
| `extraPersonality` | Anything extra you want Claude to be like |
| `maxBlocksPerCall` | Cap on a single fill (default 32768) |

## How it works
- **Ears**: your mic, transcribed offline by Vosk. Only finished sentences get sent to Claude.
- **Voice**: Sesame CSM-1B, with Kokoro as the fallback and then the OS voice. Each runs locally as a small background server the mod starts and stops with the game. It speaks sentence by sentence, preparing the next line while the current one plays.
- **Eyes**: every message includes a live report (your position, facing, crosshair target, nearby mobs, recent events like blocks broken or damage taken), plus a `see_view` tool that screenshots your actual view, shaders and all.
- **Body**: a player-shaped entity with pathfinding. Tools: look_around, scan_area, get_blocks, see_view, move_to, teleport_to, follow_player, stop, wait, place_blocks, fill, break_blocks, hold_item, give_item, attack, run_command.

## Limits
- Singleplayer only (or the computer hosting a LAN world). It's a client mod that reaches into your own integrated server.
- Mac: allow Minecraft (or your launcher) microphone access in System Settings, Privacy and Security, Microphone.
- Each spoken sentence is an API call, and big builds use many calls, so watch your usage.
