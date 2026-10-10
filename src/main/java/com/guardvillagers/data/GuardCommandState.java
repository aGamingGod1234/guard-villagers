package com.guardvillagers.data;

import com.guardvillagers.GuardSecurityLimits;
import com.guardvillagers.command.order.Action;
import com.guardvillagers.command.order.ActiveOrder;
import com.guardvillagers.command.order.Order;
import com.guardvillagers.command.order.OrderBook;
import com.guardvillagers.command.order.OrderSource;
import com.guardvillagers.command.order.Scope;
import com.guardvillagers.command.order.TargetRef;
import com.guardvillagers.entity.FormationType;
import com.mojang.serialization.Codec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.OptionalInt;
import java.util.Set;
import java.util.UUID;
import net.minecraft.core.UUIDUtil;
import net.minecraft.util.datafix.DataFixTypes;
import net.minecraft.world.level.saveddata.SavedData;
import net.minecraft.world.level.saveddata.SavedDataType;

/**
 * Per-owner chain of command ({@code guardvillagers:command}): current and pinned commander, captain per tactics group,
 * pinned captains, the commander's auto-command switch and the active player orders. This is the source of truth
 * while guards are unloaded; loaded guards whose NBT rank disagrees are corrected by the next election.
 * Bounded at {@link #MAX_OWNERS}; when full, the least recently written row that holds no player choice is dropped
 * first. Reads ({@link #peek}, {@link #getOrDefault}) never reorder or create rows. Server thread only.
 */
public final class GuardCommandState extends SavedData {
	public static final int MAX_CAPTAINS = GuardTacticsState.PlayerTactics.MAX_GROUPS;
	/** Rows are small, so this is well above the tactics cap; eviction prefers rows without player choices. */
	public static final int MAX_OWNERS = GuardSecurityLimits.MAX_TACTICS_PLAYERS * 4;

	public static final Codec<GuardCommandState> CODEC = RecordCodecBuilder.create(instance -> instance.group(
			Codec.unboundedMap(UUIDUtil.STRING_CODEC, OwnerCommand.CODEC).optionalFieldOf("owners", Map.of())
					.forGetter(GuardCommandState::entriesForCodec)
	).apply(instance, GuardCommandState::new));

	public static final SavedDataType<GuardCommandState> TYPE = new SavedDataType<>(
			GuardSavedData.id("command"),
			GuardCommandState::new,
			CODEC,
			DataFixTypes.LEVEL);

	private final Map<UUID, OwnerCommand> entries;

	public GuardCommandState() {
		this(Map.of());
	}

	private GuardCommandState(Map<UUID, OwnerCommand> entries) {
		// Insertion order, refreshed by getOrCreate: a plain get() must not restructure the map.
		this.entries = new LinkedHashMap<>(Math.max(16, entries.size()));
		for (Map.Entry<UUID, OwnerCommand> entry : entries.entrySet()) {
			this.putBounded(entry.getKey(), entry.getValue().copy());
		}
	}

	public OwnerCommand getOrCreate(UUID ownerId) {
		OwnerCommand existing = this.entries.remove(ownerId);
		if (existing != null) {
			this.entries.put(ownerId, existing);
			return existing;
		}
		OwnerCommand created = new OwnerCommand();
		this.putBounded(ownerId, created);
		this.setDirty();
		return created;
	}

	/** Read-only view; never creates a row. */
	public OwnerCommand getOrDefault(UUID ownerId) {
		OwnerCommand existing = this.entries.get(ownerId);
		return existing == null ? new OwnerCommand() : existing.copy();
	}

	/** The live row, or {@code null}. Callers that mutate it must {@link #setDirty()}. */
	public OwnerCommand peek(UUID ownerId) {
		return this.entries.get(ownerId);
	}

	public int trackedOwnerCount() {
		return this.entries.size();
	}

	private Map<UUID, OwnerCommand> entriesForCodec() {
		return Collections.unmodifiableMap(this.entries);
	}

	private void putBounded(UUID ownerId, OwnerCommand command) {
		if (!this.entries.containsKey(ownerId) && this.entries.size() >= MAX_OWNERS) {
			UUID victim = null;
			for (Map.Entry<UUID, OwnerCommand> entry : this.entries.entrySet()) {
				if (!entry.getValue().holdsPlayerChoices()) {
					victim = entry.getKey();
					break;
				}
			}
			if (victim == null) {
				Iterator<UUID> oldest = this.entries.keySet().iterator();
				victim = oldest.hasNext() ? oldest.next() : null;
			}
			if (victim != null) {
				this.entries.remove(victim);
			}
		}
		this.entries.put(ownerId, command);
	}

	/** One owner's chain of command. Mutators do not mark the state dirty; callers do. */
	public static final class OwnerCommand {
		private static final Codec<CaptainEntry> CAPTAIN_CODEC = RecordCodecBuilder.create(instance -> instance.group(
				Codec.intRange(0, MAX_CAPTAINS - 1).fieldOf("group").forGetter(CaptainEntry::group),
				UUIDUtil.STRING_CODEC.fieldOf("guard").forGetter(CaptainEntry::guard)
		).apply(instance, CaptainEntry::new));

		public static final Codec<OwnerCommand> CODEC = RecordCodecBuilder.create(instance -> instance.group(
				UUIDUtil.STRING_CODEC.optionalFieldOf("commander").forGetter(command -> Optional.ofNullable(command.commander)),
				UUIDUtil.STRING_CODEC.optionalFieldOf("pinnedCommander").forGetter(command -> Optional.ofNullable(command.pinnedCommander)),
				CAPTAIN_CODEC.listOf(0, MAX_CAPTAINS).optionalFieldOf("captains", List.of()).forGetter(OwnerCommand::captainList),
				UUIDUtil.STRING_CODEC.listOf(0, MAX_CAPTAINS).optionalFieldOf("pinnedCaptains", List.of())
						.forGetter(command -> List.copyOf(command.pinnedCaptains)),
				Codec.BOOL.optionalFieldOf("autoCommand", true).forGetter(OwnerCommand::autoCommand),
				SavedOrder.CODEC.listOf(0, OrderBook.MAX_ACTIVE).optionalFieldOf("orders", List.of()).forGetter(command -> command.orders),
				Codec.BOOL.optionalFieldOf("voiceConsent").forGetter(command -> Optional.ofNullable(command.voiceConsent))
		).apply(instance, OwnerCommand::new));

		private UUID commander;
		private UUID pinnedCommander;
		private final Map<Integer, UUID> captainByGroup = new HashMap<>();
		private final Set<UUID> pinnedCaptains = new HashSet<>();
		private boolean autoCommand = true;
		private List<SavedOrder> orders = List.of();
		/** Null until the player chooses; then the config default no longer applies. */
		private Boolean voiceConsent;

		public OwnerCommand() {
		}

		private OwnerCommand(Optional<UUID> commander, Optional<UUID> pinnedCommander, List<CaptainEntry> captains,
				List<UUID> pinnedCaptains, boolean autoCommand, List<SavedOrder> orders, Optional<Boolean> voiceConsent) {
			this.commander = commander.orElse(null);
			this.pinnedCommander = pinnedCommander.orElse(null);
			for (CaptainEntry entry : captains) {
				this.captainByGroup.put(entry.group(), entry.guard());
			}
			this.pinnedCaptains.addAll(pinnedCaptains);
			this.autoCommand = autoCommand;
			this.orders = List.copyOf(orders);
			this.voiceConsent = voiceConsent.orElse(null);
		}

		public OwnerCommand copy() {
			OwnerCommand copy = new OwnerCommand();
			copy.commander = this.commander;
			copy.pinnedCommander = this.pinnedCommander;
			copy.captainByGroup.putAll(this.captainByGroup);
			copy.pinnedCaptains.addAll(this.pinnedCaptains);
			copy.autoCommand = this.autoCommand;
			copy.orders = this.orders;
			copy.voiceConsent = this.voiceConsent;
			return copy;
		}

		/** True when the row carries something only the player can set (rank fields are recomputed by elections). */
		boolean holdsPlayerChoices() {
			return this.voiceConsent != null || !this.autoCommand || this.pinnedCommander != null
					|| !this.pinnedCaptains.isEmpty() || !this.orders.isEmpty();
		}

		public UUID commander() {
			return this.commander;
		}

		public void setCommander(UUID commander) {
			this.commander = commander;
		}

		public UUID pinnedCommander() {
			return this.pinnedCommander;
		}

		public void setPinnedCommander(UUID pinnedCommander) {
			this.pinnedCommander = pinnedCommander;
		}

		public Map<Integer, UUID> captainByGroup() {
			return Collections.unmodifiableMap(this.captainByGroup);
		}

		public void setCaptains(Map<Integer, UUID> captains) {
			this.captainByGroup.clear();
			for (Map.Entry<Integer, UUID> entry : captains.entrySet()) {
				if (entry.getKey() >= 0 && entry.getKey() < MAX_CAPTAINS) {
					this.captainByGroup.put(entry.getKey(), entry.getValue());
				}
			}
		}

		public Set<UUID> pinnedCaptains() {
			return Collections.unmodifiableSet(this.pinnedCaptains);
		}

		public void pinCaptain(UUID guard) {
			if (this.pinnedCaptains.size() < MAX_CAPTAINS) {
				this.pinnedCaptains.add(guard);
			}
		}

		public void unpin(UUID guard) {
			this.pinnedCaptains.remove(guard);
			if (guard.equals(this.pinnedCommander)) {
				this.pinnedCommander = null;
			}
		}

		public void clearPins() {
			this.pinnedCaptains.clear();
			this.pinnedCommander = null;
		}

		public Optional<Boolean> voiceConsent() {
			return Optional.ofNullable(this.voiceConsent);
		}

		public void setVoiceConsent(boolean granted) {
			this.voiceConsent = granted;
		}

		public boolean autoCommand() {
			return this.autoCommand;
		}

		public void setAutoCommand(boolean autoCommand) {
			this.autoCommand = autoCommand;
		}

		public List<ActiveOrder> orders() {
			List<ActiveOrder> restored = new ArrayList<>(this.orders.size());
			for (SavedOrder saved : this.orders) {
				saved.toActive().ifPresent(restored::add);
			}
			return restored;
		}

		public void setOrders(List<ActiveOrder> active) {
			List<SavedOrder> saved = new ArrayList<>(Math.min(active.size(), OrderBook.MAX_ACTIVE));
			for (ActiveOrder order : active) {
				if (saved.size() >= OrderBook.MAX_ACTIVE) {
					break;
				}
				saved.add(SavedOrder.of(order));
			}
			this.orders = List.copyOf(saved);
		}

		private List<CaptainEntry> captainList() {
			List<CaptainEntry> list = new ArrayList<>(this.captainByGroup.size());
			for (Map.Entry<Integer, UUID> entry : this.captainByGroup.entrySet()) {
				list.add(new CaptainEntry(entry.getKey(), entry.getValue()));
			}
			return list;
		}
	}

	private record CaptainEntry(int group, UUID guard) {
	}

	/** Flat, codec-friendly form of an {@link ActiveOrder}; unknown wire names are dropped on load. */
	record SavedOrder(long id, String action, String scopeKind, Optional<String> scopeName, Optional<String> formation,
			Optional<String> target, Optional<Integer> radius, String source, long issuedAt, long expiresAt) {
		static final Codec<SavedOrder> CODEC = RecordCodecBuilder.create(instance -> instance.group(
				Codec.LONG.fieldOf("id").forGetter(SavedOrder::id),
				Codec.string(1, 32).fieldOf("action").forGetter(SavedOrder::action),
				Codec.string(1, 16).fieldOf("scope").forGetter(SavedOrder::scopeKind),
				Codec.string(1, 64).optionalFieldOf("scopeName").forGetter(SavedOrder::scopeName),
				Codec.string(1, 16).optionalFieldOf("formation").forGetter(SavedOrder::formation),
				Codec.string(1, 32).optionalFieldOf("target").forGetter(SavedOrder::target),
				Codec.intRange(Order.MIN_RADIUS, Order.MAX_RADIUS).optionalFieldOf("radius").forGetter(SavedOrder::radius),
				Codec.string(1, 32).fieldOf("source").forGetter(SavedOrder::source),
				Codec.LONG.fieldOf("issuedAt").forGetter(SavedOrder::issuedAt),
				Codec.LONG.fieldOf("expiresAt").forGetter(SavedOrder::expiresAt)
		).apply(instance, SavedOrder::new));

		static SavedOrder of(ActiveOrder active) {
			Order order = active.order();
			return new SavedOrder(active.id(), order.action().wireName(), order.scope().kind().wireName(),
					Optional.ofNullable(order.scope().name()),
					order.formation().map(type -> type.name().toLowerCase(Locale.ROOT)),
					order.target().map(TargetRef::wireName),
					order.radius().isPresent() ? Optional.of(order.radius().getAsInt()) : Optional.empty(),
					active.source().name(), active.issuedAtTick(), active.expiresAtTick());
		}

		Optional<ActiveOrder> toActive() {
			try {
				Optional<Action> parsedAction = Action.fromWireName(this.action);
				Optional<Scope.Kind> kind = Scope.Kind.fromWireName(this.scopeKind);
				if (parsedAction.isEmpty() || kind.isEmpty()) {
					return Optional.empty();
				}
				Scope scope = new Scope(kind.get(), this.scopeName.orElse(null));
				Optional<FormationType> parsedFormation = this.formation.map(name -> FormationType.valueOf(name.toUpperCase(Locale.ROOT)));
				Optional<TargetRef> parsedTarget = this.target.flatMap(TargetRef::fromWireName);
				OptionalInt parsedRadius = this.radius.map(OptionalInt::of).orElse(OptionalInt.empty());
				Order order = new Order(parsedAction.get(), scope, parsedFormation, parsedTarget, parsedRadius);
				return Optional.of(new ActiveOrder(this.id, order, OrderSource.valueOf(this.source), this.issuedAt, this.expiresAt));
			} catch (IllegalArgumentException exception) {
				return Optional.empty();
			}
		}
	}
}
