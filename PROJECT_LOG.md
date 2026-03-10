## [2026-03-10] - Rebuild And Deploy Guard Villagers Jar
### What Was Implemented
- Rebuilt the mod from clean HEAD using .\gradlew.bat build on Java 21.
- Produced a fresh build/libs/guard-villagers-1.0.0.jar artifact.
- Replaced the repo-local development jar at .minecraft/mods/guard-villagers-1.0.0.jar with the rebuilt artifact.
- Backed up the installed-game jar to %APPDATA%\.minecraft\mods\guard-villagers-1.0.0.jar.bak-20260310-065350.
- Replaced the installed-game jar at %APPDATA%\.minecraft\mods\guard-villagers-1.0.0.jar with the rebuilt artifact.
- Verified the built artifact and both deployed copies match after deployment.

### Files Modified
- .minecraft/mods/guard-villagers-1.0.0.jar - updated tracked dev jar to the freshly built artifact.
- PROJECT_LOG.md - recorded the rebuild and deployment task.

### Assumptions Made (flag these for review)
- Deployment should update both the repo-local .minecraft/mods jar and the installed %APPDATA%\.minecraft\mods jar.
- build/libs/guard-villagers-1.0.0.jar remains the correct release artifact for deployment.

### Known Issues / Deferred
- No gameplay or in-game smoke validation was run as part of this task.
- The Gradle build emitted an existing deprecation note for src/client/java/com/guardvillagers/client/GuardVillagersClient.java but completed successfully.

### Suggested Next Steps
- Launch the game and verify the newly deployed guard AI/pathfinding/visual changes behave as expected.
- If desired, investigate the deprecated API usage noted during the build to keep future upgrades simpler.
## [2026-03-06] â€” Guard Villagers Command/Reputation/Rendering/UI Cleanup
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
- `src/main/java/com/guardvillagers/GuardVillagersMod.java` â€” command tree cleanup/gating, reputation command, unassigned group assignment handling, decay tick hookup.
- `src/main/java/com/guardvillagers/GuardReputationManager.java` â€” normalized reputation logic, scaling, no gossip/price influence, decay, reset/set helpers.
- `src/main/java/com/guardvillagers/data/GuardReputationState.java` â€” persisted format migration support and normalized storage APIs.
- `src/main/java/com/guardvillagers/entity/GuardEntity.java` â€” unassigned defaults, creative-op damage exemption, kill reset, skin profile placeholders, group index read/write updates.
- `src/main/java/com/guardvillagers/data/GuardTacticsState.java` â€” zero premade groups for new data.
- `src/main/java/com/guardvillagers/tactics/GuardTacticsInventory.java` â€” removed forced default group creation.
- `src/client/java/com/guardvillagers/client/ClientTacticsDataStore.java` â€” zero premade groups for new client world data.
- `src/client/java/com/guardvillagers/client/GuardTacticsScreen.java` â€” snapped+smoothing pane scroll, palette wheel animation, zero-default group handling.
- `src/client/java/com/guardvillagers/client/ChunkMapWidget.java` â€” zone outline rendering pass.
- `src/client/java/com/guardvillagers/client/GuardEntityModel.java` â€” switched to biped-compatible model base for feature rendering.
- `src/client/java/com/guardvillagers/client/GuardEntityRenderer.java` â€” skin resolver usage and armor/held feature setup.
- `src/client/java/com/guardvillagers/client/GuardSkinResolver.java` â€” centralized default skin resolution + placeholder path for future custom skins.
- `src/main/java/com/guardvillagers/shop/GuardShopInventory.java` â€” exact hire lore text update.

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

## [2026-03-07] - Codex 5.3: Shop/Economy Fixes + Debug System Overhaul

### What Was Done

#### Section A - Shop & Economy
- Creative mode bypass: all shop purchases show "Cost: Free" and succeed without emerald blocks.
- Creative mode bulk purchase now caps at 64 guards when shift-clicking.
- Armor upgrade pricing updated: starts at 4, doubles per level, caps at 64 emerald blocks.
- Weapon upgrade pricing updated: starts at 4, quadruples per level, caps at 64 emerald blocks.
- Hiring price reduced to base 4 + 2 per hire level.
- Reintroduced reputation-based hire cost scaling (0.75x to 1.5x modifier) in `GuardVillagersMod.getAdjustedGuardCost`.
- Fixed lore typo: "Shifh-click" -> "Shift-click".
- Weapon display now shows both sword and bow progression in upgrade cards.
- Shop info book simplified to armor odds, sword level, bow level, healing status, and shield status only.

#### Section B - Debug System
- Added `GuardDebugState` (`PersistentState`) for per-player debug toggle and range persistence.
- Added `GuardDebugManager` utility for state access and effective-range calculation.
- Reworked `/guards debug [range]` command:
- OP-gated via existing operator permission check.
- `/guards debug` toggles on/off.
- `/guards debug <range>` enables (if needed) and updates range.
- Range is capped to half of player view distance in blocks.
- Added S2C payloads:
- `GuardDebugSyncPayload` for local debug enabled/range state.
- `GuardDebugDataPayload` for batched per-guard path nodes/current index/target ID.
- Added server-side periodic debug sync every 5 ticks with per-player scoping and hash-based path change detection.
- Added client-side state holders:
- `ClientDebugState` for local toggle/range.
- `ClientGuardDebugData` for per-guard path/target cache.
- Replaced debug renderer stub with a full `GuardDebugRenderer` wired to `WorldRenderEvents.AFTER_ENTITIES`:
- Head labels: HP, Lvl, XP, Role, Behavior, Owner, Group, Zone.
- Green 32-block detection ring.
- Blue path block highlights + red current-position block.
- Yellow line from guard eye to closest point on target AABB.
- Range-based visibility filtering and local-player-only rendering.
- Updated `GuardEntity` with a path/target debug snapshot accessor for packet sync.
- Updated `GuardVillagersClient` to register packet handlers and clear debug caches on disconnect/stop.

### Decisions Made
- Debug persistence is server-side `PersistentState`, which also covers integrated singleplayer worlds.
- Debug data sync is throttled to every 5 ticks and only sends changed guard snapshots per player.
- Max synced path length is capped at 64 nodes.
- Client guard-in-range list is cached and refreshed every 10 ticks.
- Reputation pricing uses linear scaling from 1.5x (rep 0.0) to 0.75x (rep 1.0), clamped.

### Current State
- Section A and Section B code changes are implemented and compiling.
- Full project build passes after Section A and again after Section B.
- Rendering uses vanilla/Fabric rendering APIs only.

### Next Steps / Open Items
- Execute in-game manual checklist validation for all Section A and Section B scenarios.
- Run runtime/performance checks with high guard counts to tune debug rendering cost if needed.

## [2026-03-07] — Codex 5.3 Hotfix: Crash Fix + Model/Armor Geometry

### What Was Done
- **Crash fix:** Replaced `RenderLayers.lines()` GL line rendering with camera-facing billboard quads
  - Deleted broken `lineVertex()` helper that was missing the `lineWidth` vertex element
  - New `renderLineSegment()` method renders lines as thin quads using `RenderLayers.debugFilledBox()`
  - Detection circle and target line both use the new quad-based approach
  - Guarantees consistent line width across all GPU drivers (GL_LINES width > 1.0 is unreliable)
- **Arm fix:** Changed guard arm height from 8px to 12px — hands are now fully visible
- **Armor fix:** Created custom `GUARD_ARMOR_LAYER` with villager-matching proportions
  - Head: 10px tall (matches villager), uses standard armor UV for correct texture mapping
  - Body: 6px deep (matches villager)
  - Arms: 12px tall (matches fixed guard arms)
  - Dilation: 1.0F standard armor offset
  - Texture size: 64×32 (standard armor texture format)
- **Renderer fix:** Switched `GuardEntityRenderer` from `EntityModelLayers.PLAYER_EQUIPMENT` to `GUARD_ARMOR_LAYER`
- **Registration:** Added `GUARD_ARMOR_LAYER` to `EntityModelLayerRegistry` in client initializer

### Decisions Made
- Billboard quads chosen over fixing `lineWidth` vertex element because GL line width > 1.0px is not guaranteed by OpenGL spec and is ignored by many NVIDIA/AMD drivers
- Armor model uses standard player/armor UV layout (64×32) so vanilla armor textures render correctly, with villager cuboid dimensions for geometry matching
- Minor armor texture stretching on the 10px head (vs standard 8px) is accepted — pixel-perfect would require custom armor textures (deferred)

### Files Modified
- `src/client/java/com/guardvillagers/client/GuardDebugRenderer.java` — billboard quad line rendering
- `src/client/java/com/guardvillagers/client/GuardEntityModel.java` — arm height fix, armor layer
- `src/client/java/com/guardvillagers/client/GuardEntityRenderer.java` — custom armor layer usage
- `src/client/java/com/guardvillagers/client/GuardVillagersClient.java` — armor layer registration

### Next Steps
- In-game verification of all checklist items
- Consider custom armor textures for pixel-perfect villager-proportioned armor (future)
- Verify held item (sword/bow/shield) positioning hasn't shifted with arm length change

## [2026-03-10] — Vanilla Plains Villager Guard Renderer
### What Was Implemented
- Replaced the guard’s partial custom villager geometry with a full vanilla-aligned side-arm villager model based on the zombie villager proportions, including nose, hat rim, robe jacket, and vanilla villager UV layout.
- Switched the guard renderer to `BipedEntityRenderer` so held-item, head-item, and general humanoid render state handling stays on the standard vanilla path.
- Added vanilla zombie-villager armor model layers for the guard so helmets, chestplates, leggings, and boots render on villager-shaped proportions instead of the previous mismatched layer setup.
- Added the vanilla villager clothing overlay feature and fixed the guard’s visual data to a plains villager with profession `NONE`, so the guard now renders as a fixed plains villager without editing the vanilla texture assets.
- Kept explicit bow-draw and shield-block arm poses while preserving vanilla swing animation feedback for melee weapons.
- Verified the client compile and full Gradle build both complete successfully.

### Files Modified
- `src/client/java/com/guardvillagers/client/GuardEntityModel.java` — replaced the guard model geometry with a vanilla-aligned side-arm villager layout and added model layer IDs used by the renderer/features.
- `src/client/java/com/guardvillagers/client/GuardEntityRenderer.java` — moved the renderer to the vanilla biped pipeline, added armor + plains villager clothing features, and fixed render state data for the villager overlay.
- `src/client/java/com/guardvillagers/client/GuardVillagersClient.java` — registered the additional model layers required by the updated renderer setup.
- `PROJECT_LOG.md` — recorded the implementation and verification status.

### Assumptions Made (flag these for review)
- A fixed plains villager appearance means base `villager.png` plus the vanilla `villager/type/plains.png` overlay, with profession set to `NONE` and level `1`.
- Guards remain adult-only, so the extra baby model registrations are present for renderer/feature compatibility rather than active gameplay use.
- Vanilla zombie-villager armor geometry is the correct fit for a side-arm villager body and is preferable to the standard player armor geometry for visual alignment.

### Known Issues / Deferred
- I did not complete an in-game visual pass from the terminal, so “no visual bugs” is implemented to the strongest extent available from source parity plus a successful build, but still needs a live render check in Minecraft.
- The legacy custom texture file `assets/guardvillagers/textures/entity/guard_villager.png` remains in the project but is not part of the active guard render path.

### Suggested Next Steps
- Spawn a guard with sword, bow, shield, and each armor slot filled, then verify idle, swing, bow draw, and shield block poses in-game.
- If the plains overlay or armor still shows any clipping in motion, capture one screenshot per case and adjust only the affected cuboid dilation or feature layer, not the vanilla texture data.

## [2026-03-10] — Build And Install Mod Jar
### What Was Implemented
- Rebuilt the mod jar from the current source with Gradle.
- Replaced the installed mod at `.minecraft/mods/guard-villagers-1.0.0.jar` with the freshly built artifact from `build/libs/guard-villagers-1.0.0.jar`.
- Verified the installed jar matches the built jar by SHA-256 hash.

### Files Modified
- `.minecraft/mods/guard-villagers-1.0.0.jar` — replaced with the current build output.
- `PROJECT_LOG.md` — recorded the build and install step.

### Assumptions Made (flag these for review)
- The correct runtime artifact for the local Minecraft instance is `build/libs/guard-villagers-1.0.0.jar` rather than the sources jar.

### Known Issues / Deferred
- No in-game launch was performed as part of this step.

### Suggested Next Steps
- Launch Minecraft and verify the installed mod loads the updated renderer changes.

## [2026-03-10] — Corrected Active Minecraft Mods Path
### What Was Implemented
- Checked the actual Minecraft launch logs and confirmed the game session was using `%APPDATA%\\.minecraft`, not the workspace-local `.minecraft` directory.
- Replaced `%APPDATA%\\.minecraft\\mods\\guard-villagers-1.0.0.jar` with the current built artifact.
- Verified the active installed jar now matches the build output by SHA-256 hash.

### Files Modified
- `%APPDATA%\\.minecraft\\mods\\guard-villagers-1.0.0.jar` — replaced the older active runtime jar with the current build.
- `PROJECT_LOG.md` — recorded the active instance path correction.

### Assumptions Made (flag these for review)
- The screenshots and latest launch were from the default `%APPDATA%\\.minecraft` instance shown in `latest.log`.

### Known Issues / Deferred
- Minecraft still needs a full restart after the jar replacement to load the updated code.

### Suggested Next Steps
- Relaunch Minecraft and check the guard again in the same world/instance.

## [2026-03-10] — Villager Arm UV And Clothing Layer Fix
### What Was Implemented
- Split each guard arm into an 8-pixel upper segment plus a 4-pixel lower segment so the model only samples opaque pixels from the unchanged vanilla villager arm atlas.
- Removed the long robe cuboid from the base guard model and moved the plains villager lower robe to the dedicated clothing layer model instead.
- Changed the clothing layer model to use a separate `jacket` child with vanilla villager-style `0.5F` dilation so the plains robe renders as an outer garment rather than a near-flush body extension.
- Re-registered the clothing model layers to use the new clothing-specific textured model data.
- Rebuilt the mod and replaced both the workspace-local and active `%APPDATA%` jars with the updated artifact.
- Verified `build/libs/guard-villagers-1.0.0.jar` and `%APPDATA%\\.minecraft\\mods\\guard-villagers-1.0.0.jar` match by SHA-256 hash: `5A24100EC897B44A1EC4E4B90921C95AA469C4E752CF7B729A54A4705B9317B2`.

### Files Modified
- `src/client/java/com/guardvillagers/client/GuardEntityModel.java` — split arm geometry to avoid transparent villager UV rows and separated the clothing robe from the base model.
- `src/client/java/com/guardvillagers/client/GuardVillagersClient.java` — pointed the clothing model layer registrations at the clothing-specific textured model data.
- `%APPDATA%\\.minecraft\\mods\\guard-villagers-1.0.0.jar` — replaced with the rebuilt artifact containing the arm/clothing fix.
- `.minecraft\\mods\\guard-villagers-1.0.0.jar` — replaced with the same rebuilt artifact for workspace-local testing parity.
- `PROJECT_LOG.md` — recorded the root cause, implementation, and deployment details.

### Assumptions Made (flag these for review)
- The missing hand sides were caused by the vanilla villager arm UV block only containing valid pixels for an 8-pixel-tall arm section plus top/bottom faces, which required segmented arm geometry rather than texture edits.
- The robe clipping was caused by using zombie-villager-style near-flush robe geometry for the villager clothing overlay, and the vanilla villager clothing path should instead use the separate jacket child with `0.5F` dilation.

### Known Issues / Deferred
- I still did not complete a live in-game visual verification after this specific patch, so the source-level fix and successful build/install are done, but runtime confirmation is still required.
- Baby model registrations still reuse the adult geometry path; this was left unchanged because the reported issue is on adult guards and guards are expected to remain adult in normal play.

### Suggested Next Steps
- Relaunch Minecraft completely and verify two cases first: an unarmored guard standing idle, and a guard wearing armor while holding a weapon.
- If any remaining artifact appears, capture one close screenshot from the front and one from the side so the exact failing cuboid face can be isolated without changing the vanilla textures.

## [2026-03-10] — Resume Notes For Follow Recovery And Tactical Map Overhaul
### What Was Implemented
- Audited the existing follow, shop spawn, groups UI, and tactical map code paths against the requested implementation plan.
- Confirmed the current worktree was clean before documentation, so no partial implementation from the aborted session needs to be unwound.
- Created `contine_next_session.md` as a detailed handoff file with confirmed defect locations, implementation order, target files, and verification steps for the next session.

### Files Modified
- `contine_next_session.md` — added a detailed resume brief for the follow recovery, server-synced groups roster, exact spawn-column, and tactical map performance work.
- `PROJECT_LOG.md` — recorded the current status and the remaining implementation work.

### Assumptions Made (flag these for review)
- The user’s instruction to keep the guard visual “perfect” means renderer, model, and texture behavior are out of scope for the next implementation pass.
- The tactical zone changes remain visual-only and chunk-based; no server-side gameplay wiring is part of the deferred work.
- “Server-owned roster” means guards currently loaded and known to the server, without force-loading chunks.

### Known Issues / Deferred
- The requested implementation has not been started yet; this session stopped at analysis and handoff documentation only.
- Plain `/guards follow` still only targets unzoned guards.
- Shop purchases still spawn using the current lateral offset ring rather than the player’s exact `X/Z`.
- The Groups screen still depends on client-loaded `GuardEntity` instances and can therefore miss owned guards.
- The tactical map still uses the current expensive per-frame terrain rasterization path and has not received the planned 64x64 cached-texture rewrite.

### Suggested Next Steps
- Start implementation in this order: `GuardVillagersMod`, `GuardEntity`, `FormationFollowOwnerGoal`, `GuardNavigation`/`SquadRouteCache`, new roster payload, client roster consumption, then `ChunkTerrainCache` and `ChunkMapWidget`.
- Build after each subsystem milestone with `.\gradlew.bat compileJava compileClientJava`, then finish with `.\gradlew.bat build`.
- Use `contine_next_session.md` as the source of truth for the exact remaining work, acceptance criteria, and file-level hotspots.

## [2026-03-10] — Follow Recovery, Synced Guard Roster, And Tactical Map Rewrite
### What Was Implemented
- Reworked plain `/guards follow` to target all owned guards, while keeping group-specific follow limited to the named group.
- Added a persisted follow-override state on guards, cleared it on stay/home assignment flows, suppressed zone tethering while active, and blocked higher-priority behavior goals from stealing follow control.
- Replaced the owner follow goal with conditional catch-up behavior that only activates when a guard is materially behind or stalled relative to the owner, with a transient catch-up speed modifier and 2-tick repaths.
- Changed navigation stall recovery from stop-only to stop + targeted squad-route invalidation + immediate repath, and added nearby dry-exit probing when guards stall in or against flowing water.
- Removed lateral hire offsets so purchased guards now spawn on the player's exact `X/Z` column and either find a valid `Y` on that column or fail cleanly.
- Added a server-authenticated roster sync payload with authoritative group names plus per-guard summaries, and sent it when the tactics/groups screen opens and after group add/rename/assignment changes.
- Added a transient client roster store, updated client group-name syncing, and migrated the groups screen/drag flow from client entity scans to synced guard summaries keyed by guard UUID.
- Rebuilt the tactical map terrain pipeline to use 64x64 chunk sampling, cached chunk textures for zoomed-in rendering, average-color LOD for zoomed-out rendering, a viewport-bounded discovered-chunk index, and stable per-frame chunk edge coordinates.
- Verified `.\gradlew.bat compileJava compileClientJava` and `.\gradlew.bat build` both complete successfully after the implementation.

### Files Modified
- `src/main/java/com/guardvillagers/GuardVillagersMod.java` — follow command behavior, exact-column purchase spawn, roster payload registration/sending.
- `src/main/java/com/guardvillagers/entity/GuardEntity.java` — persisted follow override, transient catch-up speed modifier, behavior/tether gating changes.
- `src/main/java/com/guardvillagers/entity/goal/FormationFollowOwnerGoal.java` — conditional catch-up activation/exit logic and 2-tick repaths.
- `src/main/java/com/guardvillagers/item/GuardWhistleItem.java` — explicit whistle home assignment now clears forced follow.
- `src/main/java/com/guardvillagers/navigation/GuardNavigation.java` — stall recovery, dry-exit probing, immediate repath behavior.
- `src/main/java/com/guardvillagers/navigation/SquadRouteCache.java` — targeted squad-route invalidation helper.
- `src/main/java/com/guardvillagers/network/GuardRosterSyncPayload.java` — new server-to-client roster/group sync payload.
- `src/client/java/com/guardvillagers/client/ClientGuardRosterStore.java` — transient synced roster store for the active world context.
- `src/client/java/com/guardvillagers/client/ClientTacticsDataStore.java` — authoritative group-name replacement and discovered-chunk viewport index.
- `src/client/java/com/guardvillagers/client/GuardVillagersClient.java` — roster payload receiver registration and transient-store cleanup.
- `src/client/java/com/guardvillagers/client/GuardDragHandler.java` — drag preview now uses synced guard summaries instead of live entities.
- `src/client/java/com/guardvillagers/client/GuardTacticsScreen.java` — groups UI migrated to synced summaries and UUID-based assignment saves.
- `src/client/java/com/guardvillagers/client/ChunkTerrainCache.java` — 64x64 terrain sampling and cached texture generation path.
- `src/client/java/com/guardvillagers/client/ChunkMapWidget.java` — viewport-bounded rendering, average-color LOD, cached texture draw path, stable chunk edges.
- `contine_next_session.md` — refreshed handoff status after the implementation pass.
- `PROJECT_LOG.md` — recorded the completed work, assumptions, and verification state.

### Assumptions Made (flag these for review)
- The catch-up speed modifier uses a `+35%` temporary movement-speed bonus because the requested behavior specified a transient speed mode but did not define the exact multiplier.
- The zoomed-in terrain-texture threshold uses a chunk footprint of roughly `18px` before switching from average-color LOD to the cached 64x64 texture path.
- “Assignment save” payload refresh is satisfied by sending the roster payload after each `/guards groups assign ...` command invoked by the groups screen save flow.

### Known Issues / Deferred
- I did not run an in-game manual pass from the terminal, so the gameplay and renderer acceptance checklist in `contine_next_session.md` still needs live verification.
- `GuardVillagersClient.java` still reports an existing deprecated API usage note during compilation; the build succeeds and this task did not change that deprecation status intentionally.

### Suggested Next Steps
- Launch Minecraft and run the manual checklist for exact-column hiring, forced follow behavior, water/hill recovery, synced groups roster coverage, and tactical-map performance/visual stability.
- If the catch-up speed or texture-threshold tuning feels off in-game, adjust those constants only after measuring the affected scenario rather than broad refactoring.

## [2026-03-10] — README Usage Guide Rewrite
### What Was Implemented
- Rewrote `README.md` into a comprehensive user-facing guide covering installation, first use, guard ownership, controls, tactics, shop progression, reputation, commands, and admin/debug tooling.
- Removed stale documentation for older command surfaces and replaced it with the currently implemented `/guards` command set and current tactics/groups workflows.
- Organized the guide with clearer sections, tables, quick-start steps, and behavior notes so players can use the mod without reading source files.

### Files Modified
- `README.md` — replaced the old overview with a full usage guide based on current mod behavior.
- `PROJECT_LOG.md` — recorded the documentation rewrite and its review assumptions.

### Assumptions Made (flag these for review)
- The README should target end users and server operators rather than mod developers, so it prioritizes gameplay usage and command behavior over internal architecture.
- Documentation should only describe behavior confirmed in the current source, without promising unverified in-game outcomes beyond what the code currently implements.

### Known Issues / Deferred
- I did not run an in-game verification pass specifically for the README wording; the guide is source-based rather than playtested line by line.
- The README currently has no screenshots or GIFs; the formatting is comprehensive text-first documentation.

### Suggested Next Steps
- Review the guide in-game once and adjust any wording that feels inaccurate from a player perspective.
- Add a few screenshots later if you want the README to double as a presentation page for releases.

## [2026-03-10] — Build And Replace Installed Mod Jars
### What Was Implemented
- Ran `.\gradlew.bat build` against the current workspace and confirmed the runtime artifact is up to date.
- Replaced the repo-local `.minecraft\mods\guard-villagers-1.0.0.jar` with the freshly built `build\libs\guard-villagers-1.0.0.jar`.
- Replaced the active `%APPDATA%\.minecraft\mods\guard-villagers-1.0.0.jar` with the same built artifact.
- Verified all three jar files match by SHA-256 hash: `4B8A332B1F8AC319917BDDBFB5134B9CE98192FCA335D0244B68B48C2E643D74`.

### Files Modified
- `.minecraft\mods\guard-villagers-1.0.0.jar` — replaced with the current build output for repo-local testing.
- `%APPDATA%\.minecraft\mods\guard-villagers-1.0.0.jar` — replaced with the current build output for the default Minecraft instance.
- `PROJECT_LOG.md` — recorded the build, deployment, and hash verification.

### Assumptions Made (flag these for review)
- Replacing both the repo-local and `%APPDATA%` mod jars is preferable here because both installed locations exist and keeping them synchronized avoids loading different builds between local and default instances.

### Known Issues / Deferred
- No Minecraft launch or in-game smoke test was performed after copying the jar files.

### Suggested Next Steps
- Restart Minecraft if it is already running so it loads the replaced jar.
- Verify the current world loads the updated mod and spot-check the latest follow/tactics changes in-game.

