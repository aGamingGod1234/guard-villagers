package com.guardvillagers.command.language;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Word and name matching tolerant of speech-to-text mistakes. A spoken word matches an expected word when it is equal,
 * is a known STT confusion for it ("hole" for "hold"), or is within Damerau-Levenshtein distance 1 (expected word of
 * 4 to 6 letters) or 2 (7 letters or more). Shorter words must match exactly.
 */
public final class FuzzyMatcher {
	public static final double EXACT = 1.0;
	public static final double CONFUSION = 0.95;
	public static final double ONE_EDIT = 0.9;
	public static final double TWO_EDITS = 0.8;

	/** Spoken word -> word the grammar expects. */
	private static final Map<String, String> CONFUSIONS = Map.ofEntries(
			Map.entry("hole", "hold"),
			Map.entry("whole", "hold"),
			Map.entry("holed", "hold"),
			Map.entry("commanda", "commander"),
			Map.entry("commandos", "commander"),
			Map.entry("commando", "commander"),
			Map.entry("commandeer", "commander"),
			Map.entry("foller", "follow"),
			Map.entry("folla", "follow"),
			Map.entry("petrol", "patrol"),
			Map.entry("formation", "form"),
			Map.entry("retreating", "retreat"),
			Map.entry("captin", "captain"));

	/** A name candidate with its match score. {@code ambiguous} is true when another candidate scored the same. */
	public record NameMatch(String candidate, double score, boolean ambiguous) {
	}

	private FuzzyMatcher() {
	}

	/** Largest allowed edit distance for an expected word of this length. */
	public static int maxDistance(int expectedLength) {
		if (expectedLength >= 7) {
			return 2;
		}
		return expectedLength >= 4 ? 1 : 0;
	}

	/** Score in (0, 1] when {@code spoken} matches {@code expected}, or 0 when it does not. */
	public static double wordScore(String spoken, String expected) {
		if (spoken.equals(expected)) {
			return EXACT;
		}
		if (expected.equals(CONFUSIONS.get(spoken))) {
			return CONFUSION;
		}
		return editScore(spoken, expected);
	}

	/** True when the score came from an exact match or a known confusion, not from edit distance. */
	public static boolean isStrong(double score) {
		return score >= CONFUSION;
	}

	/**
	 * Best roster name for the spoken tokens. Names are compared as whole normalized strings, so "squad blue" matches
	 * "Squad Blue". Returns empty when nothing is close enough.
	 */
	public static Optional<NameMatch> bestName(List<String> spokenTokens, Collection<String> candidates) {
		if (spokenTokens.isEmpty()) {
			return Optional.empty();
		}
		String spoken = String.join(" ", spokenTokens);
		double best = 0.0;
		List<String> winners = new ArrayList<>();
		for (String candidate : candidates) {
			if (candidate == null) {
				continue;
			}
			String normalized = String.join(" ", Normalizer.nameTokens(candidate));
			if (normalized.isEmpty()) {
				continue;
			}
			double score = spoken.equals(normalized) ? EXACT : editScore(spoken, normalized);
			if (score <= 0.0) {
				continue;
			}
			if (score > best) {
				best = score;
				winners.clear();
				winners.add(candidate.strip());
			} else if (score == best && !winners.contains(candidate.strip())) {
				winners.add(candidate.strip());
			}
		}
		if (winners.isEmpty()) {
			return Optional.empty();
		}
		return Optional.of(new NameMatch(winners.getFirst(), best, winners.size() > 1));
	}

	/** Optimal string alignment distance (Damerau-Levenshtein with adjacent transpositions). */
	public static int distance(String a, String b) {
		int n = a.length();
		int m = b.length();
		int[][] d = new int[n + 1][m + 1];
		for (int i = 0; i <= n; i++) {
			d[i][0] = i;
		}
		for (int j = 0; j <= m; j++) {
			d[0][j] = j;
		}
		for (int i = 1; i <= n; i++) {
			for (int j = 1; j <= m; j++) {
				int cost = a.charAt(i - 1) == b.charAt(j - 1) ? 0 : 1;
				int value = Math.min(Math.min(d[i - 1][j] + 1, d[i][j - 1] + 1), d[i - 1][j - 1] + cost);
				if (i > 1 && j > 1 && a.charAt(i - 1) == b.charAt(j - 2) && a.charAt(i - 2) == b.charAt(j - 1)) {
					value = Math.min(value, d[i - 2][j - 2] + 1);
				}
				d[i][j] = value;
			}
		}
		return d[n][m];
	}

	private static double editScore(String spoken, String expected) {
		int allowed = maxDistance(expected.length());
		if (allowed == 0 || spoken.length() < 3 || Math.abs(spoken.length() - expected.length()) > allowed) {
			return 0.0;
		}
		int distance = distance(spoken, expected);
		if (distance == 0) {
			return EXACT;
		}
		if (distance > allowed) {
			return 0.0;
		}
		return distance == 1 ? ONE_EDIT : TWO_EDITS;
	}
}
