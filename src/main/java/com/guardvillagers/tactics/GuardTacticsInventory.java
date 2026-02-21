package com.guardvillagers.tactics;

import com.guardvillagers.GuardDiplomacyManager;
import com.guardvillagers.entity.GuardEntity;
import net.minecraft.component.DataComponentTypes;
import net.minecraft.component.type.LoreComponent;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.inventory.SimpleInventory;
import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;
import net.minecraft.item.Items;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.text.Text;
import net.minecraft.util.Formatting;
import net.minecraft.util.math.BlockPos;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

public final class GuardTacticsInventory extends SimpleInventory {
	private static final int SIZE = 54;
	private static final int PLAYER_SLOT_START = 18;
	private static final int PLAYER_SLOT_END = 44;
	private static final int PLAYER_SLOTS_PER_PAGE = PLAYER_SLOT_END - PLAYER_SLOT_START + 1;
	private static final int PREV_PAGE_SLOT = 45;
	private static final int INFO_SLOT = 49;
	private static final int NEXT_PAGE_SLOT = 53;
	private static final int REVIEW_SLOT = 50;

	private final ServerPlayerEntity owner;
	private final Map<Integer, UUID> playerSlotTargets = new HashMap<>();
	private int page;
	private List<PlayerListing> cachedPlayers = List.of();

	public GuardTacticsInventory(ServerPlayerEntity owner) {
		super(SIZE);
		this.owner = owner;
		this.refresh();
	}

	@Override
	public boolean canPlayerUse(PlayerEntity player) {
		return player == this.owner;
	}

	@Override
	public boolean isValid(int slot, ItemStack stack) {
		return false;
	}

	public boolean handleClick(int slot, int button) {
		MinecraftServer server = this.owner.getCommandSource().getServer();
		if (server == null) {
			return false;
		}

		if (slot == PREV_PAGE_SLOT) {
			if (this.page > 0) {
				this.page--;
			}
			return true;
		}
		if (slot == NEXT_PAGE_SLOT) {
			int maxPage = Math.max(0, (this.cachedPlayers.size() - 1) / PLAYER_SLOTS_PER_PAGE);
			if (this.page < maxPage) {
				this.page++;
			}
			return true;
		}
		if (slot == REVIEW_SLOT) {
			GuardDiplomacyManager.requestReview(this.owner);
			return true;
		}

		UUID target = this.playerSlotTargets.get(slot);
		if (target == null || target.equals(this.owner.getUuid())) {
			return false;
		}

		boolean changed;
		if (button == 1) {
			changed = GuardDiplomacyManager.toggleBlacklist(server, this.owner.getUuid(), target);
			this.owner.sendMessage(Text.literal((changed ? "Blacklisted " : "Removed blacklist for ") + this.resolveName(server, target) + "."), true);
		} else {
			changed = GuardDiplomacyManager.toggleWhitelist(server, this.owner.getUuid(), target);
			this.owner.sendMessage(Text.literal((changed ? "Whitelisted " : "Removed whitelist for ") + this.resolveName(server, target) + "."), true);
		}
		return true;
	}

	public void refresh() {
		this.playerSlotTargets.clear();
		for (int slot = 0; slot < this.size(); slot++) {
			this.setStack(slot, ItemStack.EMPTY);
		}
		for (int slot = 0; slot < this.size(); slot++) {
			if (slot >= PLAYER_SLOT_START && slot <= PLAYER_SLOT_END) {
				continue;
			}
			if (slot == PREV_PAGE_SLOT || slot == NEXT_PAGE_SLOT || slot == INFO_SLOT || slot == REVIEW_SLOT) {
				continue;
			}
			this.setStack(slot, this.decorativePane(slot));
		}

		this.populateZones();
		this.populatePlayers();
		this.populateControls();
	}

	private void populateZones() {
		List<String> zones = this.collectKnownZones();
		if (zones.isEmpty()) {
			this.setStack(4, this.card(
				Items.BARRIER,
				"Known Zones",
				Formatting.DARK_RED,
				"No guard zones assigned yet.",
				"Use the whistle on a guard to",
				"assign a home zone."
			));
			return;
		}

		for (int i = 0; i < Math.min(9, zones.size()); i++) {
			String zone = zones.get(i);
			this.setStack(i, this.card(
				Items.COMPASS,
				"Zone " + (i + 1),
				Formatting.AQUA,
				zone
			));
		}
	}

	private void populatePlayers() {
		MinecraftServer server = this.owner.getCommandSource().getServer();
		if (server == null) {
			return;
		}

		this.cachedPlayers = this.collectKnownPlayers(server);
		int maxPage = Math.max(0, (this.cachedPlayers.size() - 1) / PLAYER_SLOTS_PER_PAGE);
		this.page = Math.max(0, Math.min(this.page, maxPage));
		int start = this.page * PLAYER_SLOTS_PER_PAGE;
		int end = Math.min(this.cachedPlayers.size(), start + PLAYER_SLOTS_PER_PAGE);
		Set<UUID> whitelist = GuardDiplomacyManager.getWhitelist(server, this.owner.getUuid());
		Set<UUID> blacklist = GuardDiplomacyManager.getBlacklist(server, this.owner.getUuid());

		int slot = PLAYER_SLOT_START;
		for (int i = start; i < end; i++) {
			PlayerListing entry = this.cachedPlayers.get(i);
			boolean whitelisted = whitelist.contains(entry.uuid());
			boolean blacklisted = blacklist.contains(entry.uuid());

			Item icon = blacklisted ? Items.REDSTONE_BLOCK : (whitelisted ? Items.EMERALD_BLOCK : Items.PAPER);
			Formatting color = blacklisted ? Formatting.RED : (whitelisted ? Formatting.GREEN : Formatting.YELLOW);
			this.setStack(slot, this.card(
				icon,
				entry.name(),
				color,
				"Whitelist: " + (whitelisted ? "Yes" : "No"),
				"Blacklist: " + (blacklisted ? "Yes" : "No"),
				"Left Click: Toggle whitelist",
				"Right Click: Toggle blacklist"
			));
			this.playerSlotTargets.put(slot, entry.uuid());
			slot++;
		}
	}

	private void populateControls() {
		int maxPage = Math.max(0, (this.cachedPlayers.size() - 1) / PLAYER_SLOTS_PER_PAGE);
		this.setStack(PREV_PAGE_SLOT, this.card(
			Items.ARROW,
			"Previous Page",
			Formatting.GRAY,
			"Current: " + (this.page + 1) + "/" + (maxPage + 1)
		));
		this.setStack(NEXT_PAGE_SLOT, this.card(
			Items.SPECTRAL_ARROW,
			"Next Page",
			Formatting.GRAY,
			"Current: " + (this.page + 1) + "/" + (maxPage + 1)
		));
		this.setStack(INFO_SLOT, this.card(
			Items.BOOK,
			"Tactics Panel",
			Formatting.GOLD,
			"Manage trusted players",
			"and review squad zones."
		));
		this.setStack(REVIEW_SLOT, this.card(
			Items.WRITABLE_BOOK,
			"Request Review",
			Formatting.LIGHT_PURPLE,
			"Notify server operators about",
			"your current blacklist."
		));
	}

	private List<PlayerListing> collectKnownPlayers(MinecraftServer server) {
		Map<UUID, String> entries = new HashMap<>();
		for (ServerPlayerEntity player : server.getPlayerManager().getPlayerList()) {
			entries.put(player.getUuid(), player.getName().getString());
		}

		Set<UUID> tracked = new LinkedHashSet<>();
		tracked.addAll(GuardDiplomacyManager.getWhitelist(server, this.owner.getUuid()));
		tracked.addAll(GuardDiplomacyManager.getBlacklist(server, this.owner.getUuid()));
		for (UUID uuid : tracked) {
			entries.putIfAbsent(uuid, uuid.toString());
		}
		entries.putIfAbsent(this.owner.getUuid(), this.owner.getName().getString());

		List<PlayerListing> list = new ArrayList<>();
		for (Map.Entry<UUID, String> entry : entries.entrySet()) {
			list.add(new PlayerListing(entry.getKey(), entry.getValue()));
		}
		list.sort(Comparator.comparing(PlayerListing::name, String.CASE_INSENSITIVE_ORDER));
		return list;
	}

	private List<String> collectKnownZones() {
		MinecraftServer server = this.owner.getCommandSource().getServer();
		if (server == null) {
			return List.of();
		}

		Set<String> zones = new LinkedHashSet<>();
		for (ServerWorld world : server.getWorlds()) {
			for (GuardEntity guard : world.getEntitiesByClass(
				GuardEntity.class,
				this.owner.getBoundingBox().expand(512.0D),
				entity -> entity.isOwnedBy(this.owner.getUuid()))
			) {
				guard.getHome().ifPresent(home -> zones.add(this.formatZone(home, guard.getPatrolRadius(), world.getRegistryKey().getValue().toString())));
			}
		}
		return new ArrayList<>(zones);
	}

	private String formatZone(BlockPos home, int radius, String worldId) {
		return worldId + " @ " + home.getX() + ", " + home.getY() + ", " + home.getZ() + " r=" + radius;
	}

	private String resolveName(MinecraftServer server, UUID uuid) {
		ServerPlayerEntity player = server.getPlayerManager().getPlayer(uuid);
		return player == null ? uuid.toString() : player.getName().getString();
	}

	private ItemStack decorativePane(int slot) {
		Item pane = (slot % 2 == 0) ? Items.BLACK_STAINED_GLASS_PANE : Items.GRAY_STAINED_GLASS_PANE;
		ItemStack stack = new ItemStack(pane);
		stack.set(DataComponentTypes.CUSTOM_NAME, Text.literal(" "));
		return stack;
	}

	private ItemStack card(Item item, String title, Formatting titleColor, String... lines) {
		ItemStack stack = new ItemStack(item);
		stack.set(DataComponentTypes.CUSTOM_NAME, Text.literal(title).formatted(titleColor, Formatting.BOLD));
		if (lines.length > 0) {
			List<Text> loreLines = new ArrayList<>();
			for (String line : lines) {
				loreLines.add(Text.literal(line).formatted(Formatting.GRAY));
			}
			stack.set(DataComponentTypes.LORE, new LoreComponent(loreLines));
		}
		return stack;
	}

	private record PlayerListing(UUID uuid, String name) {
	}
}
