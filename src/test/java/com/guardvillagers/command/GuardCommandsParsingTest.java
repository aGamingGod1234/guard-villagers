package com.guardvillagers.command;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import java.util.List;
import java.util.UUID;
import java.util.stream.Collectors;
import java.util.stream.IntStream;
import org.junit.jupiter.api.Test;

final class GuardCommandsParsingTest {
	@Test
	void parsesSpaceAndCommaSeparatedUuidsWithoutDuplicates() {
		UUID a = UUID.randomUUID();
		UUID b = UUID.randomUUID();
		assertEquals(List.of(a, b), GuardCommands.parseUuidList(a + ", " + b + " " + a));
	}

	@Test
	void rejectsMalformedOrOversizedLists() {
		assertNull(GuardCommands.parseUuidList(""));
		assertNull(GuardCommands.parseUuidList("not-a-uuid"));
		assertNull(GuardCommands.parseUuidList(UUID.randomUUID() + " nope"));
		String tooMany = IntStream.range(0, 1025).mapToObj(i -> UUID.randomUUID().toString()).collect(Collectors.joining(" "));
		assertNull(GuardCommands.parseUuidList(tooMany));
		String atLimit = IntStream.range(0, 1024).mapToObj(i -> UUID.randomUUID().toString()).collect(Collectors.joining(" "));
		assertEquals(1024, GuardCommands.parseUuidList(atLimit).size());
	}
}
