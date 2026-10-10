package com.guardvillagers.data;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.DataOutputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.UUID;
import net.minecraft.SharedConstants;
import net.minecraft.core.UUIDUtil;
import net.minecraft.core.registries.Registries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.IntArrayTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.NbtIo;
import net.minecraft.resources.Identifier;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.Bootstrap;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.chunk.storage.RegionFile;
import net.minecraft.world.level.chunk.storage.RegionStorageInfo;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

final class GuardOwnershipBackfillTest {
	private static final RegionStorageInfo INFO = new RegionStorageInfo("test",
			ResourceKey.create(Registries.DIMENSION, Identifier.withDefaultNamespace("overworld")), "entities");

	@TempDir
	Path world;

	@BeforeAll
	static void bootstrap() {
		SharedConstants.tryDetectVersion();
		Bootstrap.bootStrap();
	}

	private final UUID owner = UUID.randomUUID();
	private final UUID otherOwner = UUID.randomUUID();

	@Test
	void importsOwnedGuardsFromEveryDimensionsEntityRegions() throws IOException {
		UUID overworldGuard = UUID.randomUUID();
		UUID riderGuard = UUID.randomUUID();
		UUID nestedDimensionGuard = UUID.randomUUID();
		UUID unownedGuard = UUID.randomUUID();
		writeRegion("dimensions/minecraft/overworld/entities/r.0.0.mca", new ChunkPos(3, 7), List.of(
				guard(overworldGuard, this.owner, "Aldric", 2),
				guard(unownedGuard, null, "", -1),
				entity("minecraft:zombie", UUID.randomUUID())));
		CompoundTag horse = entity("minecraft:horse", UUID.randomUUID());
		ListTag passengers = new ListTag();
		passengers.add(guard(riderGuard, this.owner, "", 0));
		horse.put("Passengers", passengers);
		writeRegion("dimensions/minecraft/overworld/entities/r.-1.0.mca", new ChunkPos(31, 0), List.of(horse));
		writeRegion("dimensions/custom/realms/sky/entities/r.0.0.mca", new ChunkPos(0, 0), List.of(
				guard(nestedDimensionGuard, this.otherOwner, "Sky", -1)));
		// Not entity storage: ignored.
		writeRegion("dimensions/minecraft/overworld/region/r.0.0.mca", new ChunkPos(0, 0), List.of(
				guard(UUID.randomUUID(), this.owner, "", -1)));

		GuardOwnershipState state = new GuardOwnershipState();
		int found = GuardOwnershipBackfill.backfill(this.world, state);

		assertEquals(3, found);
		assertEquals(2, state.countOwned(this.owner));
		assertTrue(state.guardsOf(this.owner).contains(riderGuard), "guards riding another entity are found");
		assertEquals("Aldric", state.get(overworldGuard).orElseThrow().name());
		assertEquals(2, state.get(overworldGuard).orElseThrow().groupIndex());
		assertEquals(1, state.countOwned(this.otherOwner));
		assertTrue(state.get(unownedGuard).isEmpty());
	}

	@Test
	void liveRowsWinOverSavedEntityData() throws IOException {
		UUID guardId = UUID.randomUUID();
		writeRegion("dimensions/minecraft/overworld/entities/r.0.0.mca", new ChunkPos(0, 0), List.of(
				guard(guardId, this.otherOwner, "Old", -1)));
		GuardOwnershipState state = new GuardOwnershipState();
		state.record(guardId, this.owner, 100L, "Current", 1);

		GuardOwnershipBackfill.backfill(this.world, state);

		assertEquals(this.owner, state.get(guardId).orElseThrow().owner());
		assertEquals("Current", state.get(guardId).orElseThrow().name());
	}

	@Test
	void corruptRegionFilesAndMissingFoldersAreSkipped() throws IOException {
		Path junk = this.world.resolve("dimensions/minecraft/overworld/entities/r.5.5.mca");
		Files.createDirectories(junk.getParent());
		Files.write(junk, new byte[] {1, 2, 3});
		UUID guardId = UUID.randomUUID();
		writeRegion("dimensions/minecraft/the_nether/entities/r.0.0.mca", new ChunkPos(1, 1), List.of(
				guard(guardId, this.owner, "", -1)));

		GuardOwnershipState state = new GuardOwnershipState();
		assertEquals(1, GuardOwnershipBackfill.backfill(this.world, state));
		assertEquals(0, GuardOwnershipBackfill.backfill(this.world.resolve("missing"), new GuardOwnershipState()));
	}

	private void writeRegion(String relativePath, ChunkPos pos, List<CompoundTag> entities) throws IOException {
		Path file = this.world.resolve(relativePath);
		Files.createDirectories(file.getParent());
		CompoundTag chunk = new CompoundTag();
		ListTag list = new ListTag();
		list.addAll(entities);
		chunk.put("Entities", list);
		try (RegionFile region = new RegionFile(INFO, file, file.getParent(), false);
				DataOutputStream output = region.getChunkDataOutputStream(pos)) {
			NbtIo.write(chunk, output);
		}
	}

	private static CompoundTag guard(UUID guardId, UUID ownerId, String generatedName, int group) {
		CompoundTag tag = entity(GuardOwnershipBackfill.GUARD_ENTITY_ID, guardId);
		tag.putString("GuardOwner", ownerId == null ? "" : ownerId.toString());
		tag.putString("GeneratedName", generatedName);
		tag.putInt("GroupIndex", group);
		return tag;
	}

	private static CompoundTag entity(String id, UUID uuid) {
		CompoundTag tag = new CompoundTag();
		tag.putString("id", id);
		tag.put("UUID", new IntArrayTag(UUIDUtil.uuidToIntArray(uuid)));
		return tag;
	}
}
