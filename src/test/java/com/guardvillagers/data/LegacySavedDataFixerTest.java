package com.guardvillagers.data;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.UUID;
import net.minecraft.SharedConstants;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.NbtOps;
import net.minecraft.util.datafix.DataFixTypes;
import net.minecraft.util.datafix.DataFixers;
import org.junit.jupiter.api.Test;

/**
 * Our states are registered with {@link DataFixTypes#LEVEL}, so on the first 26.1 load SavedDataStorage runs the
 * level.dat fixers (DayTimeToClock, LevelDatToSavedDataPreparation, ...) over our files. They only add or remove
 * vanilla keys at the root; this guards that the {@code data} payload comes through untouched.
 */
final class LegacySavedDataFixerTest {
	/** 1.21.10's data version; older than every 26.1 LEVEL fix. */
	private static final int LEGACY_DATA_VERSION = 4556;

	@Test
	void legacyReputationPayloadSurvivesLevelFixers() {
		SharedConstants.tryDetectVersion();
		UUID player = UUID.fromString("00000000-0000-0000-0000-000000000001");
		GuardReputationState original = new GuardReputationState();
		original.set(player, 0.75D);

		CompoundTag file = new CompoundTag();
		file.put("data", GuardReputationState.CODEC.encodeStart(NbtOps.INSTANCE, original).getOrThrow());
		file.putInt("DataVersion", LEGACY_DATA_VERSION);
		CompoundTag payloadBefore = file.getCompoundOrEmpty("data").copy();

		CompoundTag fixed = DataFixTypes.LEVEL.update(DataFixers.getDataFixer(), file, LEGACY_DATA_VERSION,
				SharedConstants.getCurrentVersion().dataVersion().version());

		assertEquals(payloadBefore, fixed.getCompoundOrEmpty("data"));
		GuardReputationState decoded = GuardReputationState.CODEC.parse(NbtOps.INSTANCE, fixed.get("data")).getOrThrow();
		assertEquals(0.75D, decoded.get(player));
	}
}
