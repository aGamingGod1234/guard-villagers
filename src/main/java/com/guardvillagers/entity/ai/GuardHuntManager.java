package com.guardvillagers.entity.ai;

import com.guardvillagers.GuardOwnershipIndex;
import com.guardvillagers.entity.GuardEntity;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.OwnableEntity;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.entity.ai.memory.MemoryModuleType;
import net.minecraft.world.entity.animal.equine.Llama;
import net.minecraft.world.entity.decoration.ArmorStand;
import net.minecraft.world.entity.monster.hoglin.Hoglin;
import net.minecraft.world.entity.monster.piglin.AbstractPiglin;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.entity.raid.Raider;
import net.minecraft.world.level.entity.EntityTypeTest;
import net.minecraft.world.phys.Vec3;

/** Server-thread, session-only owner orders. Expansion and pruning happen once per owner per five ticks. */
public final class GuardHuntManager {
	public static final int DURATION_TICKS = 600;
	public static final int REFRESH_TICKS = 5;
	public static final int MAX_TARGETS = 96;
	public static final double INITIAL_RADIUS = 24;
	public static final double LINK_RADIUS = 6;
	private static final int QUERY_LIMIT = 512;
	private static final int INSPECTION_BUDGET = 4096;
	private static final Map<UUID, Hunt> HUNTS = new HashMap<>();

	private GuardHuntManager() {}

	public static void punch(ServerPlayer owner, LivingEntity hit) {
		ServerLevel world = owner.level();
		if (!valid(hit, world) || hit == owner
				|| hit instanceof Player player && (!world.isPvpAllowed() || !owner.canHarmPlayer(player))) return;
		List<GuardEntity> guards = GuardOwnershipIndex.getOwnedGuards(world.getServer(), owner.getUUID());
		GuardEntity representative = guards.stream().filter(g -> g.level() == world).findFirst().orElse(null);
		if (representative == null || representative.isAlly(hit)) return;
		Hunt hunt = HUNTS.get(owner.getUUID());
		// A player punch is deliberately isolated, including from a previous mob hunt.
		if (hunt == null || hunt.world != world || hunt.expiresAt <= world.getGameTime()
				|| hunt.playerOnly != (hit instanceof Player)) {
			hunt = new Hunt(world, hit instanceof Player);
			HUNTS.put(owner.getUUID(), hunt);
		}
		hunt.expiresAt = world.getGameTime() + DURATION_TICKS;
		hunt.anchor = hit.position();
		if (hunt.playerOnly) {
			hunt.members.clear();
			hunt.pending.clear();
		}
		hunt.members.put(hit.getUUID(), true);
		hunt.pending.put(hit.getUUID(), true);
		trim(hunt, hit);
		refresh(hunt, representative, world.getGameTime());
		for (GuardEntity guard : guards) get(guard);
	}

	/** Joining is range-limited; a joined guard may finish the connected cluster beyond the first punch. */
	public static Hunt get(GuardEntity guard) {
		Hunt hunt = HUNTS.get(guard.getOwnerUuid());
		if (hunt == null || hunt.world != guard.level() || hunt.expiresAt <= guard.level().getGameTime()
				|| hunt.members.isEmpty() || guard.isStaying()) return null;
		double range = Math.max(8, guard.getAttributeValue(Attributes.FOLLOW_RANGE));
		if (!hunt.participants.contains(guard.getUUID()) && guard.distanceToSqr(hunt.anchor) > range * range) return null;
		hunt.participants.add(guard.getUUID());
		return hunt;
	}

	public static void tick(ServerLevel world) {
		long now = world.getGameTime();
		var iterator = HUNTS.entrySet().iterator();
		while (iterator.hasNext()) {
			var entry = iterator.next();
			Hunt hunt = entry.getValue();
			if (hunt.world != world) continue;
			ServerPlayer owner = world.getServer().getPlayerList().getPlayer(entry.getKey());
			if (owner == null || owner.level() != world || hunt.expiresAt <= now) {
				iterator.remove();
				continue;
			}
			if (now - hunt.lastRefresh < REFRESH_TICKS) continue;
			GuardEntity representative = GuardOwnershipIndex.getOwnedGuards(world.getServer(), entry.getKey())
					.stream().filter(g -> g.level() == world).findFirst().orElse(null);
			if (representative == null) {
				iterator.remove();
				continue;
			}
			refresh(hunt, representative, now);
			if (hunt.members.isEmpty()) iterator.remove();
		}
	}

	public static void cancel(UUID owner) { HUNTS.remove(owner); }
	public static void clear() { HUNTS.clear(); }

	private static void refresh(Hunt hunt, GuardEntity guard, long now) {
		if (now - hunt.lastRefresh < REFRESH_TICKS) return;
		hunt.lastRefresh = now;
		hunt.members.entrySet().removeIf(e -> !(hunt.world.getEntity(e.getKey()) instanceof LivingEntity living)
				|| !valid(living, hunt.world) || guard.isAlly(living)
				|| !e.getValue() && !expandable(living, guard));
		// One expansion per refresh bounds repeated damage callbacks and rapid punches. Direct hits are merged immediately.
		if (!hunt.pending.isEmpty()) {
			UUID id = hunt.pending.keySet().iterator().next();
			hunt.pending.remove(id);
			if (!hunt.playerOnly && hunt.world.getEntity(id) instanceof LivingEntity hit) {
				Gather gather = new Gather(hunt.world, guard, hit);
				List<LivingEntity> seeds = gather.nearby(hit, INITIAL_RADIUS);
				seeds.addAll(gather.group(hit));
				for (LivingEntity member : HuntCluster.collect(hit, seeds, gather::neighbors,
						Comparator.<LivingEntity>comparingDouble(hit::distanceToSqr).thenComparing(Entity::getUUID), MAX_TARGETS)) {
					if (valid(member, hunt.world) && !guard.isAlly(member)) hunt.members.putIfAbsent(member.getUUID(), false);
				}
				trim(hunt, hit);
			}
		}
		hunt.targets = hunt.members.keySet().stream().map(hunt.world::getEntity)
				.filter(LivingEntity.class::isInstance).map(LivingEntity.class::cast).toList();
	}

	private static void trim(Hunt hunt, LivingEntity hit) {
		if (hunt.members.size() <= MAX_TARGETS) return;
		List<UUID> nearest = hunt.members.keySet().stream().sorted(Comparator
				.<UUID>comparingDouble(id -> id.equals(hit.getUUID()) ? -1
						: hunt.world.getEntity(id) instanceof Entity entity ? hit.distanceToSqr(entity) : Double.MAX_VALUE)
				.thenComparing(id -> id)).limit(MAX_TARGETS).toList();
		hunt.members.keySet().retainAll(new HashSet<>(nearest));
		hunt.pending.keySet().retainAll(hunt.members.keySet());
	}

	private static boolean valid(LivingEntity entity, ServerLevel world) {
		return entity.level() == world && entity.isAlive() && !entity.isRemoved()
				&& entity.attackable() && entity.canBeSeenAsEnemy() && !(entity instanceof ArmorStand);
	}

	private static boolean expandable(LivingEntity entity, GuardEntity guard) {
		return guard.level() instanceof ServerLevel world && valid(entity, world) && !(entity instanceof Player)
				&& !guard.isAlly(entity) && !(entity instanceof OwnableEntity owned && owned.getOwnerReference() != null);
	}

	public static final class Hunt {
		private final ServerLevel world;
		private final boolean playerOnly;
		// true marks an explicit punch, which retains the existing direct-target rules.
		private final Map<UUID, Boolean> members = new LinkedHashMap<>();
		private final Map<UUID, Boolean> pending = new LinkedHashMap<>();
		private final Set<UUID> participants = new HashSet<>();
		private List<LivingEntity> targets = List.of();
		private Vec3 anchor = Vec3.ZERO;
		private long expiresAt;
		private long lastRefresh = Long.MIN_VALUE / 2;

		private Hunt(ServerLevel world, boolean playerOnly) { this.world = world; this.playerOnly = playerOnly; }
		public List<LivingEntity> targets() { return this.targets; }
		public boolean allows(LivingEntity target, GuardEntity guard) {
			Boolean direct = this.members.get(target.getUUID());
			if (direct == null) return false;
			if (target instanceof Player player) {
				ServerPlayer owner = this.world.getServer().getPlayerList().getPlayer(guard.getOwnerUuid());
				return owner != null && this.world.isPvpAllowed() && owner.canHarmPlayer(player);
			}
			return direct || expandable(target, guard);
		}
	}

	private static final class Gather {
		private final ServerLevel world;
		private final GuardEntity guard;
		private final LivingEntity hit;
		private int remaining = INSPECTION_BUDGET;
		private Gather(ServerLevel world, GuardEntity guard, LivingEntity hit) {
			this.world = world; this.guard = guard; this.hit = hit;
		}

		private List<LivingEntity> nearby(LivingEntity center, double radius) {
			List<LivingEntity> found = new ArrayList<>();
			if (this.remaining <= 0) return found;
			// Collect first, then filter: rejected entities count against the inspection budget too.
			this.world.getEntities(EntityTypeTest.forClass(LivingEntity.class), center.getBoundingBox().inflate(radius),
					entity -> true, found, Math.min(QUERY_LIMIT, this.remaining));
			this.remaining -= found.size();
			found.removeIf(entity -> (entity.getType() != this.hit.getType()
					&& !(center instanceof Llama && entity instanceof Llama llama && llama.getCaravanHead() == center))
					|| center.distanceToSqr(entity) > radius * radius
					|| !expandable(entity, this.guard));
			return found;
		}

		private List<LivingEntity> neighbors(LivingEntity member) {
			List<LivingEntity> result = nearby(member, LINK_RADIUS);
			// Public caravan links run toward the head. Proximity discovers nearby tails, including trader llamas.
			if (member instanceof Llama llama) add(result, llama.getCaravanHead());
			if (member.isPassenger() || member.isVehicle()) {
				var passengers = member.getRootVehicle().getSelfAndPassengers().iterator();
				while (this.remaining > 0 && passengers.hasNext()) add(result, passengers.next());
			}
			return result;
		}

		private List<LivingEntity> group(LivingEntity member) {
			List<LivingEntity> result = new ArrayList<>();
			if (member instanceof Raider raider && raider.getCurrentRaid() != null && raider.getCurrentRaid().isActive()) {
				for (Raider other : raider.getCurrentRaid().getAllRaiders()) {
					if (this.remaining <= 0) break;
					if (other.getCurrentRaid() == raider.getCurrentRaid()) add(result, other);
				}
			}
			if (member.getTeam() != null) {
				for (String name : member.getTeam().getPlayers()) {
					if (this.remaining-- <= 0) break;
					try {
						Entity other = this.world.getEntity(UUID.fromString(name));
						if (other != null && other.getTeam() == member.getTeam()) add(result, other);
					} catch (IllegalArgumentException ignored) {
						// Players use names, mobs use UUID strings. Players never participate in expansion.
					}
				}
			}
			if (member instanceof AbstractPiglin piglin && piglin.getBrain().hasMemoryValue(MemoryModuleType.NEARBY_ADULT_PIGLINS)) {
				for (AbstractPiglin other : piglin.getBrain().getMemory(MemoryModuleType.NEARBY_ADULT_PIGLINS).orElse(List.of())) {
					if (this.remaining <= 0) break;
					add(result, other);
				}
			}
			if (member instanceof Hoglin hoglin && hoglin.getBrain().hasMemoryValue(MemoryModuleType.NEAREST_VISIBLE_ADULT_HOGLINS)) {
				for (Hoglin other : hoglin.getBrain().getMemory(MemoryModuleType.NEAREST_VISIBLE_ADULT_HOGLINS).orElse(List.of())) {
					if (this.remaining <= 0) break;
					add(result, other);
				}
			}
			result.addAll(neighbors(member));
			return result;
		}

		private void add(List<LivingEntity> result, Entity entity) {
			if (this.remaining-- > 0 && entity instanceof LivingEntity living && expandable(living, this.guard)) result.add(living);
		}
	}
}
