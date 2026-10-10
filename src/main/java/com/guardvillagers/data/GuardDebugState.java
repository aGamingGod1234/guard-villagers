package com.guardvillagers.data;

import com.mojang.serialization.Codec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import java.util.Collections;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import net.minecraft.core.UUIDUtil;
import net.minecraft.util.datafix.DataFixTypes;
import net.minecraft.world.level.saveddata.SavedData;
import net.minecraft.world.level.saveddata.SavedDataType;

public final class GuardDebugState extends SavedData {
	private static final Codec<Map<UUID, DebugSettings>> SETTINGS_CODEC = Codec.unboundedMap(UUIDUtil.STRING_CODEC, DebugSettings.CODEC);

	public static final Codec<GuardDebugState> CODEC = RecordCodecBuilder.create(instance -> instance.group(
		SETTINGS_CODEC.optionalFieldOf("players", Map.of()).forGetter(GuardDebugState::entriesForCodec)
	).apply(instance, GuardDebugState::new));

	public static final SavedDataType<GuardDebugState> TYPE = new SavedDataType<>(
		GuardSavedData.id("debug"),
		GuardDebugState::new,
		CODEC,
		DataFixTypes.LEVEL
	);

	private final Map<UUID, DebugSettings> settings;

	public GuardDebugState() {
		this(Map.of());
	}

	private GuardDebugState(Map<UUID, DebugSettings> settings) {
		this.settings = new HashMap<>(settings);
	}

	public boolean isEnabled(UUID playerId) {
		return this.settings.getOrDefault(playerId, DebugSettings.DEFAULT).enabled();
	}

	public void setEnabled(UUID playerId, boolean enabled) {
		DebugSettings current = this.settings.getOrDefault(playerId, DebugSettings.DEFAULT);
		if (current.enabled() == enabled) {
			return;
		}
		this.settings.put(playerId, current.withEnabled(enabled));
		this.setDirty();
	}

	public double getRange(UUID playerId) {
		return this.settings.getOrDefault(playerId, DebugSettings.DEFAULT).range();
	}

	public void setRange(UUID playerId, double range) {
		double normalizedRange = sanitizeRange(range);
		DebugSettings current = this.settings.getOrDefault(playerId, DebugSettings.DEFAULT);
		if (Double.compare(current.range(), normalizedRange) == 0) {
			return;
		}
		this.settings.put(playerId, current.withRange(normalizedRange));
		this.setDirty();
	}

	public void toggle(UUID playerId) {
		this.setEnabled(playerId, !this.isEnabled(playerId));
	}

	private Map<UUID, DebugSettings> entriesForCodec() {
		return Collections.unmodifiableMap(this.settings);
	}

	private static double sanitizeRange(double range) {
		if (!Double.isFinite(range) || range < 0.0D) {
			return -1.0D;
		}
		return range;
	}

	public record DebugSettings(boolean enabled, double range) {
		private static final DebugSettings DEFAULT = new DebugSettings(false, -1.0D);
		private static final Codec<DebugSettings> CODEC = RecordCodecBuilder.create(instance -> instance.group(
			Codec.BOOL.optionalFieldOf("enabled", false).forGetter(DebugSettings::enabled),
			Codec.DOUBLE.optionalFieldOf("range", -1.0D).forGetter(DebugSettings::range)
		).apply(instance, DebugSettings::new));

		public DebugSettings {
			range = sanitizeRange(range);
		}

		private DebugSettings withEnabled(boolean enabled) {
			return new DebugSettings(enabled, this.range);
		}

		private DebugSettings withRange(double range) {
			return new DebugSettings(this.enabled, range);
		}
	}
}
