package com.guardvillagers.data;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.assertFalse;

import java.io.DataOutputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Random;
import java.nio.ByteBuffer;
import java.util.UUID;
import net.minecraft.SharedConstants;
import net.minecraft.core.UUIDUtil;
import net.minecraft.core.registries.Registries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.IntArrayTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.NbtIo;
import net.minecraft.nbt.NbtOps;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.ComponentSerialization;
import net.minecraft.ChatFormatting;
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
		writeRegion("dimensions/minecraft/overworld/entities/r.-1.0.mca", new ChunkPos(-1, 0), List.of(horse));
		writeRegion("dimensions/custom/a/b/c/d/e/f/g/entities/r.0.0.mca", new ChunkPos(0, 0), List.of(
				guard(nestedDimensionGuard, this.otherOwner, "Sky", -1)));
		// Not entity storage: ignored.
		writeRegion("dimensions/minecraft/overworld/region/r.0.0.mca", new ChunkPos(0, 0), List.of(
				guard(UUID.randomUUID(), this.owner, "", -1)));

		GuardOwnershipState state = new GuardOwnershipState();
		GuardOwnershipBackfill.ScanResult result = GuardOwnershipBackfill.backfill(this.world, state, NbtOps.INSTANCE);

		assertEquals(3, result.found());
		assertTrue(result.complete());
		assertTrue(state.isBackfilled());
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

		GuardOwnershipBackfill.backfill(this.world, state, NbtOps.INSTANCE);

		assertEquals(this.owner, state.get(guardId).orElseThrow().owner());
		assertEquals("Current", state.get(guardId).orElseThrow().name());
	}

	@Test
	void corruptRegionPreventsCompletionAndCanBeRetried() throws IOException {
		Path junk = this.world.resolve("dimensions/minecraft/overworld/entities/r.5.5.mca");
		Files.createDirectories(junk.getParent());
		Files.write(junk, new byte[] {1, 2, 3});
		UUID guardId = UUID.randomUUID();
		writeRegion("dimensions/minecraft/the_nether/entities/r.0.0.mca", new ChunkPos(1, 1), List.of(
				guard(guardId, this.owner, "", -1)));

		GuardOwnershipState state = new GuardOwnershipState();
		GuardOwnershipBackfill.ScanResult result = GuardOwnershipBackfill.backfill(this.world, state, NbtOps.INSTANCE);
		assertEquals(1, result.found());
		assertFalse(result.complete());
		assertFalse(state.isBackfilled());
		assertEquals(1, state.countOwned(this.owner), "successful imports survive the incomplete scan");

		Files.delete(junk);
		writeRegion("dimensions/minecraft/overworld/entities/r.5.5.mca", new ChunkPos(160, 160), List.of(
				guard(UUID.randomUUID(), this.owner, "Recovered", -1)));
		assertTrue(GuardOwnershipBackfill.backfill(this.world, state, NbtOps.INSTANCE).complete());
		assertTrue(state.isBackfilled());
		assertEquals(2, state.countOwned(this.owner), "retry imports missing rows without duplicating successful ones");
	}

	@Test
	void missingDimensionsCompleteButAnInvalidDimensionRootDoesNot() throws IOException {
		GuardOwnershipState empty = new GuardOwnershipState();
		assertTrue(GuardOwnershipBackfill.backfill(this.world, empty, NbtOps.INSTANCE).complete());
		assertTrue(empty.isBackfilled());
		Files.writeString(this.world.resolve("dimensions"), "not a directory");
		GuardOwnershipState invalid = new GuardOwnershipState();
		assertFalse(GuardOwnershipBackfill.backfill(this.world, invalid, NbtOps.INSTANCE).complete());
		assertFalse(invalid.isBackfilled());
	}

	@Test
	void invalidAllocatedChunkDoesNotCountAsAnEmptyRegion() throws IOException {
		Path region = this.world.resolve("dimensions/minecraft/overworld/entities/r.0.0.mca");
		Files.createDirectories(region.getParent());
		byte[] header = new byte[8192];
		ByteBuffer.wrap(header).putInt((100 << 8) | 1); // Allocated sector beyond EOF, cleared by vanilla.
		Files.write(region, header);
		GuardOwnershipState state = new GuardOwnershipState();
		assertFalse(GuardOwnershipBackfill.backfill(this.world, state, NbtOps.INSTANCE).complete());
		assertFalse(state.isBackfilled());
	}

	@Test
	void externalChunksUseAbsoluteCoordinatesAndMissingPayloadCanBeRetried() throws IOException {
		UUID positive = UUID.randomUUID();
		UUID negative = UUID.randomUUID();
		byte[] padding = new byte[1_100_000];
		new Random(42).nextBytes(padding); // Incompressible data forces vanilla's external .mcc storage.
		CompoundTag first = guard(positive, this.owner, "East", -1);
		first.putByteArray("Padding", padding);
		CompoundTag second = guard(negative, this.owner, "West", -1);
		second.putByteArray("Padding", padding);
		writeRegion("dimensions/minecraft/overworld/entities/r.1.0.mca", new ChunkPos(32, 0), List.of(first));
		writeRegion("dimensions/minecraft/overworld/entities/r.-2.-1.mca", new ChunkPos(-33, -1), List.of(second));
		Path external = this.world.resolve("dimensions/minecraft/overworld/entities/c.-33.-1.mcc");
		assertTrue(Files.isRegularFile(external));
		assertTrue(Files.isRegularFile(external.resolveSibling("c.32.0.mcc")));
		Path held = external.resolveSibling("held-payload");
		Files.move(external, held);
		GuardOwnershipState state = new GuardOwnershipState();
		assertFalse(GuardOwnershipBackfill.backfill(this.world, state, NbtOps.INSTANCE).complete());
		assertFalse(state.isBackfilled());
		assertTrue(state.get(positive).isPresent());
		assertTrue(state.get(negative).isEmpty());

		Files.move(held, external);
		assertTrue(GuardOwnershipBackfill.backfill(this.world, state, NbtOps.INSTANCE).complete());
		assertTrue(state.isBackfilled());
		assertEquals(2, state.countOwned(this.owner));
		assertEquals("West", state.get(negative).orElseThrow().name());
	}

	@Test
	void structuredNamesAreDecodedAndDeadGuardsAreSkipped() throws IOException {
		UUID styledId = UUID.randomUUID();
		UUID compositeId = UUID.randomUUID();
		UUID deadId = UUID.randomUUID();
		UUID fallbackId = UUID.randomUUID();
		CompoundTag styled = guard(styledId, this.owner, "Aldric", -1);
		styled.put("CustomName", ComponentSerialization.CODEC.encodeStart(NbtOps.INSTANCE,
				Component.literal("Captain").withStyle(ChatFormatting.GOLD)).getOrThrow());
		CompoundTag composite = guard(compositeId, this.owner, "Other", -1);
		composite.put("CustomName", ComponentSerialization.CODEC.encodeStart(NbtOps.INSTANCE,
				Component.literal("North ").append(Component.literal("Gate").withStyle(ChatFormatting.RED))).getOrThrow());
		CompoundTag dead = guard(deadId, this.owner, "Dead", -1);
		dead.putFloat("Health", 0.0F);
		CompoundTag fallback = guard(fallbackId, this.owner, "Fallback", -1);
		fallback.putInt("CustomName", 42);
		writeRegion("dimensions/minecraft/overworld/entities/r.0.0.mca", new ChunkPos(0, 0),
				List.of(styled, composite, dead, fallback));
		GuardOwnershipState state = new GuardOwnershipState();
		assertTrue(GuardOwnershipBackfill.backfill(this.world, state, NbtOps.INSTANCE).complete());
		assertEquals("Captain", state.get(styledId).orElseThrow().name());
		assertEquals("North Gate", state.get(compositeId).orElseThrow().name());
		assertEquals("Fallback", state.get(fallbackId).orElseThrow().name());
		assertTrue(state.get(deadId).isEmpty());
		assertEquals(3, state.countOwned(this.owner));
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
