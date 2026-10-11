package com.guardvillagers.command;

import com.guardvillagers.GuardDebugManager;
import com.guardvillagers.GuardDiplomacyManager;
import com.guardvillagers.GuardOwnershipIndex;
import com.guardvillagers.GuardReputationManager;
import com.guardvillagers.GuardSecurityLimits;
import com.guardvillagers.GuardTacticsManager;
import com.guardvillagers.GuardVillagersMod;
import com.guardvillagers.command.commander.CommanderService;
import com.guardvillagers.command.commander.VoiceCommandBridge;
import com.guardvillagers.voice.VoicePipeline;
import com.guardvillagers.voice.VoiceRuntime;
import com.guardvillagers.command.hierarchy.GuardHierarchy;
import com.guardvillagers.data.GuardDiplomacyState;
import com.guardvillagers.data.GuardTacticsState;
import com.guardvillagers.entity.GuardEntity;
import com.guardvillagers.network.GuardGroupResultPayload;
import com.guardvillagers.shop.GuardShopScreenHandler;
import com.guardvillagers.tactics.GuardTacticsMenus;
import com.guardvillagers.tactics.GuardTacticsScreenHandler;
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
	/** Upper bound for one {@code groups assignmany} call; larger selections are split across calls by the client. */
	private static final int MAX_BATCH_ASSIGN = 1_024;
	private static final double APPOINT_RANGE = 8.0D;

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
						.then(Commands.literal("follow")
								.then(Commands.argument("groupRow", IntegerArgumentType.integer(0, MAX_GROUP_ROW))
										.executes(context -> orderGroup(context.getSource(), IntegerArgumentType.getInteger(context, "groupRow") - 1, true))))
						.then(Commands.literal("stay")
								.then(Commands.argument("groupRow", IntegerArgumentType.integer(0, MAX_GROUP_ROW))
										.executes(context -> orderGroup(context.getSource(), IntegerArgumentType.getInteger(context, "groupRow") - 1, false))))
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
				// Voice-commander text path: the same interpreter, order book and acknowledgements as voice.
				.then(Commands.literal("say")
						.then(Commands.argument("text", StringArgumentType.greedyString())
								.executes(context -> say(context.getSource(), StringArgumentType.getString(context, "text")))))
				.then(Commands.literal("appoint")
						// Without a uuid: the guard the player looks at. With one: any loaded owned guard (tactics screen).
						.then(Commands.literal("commander").executes(context -> appoint(context.getSource(), true, null))
								.then(Commands.argument("guardUuid", UuidArgument.uuid())
										.executes(context -> appoint(context.getSource(), true, UuidArgument.getUuid(context, "guardUuid")))))
						.then(Commands.literal("captain").executes(context -> appoint(context.getSource(), false, null))
								.then(Commands.argument("guardUuid", UuidArgument.uuid())
										.executes(context -> appoint(context.getSource(), false, UuidArgument.getUuid(context, "guardUuid")))))
						.then(Commands.literal("auto").executes(context -> appointAuto(context.getSource()))))
				.then(Commands.literal("commander")
						.then(Commands.literal("auto")
								.then(Commands.literal("on").executes(context -> commanderAuto(context.getSource(), true)))
								.then(Commands.literal("off").executes(context -> commanderAuto(context.getSource(), false)))))
				.then(Commands.literal("status").executes(context -> status(context.getSource())))
				.then(Commands.literal("voice")
						.then(Commands.literal("on").executes(context -> voiceConsent(context.getSource(), true)))
						.then(Commands.literal("off").executes(context -> voiceConsent(context.getSource(), false)))
						.then(Commands.literal("status").executes(context -> voiceStatus(context.getSource())))
						.then(Commands.literal("reload")
								.requires(Commands.hasPermission(Commands.LEVEL_GAMEMASTERS))
								.executes(context -> voiceReload(context.getSource()))))
				// Players your guards treat as friends: they will not be targeted, and they can ring your guards to a bell.
				.then(Commands.literal("trust")
						.then(Commands.literal("list").executes(context -> listTrusted(context.getSource())))
						// A plain word, not GameProfileArgument: that falls back to a blocking Mojang lookup on the
						// server thread for any name not in the cache, which any player could trigger at will.
						.then(Commands.literal("add")
								.then(Commands.argument("player", StringArgumentType.word())
										.suggests((context, builder) -> net.minecraft.commands.SharedSuggestionProvider.suggest(
												context.getSource().getOnlinePlayerNames(), builder))
										.executes(context -> changeTrust(context.getSource(),
												StringArgumentType.getString(context, "player"), true))))
						.then(Commands.literal("remove")
								.then(Commands.argument("player", StringArgumentType.word())
										.suggests((context, builder) -> net.minecraft.commands.SharedSuggestionProvider.suggest(
												trustedNames(context.getSource()), builder))
										.executes(context -> changeTrust(context.getSource(),
												StringArgumentType.getString(context, "player"), false)))))
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
		return stay(source, groupName, null);
	}

	private static int stay(CommandSourceStack source, String groupName, Integer row) throws CommandSyntaxException {
		ServerPlayer player = source.getPlayerOrException();
		List<GuardEntity> recipients = ownedGuards(player).stream()
				.filter(guard -> row != null ? guard.getGroupIndex() == row
						: groupName == null || guard.getGroupName().equalsIgnoreCase(groupName.trim())).toList();
		CommanderService.onTypedOrder(player, recipients);
		int changed = 0;
		for (GuardEntity guard : recipients) {
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
		CommanderService.onTypedOrder(player, guards);
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
		return follow(source, groupName, null);
	}

	private static int follow(CommandSourceStack source, String groupName, Integer row) throws CommandSyntaxException {
		ServerPlayer player = source.getPlayerOrException();
		List<GuardEntity> recipients = ownedGuards(player).stream()
				.filter(guard -> row != null ? guard.getGroupIndex() == row
						: groupName == null || guard.getGroupName().equalsIgnoreCase(groupName.trim())).toList();
		CommanderService.onTypedOrder(player, recipients);
		int changed = 0;
		for (GuardEntity guard : recipients) {
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

	/** Row 0 in the command explicitly targets unassigned guards; positive rows are stable group identities. */
	private static int orderGroup(CommandSourceStack source, int row, boolean following) throws CommandSyntaxException {
		ServerPlayer player = source.getPlayerOrException();
		var tactics = GuardTacticsManager.getState(source.getServer()).getOrDefault(player.getUUID());
		if (row >= tactics.groupCount()) {
			source.sendFailure(Component.translatable("commands.guardvillagers.groups.no_such_row", row + 1, tactics.groupCount()));
			return 0;
		}
		String name = row < 0 ? "Unassigned" : tactics.getGroupName(row);
		return following ? follow(source, name, row) : stay(source, name, row);
	}

	private static int addGroup(CommandSourceStack source) throws CommandSyntaxException {
		ServerPlayer player = source.getPlayerOrException();
		GuardTacticsState state = GuardTacticsManager.getState(source.getServer());
		if (!state.canCreate(player.getUUID())) {
			source.sendFailure(Component.translatable("commands.guardvillagers.groups.owner_limit"));
			GuardTacticsMenus.sendGroupResult(player, GuardGroupResultPayload.Action.ADD, -1, 0, 1,
					"commands.guardvillagers.groups.owner_limit");
			return 0;
		}
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
		if (!tactics.setGroupName(row, requestedName)) {
			source.sendFailure(Component.translatable("commands.guardvillagers.groups.duplicate_name"));
			GuardTacticsMenus.sendGroupResult(player, GuardGroupResultPayload.Action.RENAME, row, 0, 1,
					"commands.guardvillagers.groups.duplicate_name");
			return 0;
		}
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
				com.guardvillagers.GuardGameRules.displayLimit(player.level())), false);
		return owned;
	}

	private static int changeTrust(CommandSourceStack source, String name, boolean add) throws CommandSyntaxException {
		ServerPlayer player = source.getPlayerOrException();
		NameAndId target = resolveTrustTarget(source.getServer(), player.getUUID(), name);
		if (target == null) {
			source.sendFailure(Component.translatableWithFallback("commands.guardvillagers.trust.unknown",
					"%s is not online. Trust them while they are on, or use their UUID.", name));
			return 0;
		}
		return changeTrust(source, List.of(target), add);
	}

	/**
	 * Resolves a trust target without any network lookup: an online player by name, a UUID, or (for players already
	 * trusted) a name the server's profile cache knows for one of their UUIDs.
	 */
	private static NameAndId resolveTrustTarget(MinecraftServer server, UUID owner, String name) {
		ServerPlayer online = server.getPlayerList().getPlayerByName(name);
		if (online != null) {
			return online.nameAndId();
		}
		try {
			UUID id = UUID.fromString(name);
			return server.services().nameToIdCache().get(id).orElse(new NameAndId(id, name));
		} catch (IllegalArgumentException notUuid) {
			// Fall through to the trusted list.
		}
		for (UUID trusted : GuardDiplomacyManager.getWhitelist(server, owner)) {
			// get(UUID) only reads the cache; get(String) would query Mojang on a miss.
			NameAndId known = server.services().nameToIdCache().get(trusted).orElse(null);
			if (known != null && known.name().equalsIgnoreCase(name)) {
				return known;
			}
		}
		return null;
	}

	private static List<String> trustedNames(CommandSourceStack source) {
		ServerPlayer player = source.getPlayer();
		if (player == null) {
			return List.of();
		}
		List<String> names = new ArrayList<>();
		for (UUID trusted : GuardDiplomacyManager.getWhitelist(source.getServer(), player.getUUID())) {
			names.add(source.getServer().services().nameToIdCache().get(trusted).map(NameAndId::name).orElse(trusted.toString()));
		}
		return names;
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

	private static int say(CommandSourceStack source, String text) throws CommandSyntaxException {
		ServerPlayer player = source.getPlayerOrException();
		if (text.isBlank()) {
			return 0;
		}
		CommanderService.hear(player, text, true);
		return Command.SINGLE_SUCCESS;
	}

	/**
	 * Appoints an owned guard as commander or captain of its group: the one with {@code guardId}, or when that is null
	 * the one the player looks at (8 blocks).
	 */
	private static int appoint(CommandSourceStack source, boolean commander, UUID guardId) throws CommandSyntaxException {
		ServerPlayer player = source.getPlayerOrException();
		GuardEntity guard = guardId == null ? lookedAtOwnedGuard(player) : ownedGuard(player, guardId);
		if (guard == null) {
			source.sendFailure(guardId == null
					? Component.translatable("commands.guardvillagers.appoint.no_guard", (int) APPOINT_RANGE)
					: Component.translatable("commands.guardvillagers.groups.guard_not_found"));
			return 0;
		}
		GuardHierarchy.AppointResult result = commander
				? GuardHierarchy.appointCommander(source.getServer(), player.getUUID(), guard)
				: GuardHierarchy.appointCaptain(source.getServer(), player.getUUID(), guard);
		String name = guard.getName().getString();
		switch (result) {
			case APPOINTED -> {
				source.sendSuccess(() -> commander
					? Component.translatable("commands.guardvillagers.appoint.commander", name)
					: Component.translatable("commands.guardvillagers.appoint.captain", name, guard.getGroupName()), false);
				// Elections ran synchronously; refresh an open tactics screen now instead of on the next 1 s tick.
				if (player.containerMenu instanceof GuardTacticsScreenHandler) {
					GuardTacticsMenus.syncOwnedGuardRoster(player);
				}
			}
			case NO_GROUP -> {
				source.sendFailure(Component.translatable("commands.guardvillagers.appoint.no_group", name));
				return 0;
			}
			case NOT_OWNED -> {
				source.sendFailure(Component.translatable("commands.guardvillagers.appoint.no_guard", (int) APPOINT_RANGE));
				return 0;
			}
		}
		return Command.SINGLE_SUCCESS;
	}

	private static int appointAuto(CommandSourceStack source) throws CommandSyntaxException {
		ServerPlayer player = source.getPlayerOrException();
		GuardHierarchy.clearAppointments(source.getServer(), player.getUUID());
		source.sendSuccess(() -> Component.translatable("commands.guardvillagers.appoint.auto"), false);
		return Command.SINGLE_SUCCESS;
	}

	private static int commanderAuto(CommandSourceStack source, boolean enabled) throws CommandSyntaxException {
		ServerPlayer player = source.getPlayerOrException();
		CommanderService.setAutoCommand(source.getServer(), player.getUUID(), enabled);
		source.sendSuccess(() -> Component.translatable(enabled
				? "commands.guardvillagers.commander.auto_on" : "commands.guardvillagers.commander.auto_off"), false);
		return Command.SINGLE_SUCCESS;
	}

	private static int status(CommandSourceStack source) throws CommandSyntaxException {
		ServerPlayer player = source.getPlayerOrException();
		String line = CommanderService.statusLine(player);
		source.sendSuccess(() -> Component.literal(line), false);
		return Command.SINGLE_SUCCESS;
	}

	private static int voiceConsent(CommandSourceStack source, boolean granted) throws CommandSyntaxException {
		ServerPlayer player = source.getPlayerOrException();
		VoiceRuntime.setConsent(player.getUUID(), granted);
		source.sendSuccess(() -> Component.translatable(granted
				? "commands.guardvillagers.voice.on" : "commands.guardvillagers.voice.off"), false);
		return Command.SINGLE_SUCCESS;
	}

	private static int voiceStatus(CommandSourceStack source) throws CommandSyntaxException {
		ServerPlayer player = source.getPlayerOrException();
		VoicePipeline.Status status = VoiceRuntime.status(player.getUUID());
		source.sendSuccess(() -> Component.translatable("commands.guardvillagers.voice.status."
				+ status.name().toLowerCase(java.util.Locale.ROOT)), false);
		return status == VoicePipeline.Status.READY ? 1 : 0;
	}

	private static int voiceReload(CommandSourceStack source) {
		boolean reloaded = VoiceRuntime.reload().isPresent();
		VoiceCommandBridge.rebuildInterpreter();
		source.sendSuccess(() -> Component.translatable(reloaded
				? "commands.guardvillagers.voice.reloaded" : "commands.guardvillagers.voice.reload_failed"), true);
		return reloaded ? 1 : 0;
	}

	private static GuardEntity ownedGuard(ServerPlayer player, UUID guardId) {
		for (GuardEntity guard : ownedGuards(player)) {
			if (guard.getUUID().equals(guardId) && guard.isAlive()) {
				return guard;
			}
		}
		return null;
	}

	private static GuardEntity lookedAtOwnedGuard(ServerPlayer player) {
		net.minecraft.world.phys.Vec3 eye = player.getEyePosition();
		net.minecraft.world.phys.Vec3 end = eye.add(player.getViewVector(1.0F).scale(APPOINT_RANGE));
		var hit = net.minecraft.world.entity.projectile.ProjectileUtil.getEntityHitResult(player, eye, end,
				player.getBoundingBox().expandTowards(end.subtract(eye)).inflate(1.0D),
				entity -> entity instanceof GuardEntity guard && guard.isAlive() && guard.isOwnedBy(player.getUUID()),
				APPOINT_RANGE * APPOINT_RANGE);
		return hit != null && hit.getEntity() instanceof GuardEntity guard ? guard : null;
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
