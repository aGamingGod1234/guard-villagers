package com.guardvillagers.gametest;

import static com.guardvillagers.gametest.GuardTestSupport.ARENA;
import static com.guardvillagers.gametest.GuardTestSupport.CENTER;
import static com.guardvillagers.gametest.GuardTestSupport.ENV;

import com.guardvillagers.GuardReputationManager;
import com.guardvillagers.entity.GuardEntity;
import java.util.UUID;
import com.mojang.serialization.JsonOps;
import net.fabricmc.fabric.api.gametest.v1.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.entity.projectile.arrow.Arrow;
import net.minecraft.world.entity.raid.Raid;
import net.minecraft.world.entity.monster.illager.Pillager;
import net.minecraft.world.entity.monster.zombie.Zombie;
import net.minecraft.world.entity.npc.villager.Villager;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.trading.ItemCost;
import net.minecraft.world.item.trading.MerchantOffer;
import net.minecraft.world.phys.Vec3;

/**
 * README "Reputation And Trust": trades raise it, harming villagers or guards lowers it, guard kills
 * of hostiles raise the owner's, and being killed by a guard resets it to 0.00. The deltas come from
 * GuardReputationManager (legacy points / 400). Each check reads before/after in the same tick so the
 * 36-second decay cannot interfere.
 */
public class GuardReputationGameTest {
	private static final double EPSILON = 1.0E-6D;

	private static double rep(GameTestHelper helper, UUID player) {
		return GuardReputationManager.getStoredReputation(helper.getLevel(), player);
	}

	private static void assertDelta(GameTestHelper helper, double before, double after, double expected, String what) {
		helper.assertTrue(Math.abs((after - before) - expected) < EPSILON,
				what + ": expected delta " + expected + " but reputation went " + before + " -> " + after);
	}

	@GameTest(structure = ARENA, environment = ENV, maxTicks = 40)
	public void completedTradeRaisesReputationWithCooldown(GameTestHelper helper) {
		GuardTestSupport.floor(helper);
		ServerPlayer player = GuardTestSupport.survivalPlayer(helper, CENTER + 0.5, 1, CENTER + 2.5);
		Villager villager = helper.spawn(EntityType.VILLAGER, new Vec3(CENTER + 0.5, 1, CENTER + 0.5));
		MerchantOffer offer = new MerchantOffer(new ItemCost(Items.EMERALD, 1), new ItemStack(Items.BREAD), 12, 2, 0.05F);
		villager.setTradingPlayer(player);

		double before = rep(helper, player.getUUID());
		villager.notifyTrade(offer);
		double afterFirst = rep(helper, player.getUUID());
		villager.notifyTrade(offer);
		double afterSecond = rep(helper, player.getUUID());
		villager.setTradingPlayer(null);

		assertDelta(helper, before, afterFirst, 1 / 400.0D, "first trade");
		assertDelta(helper, afterFirst, afterSecond, 0.0D, "second trade inside the 10s per-villager cooldown");
		helper.succeed();
	}

	/** A real melee hit must charge once, while a rejected attack must not charge. */
	@GameTest(structure = ARENA, environment = ENV, maxTicks = 40)
	public void hittingVillagerLowersReputation(GameTestHelper helper) {
		GuardTestSupport.floor(helper);
		ServerPlayer player = GuardTestSupport.survivalPlayer(helper, CENTER + 0.5, 1, CENTER + 2.5);
		Villager villager = helper.spawn(EntityType.VILLAGER, new Vec3(CENTER + 0.5, 1, CENTER + 0.5));

		double before = rep(helper, player.getUUID());
		player.attack(villager);
		double after = rep(helper, player.getUUID());

		helper.assertTrue(villager.getHealth() < villager.getMaxHealth(), "setup: melee hit dealt no damage");
		assertDelta(helper, before, after, -8 / 400.0D, "attacking a villager");
		villager.setInvulnerable(true);
		player.attack(villager);
		assertDelta(helper, after, rep(helper, player.getUUID()), 0.0D, "rejected melee hit");
		helper.succeed();
	}

	@GameTest(structure = ARENA, environment = ENV, maxTicks = 40)
	public void projectileAndLethalVillageHarmChargeOnce(GameTestHelper helper) {
		GuardTestSupport.floor(helper);
		ServerLevel level = helper.getLevel();
		ServerPlayer player = GuardTestSupport.survivalPlayer(helper, CENTER + 0.5, 1, CENTER + 2.5);
		Villager villager = helper.spawn(EntityType.VILLAGER, new Vec3(CENTER + 0.5, 1, CENTER + 0.5));
		Arrow arrow = new Arrow(level, player, new ItemStack(Items.ARROW), null);
		double before = rep(helper, player.getUUID());
		helper.assertTrue(villager.hurtServer(level, level.damageSources().arrow(arrow, player), 2.0F), "arrow hit rejected");
		assertDelta(helper, before, rep(helper, player.getUUID()), -0.02D, "nonlethal arrow");
		before = rep(helper, player.getUUID());
		villager.hurtServer(level, level.damageSources().arrow(arrow, player), 1000.0F);
		helper.assertTrue(villager.isDeadOrDying(), "lethal arrow did not kill villager");
		assertDelta(helper, before, rep(helper, player.getUUID()), -0.02D, "lethal arrow");
		var golem = helper.spawn(EntityType.IRON_GOLEM, new Vec3(CENTER + 4.5, 1, CENTER + 0.5));
		before = rep(helper, player.getUUID());
		golem.hurtServer(level, level.damageSources().arrow(arrow, player), 1000.0F);
		assertDelta(helper, before, rep(helper, player.getUUID()), -0.02D, "lethal arrow against golem");
		helper.succeed();
	}

	@GameTest(structure = ARENA, environment = ENV, maxTicks = 80)
	public void forgivenArmoredHitDoesNotStartSquadFight(GameTestHelper helper) {
		GuardTestSupport.floor(helper);
		ServerLevel level = helper.getLevel();
		ServerPlayer player = GuardTestSupport.survivalPlayer(helper, CENTER + 0.5, 1, CENTER + 4.5);
		GuardEntity personal = GuardTestSupport.ownedGuard(helper, CENTER + 0.5, 1, CENTER + 2.5, player);
		personal.setFollowOverride(true);
		GuardEntity victim = GuardTestSupport.swordsman(helper, CENTER + 0.5, 1, CENTER + 0.5);
		victim.setItemSlot(EquipmentSlot.HEAD, new ItemStack(Items.DIAMOND_HELMET));
		victim.setItemSlot(EquipmentSlot.CHEST, new ItemStack(Items.DIAMOND_CHESTPLATE));
		victim.setItemSlot(EquipmentSlot.LEGS, new ItemStack(Items.DIAMOND_LEGGINGS));
		victim.setItemSlot(EquipmentSlot.FEET, new ItemStack(Items.DIAMOND_BOOTS));
		player.getAttribute(Attributes.ATTACK_DAMAGE).setBaseValue(6.0D);
		// Wait for equipment attributes and a fully charged attack, then exercise both Fabric hooks.
		helper.runAfterDelay(12, () -> {
			float health = victim.getHealth();
			double before = rep(helper, player.getUUID());
			player.attack(victim);
			float dealt = health - victim.getHealth();
			helper.assertTrue(dealt > 0 && dealt <= 4, "setup: armor should reduce six raw damage to at most four, got " + dealt);
			assertDelta(helper, before, rep(helper, player.getUUID()), 0.0D, "mitigated first hit");
		});
		GuardTestSupport.onEachTick(helper, () -> {
			helper.assertTrue(personal.getTarget() == null, "owner's guard attacked after a forgiven hit");
			helper.assertTrue(victim.getTarget() == null, "victim retaliated after a forgiven hit");
		});
		helper.runAfterDelay(35, helper::succeed);
	}

	@GameTest(structure = ARENA, environment = ENV, maxTicks = 60)
	public void repeatedHitAlertsBothSquads(GameTestHelper helper) {
		GuardTestSupport.floor(helper);
		ServerLevel level = helper.getLevel();
		ServerPlayer player = GuardTestSupport.survivalPlayer(helper, CENTER + 0.5, 1, CENTER + 6.5);
		GuardEntity personal = GuardTestSupport.ownedGuard(helper, CENTER + 0.5, 1, CENTER + 4.5, player);
		personal.setFollowOverride(true);
		GuardEntity victim = GuardTestSupport.swordsman(helper, CENTER + 0.5, 1, CENTER + 0.5);
		double before = rep(helper, player.getUUID());
		victim.hurtServer(level, level.damageSources().playerAttack(player), 1.0F);
		assertDelta(helper, before, rep(helper, player.getUUID()), 0.0D, "first hit");
		victim.hurtServer(level, level.damageSources().playerAttack(player), 3.0F);
		assertDelta(helper, before, rep(helper, player.getUUID()), -0.015D, "second hit charged exactly once");
		helper.succeedWhen(() -> {
			helper.assertTrue(personal.getTarget() == victim, "owner's guard did not receive the non-forgiven attack");
			helper.assertTrue(victim.getTarget() != null, "victim did not retaliate");
		});
	}

	@GameTest(structure = ARENA, environment = ENV, maxTicks = 40)
	public void cooldownReductionCountsTowardGraceThreshold(GameTestHelper helper) {
		GuardTestSupport.floor(helper);
		ServerLevel level = helper.getLevel();
		ServerPlayer player = GuardTestSupport.survivalPlayer(helper, CENTER + 0.5, 1, CENTER + 2.5);
		GuardEntity victim = GuardTestSupport.swordsman(helper, CENTER + 0.5, 1, CENTER + 0.5);
		victim.hurtServer(level, level.damageSources().generic(), 4.0F);
		float health = victim.getHealth();
		double before = rep(helper, player.getUUID());
		helper.assertTrue(victim.hurtServer(level, level.damageSources().playerAttack(player), 6.0F), "second hit rejected");
		helper.assertTrue(health - victim.getHealth() > 0 && health - victim.getHealth() <= 4, "setup: cooldown did not reduce damage");
		assertDelta(helper, before, rep(helper, player.getUUID()), 0.0D, "cooldown-reduced first player hit");
		helper.succeed();
	}

	@GameTest(structure = ARENA, environment = ENV, maxTicks = 40)
	public void absorbedDamageCountsTowardGraceThreshold(GameTestHelper helper) {
		GuardTestSupport.floor(helper);
		ServerLevel level = helper.getLevel();
		ServerPlayer player = GuardTestSupport.survivalPlayer(helper, CENTER + 0.5, 1, CENTER + 2.5);
		GuardEntity victim = GuardTestSupport.swordsman(helper, CENTER + 0.5, 1, CENTER + 0.5);
		victim.getAttribute(Attributes.MAX_ABSORPTION).setBaseValue(20.0D);
		victim.setAbsorptionAmount(20.0F);
		float health = victim.getHealth();
		double before = rep(helper, player.getUUID());
		victim.hurtServer(level, level.damageSources().playerAttack(player), 6.0F);
		helper.assertValueEqual(victim.getHealth(), health, "health protected by absorption");
		helper.assertTrue(20.0F - victim.getAbsorptionAmount() > 4.0F, "setup: absorbed damage exceeds threshold");
		assertDelta(helper, before, rep(helper, player.getUUID()), -0.015D, "heavy absorbed hit");
		helper.succeed();
	}

	@GameTest(structure = ARENA, environment = ENV, maxTicks = 40)
	public void passiveAndPlayerKillsDoNotRewardOwner(GameTestHelper helper) {
		GuardTestSupport.floor(helper);
		ServerLevel level = helper.getLevel();
		ServerPlayer owner = GuardTestSupport.survivalPlayer(helper, CENTER + 0.5, 1, CENTER + 4.5);
		GuardEntity guard = GuardTestSupport.ownedGuard(helper, CENTER + 0.5, 1, CENTER + 0.5, owner);
		var chicken = helper.spawn(EntityType.CHICKEN, new Vec3(CENTER + 2.5, 1, CENTER + 0.5));
		double before = rep(helper, owner.getUUID());
		chicken.hurtServer(level, level.damageSources().mobAttack(guard), 1000.0F);
		helper.assertTrue(chicken.isDeadOrDying(), "setup: chicken survived");
		assertDelta(helper, before, rep(helper, owner.getUUID()), 0.0D, "passive kill");
		ServerPlayer victim = GuardTestSupport.survivalPlayer(helper, CENTER + 4.5, 1, CENTER + 0.5);
		victim.hurtServer(level, level.damageSources().mobAttack(guard), 1000.0F);
		helper.assertTrue(victim.isDeadOrDying(), "setup: player survived");
		assertDelta(helper, before, rep(helper, owner.getUUID()), 0.0D, "player kill");
		// Phantom is an Enemy but not a Monster; legitimate hostile rewards must be preserved.
		var phantom = helper.spawn(EntityType.PHANTOM, new Vec3(CENTER + 6.5, 1, CENTER + 0.5));
		phantom.hurtServer(level, level.damageSources().mobAttack(guard), 1000.0F);
		helper.assertTrue(phantom.isDeadOrDying(), "setup: phantom survived");
		assertDelta(helper, before, rep(helper, owner.getUUID()), 0.005D, "phantom kill");
		helper.succeed();
	}

	@GameTest(structure = ARENA, environment = ENV, maxTicks = 40)
	public void raidDefenseRequiresOngoingBattle(GameTestHelper helper) {
		GuardTestSupport.floor(helper);
		ServerLevel level = helper.getLevel();
		UUID owner = UUID.randomUUID();
		Pillager raider = helper.spawn(EntityType.PILLAGER, new Vec3(CENTER + 0.5, 1, CENTER + 0.5));
		for (String status : new String[] {"ongoing", "victory", "loss", "stopped"}) {
			var saved = Raid.MAP_CODEC.codec().encodeStart(JsonOps.INSTANCE, new Raid(raider.blockPosition(), level.getDifficulty()))
					.getOrThrow().getAsJsonObject();
			saved.addProperty("status", status);
			Raid raid = Raid.MAP_CODEC.codec().parse(JsonOps.INSTANCE, saved).getOrThrow();
			raider.setCurrentRaid(raid);
			double before = rep(helper, owner);
			GuardReputationManager.recordHostileKill(level, owner, raider);
			GuardReputationManager.recordRaidDefense(level, owner);
			assertDelta(helper, before, rep(helper, owner), status.equals("ongoing") ? 0.02D : 0.01D, status + " raid kill");
			before = rep(helper, owner);
			GuardReputationManager.recordRaidDefense(level, owner, raider);
			assertDelta(helper, before, rep(helper, owner), status.equals("ongoing") ? 0.01D : 0.0D, status + " explicit bonus");
		}
		raider.setCurrentRaid(null);
		helper.succeed();
	}

	@GameTest(structure = ARENA, environment = ENV, maxTicks = 40)
	public void hittingSomeoneElsesGuardLowersReputation(GameTestHelper helper) {
		GuardTestSupport.floor(helper);
		ServerLevel level = helper.getLevel();
		ServerPlayer attacker = GuardTestSupport.survivalPlayer(helper, CENTER + 0.5, 1, CENTER + 2.5);
		GuardEntity guard = GuardTestSupport.swordsman(helper, CENTER + 0.5, 1, CENTER + 0.5);

		double before = rep(helper, attacker.getUUID());
		guard.hurtServer(level, level.damageSources().playerAttack(attacker), 1.0F);
		double afterFirst = rep(helper, attacker.getUUID());
		// A larger second hit gets through the invulnerability window and lands inside the 5 s grace window.
		guard.hurtServer(level, level.damageSources().playerAttack(attacker), 3.0F);
		double afterSecond = rep(helper, attacker.getUUID());

		assertDelta(helper, before, afterFirst, 0.0D, "first light hit on an unowned guard is forgiven");
		assertDelta(helper, afterFirst, afterSecond, -6 / 400.0D, "second hit within the grace window");
		helper.succeed();
	}

	@GameTest(structure = ARENA, environment = ENV, maxTicks = 40)
	public void hittingYourOwnGuardCostsNoReputation(GameTestHelper helper) {
		GuardTestSupport.floor(helper);
		ServerLevel level = helper.getLevel();
		ServerPlayer owner = GuardTestSupport.survivalPlayer(helper, CENTER + 0.5, 1, CENTER + 2.5);
		GuardEntity guard = GuardTestSupport.ownedGuard(helper, CENTER + 0.5, 1, CENTER + 0.5, owner);

		double before = rep(helper, owner.getUUID());
		guard.hurtServer(level, level.damageSources().playerAttack(owner), 1.0F);
		double after = rep(helper, owner.getUUID());

		assertDelta(helper, before, after, 0.0D, "owner hitting own guard");
		helper.assertTrue(guard.getTarget() == null, "guard turned on its owner");
		helper.succeed();
	}

	@GameTest(structure = ARENA, environment = ENV, maxTicks = 40)
	public void ownedGuardKillingHostileRaisesOwnerReputation(GameTestHelper helper) {
		GuardTestSupport.floor(helper);
		ServerLevel level = helper.getLevel();
		ServerPlayer owner = GuardTestSupport.survivalPlayer(helper, CENTER + 0.5, 1, CENTER + 4.5);
		GuardEntity guard = GuardTestSupport.ownedGuard(helper, CENTER + 0.5, 1, CENTER + 0.5, owner);
		Zombie zombie = GuardTestSupport.noSunBurn(helper.spawn(EntityType.ZOMBIE, new Vec3(CENTER + 2.5, 1, CENTER + 0.5)));
		int xpBefore = guard.getExperience();

		double before = rep(helper, owner.getUUID());
		zombie.hurtServer(level, level.damageSources().mobAttack(guard), 1000.0F);
		double after = rep(helper, owner.getUUID());

		helper.assertFalse(zombie.isAlive(), "setup: zombie survived");
		assertDelta(helper, before, after, 2 / 400.0D, "owned guard killing a zombie");
		helper.assertValueEqual(guard.getExperience() - xpBefore, 20, "guard XP for a monster kill");
		helper.succeed();
	}

	@GameTest(structure = ARENA, environment = ENV, maxTicks = 40)
	public void ownedGuardKillingLonePillagerIsNotRaidDefense(GameTestHelper helper) {
		GuardTestSupport.floor(helper);
		ServerLevel level = helper.getLevel();
		ServerPlayer owner = GuardTestSupport.survivalPlayer(helper, CENTER + 0.5, 1, CENTER + 4.5);
		GuardEntity guard = GuardTestSupport.ownedGuard(helper, CENTER + 0.5, 1, CENTER + 0.5, owner);
		Pillager pillager = helper.spawn(EntityType.PILLAGER, new Vec3(CENTER + 2.5, 1, CENTER + 0.5));

		double before = rep(helper, owner.getUUID());
		pillager.hurtServer(level, level.damageSources().mobAttack(guard), 1000.0F);
		double after = rep(helper, owner.getUUID());

		helper.assertFalse(pillager.isAlive(), "setup: pillager survived");
		// No raid is running: a raider kill (+4 legacy points) without the raid-defense bonus.
		assertDelta(helper, before, after, 4 / 400.0D, "owned guard killing a pillager outside a raid");
		helper.succeed();
	}

	@GameTest(structure = ARENA, environment = ENV, maxTicks = 40)
	public void beingKilledByGuardResetsReputation(GameTestHelper helper) {
		GuardTestSupport.floor(helper);
		ServerLevel level = helper.getLevel();
		ServerPlayer victim = GuardTestSupport.survivalPlayer(helper, CENTER + 0.5, 1, CENTER + 2.5);
		GuardEntity guard = GuardTestSupport.swordsman(helper, CENTER + 0.5, 1, CENTER + 0.5);
		GuardReputationManager.setReputation(level, victim.getUUID(), 0.80D);

		victim.hurtServer(level, level.damageSources().mobAttack(guard), 1000.0F);

		helper.assertTrue(victim.isDeadOrDying(), "setup: player survived");
		helper.assertValueEqual(rep(helper, victim.getUUID()), 0.0D, "reputation after being killed by a guard");
		helper.succeed();
	}

	@GameTest(structure = ARENA, environment = ENV, maxTicks = 40)
	public void reputationCommandIsOperatorOnly(GameTestHelper helper) {
		GuardTestSupport.floor(helper);
		ServerPlayer player = GuardTestSupport.survivalPlayer(helper, CENTER + 0.5, 1, CENTER + 0.5);
		String name = player.getGameProfile().name();
		GuardReputationManager.setReputation(helper.getLevel(), player.getUUID(), 0.50D);

		GuardTestSupport.runCommand(player, "guards reputation " + name + " 0.95", false);
		helper.assertValueEqual(rep(helper, player.getUUID()), 0.50D, "reputation after non-operator command");

		helper.assertValueEqual(GuardTestSupport.runCommand(player, "guards reputation " + name + " 0.75", true), 1,
				"operator command result");
		helper.assertValueEqual(rep(helper, player.getUUID()), 0.75D, "reputation after operator set");

		GuardTestSupport.runCommand(player, "guards reputation " + name + " 1.5", true);
		GuardTestSupport.runCommand(player, "guards reputation " + name + " 0.333", true);
		helper.assertValueEqual(rep(helper, player.getUUID()), 0.75D, "reputation after invalid values");
		helper.succeed();
	}

	/** README: "/guards reputation <value> - Sets your own reputation." */
	@GameTest(structure = ARENA, environment = ENV, maxTicks = 40)
	public void reputationCommandSelfFormSetsOwnValue(GameTestHelper helper) {
		GuardTestSupport.floor(helper);
		ServerPlayer player = GuardTestSupport.survivalPlayer(helper, CENTER + 0.5, 1, CENTER + 0.5);
		GuardReputationManager.setReputation(helper.getLevel(), player.getUUID(), 0.50D);

		int result = GuardTestSupport.runCommand(player, "guards reputation 0.75", true);

		helper.assertTrue(result == 1 && Math.abs(rep(helper, player.getUUID()) - 0.75D) < EPSILON,
				"'/guards reputation 0.75' as operator returned " + result + ", reputation now " + rep(helper, player.getUUID()));
		helper.succeed();
	}

	@GameTest(structure = ARENA, environment = ENV, maxTicks = 40)
	public void decayStepMatchesReadme(GameTestHelper helper) {
		GuardTestSupport.floor(helper);
		UUID player = UUID.randomUUID();
		GuardReputationManager.setReputation(helper.getLevel(), player, 0.70D);
		GuardReputationManager.getState(helper.getLevel().getServer()).decayAll(GuardReputationManager.DECAY_STEP);
		helper.assertTrue(Math.abs(rep(helper, player) - 0.69D) < EPSILON, "one decay step: " + rep(helper, player));
		helper.assertValueEqual(GuardReputationManager.DECAY_INTERVAL_TICKS, 36 * 20, "decay interval ticks");
		helper.succeed();
	}

	/**
	 * A player who has ever had a reputation change sits on the tracked list and decays 0.01 every 36s.
	 * Starting from the default 0.50, one decay step puts them below the 0.50 trust line. Their own,
	 * already-hired guards should still follow them; the README only gates hiring and claiming on trust.
	 */
	@GameTest(structure = ARENA, environment = ENV, maxTicks = 300)
	public void ownedGuardKeepsFollowingAfterOneDecayStep(GameTestHelper helper) {
		GuardTestSupport.floor(helper);
		ServerLevel level = helper.getLevel();
		ServerPlayer owner = GuardTestSupport.survivalPlayer(helper, CENTER - 14.5, 1, CENTER + 0.5);
		GuardEntity guard = GuardTestSupport.ownedGuard(helper, CENTER + 8.5, 1, CENTER + 0.5, owner);
		guard.setFollowOverride(true);
		GuardReputationManager.setReputation(level, owner.getUUID(), 0.50D);
		GuardReputationManager.getState(level.getServer()).decayAll(GuardReputationManager.DECAY_STEP);

		helper.succeedWhen(() -> helper.assertTrue(guard.distanceTo(owner) <= 6.0F,
				"guard stopped following: " + guard.distanceTo(owner) + " blocks away, owner reputation "
						+ rep(helper, owner.getUUID())));
	}
}
