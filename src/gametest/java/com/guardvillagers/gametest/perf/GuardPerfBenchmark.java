package com.guardvillagers.gametest.perf;

import com.guardvillagers.GuardVillagersMod;
import java.util.HashSet;
import java.util.function.Predicate;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.monster.zombie.Husk;
import net.minecraft.world.level.entity.EntityTypeTest;
import net.minecraft.world.phys.Vec3;
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

	@GameTest(structure = "guardvillagers-gametest:arena", environment = "guardvillagers-gametest:arena")
	public void collisionIndexTracksLiveBoundsAndRemoval(GameTestHelper helper) {
		var level = helper.getLevel();
		var guard = helper.spawn(GuardVillagersMod.GUARD_ENTITY_TYPE, new Vec3(8, 2, 8));
		var other = helper.spawn(EntityType.HUSK, new Vec3(9, 2, 8));
		guard.setNoAi(true);
		other.setNoAi(true);
		var box = guard.getBoundingBox().inflate(4);
		Predicate<Entity> predicate = e -> e != guard;
		Runnable compare = () -> {
			var vanilla = level.getEntities(EntityTypeTest.forClass(Entity.class), box, predicate);
			var indexed = level.getEntities(guard, box, predicate);
			helper.assertTrue(new HashSet<>(vanilla).equals(new HashSet<>(indexed)), "indexed query differs from native query");
		};
		compare.run();
		other.setPos(other.getX() + 30, other.getY(), other.getZ());
		compare.run();
		other.setPos(guard.getX(), guard.getY(), guard.getZ());
		compare.run();
		other.setBoundingBox(other.getBoundingBox().move(30, 0, 0));
		compare.run();
		other.discard();
		compare.run();
		helper.spawn(EntityType.HUSK, new Vec3(9, 2, 8));
		compare.run();
		// Always-ticking entities remain in the visible map even in an inaccessible section.
		Husk alwaysTicking = new Husk(EntityType.HUSK, level) {
			@Override public boolean isAlwaysTicking() { return true; }
		};
		alwaysTicking.setPos(100000, guard.getY(), 100000);
		alwaysTicking.setNoAi(true);
		level.addFreshEntity(alwaysTicking);
		try {
			var hiddenBox = alwaysTicking.getBoundingBox().inflate(2);
			helper.assertTrue(level.getEntity(alwaysTicking.getUUID()) == alwaysTicking, "test entity must remain visible");
			var nativeHidden = level.getEntities(EntityTypeTest.forClass(Entity.class), hiddenBox, predicate);
			helper.assertTrue(nativeHidden.isEmpty(), "test section must be inaccessible");
			helper.assertTrue(level.getEntities(guard, hiddenBox, predicate).isEmpty(), "hidden section leaked through index");
		} finally {
			alwaysTicking.discard();
		}
		helper.succeed();
	}
}
