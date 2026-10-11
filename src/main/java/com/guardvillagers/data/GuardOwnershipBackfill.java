package com.guardvillagers.data;

import com.guardvillagers.GuardVillagersMod;
import com.mojang.serialization.DynamicOps;
import java.io.DataInputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;
import net.minecraft.core.UUIDUtil;
import net.minecraft.core.registries.Registries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.NbtIo;
import net.minecraft.nbt.NbtOps;
import net.minecraft.nbt.Tag;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.ComponentSerialization;
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
	private static final int REGION_SIZE = 32;
	private static final Pattern REGION_NAME = Pattern.compile("r\\.(-?\\d+)\\.(-?\\d+)\\.mca");
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
		ScanResult result = backfill(worldRoot, state, server.registryAccess().createSerializationContext(NbtOps.INSTANCE));
		LOGGER.info("Imported {} owned guard(s) ({} new) from saved entity data in {} ms",
				result.found(), state.size() - before, (System.nanoTime() - started) / 1_000_000L);
		if (!result.complete()) {
			LOGGER.warn("Ownership import is incomplete; remaining saved guards will be retried on the next startup");
		}
	}

	/** Marks completion only when every discovered region and allocated chunk was readable. */
	static ScanResult backfill(Path worldRoot, GuardOwnershipState state, DynamicOps<Tag> ops) {
		Path dimensionsRoot = worldRoot.resolve("dimensions");
		if (Files.notExists(dimensionsRoot)) {
			state.markBackfilled();
			return new ScanResult(0, true);
		}
		List<Path> regionFiles;
		try (Stream<Path> paths = Files.walk(dimensionsRoot)) {
			if (!Files.isDirectory(dimensionsRoot)) {
				throw new IOException("Not a dimension directory: " + dimensionsRoot);
			}
			regionFiles = paths
					.filter(path -> path.getFileName().toString().endsWith(".mca"))
					.filter(path -> path.getParent() != null && path.getParent().getFileName().toString().equals("entities"))
					.toList();
		} catch (IOException | RuntimeException e) {
			LOGGER.error("Failed to list entity region files under {}; owned guards there are not counted until loaded", dimensionsRoot, e);
			return new ScanResult(0, false);
		}
		int found = 0;
		boolean complete = true;
		for (Path regionFile : regionFiles) {
			ScanResult result = scanRegion(regionFile, state, ops);
			found += result.found();
			complete &= result.complete();
		}
		if (complete) {
			state.markBackfilled();
		}
		return new ScanResult(found, complete);
	}

	private static ScanResult scanRegion(Path regionFile, GuardOwnershipState state, DynamicOps<Tag> ops) {
		int found = 0;
		boolean complete = true;
		try {
			Matcher name = REGION_NAME.matcher(regionFile.getFileName().toString());
			if (!name.matches()) {
				throw new IOException("Invalid entity region name: " + regionFile);
			}
			int baseX = Math.multiplyExact(Integer.parseInt(name.group(1)), REGION_SIZE);
			int baseZ = Math.multiplyExact(Integer.parseInt(name.group(2)), REGION_SIZE);
			// RegionFile clears invalid header offsets internally. Keep the original allocation bits so
			// an unreadable allocated chunk cannot masquerade as an empty slot and finish the import.
			boolean[] allocated = new boolean[REGION_SIZE * REGION_SIZE];
			try (DataInputStream header = new DataInputStream(Files.newInputStream(regionFile))) {
				for (int i = 0; i < allocated.length; i++) {
					allocated[i] = header.readInt() != 0;
				}
				header.skipNBytes(4096); // Timestamp half of the region header must also exist.
			}
			try (RegionFile region = new RegionFile(STORAGE_INFO, regionFile, regionFile.getParent(), false)) {
				for (int x = 0; x < REGION_SIZE; x++) {
					for (int z = 0; z < REGION_SIZE; z++) {
						ChunkPos pos = new ChunkPos(baseX + x, baseZ + z);
						if (!allocated[x + z * REGION_SIZE]) {
							continue;
						}
						try (DataInputStream input = region.getChunkDataInputStream(pos)) {
							if (input == null) {
								throw new IOException("Allocated entity chunk has no readable stream: " + pos);
							}
							found += collectGuards(NbtIo.read(input).getListOrEmpty("Entities"), state, ops);
						} catch (IOException | RuntimeException e) {
							complete = false;
							LOGGER.warn("Skipping unreadable entity chunk {} in {}", pos, regionFile, e);
						}
					}
				}
			}
		} catch (IOException | RuntimeException e) {
			complete = false;
			LOGGER.warn("Skipping unreadable entity region {}", regionFile, e);
		}
		return new ScanResult(found, complete);
	}

	record ScanResult(int found, boolean complete) {
	}

	/** Records every owned guard in {@code entities}, including ones riding other entities. */
	static int collectGuards(ListTag entities, GuardOwnershipState state, DynamicOps<Tag> ops) {
		int found = 0;
		for (int i = 0; i < entities.size(); i++) {
			Optional<CompoundTag> maybeEntity = entities.getCompound(i);
			if (maybeEntity.isEmpty()) {
				continue;
			}
			CompoundTag entity = maybeEntity.get();
			if (GUARD_ENTITY_ID.equals(entity.getStringOr("id", "")) && entity.getFloatOr("Health", 1.0F) > 0.0F) {
				Optional<UUID> guardId = entity.getIntArray("UUID").filter(ints -> ints.length == 4).map(UUIDUtil::uuidFromIntArray);
				Optional<UUID> ownerId = parseUuid(entity.getStringOr("GuardOwner", ""));
				if (guardId.isPresent() && ownerId.isPresent()) {
					String name = Optional.ofNullable(entity.get("CustomName"))
							.flatMap(tag -> ComponentSerialization.CODEC.parse(ops, tag).result())
							.map(Component::getString).orElseGet(() -> entity.getStringOr("GeneratedName", ""));
					int group = Math.max(-1, Math.min(63, entity.getIntOr("GroupIndex", -1)));
					// Last seen 0: unknown, so these sort after guards seen since.
					state.recordIfAbsent(guardId.get(), ownerId.get(), 0L, name, group);
					found++;
				}
			}
			found += collectGuards(entity.getListOrEmpty("Passengers"), state, ops);
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
