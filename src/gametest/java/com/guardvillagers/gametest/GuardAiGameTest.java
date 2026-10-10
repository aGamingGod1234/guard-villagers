package com.guardvillagers.gametest;

import static com.guardvillagers.gametest.GuardTestSupport.ARENA;
import static com.guardvillagers.gametest.GuardTestSupport.CENTER;
import static com.guardvillagers.gametest.GuardTestSupport.ENV;

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

/**
 * Runs in the isolated {@link GuardTestSupport#ARENA}, laid out around its centre. In the default 8x8
 * structure neighbouring tests stood 5 blocks apart, well inside the 24-block ally broadcast: the
 * bowman locked on to another test's husk behind the barrier walls, and the box was too small to kite
 * its own husk, so it sometimes lost the melee trade and died.
 */
public class GuardAiGameTest {
	/** Corner of the old 8x8 layout, so the positions below keep their original spacing. */
	private static final int O = CENTER - 4;

	/** A bowman engages and kills a single husk (which fights back) and survives. */
	@GameTest(structure = ARENA, environment = ENV, maxTicks = 800)
	public void bowmanKillsNearbyHusk(GameTestHelper helper) {
		GuardTestSupport.floor(helper);
		GuardEntity guard = helper.spawn(GuardVillagersMod.GUARD_ENTITY_TYPE, new BlockPos(O + 1, 1, O + 1));
		guard.setRole(GuardRole.BOWMAN);
		guard.setItemSlot(EquipmentSlot.MAINHAND, new ItemStack(Items.BOW));
		Husk husk = helper.spawn(EntityType.HUSK, new BlockPos(O + 6, 1, O + 6));
		helper.succeedWhen(() -> {
			helper.assertTrue(guard.isAlive(), "bowman died fighting a single husk");
			helper.assertFalse(husk.isAlive(), "husk still alive");
		});
	}

	/**
	 * Eight guards spawned on one block with the same home and crowd-control behavior must spread
	 * into distinct standing spots instead of stacking on the anchor.
	 */
	@GameTest(structure = ARENA, environment = ENV, maxTicks = 300)
	public void crowdControlGuardsSpreadOut(GameTestHelper helper) {
		GuardTestSupport.floor(helper);
		BlockPos center = new BlockPos(O + 4, 1, O + 4);
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
			// Slots are 1.5 apart and snap to block centres, so neighbours end 1-1.5 blocks apart;
			// stacked guards measure ~0.
			helper.assertTrue(meanNearest >= 1.0D, "guards not spread out, mean nearest neighbour " + meanNearest);
			helper.succeed();
		});
	}
}
