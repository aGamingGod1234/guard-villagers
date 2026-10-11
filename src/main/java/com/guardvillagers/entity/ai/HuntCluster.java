package com.guardvillagers.entity.ai;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.function.Function;

/** Bounded breadth-first expansion; nearer members win within each breadth layer. */
public final class HuntCluster {
	private HuntCluster() {}

	public static <T> List<T> collect(T hit, Collection<T> seeds, Function<T, ? extends Collection<T>> neighbors,
			Comparator<T> nearest, int cap) {
		if (cap <= 0) return List.of();
		LinkedHashSet<T> selected = new LinkedHashSet<>();
		selected.add(hit);
		List<T> frontier = new ArrayList<>(seeds);
		frontier.add(hit);
		while (!frontier.isEmpty()) {
			List<T> layer = frontier.stream().distinct().sorted(nearest).toList();
			frontier = new ArrayList<>();
			for (T member : layer) {
				if (selected.size() >= cap) return List.copyOf(selected);
				selected.add(member);
			}
			if (selected.size() >= cap) return List.copyOf(selected);
			// Finish selecting the whole breadth layer before expanding any member of it.
			for (T member : layer) {
				if (selected.contains(member)) {
					for (T neighbor : neighbors.apply(member)) {
						if (!selected.contains(neighbor)) frontier.add(neighbor);
					}
				}
			}
		}
		return List.copyOf(selected);
	}
}
