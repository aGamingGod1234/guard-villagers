package com.guardvillagers.gametest;

import static com.guardvillagers.gametest.GuardTestSupport.*;

import com.guardvillagers.GuardGameRules;
import com.guardvillagers.GuardHiring;
import com.guardvillagers.GuardOwnershipIndex;
import com.guardvillagers.entity.GuardEntity;
import com.guardvillagers.network.GuardRosterSyncPayload;
import com.guardvillagers.tactics.GuardTacticsMenus;
import io.netty.buffer.Unpooled;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.stream.Collectors;
import net.fabricmc.fabric.api.gametest.v1.GameTest;
import net.minecraft.core.component.DataComponents;
import net.minecraft.core.registries.Registries;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.chat.Component;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.component.ItemLore;
import net.minecraft.world.item.enchantment.Enchantments;

public class GuardScaleGameTest {
	@GameTest(structure = ARENA, environment = ENV, maxTicks = 60)
	public void threeHundredGuardsHireFollowAssignAndRoundTrip(GameTestHelper helper) {
		floor(helper);
		var player = survivalPlayer(helper, CENTER + 0.5, 1, CENTER + 0.5);
		var level = helper.getLevel();
		var rules = level.getServer().overworld().getGameRules();
		helper.assertValueEqual(GuardGameRules.MAX_PERSONAL_GUARDS.defaultValue(), 0, "registered default is unlimited");
		int previous = rules.get(GuardGameRules.MAX_PERSONAL_GUARDS);
		var guards = new ArrayList<GuardEntity>();
		try {
			runCommand(player, "gamerule guardvillagers:max_personal_guards 0", true);
			for (int i = 0; i < 299; i++) {
				var guard = ownedGuard(helper, 3.5 + i % 20 * 2, 1, 3.5 + i / 20 * 2, player);
				guard.setNoAi(true);
				guards.add(guard);
			}
			player.getInventory().add(new ItemStack(Items.EMERALD_BLOCK, 64));
			helper.assertValueEqual(GuardHiring.hire(player, 1).spawnedCount(), 1, "hire after the old limit");
			guards = new ArrayList<>(GuardOwnershipIndex.getOwnedGuards(level.getServer(), player.getUUID()));
			helper.assertValueEqual(guards.size(), 300, "300 owned guards");
			var names = new HashSet<String>();
			for (var guard : guards) {
				guard.setNoAi(true);
				helper.assertTrue(names.add(guard.getName().getString()), "unique generated name");
			}
			helper.assertValueEqual(runCommand(player, "guards follow"), 300, "follow reaches all guards");
			helper.assertTrue(guards.stream().allMatch(GuardEntity::hasFollowOverride), "all guards follow");
			runCommand(player, "guards groups add");
			String ids = guards.stream().map(g -> g.getUUID().toString()).collect(Collectors.joining(" "));
			helper.assertValueEqual(runCommand(player, "guards groups assignmany 1 " + ids), 300, "all guards assigned");
			helper.assertTrue(guards.stream().allMatch(g -> g.getGroupIndex() == 0), "all group memberships retained");

			// Enchanted, component-heavy gear forces byte-based splitting well before the count bound.
			ItemStack gear = new ItemStack(Items.DIAMOND_SWORD);
			gear.enchant(level.registryAccess().lookupOrThrow(Registries.ENCHANTMENT).getOrThrow(Enchantments.SHARPNESS), 5);
			gear.set(DataComponents.LORE, new ItemLore(java.util.Collections.nCopies(20, Component.literal("x".repeat(200)))));
			for (var guard : guards) guard.setItemSlot(EquipmentSlot.MAINHAND, gear.copy());
			var roster = GuardTacticsMenus.buildRoster(player);
			helper.assertValueEqual(roster.guards().size(), 300, "roster includes every owned guard");
			var packets = roster.chunks(level.registryAccess(), 7L);
			helper.assertTrue(packets.size() > 1, "heavy gear requires multiple packets");
			var decoded = new ArrayList<GuardRosterSyncPayload.GuardSummary>();
			int totalBytes = 0;
			for (var packet : packets) {
				var buf = new RegistryFriendlyByteBuf(Unpooled.buffer(), level.registryAccess());
				try {
					GuardRosterSyncPayload.CODEC.encode(buf, packet);
					int size = buf.readableBytes();
					helper.assertTrue(size <= GuardRosterSyncPayload.MAX_PACKET_BYTES, "packet within 512 KiB");
					totalBytes += size;
					var copy = GuardRosterSyncPayload.CODEC.decode(buf);
					helper.assertValueEqual(copy.offset(), decoded.size(), "ordered chunk offsets");
					helper.assertValueEqual(copy.snapshotId(), 7L, "snapshot identity preserved");
					helper.assertValueEqual(copy.contentHash(), packet.contentHash(), "equipment and status round trip");
					decoded.addAll(copy.guards());
				} finally { buf.release(); }
			}
			helper.assertTrue(totalBytes > 1_048_576, "test exceeds vanilla single-packet limit");
			helper.assertValueEqual(decoded.size(), 300, "complete decoded roster");
			helper.assertTrue(packets.getLast().last(), "last chunk marked");

			ItemStack oversized = gear.copy();
			oversized.set(DataComponents.LORE, new ItemLore(java.util.Collections.nCopies(20, Component.literal("y".repeat(20000)))));
			var first = guards.getFirst();
			first.setItemSlot(EquipmentSlot.MAINHAND, oversized);
			first.setItemSlot(EquipmentSlot.HEAD, oversized.copy());
			var safePackets = GuardTacticsMenus.buildRoster(player).chunks(level.registryAccess(), 8L);
			var safeRow = safePackets.stream().flatMap(packet -> packet.guards().stream())
					.filter(row -> row.guardUuid().equals(first.getUUID())).findFirst().orElseThrow();
			helper.assertTrue(safeRow.loaded() && safeRow.mainHand().isEmpty() && safeRow.helmet().isEmpty(),
					"oversized equipment omitted without losing the guard");

			helper.assertValueEqual(runCommand(player, "gamerule guardvillagers:max_personal_guards -1", true), -1,
					"negative caps rejected by gamerule argument");
			runCommand(player, "gamerule guardvillagers:max_personal_guards 300", true);
			helper.assertValueEqual(GuardHiring.hire(player, 1).result(), GuardHiring.Result.LIMIT_REACHED, "operator cap blocks hire");
			var unowned = swordsman(helper, CENTER + 1.5, 1, CENTER + 1.5);
			player.setItemInHand(InteractionHand.MAIN_HAND, new ItemStack(Items.EMERALD, 64));
			unowned.interact(player, InteractionHand.MAIN_HAND, unowned.position());
			helper.assertFalse(unowned.hasOwner(), "operator cap blocks emerald claim");
			runCommand(player, "gamerule guardvillagers:max_personal_guards 0", true);
			unowned.interact(player, InteractionHand.MAIN_HAND, unowned.position());
			helper.assertTrue(unowned.isOwnedBy(player.getUUID()), "unlimited restores emerald claiming");
			unowned.discard();
		} finally {
			rules.set(GuardGameRules.MAX_PERSONAL_GUARDS, previous, level.getServer());
			for (var guard : guards) guard.discard();
		}
		helper.succeed();
	}
}
