package com.guardvillagers.command.language.llm;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.guardvillagers.command.order.Action;
import com.guardvillagers.command.order.Order;
import com.guardvillagers.command.order.Scope;
import com.guardvillagers.command.order.TargetRef;
import com.guardvillagers.entity.FormationType;
import java.util.List;
import java.util.OptionalInt;
import org.junit.jupiter.api.Test;

final class OrderSchemaTest {
	static String order(String action, String kind, String name, String formation, String target, String radius) {
		return "{\"action\":" + json(action) + ",\"scope_kind\":" + json(kind) + ",\"scope_name\":" + json(name)
				+ ",\"formation\":" + json(formation) + ",\"target\":" + json(target) + ",\"radius\":" + (radius == null ? "null" : radius) + "}";
	}

	static String args(String reply, String... orders) {
		return "{\"orders\":[" + String.join(",", orders) + "],\"reply\":" + json(reply) + "}";
	}

	private static String json(String value) {
		return value == null ? "null" : "\"" + value + "\"";
	}

	@Test
	void toolMatchesDesignSchema() {
		JsonObject tool = OrderSchema.tool();
		assertEquals("function", tool.get("type").getAsString());
		assertEquals("issue_orders", tool.get("name").getAsString());
		assertTrue(tool.get("strict").getAsBoolean());
		JsonObject params = tool.getAsJsonObject("parameters");
		assertEquals(false, params.get("additionalProperties").getAsBoolean());
		assertEquals(List.of("orders", "reply"), strings(params.getAsJsonArray("required")));
		JsonObject orders = params.getAsJsonObject("properties").getAsJsonObject("orders");
		assertEquals(3, orders.get("maxItems").getAsInt());
		JsonObject item = orders.getAsJsonObject("items");
		assertEquals(false, item.get("additionalProperties").getAsBoolean());
		assertEquals(List.of("action", "scope_kind", "scope_name", "formation", "target", "radius"), strings(item.getAsJsonArray("required")));
		JsonObject props = item.getAsJsonObject("properties");
		assertEquals(13, props.getAsJsonObject("action").getAsJsonArray("enum").size());
		assertEquals(List.of("all", "commander", "group", "guard"), strings(props.getAsJsonObject("scope_kind").getAsJsonArray("enum")));
		assertEquals(24, props.getAsJsonObject("scope_name").get("maxLength").getAsInt());
		JsonArray formationEnum = props.getAsJsonObject("formation").getAsJsonArray("enum");
		assertTrue(formationEnum.get(formationEnum.size() - 1).isJsonNull());
		assertEquals(8, props.getAsJsonObject("radius").get("minimum").getAsInt());
		assertEquals(128, props.getAsJsonObject("radius").get("maximum").getAsInt());
		assertEquals(120, params.getAsJsonObject("properties").getAsJsonObject("reply").get("maxLength").getAsInt());
	}

	@Test
	void parsesValidOrders() throws Exception {
		OrderSchema.Parsed parsed = OrderSchema.parseArguments(args("Alpha, form a line.",
				order("form", "group", "Alpha", "line", null, null),
				order("attack_target", "all", null, null, "my_attacker", null),
				order("patrol_here", "guard", "Liam", null, null, "32")));
		assertEquals(List.of(
				Order.form(Scope.group("Alpha"), FormationType.LINE),
				Order.attack(Scope.ALL, TargetRef.MY_ATTACKER),
				Order.patrol(Scope.guard("Liam"), OptionalInt.of(32))), parsed.orders());
		assertEquals("Alpha, form a line.", parsed.reply());
		assertEquals(List.of(), parsed.rejected());
	}

	@Test
	void commanderAndAllIgnoreScopeName() throws Exception {
		OrderSchema.Parsed parsed = OrderSchema.parseArguments(args("", order("come_here", "commander", "whatever", null, null, null)));
		assertEquals(List.of(Order.of(Action.COME_HERE, Scope.COMMANDER)), parsed.orders());
	}

	@Test
	void rejectsOutOfEnumAndExtraFields() throws Exception {
		OrderSchema.Parsed parsed = OrderSchema.parseArguments(args("ok",
				order("dance", "all", null, null, null, null),
				order("hold", "role", "bowmen", null, null, null),
				order("form", "all", null, "wedge", null, null),
				order("attack_target", "all", null, null, "the_player", null),
				order("HOLD", "all", null, null, null, null),
				"{\"action\":\"hold\",\"scope_kind\":\"all\",\"scope_name\":null,\"formation\":null,\"target\":null,\"radius\":null,\"x\":1}"));
		assertEquals(List.of(), parsed.orders());
		// Three are parsed (maxItems), the rest are rejected as too many.
		assertEquals(List.of("bad_action", "bad_scope_kind", "bad_formation", "too_many_orders", "too_many_orders", "too_many_orders"),
				parsed.rejected());

		OrderSchema.Parsed extra = OrderSchema.parseArguments(args("ok",
				"{\"action\":\"hold\",\"scope_kind\":\"all\",\"scope_name\":null,\"formation\":null,\"target\":null,\"radius\":null,\"x\":1}",
				"{\"action\":\"hold\",\"scope_kind\":\"all\"}",
				order("attack_target", "all", null, null, "the_player", null)));
		assertEquals(List.of("unexpected_or_missing_fields", "unexpected_or_missing_fields", "bad_target"), extra.rejected());
	}

	@Test
	void rejectsBadRadiusAndNames() throws Exception {
		OrderSchema.Parsed parsed = OrderSchema.parseArguments(args("",
				order("patrol_here", "all", null, null, null, "7"),
				order("patrol_here", "all", null, null, null, "8.5"),
				order("hold", "group", null, null, null, null)));
		assertEquals(List.of("bad_radius", "bad_radius", "missing_scope_name"), parsed.rejected());

		OrderSchema.Parsed longName = OrderSchema.parseArguments(args("", order("hold", "group", "x".repeat(25), null, null, null)));
		assertEquals(List.of("scope_name_too_long"), longName.rejected());
		OrderSchema.Parsed stringRadius = OrderSchema.parseArguments(args("", order("patrol_here", "all", null, null, null, "\"16\"")));
		assertEquals(List.of("bad_radius"), stringRadius.rejected());
	}

	@Test
	void malformedTopLevelThrows() {
		assertThrows(OrderSchema.MalformedException.class, () -> OrderSchema.parseArguments("{bad json"));
		assertThrows(OrderSchema.MalformedException.class, () -> OrderSchema.parseArguments("[]"));
		assertThrows(OrderSchema.MalformedException.class, () -> OrderSchema.parseArguments("{\"orders\":[],\"reply\":\"x\",\"run\":\"rm\"}"));
		assertThrows(OrderSchema.MalformedException.class, () -> OrderSchema.parseArguments("{\"orders\":[]}"));
		assertThrows(OrderSchema.MalformedException.class, () -> OrderSchema.parseArguments("{\"orders\":{},\"reply\":\"x\"}"));
		assertThrows(OrderSchema.MalformedException.class, () -> OrderSchema.parseArguments("{\"orders\":[],\"reply\":5}"));
	}

	@Test
	void replyIsCapped() throws Exception {
		OrderSchema.Parsed parsed = OrderSchema.parseArguments(args("y".repeat(300)));
		assertEquals(120, parsed.reply().length());
		assertEquals(List.of(), parsed.orders());
	}

	@Test
	void actionNamesAreCaseSensitive() throws Exception {
		assertEquals(List.of("bad_action"), OrderSchema.parseArguments(args("", order("HOLD", "all", null, null, null, null))).rejected());
	}

	private static List<String> strings(JsonArray array) {
		return array.asList().stream().map(e -> e.getAsString()).toList();
	}
}
