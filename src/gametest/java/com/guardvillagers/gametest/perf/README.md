# Guard server benchmark

Run with Java 25: `./gradlew --no-daemon --console=plain runGameTest -Pperf`.
The benchmark suite is opt-in; the collision-index regression test runs in normal server tests.

Default populations: 16, 64, 128, 256, 512. The enclosed arena is 80 by 80 blocks with all surrounding chunks forced to tick. Combat starts near the centre; incoming husks start 22 blocks from the centre so increasing arena size does not move the fight beyond guard detection range.

- `idle`: N guards and 16 villagers.
- `follow`: N owned guards following one moving, registered mock player.
- `combat`: N unowned guards and N/2 husks.
- `patrol`: N guards with a home and perimeter behavior.
- `commander`: one registered owner, N guards in four groups, automatic commander decisions, and N/2 husks.
- `mixed`: four owners with four groups each, N guards total, and N/2 husks. Existing diplomacy applies: guards belonging to different owners can fight each other.

Combat populations are replenished after timing each tick, at most four replacements per population per tick. Reports include surviving populations and respawn counts. Vanilla controls use N villagers, or N villagers with N/2 husks. Controls retain native AI; they compare server workload, not identical combat outcomes. Idle includes 16 additional background villagers beyond its N-villager control.

`-PperfSizes=128,256,512` selects sizes. `-PperfOnly=combat,follow,commander,mixed` selects guard scenario prefixes; matching-size vanilla controls and the empty phase are always included. `-PperfMeasure=600`, `-PperfWarmup=100`, and `-PperfJitWarmup=200` adjust phase lengths. Every kind receives an unrecorded JIT warmup.

Results in `build/perf/perf-<timestamp>.{md,csv}` time the complete server tick, excluding benchmark setup, teardown, and replacement spawning. The per-guard column is `(scenario mean - empty mean) / N`, including any hostiles and commander work; it is not an isolated guard-method timer. Delta columns compare the matching vanilla control. Keep before/after settings identical and rerun when the controls indicate machine-load changes.

`-PperfJfr` records `build/perf/guard-perf.jfr`. Copy it before the next run overwrites it. Analyze with:

```
java src/gametest/java/com/guardvillagers/gametest/perf/JfrReport.java build/perf/guard-perf.jfr combat_512 15
```

JFR samples are attributed by phase and server thread; harness upkeep stacks are excluded, matching the tick timer. Sampling shares are estimates, especially for short phases. Reports and recordings are local build artifacts and should not be committed.
