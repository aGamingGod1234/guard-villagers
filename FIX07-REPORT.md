# Fixer 07: survival and stationary behaviors

Branch: `fix/review-07`, based on `4d6fc22`. All work stayed in `wt-fix07`. No pushes or GitHub operations.

## Findings

- **17/1 ? FIXED ? f9a6589.** SeekAir selects complete reachable routes, validates body clearance and actual eye height at the endpoint, and reselects failed/completed routes. A final centering step clears the guard's shoulders from the roof opening before floating upward. The real submerged-guard test exposed and verifies this last step.
- **17/2 ? FIXED ? f9a6589.** ReturnToLand rejects unreachable exits and last-land fallbacks and requests reach range zero. The same-height, sign-contained puddle test verifies actual dry arrival; another test rejects a nearer sealed dry room.
- **17/3 ? FIXED ? d0e9ef1.** The unstick watchdog exempts Hold and requires an unfinished movement request.
- **17/4 ? FIXED ? f9a6589.** The 40-game-tick deadline gates empty and completed searches as well as successful searches.
- **18/1 ? FIXED ? d0e9ef1.** Bell recipient search caching is separate from successful per-recipient cooldowns. Permission is checked for each ring. Bob rallying his own guard cannot consume Alice's allowance. Tested through the actual BellBlock mixin entry point.
- **18/2 ? FIXED ? d0e9ef1.** Same root cause as 17/3. Settled crowd/home anchors have no unfinished path, so deliberate stationary occupancy cannot trigger recovery. The claimed unconditional home cycle was not assumed; the confirmed Hold/crowd cases are tested.
- **18/3 ? FIXED ? d0e9ef1.** Whistles preserve Stay and the saved Hold origin. Active rallies temporarily suspend effective Hold. Expiry restores Hold automatically; subsequent explicit Hold, Stay, Follow, or Home orders cancel the temporary rally. Zero-duration rallies clear immediately. Tests cover live whistle movement, expiry, preserved Stay, and a superseding Hold.
- **18/4 ? FIXED ? d0e9ef1.** Hold's idle executor starts a reach-zero route instead of RandomStroll's default reach-one route. A guard displaced by one block returns to its original block.
- **18/5 ? FIXED ? d0e9ef1.** AnchorHold requests exact block arrival and restricts final direct centering to the slot's own block. The reported fractional-slot/adjacent-endpoint example is covered.
- **18/6 ? FIXED (confirmed part) ? f9a6589.** Grounding searches all nearby columns at each vertical distance before considering more distant heights, preferring an adjacent street to a roof. It does not claim that every reachable roof is forbidden.
- **18/7 ? FIXED ? d0e9ef1.** Owned whistle interactions return PASS to the normal item handler, after the existing ownership check. Real Player.interactOn tests cover home, radius, and unauthorized clicks.
- **A09/New 1 ? FIXED ? f9a6589.** Offensive raid positioning continues approaching a nearby raider when sight is blocked.
- **A09/New 2 ? FIXED ? f9a6589.** Retreat validates exact reachable slots while trying clerics, beds, home, then escape directions. An unreachable preferred refuge cannot suppress alternatives; the selected safe point is retained for the 40-tick refresh interval. The raised-cleric/reachable-home case is tested.
- **A09/New 3 ? FIXED ? f9a6589.** Empty raid searches honor the 20-game-tick deadline; a newly dead existing target triggers one immediate reselection.
- **10/4 (A05 duplicate of 18/1) ? FIXED ? d0e9ef1.** Same bell cooldown fix.
- **16/4 (A08) ? FIXED ? f9a6589.** Grounding accepts WATER_BORDER standing positions, allowing narrow dry shores. This was necessary for the same-height shallow-water regression.

No assigned finding was rejected or deferred.

## Files touched outside the listed ownership

Paths are relative to the repository root.

- `src/main/java/com/guardvillagers/entity/GuardEntity.java`: minimal order-setter/rally integration, effective Hold check, whistle PASS branch, watchdog guard, and replacement of the retreat-search body with a call into TacticalRetreatGoal. Removed only imports made unused by that move. Coordinate with the combat fixer.
- `src/main/java/com/guardvillagers/entity/ai/GuardAiController.java`: one-line immediate clearing of the rally point for zero-duration rallies. Coordinate with the combat/commander fixers.
- `src/main/java/com/guardvillagers/navigation/GuardNavigation.java`: four-line predicate overload for dry exits and a predicate check during candidate selection. Existing no-argument callers retain their behavior. Coordinate with the movement fixer.
- `src/main/java/com/guardvillagers/navigation/GuardGrounding.java`: height-first nearby search and WATER_BORDER acceptance. Coordinate with the movement fixer, including duplicate 16/4.
- `src/gametest/java/com/guardvillagers/gametest/GuardBehaviorRegressionGameTest.java`: new isolated regression class with 15 server scenarios.
- `src/gametest/resources/fabric.mod.json`: one server-test entrypoint.
- `FIX07-REPORT.md`: this completion report.

A12/New 2's stale automatic-rally ownership marker is rooted in CommanderBrain, outside this assignment. No commander ownership-tracking code was changed here; its separate generation/provenance fix must retain the immediate zero-duration clearing in the controller. The bell mixin descriptor was checked against Minecraft 26.1.2 and needed no change.

## Verification

Used `JAVA_HOME=C:/Program Files/Java/jdk-25`. API checks used the supplied Minecraft 26.1.2 sources (PathNavigation, GroundPathNavigation, WalkNodeEvaluator, Path/Node, MoveControl, CollisionGetter, Entity, Vec3, Player, BellBlock, PoiManager, and VillagerData), then compilation against the requested dependencies.

- `./gradlew --no-daemon --console=plain compileJava compileClientJava test` passed during iteration.
- Focused run of all 15 new server scenarios passed.
- Final, unfiltered `./gradlew --no-daemon --console=plain build` passed on the final Java/test sources.
- 293 unit tests passed: zero failures, errors, or skips.
- All original 79 required server tests and 15 added regression tests passed.

```text
[09:41:04] [Server thread/INFO] (Minecraft) All 94 required tests passed :)
BUILD SUCCESSFUL in 1m 13s
```

Final build log: `fix07-final-build.log` (ignored local artifact). Verification was server-side and unit/build testing; no client/manual gameplay session was performed. Some isolated path-selection/throttle tests freeze AI and set the grounded state explicitly; live movement, rally expiry, bell, whistle, and water escape tests run through actual server entity behavior.
