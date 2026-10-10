package com.guardvillagers.command.language;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import org.junit.jupiter.api.Test;

final class NormalizerAndFuzzyTest {
	@Test
	void lowercasesStripsPunctuationAndFillers() {
		assertEquals("hold the line", Normalizer.normalize("Hold the LINE, please!"));
		assertEquals("follow me", Normalizer.normalize("Uh, can you follow me now?"));
		assertEquals("i want", Normalizer.normalize("I want"));
		assertEquals("whats the status", Normalizer.normalize("What's the status?"));
		assertEquals("cafe", Normalizer.normalize("Café"));
		assertEquals("", Normalizer.normalize("   ...  "));
		assertEquals(List.of(), Normalizer.tokens(null));
	}

	@Test
	void convertsNumberWords() {
		assertEquals("patrol here radius 25", Normalizer.normalize("patrol here, radius twenty-five"));
		assertEquals("128", Normalizer.normalize("one hundred twenty eight"));
		assertEquals("105", Normalizer.normalize("one hundred five"));
		assertEquals("2 20", Normalizer.normalize("two twenty"));
		assertEquals("12 blocks", Normalizer.normalize("twelve blocks"));
		assertEquals("to me", Normalizer.normalize("to me"));
		assertEquals("everyone", Normalizer.normalize("everyone"));
	}

	@Test
	void capsInputLength() {
		String huge = "hold ".repeat(1000);
		assertTrue(Normalizer.tokens(huge).size() <= Normalizer.MAX_INPUT_CHARS / 5 + 1);
	}

	@Test
	void namesKeepFillerWords() {
		assertEquals(List.of("squad", "now"), Normalizer.nameTokens("Squad Now"));
	}

	@Test
	void damerauLevenshteinDistance() {
		assertEquals(0, FuzzyMatcher.distance("hold", "hold"));
		assertEquals(1, FuzzyMatcher.distance("form", "from"));
		assertEquals(1, FuzzyMatcher.distance("alpah", "alpha"));
		assertEquals(2, FuzzyMatcher.distance("comander", "commandr"), "insert + delete");
		assertEquals(3, FuzzyMatcher.distance("abc", ""));
	}

	@Test
	void wordScoresFollowLengthThresholds() {
		assertEquals(FuzzyMatcher.EXACT, FuzzyMatcher.wordScore("hold", "hold"));
		assertEquals(FuzzyMatcher.CONFUSION, FuzzyMatcher.wordScore("hole", "hold"));
		assertEquals(FuzzyMatcher.CONFUSION, FuzzyMatcher.wordScore("whole", "hold"));
		assertEquals(FuzzyMatcher.CONFUSION, FuzzyMatcher.wordScore("commanda", "commander"));
		assertEquals(FuzzyMatcher.CONFUSION, FuzzyMatcher.wordScore("foller", "follow"));
		assertEquals(FuzzyMatcher.ONE_EDIT, FuzzyMatcher.wordScore("folow", "follow"));
		assertEquals(FuzzyMatcher.TWO_EDITS, FuzzyMatcher.wordScore("retrete", "retreat"));
		// 4-6 letters: one edit only.
		assertEquals(FuzzyMatcher.ONE_EDIT, FuzzyMatcher.wordScore("holt", "hold"));
		assertEquals(0.0, FuzzyMatcher.wordScore("halt", "hold"));
		assertEquals(0.0, FuzzyMatcher.wordScore("stip", "patrol"));
		assertEquals(0.0, FuzzyMatcher.wordScore("plz", "pls"));
		// Under 4 letters: exact only.
		assertEquals(0.0, FuzzyMatcher.wordScore("in", "on"));
		assertEquals(0.0, FuzzyMatcher.wordScore("mi", "me"));
		assertFalse(FuzzyMatcher.isStrong(FuzzyMatcher.ONE_EDIT));
		assertTrue(FuzzyMatcher.isStrong(FuzzyMatcher.CONFUSION));
	}

	@Test
	void bestNamePrefersExactAndFlagsTies() {
		FuzzyMatcher.NameMatch exact = FuzzyMatcher.bestName(List.of("liam"), List.of("Liam", "Lian")).orElseThrow();
		assertEquals("Liam", exact.candidate());
		assertFalse(exact.ambiguous());

		FuzzyMatcher.NameMatch tie = FuzzyMatcher.bestName(List.of("liar"), List.of("Liam", "Lian")).orElseThrow();
		assertTrue(tie.ambiguous());

		assertEquals("Squad Blue", FuzzyMatcher.bestName(List.of("squad", "blue"), List.of("Squad Blue", "Red")).orElseThrow().candidate());
		assertTrue(FuzzyMatcher.bestName(List.of("zzz"), List.of("Liam")).isEmpty());
	}
}
