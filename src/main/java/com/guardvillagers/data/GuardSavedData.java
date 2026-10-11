package com.guardvillagers.data;

import com.guardvillagers.GuardVillagersMod;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.DirectoryNotEmptyException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.NoSuchFileException;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;
import net.minecraft.resources.Identifier;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.level.storage.LevelResource;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public final class GuardSavedData {
	private static final Logger LOGGER = LoggerFactory.getLogger(GuardVillagersMod.MOD_ID + "/data");
	static final List<String> GLOBAL_STATES = List.of("debug", "diplomacy", "reputation", "tactics", "upgrades");
	static final String VILLAGE_STATE = "village_state";
	private static final String LEGACY_PREFIX = GuardVillagersMod.MOD_ID + "_";
	/** Pre-26.1 vanilla dimension folders that {@code DimensionStorageFileFix} relocates (and only deletes when empty). */
	private static final Map<String, String> LEGACY_VANILLA_DIMENSIONS = Map.of(
		"DIM-1", "dimensions/minecraft/the_nether",
		"DIM1", "dimensions/minecraft/the_end"
	);

	private GuardSavedData() {
	}

	public static Identifier id(String path) {
		return Identifier.fromNamespaceAndPath(GuardVillagersMod.MOD_ID, path);
	}

	/** SERVER_STARTING: relocate legacy files, then import owned guards saved before the ownership index existed. */
	public static void migrateLegacyFiles(MinecraftServer server) {
		migrateLegacyFiles(server.getWorldPath(LevelResource.ROOT));
		GuardOwnershipBackfill.runOnce(server);
	}

	/**
	 * Before 26.1 every global state lived in the overworld's data folder as {@code data/guardvillagers_<name>.dat},
	 * and the per-level village state sat next to each dimension's vanilla data. 26.1 reads global data from
	 * {@code data/<namespace>/<path>.dat} and per-level data from {@code dimensions/<ns>/<dim>/data/<namespace>/<path>.dat}.
	 * Vanilla's {@code DimensionStorageFileFix} only moves vanilla files and leaves ours (and therefore the
	 * non-empty {@code DIM-1}/{@code DIM1} folders) behind, so relocate them here. Runs on SERVER_STARTING,
	 * before any level or saved data is loaded; it is idempotent and cheap when there is nothing to move.
	 */
	public static void migrateLegacyFiles(Path worldRoot) {
		Path rootData = worldRoot.resolve("data");
		for (String name : GLOBAL_STATES) {
			moveIfPresent(rootData.resolve(legacyFileName(name)), id(name).withSuffix(".dat").resolveAgainst(rootData));
		}

		// The old overworld storage was the root data folder.
		moveVillageState(rootData, worldRoot.resolve("dimensions/minecraft/overworld/data"));
		for (Map.Entry<String, String> entry : LEGACY_VANILLA_DIMENSIONS.entrySet()) {
			Path legacyDimension = worldRoot.resolve(entry.getKey());
			Path legacyData = legacyDimension.resolve("data");
			moveVillageState(legacyData, worldRoot.resolve(entry.getValue()).resolve("data"));
			deleteIfEmptyDirectory(legacyData);
			deleteIfEmptyDirectory(legacyDimension);
		}

		// Datapack dimensions already lived under dimensions/<ns>/<path>/; only the file name changed. The path can
		// contain arbitrarily many slashes, including in dimensions from disabled datapacks.
		Path dimensionsRoot = worldRoot.resolve("dimensions");
		if (Files.notExists(dimensionsRoot)) {
			return;
		}
		String legacyName = legacyFileName(VILLAGE_STATE);
		List<Path> legacyDataDirs;
		try (Stream<Path> paths = Files.walk(dimensionsRoot)) {
			if (!Files.isDirectory(dimensionsRoot)) {
				throw new IOException("Not a dimension directory: " + dimensionsRoot);
			}
			legacyDataDirs = paths
					.filter(path -> path.getFileName().toString().equals(legacyName))
					.map(Path::getParent)
					.filter(parent -> parent.getFileName().toString().equals("data"))
					.toList();
		} catch (IOException | UncheckedIOException e) {
			throw new IllegalStateException("Cannot scan legacy guard village data in " + dimensionsRoot, e);
		}
		for (Path data : legacyDataDirs) {
			moveVillageState(data, data);
		}
	}

	static String legacyFileName(String name) {
		return LEGACY_PREFIX + name + ".dat";
	}

	private static void moveVillageState(Path legacyDataDir, Path targetDataDir) {
		moveIfPresent(legacyDataDir.resolve(legacyFileName(VILLAGE_STATE)), id(VILLAGE_STATE).withSuffix(".dat").resolveAgainst(targetDataDir));
	}

	private static void moveIfPresent(Path legacy, Path target) {
		try {
			if (!Files.readAttributes(legacy, BasicFileAttributes.class).isRegularFile()) {
				throw new IOException("Not a regular saved-data file: " + legacy);
			}
		} catch (NoSuchFileException e) {
			return;
		} catch (IOException e) {
			throw new UncheckedIOException("Cannot inspect legacy guard data " + legacy, e);
		}
		if (Files.exists(target)) {
			LOGGER.warn("Both legacy guard data {} and migrated {} exist; keeping the migrated file", legacy, target);
			return;
		}
		try {
			Files.createDirectories(target.getParent());
			Files.move(legacy, target);
			LOGGER.info("Migrated legacy guard data {} -> {}", legacy, target);
		} catch (IOException e) {
			// Continuing would let SavedDataStorage save a default over the pending migration.
			throw new UncheckedIOException("Cannot migrate legacy guard data " + legacy, e);
		}
	}

	private static void deleteIfEmptyDirectory(Path directory) {
		if (!Files.isDirectory(directory)) {
			return;
		}
		try {
			Files.delete(directory);
		} catch (DirectoryNotEmptyException ignored) {
			// Something else still lives there; leave it alone like vanilla does.
		} catch (IOException e) {
			LOGGER.warn("Could not remove empty legacy folder {}", directory, e);
		}
	}
}
