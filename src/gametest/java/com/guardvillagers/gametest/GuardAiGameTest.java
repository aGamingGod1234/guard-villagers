package com.guardvillagers.gametest;

import com.guardvillagers.GuardVillagersMod;
import com.guardvillagers.entity.GuardBehavior;
import com.guardvillagers.entity.GuardEntity;
import com.guardvillagers.entity.GuardRole;
import java.util.List;
import net.fabricmc.fabric.api.gametest.v1.GameTest;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.monster.zombie.Husk;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Blocks;

public class GuardAiGameTest {
	private static void floor(GameTestHelper helper) {
		for (int x = 0; x < 8; x++) {
			for (int z = 0; z < 8; z++) {
				helper.setBlock(new BlockPos(x, 0, z), Blocks.STONE);
			}
		}
	}

	/** A bowman engages and kills a hostile that never attacks it (husks ignore guards). */
	@GameTest(maxTicks = 800)
	public void bowmanKillsNearbyHusk(GameTestHelper helper) {
		floor(helper);
		GuardEntity guard = helper.spawn(GuardVillagersMod.GUARD_ENTITY_TYPE, new BlockPos(1, 1, 1));
		guard.setRole(GuardRole.BOWMAN);
		guard.setItemSlot(EquipmentSlot.MAINHAND, new ItemStack(Items.BOW));
		Husk husk = helper.spawn(EntityType.HUSK, new BlockPos(6, 1, 6));
		helper.succeedWhen(() -> {
			helper.assertTrue(guard.isAlive(), "bowman died fighting a single husk");
			helper.assertFalse(husk.isAlive(), "husk still alive");
		});
	}

	/**
	 * Eight guards spawned on one block with the same home and crowd-control behavior must spread
	 * into distinct standing spots instead of stacking on the anchor.
	 */
	@GameTest(maxTicks = 300)
	public void crowdControlGuardsSpreadOut(GameTestHelper helper) {
		floor(helper);
		BlockPos center = new BlockPos(4, 1, 4);
		BlockPos home = helper.absolutePos(center);
		List<GuardEntity> guards = helper.spawn(GuardVillagersMod.GUARD_ENTITY_TYPE, center, 8);
		for (GuardEntity guard : guards) {
			guard.setBehavior(GuardBehavior.CROWD_CONTROL);
			guard.setHome(home, 16);
		}
		helper.runAtTickTime(240, () -> {
			int overlappingPairs = 0;
			double nearestSum = 0.0D;
			for (int i = 0; i < guards.size(); i++) {
				double nearest = Double.MAX_VALUE;
				for (int j = 0; j < guards.size(); j++) {
					if (i == j) {
						continue;
					}
					double distance = guards.get(i).distanceTo(guards.get(j));
					nearest = Math.min(nearest, distance);
					if (j > i && distance < 0.6D) {
						overlappingPairs++;
					}
				}
				nearestSum += nearest;
			}
			double meanNearest = nearestSum / guards.size();
			helper.assertTrue(overlappingPairs == 0, overlappingPairs + " guard pairs overlapping after 12s");
			helper.assertTrue(meanNearest >= 1.2D, "guards not spread out, mean nearest neighbour " + meanNearest);
			helper.succeed();
		});
	}
}
