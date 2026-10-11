# F01 implementation handoff

Worktree: `wt-fix01`; branch: `fix/review-01`; base: `4d6fc22`.

## Finding disposition

- **01-build / A01 build row:** NOT A BUG. Neither review reported a build-configuration defect. No build/configuration changes needed; commit: none.
- **02-migration/1 (A01 confirmed): FIXED**, `6baf033`. Existing legacy files that cannot be inspected/moved now abort startup before defaults can be created. Dimension enumeration failures also abort. Tests cover a blocked destination, recovery, and an invalid dimension root.
- **02-migration/2 (A01 confirmed): FIXED**, `6baf033`. Completion is recorded only after all discovered regions and allocated chunks were examined successfully. Successful imports survive failures and retries. Original allocation headers detect invalid offsets that vanilla otherwise clears. Tests cover truncated regions, invalid allocated slots, missing external payloads, repair/retry, and enumeration failure.
- **02-migration/3 (A01 confirmed), 04-ownership/2 (A02 confirmed): FIXED**, `6baf033`. Region coordinates produce absolute chunk positions for external payload lookup. Tests write real oversized vanilla regions at positive and negative coordinates.
- **02-migration/4 (A01 confirmed): FIXED**, `6baf033`. Both scans accept arbitrarily nested dimension paths; regression fixtures exceed both previous depth limits.
- **03-codecs/1 (A02 confirmed): DEFERRED to commander fixer**, commit: none. Explicit voice opt-out eviction belongs to the excluded `GuardCommandState`; no edits made there.
- **03-codecs/2 (A02 confirmed): DEFERRED to commander fixer**, commit: none. Persisted long-name order encoding belongs to the excluded `GuardCommandState`; no edits made there.
- **03-codecs/3 (A02 confirmed): DEFERRED to F03**, commit: none. Lead explicitly reassigned `GuardDiplomacyState` and its codec tests. Neither was edited.
- **04-ownership/1 (A02 confirmed): FIXED**, `4c42e91`. Every authoritative ownership row survives recording, owner changes, and codec reloads, even above the former 64-row cap. Regression retains the unloaded survivor after the other 64 guards are removed. Existing roster serialization already limits presentation to 1,024 rows independently.
- **04-ownership/3 (A02 confirmed): FIXED**, `4c42e91` plus the backfill health filter in `6baf033`. Death immediately releases ownership; loading/refreshing a corpse cannot restore its row; import ignores saved zero-health guards. A real server game test kills a guard, checks before corpse removal, reloads its saved NBT with a simulated old ownership row, and checks tracking again.
- **A01/New-1 and A02/New-1: FIXED**, `6baf033`. Custom names decode through `ComponentSerialization.CODEC` with server registry serialization context. Styled/composite names and invalid-name fallback have tests.
- **A03/New-1: FIXED**, `29e4c0f`. Creating another tactics owner never evicts saved settings. A codec regression preserves 2,050 owners and their group names/colors.
- **A03/New-2 and A14/New-1: FIXED in owned state mutation paths; DEFERRED remaining row targeting to tactics/client fixers**, `29e4c0f`. Add/ensure/cycle generate unique names; rename rejects collisions after trimming/truncation and case-insensitive comparison and reserves `Unassigned`. `setGroupName(int, String)` now returns boolean; the command checks it and sends a translated rejection. Existing legacy duplicate labels remain unchanged to avoid silently renaming saved choices. Stable row-based UI commands and ambiguous legacy-name resolution still need integration by the command/client owners.

## Shared files outside production ownership

- `src/main/java/com/guardvillagers/entity/GuardEntity.java`: three-line `die` hook, `4c42e91`.
- `src/main/java/com/guardvillagers/command/GuardCommands.java`: check new boolean rename result and report rejection, `29e4c0f`.
- `src/main/resources/assets/guardvillagers/lang/en_us.json`: one rejection translation key, `29e4c0f`.
- `src/gametest/java/com/guardvillagers/gametest/GuardPersistenceGameTest.java`: one new death/reload test, `4c42e91`.

Area regression files: `GuardSavedDataMigrationTest`, `GuardOwnershipBackfillTest`, `GuardOwnershipStateTest`, and new `GuardTacticsStateTest` under `src/test/java/com/guardvillagers/data/`.

## Validation

Used Java 25 from `C:/Program Files/Java/jdk-25`. Verified Minecraft APIs against the supplied 26.1.2 sources (`RegionFile`, `ComponentSerialization`, `HolderLookup.Provider`, `MinecraftServer`, `LivingEntity`, and `DamageSources`) and compilation. No dependency/API version changes.

- `./gradlew --no-daemon --console=plain compileJava compileClientJava test`: BUILD SUCCESSFUL; 301 unit tests, zero failures/errors.
- `./gradlew --no-daemon --console=plain build`: exit 0, BUILD SUCCESSFUL in 2m 23s.
- Server output: `[09:31:03] [Server thread/INFO] (Minecraft) All 80 required tests passed :)`
- The 80 tests are the original 79 plus the new death/reload regression. Client compilation passed; interactive client tests were not run.
- `git diff --check`: passed. No push or GitHub content changes.

Local evidence: `fix01-test.log`, `fix01-build.log`, and `build/test-results/test/`. Logs/build outputs are ignored by git.
