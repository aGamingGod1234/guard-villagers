package com.guardvillagers.gametest;

import com.guardvillagers.GuardVillagersMod;
import com.guardvillagers.entity.GuardEntity;
import net.fabricmc.fabric.api.gametest.v1.GameTest;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.monster.zombie.Husk;
import net.minecraft.world.level.block.Blocks;

public class GuardCombatGameTest {
	private static void floor(GameTestHelper helper) {
		for (int x = 0; x < 8; x++) {
			for (int z = 0; z < 8; z++) {
				helper.setBlock(new BlockPos(x, 0, z), Blocks.STONE);
			}
		}
	}

	@GameTest(maxTicks = 600)
	public void guardKillsNearbyHusk(GameTestHelper helper) {
		floor(helper);
		GuardEntity guard = helper.spawn(GuardVillagersMod.GUARD_ENTITY_TYPE, new BlockPos(1, 1, 1));
		// Husks don't burn in daylight, so the only way this one dies is the guard killing it.
		Husk husk = helper.spawn(EntityType.HUSK, new BlockPos(5, 1, 5));
		helper.succeedWhen(() -> {
			helper.assertTrue(guard.isAlive(), "guard died fighting a single husk");
			helper.assertFalse(husk.isAlive(), "husk still alive");
		});
	}
}
