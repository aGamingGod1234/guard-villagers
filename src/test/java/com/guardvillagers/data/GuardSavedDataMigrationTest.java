package com.guardvillagers.data;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.assertThrows;
import java.io.UncheckedIOException;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

final class GuardSavedDataMigrationTest {
	@TempDir
	Path world;

	@Test
	void movesGlobalStatesIntoNamespacedServerDataFolder() throws IOException {
		for (String name : GuardSavedData.GLOBAL_STATES) {
			write(this.world.resolve("data").resolve(GuardSavedData.legacyFileName(name)), name);
		}

		GuardSavedData.migrateLegacyFiles(this.world);

		for (String name : GuardSavedData.GLOBAL_STATES) {
			assertEquals(name, Files.readString(this.world.resolve("data/guardvillagers/" + name + ".dat")));
			assertFalse(Files.exists(this.world.resolve("data").resolve(GuardSavedData.legacyFileName(name))));
		}
	}

	@Test
	void movesVillageStateForEveryDimensionAndCleansEmptyLegacyFolders() throws IOException {
		String legacyVillage = GuardSavedData.legacyFileName(GuardSavedData.VILLAGE_STATE);
		write(this.world.resolve("data").resolve(legacyVillage), "overworld");
		write(this.world.resolve("DIM-1/data").resolve(legacyVillage), "nether");
		write(this.world.resolve("DIM1/data").resolve(legacyVillage), "end");
		write(this.world.resolve("dimensions/custom/sky/data").resolve(legacyVillage), "custom");
		// Simulate an unrelated mod file that must keep DIM1 alive.
		write(this.world.resolve("DIM1/data/othermod_state.dat"), "other");

		GuardSavedData.migrateLegacyFiles(this.world);

		assertEquals("overworld", Files.readString(this.world.resolve("dimensions/minecraft/overworld/data/guardvillagers/village_state.dat")));
		assertEquals("nether", Files.readString(this.world.resolve("dimensions/minecraft/the_nether/data/guardvillagers/village_state.dat")));
		assertEquals("end", Files.readString(this.world.resolve("dimensions/minecraft/the_end/data/guardvillagers/village_state.dat")));
		assertEquals("custom", Files.readString(this.world.resolve("dimensions/custom/sky/data/guardvillagers/village_state.dat")));
		assertFalse(Files.exists(this.world.resolve("DIM-1")), "empty legacy nether folder should be removed");
		assertTrue(Files.exists(this.world.resolve("DIM1/data/othermod_state.dat")), "foreign files must be left alone");
	}

	@Test
	void movesVillageStateForNestedDatapackDimensionPaths() throws IOException {
		String legacyVillage = GuardSavedData.legacyFileName(GuardSavedData.VILLAGE_STATE);
		write(this.world.resolve("dimensions/custom/realms/sky/data").resolve(legacyVillage), "nested");
		write(this.world.resolve("dimensions/custom/a/b/c/d/e/f/g/data").resolve(legacyVillage), "deeper");
		// Not a data folder: must stay put.
		write(this.world.resolve("dimensions/custom/sky/region").resolve(legacyVillage), "stray");

		GuardSavedData.migrateLegacyFiles(this.world);

		assertEquals("nested", Files.readString(this.world.resolve("dimensions/custom/realms/sky/data/guardvillagers/village_state.dat")));
		assertEquals("deeper", Files.readString(this.world.resolve("dimensions/custom/a/b/c/d/e/f/g/data/guardvillagers/village_state.dat")));
		assertFalse(Files.exists(this.world.resolve("dimensions/custom/realms/sky/data").resolve(legacyVillage)));
		assertTrue(Files.exists(this.world.resolve("dimensions/custom/sky/region").resolve(legacyVillage)));
	}

	@Test
	void neverOverwritesAlreadyMigratedData() throws IOException {
		write(this.world.resolve("data").resolve(GuardSavedData.legacyFileName("tactics")), "stale");
		write(this.world.resolve("data/guardvillagers/tactics.dat"), "current");

		GuardSavedData.migrateLegacyFiles(this.world);
		GuardSavedData.migrateLegacyFiles(this.world);

		assertEquals("current", Files.readString(this.world.resolve("data/guardvillagers/tactics.dat")));
		assertEquals("stale", Files.readString(this.world.resolve("data").resolve(GuardSavedData.legacyFileName("tactics"))));
	}

	@Test
	void missingWorldFoldersAreANoOp() {
		GuardSavedData.migrateLegacyFiles(this.world.resolve("does-not-exist"));
	}

	@Test
	void migrationFailureStopsBeforeDefaultsCanBeCreatedAndCanResume() throws IOException {
		Path legacy = this.world.resolve("data/guardvillagers_reputation.dat");
		Path blocker = this.world.resolve("data/guardvillagers");
		write(legacy, "legacy reputation");
		write(blocker, "blocks destination directory creation");
		assertThrows(UncheckedIOException.class, () -> GuardSavedData.migrateLegacyFiles(this.world));
		assertEquals("legacy reputation", Files.readString(legacy));
		Files.delete(blocker);
		GuardSavedData.migrateLegacyFiles(this.world);
		assertEquals("legacy reputation", Files.readString(blocker.resolve("reputation.dat")));
		assertFalse(Files.exists(legacy));
	}

	@Test
	void invalidDimensionRootStopsMigration() throws IOException {
		write(this.world.resolve("dimensions"), "not a directory");
		assertThrows(IllegalStateException.class, () -> GuardSavedData.migrateLegacyFiles(this.world));
	}

	private static void write(Path path, String content) throws IOException {
		Files.createDirectories(path.getParent());
		Files.writeString(path, content);
	}
}
