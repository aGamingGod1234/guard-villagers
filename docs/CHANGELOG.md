# Changelog

## 2.0.0

Port to Minecraft 26.1.2 with a new command system, a rebuilt tactics screen and reworked guard AI.

### Requirements

- Minecraft `26.1.2`, Java `25`, Fabric Loader `0.19.5+`, Fabric API `0.155.3+26.1.2`.
- Sources moved from Yarn to Mojang mappings.
- Simple Voice Chat `2.6.21+` is an optional dependency for voice orders. It is not bundled.

### Added

- **Chain of command.** Each player gets a commander (most senior guard) and one captain per group with two or more loaded members. Ranks are stable, survive restarts, pass on when a rank holder dies or unloads, and can be pinned with `/guards appoint commander|captain`, cleared with `/guards appoint auto`. Ranks show as badges in the tactics sidebar (with a right-click promote menu) and on name tags.
- **Plain-English orders.** `/guards say <text>` and the `!g` chat prefix accept follow, stay, hold, come here, attack (my target, my attacker, nearest hostile), defend the village, patrol here (with a radius), form line/square/circle, retreat, as you were, status report and appointments. Orders can name all guards, the commander, a group, a guard or a role, and up to three can be chained. The built-in grammar works offline and tolerates typos and common mishearings.
- **Commander AI.** Without orders, the commander defends the owner, assigns guards to threats by score and role, reinforces losing fights, pulls wounded guards back, responds to raids and rotates patrols. `/guards commander auto off` turns this off. Callouts go to chat and the action bar.
- **Voice orders** through Simple Voice Chat: push-to-talk speech is transcribed with OpenAI, interpreted by the same grammar, and the commander answers with positional speech in its own "Guard Voices" volume category. Opt-in per player (`/guards voice on|off|status`), server config in `config/guardvillagers-voice.json`, key from the `OPENAI_API_KEY` environment variable, rate limits on every API call, and an optional LLM fallback restricted to a fixed order schema. No audio is stored. See [voice.md](voice.md).
- `/guards status` for a commander status report.
- `/guards hold`: guards stand on their block and fight only what they can reach.
- `/guards cleartargets`: drop all current targets.
- `/guards stay <group>` to match `/guards follow <group>`.
- `/guards trust add|remove|list`: players your guards treat as friends, who can also ring your guards to a bell.
- `/guards reputation get [player]` for operators.
- `/guards groups assignmany` so the tactics screen saves many moves in one command.
- **Bell rally:** ringing a bell calls nearby village guards and the owned guards that live in that village (owner, trusted players or redstone only), once per 5 s per bell.
- **Hit forgiveness:** a player's first light hit on a guard (2 hearts or less, no guard hit in the last 5 s, reputation 0.25 or more) costs no reputation and causes no retaliation.
- Guard visuals: outfit layer, biome robes, fixed arm and armor rendering, new item art.
- Test suites: JUnit unit tests, server game tests, client game tests with screenshots, and an opt-in server tick benchmark (`./gradlew runGameTest -Pperf`).

### Changed

- **Command post:** the tactics and groups screens are now one screen with a paintable chunk map, a drag-and-drop group roster, a bottom bar for zone tools and orders, keyboard shortcuts and an in-game legend (`H`). Unsaved moves are batched and confirmed on close.
- The roster now lists every owned guard. Loaded guards show gear, health, distance and current orders; unloaded guards show their saved name and group.
- Guard AI rework: one controller per guard, target scoring, formation slots, shared squad routes, path-safe grounding, bowmen engagement, swordsmen back off swelling creepers, retreat fights back when cornered.
- Reputation now decays toward `0.50` from both sides instead of toward `0.00`.
- Trade and raid reputation rewards fixed (raid bonus only during an active raid; trade reward at most every 10 s per villager).
- Hired guards spawn only in the buyer's own block column and never inside sealed pockets; failed hires are refunded.
- Owned guards are tracked in saved data, so counts and the 16-guard limit include unloaded guards.
- Shop cards rewritten for readability.
- Debug overlay ported to 26.1 gizmos, with a 48-block focus pick and no overlapping labels.
- Village scans are cheaper (loaded chunks only, palette-skipped door counting).
- Command permissions fixed: reputation, debug and voice reload are operator-only.

### Upgrading from 1.x (Minecraft 1.21.11)

Worlds migrate automatically on first start: saved data files move to the 26.1 `data/guardvillagers/` layout, village data leaves the old `DIM-1`/`DIM1` folders, legacy reputation values convert to the `0.00`-`1.00` scale, and old guard and upgrade data is read and rewritten. Back up worlds before upgrading.
