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
import com.guardvillagers.entity.GuardBehavior;
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
import net.minecraft.core.BlockPos;
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
		/** Guards whose behavior a raid response changed and has not put back yet. */
		public static final int MAX_RAID_BEHAVIORS = 256;

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
				Codec.BOOL.optionalFieldOf("autoCommand").forGetter(OwnerCommand::autoCommandChoice),
				SavedOrder.CODEC.listOf(0, OrderBook.MAX_ACTIVE).optionalFieldOf("orders", List.of()).forGetter(command -> command.orders),
				Codec.BOOL.optionalFieldOf("voiceConsent").forGetter(command -> Optional.ofNullable(command.voiceConsent)),
				RaidEntry.CODEC.listOf(0, MAX_RAID_BEHAVIORS).optionalFieldOf("raidBehaviors", List.of())
						.forGetter(OwnerCommand::raidEntryList),
				SavedOrder.CODEC.listOf().optionalFieldOf("pendingCleanup", List.of()).forGetter(command -> command.pendingCleanup)
		).apply(instance, OwnerCommand::new));


		private UUID commander;
		private UUID pinnedCommander;
		private final Map<Integer, UUID> captainByGroup = new HashMap<>();
		private final Set<UUID> pinnedCaptains = new HashSet<>();
		private Boolean autoCommand;
		private List<SavedOrder> pendingCleanup = List.of();
		private List<SavedOrder> orders = List.of();
		/** Null until the player chooses; then the config default no longer applies. */
		private Boolean voiceConsent;
		/** Oldest first, so the oldest entry goes when full. */
		private final Map<UUID, RaidBehavior> raidBehaviors = new LinkedHashMap<>();

		public OwnerCommand() {
		}

		private OwnerCommand(Optional<UUID> commander, Optional<UUID> pinnedCommander, List<CaptainEntry> captains,
				List<UUID> pinnedCaptains, Optional<Boolean> autoCommand, List<SavedOrder> orders, Optional<Boolean> voiceConsent,
				List<RaidEntry> raidEntries, List<SavedOrder> pendingCleanup) {
			this.commander = commander.orElse(null);
			this.pinnedCommander = pinnedCommander.orElse(null);
			for (CaptainEntry entry : captains) {
				this.captainByGroup.put(entry.group(), entry.guard());
			}
			this.pinnedCaptains.addAll(pinnedCaptains);
			this.autoCommand = autoCommand.orElse(null);
			this.pendingCleanup = List.copyOf(pendingCleanup);
			this.orders = List.copyOf(orders);
			this.voiceConsent = voiceConsent.orElse(null);
			for (RaidEntry entry : raidEntries) {
				entry.toBehavior().ifPresent(behavior -> this.raidBehaviors.put(entry.guard(), behavior));
			}
		}

		public OwnerCommand copy() {
			OwnerCommand copy = new OwnerCommand();
			copy.commander = this.commander;
			copy.pinnedCommander = this.pinnedCommander;
			copy.captainByGroup.putAll(this.captainByGroup);
			copy.pinnedCaptains.addAll(this.pinnedCaptains);
			copy.autoCommand = this.autoCommand;
			copy.orders = this.orders;
			copy.pendingCleanup = this.pendingCleanup;
			copy.voiceConsent = this.voiceConsent;
			copy.raidBehaviors.putAll(this.raidBehaviors);
			return copy;
		}

		/** True when the row carries something only the player can set (rank fields are recomputed by elections). */
		boolean holdsPlayerChoices() {
			return this.voiceConsent != null || this.autoCommand != null || this.pinnedCommander != null
					|| !this.pinnedCaptains.isEmpty() || !this.orders.isEmpty() || !this.raidBehaviors.isEmpty()
					|| !this.pendingCleanup.isEmpty();
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

		/** @return false when the pin did not fit ({@link #MAX_CAPTAINS} pins already) */
		public boolean pinCaptain(UUID guard) {
			if (this.pinnedCaptains.contains(guard)) {
				return true;
			}
			if (this.pinnedCaptains.size() >= MAX_CAPTAINS) {
				return false;
			}
			this.pinnedCaptains.add(guard);
			return true;
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
			return this.autoCommand == null || this.autoCommand;
		}

		public Optional<Boolean> autoCommandChoice() {
			return Optional.ofNullable(this.autoCommand);
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

		public List<ActiveOrder> pendingCleanup() {
			return this.pendingCleanup.stream().flatMap(saved -> saved.toActive().stream()).toList();
		}

		public void queueCleanup(ActiveOrder order) {
			SavedOrder saved = SavedOrder.of(order);
			if (this.pendingCleanup.contains(saved)) return;
			List<SavedOrder> pending = new ArrayList<>(this.pendingCleanup);
			pending.add(saved);
			this.pendingCleanup = List.copyOf(pending);
		}

		public void setPendingCleanup(List<ActiveOrder> pending) {
			this.pendingCleanup = pending.stream().map(SavedOrder::of).toList();
		}

		public Map<UUID, RaidBehavior> raidBehaviors() {
			return Collections.unmodifiableMap(this.raidBehaviors);
		}

		/**
		 * Notes that a raid response set {@code guard} to {@code applied}. The first original is kept across repeated
		 * calls and raids, so the guard goes back to what the player had chosen.
		 */
		public boolean recordRaidBehavior(UUID guard, GuardBehavior original, GuardBehavior applied) {
			return recordRaidBehavior(guard, original, applied, null, null);
		}

		public boolean recordRaidBehavior(UUID guard, GuardBehavior original, GuardBehavior applied, String dimension, BlockPos raidCenter) {
			RaidBehavior existing = this.raidBehaviors.get(guard);
			if (existing != null) {
				if (existing.applied() == applied) {
					return false;
				}
				this.raidBehaviors.put(guard, new RaidBehavior(existing.original(), applied, existing.dimension(), existing.raidCenter(), existing.manuallyChanged()));
				return true;
			}
			if (this.raidBehaviors.size() >= MAX_RAID_BEHAVIORS) {
				this.raidBehaviors.remove(this.raidBehaviors.keySet().iterator().next());
			}
			this.raidBehaviors.put(guard, new RaidBehavior(original, applied, dimension, raidCenter, false));
			return true;
		}

		public void markRaidBehaviorManual(UUID guard) {
			RaidBehavior existing = this.raidBehaviors.get(guard);
			if (existing != null && !existing.manuallyChanged()) {
				this.raidBehaviors.put(guard, new RaidBehavior(existing.original(), existing.applied(),
						existing.dimension(), existing.raidCenter(), true));
			}
		}

		public void clearRaidBehavior(UUID guard) {
			this.raidBehaviors.remove(guard);
		}

		private List<RaidEntry> raidEntryList() {
			List<RaidEntry> list = new ArrayList<>(this.raidBehaviors.size());
			for (Map.Entry<UUID, RaidBehavior> entry : this.raidBehaviors.entrySet()) {
				list.add(new RaidEntry(entry.getKey(), entry.getValue().original().name(), entry.getValue().applied().name(),
						Optional.ofNullable(entry.getValue().dimension()), Optional.ofNullable(entry.getValue().raidCenter()),
						entry.getValue().manuallyChanged()));
			}
			return list;
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

	/** A guard's behavior before a raid response changed it, and what the raid set. */
	public record RaidBehavior(GuardBehavior original, GuardBehavior applied, String dimension, BlockPos raidCenter, boolean manuallyChanged) {
		public RaidBehavior(GuardBehavior original, GuardBehavior applied) {
			this(original, applied, null, null, false);
		}
	}

	private record RaidEntry(UUID guard, String original, String applied, Optional<String> dimension, Optional<BlockPos> raidCenter, boolean manuallyChanged) {
		static final Codec<RaidEntry> CODEC = RecordCodecBuilder.create(instance -> instance.group(
				UUIDUtil.STRING_CODEC.fieldOf("guard").forGetter(RaidEntry::guard),
				Codec.string(1, 32).fieldOf("original").forGetter(RaidEntry::original),
				Codec.string(1, 32).fieldOf("applied").forGetter(RaidEntry::applied),
				Codec.STRING.optionalFieldOf("dimension").forGetter(RaidEntry::dimension),
				BlockPos.CODEC.optionalFieldOf("raidCenter").forGetter(RaidEntry::raidCenter),
				Codec.BOOL.optionalFieldOf("manuallyChanged", false).forGetter(RaidEntry::manuallyChanged)
		).apply(instance, RaidEntry::new));

		Optional<RaidBehavior> toBehavior() {
			try {
				return Optional.of(new RaidBehavior(GuardBehavior.valueOf(this.original), GuardBehavior.valueOf(this.applied),
						this.dimension.orElse(null), this.raidCenter.orElse(null), this.manuallyChanged));
			} catch (IllegalArgumentException unknown) {
				return Optional.empty();
			}
		}
	}

	/** Flat, codec-friendly form of an {@link ActiveOrder}; unknown wire names are dropped on load. */
	record SavedOrder(long id, String action, String scopeKind, Optional<String> scopeName, Optional<String> formation,
			Optional<String> target, Optional<Integer> radius, String source, long issuedAt, long expiresAt, Optional<List<UUID>> recipients) {
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
				Codec.LONG.fieldOf("expiresAt").forGetter(SavedOrder::expiresAt),
				UUIDUtil.STRING_CODEC.listOf().optionalFieldOf("recipients").forGetter(SavedOrder::recipients)
		).apply(instance, SavedOrder::new));

		static SavedOrder of(ActiveOrder active) {
			Order order = active.order();
			return new SavedOrder(active.id(), order.action().wireName(), order.scope().kind().wireName(),
					Optional.ofNullable(order.scope().name()),
					order.formation().map(type -> type.name().toLowerCase(Locale.ROOT)),
					order.target().map(TargetRef::wireName),
					order.radius().isPresent() ? Optional.of(order.radius().getAsInt()) : Optional.empty(),
					active.source().name(), active.issuedAtTick(), active.expiresAtTick(), active.recipients().map(List::copyOf));
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
				return Optional.of(new ActiveOrder(this.id, order, OrderSource.valueOf(this.source), this.issuedAt, this.expiresAt, this.recipients.map(Set::copyOf)));
			} catch (IllegalArgumentException exception) {
				return Optional.empty();
			}
		}
	}
}
