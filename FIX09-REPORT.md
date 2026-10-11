# F09 implementation report

Worktree: `wt-fix09`; branch: `fix/review-09`; base: `4d6fc22`. No push or GitHub action.

All assigned confirmed findings and assigned new findings are fixed. No assigned finding was rejected or deferred.

## Finding outcomes

| Finding | Status | Commit(s) | Result |
|---|---|---|---|
| 23#1 | FIXED | `77d21b0 + 711fec5` | Undo uses persisted recipient UUIDs, surviving group/name/rank changes. |
| 23#2 | FIXED | `77d21b0 + 711fec5` | Removed orders retain unloaded recipients in saved cleanup; ENTITY_LOAD reconciles even offline owners. |
| 23#3 | FIXED | `77d21b0 + 711fec5` | Execution, effective control and attack pressing use original recipients, excluding other dimensions. |
| 23#4 | FIXED | `77d21b0 + 711fec5` | Typed orders remove actual intersecting recipients, retaining unaffected members and attack IDs. |
| 23#5 | FIXED | `77d21b0` | Typed cleanup runs before new flags and cancels old rally/formation effects. |
| 23#6 | FIXED | `77d21b0` | Withdraws command-specific attack alerts; command-only passive fights no longer echo as allied threats. |
| 23#7 | FIXED | `77d21b0` | Reporting tracks rally generation and relinquishes control when another order replaces it. |
| 23#8 | FIXED | `77d21b0` | Resolve once, validate actual attack eligibility before replacement, and count actual recipients. |
| 23#9 | FIXED | `77d21b0 + 711fec5` | Timed orders end on restoration and their persistent effects enter saved cleanup. |
| 24#1 | FIXED | `77d21b0` | Explicit AI recovery interrupts combat below 30%, persists through 50%, and yields to overrides. |
| 24#2 | FIXED | `77d21b0` | Counts actual fighters and excludes them from additional reinforcement candidates. |
| 24#3 | FIXED | `77d21b0 + 711fec5` | Detects raids by local squad homes and bounds participants; patrol cannot replace an active raid rally. |
| 24#4 | FIXED | `77d21b0 + 711fec5` | Persists manual behavior ownership, including cycling back to the raid-applied value. |
| 24#5 | FIXED | `77d21b0` | Auto-off restores raid behavior and cancels automatic recovery/rallies. |
| 24#6 | FIXED | `77d21b0` | Status/reporting preserves existing movement orders and foreign rallies. |
| 24#7 | FIXED | `711fec5 + 77d21b0 + 1c690d4` | Config supplies default autonomy, planning interval and new-order expiry at startup/reload; explicit choices survive. |
| 24#8 | FIXED | `77d21b0` | Autonomous threats require observation/protected-victim evidence; assignments honor suppression. |
| 24#9 | FIXED | `77d21b0` | Counts accepted scan boxes once, allowing the owner box plus three homes. |
| 25#1 | FIXED | `1c690d4` | End-to-end transport deadline covers stalled response bodies and cancels the exchange. |
| 25#2 | FIXED | `1c690d4` | Releases STT permits before successful, failed or cancelled caller continuations. |
| 25#3 | FIXED | `1c690d4` | Legacy TTS models receive compatible voices and no unsupported delivery instructions. |
| 26#1 | FIXED | `1c690d4` | Rejects every SVC group type and whisper before reading audio; joining a group cancels capture. |
| 26#2 | FIXED | `1c690d4` | Fresh server-thread admission checks consent, online state, proximity and channel, including delayed requests. |
| 26#3 | FIXED | `1c690d4` | Verified Fabric death/unload lifecycle hooks stop active playback. |
| A12 new#1 | FIXED | `77d21b0` | Attack pressing obeys per-recipient effective-order precedence and withdraws superseded directives. |
| A12 new#2 | FIXED | `77d21b0` | Rally generation tokens distinguish automatic rallies from later whistle/bell replacements. |
| A12 new#3 | FIXED | `77d21b0 + 711fec5` | Raid-origin reconciliation runs across loaded dimensions before empty-local-roster return. |
| A12 new#4 | FIXED | `711fec5 + 77d21b0` | Intake drains expiry cleanup; OrderBook retains any expiry notifications until consumed. |
| A13 new#1 | FIXED | `1c690d4` | Only saved explicit true consent authorizes capture/upload; server default cannot grant it. |
| A13 new#2 | FIXED | `1c690d4` | Transcript feedback defaults off, opt-in uses overlay, raw DEBUG transcript logging removed. |
| 07#3 | FIXED | `77d21b0` | Duplicate typed stale rally finding: cleanup precedes flags. |
| A04 new#1 | FIXED | `711fec5 + 77d21b0` | Duplicate typed overlapping attack finding: recipient-based partial removal. |
| 30#1 | FIXED | `711fec5 + 77d21b0 + 1c690d4` | All documented commander settings connected to runtime and tested with nondefaults. |
| 20#1 | FIXED | `77d21b0` | Immediate execution filters recipients through effective precedence. |
| 20#2 | FIXED | `711fec5 + 77d21b0` | Duplicate lost-expiry notification finding. |
| 20#3 | FIXED | `711fec5` | Capacity admission rejects weaker incoming orders before mutating existing recipients. |
| 22#2 | FIXED | `77d21b0` | Intake revisions invalidate delayed LLM responses after newer interpreted or typed orders. |
| A11 new#1 | FIXED | `77d21b0` | Captures intake session/dimension/location and target IDs; rejects changed relative contexts instead of retargeting. |
| 30#6 | FIXED | `77d21b0` | New recovery test proves movement and hysteresis; existing healed branch checks actual renewed combat. |
| 30#8 | FIXED | `1c690d4` | Controlled scheduler/clock replaces timing-dependent STT cancellation test. |

Cross-report 22#3 (model-specific LLM reasoning) is DEFERRED to the language fixer: the payload policy is in `LlmInterpreter`, outside this area's implementation. No independent patch to that file was made here.

## Verification

With `JAVA_HOME=C:/Program Files/Java/jdk-25`:

- `./gradlew --no-daemon --console=plain compileJava compileClientJava test` was iterated through compile corrections.
- Combined compile/test/build runs exercised all unit and server integration tests.
- Final exact command: `./gradlew --no-daemon --console=plain build`.
- Final output (`build-fix09-final.log`):

```text
[09:43:00] [Server thread/INFO] (Minecraft) All 99 required tests passed :)
BUILD SUCCESSFUL in 59s
```

315 unit tests passed. Twenty added server tests bring the original 79 required tests to 99. Tests use the actual Minecraft server and command/AI entry points; HTTP uses a loopback server, and SVC callback/privacy tests use API proxies. No live SVC client, live OpenAI upload, paid provider request, or client gameplay test was performed.

New/used Minecraft APIs were checked in the supplied 26.1.2 sources. Fabric lifecycle and SVC 2.6.20 interfaces were checked with JDK25 javap. Legacy TTS compatibility was checked against the official OpenAI voice documentation.

The recovery movement test now wounds an established, grounded fighter. Its initial immediate-spawn version hit the existing navigator's 40-tick airborne-path retry delay; no production timeout was changed. Its recovery movement window stays 25 ticks.

## Files touched outside the listed production ownership

- `src/main/java/com/guardvillagers/command/order/ActiveOrder.java`: recipient snapshots.
- `src/main/java/com/guardvillagers/command/order/OrderBook.java`: recipient splitting, precedence, expiry notifications and capacity admission.
- `src/main/java/com/guardvillagers/data/GuardCommandState.java`: saved recipients/cleanup, explicit auto choice and raid ownership/origin.
- `src/main/java/com/guardvillagers/command/hierarchy/CommandModule.java`: one ENTITY_LOAD cleanup hook.
- `src/main/java/com/guardvillagers/entity/GuardEntity.java`: recovery/rally-generation/directive APIs and manual raid-behavior ownership.
- `src/main/java/com/guardvillagers/entity/ai/GuardAiController.java`: recovery intent, rally generations, targeted directive withdrawal and command-only broadcast provenance.
- `build.gradle`: test-only SVC API dependency; no packaged runtime dependency added.
- `src/gametest/resources/fabric.mod.json`: register two regression classes.
- `src/gametest/java/com/guardvillagers/gametest/GuardCommanderGameTest.java`: healed assertion in `woundedGuardPulledBack`.
- New game tests: `GuardCommanderRegressionGameTest.java`, `GuardOrderRegressionGameTest.java` in the same directory.
- Unit tests: `command/order/OrderBookTest.java`, `data/GuardCommandStateTest.java`, `voice/{OpenAiHttpCloseTest,OpenAiTextToSpeechTest,VoiceConfigTest,VoicePipelineTest}.java`, and new `compat/voicechat/GuardVoicechatPrivacyTest.java`, all under `src/test/java/com/guardvillagers/`.
- This report.

`GuardCommands.java` changes are within the explicitly delegated follow/stay/hold hooks, but listed here for F02/F03 merge coordination: cleanup moved before flags, follow/stay gain `Integer row` overloads and resolve exact recipients.

## Merge instructions

- **F02/F03:** KEEP F09's follow/stay overload bodies and hold cleanup, plus F02's groups registration and `orderGroup(...)` helper. The overload signatures are `follow(source, canonicalName, Integer row)` and `stay(...)`, where row is zero-based. The exact cleanup signature is `CommanderService.onTypedOrder(ServerPlayer, List<GuardEntity>)`; the existing scope overload remains available.
- **F10 scheduler:** preserve the package-private constructor hook from `de928fc`. F09 now uses the same `BiConsumer<Runnable, Long>` ordering and adds ordered completion/cancellation behavior around it.
- **F10 wounded test:** keep F10's stronger setup/assertions, and retain F09's healed condition based on actual matching combat target plus `!guard.isCommanderRecovering()`. Correct reinforcement exclusion means an already-fighting recovered guard need not appear in the latest new-assignment map.
- **F10 docs:** `consentDefault=false`, but even true never grants consent; only explicit saved consent permits upload. `showTranscripts=false`; opt-in feedback is overlay, not chat. Raw DEBUG transcripts removed; INFO contains metadata/latency. All group types and whispers excluded; group entry cancels buffered, queued and in-flight capture.
- **F02/F08:** merge `GuardCommandState` and `OrderBook` changes with their independent work; partial recipient removal keeps the original active-order ID, so preserve its attack target mapping while survivors remain.

Compatibility note: newly issued orders always save exact UUID recipients. Older prototype saved orders lacking this information retain the legacy scope fallback; original renamed/unloaded recipient membership cannot be reconstructed from data that never stored it.
