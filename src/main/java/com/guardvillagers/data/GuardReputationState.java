package com.guardvillagers.data;

import com.mojang.serialization.Codec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import net.minecraft.datafixer.DataFixTypes;
import net.minecraft.util.Uuids;
import net.minecraft.world.PersistentState;
import net.minecraft.world.PersistentStateType;

import java.util.Collections;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

public final class GuardReputationState extends PersistentState {
	private static final Codec<Map<UUID, Integer>> REPUTATION_CODEC = Codec.unboundedMap(Uuids.STRING_CODEC, Codec.INT);

	public static final Codec<GuardReputationState> CODEC = RecordCodecBuilder.create(instance -> instance.group(
		REPUTATION_CODEC.optionalFieldOf("reputation", Map.of()).forGetter(GuardReputationState::reputationForCodec)
	).apply(instance, GuardReputationState::new));

	public static final PersistentStateType<GuardReputationState> TYPE = new PersistentStateType<>(
		"guardvillagers_reputation",
		GuardReputationState::new,
		CODEC,
		DataFixTypes.LEVEL
	);

	private final Map<UUID, Integer> reputation;

	public GuardReputationState() {
		this(Map.of());
	}

	private GuardReputationState(Map<UUID, Integer> reputation) {
		this.reputation = new HashMap<>(reputation);
	}

	public int get(UUID playerId) {
		return this.reputation.getOrDefault(playerId, 0);
	}

	public int add(UUID playerId, int delta) {
		int updated = Math.max(-200, Math.min(200, this.get(playerId) + delta));
		this.reputation.put(playerId, updated);
		this.markDirty();
		return updated;
	}

	public void set(UUID playerId, int value) {
		int clamped = Math.max(-200, Math.min(200, value));
		this.reputation.put(playerId, clamped);
		this.markDirty();
	}

	private Map<UUID, Integer> reputationForCodec() {
		return Collections.unmodifiableMap(this.reputation);
	}
}
