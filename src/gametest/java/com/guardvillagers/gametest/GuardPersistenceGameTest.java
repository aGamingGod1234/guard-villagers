package com.guardvillagers.gametest;

import static com.guardvillagers.gametest.GuardTestSupport.ARENA;
import static com.guardvillagers.gametest.GuardTestSupport.CENTER;
import static com.guardvillagers.gametest.GuardTestSupport.ENV;

import com.guardvillagers.GuardOwnershipIndex;
import com.guardvillagers.data.GuardOwnershipState;
import com.guardvillagers.GuardVillagersMod;
import com.guardvillagers.entity.FormationType;
import com.guardvillagers.entity.GuardBehavior;
import com.guardvillagers.entity.GuardEntity;
import com.guardvillagers.entity.GuardRole;
import java.util.UUID;
import net.fabricmc.fabric.api.gametest.v1.GameTest;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.util.ProblemReporter;
import net.minecraft.world.entity.EntitySpawnReason;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.storage.TagValueInput;
import net.minecraft.world.level.storage.TagValueOutput;

/** Entity save/load round trip, the same path used when a chunk unloads and reloads. */
public class GuardPersistenceGameTest {

	private static GuardEntity roundTrip(ServerLevel level, GuardEntity guard) {
		TagValueOutput output = TagValueOutput.createWithContext(ProblemReporter.DISCARDING, level.registryAccess());
		guard.saveWithoutId(output);
		CompoundTag tag = output.buildResult();
		GuardEntity copy = GuardVillagersMod.GUARD_ENTITY_TYPE.create(level, EntitySpawnReason.LOAD);
		copy.load(TagValueInput.create(ProblemReporter.DISCARDING, level.registryAccess(), tag));
		return copy;
	}

	@GameTest(structure = ARENA, environment = ENV, maxTicks = 40)
	public void deathReleasesOwnershipBeforeCorpseRemovalAndAfterReload(GameTestHelper helper) {
		GuardTestSupport.floor(helper);
		ServerLevel level = helper.getLevel();
		UUID owner = UUID.randomUUID();
		GuardEntity guard = GuardTestSupport.swordsman(helper, CENTER + 0.5, 1, CENTER + 0.5);
		guard.setOwnerUuid(owner);
		GuardOwnershipState state = level.getServer().getDataStorage().computeIfAbsent(GuardOwnershipState.TYPE);
		helper.assertValueEqual(state.countOwned(owner), 1, "ownership before death");
		guard.hurtServer(level, level.damageSources().genericKill(), Float.MAX_VALUE);
		helper.assertFalse(guard.isAlive(), "lethal damage must kill the guard");
		helper.assertFalse(guard.isRemoved(), "test must run before the corpse removal tick");
		helper.assertValueEqual(state.countOwned(owner), 0, "death must immediately release the saved slot");
		// Simulate an old saved ownership row paired with a corpse saved before its removal tick.
		state.record(guard.getUUID(), owner, 0L);
		GuardEntity copy = roundTrip(level, guard);
		helper.assertFalse(copy.isAlive(), "reloaded corpse must remain dead");
		helper.assertValueEqual(state.countOwned(owner), 0, "loading a corpse must remove its stale row");
		GuardOwnershipIndex.track(copy);
		helper.assertValueEqual(state.countOwned(owner), 0, "periodic tracking must not restore a dead row");
		helper.assertTrue(GuardOwnershipIndex.getOwnedGuards(level.getServer(), owner).isEmpty(), "dead guard in loaded roster");
		helper.succeed();
	}

	@GameTest(structure = ARENA, environment = ENV, maxTicks = 40)
	public void ownedGuardRoundTripsAllPlayerState(GameTestHelper helper) {
		GuardTestSupport.floor(helper);
		ServerLevel level = helper.getLevel();
		UUID ownerUuid = UUID.randomUUID();
		GuardEntity guard = GuardTestSupport.guard(helper, CENTER + 0.5, 1, CENTER + 0.5, GuardRole.BOWMAN);
		guard.setOwnerUuid(ownerUuid);
		guard.setExperience(360);
		guard.setBehavior(GuardBehavior.PERIMETER);
		guard.setFormationType(FormationType.SQUARE);
		guard.setGroupIndex(2);
		guard.setGroupColumn(0);
		guard.setGroupName("Alpha");
		BlockPos home = helper.absolutePos(new BlockPos(CENTER + 5, 1, CENTER - 5));
		guard.setHome(home, 30);
		guard.setSquadLeader(true);
		guard.setStaying(true);
		guard.setItemSlot(EquipmentSlot.HEAD, new ItemStack(Items.IRON_HELMET));
		String name = guard.getGeneratedName();

		GuardEntity copy = roundTrip(level, guard);
		// load() re-registers the copy under the same UUID; point the index back at the live guard.
		GuardOwnershipIndex.untrack(copy);
		GuardOwnershipIndex.track(guard);

		helper.assertValueEqual(copy.getUUID(), guard.getUUID(), "uuid");
		helper.assertValueEqual(copy.getOwnerUuid(), ownerUuid, "owner");
		helper.assertValueEqual(copy.getExperience(), 360, "experience");
		helper.assertValueEqual(copy.getLevel(), 4, "level");
		helper.assertValueEqual(copy.getRole(), GuardRole.BOWMAN, "role");
		helper.assertValueEqual(copy.getBehavior(), GuardBehavior.PERIMETER, "behavior");
		helper.assertValueEqual(copy.getFormationType(), FormationType.SQUARE, "formation");
		helper.assertValueEqual(copy.getGroupIndex(), 2, "group index");
		helper.assertValueEqual(copy.getGroupColumn(), 0, "group column");
		helper.assertValueEqual(copy.getGroupName(), "Alpha", "group name");
		helper.assertValueEqual(copy.getHome().orElse(null), home, "home");
		helper.assertValueEqual(copy.getPatrolRadius(), 30, "patrol radius");
		helper.assertValueEqual(copy.getSyncedHome().orElse(null), home, "synced home");
		helper.assertValueEqual(copy.isStaying(), true, "staying");
		helper.assertValueEqual(copy.getStayOrigin(), guard.getStayOrigin(), "stay origin");
		helper.assertValueEqual(copy.isSquadLeader(), true, "squad leader");
		helper.assertValueEqual(copy.getSquadId(), guard.getSquadId(), "squad id");
		helper.assertValueEqual(copy.getGeneratedName(), name, "generated name");
		helper.assertValueEqual(copy.getMainHandItem().getItem(), Items.BOW, "main hand");
		helper.assertValueEqual(copy.getItemBySlot(EquipmentSlot.HEAD).getItem(), Items.IRON_HELMET, "helmet");
		helper.assertValueEqual(copy.getMaxHealth(), guard.getMaxHealth(), "max health incl. level bonus");
		helper.succeed();
	}

	@GameTest(structure = ARENA, environment = ENV, maxTicks = 40)
	public void followOverrideSurvivesRoundTrip(GameTestHelper helper) {
		GuardTestSupport.floor(helper);
		GuardEntity guard = GuardTestSupport.swordsman(helper, CENTER + 0.5, 1, CENTER + 0.5);
		guard.setOwnerUuid(UUID.randomUUID());
		guard.setHome(helper.absolutePos(new BlockPos(CENTER, 1, CENTER)), 16);
		guard.setFollowOverride(true);

		GuardEntity copy = roundTrip(helper.getLevel(), guard);
		GuardOwnershipIndex.untrack(copy);
		GuardOwnershipIndex.track(guard);

		helper.assertValueEqual(copy.hasFollowOverride(), true, "follow override");
		helper.assertValueEqual(copy.isStaying(), false, "staying");
		helper.assertValueEqual(copy.getPatrolRadius(), 16, "patrol radius");
		helper.succeed();
	}

	@GameTest(structure = ARENA, environment = ENV, maxTicks = 40)
	public void unownedGuardRoundTripStaysUnowned(GameTestHelper helper) {
		GuardTestSupport.floor(helper);
		GuardEntity guard = GuardTestSupport.swordsman(helper, CENTER + 0.5, 1, CENTER + 0.5);
		guard.setBehavior(GuardBehavior.CROWD_CONTROL);

		GuardEntity copy = roundTrip(helper.getLevel(), guard);

		helper.assertFalse(copy.hasOwner(), "unowned guard gained an owner");
		helper.assertValueEqual(copy.getBehavior(), GuardBehavior.CROWD_CONTROL, "behavior");
		helper.assertValueEqual(copy.getRole(), GuardRole.SWORDSMAN, "role");
		helper.assertValueEqual(copy.getFormationType(), FormationType.FOLLOW, "formation");
		helper.succeed();
	}
	@GameTest(structure = ARENA, environment = ENV, maxTicks = 40)
	public void offlineOwnerReloadReconcilesStaleCommander(GameTestHelper helper) {
		GuardTestSupport.floor(helper);
		UUID owner = UUID.randomUUID();
		GuardEntity incumbent = GuardTestSupport.swordsman(helper, CENTER - 2.5, 1, CENTER + 0.5);
		GuardEntity stale = GuardTestSupport.swordsman(helper, CENTER + 2.5, 1, CENTER + 0.5);
		incumbent.setOwnerUuid(owner);
		stale.setOwnerUuid(owner);
		var hierarchy = com.guardvillagers.command.hierarchy.GuardHierarchy.state(helper.getLevel().getServer());
		hierarchy.getOrCreate(owner).setPinnedCommander(incumbent.getUUID());
		com.guardvillagers.command.hierarchy.GuardHierarchy.elect(helper.getLevel().getServer(), owner);
		com.guardvillagers.command.hierarchy.GuardHierarchy.tick(helper.getLevel().getServer());
		stale.setRank(com.guardvillagers.command.hierarchy.GuardRank.COMMANDER);
		GuardEntity copy = roundTrip(helper.getLevel(), stale);
		helper.succeedWhen(() -> {
			helper.assertTrue(copy.getRank() == com.guardvillagers.command.hierarchy.GuardRank.SOLDIER, "loaded stale rank never reconciled");
			helper.assertTrue(incumbent.getRank() == com.guardvillagers.command.hierarchy.GuardRank.COMMANDER, "incumbent changed");
			GuardOwnershipIndex.untrack(copy);
			GuardOwnershipIndex.track(stale);
		});
	}

}
