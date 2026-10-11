# Voice commands: server setup

Players can give guard orders by talking in [Simple Voice Chat](https://modrinth.com/plugin/simple-voice-chat) (SVC). The commander can also answer out loud. This page is for server owners. Gameplay and the list of phrases are in the main [README](../README.md#commander-captains-and-orders).

Voice is optional. Without SVC or without an OpenAI key, players still give the same orders as text with `/guards say <text>` or the `!g` chat prefix, and the commander answers in chat.

## What you need

| Piece | Where |
| --- | --- |
| Simple Voice Chat `2.6.21` or newer for 26.1.2 | On the server and on every client that wants to talk. Guard Villagers does not bundle it. |
| An OpenAI API key | In an environment variable on the server, `OPENAI_API_KEY` by default. |
| Player consent | Each player turns voice on for themselves with `/guards voice on`. |

## Setup

1. Install Simple Voice Chat on the server and the clients.
2. Set the key in the server's environment before starting it:
   ```bash
   export OPENAI_API_KEY=sk-...          # Linux / macOS
   ```
   ```powershell
   $env:OPENAI_API_KEY = "sk-..."        # Windows PowerShell, same session that starts the server
   ```
   The key is read only from the environment. It is never written to the config file and never sent to clients.
3. Start the server once. It creates `config/guardvillagers-voice.json` with the defaults below.
4. Edit the file if needed, then run `/guards voice reload` (operator) or restart.
5. Each player runs `/guards voice on`, or clicks **[Enable]** on the prompt shown when they join with at least one guard. `/guards voice status` tells them what is missing.

`/guards voice status` answers with one of:

| Status | Meaning |
| --- | --- |
| Simple Voice Chat is not installed | SVC is missing or its server is not running. Text orders still work. |
| the server has no OpenAI key set | The variable named in `openai.apiKeyEnv` is empty. |
| turned off in the server config | `voiceInput.enabled` is `false`. |
| OpenAI rejected the server key | Fix the key, then `/guards voice reload`. |
| off for you | That player has not run `/guards voice on`. |
| ready | Hold the voice chat key and give an order. |

## Config file

`config/guardvillagers-voice.json`. Missing or out-of-range values fall back to the default or are clamped, with a warning in the server log. A broken file never stops the server.

```json
{
  "version": 1,
  "voiceInput": { "enabled": true, "consentDefault": false, "captureRangeToOwnGuards": 48, "maxUtteranceMs": 10000, "endpointSilenceMs": 300 },
  "commander": { "autoCommandDefault": true, "planIntervalTicks": 40, "voiceOrderExpirySeconds": 60 },
  "openai": {
    "apiKeyEnv": "OPENAI_API_KEY",
    "baseUrl": "https://api.openai.com/v1",
    "sttModel": "gpt-transcribe",
    "sttLanguage": "en",
    "ttsModel": "gpt-4o-mini-tts",
    "timeoutsMs": { "stt": 15000, "tts": 15000, "llm": 8000 }
  },
  "tts": { "enabled": true, "distance": 32, "cacheMiB": 16, "tone": "serious" },
  "llm": { "enabled": true, "model": "gpt-6-luna", "maxPerPlayerPerMinute": 6, "maxPerMinuteGlobal": 60, "maxInFlight": 4 },
  "rateLimits": { "sttMinIntervalMs": 1000, "sttSecondsPerPlayerPerHour": 300, "sttGlobalInFlight": 4, "ttsGlobalInFlight": 4, "calloutMinIntervalMs": 4000 },
  "chat": { "prefix": "!g ", "prefixEnabled": true, "showTranscripts": false }
}
```

| Field | Default | Range | What it does |
| --- | --- | --- | --- |
| `voiceInput.enabled` | `true` | | Turns voice capture on or off for the whole server. |
| `voiceInput.consentDefault` | `false` | | Legacy compatibility field. Capture and upload always require the player's explicit `/guards voice on` choice; `true` cannot opt players in. |
| `voiceInput.captureRangeToOwnGuards` | `48` | 4 to 256 | Speech is only captured when the speaker has a loaded guard of their own within this many blocks. |
| `voiceInput.maxUtteranceMs` | `10000` | 1000 to 20000 | Longest single utterance. |
| `voiceInput.endpointSilenceMs` | `300` | 160 to 2000 | Silence that ends a short utterance. After 24,000 samples (0.5 seconds at 48 kHz), the endpoint uses 160 ms instead. |
| `commander.autoCommandDefault` | `true` | | Whether commanders act on their own by default. Players change it with `/guards commander auto on\|off`. |
| `commander.planIntervalTicks` | `40` | 10 to 400 | How often the commander re-plans (20 ticks = 1 s). |
| `commander.voiceOrderExpirySeconds` | `60` | 5 to 3600 | How long timed orders last (come here, attack, form, retreat). |
| `openai.apiKeyEnv` | `OPENAI_API_KEY` | | Name of the environment variable that holds the key. |
| `openai.baseUrl` | `https://api.openai.com/v1` | | Must be `https`; plain `http` is accepted only for `localhost`. |
| `openai.sttModel` | `gpt-transcribe` | | Speech-to-text model. |
| `openai.sttLanguage` | `en` | two letters or empty | Language hint for transcription. Empty lets the model detect it. The order grammar is English. |
| `openai.ttsModel` | `gpt-4o-mini-tts` | | Text-to-speech model for the commander's voice. |
| `openai.timeoutsMs.stt` / `tts` / `llm` | `15000` / `15000` / `8000` | 1000 to 60000 | Request timeouts. |
| `tts.enabled` | `true` | | Spoken callouts. Off means chat only. |
| `tts.distance` | `32` | 4 to 128 | How far the commander's voice carries. |
| `tts.cacheMiB` | `16` | 0 to 256 | In-memory cache of spoken lines, so repeated lines cost nothing. |
| `tts.tone` | `serious` | `neutral`, `warm`, `excited`, `serious`, `dramatic`, `whisper`, `robotic`, `angry` | Delivery style. |
| `llm.enabled` | `true` | | Optional fallback that interprets orders the built-in grammar does not understand. |
| `llm.model` | `gpt-6-luna` | | Model for that fallback. |
| `llm.maxPerPlayerPerMinute` | `6` | 0 to 60 | Fallback calls per player per minute. |
| `llm.maxPerMinuteGlobal` | `60` | 0 to 600 | Fallback calls per minute across the server. |
| `llm.maxInFlight` | `4` | 1 to 32 | Concurrent fallback requests across the server. |
| `rateLimits.sttMinIntervalMs` | `1000` | 0 to 60000 | Minimum gap between transcriptions for one player. |
| `rateLimits.sttSecondsPerPlayerPerHour` | `300` | 0 to 3600 | Seconds of audio each player may send per hour. |
| `rateLimits.sttGlobalInFlight` / `ttsGlobalInFlight` | `4` / `4` | 1 to 32 | Concurrent requests across the server. |
| `rateLimits.calloutMinIntervalMs` | `4000` | 0 to 60000 | Minimum gap between commander callouts for one player. |
| `chat.prefix` | `!g ` | 1 to 16 characters | Chat messages starting with this are orders. They are not broadcast. |
| `chat.prefixEnabled` | `true` | | Turns the chat prefix on or off. |
| `chat.showTranscripts` | `false` | | Shows the player a temporary `Heard: ...` overlay when enabled; it is not added to chat. |

## Privacy

- **Consent first.** Audio from a player is decoded only after they explicitly opt in, and only while they own a loaded guard within `captureRangeToOwnGuards` blocks. Only public proximity speech is eligible: group speech (including open groups) and whispers are excluded. Consent, connection, proximity and channel are checked again before upload; losing eligibility cancels capture.
- **No audio is stored.** Audio exists in memory until the transcription request finishes. It is not written to disk or logs. `/guards voice off` drops any utterance in flight.
- **Transcript feedback.** Disabled by default. With `showTranscripts` enabled, the speaker sees a temporary overlay instead of a chat message, so Minecraft's normal chat logger does not record it. The mod does not log raw transcripts, including at DEBUG level.
- **What is sent to OpenAI:**
  - Transcription: the utterance audio (48 kHz mono WAV) plus the speaker's own group and guard names as a spelling hint.
  - Optional LLM fallback: the transcript (at most 512 characters) and the speaker's group and guard names. No coordinates, no other players, no world data. The model can only fill in a fixed order schema; its output is validated and its text is never run as a command.
  - Speech: the text of the commander's lines.

Tell your players that their voice goes to OpenAI when they turn it on. The in-game prompt and `/guards voice on` say so.

## Cost

Every request is billed to the account that owns the key. The defaults limit spend per player: at most 300 seconds of audio per hour, one transcription per second, 6 LLM fallback calls per minute, and one callout every 4 seconds. Common spoken lines are cached, so repeated acknowledgements make no new speech request. To keep costs down further:

- Lower `rateLimits.sttSecondsPerPlayerPerHour`.
- Set `llm.enabled` to `false`; the built-in grammar handles every order in the README phrase table without any API call.
- Set `tts.enabled` to `false`; callouts then appear in chat only.
- Set a monthly budget on the key in the OpenAI dashboard.

## Troubleshooting

| Problem | Fix |
| --- | --- |
| `/guards voice status` says the key is missing | The variable must be set in the environment of the server process itself, then restart. |
| OpenAI rejected the key | Replace the key, then `/guards voice reload`. Voice stays off until then. |
| "Commander couldn't hear you, try again." | OpenAI timed out or rate-limited the request. The mod backs off and retries on the next utterance. |
| Nothing happens when talking | Check consent, that a guard of yours is loaded within 48 blocks, and that SVC push-to-talk is actually sending. Chatting to other players is ignored unless it parses as an order. |
| Config changes have no effect | Run `/guards voice reload` and check the server log for `guardvillagers-voice.json` warnings. |
