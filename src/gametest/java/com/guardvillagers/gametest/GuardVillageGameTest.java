package com.guardvillagers.gametest;

import static com.guardvillagers.gametest.GuardTestSupport.ARENA;
import static com.guardvillagers.gametest.GuardTestSupport.CENTER;
import static com.guardvillagers.gametest.GuardTestSupport.ENV;

import com.guardvillagers.entity.GuardEntity;
import com.guardvillagers.GuardVillagersMod;
import com.guardvillagers.village.VillageManagerHandler;
import java.util.List;
import net.fabricmc.fabric.api.gametest.v1.GameTest;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.level.block.BedBlock;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.properties.BedPart;
import net.minecraft.world.phys.Vec3;

/** Natural village guards appear around a small hand-built village (beds + villagers). */
public class GuardVillageGameTest {

	@GameTest(structure = ARENA, environment = ENV, maxTicks = 320)
	public void villageAfterFirst256VillagersStillSpawnsGuards(GameTestHelper helper) {
		villageWithEarlierIneligibleVillagers(helper, 256);
	}

	@GameTest(structure = ARENA, environment = ENV, maxTicks = 320)
	public void negativeLookupDoesNotHideVillageAtAnotherHeight(GameTestHelper helper) {
		villageWithEarlierIneligibleVillagers(helper, 1);
	}

	private static void villageWithEarlierIneligibleVillagers(GameTestHelper helper, int earlierCount) {
		GuardTestSupport.floor(helper);
		for (int i = 0; i < earlierCount; i++) {
			var villager = helper.spawn(EntityType.VILLAGER, new Vec3(CENTER + 0.5, 90, CENTER + 2.5));
			villager.setNoAi(true);
			villager.setNoGravity(true);
			GuardTestSupport.onFinish(helper, villager::discard);
		}
		bed(helper, CENTER, CENTER);
		bed(helper, CENTER + 2, CENTER);
		for (int i = 0; i < 2; i++) {
			var villager = helper.spawn(EntityType.VILLAGER, new Vec3(CENTER + 0.5, 1, CENTER + 2.5));
			villager.setNoAi(true);
		}
		helper.succeedWhen(() -> helper.assertFalse(helper.getLevel().getEntitiesOfClass(
			GuardEntity.class, helper.getBounds(), GuardEntity::isAlive).isEmpty(),
			"eligible surface village was hidden by earlier villagers"));
	}

	@GameTest(structure = ARENA, environment = ENV)
	public void discoveryAndSpawnSearchDoNotLoadDistantChunks(GameTestHelper helper) {
		var level = helper.getLevel();
		BlockPos distant = new BlockPos(20_000_000, 80, 20_000_000);
		int chunkX = distant.getX() >> 4;
		int chunkZ = distant.getZ() >> 4;
		helper.assertTrue(level.getChunkSource().getChunkNow(chunkX, chunkZ) == null, "test chunk was already loaded");
		helper.assertTrue(VillageManagerHandler.findVillageDescriptor(level, distant).isEmpty(), "unexpected distant village");
		helper.assertTrue(GuardVillagersMod.findNearbyGuardSpawnPos(level, distant, 2) == null, "spawn search loaded terrain");
		helper.assertTrue(level.getChunkSource().getChunkNow(chunkX, chunkZ) == null, "discovery loaded terrain");
		helper.succeed();
	}

	private static void bed(GameTestHelper helper, int x, int z) {
		var foot = Blocks.RED_BED.defaultBlockState().setValue(BedBlock.FACING, Direction.NORTH).setValue(BedBlock.PART, BedPart.FOOT);
		var head = Blocks.RED_BED.defaultBlockState().setValue(BedBlock.FACING, Direction.NORTH).setValue(BedBlock.PART, BedPart.HEAD);
		helper.setBlock(new BlockPos(x, 1, z), foot);
		helper.setBlock(new BlockPos(x, 1, z - 1), head);
	}

	/**
	 * Three beds and three villagers. The village manager runs every 100 game ticks, builds a fallback
	 * village from the nearest bed POI, and should spawn up to two unowned guards with a home set.
	 */
	@GameTest(structure = ARENA, environment = ENV, maxTicks = 320)
	public void naturalGuardsSpawnInBuiltVillage(GameTestHelper helper) {
		GuardTestSupport.floor(helper);
		bed(helper, CENTER - 3, CENTER);
		bed(helper, CENTER, CENTER);
		bed(helper, CENTER + 3, CENTER);
		helper.setBlock(new BlockPos(CENTER, 1, CENTER + 4), Blocks.BELL);
		for (int i = 0; i < 3; i++) {
			helper.spawn(EntityType.VILLAGER, new Vec3(CENTER - 3 + i * 3 + 0.5, 1, CENTER + 2.5));
		}

		helper.succeedWhen(() -> {
			List<GuardEntity> guards = helper.getLevel().getEntitiesOfClass(GuardEntity.class, helper.getBounds(), g -> g.isAlive());
			helper.assertFalse(guards.isEmpty(), "no natural guard spawned");
			for (GuardEntity guard : guards) {
				helper.assertFalse(guard.hasOwner(), "natural guard has an owner");
				helper.assertTrue(guard.getHome().isPresent(), "natural guard has no village home");
				helper.assertFalse(guard.getMainHandItem().isEmpty(), "natural guard spawned unarmed");
			}
		});
	}

	/** Villagers alone (no beds or job sites) are not a village; nothing should spawn. */
	@GameTest(structure = ARENA, environment = ENV, maxTicks = 260)
	public void noGuardsWithoutVillageStructures(GameTestHelper helper) {
		GuardTestSupport.floor(helper);
		for (int i = 0; i < 3; i++) {
			helper.spawn(EntityType.VILLAGER, new Vec3(CENTER - 3 + i * 3 + 0.5, 1, CENTER + 2.5));
		}
		helper.runAfterDelay(240, () -> {
			List<GuardEntity> guards = helper.getLevel().getEntitiesOfClass(GuardEntity.class, helper.getBounds(), g -> g.isAlive());
			helper.assertTrue(guards.isEmpty(), guards.size() + " guards spawned with no beds or job sites");
			helper.succeed();
		});
	}
}
