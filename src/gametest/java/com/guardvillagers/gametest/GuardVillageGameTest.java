package com.guardvillagers.gametest;

import static com.guardvillagers.gametest.GuardTestSupport.ARENA;
import static com.guardvillagers.gametest.GuardTestSupport.CENTER;
import static com.guardvillagers.gametest.GuardTestSupport.ENV;

import com.guardvillagers.entity.GuardEntity;
import com.guardvillagers.GuardVillagersMod;
import com.guardvillagers.GuardDiplomacyManager;
import com.guardvillagers.data.GuardVillageState;
import com.guardvillagers.village.GuardBellRally;
import com.guardvillagers.village.VillageManagerHandler;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.UUID;
import net.fabricmc.fabric.api.gametest.v1.GameTest;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntitySpawnReason;
import net.minecraft.world.level.block.BedBlock;
import net.minecraft.world.level.block.BellBlock;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.properties.BedPart;
import net.minecraft.world.phys.Vec3;

/** Natural village guards appear around a small hand-built village (beds + villagers). */
public class GuardVillageGameTest {

	@GameTest(structure = ARENA, environment = ENV)
	public void rejectedBellRingDoesNotBlockOwnersTrustedPlayersOrRedstone(GameTestHelper helper) {
		GuardTestSupport.floor(helper);
		var level = helper.getLevel();
		BlockPos bell = helper.absolutePos(new BlockPos(CENTER, 1, CENTER));
		helper.setBlock(new BlockPos(CENTER, 1, CENTER), Blocks.BELL);
		var owner = GuardTestSupport.survivalPlayer(helper, CENTER + 3, 1, CENTER);
		var stranger = GuardTestSupport.survivalPlayer(helper, CENTER + 4, 1, CENTER);
		GuardEntity owned = GuardTestSupport.ownedGuard(helper, CENTER + 6, 1, CENTER, owner);
		GuardEntity strangers = GuardTestSupport.ownedGuard(helper, CENTER + 7, 1, CENTER, stranger);
		GuardEntity trusted = GuardTestSupport.swordsman(helper, CENTER + 8, 1, CENTER);
		UUID trustedOwner = UUID.randomUUID();
		trusted.setOwnerUuid(trustedOwner);
		GuardDiplomacyManager.trust(level.getServer(), trustedOwner, owner.getUUID());
		GuardEntity redstone = GuardTestSupport.swordsman(helper, CENTER + 9, 1, CENTER);
		redstone.setOwnerUuid(UUID.randomUUID());
		for (GuardEntity guard : List.of(owned, strangers, trusted, redstone)) {
			guard.setHome(bell, 32);
			guard.setNoAi(true);
		}
		BellBlock block = (BellBlock) Blocks.BELL;
		helper.assertTrue(block.attemptToRing(stranger, level, bell, Direction.NORTH), "stranger could not ring bell");
		helper.assertTrue(strangers.getRallyPoint().isPresent(), "stranger's own guard did not rally");
		helper.assertTrue(owned.getRallyPoint().isEmpty() && trusted.getRallyPoint().isEmpty(), "unauthorized ring commanded another owner's guard");
		helper.assertTrue(block.attemptToRing(owner, level, bell, Direction.NORTH), "owner could not ring bell");
		helper.assertValueEqual(owned.getRallyPoint().orElse(null), bell, "owner rally after stranger");
		helper.assertValueEqual(trusted.getRallyPoint().orElse(null), bell, "trusted player rally after stranger");
		helper.assertTrue(redstone.getRallyPoint().isEmpty(), "untrusted player rallied redstone-only fixture");
		helper.assertTrue(block.attemptToRing(level, bell, Direction.NORTH), "redstone could not ring bell");
		helper.assertValueEqual(redstone.getRallyPoint().orElse(null), bell, "redstone rally after rejected player ring");
		helper.assertValueEqual(GuardBellRally.onBellRung(level, bell, null), 0, "accepted owner cooldowns still throttle repeated rings");
		helper.succeed();
	}

	@GameTest(structure = ARENA, environment = ENV, maxTicks = 280)
	public void movedAndUnloadedGarrisonStillFillsVillageCap(GameTestHelper helper) {
		GuardTestSupport.floor(helper);
		bed(helper, CENTER, CENTER);
		bed(helper, CENTER + 1, CENTER);
		var level = helper.getLevel();
		BlockPos home = helper.absolutePos(new BlockPos(CENTER, 1, CENTER - 1));
		String villageId = level.dimension().identifier() + "|poi|" + (home.getX() >> 4) + "|" + (home.getZ() >> 4);
		UUID squad = UUID.nameUUIDFromBytes(villageId.getBytes(StandardCharsets.UTF_8));
		GuardVillageState state = level.getDataStorage().computeIfAbsent(GuardVillageState.TYPE);
		for (int i = 0; i < 2; i++) {
			var villager = helper.spawn(EntityType.VILLAGER, new Vec3(CENTER + 0.5, 1, CENTER + 2.5));
			villager.setNoAi(true);
			GuardEntity guard = GuardVillagersMod.GUARD_ENTITY_TYPE.create(level, EntitySpawnReason.NATURAL);
			guard.setSquadId(squad);
			guard.setHome(home, 32);
			guard.setNoAi(true);
			guard.setNoGravity(true);
			guard.snapTo(home.getX() + 0.5, home.getY(), home.getZ() + 0.5, 0, 0);
			helper.assertTrue(level.addFreshEntity(guard), "garrison fixture did not load");
			guard.snapTo(home.getX() + 70.5, home.getY() + 80, home.getZ() + 0.5, 0, 0);
			GuardTestSupport.onFinish(helper, () -> {
				guard.discard();
				state.removeNaturalGuard(guard.getUUID());
			});
			if (i == 0) {
				guard.remove(Entity.RemovalReason.UNLOADED_TO_CHUNK);
			}
		}
		helper.assertValueEqual(state.countNaturalGuards(squad), 2, "loaded and unloaded membership");
		helper.runAfterDelay(240, () -> {
			helper.assertTrue(state.getMaxGuardCount(villageId) >= 2, "village was not processed during the test");
			helper.assertTrue(level.getEntitiesOfClass(GuardEntity.class, helper.getBounds(), GuardEntity::isAlive).isEmpty(),
				"village spawned replacements for its living garrison outside the bounds");
			helper.assertValueEqual(state.countNaturalGuards(squad), 2, "persistent garrison count");
			helper.succeed();
		});
	}

	@GameTest(structure = ARENA, environment = ENV, maxTicks = 160)
	public void hiringAndDeathReleaseNaturalMembership(GameTestHelper helper) {
		GuardTestSupport.floor(helper);
		var level = helper.getLevel();
		UUID squad = UUID.randomUUID();
		GuardVillageState state = level.getDataStorage().computeIfAbsent(GuardVillageState.TYPE);
		GuardEntity guard = GuardVillagersMod.GUARD_ENTITY_TYPE.create(level, EntitySpawnReason.NATURAL);
		guard.setSquadId(squad);
		guard.setHome(helper.absolutePos(new BlockPos(CENTER, 1, CENTER)), 32);
		guard.snapTo(helper.absoluteVec(new Vec3(CENTER + 0.5, 1, CENTER + 0.5)));
		helper.assertTrue(level.addFreshEntity(guard), "natural guard did not load");
		helper.assertValueEqual(state.countNaturalGuards(squad), 1, "spawned membership");
		GuardEntity doomed = GuardVillagersMod.GUARD_ENTITY_TYPE.create(level, EntitySpawnReason.NATURAL);
		doomed.setSquadId(squad);
		doomed.setHome(guard.getHome().orElseThrow(), 32);
		doomed.snapTo(guard.position());
		helper.assertTrue(level.addFreshEntity(doomed), "second natural guard did not load");
		helper.assertValueEqual(state.countNaturalGuards(squad), 2, "second spawned membership");
		doomed.remove(Entity.RemovalReason.KILLED);
		helper.assertValueEqual(state.countNaturalGuards(squad), 1, "death should release membership immediately");
		guard.setOwnerUuid(UUID.randomUUID());
		helper.runAfterDelay(110, () -> {
			helper.assertValueEqual(state.countNaturalGuards(squad), 0, "hiring should release membership");
			guard.setOwnerUuid(null);
			guard.discard();
			helper.assertValueEqual(state.countNaturalGuards(squad), 0, "destroyed guards must not be re-recorded");
			helper.succeed();
		});
	}

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
