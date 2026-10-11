package com.guardvillagers.gametest.perf;

import com.guardvillagers.GuardVillagersMod;
import com.mojang.authlib.GameProfile;
import io.netty.channel.embedded.EmbeddedChannel;
import net.minecraft.network.Connection;
import net.minecraft.network.protocol.PacketFlow;
import net.minecraft.network.protocol.game.ServerboundPlayerLoadedPacket;
import net.minecraft.server.network.CommonListenerCookie;
import net.minecraft.world.level.GameType;
import java.util.UUID;
import com.guardvillagers.entity.GuardBehavior;
import com.guardvillagers.entity.GuardEntity;
import java.io.IOException;
import java.lang.management.ManagementFactory;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Random;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntitySpawnReason;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Runs benchmark scenarios one after another and times every full server tick (START_SERVER_TICK to
 * END_SERVER_TICK) plus the bytes the server thread allocated during that tick. Scenario setup and teardown
 * happen at the end of a tick and are never inside a measured tick; each scenario also has warmup ticks.
 */
final class PerfRunner {
	private static final Logger LOGGER = LoggerFactory.getLogger("GuardPerf");

	private static final int ARENA_MIN = 12;
	private static final int ARENA_SIZE = 80;
	private static final int ARENA_MAX = ARENA_MIN + ARENA_SIZE - 1;
	private static final double CENTER = ARENA_MIN + ARENA_SIZE / 2.0;
	private static final int IDLE_VILLAGERS = 16;
	private static final int PATROL_RADIUS = 12;
	private static final double FOLLOW_RADIUS = 14.0;
	private static final double FOLLOW_SPEED = 0.25; // blocks per tick, a little under sprinting
	private static final int MAX_RESPAWNS_PER_TICK = 4;

	private static final com.sun.management.ThreadMXBean THREADS =
			(com.sun.management.ThreadMXBean) ManagementFactory.getThreadMXBean();

	private static PerfRunner active;
	private static boolean listenersRegistered;

	enum Kind { EMPTY, VILLAGERS, HUSKS, VANILLA_COMBAT, IDLE, FOLLOW, COMBAT, PATROL, COMMANDER, MIXED }

	record Phase(String name, Kind kind, int n, int warmup, int measure, boolean recorded) {
	}

	record Result(Phase phase, long[] tickNanos, long[] allocBytes, String endState) {
		double meanMs() {
			return Arrays.stream(this.tickNanos).average().orElse(0) / 1e6;
		}

		double pctMs(double p) {
			long[] sorted = this.tickNanos.clone();
			Arrays.sort(sorted);
			int idx = (int) Math.ceil(p * sorted.length) - 1;
			return sorted[Math.max(0, Math.min(sorted.length - 1, idx))] / 1e6;
		}

		double allocKbPerTick() {
			return Arrays.stream(this.allocBytes).average().orElse(0) / 1024.0;
		}
	}

	private final GameTestHelper helper;
	private final ServerLevel level;
	private final List<Phase> phases;
	private final List<Result> results = new ArrayList<>();
	private final List<Entity> guards = new ArrayList<>();
	private final List<Entity> husks = new ArrayList<>();
	private final List<Entity> others = new ArrayList<>();
	private Random random;
	private ServerPlayer player;
	private final List<ServerPlayer> owners = new ArrayList<>();
	private int nextGuard;
	private double followAngle;
	private int phaseIndex = -1;
	private int tickInPhase;
	private long[] tickNanos;
	private long[] allocBytes;
	private int recordedTicks;
	private long tickStart;
	private long allocStart;
	private int huskRespawns;
	private int guardRespawns;
	private boolean done;
	private PerfPhaseEvent phaseEvent;

	private PerfRunner(GameTestHelper helper) {
		this.helper = helper;
		this.level = helper.getLevel();
		this.phases = buildPhases();
	}

	static PerfRunner start(GameTestHelper helper) {
		if (!listenersRegistered) {
			listenersRegistered = true;
			ServerTickEvents.START_SERVER_TICK.register(server -> {
				if (active != null) {
					active.onTickStart();
				}
			});
			ServerTickEvents.END_SERVER_TICK.register(server -> {
				if (active != null) {
					active.onTickEnd();
				}
			});
		}
		PerfRunner runner = new PerfRunner(helper);
		runner.buildArena();
		active = runner;
		runner.nextPhase();
		return runner;
	}

	boolean isDone() {
		return this.done;
	}

	private static List<Phase> buildPhases() {
		int warmup = Integer.getInteger("guardvillagers.perf.warmup", 100);
		int measure = Integer.getInteger("guardvillagers.perf.measure", 600);
		int[] sizes = Arrays.stream(System.getProperty("guardvillagers.perf.sizes", "16,64,128,256,512").split(","))
				.map(String::trim).mapToInt(Integer::parseInt).toArray();
		String only = System.getProperty("guardvillagers.perf.only", "");
		List<String> filters = only.isBlank() ? List.of() : Arrays.stream(only.split(",")).map(String::trim).toList();

		List<Phase> all = new ArrayList<>();
		// Unrecorded JIT warmup: run every scenario kind once so no measured phase pays for compilation.
		int jitWarmup = Integer.getInteger("guardvillagers.perf.jitwarmup", 200);
		for (Kind kind : List.of(Kind.VILLAGERS, Kind.HUSKS, Kind.IDLE, Kind.FOLLOW, Kind.COMBAT, Kind.PATROL, Kind.COMMANDER, Kind.MIXED)) {
			all.add(new Phase("jit_warmup_" + kind.name().toLowerCase(Locale.ROOT), kind, 64, 0, jitWarmup, false));
		}
		all.add(new Phase("empty", Kind.EMPTY, 0, warmup, measure, true));
		all.add(new Phase("villagers_" + IDLE_VILLAGERS, Kind.VILLAGERS, IDLE_VILLAGERS, warmup, measure, true));
		for (int n : sizes) {
			if (n != IDLE_VILLAGERS) {
				all.add(new Phase("villagers_" + n, Kind.VILLAGERS, n, warmup, measure, true));
			}
			all.add(new Phase("husks_" + (n / 2), Kind.HUSKS, n / 2, warmup, measure, true));
			all.add(new Phase("vanilla_combat_" + n, Kind.VANILLA_COMBAT, n, warmup, measure, true));
			all.add(new Phase("commander_" + n, Kind.COMMANDER, n, warmup, measure, true));
			all.add(new Phase("mixed_" + n, Kind.MIXED, n, warmup, measure, true));
			all.add(new Phase("idle_" + n, Kind.IDLE, n, warmup, measure, true));
			all.add(new Phase("follow_" + n, Kind.FOLLOW, n, warmup, measure, true));
			all.add(new Phase("combat_" + n, Kind.COMBAT, n, warmup, measure, true));
			all.add(new Phase("patrol_" + n, Kind.PATROL, n, warmup, measure, true));
		}
		if (filters.isEmpty()) {
			return all;
		}
		return all.stream()
				.filter(p -> !p.recorded() || p.kind() == Kind.EMPTY || p.kind() == Kind.VILLAGERS || p.kind() == Kind.VANILLA_COMBAT || p.kind() == Kind.HUSKS || filters.stream().anyMatch(f -> p.name().startsWith(f)))
				.toList();
	}

	// ---- tick hooks ----

	private void onTickStart() {
		if (!this.done && this.phaseIndex >= 0 && this.tickInPhase == this.phases.get(this.phaseIndex).warmup()) {
			this.phaseEvent = new PerfPhaseEvent();
			this.phaseEvent.phase = this.phases.get(this.phaseIndex).name();
			this.phaseEvent.begin();
		}
		this.allocStart = THREADS.getCurrentThreadAllocatedBytes();
		this.tickStart = System.nanoTime();
	}

	private void onTickEnd() {
		long elapsed = System.nanoTime() - this.tickStart;
		long allocated = THREADS.getCurrentThreadAllocatedBytes() - this.allocStart;
		if (this.done || this.phaseIndex < 0) {
			return;
		}
		Phase phase = this.phases.get(this.phaseIndex);
		if (phase.recorded() && this.tickInPhase >= phase.warmup() && this.recordedTicks < this.tickNanos.length) {
			this.tickNanos[this.recordedTicks] = elapsed;
			this.allocBytes[this.recordedTicks] = allocated;
			this.recordedTicks++;
		}
		this.tickInPhase++;

		if (this.tickInPhase >= phase.warmup() + phase.measure()) {
			this.finishPhase(phase);
			this.nextPhase();
		} else {
			this.maintain(phase);
		}
	}

	// ---- phase lifecycle ----

	private void nextPhase() {
		this.phaseIndex++;
		if (this.phaseIndex >= this.phases.size()) {
			this.done = true;
			active = null;
			this.teardownArena();
			this.report();
			return;
		}
		Phase phase = this.phases.get(this.phaseIndex);
		this.tickInPhase = 0;
		this.recordedTicks = 0;
		this.tickNanos = new long[phase.measure()];
		this.allocBytes = new long[phase.measure()];
		this.huskRespawns = 0;
		this.nextGuard = 0;
		this.guardRespawns = 0;
		this.random = new Random(phase.name().hashCode());
		LOGGER.info("[perf] phase {} ({} ticks warmup, {} measured)", phase.name(), phase.warmup(), phase.measure());
		this.setup(phase);
	}

	private void setup(Phase phase) {
		switch (phase.kind()) {
			case EMPTY -> {
			}
			case VILLAGERS -> {
				for (int i = 0; i < phase.n(); i++) {
					this.others.add(this.helper.spawn(EntityType.VILLAGER, this.randomArenaPos(2)));
				}
			}
			case HUSKS -> {
				for (int i = 0; i < phase.n(); i++) {
					this.husks.add(this.helper.spawn(EntityType.HUSK, this.randomArenaPos(2)));
				}
			}
			case IDLE -> {
				for (int i = 0; i < IDLE_VILLAGERS; i++) {
					this.others.add(this.helper.spawn(EntityType.VILLAGER, this.randomArenaPos(2)));
				}
				for (int i = 0; i < phase.n(); i++) {
					this.guards.add(this.spawnGuard(this.randomArenaPos(2), GuardBehavior.DEFENSIVE));
				}
			}
			case FOLLOW -> {
				this.player = this.makeOwner(0);
				this.followAngle = 0;
				this.movePlayer();
				for (int i = 0; i < phase.n(); i++) {
					GuardEntity guard = this.spawnGuard(this.randomNear(CENTER + FOLLOW_RADIUS, CENTER, 6), GuardBehavior.DEFENSIVE);
					guard.setOwnerUuid(this.player.getUUID());
					this.guards.add(guard);
				}
			}
			case COMBAT, COMMANDER, MIXED, VANILLA_COMBAT -> {
				if (phase.kind() == Kind.COMMANDER || phase.kind() == Kind.MIXED) {
					int count = phase.kind() == Kind.MIXED ? 4 : 1;
					for (int i = 0; i < count; i++) this.makeOwner(i);
				}
				for (int i = 0; i < phase.n(); i++) {
					this.guards.add(this.spawnCombatant(phase));
				}
				for (int i = 0; i < phase.n() / 2; i++) {
					this.husks.add(this.helper.spawn(EntityType.HUSK, this.randomEdgePos()));
				}
			}
			case PATROL -> {
				BlockPos home = this.helper.absolutePos(BlockPos.containing(CENTER, 1, CENTER));
				for (int i = 0; i < phase.n(); i++) {
					GuardEntity guard = this.spawnGuard(this.randomArenaPos(2), GuardBehavior.PERIMETER);
					guard.setHome(home, PATROL_RADIUS);
					this.guards.add(guard);
				}
			}
		}
	}

	/** Per-tick scenario upkeep, run after the tick has been timed. */
	private void maintain(Phase phase) {
		switch (phase.kind()) {
			case FOLLOW -> this.movePlayer();
			case COMBAT, COMMANDER, MIXED, VANILLA_COMBAT -> {
				// Keep the fight going at a constant size so every measured tick sees the same load.
				this.huskRespawns += this.topUp(this.husks, phase.n() / 2, () -> this.helper.spawn(EntityType.HUSK, this.randomEdgePos()));
				this.guardRespawns += this.topUp(this.guards, phase.n(),
						() -> this.spawnCombatant(phase));
			}
			default -> {
			}
		}
	}

	private int topUp(List<Entity> tracked, int target, java.util.function.Supplier<Entity> spawner) {
		tracked.removeIf(e -> !e.isAlive());
		int spawned = 0;
		while (tracked.size() < target && spawned < MAX_RESPAWNS_PER_TICK) {
			tracked.add(spawner.get());
			spawned++;
		}
		return spawned;
	}

	private void finishPhase(Phase phase) {
		if (this.phaseEvent != null) {
			this.phaseEvent.commit();
			this.phaseEvent = null;
		}
		long guardsAlive = this.guards.stream().filter(Entity::isAlive).count();
		long husksAlive = this.husks.stream().filter(Entity::isAlive).count();
		String endState = String.format(Locale.ROOT, "guards=%d husks=%d others=%d huskRespawns=%d guardRespawns=%d",
				guardsAlive, husksAlive, this.others.size(), this.huskRespawns, this.guardRespawns);
		if (phase.recorded()) {
			Result result = new Result(phase, Arrays.copyOf(this.tickNanos, this.recordedTicks),
					Arrays.copyOf(this.allocBytes, this.recordedTicks), endState);
			this.results.add(result);
			LOGGER.info(String.format(Locale.ROOT, "[perf] %-14s mean=%.3fms p50=%.3fms p95=%.3fms p99=%.3fms alloc=%.1fKB/tick %s",
					phase.name(), result.meanMs(), result.pctMs(0.50), result.pctMs(0.95), result.pctMs(0.99),
					result.allocKbPerTick(), endState));
		}
		this.clearEntities();
	}

	// ---- world helpers ----

	private GuardEntity spawnGuard(Vec3 relPos, GuardBehavior behavior) {
		// COMMAND runs finalizeSpawn, which gives the guard its natural role and weapon loadout.
		GuardEntity guard = this.helper.spawn(GuardVillagersMod.GUARD_ENTITY_TYPE, relPos, EntitySpawnReason.COMMAND);
		guard.setBehavior(behavior);
		return guard;
	}

	private ServerPlayer makeOwner(int index) {
		var server = this.level.getServer();
		GameProfile profile = new GameProfile(new UUID(0x50455246L, index + 1), "perf" + index);
		CommonListenerCookie cookie = CommonListenerCookie.createInitial(profile, false);
		ServerPlayer owner = new ServerPlayer(server, this.level, profile, cookie.clientInformation());
		Connection connection = new Connection(PacketFlow.SERVERBOUND);
		new EmbeddedChannel(connection);
		server.getPlayerList().placeNewPlayer(connection, owner, cookie);
		owner.connection.handleAcceptPlayerLoad(new ServerboundPlayerLoadedPacket());
		owner.setGameMode(GameType.CREATIVE);
		Vec3 pos = this.helper.absoluteVec(new Vec3(CENTER + index * 3, 1, CENTER));
		owner.snapTo(pos.x, pos.y, pos.z, 0, 0);
		this.owners.add(owner);
		return owner;
	}

	private Entity spawnCombatant(Phase phase) {
		Vec3 pos = this.randomNear(CENTER, CENTER, 12);
		if (phase.kind() == Kind.VANILLA_COMBAT) return this.helper.spawn(EntityType.VILLAGER, pos);
		GuardEntity guard = this.spawnGuard(pos, GuardBehavior.DEFENSIVE);
		if (!this.owners.isEmpty()) {
			int index = this.nextGuard++;
			guard.setOwnerUuid(this.owners.get(index % this.owners.size()).getUUID());
			guard.setGroupIndex((index / this.owners.size()) % 4);
			guard.setGroupName("Group " + guard.getGroupIndex());
			guard.setExperience(index == 0 ? 1000 : index % 300);
		}
		return guard;
	}

	private void movePlayer() {
		this.followAngle += FOLLOW_SPEED / FOLLOW_RADIUS;
		Vec3 rel = new Vec3(CENTER + Math.cos(this.followAngle) * FOLLOW_RADIUS, 1, CENTER + Math.sin(this.followAngle) * FOLLOW_RADIUS);
		Vec3 abs = this.helper.absoluteVec(rel);
		float yaw = (float) Math.toDegrees(this.followAngle) + 180.0F;
		this.player.snapTo(abs.x, abs.y, abs.z, yaw, 0.0F);
	}

	private Vec3 randomArenaPos(int margin) {
		double x = ARENA_MIN + margin + this.random.nextDouble() * (ARENA_SIZE - 2 * margin);
		double z = ARENA_MIN + margin + this.random.nextDouble() * (ARENA_SIZE - 2 * margin);
		return new Vec3(x, 1, z);
	}

	private Vec3 randomNear(double cx, double cz, double radius) {
		double x = cx + (this.random.nextDouble() * 2 - 1) * radius;
		double z = cz + (this.random.nextDouble() * 2 - 1) * radius;
		x = Math.max(ARENA_MIN + 1.5, Math.min(ARENA_MAX - 0.5, x));
		z = Math.max(ARENA_MIN + 1.5, Math.min(ARENA_MAX - 0.5, z));
		return new Vec3(x, 1, z);
	}

	private Vec3 randomEdgePos() {
		double along = CENTER - 22 + this.random.nextDouble() * 44;
		double near = CENTER - 22;
		double far = CENTER + 22;
		return switch (this.random.nextInt(4)) {
			case 0 -> new Vec3(along, 1, near);
			case 1 -> new Vec3(along, 1, far);
			case 2 -> new Vec3(near, 1, along);
			default -> new Vec3(far, 1, along);
		};
	}

	private AABB arenaBox() {
		BlockPos min = this.helper.absolutePos(new BlockPos(ARENA_MIN - 16, -8, ARENA_MIN - 16));
		BlockPos max = this.helper.absolutePos(new BlockPos(ARENA_MAX + 16, 40, ARENA_MAX + 16));
		return AABB.encapsulatingFullBlocks(min, max);
	}

	private void buildArena() {
		for (int x = ARENA_MIN; x <= ARENA_MAX; x++) {
			for (int z = ARENA_MIN; z <= ARENA_MAX; z++) {
				this.helper.setBlock(new BlockPos(x, 0, z), Blocks.STONE);
				boolean edge = x == ARENA_MIN || x == ARENA_MAX || z == ARENA_MIN || z == ARENA_MAX;
				if (edge) {
					for (int y = 1; y <= 4; y++) {
						this.helper.setBlock(new BlockPos(x, y, z), Blocks.BARRIER);
					}
				}
			}
		}
		// Keep every arena chunk entity-ticking. The game test runner releases forced chunks after the batch.
		BlockPos min = this.helper.absolutePos(new BlockPos(ARENA_MIN - 16, 0, ARENA_MIN - 16));
		BlockPos max = this.helper.absolutePos(new BlockPos(ARENA_MAX + 16, 0, ARENA_MAX + 16));
		for (int cx = Math.min(min.getX(), max.getX()) >> 4; cx <= Math.max(min.getX(), max.getX()) >> 4; cx++) {
			for (int cz = Math.min(min.getZ(), max.getZ()) >> 4; cz <= Math.max(min.getZ(), max.getZ()) >> 4; cz++) {
				this.level.setChunkForced(cx, cz, true);
			}
		}
	}

	private void teardownArena() {
		for (int x = ARENA_MIN; x <= ARENA_MAX; x++) {
			for (int z = ARENA_MIN; z <= ARENA_MAX; z++) {
				if (x == ARENA_MIN || x == ARENA_MAX || z == ARENA_MIN || z == ARENA_MAX) {
					for (int y = 1; y <= 4; y++) {
						this.helper.setBlock(new BlockPos(x, y, z), Blocks.AIR);
					}
				}
			}
		}
	}

	private void clearEntities() {
		for (List<Entity> list : List.of(this.guards, this.husks, this.others)) {
			list.forEach(Entity::discard);
			list.clear();
		}
		// Drops, XP orbs and arrows left behind by fights.
		for (Entity entity : this.level.getEntities((Entity) null, this.arenaBox(), e -> !(e instanceof Player))) {
			entity.discard();
		}
		for (ServerPlayer owner : this.owners) this.level.getServer().getPlayerList().remove(owner);
		this.owners.clear();
		this.player = null;
	}

	// ---- reporting ----

	private void report() {
		Map<String, Result> byName = new HashMap<>();
		this.results.forEach(r -> byName.put(r.phase().name(), r));
		Result empty = byName.get("empty");

		StringBuilder md = new StringBuilder();
		StringBuilder csv = new StringBuilder(
				"scenario,n,ticks,mean_ms,p50_ms,p95_ms,p99_ms,max_ms,alloc_kb_per_tick,baseline,baseline_mean_ms,delta_mean_ms,delta_p95_ms,us_per_guard,end_state\n");
		md.append("| scenario | N | mean ms | p50 ms | p95 ms | p99 ms | alloc KB/tick | baseline | delta mean ms | delta p95 ms | us/guard/tick |\n");
		md.append("|---|---|---|---|---|---|---|---|---|---|---|\n");
		for (Result r : this.results) {
			Phase p = r.phase();
			String baselineName = switch (p.kind()) {
				case IDLE, FOLLOW, PATROL -> "villagers_" + p.n();
				case COMBAT, COMMANDER, MIXED -> "vanilla_combat_" + p.n();
				case EMPTY -> "";
				default -> "empty";
			};
			Result base = baselineName.isEmpty() ? null : byName.getOrDefault(baselineName, empty);
			if (base != null && !byName.containsKey(baselineName)) {
				baselineName = "empty";
			}
			double deltaMean = base == null ? 0 : r.meanMs() - base.meanMs();
			double deltaP95 = base == null ? 0 : r.pctMs(0.95) - base.pctMs(0.95);
			boolean guardScenario = p.kind() == Kind.IDLE || p.kind() == Kind.FOLLOW || p.kind() == Kind.COMBAT || p.kind() == Kind.PATROL || p.kind() == Kind.COMMANDER || p.kind() == Kind.MIXED;
			String perGuard = guardScenario && p.n() > 0 ? String.format(Locale.ROOT, "%.1f", (r.meanMs() - empty.meanMs()) * 1000.0 / p.n()) : "";
			md.append(String.format(Locale.ROOT, "| %s | %d | %.3f | %.3f | %.3f | %.3f | %.1f | %s | %s | %s | %s |\n",
					p.name(), p.n(), r.meanMs(), r.pctMs(0.5), r.pctMs(0.95), r.pctMs(0.99), r.allocKbPerTick(),
					base == null ? "-" : baselineName,
					base == null ? "" : String.format(Locale.ROOT, "%+.3f", deltaMean),
					base == null ? "" : String.format(Locale.ROOT, "%+.3f", deltaP95),
					perGuard));
			csv.append(String.format(Locale.ROOT, "%s,%d,%d,%.4f,%.4f,%.4f,%.4f,%.4f,%.2f,%s,%.4f,%.4f,%.4f,%s,%s\n",
					p.name(), p.n(), r.tickNanos().length, r.meanMs(), r.pctMs(0.5), r.pctMs(0.95), r.pctMs(0.99), r.pctMs(1.0),
					r.allocKbPerTick(), base == null ? "" : baselineName, base == null ? 0 : base.meanMs(), deltaMean, deltaP95,
					perGuard, r.endState()));
		}
		LOGGER.info("[perf] results\n{}", md);

		String stamp = LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss"));
		Path dir = Path.of(System.getProperty("guardvillagers.perf.out", "perf-results"));
		try {
			Files.createDirectories(dir);
			Files.writeString(dir.resolve("perf-" + stamp + ".md"), md.toString());
			Files.writeString(dir.resolve("perf-" + stamp + ".csv"), csv.toString());
			LOGGER.info("[perf] wrote {}", dir.toAbsolutePath().resolve("perf-" + stamp + ".{md,csv}"));
		} catch (IOException e) {
			LOGGER.error("[perf] could not write results", e);
		}
	}
}
