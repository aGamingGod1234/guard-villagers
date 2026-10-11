# F08 implementation report

Worktree: `wt-fix08`, branch `fix/review-08`, base `4d6fc22`.

## Findings

| Finding | Status | Implementation commit | Resolution |
| --- | --- | --- | --- |
| 03/1 (A02) | FIXED | 6153e03 | Evict only recomputable rows. Explicit consent and other player choices survive capacity pressure and NBT round trips; MAX_OWNERS is a soft limit. |
| 03/2 (A02) | FIXED | 6153e03 | Saved scope names encode losslessly; a long canonical guard name cannot abort saving. |
| 19/1 (A10) | FIXED | 6153e03 | Succession releases the promoted captain's pin, making subsequent incumbent elections stable. |
| 19/2 (A10) | FIXED | 6153e03 | Group captain replacement does not clear an unrelated commander pin. |
| 19/3 (A10) | FIXED | 6153e03, a90b6f9 | Persist captain pins by group; replace unloaded predecessors and revoke pins when guards leave their appointed group. Resolve legacy pins against saved captain/ownership records. |
| 19/4 (A10 PARTIAL) | FIXED | 6153e03 | Request an election after loading and registering an owned guard, including offline owners. |
| 20/1 (A10) | FIXED | ab30716 | Preserve overlapping scopes, admit execution only for recipients where the incoming order wins, and refuse execution when all recipients are outranked. |
| 20/2 (A10) | FIXED | ab30716 | Queue expiry removals for exactly-once delivery through expire(), preserving expired=true cleanup. |
| 20/3 (A10 PARTIAL) | FIXED | ab30716 | A lower-priority candidate cannot evict a higher-priority order at capacity. |
| 21/1 (A11) | FIXED | 12b66b8 | Require full semantic coverage; regressions cover both negations, unknown Steve, and appointment timing suffix. |
| 21/2 (A11) | FIXED | 12b66b8 | Require a strong action-bearing first word or explicit strong scope; regressions cover fill it, bill it, call back. |
| 21/3 (A11) | FIXED | 12b66b8 | Return no-match for an empty core after radius extraction. |
| 21/4 (A11) | FIXED | 12b66b8 | Preserve exact roster names before filler/soft-word removal. |
| 21/5 (A11) | FIXED | 12b66b8 | Tokenize Unicode letters/numbers; retain Chinese and dotless-i names. |
| 21/6 (A11) | FIXED | 12b66b8 | Protect exact name spans and score complete phrase/scope candidates. |
| 21/7 (A11) | FIXED | 12b66b8 | Protect roster-name spans before splitting clauses. |
| 21/8 (A11) | FIXED | 12b66b8 | Exact roster names beat fuzzy commander keywords. |
| 21/9 (A11) | FIXED | 12b66b8, ab30716 | Refuse qualified appointments because the order model cannot represent their requested group. Block LLM fallback for these refusals. Use an unqualified appointment after assigning the intended group. |
| 21/10 (A11) | FIXED | 12b66b8 | Accept commander/you prefixes before appointment verbs. |
| 22/1 (A11) | FIXED | f6dfafb | Close the actual response stream on timeout/cancellation, including the headers-before-reader race. Local socket test observes EOF before transport shutdown. |
| 22/2 (A11) | FIXED | ab30716 | Reject completion after newer input, player-session replacement, owner-context replacement, or interpreter replacement. |
| 22/3 (A11) | FIXED | f6dfafb | Expose optional llm.reasoningEffort; custom models omit reasoning by default; the default model retains low. |
| A11/new1 | FIXED | ab30716 | Capture dimension, anchor, facing, and candidate target UUIDs. Refuse delayed commands whose relevant references changed, with a repeat-order acknowledgement; never silently reselect a target or location. |
| A11/new2 | FIXED | f6dfafb | Preserve complete supported guard names in request and schema (512 code points); omit oversized identities instead of sending another guard's prefix. |
| A11/new3 | FIXED | 12b66b8 | Match full roster-name prefixes without the three-word cap; facade regression proves LLM invocation. |
| A12/new1 | FIXED (effective-order refresh) | ab30716 | Press attack directives only for guards where that attack is effective. General combat-directive undo remains F09's 23/6 work. |
| A12/new4 | FIXED | ab30716 | Duplicate of 20/2. |
| A04/new1, 23/4 | DEFERRED to F09 | none | Typed-command overlap requires F09's per-recipient cancellation and executor/brain integration. clearCovering remains unchanged; no claim that a typed group command now cancels intersecting individual/role orders. |
| 23/3 | DEFERRED to F09 | none | Dimension/recipient persistence belongs with F09's execution-recipient model; this branch does not change SavedOrder's recipient/dimension fields. |
| 24/7, 30/1, A15/1 | DEFERRED to F09 | none | Commander cadence, expiry configuration, and auto-command defaults require coordinated commander runtime changes. The existing configurable OrderBook constructor is retained. |

No assigned finding was rejected as NOT A BUG.

## Files outside F08 ownership

- `src/main/java/com/guardvillagers/entity/GuardEntity.java`: one election request at the existing end-of-load registration point.
- `src/main/java/com/guardvillagers/command/commander/CommanderService.java`: effective-recipient filtering; input/session/interpreter sequencing; delayed-context checks; qualified-appointment fallback gate.
- `src/main/java/com/guardvillagers/command/commander/CommanderBrain.java`: effective-order check before attack refresh.
- `src/main/java/com/guardvillagers/command/commander/VoiceCommandBridge.java`: forward configured reasoning effort.
- `src/main/java/com/guardvillagers/voice/VoiceConfig.java`: optional reasoning effort, default policy, and serialization.
- `src/test/java/com/guardvillagers/voice/VoiceConfigTest.java`: reasoning config regression.
- `src/gametest/java/com/guardvillagers/gametest/GuardCommanderGameTest.java`: four new game tests (captain pins/group changes; stable succession; effective-order execution; delayed LLM ordering/anchor).
- `src/gametest/java/com/guardvillagers/gametest/GuardPersistenceGameTest.java`: offline-owner rank reload regression.

These shared-file edits need reconciliation with F09 and the entity/voice fixers. No changes to OrderExecutor, TargetResolver, or other worktrees. No pushes or GitHub mutations.

## Verification

- Java 25, Loom 1.18.4, Minecraft 26.1.2 and supplied Fabric API.
- `./gradlew --no-daemon --console=plain compileJava compileClientJava test`: passed, 329 unit tests, zero failures/errors.
- Initial full build: passed; `All 84 required tests passed :)` (79 baseline plus five regressions).
- Final full build after the response-stream race and legacy-pin encoding changes: **BUILD SUCCESSFUL in 1m 35s**. Exact server result: `[09:38:40] [Server thread/INFO] (Minecraft) All 84 required tests passed :)`. Unit results: 329 tests, zero failures/errors. Log: `fix08-build-final.log`.
- Minecraft APIs checked against the supplied decompiled Entity/ServerPlayer sources and existing project entry points; codec behavior exercised through actual NBT encoding/decoding.
- LLM tests use fake transport or a local HTTP peer. No paid API or client UI test was performed.
