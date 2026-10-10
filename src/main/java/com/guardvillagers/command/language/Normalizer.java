package com.guardvillagers.command.language;

import java.text.Normalizer.Form;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * Turns a transcript or chat line into grammar tokens: lowercase, accents and punctuation stripped, number words
 * turned into digits, filler words ("uh", "please", "now", "okay") and polite lead-ins ("can you") removed.
 */
public final class Normalizer {
	public static final int MAX_INPUT_CHARS = 512;

	private static final Set<String> FILLERS = Set.of(
			"uh", "uhh", "um", "umm", "er", "erm", "ah", "hmm", "mm",
			"please", "now", "okay", "ok", "hey", "yo", "just", "alright", "quickly");

	private static final List<List<String>> FILLER_PHRASES = List.of(
			List.of("i", "want", "you", "to"),
			List.of("i", "need", "you", "to"),
			List.of("can", "you"),
			List.of("could", "you"),
			List.of("would", "you"),
			List.of("will", "you"),
			List.of("go", "ahead", "and"),
			List.of("right", "away"));

	private static final Map<String, Integer> UNITS = Map.ofEntries(
			Map.entry("zero", 0), Map.entry("one", 1), Map.entry("two", 2), Map.entry("three", 3), Map.entry("four", 4),
			Map.entry("five", 5), Map.entry("six", 6), Map.entry("seven", 7), Map.entry("eight", 8), Map.entry("nine", 9),
			Map.entry("ten", 10), Map.entry("eleven", 11), Map.entry("twelve", 12), Map.entry("thirteen", 13),
			Map.entry("fourteen", 14), Map.entry("fifteen", 15), Map.entry("sixteen", 16), Map.entry("seventeen", 17),
			Map.entry("eighteen", 18), Map.entry("nineteen", 19));

	private static final Map<String, Integer> TENS = Map.of(
			"twenty", 20, "thirty", 30, "forty", 40, "fifty", 50, "sixty", 60, "seventy", 70, "eighty", 80, "ninety", 90);

	private Normalizer() {
	}

	/** Grammar tokens for a spoken or typed command. */
	public static List<String> tokens(String text) {
		List<String> raw = rawTokens(text);
		List<String> withNumbers = convertNumbers(raw);
		return removeFillers(withNumbers);
	}

	/** {@link #tokens(String)} joined by single spaces. */
	public static String normalize(String text) {
		return String.join(" ", tokens(text));
	}

	/** Tokens for a roster name ("Squad Blue", "Liam"): same cleanup but fillers are kept, they may be part of a name. */
	public static List<String> nameTokens(String name) {
		return convertNumbers(rawTokens(name));
	}

	private static List<String> rawTokens(String text) {
		if (text == null) {
			return List.of();
		}
		String capped = text.length() > MAX_INPUT_CHARS ? text.substring(0, MAX_INPUT_CHARS) : text;
		String folded = java.text.Normalizer.normalize(capped, Form.NFKD)
				.replaceAll("\\p{M}+", "")
				.toLowerCase(Locale.ROOT)
				.replace("'", "")
				.replace("’", "");
		List<String> out = new ArrayList<>();
		for (String token : folded.split("[^a-z0-9]+")) {
			if (!token.isEmpty()) {
				out.add(token);
			}
		}
		return out;
	}

	private static List<String> convertNumbers(List<String> tokens) {
		List<String> out = new ArrayList<>(tokens.size());
		int i = 0;
		while (i < tokens.size()) {
			if (!isNumberWord(tokens.get(i))) {
				out.add(tokens.get(i));
				i++;
				continue;
			}
			// "one hundred twenty eight" -> 128; a word that cannot extend the number ("two twenty") starts a new one.
			int current = -1;
			while (i < tokens.size() && isNumberWord(tokens.get(i))) {
				String word = tokens.get(i);
				boolean roundHundreds = current >= 100 && current % 100 == 0;
				if (word.equals("hundred")) {
					if (current >= 1 && current <= 9) {
						current *= 100;
					} else {
						current = flush(out, current, 100);
					}
				} else if (TENS.containsKey(word)) {
					int tens = TENS.get(word);
					current = roundHundreds ? current + tens : flush(out, current, tens);
				} else {
					int unit = UNITS.get(word);
					int lastTwo = current % 100;
					boolean bareTens = current > 0 && lastTwo >= 20 && lastTwo % 10 == 0;
					current = (roundHundreds || bareTens) && unit > 0 ? current + unit : flush(out, current, unit);
				}
				i++;
			}
			flush(out, current, 0);
		}
		return out;
	}

	/** Emits the number being built (if any) and returns the start value of the next one. */
	private static int flush(List<String> out, int current, int next) {
		if (current >= 0) {
			out.add(Integer.toString(current));
		}
		return next;
	}

	private static boolean isNumberWord(String word) {
		return UNITS.containsKey(word) || TENS.containsKey(word) || word.equals("hundred");
	}

	private static List<String> removeFillers(List<String> tokens) {
		List<String> out = new ArrayList<>(tokens.size());
		int i = 0;
		outer:
		while (i < tokens.size()) {
			for (List<String> phrase : FILLER_PHRASES) {
				if (i + phrase.size() <= tokens.size() && tokens.subList(i, i + phrase.size()).equals(phrase)) {
					i += phrase.size();
					continue outer;
				}
			}
			String token = tokens.get(i);
			if (!FILLERS.contains(token)) {
				out.add(token);
			}
			i++;
		}
		return out;
	}
}
