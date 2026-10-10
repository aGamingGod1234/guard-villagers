package com.guardvillagers.gametest;

import static com.guardvillagers.gametest.GuardTestSupport.ARENA;
import static com.guardvillagers.gametest.GuardTestSupport.CENTER;
import static com.guardvillagers.gametest.GuardTestSupport.ENV;

import com.guardvillagers.GuardReputationManager;
import com.guardvillagers.entity.GuardEntity;
import java.util.UUID;
import net.fabricmc.fabric.api.event.player.AttackEntityCallback;
import net.fabricmc.fabric.api.gametest.v1.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.EntityType;
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

	@GameTest(structure = ARENA, environment = ENV, maxTicks = 40, required = false)
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

	/** Villager harm is recorded from the attack callback (fired by the real attack packet handler). */
	@GameTest(structure = ARENA, environment = ENV, maxTicks = 40)
	public void hittingVillagerLowersReputation(GameTestHelper helper) {
		GuardTestSupport.floor(helper);
		ServerPlayer player = GuardTestSupport.survivalPlayer(helper, CENTER + 0.5, 1, CENTER + 2.5);
		Villager villager = helper.spawn(EntityType.VILLAGER, new Vec3(CENTER + 0.5, 1, CENTER + 0.5));

		double before = rep(helper, player.getUUID());
		AttackEntityCallback.EVENT.invoker().interact(player, helper.getLevel(), InteractionHand.MAIN_HAND, villager, null);
		double after = rep(helper, player.getUUID());

		assertDelta(helper, before, after, -8 / 400.0D, "attacking a villager");
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
		double after = rep(helper, attacker.getUUID());

		assertDelta(helper, before, after, -6 / 400.0D, "hitting an unowned guard");
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
	public void ownedGuardKillingRaiderCountsAsRaidDefense(GameTestHelper helper) {
		GuardTestSupport.floor(helper);
		ServerLevel level = helper.getLevel();
		ServerPlayer owner = GuardTestSupport.survivalPlayer(helper, CENTER + 0.5, 1, CENTER + 4.5);
		GuardEntity guard = GuardTestSupport.ownedGuard(helper, CENTER + 0.5, 1, CENTER + 0.5, owner);
		Pillager pillager = helper.spawn(EntityType.PILLAGER, new Vec3(CENTER + 2.5, 1, CENTER + 0.5));

		double before = rep(helper, owner.getUUID());
		pillager.hurtServer(level, level.damageSources().mobAttack(guard), 1000.0F);
		double after = rep(helper, owner.getUUID());

		helper.assertFalse(pillager.isAlive(), "setup: pillager survived");
		// raider kill (+4) plus raid-defense bonus (+4) in legacy points
		assertDelta(helper, before, after, 8 / 400.0D, "owned guard killing a pillager");
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
	@GameTest(structure = ARENA, environment = ENV, maxTicks = 40, required = false)
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
	@GameTest(structure = ARENA, environment = ENV, maxTicks = 300, required = false)
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
