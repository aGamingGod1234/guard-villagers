## [2026-03-06] — Guard Villagers Command/Reputation/Rendering/UI Cleanup
### What Was Implemented
- Removed `/guards behavior ...`, `/guards behaviour ...`, and `/guards hierarchy` command literals.
- Added op-only `/guards reputation <value>` and `/guards reputation <player> <value>` with strict `0.00..1.00` validation (max 2 decimals).
- Gated `/guards debug` and `/guards reputation` behind operator-level permission checks so non-ops do not see/execute them.
- Reworked reputation to normalized `0.00..1.00` semantics with legacy `[-200..200]` linear migration at decode time.
- Disabled gossip influence in guard trust/hostility computation and removed reputation-based hire cost scaling.
- Preserved legacy event impact proportions via `legacyDelta / 400.0`.
- Added runtime decay of `0.01` every `36s` (`720` ticks) toward `0.00`.
- Added guard-kill reset so players killed by guards immediately get reputation `0.00`.
- Added creative-op damage-time exemption for guard harm penalties and guard retaliation targeting.
- Switched guard default texture resolution to vanilla villager texture via centralized skin resolver.
- Added armor feature rendering support and biped-compatible model path so armor/held/offhand rendering works through standard feature layers.
- Added skin profile placeholder storage on `GuardEntity` for future custom skin extensibility.
- Changed new-data group defaults to zero premade groups (server + client stores).
- Made new natural/purchased guards unassigned by default and allowed unassigned group index (`-1`).
- Updated group assignment command/UI flow to support unassigning (`groupRow 0` -> internal `-1`).
- Updated groups-pane wheel behavior to hovered-pane, snapped stepping with smooth interpolation.
- Added 140ms smooth palette wheel scroll animation (wheel-only; swatch click remains immediate).
- Added ~80% opacity 1px zone outlines for clearer chunk zone boundaries.
- Updated hire lore text to exact `Shifh-click to buy max.` and retained max-affordable shift-click purchase behavior.

### Files Modified
- `src/main/java/com/guardvillagers/GuardVillagersMod.java` — command tree cleanup/gating, reputation command, unassigned group assignment handling, decay tick hookup.
- `src/main/java/com/guardvillagers/GuardReputationManager.java` — normalized reputation logic, scaling, no gossip/price influence, decay, reset/set helpers.
- `src/main/java/com/guardvillagers/data/GuardReputationState.java` — persisted format migration support and normalized storage APIs.
- `src/main/java/com/guardvillagers/entity/GuardEntity.java` — unassigned defaults, creative-op damage exemption, kill reset, skin profile placeholders, group index read/write updates.
- `src/main/java/com/guardvillagers/data/GuardTacticsState.java` — zero premade groups for new data.
- `src/main/java/com/guardvillagers/tactics/GuardTacticsInventory.java` — removed forced default group creation.
- `src/client/java/com/guardvillagers/client/ClientTacticsDataStore.java` — zero premade groups for new client world data.
- `src/client/java/com/guardvillagers/client/GuardTacticsScreen.java` — snapped+smoothing pane scroll, palette wheel animation, zero-default group handling.
- `src/client/java/com/guardvillagers/client/ChunkMapWidget.java` — zone outline rendering pass.
- `src/client/java/com/guardvillagers/client/GuardEntityModel.java` — switched to biped-compatible model base for feature rendering.
- `src/client/java/com/guardvillagers/client/GuardEntityRenderer.java` — skin resolver usage and armor/held feature setup.
- `src/client/java/com/guardvillagers/client/GuardSkinResolver.java` — centralized default skin resolution + placeholder path for future custom skins.
- `src/main/java/com/guardvillagers/shop/GuardShopInventory.java` — exact hire lore text update.

### Assumptions Made (flag these for review)
- Used `PermissionLevel.GAMEMASTERS` as the equivalent of `requires(... permission level 2)` in the current permission API.
- Treated missing/new reputation as neutral `0.50` (legacy `0` equivalent) so legacy behavior maps linearly.
- Used `"Unassigned"` as the default label when guards are not in any group.
- Implemented default villager skin on the current guard biped model path (not the vanilla villager-entity model class).

### Known Issues / Deferred
- In-game/manual validation steps from the full test matrix are not executed yet in this change set.
- Existing `logs/mcp_server.log` was already modified before implementation and was not changed intentionally as part of this task.

### Suggested Next Steps
- Run the provided test plan in-game (permissions, command validation, decay timing, creative-op exemption, rendering checks, groups/UI behavior).
- If desired, add automated command parsing tests for reputation input edge cases and assignment `0` (unassigned) coverage.
