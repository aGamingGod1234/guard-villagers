package com.guardvillagers.gametest;

import static com.guardvillagers.gametest.GuardTestSupport.ARENA;
import static com.guardvillagers.gametest.GuardTestSupport.ENV;

import com.guardvillagers.command.commander.CommanderService;
import com.guardvillagers.entity.GuardEntity;
import com.guardvillagers.entity.ai.GuardHuntManager;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import net.fabricmc.fabric.api.event.player.AttackEntityCallback;
import net.fabricmc.fabric.api.gametest.v1.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.entity.ai.memory.MemoryModuleType;
import net.minecraft.world.entity.raid.Raid;
import net.minecraft.world.level.GameType;
import net.minecraft.world.phys.Vec3;

/** Real server AI and event callbacks; stationary victims keep these tests about hunt orders, not fleeing AI. */
public class GuardHuntGameTest {
	private static void punch(ServerPlayer owner, LivingEntity target) {
		AttackEntityCallback.EVENT.invoker().interact(owner, owner.level(), InteractionHand.MAIN_HAND, target, null);
	}

	private static <T extends Mob> T stationary(T mob) { mob.setNoAi(true); return mob; }

	private static List<GuardEntity> squad(GameTestHelper helper, ServerPlayer owner, int count) {
		List<GuardEntity> guards = new ArrayList<>();
		for (int i = 0; i < count; i++) guards.add(GuardTestSupport.ownedGuard(helper, 12 + i * 4, 1, 20, owner));
		return guards;
	}

	private static void contains(GameTestHelper helper, GuardEntity guard, LivingEntity... targets) {
		var hunt = GuardHuntManager.get(guard);
		helper.assertTrue(hunt != null, "hunt missing");
		for (LivingEntity target : targets) helper.assertTrue(hunt.targets().contains(target), "missing " + target.getType());
	}

	@GameTest(structure = ARENA, environment = ENV, maxTicks = 80)
	public void hundredsOfGuardsShareOneHuntAndSpreadAcrossTargets(GameTestHelper helper) {
		GuardTestSupport.floor(helper);
		ServerPlayer owner = GuardTestSupport.player(helper, GameType.CREATIVE, 24, 1, 10);
		List<GuardEntity> guards = new ArrayList<>();
		for (int i = 0; i < 200; i++) guards.add(GuardTestSupport.ownedGuard(helper, 9 + i % 20, 1, 12 + i / 20, owner));
		List<LivingEntity> cows = new ArrayList<>();
		for (int i = 0; i < 6; i++) {
			var cow = stationary(helper.spawn(EntityType.COW, new Vec3(10 + i * 5, 1, 30)));
			cow.getAttribute(Attributes.MAX_HEALTH).setBaseValue(1024);
			cow.setHealth(1024);
			cows.add(cow);
		}
		punch(owner, cows.getFirst());
		var shared = GuardHuntManager.get(guards.getFirst());
		helper.runAfterDelay(25, () -> {
			helper.assertTrue(guards.stream().allMatch(g -> GuardHuntManager.get(g) == shared), "guards did not share the owner roster");
			long fighting = guards.stream().filter(g -> g.getTarget() != null).count();
			long distinct = guards.stream().map(GuardEntity::getTarget).filter(java.util.Objects::nonNull).distinct().count();
			helper.assertTrue(fighting >= 190 && distinct == 6, "large hunt failed to spread: fighting=" + fighting + ", targets=" + distinct);
			helper.succeed();
		});
	}

	@GameTest(structure = ARENA, environment = ENV, maxTicks = 40)
	public void publicCaravanAndPiglinHoglinSocialLinksAreExpanded(GameTestHelper helper) {
		GuardTestSupport.floor(helper);
		ServerPlayer owner = GuardTestSupport.player(helper, GameType.CREATIVE, 24, 1, 10);
		GuardEntity guard = GuardTestSupport.ownedGuard(helper, 24, 1, 20, owner);
		var tail = stationary(helper.spawn(EntityType.LLAMA, new Vec3(10, 1, 27)));
		var head = stationary(helper.spawn(EntityType.TRADER_LLAMA, new Vec3(39, 1, 27)));
		tail.joinCaravan(head);
		punch(owner, tail);
		contains(helper, guard, tail, head);
		var piglin = stationary(helper.spawn(EntityType.PIGLIN, new Vec3(10, 1, 30)));
		var brute = stationary(helper.spawn(EntityType.PIGLIN_BRUTE, new Vec3(39, 1, 30)));
		piglin.getBrain().setMemory(MemoryModuleType.NEARBY_ADULT_PIGLINS, List.of(brute));
		helper.runAfterDelay(6, () -> punch(owner, piglin));
		helper.runAfterDelay(12, () -> contains(helper, guard, piglin, brute));
		var hoglin = stationary(helper.spawn(EntityType.HOGLIN, new Vec3(10, 1, 35)));
		var herd = stationary(helper.spawn(EntityType.HOGLIN, new Vec3(39, 1, 35)));
		hoglin.getBrain().setMemory(MemoryModuleType.NEAREST_VISIBLE_ADULT_HOGLINS, List.of(herd));
		helper.runAfterDelay(18, () -> punch(owner, hoglin));
		helper.runAfterDelay(24, () -> {
			contains(helper, guard, hoglin, herd);
			helper.succeed();
		});
	}

	@GameTest(structure = ARENA, environment = ENV, maxTicks = 40)
	public void capKeepsNearestNinetySixAndLogoutClearsHunt(GameTestHelper helper) {
		GuardTestSupport.floor(helper);
		ServerPlayer owner = GuardTestSupport.player(helper, GameType.CREATIVE, 24, 1, 10);
		GuardEntity guard = GuardTestSupport.ownedGuard(helper, 24, 1, 20, owner);
		guard.setNoAi(true);
		List<LivingEntity> cows = new ArrayList<>();
		for (int i = 0; i < 100; i++) cows.add(stationary(helper.spawn(EntityType.COW, new Vec3(10 + i * 0.2, 1, 27))));
		punch(owner, cows.getFirst());
		var targets = GuardHuntManager.get(guard).targets();
		helper.assertValueEqual(targets.size(), 96, "hunt cap");
		helper.assertTrue(targets.containsAll(cows.subList(0, 96)), "cap did not prefer nearest");
		helper.assertFalse(targets.contains(cows.getLast()), "farthest cow survived cap");
		helper.getLevel().getServer().getPlayerList().remove(owner);
		helper.runAfterDelay(10, () -> {
			helper.assertTrue(GuardHuntManager.get(guard) == null, "logout retained hunt");
			helper.succeed();
		});
	}

	@GameTest(structure = ARENA, environment = ENV, maxTicks = 40)
	public void holdReachZoneAndSuspendedCombatStillRestrictHunts(GameTestHelper helper) {
		GuardTestSupport.floor(helper);
		ServerPlayer owner = GuardTestSupport.survivalPlayer(helper, 24, 1, 10);
		GuardEntity held = GuardTestSupport.ownedGuard(helper, 16, 1, 20, owner);
		held.setHoldPosition(true);
		GuardEntity zoned = GuardTestSupport.ownedGuard(helper, 24, 1, 20, owner);
		zoned.setHome(zoned.blockPosition(), 4);
		GuardEntity suspended = GuardTestSupport.ownedGuard(helper, 32, 1, 20, owner);
		suspended.suspendCombat();
		var cow = stationary(helper.spawn(EntityType.COW, new Vec3(24, 1, 32)));
		punch(owner, cow);
		helper.runAfterDelay(25, () -> {
			for (GuardEntity guard : List.of(held, zoned, suspended)) helper.assertTrue(guard.getTarget() == null, "restricted guard joined hunt");
			helper.assertTrue(cow.getHealth() == cow.getMaxHealth(), "restricted guard hurt cow");
			helper.succeed();
		});
	}

	@GameTest(structure = ARENA, environment = ENV, maxTicks = 580)
	public void creativePunchHuntsSixHusksAlongTransitiveChainAndSplitsGuards(GameTestHelper helper) {
		GuardTestSupport.floor(helper);
		ServerPlayer owner = GuardTestSupport.player(helper, GameType.CREATIVE, 24, 1, 10);
		List<GuardEntity> guards = squad(helper, owner, 6);
		List<LivingEntity> husks = new ArrayList<>();
		for (int i = 0; i < 6; i++) husks.add(stationary(helper.spawn(EntityType.HUSK, new Vec3(10 + 6 * i, 1, 27))));
		Set<UUID> simultaneously = new HashSet<>();
		boolean[] split = {false};
		punch(owner, husks.getFirst());
		contains(helper, guards.getFirst(), husks.toArray(LivingEntity[]::new));
		GuardTestSupport.onEachTick(helper, () -> {
			simultaneously.clear();
			for (GuardEntity guard : guards) if (guard.getTarget() != null) simultaneously.add(guard.getTarget().getUUID());
			if (simultaneously.size() >= 3) split[0] = true;
		});
		helper.succeedWhen(() -> {
			helper.assertTrue(split[0], "guards never split across at least three husks");
			helper.assertTrue(husks.stream().noneMatch(LivingEntity::isAlive), "not all six husks died");
		});
	}

	@GameTest(structure = ARENA, environment = ENV, maxTicks = 500)
	public void ownerDamageHuntsCowsAndLeavesDogAndVillagerUntouched(GameTestHelper helper) {
		GuardTestSupport.floor(helper);
		ServerPlayer owner = GuardTestSupport.survivalPlayer(helper, 24, 1, 10);
		List<GuardEntity> guards = squad(helper, owner, 3);
		List<LivingEntity> cows = new ArrayList<>();
		for (int i = 0; i < 3; i++) cows.add(stationary(helper.spawn(EntityType.COW, new Vec3(17 + 5 * i, 1, 27))));
		var dog = stationary(helper.spawn(EntityType.WOLF, new Vec3(23, 1, 27)));
		dog.tame(owner);
		var villager = stationary(helper.spawn(EntityType.VILLAGER, new Vec3(25, 1, 27)));
		float dogHealth = dog.getHealth(), villagerHealth = villager.getHealth();
		cows.getFirst().hurtServer(helper.getLevel(), helper.getLevel().damageSources().playerAttack(owner), 1);
		contains(helper, guards.getFirst(), cows.toArray(LivingEntity[]::new));
		helper.succeedWhen(() -> {
			helper.assertTrue(cows.stream().noneMatch(LivingEntity::isAlive), "cows not hunted");
			helper.assertTrue(dog.isAlive() && dog.getHealth() == dogHealth, "owned wolf hurt");
			helper.assertTrue(villager.isAlive() && villager.getHealth() == villagerHealth, "villager hurt");
		});
	}

	@GameTest(structure = ARENA, environment = ENV, maxTicks = 100)
	public void raidPunchIncludesDifferentRaiderTypesOutsideProximityRadius(GameTestHelper helper) {
		GuardTestSupport.floor(helper);
		ServerPlayer owner = GuardTestSupport.player(helper, GameType.CREATIVE, 24, 1, 10);
		List<GuardEntity> guards = squad(helper, owner, 3);
		var pillager = stationary(helper.spawn(EntityType.PILLAGER, new Vec3(10, 1, 27)));
		var vindicator = stationary(helper.spawn(EntityType.VINDICATOR, new Vec3(36, 1, 27)));
		var witch = stationary(helper.spawn(EntityType.WITCH, new Vec3(41, 1, 27)));
		Raid raid = new Raid(pillager.blockPosition(), helper.getLevel().getDifficulty());
		for (var raider : List.of(pillager, vindicator, witch)) raid.joinRaid(helper.getLevel(), 1, raider, null, true);
		punch(owner, pillager);
		contains(helper, guards.getFirst(), pillager, vindicator, witch);
		Set<UUID> assigned = new HashSet<>();
		GuardTestSupport.onEachTick(helper, () -> {
			for (GuardEntity guard : guards) if (guard.getTarget() != null) assigned.add(guard.getTarget().getUUID());
		});
		helper.succeedWhen(() -> helper.assertTrue(assigned.containsAll(List.of(pillager.getUUID(), vindicator.getUUID(), witch.getUUID())),
				"mixed raid members were not targeted"));
	}

	@GameTest(structure = ARENA, environment = ENV, maxTicks = 50)
	public void stayingGuardDoesNotJoinCowHunt(GameTestHelper helper) {
		GuardTestSupport.floor(helper);
		ServerPlayer owner = GuardTestSupport.survivalPlayer(helper, 24, 1, 10);
		GuardEntity guard = GuardTestSupport.ownedGuard(helper, 24, 1, 24, owner);
		guard.setStaying(true);
		var cow = stationary(helper.spawn(EntityType.COW, new Vec3(26, 1, 24)));
		punch(owner, cow);
		helper.runAfterDelay(30, () -> {
			helper.assertTrue(guard.getTarget() == null && !guard.hasActiveHunt(), "staying guard joined");
			helper.assertTrue(cow.getHealth() == cow.getMaxHealth(), "staying guard attacked cow");
			helper.succeed();
		});
	}

	@GameTest(structure = ARENA, environment = ENV, maxTicks = 50)
	public void playerPunchTargetsOnlyThatPlayerWithPvpEnabled(GameTestHelper helper) {
		GuardTestSupport.floor(helper);
		ServerPlayer owner = GuardTestSupport.survivalPlayer(helper, 24, 1, 10);
		ServerPlayer victim = GuardTestSupport.survivalPlayer(helper, 24, 1, 27);
		ServerPlayer bystander = GuardTestSupport.survivalPlayer(helper, 26, 1, 27);
		GuardEntity guard = GuardTestSupport.ownedGuard(helper, 24, 1, 20, owner);
		var cow = stationary(helper.spawn(EntityType.COW, new Vec3(28, 1, 27)));
		helper.assertTrue(helper.getLevel().isPvpAllowed(), "setup: PvP disabled");
		punch(owner, cow);
		punch(owner, victim);
		contains(helper, guard, victim);
		helper.assertValueEqual(GuardHuntManager.get(guard).targets().size(), 1, "player hunt size");
		helper.succeedWhen(() -> {
			helper.assertTrue(guard.getTarget() == victim, "punched player not targeted");
			helper.assertTrue(bystander.getHealth() == bystander.getMaxHealth(), "bystander hurt");
			helper.assertTrue(cow.getHealth() == cow.getMaxHealth(), "previous cow hunt leaked into PvP");
		});
	}

	@GameTest(structure = ARENA, environment = ENV, maxTicks = 730)
	public void huntMergesPunchesAndExpires600TicksAfterLastPunch(GameTestHelper helper) {
		GuardTestSupport.floor(helper);
		ServerPlayer owner = GuardTestSupport.survivalPlayer(helper, 24, 1, 10);
		GuardEntity guard = GuardTestSupport.ownedGuard(helper, 24, 1, 20, owner);
		var cow = stationary(helper.spawn(EntityType.COW, new Vec3(20, 1, 27)));
		var sheep = stationary(helper.spawn(EntityType.SHEEP, new Vec3(28, 1, 27)));
		for (Mob mob : List.of(cow, sheep)) {
			mob.getAttribute(Attributes.MAX_HEALTH).setBaseValue(1024);
			mob.setHealth(1024);
		}
		punch(owner, cow);
		helper.runAfterDelay(100, () -> punch(owner, sheep));
		helper.runAfterDelay(110, () -> contains(helper, guard, cow, sheep));
		helper.runAfterDelay(699, () -> helper.assertTrue(guard.hasActiveHunt(), "hunt expired before refreshed deadline"));
		helper.runAfterDelay(701, () -> helper.assertFalse(guard.hasActiveHunt(), "hunt survived 600 ticks after last punch"));
		helper.runAfterDelay(710, () -> {
			helper.assertTrue(guard.getTarget() == null, "expired hunt retained a neutral target");
			helper.succeed();
		});
	}

	@GameTest(structure = ARENA, environment = ENV, maxTicks = 50)
	public void strayWolfDoesNotPullInOwnedWolvesOrArmorStands(GameTestHelper helper) {
		GuardTestSupport.floor(helper);
		ServerPlayer owner = GuardTestSupport.survivalPlayer(helper, 24, 1, 10);
		GuardEntity guard = GuardTestSupport.ownedGuard(helper, 24, 1, 20, owner);
		var wild = stationary(helper.spawn(EntityType.WOLF, new Vec3(24, 1, 27)));
		var pet = stationary(helper.spawn(EntityType.WOLF, new Vec3(25, 1, 27)));
		pet.tame(owner);
		var stand = helper.spawn(EntityType.ARMOR_STAND, new Vec3(26, 1, 27));
		var team = helper.getLevel().getScoreboard().addPlayerTeam("hunt" + wild.getId());
		for (var entity : List.of(wild, pet, stand)) helper.getLevel().getScoreboard().addPlayerToTeam(entity.getScoreboardName(), team);
		GuardTestSupport.onFinish(helper, () -> helper.getLevel().getScoreboard().removePlayerTeam(team));
		punch(owner, wild);
		helper.assertValueEqual(GuardHuntManager.get(guard).targets().size(), 1, "protected expansion members included");
		helper.runAfterDelay(30, () -> {
			helper.assertTrue(pet.getHealth() == pet.getMaxHealth() && stand.isAlive(), "protected team member hurt");
			helper.succeed();
		});
	}

	@GameTest(structure = ARENA, environment = ENV, maxTicks = 50)
	public void teamAndVehicleRelationshipsIncludeDifferentTypes(GameTestHelper helper) {
		GuardTestSupport.floor(helper);
		ServerPlayer owner = GuardTestSupport.player(helper, GameType.CREATIVE, 24, 1, 10);
		GuardEntity guard = GuardTestSupport.ownedGuard(helper, 24, 1, 20, owner);
		var cow = stationary(helper.spawn(EntityType.COW, new Vec3(10, 1, 27)));
		var sheep = stationary(helper.spawn(EntityType.SHEEP, new Vec3(39, 1, 27)));
		var pig = stationary(helper.spawn(EntityType.PIG, new Vec3(10, 1, 27)));
		pig.startRiding(cow, true, true);
		var team = helper.getLevel().getScoreboard().addPlayerTeam("hunt" + cow.getId());
		for (var entity : List.of(cow, sheep)) helper.getLevel().getScoreboard().addPlayerToTeam(entity.getScoreboardName(), team);
		GuardTestSupport.onFinish(helper, () -> helper.getLevel().getScoreboard().removePlayerTeam(team));
		punch(owner, pig);
		contains(helper, guard, pig, cow);
		helper.runAfterDelay(6, () -> {
			punch(owner, cow);
		});
		helper.runAfterDelay(12, () -> {
			contains(helper, guard, pig, cow, sheep);
			helper.succeed();
		});
	}

	@GameTest(structure = ARENA, environment = ENV, maxTicks = 140)
	public void commanderLeavesHuntAloneAndTextOrdersCancelIt(GameTestHelper helper) {
		GuardTestSupport.floor(helper);
		ServerPlayer owner = GuardTestSupport.survivalPlayer(helper, 24, 1, 10);
		GuardEntity guard = GuardTestSupport.ownedGuard(helper, 24, 1, 20, owner);
		var cow = stationary(helper.spawn(EntityType.COW, new Vec3(24, 1, 30)));
		cow.getAttribute(Attributes.MAX_HEALTH).setBaseValue(1024);
		cow.setHealth(1024);
		stationary(helper.spawn(EntityType.HUSK, new Vec3(24, 1, 6)));
		punch(owner, cow);
		helper.runAfterDelay(40, () -> {
			helper.assertTrue(guard.getTarget() == cow, "commander reassigned hunter");
			helper.assertFalse(CommanderService.lastAssignments(owner.getUUID()).containsKey(guard.getUUID()), "commander assigned hunter");
			GuardTestSupport.runCommand(owner, "guards say as you were");
			helper.assertFalse(guard.hasActiveHunt(), "as you were retained hunt");
		});
		helper.runAfterDelay(65, () -> {
			punch(owner, cow);
			GuardTestSupport.runCommand(owner, "guards hold");
			helper.assertTrue(guard.isHoldingPosition() && GuardHuntManager.get(guard) == null, "hold retained hunt");
		});
		helper.runAfterDelay(90, () -> {
			GuardTestSupport.runCommand(owner, "guards follow");
			punch(owner, cow);
			GuardTestSupport.runCommand(owner, "guards say stay here");
			helper.assertTrue(guard.isStaying() && GuardHuntManager.get(guard) == null, "stay retained hunt");
			helper.succeed();
		});
	}
}
