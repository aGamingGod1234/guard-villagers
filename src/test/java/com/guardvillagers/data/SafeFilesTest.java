package com.guardvillagers.data;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.google.gson.JsonParser;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

final class SafeFilesTest {
	private static final Logger LOGGER = LoggerFactory.getLogger(SafeFilesTest.class);

	@TempDir
	Path dir;

	@Test
	void malformedJsonIsMovedAsideInsteadOfThrowing() throws IOException {
		Path file = this.dir.resolve("guardvillagers_client_tactics.json");
		Files.writeString(file, "{\"worlds\": {\"a\": [1, 2,");

		boolean loaded = SafeFiles.readOrQuarantine(file, LOGGER, json -> JsonParser.parseString(json));

		assertFalse(loaded);
		assertFalse(Files.exists(file), "the bad file must not be read again on the next launch");
		List<Path> quarantined = list();
		assertEquals(1, quarantined.size());
		assertTrue(quarantined.getFirst().getFileName().toString().startsWith("guardvillagers_client_tactics.json.corrupt-"));
		assertEquals("{\"worlds\": {\"a\": [1, 2,", Files.readString(quarantined.getFirst()), "the player's data is kept, not deleted");
	}

	@Test
	void anyParserRuntimeExceptionQuarantines() throws IOException {
		Path file = this.dir.resolve("data.json");
		Files.writeString(file, "{\"worlds\": {\"a\": {\"groupNames\": [{}]}}}");

		boolean loaded = SafeFiles.readOrQuarantine(file, LOGGER, json -> {
			// What JsonElement.getAsString() does on an object.
			throw new UnsupportedOperationException("JsonObject");
		});

		assertFalse(loaded);
		assertFalse(Files.exists(file));
	}

	@Test
	void goodAndMissingFilesAreNotTouched() throws IOException {
		Path file = this.dir.resolve("data.json");
		Files.writeString(file, "{\"version\": 1}");
		AtomicReference<String> seen = new AtomicReference<>();

		assertTrue(SafeFiles.readOrQuarantine(file, LOGGER, seen::set));
		assertEquals("{\"version\": 1}", seen.get());
		assertTrue(Files.exists(file));
		assertFalse(SafeFiles.readOrQuarantine(this.dir.resolve("missing.json"), LOGGER, seen::set));
		assertEquals(1, list().size());
	}

	@Test
	void atomicWriteReplacesTheFileAndLeavesNoTempFiles() throws IOException {
		Path file = this.dir.resolve("nested/config.json");

		SafeFiles.writeAtomically(file, "first");
		SafeFiles.writeAtomically(file, "second, longer than the first");
		SafeFiles.writeAtomically(file, "3");

		assertEquals("3", Files.readString(file));
		try (Stream<Path> files = Files.list(file.getParent())) {
			assertEquals(List.of(file), files.toList());
		}
	}

	private List<Path> list() throws IOException {
		try (Stream<Path> files = Files.list(this.dir)) {
			return files.toList();
		}
	}
}
