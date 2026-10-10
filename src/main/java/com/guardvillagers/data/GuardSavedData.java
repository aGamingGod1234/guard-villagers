package com.guardvillagers.data;

import com.guardvillagers.GuardVillagersMod;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import net.minecraft.resources.Identifier;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.dimension.DimensionType;
import net.minecraft.world.level.storage.LevelResource;

public final class GuardSavedData {
	private static final List<String> GLOBAL_STATES = List.of("debug", "diplomacy", "reputation", "tactics", "upgrades");

	private GuardSavedData() {
	}

	public static Identifier id(String path) {
		return Identifier.fromNamespaceAndPath(GuardVillagersMod.MOD_ID, path);
	}

	/**
	 * Before 26.1 every state lived in the overworld's data folder as {@code data/guardvillagers_<name>.dat}.
	 * Vanilla's world upgrade leaves unknown files where they are, so move ours to the namespaced paths
	 * that 26.1 reads. Must run before any state is first loaded.
	 */
	public static void migrateLegacyFiles(MinecraftServer server) {
		Path legacyDataDir = server.getWorldPath(LevelResource.DATA);
		for (String name : GLOBAL_STATES) {
			moveIfPresent(legacyDataDir.resolve("guardvillagers_" + name + ".dat"), id(name).withSuffix(".dat").resolveAgainst(legacyDataDir));
		}
		Path overworldDataDir = DimensionType.getStorageFolder(Level.OVERWORLD, server.getWorldPath(LevelResource.ROOT)).resolve("data");
		moveIfPresent(legacyDataDir.resolve("guardvillagers_village_state.dat"), id("village_state").withSuffix(".dat").resolveAgainst(overworldDataDir));
	}

	private static void moveIfPresent(Path legacy, Path target) {
		if (!Files.isRegularFile(legacy) || Files.exists(target)) {
			return;
		}
		try {
			Files.createDirectories(target.getParent());
			Files.move(legacy, target);
			GuardVillagersMod.LOGGER.info("Migrated legacy guard data {} -> {}", legacy.getFileName(), target);
		} catch (IOException e) {
			GuardVillagersMod.LOGGER.error("Failed to migrate legacy guard data {}", legacy, e);
		}
	}
}
