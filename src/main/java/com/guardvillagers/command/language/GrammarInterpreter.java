package com.guardvillagers.command.language;

import com.guardvillagers.command.order.Action;
import com.guardvillagers.command.order.Order;
import com.guardvillagers.command.order.OwnerRoster;
import com.guardvillagers.command.order.Scope;
import com.guardvillagers.command.order.TargetRef;
import com.guardvillagers.entity.FormationType;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;
import java.util.OptionalInt;
import java.util.Set;

/**
 * Deterministic grammar for core orders: {@code [addressee] verb-phrase [scope]}, up to three orders joined by "and" /
 * "then". No network, no Minecraft types. Every token of the utterance must be accounted for by the verb phrase, a
 * scope, a radius or a few soft words; the share of accounted tokens (weighted by match quality) is the parse score,
 * and a score under {@link #MIN_SCORE} or a tie between two scopes is a no-match.
 */
public final class GrammarInterpreter {
	public static final double MIN_SCORE = 0.75;
	public static final int MAX_ORDERS = 3;

	public enum Failure {
		/** Nothing left after normalization. */
		EMPTY,
		/** No verb phrase found. */
		NO_ORDER,
		/** A verb phrase was found but too much of the utterance was unexplained. */
		LOW_SCORE,
		/** A name matched two scopes equally, or the utterance named two different scopes for one order. */
		AMBIGUOUS,
		/** More than {@link #MAX_ORDERS} orders. */
		TOO_MANY_ORDERS
	}

	/**
	 * Grammar output. {@code addressed} is true when the utterance starts with or names a scope ("commander", "alpha",
	 * "guards"), which tells the caller to answer "I did not catch that" on a failure instead of staying silent.
	 */
	public record Result(List<Order> orders, double score, boolean addressed, Optional<Failure> failure) {
		public Result {
			orders = List.copyOf(orders);
		}

		public boolean matched() {
			return this.failure.isEmpty() && !this.orders.isEmpty();
		}

		static Result fail(Failure failure, boolean addressed, double score) {
			return new Result(List.of(), score, addressed, Optional.of(failure));
		}
	}

	private record Phrase(List<String> tokens, Action action, FormationType formation, TargetRef target) {
		Order build(Scope scope, OptionalInt radius) {
			return switch (this.action) {
				case FORM -> Order.form(scope, this.formation);
				case ATTACK_TARGET -> Order.attack(scope, this.target);
				case PATROL_HERE -> Order.patrol(scope, radius);
				default -> Order.of(this.action, scope);
			};
		}
	}

	/** A scope found in part of a clause, with the summed token score it explains. */
	private record ScopeMatch(Scope scope, double weight, boolean strong, boolean ambiguous) {
	}

	private record Side(Optional<ScopeMatch> match, double weight) {
		static final Side EMPTY = new Side(Optional.empty(), 0.0);
	}

	private record Clause(Order order, boolean explicitScope, double score, Failure failure) {
		static Clause fail(Failure failure, double score) {
			return new Clause(null, false, score, failure);
		}
	}

	private record Radius(int value, int tokenCount) {
	}

	private static final List<Phrase> PHRASES = buildPhrases();

	private static final Set<String> SPLITTERS = Set.of("and", "then");
	/** Words allowed around a scope or verb phrase without meaning anything ("you guys, go hold"). */
	private static final Set<String> SOFT = Set.of("you", "go", "guys", "lets", "hey");
	private static final Set<String> ALL_WORDS = Set.of(
			"guards", "guard", "everyone", "everybody", "all", "squad", "team", "men", "troops", "soldiers", "units",
			"boys", "lads", "yall");
	private static final Set<String> ALL_GLUE = Set.of("the", "of", "my");
	private static final Set<String> GROUP_PREFIXES = Set.of("squad", "team", "group");
	private static final Set<String> APPOINT_VERBS = Set.of("make", "promote", "appoint");
	private static final Set<String> APPOINT_GLUE = Set.of("to", "the", "our", "a", "an", "new", "as", "my");
	private static final List<String> BOWMEN_WORDS = List.of("archers", "archer", "bowmen", "bowman", "bows");
	private static final List<String> SWORDSMEN_WORDS = List.of("swordsmen", "swordsman", "swords", "fighters");

	/** Parses a transcript or chat line against the speaker's roster. */
	public Result parse(String text, OwnerRoster roster) {
		List<String> tokens = Normalizer.tokens(text);
		if (tokens.isEmpty()) {
			return Result.fail(Failure.EMPTY, false, 0.0);
		}
		boolean addressed = startsWithAddress(tokens, roster);
		List<List<String>> clauses = split(tokens);
		if (clauses.isEmpty()) {
			return Result.fail(Failure.EMPTY, addressed, 0.0);
		}
		if (clauses.size() > MAX_ORDERS) {
			return Result.fail(Failure.TOO_MANY_ORDERS, addressed, 0.0);
		}

		List<Order> orders = new ArrayList<>();
		double score = 1.0;
		Scope carried = null;
		for (List<String> clauseTokens : clauses) {
			Clause clause = parseClause(clauseTokens, roster);
			if (clause.failure() != null) {
				return Result.fail(clause.failure(), addressed, clause.score());
			}
			Order order = clause.order();
			if (clause.explicitScope()) {
				carried = order.scope();
				addressed = true;
			} else if (carried != null) {
				// "alpha form a line and then hold": the second order keeps the first one's scope.
				order = order.withScope(carried);
			}
			orders.add(order);
			score = Math.min(score, clause.score());
		}
		return new Result(orders, score, addressed, Optional.empty());
	}

	/** True when the utterance opens with something that names guards ("commander", "alpha", "guards", "Liam"). */
	public boolean isAddressed(String text, OwnerRoster roster) {
		return startsWithAddress(Normalizer.tokens(text), roster);
	}

	private boolean startsWithAddress(List<String> tokens, OwnerRoster roster) {
		int start = 0;
		while (start < tokens.size() && SOFT.contains(tokens.get(start))) {
			start++;
		}
		for (int n = Math.min(3, tokens.size() - start); n >= 1; n--) {
			Optional<ScopeMatch> match = resolveScope(tokens.subList(start, start + n), roster);
			if (match.isPresent() && !match.get().ambiguous() && match.get().weight() / n >= FuzzyMatcher.ONE_EDIT) {
				return true;
			}
		}
		return false;
	}

	private static List<List<String>> split(List<String> tokens) {
		List<List<String>> clauses = new ArrayList<>();
		List<String> current = new ArrayList<>();
		for (int i = 0; i < tokens.size(); i++) {
			String token = tokens.get(i);
			boolean afterThat = token.equals("after") && i + 1 < tokens.size() && tokens.get(i + 1).equals("that");
			if (SPLITTERS.contains(token) || afterThat) {
				if (afterThat) {
					i++;
				}
				if (!current.isEmpty()) {
					clauses.add(current);
					current = new ArrayList<>();
				}
				continue;
			}
			current.add(token);
		}
		if (!current.isEmpty()) {
			clauses.add(current);
		}
		return clauses;
	}

	private Clause parseClause(List<String> clauseTokens, OwnerRoster roster) {
		List<String> core = new ArrayList<>(clauseTokens);
		Radius radius = extractRadius(core);
		int total = clauseTokens.size();

		if (APPOINT_VERBS.contains(core.getFirst())) {
			Clause appointment = parseAppointment(core, total, roster);
			if (appointment != null) {
				return appointment;
			}
		}

		Phrase bestPhrase = null;
		int bestStart = -1;
		double bestSum = 0.0;
		boolean bestStrong = false;
		for (Phrase phrase : PHRASES) {
			int length = phrase.tokens().size();
			for (int start = 0; start + length <= core.size(); start++) {
				double sum = 0.0;
				boolean strong = false;
				boolean ok = true;
				for (int k = 0; k < length; k++) {
					double s = FuzzyMatcher.wordScore(core.get(start + k), phrase.tokens().get(k));
					if (s <= 0.0) {
						ok = false;
						break;
					}
					sum += s;
					strong |= FuzzyMatcher.isStrong(s);
				}
				if (!ok) {
					continue;
				}
				int bestLength = bestPhrase == null ? 0 : bestPhrase.tokens().size();
				if (length > bestLength || (length == bestLength && sum > bestSum)) {
					bestPhrase = phrase;
					bestStart = start;
					bestSum = sum;
					bestStrong = strong;
				}
			}
		}
		if (bestPhrase == null) {
			return Clause.fail(Failure.NO_ORDER, 0.0);
		}

		int end = bestStart + bestPhrase.tokens().size();
		Side prefix = resolveSide(core.subList(0, bestStart), roster);
		Side suffix = resolveSide(core.subList(end, core.size()), roster);
		Optional<ScopeMatch> scopeMatch = Optional.empty();
		for (Side side : List.of(prefix, suffix)) {
			if (side.match().isEmpty()) {
				continue;
			}
			ScopeMatch match = side.match().get();
			if (match.ambiguous()) {
				return Clause.fail(Failure.AMBIGUOUS, 0.0);
			}
			if (scopeMatch.isPresent() && !scopeMatch.get().scope().sameAs(match.scope())) {
				return Clause.fail(Failure.AMBIGUOUS, 0.0);
			}
			scopeMatch = Optional.of(match);
		}

		boolean usesRadius = bestPhrase.action() == Action.PATROL_HERE && radius != null;
		double accounted = bestSum + prefix.weight() + suffix.weight() + (usesRadius ? radius.tokenCount() : 0);
		double score = accounted / total;
		boolean strong = bestStrong || scopeMatch.map(ScopeMatch::strong).orElse(false);
		if (!strong || score < MIN_SCORE) {
			return Clause.fail(Failure.LOW_SCORE, score);
		}

		Scope scope = scopeMatch.map(ScopeMatch::scope).orElse(Scope.ALL);
		OptionalInt radiusValue = usesRadius ? OptionalInt.of(radius.value()) : OptionalInt.empty();
		return new Clause(bestPhrase.build(scope, radiusValue), scopeMatch.isPresent(), score, null);
	}

	/** "make Liam commander", "promote Ava to captain of alpha". Returns null when this is not an appointment. */
	private Clause parseAppointment(List<String> core, int total, OwnerRoster roster) {
		int rankIndex = -1;
		double rankScore = 0.0;
		Action action = null;
		for (int i = 2; i < core.size() && rankIndex < 0; i++) {
			double commander = FuzzyMatcher.wordScore(core.get(i), "commander");
			double captain = FuzzyMatcher.wordScore(core.get(i), "captain");
			if (commander > 0.0 || captain > 0.0) {
				rankIndex = i;
				action = commander >= captain ? Action.APPOINT_COMMANDER : Action.APPOINT_CAPTAIN;
				rankScore = Math.max(commander, captain);
			}
		}
		if (rankIndex < 0) {
			return null;
		}
		List<String> nameTokens = new ArrayList<>();
		int glue = 0;
		for (String token : core.subList(1, rankIndex)) {
			if (APPOINT_GLUE.contains(token)) {
				glue++;
			} else {
				nameTokens.add(token);
			}
		}
		if (nameTokens.isEmpty()) {
			return null;
		}
		Optional<ScopeMatch> guard = matchNames(nameTokens, List.of(), roster.guardNames());
		if (guard.isEmpty()) {
			return Clause.fail(Failure.LOW_SCORE, 0.0);
		}
		if (guard.get().ambiguous()) {
			return Clause.fail(Failure.AMBIGUOUS, 0.0);
		}
		double accounted = 1.0 + glue + guard.get().weight() + rankScore;
		List<String> rest = core.subList(rankIndex + 1, core.size());
		if (!rest.isEmpty() && (rest.getFirst().equals("of") || rest.getFirst().equals("for")) && rest.size() > 1) {
			// The captain's group follows from the guard's own group; the spoken group is only checked, not used.
			Optional<ScopeMatch> group = matchNames(rest.subList(1, rest.size()), roster.groupNames(), List.of());
			if (group.isPresent() && !group.get().ambiguous()) {
				accounted += 1.0 + group.get().weight();
			}
		}
		double score = accounted / total;
		if (score < MIN_SCORE) {
			return Clause.fail(Failure.LOW_SCORE, score);
		}
		return new Clause(Order.of(action, guard.get().scope()), true, score, null);
	}

	/** Removes "radius 20", "within 20 blocks", "20 blocks" from the clause. */
	private static Radius extractRadius(List<String> core) {
		for (int i = 0; i < core.size(); i++) {
			String token = core.get(i);
			int numberAt = -1;
			int from = i;
			int to;
			if ((token.equals("radius") || token.equals("range") || token.equals("within")) && i + 1 < core.size()) {
				numberAt = core.get(i + 1).equals("of") && i + 2 < core.size() ? i + 2 : i + 1;
			} else if (isNumber(token) && i + 1 < core.size() && (core.get(i + 1).equals("blocks") || core.get(i + 1).equals("block"))) {
				numberAt = i;
			}
			if (numberAt < 0 || !isNumber(core.get(numberAt))) {
				continue;
			}
			to = numberAt + 1;
			if (to < core.size() && (core.get(to).equals("blocks") || core.get(to).equals("block"))) {
				to++;
			}
			int value;
			try {
				value = Integer.parseInt(core.get(numberAt));
			} catch (NumberFormatException e) {
				value = Order.MAX_RADIUS;
			}
			value = Math.clamp(value, Order.MIN_RADIUS, Order.MAX_RADIUS);
			int count = to - from;
			core.subList(from, to).clear();
			return new Radius(value, count);
		}
		return null;
	}

	private static boolean isNumber(String token) {
		return !token.isEmpty() && token.length() <= 6 && token.chars().allMatch(Character::isDigit);
	}

	private Side resolveSide(List<String> side, OwnerRoster roster) {
		if (side.isEmpty()) {
			return Side.EMPTY;
		}
		int start = 0;
		int end = side.size();
		while (start < end && SOFT.contains(side.get(start))) {
			start++;
		}
		while (end > start && SOFT.contains(side.get(end - 1))) {
			end--;
		}
		double soft = side.size() - (end - start);
		if (start == end) {
			return new Side(Optional.empty(), soft);
		}
		Optional<ScopeMatch> match = resolveScope(side.subList(start, end), roster);
		return new Side(match, soft + match.map(ScopeMatch::weight).orElse(0.0));
	}

	private Optional<ScopeMatch> resolveScope(List<String> tokens, OwnerRoster roster) {
		if (tokens.isEmpty()) {
			return Optional.empty();
		}
		double commander = FuzzyMatcher.wordScore(tokens.getFirst(), "commander");
		if (commander > 0.0) {
			if (tokens.size() == 1) {
				return Optional.of(new ScopeMatch(Scope.COMMANDER, commander, FuzzyMatcher.isStrong(commander), false));
			}
			// "commander, alpha hold": the commander relays to the named scope.
			return resolveScope(tokens.subList(1, tokens.size()), roster)
					.map(rest -> new ScopeMatch(rest.scope(), rest.weight() + commander, rest.strong(), rest.ambiguous()));
		}

		boolean anyAllWord = false;
		boolean onlyAllWords = true;
		for (String token : tokens) {
			if (ALL_WORDS.contains(token)) {
				anyAllWord = true;
			} else if (!ALL_GLUE.contains(token)) {
				onlyAllWords = false;
			}
		}
		if (anyAllWord && onlyAllWords) {
			return Optional.of(new ScopeMatch(Scope.ALL, tokens.size(), true, false));
		}

		Optional<ScopeMatch> named = matchNames(tokens, roster.groupNames(), roster.guardNames());
		if (named.isPresent()) {
			return named;
		}

		List<String> roleTokens = stripGroupPrefix(tokens);
		if (roleTokens.size() == 1) {
			Optional<ScopeMatch> role = matchRole(roleTokens.getFirst(), tokens.size() - 1);
			if (role.isPresent()) {
				return role;
			}
		}
		return Optional.empty();
	}

	private static Optional<ScopeMatch> matchRole(String word, int prefixTokens) {
		double bow = bestOf(word, BOWMEN_WORDS);
		double sword = bestOf(word, SWORDSMEN_WORDS);
		if (bow <= 0.0 && sword <= 0.0) {
			return Optional.empty();
		}
		if (bow == sword) {
			return Optional.of(new ScopeMatch(Scope.role(Scope.Role.BOWMEN), bow + prefixTokens, false, true));
		}
		Scope scope = Scope.role(bow > sword ? Scope.Role.BOWMEN : Scope.Role.SWORDSMEN);
		double score = Math.max(bow, sword);
		return Optional.of(new ScopeMatch(scope, score + prefixTokens, FuzzyMatcher.isStrong(score), false));
	}

	private static double bestOf(String word, List<String> options) {
		double best = 0.0;
		for (String option : options) {
			best = Math.max(best, FuzzyMatcher.wordScore(word, option));
		}
		return best;
	}

	/**
	 * Best group or guard name for the tokens. A leading "squad"/"team"/"group" is ignored on both sides when matching
	 * groups, so "squad blue" finds a group named "Blue" or "Squad Blue". A group and a guard (or two different names)
	 * scoring the same is ambiguous; two guards with the identical name are not, since the order can go to both.
	 */
	private static Optional<ScopeMatch> matchNames(List<String> tokens, java.util.Collection<String> groups, java.util.Collection<String> guards) {
		String full = String.join(" ", tokens);
		String stripped = String.join(" ", stripGroupPrefix(tokens));
		double best = 0.0;
		List<Scope> winners = new ArrayList<>();
		for (String group : groups) {
			if (group == null || group.isBlank()) {
				continue;
			}
			List<String> nameTokens = Normalizer.nameTokens(group);
			String key = String.join(" ", nameTokens);
			String keyStripped = String.join(" ", stripGroupPrefix(nameTokens));
			double score = Math.max(Math.max(nameScore(full, key), nameScore(stripped, keyStripped)), nameScore(stripped, key));
			best = consider(winners, best, score, Scope.group(group));
		}
		for (String guard : guards) {
			if (guard == null || guard.isBlank()) {
				continue;
			}
			double score = nameScore(full, String.join(" ", Normalizer.nameTokens(guard)));
			best = consider(winners, best, score, Scope.guard(guard));
		}
		if (winners.isEmpty()) {
			return Optional.empty();
		}
		boolean ambiguous = winners.stream().map(Scope::toString).distinct().count() > 1;
		return Optional.of(new ScopeMatch(winners.getFirst(), best * tokens.size(), FuzzyMatcher.isStrong(best), ambiguous));
	}

	private static double consider(List<Scope> winners, double best, double score, Scope scope) {
		if (score <= 0.0) {
			return best;
		}
		if (score > best) {
			winners.clear();
			winners.add(scope);
			return score;
		}
		if (score == best) {
			winners.add(scope);
		}
		return best;
	}

	private static double nameScore(String spoken, String name) {
		if (spoken.isEmpty() || name.isEmpty()) {
			return 0.0;
		}
		return spoken.equals(name) ? FuzzyMatcher.EXACT : FuzzyMatcher.wordScore(spoken, name);
	}

	private static List<String> stripGroupPrefix(List<String> tokens) {
		if (tokens.size() > 1 && GROUP_PREFIXES.contains(tokens.getFirst())) {
			return tokens.subList(1, tokens.size());
		}
		return tokens;
	}

	private static List<Phrase> buildPhrases() {
		List<Phrase> phrases = new ArrayList<>();
		add(phrases, Action.FOLLOW, "follow me", "follow", "on me", "with me", "come with me", "fall in", "stay with me",
				"stick with me", "follow us", "keep up");
		add(phrases, Action.STAY, "stay", "stay here", "stay put", "wait", "wait here", "stop", "halt");
		add(phrases, Action.HOLD, "hold", "hold position", "hold your position", "hold the line", "hold here",
				"hold this position", "stand your ground", "hold your ground", "hold fast");
		add(phrases, Action.COME_HERE, "come here", "to me", "report to me", "over here", "get over here", "come to me",
				"come over here");
		addAttack(phrases, TargetRef.MY_TARGET, "attack", "attack that", "attack this", "attack it", "attack him",
				"attack her", "attack them", "attack my target", "kill it", "kill that", "kill him", "kill her", "kill them",
				"get him", "get her", "get them", "get it", "get that", "charge");
		addAttack(phrases, TargetRef.MY_ATTACKER, "attack my attacker", "get whoever hit me", "attack whoever hit me",
				"kill whoever hit me", "attack whoever is attacking me", "get whoever is attacking me", "protect me",
				"defend me");
		addAttack(phrases, TargetRef.NEAREST_HOSTILE, "engage");
		for (String verb : List.of("attack", "kill", "get", "engage")) {
			for (String article : List.of("", "the ")) {
				for (String noun : List.of("enemy", "hostile", "mob", "monster")) {
					addAttack(phrases, TargetRef.NEAREST_HOSTILE, verb + " " + article + "nearest " + noun);
				}
			}
		}
		add(phrases, Action.DEFEND_VILLAGE, "defend the village", "guard the village", "protect the village", "to the bell",
				"go to the bell", "defend the bell", "defend village", "protect village", "guard village");
		add(phrases, Action.PATROL_HERE, "patrol here", "patrol this area", "guard this area", "set up here", "patrol",
				"patrol the area", "patrol around here", "guard here", "secure this area");
		for (FormationType formation : List.of(FormationType.LINE, FormationType.CIRCLE, FormationType.SQUARE)) {
			String shape = formation.name().toLowerCase(java.util.Locale.ROOT);
			for (String lead : List.of("form", "form a", "make a", "get in a", "get into a", "form up in a")) {
				addForm(phrases, formation, lead + " " + shape);
			}
		}
		addForm(phrases, FormationType.LINE, "line up");
		addForm(phrases, FormationType.CIRCLE, "circle up");
		add(phrases, Action.RETREAT, "retreat", "fall back", "pull back", "back off");
		add(phrases, Action.AS_YOU_WERE, "as you were", "carry on", "commander take over", "take over", "at ease",
				"stand down", "dismissed");
		add(phrases, Action.STATUS_REPORT, "status", "report", "status report", "how are we doing", "sitrep",
				"report status", "give me a report", "give me a status report", "how are you doing", "whats the status");
		return List.copyOf(phrases);
	}

	private static void add(List<Phrase> phrases, Action action, String... texts) {
		for (String text : texts) {
			phrases.add(new Phrase(Arrays.asList(text.split(" ")), action, null, null));
		}
	}

	private static void addAttack(List<Phrase> phrases, TargetRef target, String... texts) {
		for (String text : texts) {
			phrases.add(new Phrase(Arrays.asList(text.split(" ")), Action.ATTACK_TARGET, null, target));
		}
	}

	private static void addForm(List<Phrase> phrases, FormationType formation, String text) {
		phrases.add(new Phrase(Arrays.asList(text.split(" ")), Action.FORM, formation, null));
	}
}
