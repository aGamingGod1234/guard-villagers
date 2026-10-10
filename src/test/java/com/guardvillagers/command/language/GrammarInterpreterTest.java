package com.guardvillagers.command.language;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.guardvillagers.command.order.Action;
import com.guardvillagers.command.order.Order;
import com.guardvillagers.command.order.OwnerRoster;
import com.guardvillagers.command.order.Scope;
import com.guardvillagers.command.order.TargetRef;
import com.guardvillagers.entity.FormationType;
import java.util.List;
import java.util.Optional;
import java.util.OptionalInt;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

final class GrammarInterpreterTest {
	private static final OwnerRoster ROSTER = OwnerRoster.of(List.of("Alpha", "Beta", "Squad Blue"), List.of("Liam", "Ava", "Noah"));
	private final GrammarInterpreter grammar = new GrammarInterpreter();

	private Order single(String text) {
		return single(text, ROSTER);
	}

	private Order single(String text, OwnerRoster roster) {
		GrammarInterpreter.Result result = this.grammar.parse(text, roster);
		assertTrue(result.matched(), () -> "no match for '" + text + "': " + result.failure() + " score " + result.score());
		assertEquals(1, result.orders().size(), () -> "orders for '" + text + "': " + result.orders());
		assertTrue(result.score() >= GrammarInterpreter.MIN_SCORE);
		return result.orders().getFirst();
	}

	private void assertNoMatch(String text, GrammarInterpreter.Failure expected) {
		GrammarInterpreter.Result result = this.grammar.parse(text, ROSTER);
		assertFalse(result.matched(), () -> "unexpected match for '" + text + "': " + result.orders());
		if (expected != null) {
			assertEquals(Optional.of(expected), result.failure(), () -> "failure for '" + text + "'");
		}
	}

	private void assertAll(String text, Action action) {
		Order order = single(text);
		assertEquals(action, order.action(), text);
		assertEquals(Scope.ALL, order.scope(), text);
	}

	// One test per row of the phrasing table (design section 5).

	@ParameterizedTest
	@ValueSource(strings = {"follow me", "on me", "with me", "come with me", "fall in", "Follow me, please!"})
	void follow(String text) {
		assertAll(text, Action.FOLLOW);
	}

	@ParameterizedTest
	@ValueSource(strings = {"stay", "wait here", "stay here", "stop"})
	void stay(String text) {
		assertAll(text, Action.STAY);
	}

	@ParameterizedTest
	@ValueSource(strings = {"hold", "hold position", "hold the line", "hold here", "stand your ground"})
	void hold(String text) {
		assertAll(text, Action.HOLD);
	}

	@ParameterizedTest
	@ValueSource(strings = {"come here", "to me", "report to me", "over here"})
	void comeHere(String text) {
		assertAll(text, Action.COME_HERE);
		Order commander = single("commander, " + text);
		assertEquals(Action.COME_HERE, commander.action());
		assertEquals(Scope.COMMANDER, commander.scope());
	}

	@ParameterizedTest
	@ValueSource(strings = {"attack", "attack that", "attack my target", "kill it", "get him", "charge"})
	void attackTarget(String text) {
		Order order = single(text);
		assertEquals(Action.ATTACK_TARGET, order.action());
		assertEquals(Optional.of(TargetRef.MY_TARGET), order.target());
	}

	@Test
	void attackOtherTargets() {
		assertEquals(Optional.of(TargetRef.NEAREST_HOSTILE), single("attack the nearest enemy").target());
		assertEquals(Optional.of(TargetRef.MY_ATTACKER), single("get whoever hit me").target());
	}

	@ParameterizedTest
	@ValueSource(strings = {"defend the village", "guard the village", "protect the village", "to the bell"})
	void defendVillage(String text) {
		assertAll(text, Action.DEFEND_VILLAGE);
	}

	@ParameterizedTest
	@ValueSource(strings = {"patrol here", "patrol this area", "guard this area", "set up here"})
	void patrolHere(String text) {
		Order order = single(text);
		assertEquals(Action.PATROL_HERE, order.action());
		assertEquals(OptionalInt.empty(), order.radius());
	}

	@Test
	void patrolRadius() {
		assertEquals(OptionalInt.of(20), single("set up here radius twenty").radius());
		assertEquals(OptionalInt.of(32), single("patrol here within 32 blocks").radius());
		assertEquals(OptionalInt.of(Order.MAX_RADIUS), single("patrol this area radius 500").radius());
		assertEquals(OptionalInt.of(Order.MIN_RADIUS), single("patrol here radius two").radius());
	}

	@ParameterizedTest
	@ValueSource(strings = {"form a line:LINE", "form line:LINE", "line up:LINE", "form a circle:CIRCLE", "circle up:CIRCLE",
			"make a circle:CIRCLE", "form a square:SQUARE", "form square:SQUARE", "formation line:LINE"})
	void form(String row) {
		String[] parts = row.split(":");
		Order order = single(parts[0]);
		assertEquals(Action.FORM, order.action());
		assertEquals(Optional.of(FormationType.valueOf(parts[1])), order.formation());
	}

	@ParameterizedTest
	@ValueSource(strings = {"retreat", "fall back", "pull back"})
	void retreat(String text) {
		assertAll(text, Action.RETREAT);
	}

	@ParameterizedTest
	@ValueSource(strings = {"as you were", "carry on", "commander take over", "commander, take over", "at ease"})
	void asYouWere(String text) {
		assertAll(text, Action.AS_YOU_WERE);
	}

	@ParameterizedTest
	@ValueSource(strings = {"status", "report", "status report", "how are we doing"})
	void statusReport(String text) {
		assertAll(text, Action.STATUS_REPORT);
	}

	@Test
	void appoint() {
		Order commander = single("make Liam commander");
		assertEquals(Action.APPOINT_COMMANDER, commander.action());
		assertEquals(Scope.guard("Liam"), commander.scope());

		Order captain = single("make Ava captain");
		assertEquals(Action.APPOINT_CAPTAIN, captain.action());
		assertEquals(Scope.guard("Ava"), captain.scope());

		assertEquals(Scope.guard("Ava"), single("make ava captain of alpha").scope());
		assertEquals(Action.APPOINT_CAPTAIN, single("promote Noah to captain").action());
		assertEquals(Action.APPOINT_COMMANDER, single("make Liam the new commander").action());
		assertNoMatch("make Steve commander", null);
	}

	// Scope resolution.

	@Test
	void groupGuardAndAllScopes() {
		assertEquals(Scope.group("Alpha"), single("alpha hold the line").scope());
		assertEquals(Scope.group("Alpha"), single("hold the line, alpha").scope());
		assertEquals(Scope.group("Beta"), single("Beta, fall back").scope());
		assertEquals(Scope.guard("Liam"), single("Liam, come here").scope());
		assertEquals(Scope.ALL, single("everyone follow me").scope());
		assertEquals(Scope.ALL, single("guards, stay here").scope());
		assertEquals(Scope.ALL, single("follow me guards").scope());
		assertEquals(Scope.ALL, single("squad, hold").scope());
		assertEquals(Scope.ALL, single("all of you retreat").scope());
	}

	@Test
	void squadPrefixIsDropped() {
		assertEquals(Scope.group("Squad Blue"), single("squad blue hold").scope());
		assertEquals(Scope.group("Squad Blue"), single("blue, hold").scope());
		OwnerRoster plainBlue = OwnerRoster.of(List.of("Blue"), List.of());
		assertEquals(Scope.group("Blue"), single("squad blue hold", plainBlue).scope());
		assertEquals(Scope.group("Blue"), single("team blue follow me", plainBlue).scope());
	}

	@Test
	void roleWordsWhenNoGroupHasThatName() {
		assertEquals(Scope.role(Scope.Role.BOWMEN), single("archers follow me").scope());
		assertEquals(Scope.role(Scope.Role.BOWMEN), single("bowmen hold").scope());
		assertEquals(Scope.role(Scope.Role.SWORDSMEN), single("swordsmen charge").scope());
		OwnerRoster withArchersGroup = OwnerRoster.of(List.of("Archers"), List.of());
		assertEquals(Scope.group("Archers"), single("archers follow me", withArchersGroup).scope());
	}

	// Fuzzy matching.

	@Test
	void fuzzyAndKnownConfusions() {
		assertEquals(Action.HOLD, single("hole the line").action());
		assertEquals(Action.HOLD, single("whole position").action());
		Order commander = single("commanda come here");
		assertEquals(Action.COME_HERE, commander.action());
		assertEquals(Scope.COMMANDER, commander.scope());
		assertEquals(Scope.COMMANDER, single("commandos, hold").scope());
		assertEquals(Action.FOLLOW, single("foller me").action());
		assertEquals(Scope.group("Alpha"), single("alpah hold").scope());
		assertEquals(Action.RETREAT, single("alpha retreet").action());
		assertEquals(Action.PATROL_HERE, single("petrol here").action());
	}

	@Test
	void editDistanceAloneIsNotEnough() {
		// "shop" is one edit from "stop", but a single fuzzy word with nothing exact around it is not an order.
		assertNoMatch("shop", GrammarInterpreter.Failure.LOW_SCORE);
		assertNoMatch("retreet", GrammarInterpreter.Failure.LOW_SCORE);
	}

	// Multiple orders.

	@Test
	void splitsMultipleOrders() {
		GrammarInterpreter.Result result = this.grammar.parse("alpha hold the line and archers follow me", ROSTER);
		assertTrue(result.matched());
		assertEquals(List.of(Order.of(Action.HOLD, Scope.group("Alpha")), Order.of(Action.FOLLOW, Scope.role(Scope.Role.BOWMEN))),
				result.orders());

		GrammarInterpreter.Result carried = this.grammar.parse("alpha form a line and then hold", ROSTER);
		assertEquals(List.of(Order.form(Scope.group("Alpha"), FormationType.LINE), Order.of(Action.HOLD, Scope.group("Alpha"))),
				carried.orders());

		GrammarInterpreter.Result three = this.grammar.parse("commander come here, then beta retreat and liam stay", ROSTER);
		assertEquals(3, three.orders().size());
		assertEquals(Scope.COMMANDER, three.orders().get(0).scope());
		assertEquals(Scope.group("Beta"), three.orders().get(1).scope());
		assertEquals(Scope.guard("Liam"), three.orders().get(2).scope());

		assertNoMatch("hold and stay and follow me and retreat", GrammarInterpreter.Failure.TOO_MANY_ORDERS);
		assertNoMatch("alpha hold and sing me a song", null);
	}

	// Ambiguity and junk.

	@Test
	void ambiguityIsNoMatch() {
		OwnerRoster clash = OwnerRoster.of(List.of("Ava"), List.of("Ava"));
		GrammarInterpreter.Result sameName = this.grammar.parse("ava hold", clash);
		assertEquals(Optional.of(GrammarInterpreter.Failure.AMBIGUOUS), sameName.failure());

		OwnerRoster similar = OwnerRoster.of(List.of(), List.of("Liam", "Lian"));
		assertEquals(Optional.of(GrammarInterpreter.Failure.AMBIGUOUS), this.grammar.parse("liar hold", similar).failure());

		assertNoMatch("alpha hold liam", GrammarInterpreter.Failure.AMBIGUOUS);
	}

	@ParameterizedTest
	@ValueSource(strings = {"the weather is nice today", "I am going to the shop", "lol", "", "   ", "can you hold this for me",
			"uh um", "what are you doing tonight", "lets go mining", "I stopped by the village earlier"})
	void junkIsNoMatch(String text) {
		assertNoMatch(text, null);
	}

	@Test
	void addressedFlag() {
		assertTrue(this.grammar.parse("commander, sing a song", ROSTER).addressed());
		assertTrue(this.grammar.parse("alpha do a barrel roll", ROSTER).addressed());
		assertFalse(this.grammar.parse("sing a song", ROSTER).addressed());
		assertTrue(this.grammar.parse("alpha hold", ROSTER).addressed());
		assertFalse(this.grammar.parse("hold", ROSTER).addressed());
		assertTrue(this.grammar.isAddressed("Guards, dance", ROSTER));
	}
}
