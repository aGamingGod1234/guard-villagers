package com.guardvillagers.command;

import com.guardvillagers.GuardDebugManager;
import com.guardvillagers.GuardDiplomacyManager;
import com.guardvillagers.GuardOwnershipIndex;
import com.guardvillagers.GuardReputationManager;
import com.guardvillagers.GuardSecurityLimits;
import com.guardvillagers.GuardTacticsManager;
import com.guardvillagers.GuardVillagersMod;
import com.guardvillagers.data.GuardDiplomacyState;
import com.guardvillagers.data.GuardTacticsState;
import com.guardvillagers.entity.GuardEntity;
import com.guardvillagers.network.GuardGroupResultPayload;
import com.guardvillagers.shop.GuardShopScreenHandler;
import com.guardvillagers.tactics.GuardTacticsMenus;
import com.mojang.brigadier.Command;
import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.context.CommandContext;
import com.mojang.brigadier.exceptions.CommandSyntaxException;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.commands.arguments.EntityArgument;
import net.minecraft.commands.arguments.GameProfileArgument;
import net.minecraft.commands.arguments.UuidArgument;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.players.NameAndId;
import net.minecraft.world.SimpleMenuProvider;

/** The {@code /guards} command tree. Player-facing subcommands only ever touch the caller's own guards and state. */
public final class GuardCommands {
	/** Mirrors the tactics state and roster packet cap so row arguments can never grow state past it. */
	private static final int MAX_GROUP_ROW = GuardTacticsState.PlayerTactics.MAX_GROUPS;
	private static final int MIN_ZONE_RADIUS = 8;
	private static final int MAX_ZONE_RADIUS = 128;
	private static final double ZONE_ASSIGN_RANGE = 96.0D;
	/** Upper bound for one {@code groups assignmany} call; the roster never holds more owned guards than this per screen. */
	private static final int MAX_BATCH_ASSIGN = 64;

	private GuardCommands() {
	}

	public static void register(CommandDispatcher<CommandSourceStack> dispatcher) {
		dispatcher.register(Commands.literal("guards")
				.then(Commands.literal("shop").executes(context -> openShop(context.getSource().getPlayerOrException())))
				.then(Commands.literal("tactics").executes(context -> {
					GuardTacticsMenus.openTactics(context.getSource().getPlayerOrException());
					return Command.SINGLE_SUCCESS;
				}))
				.then(Commands.literal("stay")
						.executes(context -> stay(context.getSource(), null))
						// Mirrors "follow <group>" so the tactics screen can order one group at a time.
						.then(Commands.argument("group", StringArgumentType.greedyString())
								.executes(context -> stay(context.getSource(), StringArgumentType.getString(context, "group")))))
				.then(Commands.literal("hold").executes(context -> hold(context.getSource())))
				.then(Commands.literal("cleartargets").executes(context -> clearTargets(context.getSource())))
				.then(Commands.literal("follow")
						.executes(context -> follow(context.getSource(), null))
						.then(Commands.argument("group", StringArgumentType.greedyString())
								.executes(context -> follow(context.getSource(), StringArgumentType.getString(context, "group")))))
				.then(Commands.literal("zone")
						.then(Commands.argument("radius", IntegerArgumentType.integer(MIN_ZONE_RADIUS, MAX_ZONE_RADIUS))
								.executes(context -> zone(context.getSource(), IntegerArgumentType.getInteger(context, "radius")))))
				.then(Commands.literal("groups")
						.executes(context -> {
							GuardTacticsMenus.openGroups(context.getSource().getPlayerOrException());
							return Command.SINGLE_SUCCESS;
						})
						.then(Commands.literal("add").executes(context -> addGroup(context.getSource())))
						.then(Commands.literal("rename")
								.then(Commands.argument("row", IntegerArgumentType.integer(1, MAX_GROUP_ROW))
										.then(Commands.argument("name", StringArgumentType.greedyString())
												.executes(context -> renameGroup(context.getSource(),
														IntegerArgumentType.getInteger(context, "row") - 1,
														StringArgumentType.getString(context, "name"))))))
						.then(Commands.literal("assign")
								.then(Commands.argument("guardUuid", UuidArgument.uuid())
										// 0 unassigns; 1..MAX_GROUP_ROW select an existing group row.
										.then(Commands.argument("groupRow", IntegerArgumentType.integer(0, MAX_GROUP_ROW))
												.executes(context -> assign(context.getSource(),
														List.of(UuidArgument.getUuid(context, "guardUuid")),
														IntegerArgumentType.getInteger(context, "groupRow") - 1)))))
						// One command for many guards: the tactics screen otherwise sends one command per guard,
						// which trips vanilla's chat spam kick (10+ commands at once) for non-operators.
						.then(Commands.literal("assignmany")
								.then(Commands.argument("groupRow", IntegerArgumentType.integer(0, MAX_GROUP_ROW))
										.then(Commands.argument("guardUuids", StringArgumentType.greedyString())
												.executes(context -> assignMany(context)))))
				)
				.then(Commands.literal("count").executes(context -> count(context.getSource())))
				// Players your guards treat as friends: they will not be targeted, and they can ring your guards to a bell.
				.then(Commands.literal("trust")
						.then(Commands.literal("list").executes(context -> listTrusted(context.getSource())))
						.then(Commands.literal("add")
								.then(Commands.argument("players", GameProfileArgument.gameProfile())
										.executes(context -> changeTrust(context.getSource(),
												GameProfileArgument.getGameProfiles(context, "players"), true))))
						.then(Commands.literal("remove")
								.then(Commands.argument("players", GameProfileArgument.gameProfile())
										.executes(context -> changeTrust(context.getSource(),
												GameProfileArgument.getGameProfiles(context, "players"), false)))))
				.then(Commands.literal("reputation")
						.requires(Commands.hasPermission(Commands.LEVEL_GAMEMASTERS))
						.then(Commands.literal("get")
								.executes(context -> getReputation(context.getSource(), context.getSource().getPlayerOrException()))
								.then(Commands.argument("player", EntityArgument.player())
										.executes(context -> getReputation(context.getSource(), EntityArgument.getPlayer(context, "player")))))
						// Order matters: "/guards reputation 0.50" also parses as a player name, and brigadier keeps the
						// first of two equally good parses. Registering <value> first makes the self form work.
						.then(Commands.argument("value", StringArgumentType.word())
								.executes(context -> setReputation(context.getSource(), context.getSource().getPlayerOrException(),
										StringArgumentType.getString(context, "value"))))
						.then(Commands.argument("player", EntityArgument.player())
								.then(Commands.argument("value", StringArgumentType.word())
										.executes(context -> setReputation(context.getSource(), EntityArgument.getPlayer(context, "player"),
												StringArgumentType.getString(context, "value"))))))
				.then(Commands.literal("debug")
						.requires(Commands.hasPermission(Commands.LEVEL_GAMEMASTERS))
						.executes(context -> debug(context.getSource(), -1))
						.then(Commands.argument("range", IntegerArgumentType.integer(1, GuardDebugManager.MAX_REQUESTED_RANGE))
								.executes(context -> debug(context.getSource(), IntegerArgumentType.getInteger(context, "range"))))));
	}

	private static int openShop(ServerPlayer player) {
		try {
			player.openMenu(new SimpleMenuProvider(
					(syncId, playerInventory, ignoredPlayer) -> new GuardShopScreenHandler(syncId, playerInventory, player),
					Component.translatable("screen.guardvillagers.shop")));
			return Command.SINGLE_SUCCESS;
		} catch (RuntimeException exception) {
			GuardVillagersMod.LOGGER.error("Failed to open guard shop for {}", player.getName().getString(), exception);
			player.sendSystemMessage(Component.translatable("message.guardvillagers.screen_failed"));
			return 0;
		}
	}

	private static int stay(CommandSourceStack source, String groupName) throws CommandSyntaxException {
		ServerPlayer player = source.getPlayerOrException();
		int changed = 0;
		for (GuardEntity guard : ownedGuards(player)) {
			if (groupName != null && !guard.getGroupName().equalsIgnoreCase(groupName.trim())) {
				continue;
			}
			guard.setStaying(true);
			changed++;
		}
		if (groupName != null && changed == 0) {
			source.sendFailure(Component.translatable("commands.guardvillagers.stay.no_group", groupName.trim()));
			return 0;
		}
		Component message = groupName == null
				? Component.translatable("commands.guardvillagers.stay", changed)
				: Component.translatable("commands.guardvillagers.stay.group", changed, groupName.trim());
		return reportOrders(source, changed, message);
	}

	/** Stand ground where they are: fight only what is in reach, never path away (GuardEntity hold mode). */
	private static int hold(CommandSourceStack source) throws CommandSyntaxException {
		ServerPlayer player = source.getPlayerOrException();
		List<GuardEntity> guards = ownedGuards(player);
		for (GuardEntity guard : guards) {
			guard.setHoldPosition(true);
		}
		return reportOrders(source, guards.size(), Component.translatable("commands.guardvillagers.hold", guards.size()));
	}

	private static int clearTargets(CommandSourceStack source) throws CommandSyntaxException {
		ServerPlayer player = source.getPlayerOrException();
		List<GuardEntity> guards = ownedGuards(player);
		for (GuardEntity guard : guards) {
			guard.clearCombatTarget();
		}
		return reportOrders(source, guards.size(), Component.translatable("commands.guardvillagers.cleartargets", guards.size()));
	}

	private static int follow(CommandSourceStack source, String groupName) throws CommandSyntaxException {
		ServerPlayer player = source.getPlayerOrException();
		int changed = 0;
		for (GuardEntity guard : ownedGuards(player)) {
			if (groupName != null && !guard.getGroupName().equalsIgnoreCase(groupName.trim())) {
				continue;
			}
			guard.setStaying(false);
			guard.setFollowOverride(true);
			guard.clearCombatTarget();
			changed++;
		}
		if (groupName != null && changed == 0) {
			source.sendFailure(Component.translatable("commands.guardvillagers.follow.no_group", groupName.trim()));
			return 0;
		}
		int finalChanged = changed;
		Component message = groupName == null
				? Component.translatable("commands.guardvillagers.follow.all", finalChanged)
				: Component.translatable("commands.guardvillagers.follow.group", finalChanged, groupName.trim());
		return reportOrders(source, changed, message);
	}

	private static int zone(CommandSourceStack source, int radius) throws CommandSyntaxException {
		ServerPlayer player = source.getPlayerOrException();
		BlockPos home = player.blockPosition();
		int changed = 0;
		for (GuardEntity guard : player.level().getEntitiesOfClass(
				GuardEntity.class,
				player.getBoundingBox().inflate(ZONE_ASSIGN_RANGE),
				entity -> entity.isOwnedBy(player.getUUID()))) {
			guard.setHome(home, radius);
			guard.clearCombatTarget();
			changed++;
		}
		if (changed == 0) {
			source.sendFailure(Component.translatable("commands.guardvillagers.zone.none", (int) ZONE_ASSIGN_RANGE));
			return 0;
		}
		int finalChanged = changed;
		source.sendSuccess(() -> Component.translatable("commands.guardvillagers.zone", finalChanged, radius), false);
		return changed;
	}

	private static int addGroup(CommandSourceStack source) throws CommandSyntaxException {
		ServerPlayer player = source.getPlayerOrException();
		GuardTacticsState state = GuardTacticsManager.getState(source.getServer());
		GuardTacticsState.PlayerTactics tactics = state.getOrCreate(player.getUUID());
		int row = tactics.addGroup();
		if (row < 0) {
			source.sendFailure(Component.translatable("commands.guardvillagers.groups.limit", GuardTacticsState.PlayerTactics.MAX_GROUPS));
			GuardTacticsMenus.sendGroupResult(player, GuardGroupResultPayload.Action.ADD, -1, 0, 1, "commands.guardvillagers.groups.limit");
			return 0;
		}
		state.setDirty();
		GuardTacticsMenus.syncOwnedGuardRoster(player);
		GuardTacticsMenus.refreshOpenScreen(player);
		String name = tactics.getGroupName(row);
		source.sendSuccess(() -> Component.translatable("commands.guardvillagers.groups.added", row + 1, name), false);
		GuardTacticsMenus.sendGroupResult(player, GuardGroupResultPayload.Action.ADD, row, 1, 0, "");
		return row + 1;
	}

	private static int renameGroup(CommandSourceStack source, int row, String requestedName) throws CommandSyntaxException {
		ServerPlayer player = source.getPlayerOrException();
		if (requestedName == null || requestedName.isBlank()) {
			source.sendFailure(Component.translatable("commands.guardvillagers.groups.invalid_name"));
			GuardTacticsMenus.sendGroupResult(player, GuardGroupResultPayload.Action.RENAME, row, 0, 1, "commands.guardvillagers.groups.invalid_name");
			return 0;
		}
		GuardTacticsState state = GuardTacticsManager.getState(source.getServer());
		GuardTacticsState.PlayerTactics tactics = state.getOrDefault(player.getUUID());
		if (row >= tactics.groupCount()) {
			source.sendFailure(Component.translatable("commands.guardvillagers.groups.no_such_row", row + 1, tactics.groupCount()));
			GuardTacticsMenus.sendGroupResult(player, GuardGroupResultPayload.Action.RENAME, row, 0, 1, "commands.guardvillagers.groups.no_such_row");
			return 0;
		}
		tactics = state.getOrCreate(player.getUUID());
		tactics.setGroupName(row, requestedName);
		String normalizedName = tactics.getGroupName(row);
		for (GuardEntity guard : ownedGuards(player)) {
			if (guard.getGroupIndex() == row) {
				guard.setGroupName(normalizedName);
			}
		}
		state.setDirty();
		GuardTacticsMenus.syncOwnedGuardRoster(player);
		GuardTacticsMenus.refreshOpenScreen(player);
		source.sendSuccess(() -> Component.translatable("commands.guardvillagers.groups.renamed", row + 1, normalizedName), false);
		GuardTacticsMenus.sendGroupResult(player, GuardGroupResultPayload.Action.RENAME, row, 1, 0, "");
		return Command.SINGLE_SUCCESS;
	}

	private static int assignMany(CommandContext<CommandSourceStack> context) throws CommandSyntaxException {
		CommandSourceStack source = context.getSource();
		int groupRow = IntegerArgumentType.getInteger(context, "groupRow") - 1;
		List<UUID> guardIds = parseUuidList(StringArgumentType.getString(context, "guardUuids"));
		if (guardIds == null) {
			source.sendFailure(Component.translatable("commands.guardvillagers.groups.invalid_uuids", MAX_BATCH_ASSIGN));
			GuardTacticsMenus.sendGroupResult(source.getPlayerOrException(), GuardGroupResultPayload.Action.ASSIGN, groupRow, 0, 1,
					"commands.guardvillagers.groups.invalid_uuids");
			return 0;
		}
		return assign(source, guardIds, groupRow);
	}

	/** @param groupRow zero-based row, or {@code -1} to unassign */
	private static int assign(CommandSourceStack source, List<UUID> guardIds, int groupRow) throws CommandSyntaxException {
		ServerPlayer player = source.getPlayerOrException();
		String groupName = "Unassigned";
		if (groupRow >= 0) {
			GuardTacticsState.PlayerTactics tactics = GuardTacticsManager.getState(source.getServer()).getOrDefault(player.getUUID());
			if (groupRow >= tactics.groupCount()) {
				source.sendFailure(Component.translatable("commands.guardvillagers.groups.no_such_row", groupRow + 1, tactics.groupCount()));
				GuardTacticsMenus.sendGroupResult(player, GuardGroupResultPayload.Action.ASSIGN, groupRow, 0, guardIds.size(),
						"commands.guardvillagers.groups.no_such_row");
				return 0;
			}
			groupName = tactics.getGroupName(groupRow);
		}

		Map<UUID, GuardEntity> owned = ownedGuards(player).stream()
				.collect(Collectors.toMap(GuardEntity::getUUID, Function.identity(), (first, second) -> first));
		int changed = 0;
		for (UUID guardId : guardIds) {
			GuardEntity guard = owned.get(guardId);
			if (guard == null) {
				continue;
			}
			guard.setGroupIndex(groupRow);
			guard.setGroupName(groupName);
			guard.updateGroupNameplate();
			changed++;
		}
		if (changed == 0) {
			source.sendFailure(Component.translatable("commands.guardvillagers.groups.guard_not_found"));
			GuardTacticsMenus.sendGroupResult(player, GuardGroupResultPayload.Action.ASSIGN, groupRow, 0, guardIds.size(),
					"commands.guardvillagers.groups.guard_not_found");
			return 0;
		}

		GuardTacticsMenus.syncOwnedGuardRoster(player);
		int finalChanged = changed;
		Component message;
		if (guardIds.size() == 1) {
			message = groupRow < 0
					? Component.translatable("commands.guardvillagers.groups.unassigned")
					: Component.translatable("commands.guardvillagers.groups.assigned", groupRow + 1, groupName);
		} else {
			message = groupRow < 0
					? Component.translatable("commands.guardvillagers.groups.unassigned_many", finalChanged, guardIds.size())
					: Component.translatable("commands.guardvillagers.groups.assigned_many", finalChanged, guardIds.size(), groupRow + 1, groupName);
		}
		source.sendSuccess(() -> message, false);
		GuardTacticsMenus.sendGroupResult(player, GuardGroupResultPayload.Action.ASSIGN, groupRow, changed, guardIds.size() - changed,
				changed < guardIds.size() ? "commands.guardvillagers.groups.guard_not_found" : "");
		return changed;
	}

	/** Space or comma separated UUIDs, at most {@link #MAX_BATCH_ASSIGN}. Returns {@code null} when malformed. */
	static List<UUID> parseUuidList(String input) {
		if (input == null || input.isBlank()) {
			return null;
		}
		Set<UUID> ids = new LinkedHashSet<>();
		for (String token : input.trim().split("[\\s,]+")) {
			if (ids.size() >= MAX_BATCH_ASSIGN) {
				return null;
			}
			try {
				ids.add(UUID.fromString(token));
			} catch (IllegalArgumentException exception) {
				return null;
			}
		}
		return new ArrayList<>(ids);
	}

	private static int count(CommandSourceStack source) throws CommandSyntaxException {
		ServerPlayer player = source.getPlayerOrException();
		MinecraftServer server = source.getServer();
		int owned = GuardOwnershipIndex.countOwnedGuards(server, player.getUUID());
		int loaded = GuardOwnershipIndex.getOwnedGuards(server, player.getUUID()).size();
		source.sendSuccess(() -> Component.translatable("commands.guardvillagers.count", owned, loaded,
				GuardSecurityLimits.MAX_PERSONAL_GUARDS_PER_PLAYER), false);
		return owned;
	}

	private static int changeTrust(CommandSourceStack source, Collection<NameAndId> targets, boolean add) throws CommandSyntaxException {
		ServerPlayer player = source.getPlayerOrException();
		int changed = 0;
		for (NameAndId target : targets) {
			GuardDiplomacyState.Change result = add
					? GuardDiplomacyManager.trust(source.getServer(), player.getUUID(), target.id())
					: GuardDiplomacyManager.untrust(source.getServer(), player.getUUID(), target.id());
			switch (result) {
				case ADDED -> {
					changed++;
					source.sendSuccess(() -> Component.translatable("commands.guardvillagers.trust.added", target.name()), false);
				}
				case REMOVED -> {
					changed++;
					source.sendSuccess(() -> Component.translatable("commands.guardvillagers.trust.removed", target.name()), false);
				}
				case ALREADY_PRESENT -> source.sendFailure(Component.translatable("commands.guardvillagers.trust.already", target.name()));
				case NOT_PRESENT -> source.sendFailure(Component.translatable("commands.guardvillagers.trust.not_trusted", target.name()));
				case SELF -> source.sendFailure(Component.translatable("commands.guardvillagers.trust.self"));
				case LIST_FULL -> {
					source.sendFailure(Component.translatable("commands.guardvillagers.trust.full", GuardDiplomacyState.MAX_LIST_SIZE));
					return changed;
				}
				case STATE_FULL -> {
					source.sendFailure(Component.translatable("commands.guardvillagers.trust.server_full"));
					return changed;
				}
			}
		}
		return changed;
	}

	private static int listTrusted(CommandSourceStack source) throws CommandSyntaxException {
		ServerPlayer player = source.getPlayerOrException();
		Set<UUID> trusted = GuardDiplomacyManager.getWhitelist(source.getServer(), player.getUUID());
		if (trusted.isEmpty()) {
			source.sendSuccess(() -> Component.translatable("commands.guardvillagers.trust.list.empty"), false);
			return 0;
		}
		String names = trusted.stream()
				.map(id -> source.getServer().services().nameToIdCache().get(id).map(NameAndId::name).orElse(id.toString()))
				.sorted(String.CASE_INSENSITIVE_ORDER)
				.collect(Collectors.joining(", "));
		source.sendSuccess(() -> Component.translatable("commands.guardvillagers.trust.list", trusted.size(),
				GuardDiplomacyState.MAX_LIST_SIZE, names), false);
		return trusted.size();
	}

	private static int getReputation(CommandSourceStack source, ServerPlayer target) {
		double value = GuardReputationManager.getEffectiveReputation(target);
		source.sendSuccess(() -> Component.translatable("commands.guardvillagers.reputation.get",
				target.getDisplayName(),
				GuardReputationManager.formatReputation(value),
				GuardReputationManager.formatReputation(GuardReputationManager.TRUST_THRESHOLD),
				GuardReputationManager.formatReputation(GuardReputationManager.HOSTILE_THRESHOLD)), false);
		return (int) Math.round(value * 100.0D);
	}

	private static int setReputation(CommandSourceStack source, ServerPlayer target, String input) {
		Double parsedValue = GuardReputationManager.parseReputationInput(input);
		if (parsedValue == null) {
			source.sendFailure(Component.translatable("commands.guardvillagers.reputation.invalid", input));
			return 0;
		}

		GuardReputationManager.setReputation(source.getServer(), target.getUUID(), parsedValue);
		String valueText = GuardReputationManager.formatReputation(parsedValue);
		if (target == source.getPlayer()) {
			source.sendSuccess(() -> Component.translatable("commands.guardvillagers.reputation.set.self", valueText), true);
		} else {
			source.sendSuccess(() -> Component.translatable("commands.guardvillagers.reputation.set.other", target.getDisplayName(), valueText), true);
			target.sendSystemMessage(Component.translatable("commands.guardvillagers.reputation.notify", valueText));
		}
		return Command.SINGLE_SUCCESS;
	}

	private static int debug(CommandSourceStack source, int requestedRange) throws CommandSyntaxException {
		ServerPlayer player = source.getPlayerOrException();
		double effectiveRange = GuardDebugManager.toggle(player, requestedRange);
		if (effectiveRange < 0.0D) {
			source.sendSuccess(() -> Component.translatable("commands.guardvillagers.debug.disabled"), false);
		} else {
			int shownRange = (int) Math.round(effectiveRange);
			source.sendSuccess(() -> Component.translatable("commands.guardvillagers.debug.enabled", shownRange), false);
		}
		return Command.SINGLE_SUCCESS;
	}

	private static int reportOrders(CommandSourceStack source, int changed, Component message) {
		if (changed == 0) {
			source.sendFailure(Component.translatable("commands.guardvillagers.no_loaded_guards"));
			return 0;
		}
		source.sendSuccess(() -> message, false);
		return changed;
	}

	private static List<GuardEntity> ownedGuards(ServerPlayer player) {
		return GuardOwnershipIndex.getOwnedGuards(player.level().getServer(), player.getUUID());
	}
}
