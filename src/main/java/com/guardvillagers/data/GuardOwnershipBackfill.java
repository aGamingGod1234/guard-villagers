package com.guardvillagers.data;

import com.guardvillagers.GuardVillagersMod;
import java.io.DataInputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.stream.Stream;
import net.minecraft.core.UUIDUtil;
import net.minecraft.core.registries.Registries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.NbtIo;
import net.minecraft.resources.Identifier;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.chunk.storage.RegionFile;
import net.minecraft.world.level.chunk.storage.RegionStorageInfo;
import net.minecraft.world.level.storage.LevelResource;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * One-time import of owned guards from saved entity chunks into {@link GuardOwnershipState}.
 *
 * <p>Worlds from before the ownership index (1.21.11 and early 26.1 builds) have owned guards sitting in unloaded
 * chunks with no row, so they would not count toward the personal limit or show in the roster until their chunk
 * loaded again. Nothing else on disk records who owns which guard, so the entity region files are the only complete
 * source. The scan runs once per world on SERVER_STARTING, before any level opens those files, and sets
 * {@code backfilled} so later starts skip it.
 */
public final class GuardOwnershipBackfill {
	private static final Logger LOGGER = LoggerFactory.getLogger(GuardVillagersMod.MOD_ID + "/data");
	static final String GUARD_ENTITY_ID = GuardVillagersMod.MOD_ID + ":guard";
	/** {@code dimensions/<ns>/<path...>/entities}; datapack dimension paths can nest a few levels. */
	private static final int MAX_DIMENSION_DEPTH = 6;
	private static final int REGION_SIZE = 32;
	// Only used for RegionFile log messages.
	private static final RegionStorageInfo STORAGE_INFO = new RegionStorageInfo("guardvillagers-backfill",
			ResourceKey.create(Registries.DIMENSION, Identifier.withDefaultNamespace("overworld")), "entities");

	private GuardOwnershipBackfill() {
	}

	public static void runOnce(MinecraftServer server) {
		GuardOwnershipState state = server.getDataStorage().computeIfAbsent(GuardOwnershipState.TYPE);
		if (state.isBackfilled()) {
			return;
		}
		Path worldRoot = server.getWorldPath(LevelResource.ROOT);
		long started = System.nanoTime();
		int before = state.size();
		int found = backfill(worldRoot, state);
		state.markBackfilled();
		LOGGER.info("Imported {} owned guard(s) ({} new) from saved entity data in {} ms",
				found, state.size() - before, (System.nanoTime() - started) / 1_000_000L);
	}

	/** Scans every dimension's entity region files under {@code worldRoot}; returns how many owned guards were seen. */
	static int backfill(Path worldRoot, GuardOwnershipState state) {
		Path dimensionsRoot = worldRoot.resolve("dimensions");
		if (!Files.isDirectory(dimensionsRoot)) {
			return 0;
		}
		List<Path> regionFiles;
		try (Stream<Path> paths = Files.walk(dimensionsRoot, MAX_DIMENSION_DEPTH)) {
			regionFiles = paths
					.filter(path -> path.getFileName().toString().endsWith(".mca"))
					.filter(path -> path.getParent() != null && path.getParent().getFileName().toString().equals("entities"))
					.filter(Files::isRegularFile)
					.toList();
		} catch (IOException | RuntimeException e) {
			LOGGER.error("Failed to list entity region files under {}; owned guards there are not counted until loaded", dimensionsRoot, e);
			return 0;
		}
		int found = 0;
		for (Path regionFile : regionFiles) {
			found += scanRegion(regionFile, state);
		}
		return found;
	}

	private static int scanRegion(Path regionFile, GuardOwnershipState state) {
		int found = 0;
		try (RegionFile region = new RegionFile(STORAGE_INFO, regionFile, regionFile.getParent(), false)) {
			for (int x = 0; x < REGION_SIZE; x++) {
				for (int z = 0; z < REGION_SIZE; z++) {
					// RegionFile only uses the in-region offset of the position.
					ChunkPos pos = new ChunkPos(x, z);
					if (!region.hasChunk(pos)) {
						continue;
					}
					try (DataInputStream input = region.getChunkDataInputStream(pos)) {
						if (input != null) {
							found += collectGuards(NbtIo.read(input).getListOrEmpty("Entities"), state);
						}
					} catch (IOException | RuntimeException e) {
						LOGGER.warn("Skipping unreadable entity chunk {} in {}", pos, regionFile, e);
					}
				}
			}
		} catch (IOException | RuntimeException e) {
			LOGGER.warn("Skipping unreadable entity region {}", regionFile, e);
		}
		return found;
	}

	/** Records every owned guard in {@code entities}, including ones riding other entities. */
	static int collectGuards(ListTag entities, GuardOwnershipState state) {
		int found = 0;
		for (int i = 0; i < entities.size(); i++) {
			Optional<CompoundTag> maybeEntity = entities.getCompound(i);
			if (maybeEntity.isEmpty()) {
				continue;
			}
			CompoundTag entity = maybeEntity.get();
			if (GUARD_ENTITY_ID.equals(entity.getStringOr("id", ""))) {
				Optional<UUID> guardId = entity.getIntArray("UUID").filter(ints -> ints.length == 4).map(UUIDUtil::uuidFromIntArray);
				Optional<UUID> ownerId = parseUuid(entity.getStringOr("GuardOwner", ""));
				if (guardId.isPresent() && ownerId.isPresent()) {
					String name = entity.getString("CustomName").orElseGet(() -> entity.getStringOr("GeneratedName", ""));
					int group = Math.max(-1, Math.min(63, entity.getIntOr("GroupIndex", -1)));
					// Last seen 0: unknown, so these sort after guards seen since.
					state.recordIfAbsent(guardId.get(), ownerId.get(), 0L, name, group);
					found++;
				}
			}
			found += collectGuards(entity.getListOrEmpty("Passengers"), state);
		}
		return found;
	}

	private static Optional<UUID> parseUuid(String value) {
		if (value == null || value.isBlank()) {
			return Optional.empty();
		}
		try {
			return Optional.of(UUID.fromString(value));
		} catch (IllegalArgumentException e) {
			return Optional.empty();
		}
	}
}
