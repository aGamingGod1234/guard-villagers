package com.guardvillagers.gametest;

import static com.guardvillagers.gametest.GuardTestSupport.ARENA;
import static com.guardvillagers.gametest.GuardTestSupport.CENTER;
import static com.guardvillagers.gametest.GuardTestSupport.ENV;

import com.guardvillagers.GuardReputationManager;
import com.guardvillagers.GuardVillagersMod;
import com.guardvillagers.entity.GuardEntity;
import com.guardvillagers.entity.GuardRole;
import java.util.ArrayList;
import java.util.List;
import net.fabricmc.fabric.api.gametest.v1.GameTest;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.util.ProblemReporter;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.monster.Creeper;
import net.minecraft.world.entity.monster.zombie.Husk;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.storage.TagValueInput;
import net.minecraft.world.level.storage.TagValueOutput;
import net.minecraft.world.phys.Vec3;

/** Real entity-tick regressions for the lifecycle and combat review findings. */
public class GuardCombatReviewGameTest {
	@GameTest(structure = ARENA, environment = ENV, maxTicks = 40)
	public void missingWeaponPreservesLoadedEquipment(GameTestHelper helper) {
		GuardTestSupport.floor(helper);
		GuardEntity guard = helper.spawn(GuardVillagersMod.GUARD_ENTITY_TYPE, new Vec3(CENTER + 0.5, 1, CENTER + 0.5));
		guard.setRole(GuardRole.SWORDSMAN);
		guard.setItemSlot(EquipmentSlot.MAINHAND, ItemStack.EMPTY);
		ItemStack helmet = new ItemStack(Items.DIAMOND_HELMET);
		helmet.setDamageValue(37);
		guard.setItemSlot(EquipmentSlot.HEAD, helmet);
		guard.setDropChance(EquipmentSlot.HEAD, 1.0F);
		guard.setItemSlot(EquipmentSlot.OFFHAND, new ItemStack(Items.SHIELD));
		guard.setDropChance(EquipmentSlot.OFFHAND, 0.7F);
		TagValueOutput output = TagValueOutput.createWithContext(ProblemReporter.DISCARDING, helper.getLevel().registryAccess());
		guard.saveWithoutId(output);
		CompoundTag tag = output.buildResult();
		tag.putBoolean("PlayerHelmet", true);
		guard.load(TagValueInput.create(ProblemReporter.DISCARDING, helper.getLevel().registryAccess(), tag));
		helper.runAfterDelay(5, () -> {
			helper.assertTrue(guard.getMainHandItem().is(Items.STONE_SWORD), "missing sword was not filled");
			helper.assertTrue(guard.getItemBySlot(EquipmentSlot.HEAD).is(Items.DIAMOND_HELMET), "loaded helmet was replaced");
			helper.assertValueEqual(guard.getItemBySlot(EquipmentSlot.HEAD).getDamageValue(), 37, "helmet damage");
			helper.assertValueEqual(guard.getDropChances().byEquipment(EquipmentSlot.HEAD), 1.0F, "helmet drop chance");
			helper.assertTrue(guard.getOffhandItem().is(Items.SHIELD), "loaded shield was removed");
			helper.assertValueEqual(guard.getDropChances().byEquipment(EquipmentSlot.OFFHAND), 0.7F, "shield drop chance");
			TagValueOutput saved = TagValueOutput.createWithContext(ProblemReporter.DISCARDING, helper.getLevel().registryAccess());
			guard.saveWithoutId(saved);
			helper.assertTrue(saved.buildResult().getBooleanOr("PlayerHelmet", false), "player armor flag was cleared");
			helper.succeed();
		});
	}

	@GameTest(structure = ARENA, environment = ENV, maxTicks = 100)
	public void ownerDefenseOverridesLiveSelfDefense(GameTestHelper helper) {
		GuardTestSupport.floor(helper);
		ServerPlayer owner = GuardTestSupport.survivalPlayer(helper, CENTER, 1, CENTER - 8);
		GuardEntity guard = GuardTestSupport.ownedGuard(helper, CENTER, 1, CENTER, owner);
		Husk husk = helper.spawn(EntityType.HUSK, new Vec3(CENTER + 2, 1, CENTER));
		husk.setNoAi(true);
		var goat = helper.spawn(EntityType.GOAT, new Vec3(CENTER, 1, CENTER + 8));
		goat.setNoAi(true);
		helper.startSequence()
				.thenWaitUntil(() -> helper.assertTrue(guard.getTarget() == husk, "setup: current hostile"))
				.thenExecute(() -> {
					husk.setPos(guard.getX() + 1, guard.getY(), guard.getZ());
					guard.hurtServer(helper.getLevel(), helper.getLevel().damageSources().mobAttack(husk), 2);
					owner.hurtServer(helper.getLevel(), helper.getLevel().damageSources().mobAttack(goat), 2);
				})
				.thenExecuteAfter(3, () -> helper.assertTrue(guard.getTarget() == goat, "live direct-damage alert overrode owner defense"))
				.thenSucceed();
	}

	@GameTest(structure = ARENA, environment = ENV, maxTicks = 80)
	public void scansAdvancePastEightOccludedHostiles(GameTestHelper helper) {
		GuardTestSupport.floor(helper);
		GuardEntity guard = GuardTestSupport.guard(helper, CENTER + 0.5, 1, CENTER + 0.5, GuardRole.BOWMAN);
		guard.setHoldPosition(true);
		GuardTestSupport.fill(helper, CENTER + 2, 1, CENTER - 2, CENTER + 6, 4, CENTER + 2, Blocks.STONE);
		GuardTestSupport.fill(helper, CENTER + 3, 1, CENTER - 1, CENTER + 5, 3, CENTER + 1, Blocks.AIR);
		for (int i = 0; i < 8; i++) {
			Husk hidden = helper.spawn(EntityType.HUSK, new Vec3(CENTER + 4.5, 1, CENTER + 0.5));
			hidden.setNoAi(true);
			helper.assertFalse(guard.getSensing().hasLineOfSight(hidden), "setup: hidden hostile has sight");
		}
		Husk visible = helper.spawn(EntityType.HUSK, new Vec3(CENTER - 10.5, 1, CENTER + 0.5));
		visible.setNoAi(true);
		helper.assertTrue(guard.getSensing().hasLineOfSight(visible), "setup: ninth hostile not visible");
		helper.succeedWhen(() -> helper.assertTrue(guard.getTarget() == visible, "visible ninth hostile starved"));
	}

	@GameTest(structure = ARENA, environment = ENV, maxTicks = 120)
	public void pendingSelfDefenseAndAlliedPeelsShareCap(GameTestHelper helper) {
		GuardTestSupport.floor(helper);
		List<GuardEntity> guards = new ArrayList<>();
		for (int i = 0; i < 8; i++) {
			guards.add(GuardTestSupport.swordsman(helper, CENTER - 7 + i * 2, 1, CENTER));
		}
		var primary = helper.spawn(EntityType.GOAT, new Vec3(CENTER, 1, CENTER + 10));
		primary.setNoAi(true);
		var attacker = helper.spawn(EntityType.GOAT, new Vec3(CENTER - 6, 1, CENTER));
		attacker.setNoAi(true);
		for (GuardEntity guard : guards) {
			guard.setMainTarget(primary);
		}
		helper.startSequence()
				.thenWaitUntil(() -> helper.assertTrue(guards.stream().allMatch(g -> primary.getUUID().equals(g.getMainTargetUuid())), "setup: main targets"))
				.thenExecute(() -> {
					for (GuardEntity guard : guards) {
						guard.setNoAi(true);
					}
					attacker.setPos(guards.getFirst().getX() + 1, guards.getFirst().getY(), guards.getFirst().getZ());
					guards.getFirst().hurtServer(helper.getLevel(), helper.getLevel().damageSources().mobAttack(attacker), 1);
					// A second callback before any controller selects its queued alerts must see those reservations.
					attacker.setPos(guards.getLast().getX() + 1, guards.getLast().getY(), guards.getLast().getZ());
					guards.getLast().hurtServer(helper.getLevel(), helper.getLevel().damageSources().mobAttack(attacker), 1);
					for (GuardEntity guard : guards) {
						guard.setNoAi(false);
					}
				})
				.thenExecuteAfter(2, () -> {
					long peeling = guards.stream().filter(g -> g.getUrgentTargetUuid() != null).count();
					helper.assertValueEqual(peeling, 2L, "peel cap including pending self-defense");
					for (GuardEntity guard : guards) {
						guard.clearCombatTarget();
					}
					GuardEntity held = guards.getFirst();
					held.setHoldPosition(true);
					attacker.setPos(held.getX() + 10, held.getY(), held.getZ());
					helper.assertFalse(held.receiveUrgentPeel(attacker, helper.getLevel()), "out-of-reach peel accepted");
				})
				.thenSucceed();
	}

	@GameTest(structure = ARENA, environment = ENV, maxTicks = 160)
	public void heldGuardsOutsideReachLeaveMobileFrontlineSlot(GameTestHelper helper) {
		GuardTestSupport.floor(helper);
		Husk target = helper.spawn(EntityType.HUSK, new Vec3(CENTER + 0.5, 1, CENTER + 0.5));
		target.setNoAi(true);
		for (int[] offset : new int[][] {{2, 0}, {-2, 0}, {0, 2}, {0, -2}}) {
			GuardEntity held = GuardTestSupport.swordsman(helper, CENTER + 0.5 + offset[0], 1, CENTER + 0.5 + offset[1]);
			held.setHoldPosition(true);
			helper.assertFalse(held.isWithinMeleeAttackRange(target), "setup: held guard in reach");
		}
		GuardEntity mobile = GuardTestSupport.swordsman(helper, CENTER + 3, 1, CENTER + 3);
		helper.succeedWhen(() -> helper.assertTrue(target.getLastHurtByMob() == mobile, "mobile swordsman never reached the fight"));
	}

	private static Creeper primedCreeper(GameTestHelper helper, boolean powered, int fuse, double x) {
		Creeper creeper = helper.spawn(EntityType.CREEPER, new Vec3(x, 1, CENTER + 0.5));
		TagValueOutput output = TagValueOutput.createWithContext(ProblemReporter.DISCARDING, helper.getLevel().registryAccess());
		creeper.saveWithoutId(output);
		CompoundTag tag = output.buildResult();
		tag.putBoolean("powered", powered);
		tag.putShort("Fuse", (short) fuse);
		tag.putBoolean("ignited", true);
		creeper.load(TagValueInput.create(ProblemReporter.DISCARDING, helper.getLevel().registryAccess(), tag));
		creeper.setNoAi(true);
		return creeper;
	}

	@GameTest(structure = ARENA, environment = ENV, maxTicks = 100)
	public void unshieldedGuardEscapesChargedCreeperBlastRange(GameTestHelper helper) {
		GuardTestSupport.floor(helper);
		Creeper creeper = primedCreeper(helper, true, 100, CENTER + 0.5);
		GuardEntity guard = GuardTestSupport.swordsman(helper, CENTER + 8.5, 1, CENTER + 0.5);
		guard.setMainTarget(creeper);
		helper.succeedWhen(() -> helper.assertTrue(guard.distanceToSqr(creeper) >= 13 * 13, "guard stopped inside charged blast range"));
	}

	@GameTest(structure = ARENA, environment = ENV, maxTicks = 60)
	public void raisedShieldFacesAndBlocksCreeperBlast(GameTestHelper helper) {
		GuardTestSupport.floor(helper);
		Creeper creeper = primedCreeper(helper, false, 30, CENTER + 0.5);
		GuardEntity guard = GuardTestSupport.swordsman(helper, CENTER + 3.5, 1, CENTER + 0.5);
		guard.setItemSlot(EquipmentSlot.OFFHAND, new ItemStack(Items.SHIELD));
		guard.setMainTarget(creeper);
		helper.runAfterDelay(15, () -> {
			helper.assertTrue(guard.isBlocking(), "shield did not activate before explosion");
			Vec3 facing = guard.getViewVector(1.0F);
			helper.assertTrue(facing.dot(creeper.position().subtract(guard.position()).normalize()) > 0.5, "shield facing away from blast");
		});
		helper.runAfterDelay(35, () -> {
			helper.assertTrue(creeper.isRemoved(), "setup: creeper did not explode");
			helper.assertTrue(guard.isAlive() && guard.getHealth() == guard.getMaxHealth(), "raised shield failed to block explosion: health=" + guard.getHealth());
			helper.succeed();
		});
	}

	@GameTest(structure = ARENA, environment = ENV, maxTicks = 180)
	public void bowStrafingStaysInsideHomeZone(GameTestHelper helper) {
		GuardTestSupport.floor(helper);
		GuardEntity guard = GuardTestSupport.guard(helper, CENTER + 9.5, 1, CENTER + 0.5, GuardRole.BOWMAN);
		guard.setHome(helper.absolutePos(new BlockPos(CENTER, 1, CENTER)), 10);
		Husk target = helper.spawn(EntityType.HUSK, new Vec3(CENTER + 3.5, 1, CENTER + 0.5));
		target.setNoAi(true);
		target.getAttribute(net.minecraft.world.entity.ai.attributes.Attributes.MAX_HEALTH).setBaseValue(1000);
		target.setHealth(1000);
		helper.runAfterDelay(30, () -> {
			// Once the sight warmup ends, start a backward strafe at the actual border.
			Vec3 edge = helper.absoluteVec(new Vec3(CENTER + 10.8, 1, CENTER + 0.5));
			guard.setPos(edge);
			guard.setDeltaMovement(Vec3.ZERO);
			guard.lookAt(target, 360.0F, 360.0F);
		});
		GuardTestSupport.onEachTick(helper, () -> helper.assertTrue(guard.canTargetWithinZone(guard.blockPosition()), "bow strafe crossed home boundary"));
		helper.runAfterDelay(140, () -> {
			helper.assertTrue(target.getHealth() < 1000, "bowman never fought while checking boundary");
			helper.succeed();
		});
	}

	@GameTest(structure = ARENA, environment = ENV, maxTicks = 160)
	public void spectatorDoesNotBlockBowShot(GameTestHelper helper) {
		GuardTestSupport.floor(helper);
		GuardEntity guard = GuardTestSupport.guard(helper, CENTER + 0.5, 1, CENTER + 0.5, GuardRole.BOWMAN);
		guard.setHoldPosition(true);
		Husk target = helper.spawn(EntityType.HUSK, new Vec3(CENTER + 10.5, 1, CENTER + 0.5));
		target.setNoAi(true);
		GuardTestSupport.player(helper, GameType.SPECTATOR, CENTER + 5.5, 1, CENTER + 0.5);
		helper.succeedWhen(() -> helper.assertTrue(target.getLastHurtByMob() == guard, "spectator obstructed shot"));
	}

	@GameTest(structure = ARENA, environment = ENV, maxTicks = 100)
	public void villageGuardAcquiresDistrustedPlayerButOwnerRemainsAlly(GameTestHelper helper) {
		GuardTestSupport.floor(helper);
		ServerPlayer player = GuardTestSupport.survivalPlayer(helper, CENTER + 0.5, 1, CENTER + 0.5);
		GuardReputationManager.setReputation(helper.getLevel(), player.getUUID(), 0.0D);
		GuardEntity village = GuardTestSupport.swordsman(helper, CENTER + 8.5, 1, CENTER + 0.5);
		GuardEntity personal = GuardTestSupport.ownedGuard(helper, CENTER - 8.5, 1, CENTER + 0.5, player);
		GuardTestSupport.onEachTick(helper, () -> helper.assertTrue(personal.getTarget() != player, "personal guard targeted its owner"));
		helper.succeedWhen(() -> helper.assertTrue(village.getTarget() == player, "village guard did not acquire hostile-reputation player"));
	}

	@GameTest(structure = ARENA, environment = ENV, maxTicks = 100)
	public void reputationScanHonorsSightAndPlayerExemptions(GameTestHelper helper) {
		GuardTestSupport.floor(helper);
		GuardEntity guard = GuardTestSupport.guard(helper, CENTER + 0.5, 1, CENTER + 0.5, GuardRole.BOWMAN);
		guard.setHoldPosition(true);
		ServerPlayer creative = GuardTestSupport.player(helper, GameType.CREATIVE, CENTER - 3.5, 1, CENTER + 0.5);
		ServerPlayer spectator = GuardTestSupport.player(helper, GameType.SPECTATOR, CENTER + 0.5, 1, CENTER - 3.5);
		ServerPlayer hidden = GuardTestSupport.survivalPlayer(helper, CENTER + 4.5, 1, CENTER + 0.5);
		for (ServerPlayer player : List.of(creative, spectator, hidden)) {
			GuardReputationManager.setReputation(helper.getLevel(), player.getUUID(), 0.0D);
		}
		GuardTestSupport.fill(helper, CENTER + 2, 1, CENTER - 3, CENTER + 2, 4, CENTER + 3, Blocks.STONE);
		helper.runAfterDelay(40, () -> {
			helper.assertTrue(guard.getTarget() == null, "reputation scan ignored visibility or player exemptions");
			GuardTestSupport.fill(helper, CENTER + 2, 1, CENTER - 3, CENTER + 2, 4, CENTER + 3, Blocks.AIR);
		});
		helper.startSequence().thenIdle(45)
				.thenWaitUntil(() -> helper.assertTrue(guard.getTarget() == hidden, "visible distrusted player was not acquired"))
				.thenSucceed();
	}
}
