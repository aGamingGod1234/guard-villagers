package com.guardvillagers.gametest;

import static com.guardvillagers.gametest.GuardTestSupport.ARENA;
import static com.guardvillagers.gametest.GuardTestSupport.CENTER;
import static com.guardvillagers.gametest.GuardTestSupport.ENV;

import com.guardvillagers.GuardVillagersMod;
import com.guardvillagers.entity.GuardEntity;
import net.fabricmc.fabric.api.gametest.v1.GameTest;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.monster.zombie.Husk;

/** Isolated arena for the same reason as {@link GuardAiGameTest}: this guard's broadcasts reached the bowman test. */
public class GuardCombatGameTest {
	private static final int O = CENTER - 4;

	@GameTest(structure = ARENA, environment = ENV, maxTicks = 600)
	public void guardKillsNearbyHusk(GameTestHelper helper) {
		GuardTestSupport.floor(helper);
		GuardEntity guard = helper.spawn(GuardVillagersMod.GUARD_ENTITY_TYPE, new BlockPos(O + 1, 1, O + 1));
		// Husks don't burn in daylight, so the only way this one dies is the guard killing it.
		Husk husk = helper.spawn(EntityType.HUSK, new BlockPos(O + 5, 1, O + 5));
		helper.succeedWhen(() -> {
			helper.assertTrue(guard.isAlive(), "guard died fighting a single husk");
			helper.assertFalse(husk.isAlive(), "husk still alive");
		});
	}
}
