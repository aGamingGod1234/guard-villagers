package com.guardvillagers.gametest.perf;

import net.fabricmc.fabric.api.gametest.v1.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;

/**
 * Opt-in server tick benchmark for guards. Runs only when the JVM has -Dguardvillagers.perf=true
 * (gradle: ./gradlew runGameTest -Pperf). Otherwise it passes immediately so normal test runs are unaffected.
 *
 * The test uses its own test environment so the game test runner puts it in a batch of its own:
 * no other test ticks while scenarios are measured.
 */
public class GuardPerfBenchmark {
	public static final boolean ENABLED = Boolean.getBoolean("guardvillagers.perf");

	@GameTest(environment = "guardvillagers-gametest:perf", maxTicks = 60000, required = false)
	public void suite(GameTestHelper helper) {
		if (!ENABLED) {
			helper.succeed();
			return;
		}
		PerfRunner runner = PerfRunner.start(helper);
		helper.succeedWhen(() -> helper.assertTrue(runner.isDone(), "benchmark still running"));
	}
}
