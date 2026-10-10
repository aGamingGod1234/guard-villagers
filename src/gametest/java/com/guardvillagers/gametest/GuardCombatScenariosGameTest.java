package com.guardvillagers.gametest;

import static com.guardvillagers.gametest.GuardTestSupport.ARENA;
import static com.guardvillagers.gametest.GuardTestSupport.CENTER;
import static com.guardvillagers.gametest.GuardTestSupport.ENV;

import com.guardvillagers.entity.GuardEntity;
import com.guardvillagers.entity.GuardRole;
import java.util.List;
import net.fabricmc.fabric.api.gametest.v1.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.animal.golem.IronGolem;
import net.minecraft.world.entity.animal.goat.Goat;
import net.minecraft.world.entity.monster.Creeper;
import net.minecraft.world.entity.monster.illager.Pillager;
import net.minecraft.world.entity.monster.skeleton.Skeleton;
import net.minecraft.world.entity.monster.zombie.Zombie;
import net.minecraft.world.entity.npc.villager.Villager;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;

/**
 * Combat behaviour: what guards fight and what they leave alone.
 * Mobs start 8-12 blocks from the guard, inside the 20-block hostile scan range.
 */
public class GuardCombatScenariosGameTest {
	private static void meleeVersus(GameTestHelper helper, Mob enemy) {
		GuardEntity guard = GuardTestSupport.swordsman(helper, CENTER - 5, 1, CENTER);
		helper.succeedWhen(() -> {
			helper.assertTrue(guard.isAlive(), "guard died");
			helper.assertFalse(enemy.isAlive(), enemy.getType().toShortString() + " still alive, health="
					+ enemy.getHealth() + ", guard target=" + guard.getTarget());
		});
	}

	@GameTest(structure = ARENA, environment = ENV, maxTicks = 600)
	public void swordsmanKillsZombie(GameTestHelper helper) {
		GuardTestSupport.floor(helper);
		Zombie zombie = GuardTestSupport.noSunBurn(helper.spawn(EntityType.ZOMBIE, new net.minecraft.world.phys.Vec3(CENTER + 4, 1, CENTER)));
		meleeVersus(helper, zombie);
	}

	@GameTest(structure = ARENA, environment = ENV, maxTicks = 600)
	public void swordsmanKillsSkeleton(GameTestHelper helper) {
		GuardTestSupport.floor(helper);
		Skeleton skeleton = GuardTestSupport.noSunBurn(helper.spawn(EntityType.SKELETON, new net.minecraft.world.phys.Vec3(CENTER + 4, 1, CENTER)));
		skeleton.setItemSlot(EquipmentSlot.MAINHAND, new ItemStack(Items.BOW));
		meleeVersus(helper, skeleton);
	}

	@GameTest(structure = ARENA, environment = ENV, maxTicks = 600)
	public void swordsmanKillsPillager(GameTestHelper helper) {
		GuardTestSupport.floor(helper);
		Pillager pillager = helper.spawn(EntityType.PILLAGER, new net.minecraft.world.phys.Vec3(CENTER + 4, 1, CENTER));
		pillager.setItemSlot(EquipmentSlot.MAINHAND, new ItemStack(Items.CROSSBOW));
		meleeVersus(helper, pillager);
	}

	/**
	 * A creeper is "handled" when it is gone and the guard survived; either the guard killed it or it
	 * blew up without killing the guard. Mob griefing is off in the test environment, so the floor stays.
	 */
	@GameTest(structure = ARENA, environment = ENV, maxTicks = 600)
	public void swordsmanHandlesCreeper(GameTestHelper helper) {
		GuardTestSupport.floor(helper);
		Creeper creeper = helper.spawn(EntityType.CREEPER, new net.minecraft.world.phys.Vec3(CENTER + 4, 1, CENTER));
		GuardEntity guard = GuardTestSupport.swordsman(helper, CENTER - 5, 1, CENTER);
		helper.succeedWhen(() -> {
			helper.assertTrue(guard.isAlive(), "guard died to the creeper");
			helper.assertFalse(creeper.isAlive(), "creeper still alive, guard target=" + guard.getTarget());
		});
	}

	@GameTest(structure = ARENA, environment = ENV, maxTicks = 800)
	public void bowmanKillsZombieAtRange(GameTestHelper helper) {
		GuardTestSupport.floor(helper);
		GuardEntity guard = GuardTestSupport.guard(helper, CENTER - 8, 1, CENTER, GuardRole.BOWMAN);
		Zombie zombie = GuardTestSupport.noSunBurn(helper.spawn(EntityType.ZOMBIE, new net.minecraft.world.phys.Vec3(CENTER + 6, 1, CENTER)));
		helper.succeedWhen(() -> {
			helper.assertTrue(guard.isAlive(), "bowman died");
			helper.assertFalse(zombie.isAlive(), "zombie still alive, health=" + zombie.getHealth()
					+ ", bowman target=" + guard.getTarget());
		});
	}

	/**
	 * Guard stands next to a villager, an iron golem, a guard with a different owner, an unowned guard
	 * and its own (survival) owner for 10 seconds. Nobody may be targeted or take damage.
	 */
	@GameTest(structure = ARENA, environment = ENV, maxTicks = 260)
	public void guardLeavesFriendliesAlone(GameTestHelper helper) {
		GuardTestSupport.floor(helper);
		ServerPlayer owner = GuardTestSupport.survivalPlayer(helper, CENTER + 0.5, 1, CENTER - 4.5);
		ServerPlayer otherOwner = GuardTestSupport.survivalPlayer(helper, CENTER + 6.5, 1, CENTER + 6.5);
		GuardEntity guard = GuardTestSupport.ownedGuard(helper, CENTER + 0.5, 1, CENTER + 0.5, owner);
		GuardEntity rivalGuard = GuardTestSupport.ownedGuard(helper, CENTER + 3.5, 1, CENTER + 0.5, otherOwner);
		GuardEntity villageGuard = GuardTestSupport.swordsman(helper, CENTER - 3.5, 1, CENTER + 0.5);
		Villager villager = helper.spawn(EntityType.VILLAGER, new net.minecraft.world.phys.Vec3(CENTER + 0.5, 1, CENTER + 3.5));
		IronGolem golem = helper.spawn(EntityType.IRON_GOLEM, new net.minecraft.world.phys.Vec3(CENTER + 0.5, 1, CENTER - 8.5));
		List<LivingEntity> friendlies = List.of(owner, otherOwner, rivalGuard, villageGuard, villager, golem);
		List<GuardEntity> guards = List.of(guard, rivalGuard, villageGuard);

		GuardTestSupport.onEachTick(helper, () -> {
			for (GuardEntity g : guards) {
				LivingEntity target = g.getTarget();
				if (target != null && (friendlies.contains(target) || guards.contains(target))) {
					helper.fail("guard " + g.getUUID() + " targeted friendly " + target.getType().toShortString());
				}
			}
		});
		helper.runAfterDelay(240, () -> {
			for (LivingEntity entity : friendlies) {
				helper.assertTrue(entity.isAlive() && entity.getHealth() >= entity.getMaxHealth(),
						entity.getType().toShortString() + " was hurt: " + entity.getHealth() + "/" + entity.getMaxHealth());
			}
			helper.assertTrue(guard.getHealth() >= guard.getMaxHealth(), "owned guard was hurt");
			helper.succeed();
		});
	}

	/**
	 * A goat is not a Monster, so the guard's hostile scan ignores it. The only reason to attack it is
	 * the owner-defense hook (AFTER_DAMAGE on the owner).
	 */
	@GameTest(structure = ARENA, environment = ENV, maxTicks = 400)
	public void guardDefendsOwnerWhenMobHitsOwner(GameTestHelper helper) {
		GuardTestSupport.floor(helper);
		ServerPlayer owner = GuardTestSupport.survivalPlayer(helper, CENTER + 0.5, 1, CENTER + 0.5);
		GuardEntity guard = GuardTestSupport.ownedGuard(helper, CENTER - 3.5, 1, CENTER + 0.5, owner);
		Goat goat = helper.spawn(EntityType.GOAT, new net.minecraft.world.phys.Vec3(CENTER + 4.5, 1, CENTER + 0.5));
		helper.runAfterDelay(20, () -> owner.hurtServer(helper.getLevel(), helper.getLevel().damageSources().mobAttack(goat), 2.0F));
		helper.succeedWhen(() -> {
			helper.assertTrue(owner.getHealth() < owner.getMaxHealth(), "setup: owner was not damaged");
			helper.assertTrue(goat.getLastHurtByMob() == guard || !goat.isAlive(),
					"guard never hit the goat that attacked its owner; guard target=" + guard.getTarget());
		});
	}

	@GameTest(structure = ARENA, environment = ENV, maxTicks = 600)
	public void guardSavesVillagerFromZombie(GameTestHelper helper) {
		GuardTestSupport.floor(helper);
		Villager villager = helper.spawn(EntityType.VILLAGER, new net.minecraft.world.phys.Vec3(CENTER + 6.5, 1, CENTER + 0.5));
		Zombie zombie = GuardTestSupport.noSunBurn(helper.spawn(EntityType.ZOMBIE, new net.minecraft.world.phys.Vec3(CENTER + 8.5, 1, CENTER + 0.5)));
		GuardEntity guard = GuardTestSupport.swordsman(helper, CENTER - 5.5, 1, CENTER + 0.5);
		helper.succeedWhen(() -> {
			helper.assertTrue(villager.isAlive() && !villager.isRemoved(), "villager died or converted");
			helper.assertTrue(guard.isAlive(), "guard died");
			helper.assertFalse(zombie.isAlive(), "zombie still alive, guard target=" + guard.getTarget());
		});
	}

	/** Owner-defense alerting alone (no melee needed): the guard must lock on to the goat that hit its owner. */
	@GameTest(structure = ARENA, environment = ENV, maxTicks = 200)
	public void guardTargetsMobThatHitOwner(GameTestHelper helper) {
		GuardTestSupport.floor(helper);
		ServerPlayer owner = GuardTestSupport.survivalPlayer(helper, CENTER + 0.5, 1, CENTER + 0.5);
		GuardEntity guard = GuardTestSupport.ownedGuard(helper, CENTER - 3.5, 1, CENTER + 0.5, owner);
		Goat goat = helper.spawn(EntityType.GOAT, new net.minecraft.world.phys.Vec3(CENTER + 4.5, 1, CENTER + 0.5));
		helper.runAfterDelay(20, () -> owner.hurtServer(helper.getLevel(), helper.getLevel().damageSources().mobAttack(goat), 2.0F));
		helper.succeedWhen(() -> helper.assertTrue(guard.getTarget() == goat, "guard target=" + guard.getTarget()));
	}

	/** Hostile scan alone: an unowned guard must lock on to a zombie that is going after a villager. */
	@GameTest(structure = ARENA, environment = ENV, maxTicks = 200)
	public void guardTargetsZombieAttackingVillager(GameTestHelper helper) {
		GuardTestSupport.floor(helper);
		helper.spawn(EntityType.VILLAGER, new net.minecraft.world.phys.Vec3(CENTER + 6.5, 1, CENTER + 0.5));
		Zombie zombie = GuardTestSupport.noSunBurn(helper.spawn(EntityType.ZOMBIE, new net.minecraft.world.phys.Vec3(CENTER + 8.5, 1, CENTER + 0.5)));
		GuardEntity guard = GuardTestSupport.swordsman(helper, CENTER - 5.5, 1, CENTER + 0.5);
		helper.succeedWhen(() -> helper.assertTrue(guard.getTarget() == zombie, "guard target=" + guard.getTarget()));
	}

	/**
	 * The guard is already fighting a (frozen) zombie when a goat hits its owner. The alert table in
	 * GuardAiController ranks OWNER_DAMAGED (90) above CURRENT_TARGET (84) and HOSTILE_SCAN (70), so the
	 * guard should switch to the goat.
	 */
	@GameTest(structure = ARENA, environment = ENV, maxTicks = 120)
	public void ownerDefenseOutranksRoutineHostile(GameTestHelper helper) {
		GuardTestSupport.floor(helper);
		ServerPlayer owner = GuardTestSupport.survivalPlayer(helper, CENTER + 0.5, 1, CENTER - 6.5);
		GuardEntity guard = GuardTestSupport.ownedGuard(helper, CENTER + 0.5, 1, CENTER + 0.5, owner);
		Zombie zombie = GuardTestSupport.noSunBurn(helper.spawn(EntityType.ZOMBIE, new net.minecraft.world.phys.Vec3(CENTER + 8.5, 1, CENTER + 0.5)));
		zombie.setNoAi(true);
		Goat goat = helper.spawn(EntityType.GOAT, new net.minecraft.world.phys.Vec3(CENTER + 0.5, 1, CENTER - 9.5));
		goat.setNoAi(true);
		helper.startSequence()
				.thenWaitUntil(() -> helper.assertTrue(guard.getTarget() == zombie, "setup: guard never targeted the zombie"))
				.thenExecute(() -> owner.hurtServer(helper.getLevel(), helper.getLevel().damageSources().mobAttack(goat), 2.0F))
				.thenExecuteAfter(30, () -> helper.assertTrue(guard.getTarget() == goat,
						"30 ticks after the owner was hit the guard still targets " + guard.getTarget()))
				.thenSucceed();
	}
}
