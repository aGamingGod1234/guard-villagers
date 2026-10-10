package com.guardvillagers.gametest;

import static com.guardvillagers.gametest.GuardTestSupport.ARENA;
import static com.guardvillagers.gametest.GuardTestSupport.CENTER;
import static com.guardvillagers.gametest.GuardTestSupport.ENV;

import com.guardvillagers.GuardHiring;
import com.guardvillagers.GuardOwnershipIndex;
import com.guardvillagers.GuardReputationManager;
import com.guardvillagers.GuardSecurityLimits;
import com.guardvillagers.GuardVillagersMod;
import com.guardvillagers.data.GuardOwnershipState;
import com.guardvillagers.entity.GuardEntity;
import com.guardvillagers.shop.GuardShopInventory;
import com.guardvillagers.shop.GuardShopScreenHandler;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import net.fabricmc.fabric.api.gametest.v1.GameTest;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.EntitySpawnReason;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.inventory.ContainerInput;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.phys.Vec3;

/** Claiming, hiring and the follow / stay / zone commands. */
public class GuardOwnershipGameTest {

	// ---- claiming an unowned guard with emeralds -------------------------------------------------

	private static int emeralds(ServerPlayer player) {
		return player.getInventory().countItem(Items.EMERALD);
	}

	private static int emeraldBlocks(ServerPlayer player) {
		return player.getInventory().countItem(Items.EMERALD_BLOCK);
	}

	private static void claim(ServerPlayer player, GuardEntity guard) {
		guard.interact(player, InteractionHand.MAIN_HAND, guard.position());
	}

	@GameTest(structure = ARENA, environment = ENV, maxTicks = 40)
	public void claimLevelOneGuardCostsFourEmeralds(GameTestHelper helper) {
		GuardTestSupport.floor(helper);
		ServerPlayer player = GuardTestSupport.survivalPlayer(helper, CENTER + 0.5, 1, CENTER + 2.5);
		GuardEntity guard = GuardTestSupport.swordsman(helper, CENTER + 0.5, 1, CENTER + 0.5);
		player.setItemInHand(InteractionHand.MAIN_HAND, new ItemStack(Items.EMERALD, 10));

		claim(player, guard);

		helper.assertTrue(guard.isOwnedBy(player.getUUID()), "guard was not claimed");
		helper.assertValueEqual(emeralds(player), 6, "emeralds left after claiming a level 1 guard (README: 4 + 2*(level-1))");
		helper.assertTrue(GuardOwnershipIndex.getOwnedGuards(helper.getLevel().getServer(), player.getUUID()).contains(guard),
				"claimed guard missing from ownership index");
		helper.succeed();
	}

	@GameTest(structure = ARENA, environment = ENV, maxTicks = 40)
	public void claimLevelThreeGuardChargesEightAndRejectsSeven(GameTestHelper helper) {
		GuardTestSupport.floor(helper);
		ServerPlayer player = GuardTestSupport.survivalPlayer(helper, CENTER + 0.5, 1, CENTER + 2.5);
		GuardEntity guard = GuardTestSupport.swordsman(helper, CENTER + 0.5, 1, CENTER + 0.5);
		guard.setExperience(240);
		helper.assertValueEqual(guard.getLevel(), 3, "setup: guard level");

		player.setItemInHand(InteractionHand.MAIN_HAND, new ItemStack(Items.EMERALD, 7));
		claim(player, guard);
		helper.assertFalse(guard.hasOwner(), "level 3 guard was claimed for 7 emeralds");
		helper.assertValueEqual(emeralds(player), 7, "emeralds after rejected claim");

		player.setItemInHand(InteractionHand.MAIN_HAND, new ItemStack(Items.EMERALD, 8));
		claim(player, guard);
		helper.assertTrue(guard.isOwnedBy(player.getUUID()), "level 3 guard not claimed for 8 emeralds");
		helper.assertValueEqual(emeralds(player), 0, "emeralds after claiming level 3 guard");
		helper.succeed();
	}

	@GameTest(structure = ARENA, environment = ENV, maxTicks = 40)
	public void claimRefusedWhenReputationBelowTrust(GameTestHelper helper) {
		GuardTestSupport.floor(helper);
		ServerPlayer player = GuardTestSupport.survivalPlayer(helper, CENTER + 0.5, 1, CENTER + 2.5);
		GuardReputationManager.setReputation(helper.getLevel(), player.getUUID(), 0.30D);
		GuardEntity guard = GuardTestSupport.swordsman(helper, CENTER + 0.5, 1, CENTER + 0.5);
		player.setItemInHand(InteractionHand.MAIN_HAND, new ItemStack(Items.EMERALD, 10));

		claim(player, guard);

		helper.assertFalse(guard.hasOwner(), "distrusted player claimed a guard");
		helper.assertValueEqual(emeralds(player), 10, "distrusted player lost emeralds");
		helper.succeed();
	}

	@GameTest(structure = ARENA, environment = ENV, maxTicks = 40)
	public void claimingSomeoneElsesGuardDoesNothing(GameTestHelper helper) {
		GuardTestSupport.floor(helper);
		ServerPlayer owner = GuardTestSupport.survivalPlayer(helper, CENTER + 4.5, 1, CENTER + 0.5);
		ServerPlayer thief = GuardTestSupport.survivalPlayer(helper, CENTER + 0.5, 1, CENTER + 2.5);
		GuardEntity guard = GuardTestSupport.ownedGuard(helper, CENTER + 0.5, 1, CENTER + 0.5, owner);
		thief.setItemInHand(InteractionHand.MAIN_HAND, new ItemStack(Items.EMERALD, 10));

		claim(thief, guard);

		helper.assertTrue(guard.isOwnedBy(owner.getUUID()), "guard changed owner");
		helper.assertValueEqual(emeralds(thief), 10, "thief lost emeralds");
		helper.succeed();
	}

	// ---- shop hiring ------------------------------------------------------------------------------

	private static GuardShopScreenHandler openShop(GameTestHelper helper, ServerPlayer player) {
		GuardTestSupport.runCommand(player, "guards shop");
		if (!(player.containerMenu instanceof GuardShopScreenHandler shop)) {
			throw helper.assertionException("/guards shop did not open the shop menu, open menu=" + player.containerMenu);
		}
		return shop;
	}

	private static List<GuardEntity> owned(GameTestHelper helper, ServerPlayer player) {
		return GuardOwnershipIndex.getOwnedGuards(helper.getLevel().getServer(), player.getUUID());
	}

	private static int droppedEmeraldBlocks(GameTestHelper helper) {
		AABB box = helper.getBounds().inflate(4.0D);
		return helper.getLevel().getEntitiesOfClass(ItemEntity.class, box, item -> item.getItem().is(Items.EMERALD_BLOCK))
				.stream().mapToInt(item -> item.getItem().getCount()).sum();
	}

	@GameTest(structure = ARENA, environment = ENV, maxTicks = 40)
	public void shopHireChargesExactlyOnce(GameTestHelper helper) {
		GuardTestSupport.floor(helper);
		ServerPlayer player = GuardTestSupport.survivalPlayer(helper, CENTER + 0.5, 1, CENTER + 0.5);
		player.getInventory().add(new ItemStack(Items.EMERALD_BLOCK, 20));
		int cost = com.guardvillagers.GuardHiring.getAdjustedGuardCost(player);
		GuardShopScreenHandler shop = openShop(helper, player);

		shop.clicked(GuardShopInventory.SLOT_BUY_GUARD, 0, ContainerInput.PICKUP, player);

		helper.assertValueEqual(owned(helper, player).size(), 1, "guards owned after one hire click");
		helper.assertValueEqual(emeraldBlocks(player), 20 - cost, "emerald blocks after one hire (cost " + cost + ")");
		helper.assertValueEqual(droppedEmeraldBlocks(helper), 0, "emerald blocks dropped as items (refund/duplication)");
		GuardEntity hired = owned(helper, player).getFirst();
		helper.assertTrue(hired.hasFollowOverride(), "hired guard should start in follow mode");
		helper.assertFalse(hired.getMainHandItem().isEmpty(), "hired guard has no weapon");
		helper.succeed();
	}

	@GameTest(structure = ARENA, environment = ENV, maxTicks = 40)
	public void shopBulkHireChargesPerGuard(GameTestHelper helper) {
		GuardTestSupport.floor(helper);
		ServerPlayer player = GuardTestSupport.survivalPlayer(helper, CENTER + 0.5, 1, CENTER + 0.5);
		int cost = com.guardvillagers.GuardHiring.getAdjustedGuardCost(player);
		player.getInventory().add(new ItemStack(Items.EMERALD_BLOCK, cost * 3 + 1));
		GuardShopScreenHandler shop = openShop(helper, player);

		shop.clicked(GuardShopInventory.SLOT_BUY_GUARD, 0, ContainerInput.QUICK_MOVE, player);

		helper.assertValueEqual(owned(helper, player).size(), 3, "guards owned after shift-click hire");
		helper.assertValueEqual(emeraldBlocks(player), 1, "emerald blocks left after bulk hire (3 x " + cost + ")");
		helper.assertValueEqual(droppedEmeraldBlocks(helper), 0, "emerald blocks dropped as items");
		helper.succeed();
	}

	@GameTest(structure = ARENA, environment = ENV, maxTicks = 40)
	public void shopHireWithoutFundsSpendsNothing(GameTestHelper helper) {
		GuardTestSupport.floor(helper);
		ServerPlayer player = GuardTestSupport.survivalPlayer(helper, CENTER + 0.5, 1, CENTER + 0.5);
		int cost = com.guardvillagers.GuardHiring.getAdjustedGuardCost(player);
		player.getInventory().add(new ItemStack(Items.EMERALD_BLOCK, cost - 1));
		GuardShopScreenHandler shop = openShop(helper, player);

		shop.clicked(GuardShopInventory.SLOT_BUY_GUARD, 0, ContainerInput.PICKUP, player);
		shop.clicked(GuardShopInventory.SLOT_BUY_GUARD, 0, ContainerInput.QUICK_MOVE, player);

		helper.assertValueEqual(owned(helper, player).size(), 0, "guards hired without funds");
		helper.assertValueEqual(emeraldBlocks(player), cost - 1, "emerald blocks after failed hires");
		helper.succeed();
	}

	/**
	 * Solid stone block, 25 wide and 26 tall, with a single 1-high air cell for the player at y=13.
	 * The spawn search covers +-10 blocks vertically and horizontally, all solid, so the hire must fail
	 * and the emerald blocks must go back into the inventory.
	 */
	private static ServerPlayer entombedBuyer(GameTestHelper helper) {
		GuardTestSupport.fill(helper, CENTER - 12, 0, CENTER - 12, CENTER + 12, 25, CENTER + 12, Blocks.STONE);
		helper.setBlock(new BlockPos(CENTER, 13, CENTER), Blocks.AIR);
		ServerPlayer player = GuardTestSupport.survivalPlayer(helper, CENTER + 0.5, 13, CENTER + 0.5);
		return player;
	}

	@GameTest(structure = ARENA, environment = ENV, maxTicks = 40)
	public void shopHireRefundsWhenNoSpawnSpace(GameTestHelper helper) {
		ServerPlayer player = entombedBuyer(helper);
		int cost = com.guardvillagers.GuardHiring.getAdjustedGuardCost(player);
		player.getInventory().add(new ItemStack(Items.EMERALD_BLOCK, cost * 2));
		GuardShopScreenHandler shop = openShop(helper, player);

		shop.clicked(GuardShopInventory.SLOT_BUY_GUARD, 0, ContainerInput.PICKUP, player);

		helper.assertTrue(owned(helper, player).isEmpty(), "guard spawned with no space at "
				+ owned(helper, player).stream().map(g -> GuardTestSupport.relative(helper, g).toString()).toList());
		helper.assertValueEqual(emeraldBlocks(player), cost * 2, "emerald blocks in inventory after failed spawn");
		helper.assertValueEqual(droppedEmeraldBlocks(helper), 0, "emerald blocks dropped on the floor");
		helper.succeed();
	}

	/**
	 * Same tomb, plus a sealed 3x3x2 air pocket 8 blocks under the player's feet with no way out.
	 * README: "If the exact player X/Z column cannot support a spawn, the purchase fails cleanly".
	 * The hired guard must not be dropped into a cavity it can never leave.
	 */
	@GameTest(structure = ARENA, environment = ENV, maxTicks = 40)
	public void shopHireDoesNotSpawnIntoSealedCavity(GameTestHelper helper) {
		ServerPlayer player = entombedBuyer(helper);
		GuardTestSupport.fill(helper, CENTER - 1, 4, CENTER - 1, CENTER + 1, 5, CENTER + 1, Blocks.AIR);
		int cost = com.guardvillagers.GuardHiring.getAdjustedGuardCost(player);
		player.getInventory().add(new ItemStack(Items.EMERALD_BLOCK, cost));
		GuardShopScreenHandler shop = openShop(helper, player);

		shop.clicked(GuardShopInventory.SLOT_BUY_GUARD, 0, ContainerInput.PICKUP, player);

		for (GuardEntity guard : owned(helper, player)) {
			helper.assertTrue(Math.abs(guard.getY() - player.getY()) < 2.0D,
					"hired guard was placed in a sealed cavity at " + GuardTestSupport.relative(helper, guard)
							+ ", player at " + GuardTestSupport.relative(helper, player));
		}
		helper.succeed();
	}

	// ---- commands -----------------------------------------------------------------------------

	@GameTest(structure = ARENA, environment = ENV, maxTicks = 200)
	public void stayCommandHoldsPosition(GameTestHelper helper) {
		GuardTestSupport.floor(helper);
		ServerPlayer owner = GuardTestSupport.survivalPlayer(helper, CENTER + 2.5, 1, CENTER + 0.5);
		GuardEntity guard = GuardTestSupport.ownedGuard(helper, CENTER + 0.5, 1, CENTER + 0.5, owner);
		guard.setFollowOverride(true);
		helper.assertValueEqual(GuardTestSupport.runCommand(owner, "guards stay"), 1, "/guards stay result");
		helper.assertTrue(guard.isStaying(), "guard not staying after /guards stay");
		Vec3 origin = guard.position();
		GuardTestSupport.moveTo(helper, owner, CENTER + 18.5, 1, CENTER + 0.5);

		helper.runAfterDelay(160, () -> {
			helper.assertTrue(guard.position().distanceTo(origin) <= GuardEntity.STAY_RADIUS + 1,
					"staying guard drifted " + guard.position().distanceTo(origin) + " blocks");
			helper.succeed();
		});
	}

	@GameTest(structure = ARENA, environment = ENV, maxTicks = 300)
	public void followCommandBringsGuardToOwner(GameTestHelper helper) {
		GuardTestSupport.floor(helper);
		ServerPlayer owner = GuardTestSupport.survivalPlayer(helper, CENTER - 14.5, 1, CENTER + 0.5);
		GuardEntity guard = GuardTestSupport.ownedGuard(helper, CENTER + 6.5, 1, CENTER + 0.5, owner);
		guard.setStaying(true);
		helper.assertValueEqual(GuardTestSupport.runCommand(owner, "guards follow"), 1, "/guards follow result");
		helper.assertFalse(guard.isStaying(), "guard still staying after /guards follow");
		helper.assertTrue(guard.hasFollowOverride(), "follow override not set");

		helper.succeedWhen(() -> helper.assertTrue(guard.distanceTo(owner) <= 5.0F,
				"guard is " + guard.distanceTo(owner) + " blocks from owner"));
	}

	@GameTest(structure = ARENA, environment = ENV, maxTicks = 400)
	public void zoneCommandTethersGuardHome(GameTestHelper helper) {
		GuardTestSupport.floor(helper);
		ServerPlayer owner = GuardTestSupport.survivalPlayer(helper, CENTER + 0.5, 1, CENTER + 0.5);
		GuardEntity guard = GuardTestSupport.ownedGuard(helper, CENTER + 2.5, 1, CENTER + 0.5, owner);
		helper.assertValueEqual(GuardTestSupport.runCommand(owner, "guards zone 8"), 1, "/guards zone result");
		BlockPos home = owner.blockPosition();
		helper.assertValueEqual(guard.getHome().orElse(null), home, "guard home");
		helper.assertValueEqual(guard.getPatrolRadius(), 8, "patrol radius");
		helper.assertFalse(guard.hasFollowOverride(), "zone assignment should clear forced follow");

		// Drag the guard well outside radius + 8 tether buffer; the owner stays at home.
		guard.teleportTo(guard.getX() + 20, guard.getY(), guard.getZ());
		helper.succeedWhen(() -> {
			double distance = Math.sqrt(guard.blockPosition().distSqr(home));
			helper.assertTrue(distance <= 16.0D, "guard is " + distance + " blocks from its zone home");
		});
	}

	@GameTest(structure = ARENA, environment = ENV, maxTicks = 400)
	public void followCommandOverridesZoneTether(GameTestHelper helper) {
		GuardTestSupport.floor(helper);
		ServerPlayer owner = GuardTestSupport.survivalPlayer(helper, CENTER - 10.5, 1, CENTER + 0.5);
		GuardEntity guard = GuardTestSupport.ownedGuard(helper, CENTER - 8.5, 1, CENTER + 0.5, owner);
		GuardTestSupport.runCommand(owner, "guards zone 8");
		GuardTestSupport.runCommand(owner, "guards follow");
		helper.assertTrue(guard.getHome().isPresent(), "plain follow should keep the zone (README: tether is suppressed, not cleared)");
		GuardTestSupport.moveTo(helper, owner, CENTER + 18.5, 1, CENTER + 0.5);

		helper.succeedWhen(() -> helper.assertTrue(guard.distanceTo(owner) <= 6.0F,
				"guard is " + guard.distanceTo(owner) + " blocks from owner outside its zone"));
	}

	// ---- persisted ownership rows ------------------------------------------------------------------

	private static GuardOwnershipState ownershipState(GameTestHelper helper) {
		return helper.getLevel().getServer().getDataStorage().computeIfAbsent(GuardOwnershipState.TYPE);
	}

	@GameTest(structure = ARENA, environment = ENV, maxTicks = 40)
	public void longUnseenGuardsStillCountTowardTheLimit(GameTestHelper helper) {
		GuardTestSupport.floor(helper);
		ServerPlayer player = GuardTestSupport.survivalPlayer(helper, CENTER + 0.5, 1, CENTER + 0.5);
		player.getInventory().add(new ItemStack(Items.EMERALD_BLOCK, 64));
		GuardOwnershipState state = ownershipState(helper);
		List<UUID> parked = new ArrayList<>();
		// Guards last seen at tick 0, i.e. parked in unloaded chunks far longer than the old 100-day expiry.
		for (int i = 0; i < GuardSecurityLimits.MAX_PERSONAL_GUARDS_PER_PLAYER; i++) {
			UUID guardId = UUID.randomUUID();
			parked.add(guardId);
			state.record(guardId, player.getUUID(), 0L, "Parked " + i, -1);
		}
		try {
			GuardHiring.BatchResult result = GuardHiring.hire(player, 1);

			helper.assertValueEqual(result.result(), GuardHiring.Result.LIMIT_REACHED, "hire result with 16 unloaded guards");
			helper.assertValueEqual(owned(helper, player).size(), 0, "guards spawned past the limit");
			helper.assertValueEqual(GuardOwnershipIndex.getUnloadedOwnedGuards(helper.getLevel().getServer(), player.getUUID(), Set.of()).size(),
					GuardSecurityLimits.MAX_PERSONAL_GUARDS_PER_PLAYER, "unloaded roster rows");
		} finally {
			parked.forEach(state::remove);
		}
		helper.succeed();
	}

	@GameTest(structure = ARENA, environment = ENV, maxTicks = 40)
	public void failedSpawnDoesNotLeaveAPhantomOwnershipRow(GameTestHelper helper) {
		GuardTestSupport.floor(helper);
		ServerPlayer player = GuardTestSupport.survivalPlayer(helper, CENTER + 0.5, 1, CENTER + 0.5);
		GuardEntity guard = GuardVillagersMod.GUARD_ENTITY_TYPE.create(helper.getLevel(), EntitySpawnReason.EVENT);
		helper.assertTrue(guard != null, "guard entity type did not create a guard");
		Vec3 spawn = helper.absoluteVec(new Vec3(CENTER + 2.5, 1, CENTER + 0.5));
		guard.snapTo(spawn.x, spawn.y, spawn.z, 0.0F, 0.0F);

		boolean threw = false;
		try {
			GuardHiring.spawnOwned(helper.getLevel(), guard, player.getUUID(), configured -> {
				throw new IllegalStateException("simulated loadout failure");
			});
		} catch (IllegalStateException expected) {
			threw = true;
		}

		helper.assertTrue(threw, "spawn failure was swallowed instead of rethrown");
		helper.assertTrue(ownershipState(helper).get(guard.getUUID()).isEmpty(), "failed spawn left a persisted ownership row");
		helper.assertValueEqual(GuardOwnershipIndex.countOwnedGuards(helper.getLevel().getServer(), player.getUUID()), 0,
				"failed spawn still counts toward the personal limit");
		helper.succeed();
	}
}
