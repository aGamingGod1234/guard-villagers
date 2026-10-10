# Continue Next Session

## Status At Handoff
- The deferred "Guard Follow Recovery and High-Performance Tactical Map" implementation is now applied in source.
- `.\gradlew.bat compileJava compileClientJava` and `.\gradlew.bat build` both succeeded on `2026-03-10`.
- The remaining work is manual in-game verification and any tuning that depends on live behavior rather than source inspection.

## What Changed This Session
- Plain `/guards follow` now targets all owned guards and enables a persisted follow override.
- Follow override now suppresses patrol/crowd-control behavior competition and bypasses zone tethering until explicitly cleared by stay/home commands.
- Owner follow now uses conditional catch-up logic with a transient speed modifier instead of constant long-range follow pressure.
- Navigation stall recovery now invalidates stale squad routes, requests an immediate repath, and probes for nearby dry exits when guards stall in flowing water.
- Purchased guards now attempt to spawn on the player's exact `X/Z` column only.
- The groups screen now consumes a synced server roster payload instead of scanning client-loaded `GuardEntity` instances.
- The tactical map now uses 64x64 terrain sampling, cached chunk textures, average-color LOD, viewport-bounded chunk discovery lookup, and shared chunk-edge calculations per frame.

## Primary Files Touched
- `src/main/java/com/guardvillagers/GuardVillagersMod.java`
- `src/main/java/com/guardvillagers/entity/GuardEntity.java`
- `src/main/java/com/guardvillagers/entity/goal/FormationFollowOwnerGoal.java`
- `src/main/java/com/guardvillagers/item/GuardWhistleItem.java`
- `src/main/java/com/guardvillagers/navigation/GuardNavigation.java`
- `src/main/java/com/guardvillagers/navigation/SquadRouteCache.java`
- `src/main/java/com/guardvillagers/network/GuardRosterSyncPayload.java`
- `src/client/java/com/guardvillagers/client/ClientGuardRosterStore.java`
- `src/client/java/com/guardvillagers/client/ClientTacticsDataStore.java`
- `src/client/java/com/guardvillagers/client/GuardVillagersClient.java`
- `src/client/java/com/guardvillagers/client/GuardDragHandler.java`
- `src/client/java/com/guardvillagers/client/GuardTacticsScreen.java`
- `src/client/java/com/guardvillagers/client/ChunkTerrainCache.java`
- `src/client/java/com/guardvillagers/client/ChunkMapWidget.java`

## Manual Verification Checklist
- Hire guards from the shop on flat ground, slopes, and edge cases; confirm they spawn on the player's exact `X/Z`.
- Run plain `/guards follow` with both zoned and unzoned owned guards; confirm all owned guards follow and zoned guards are not dragged back home while forced follow is active.
- Confirm catch-up speed only appears when guards are significantly behind or stalled, and that it drops once they settle back near the owner.
- Exercise hills, forests, and flowing-water cases; confirm stalled guards recover instead of freezing.
- Open the tactics/groups UI with owned guards both near and far away; confirm the roster includes loaded server-owned guards even when they are not client-visible entities.
- Pan and zoom the tactical map; confirm full chunk tint, edge-only borders, no grey fallback background, stable chunk sizing, and materially improved zoomed-out performance.

## Known Follow-Up Risks
- Catch-up speed uses a fixed temporary movement-speed bonus and may need tuning from live feel testing.
- The new map texture/LOD threshold is source-verified only; performance should still be checked in a large explored world.
- No renderer/model changes were made in this pass, per the prior scope guardrails.

## If Another Session Is Needed
- Start with the manual checklist above before changing more code.
- If any failure is found, record the exact reproduction case and adjust only the affected subsystem rather than reopening the whole plan.
