package com.guardvillagers.command.order;

import java.util.Collection;
import java.util.List;

/**
 * The speaker's own tactics group names and loaded guard names, supplied by the caller so the language and order
 * classes stay free of Minecraft types. Names are matched case-insensitively. A guard name may appear more than once.
 */
public interface OwnerRoster {
	OwnerRoster EMPTY = of(List.of(), List.of());

	Collection<String> groupNames();

	Collection<String> guardNames();

	static OwnerRoster of(Collection<String> groupNames, Collection<String> guardNames) {
		return new Snapshot(List.copyOf(groupNames), List.copyOf(guardNames));
	}

	record Snapshot(List<String> groupNames, List<String> guardNames) implements OwnerRoster {
	}
}
