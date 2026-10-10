package com.guardvillagers.command.language.llm;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonNull;
import com.google.gson.JsonObject;
import com.google.gson.JsonParseException;
import com.google.gson.JsonParser;
import com.google.gson.JsonPrimitive;
import com.guardvillagers.command.order.Action;
import com.guardvillagers.command.order.Order;
import com.guardvillagers.command.order.OrderValidator;
import com.guardvillagers.command.order.Scope;
import com.guardvillagers.command.order.TargetRef;
import com.guardvillagers.entity.FormationType;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.OptionalInt;
import java.util.Set;

/**
 * The one strict function tool the model may call ({@code issue_orders}) and the parser for its arguments. The parser
 * re-checks everything strict mode promises (exact keys, enum values, ranges) because model output is untrusted: an
 * order with an extra or missing field or an out-of-enum value is dropped; a malformed top level drops the reply.
 */
public final class OrderSchema {
	public static final String TOOL_NAME = "issue_orders";
	public static final int MAX_ORDERS = OrderValidator.MAX_ORDERS;
	public static final int MAX_SCOPE_NAME = 24;
	public static final int MAX_REPLY = OrderValidator.MAX_REPLY_CODE_POINTS;

	private static final Set<String> TOP_KEYS = Set.of("orders", "reply");
	private static final Set<String> ORDER_KEYS = Set.of("action", "scope_kind", "scope_name", "formation", "target", "radius");
	private static final List<String> SCOPE_KINDS = List.of("all", "commander", "group", "guard");
	private static final List<String> FORMATIONS = List.of("line", "square", "circle", "follow");

	/** Orders the model produced that survived schema checks (not yet roster-validated), plus its reply. */
	public record Parsed(List<Order> orders, List<String> rejected, String reply) {
		public Parsed {
			orders = List.copyOf(orders);
			rejected = List.copyOf(rejected);
		}
	}

	/** The top-level arguments object is not the agreed shape. */
	public static final class MalformedException extends Exception {
		public MalformedException(String message) {
			super(message);
		}
	}

	private OrderSchema() {
	}

	/** Builds the Responses API function tool definition. A fresh object each call, safe to mutate. */
	public static JsonObject tool() {
		JsonObject tool = new JsonObject();
		tool.addProperty("type", "function");
		tool.addProperty("name", TOOL_NAME);
		tool.addProperty("description", "Translate the player's words into guard orders. Use only the listed groups and guards. "
				+ "If unclear, return no orders and a short clarifying reply.");
		tool.addProperty("strict", true);

		JsonObject item = new JsonObject();
		item.addProperty("type", "object");
		item.addProperty("additionalProperties", false);
		item.add("required", strings(ORDER_KEYS_IN_ORDER));
		JsonObject props = new JsonObject();
		List<String> actions = new ArrayList<>();
		for (Action action : Action.values()) {
			actions.add(action.wireName());
		}
		props.add("action", enumOf("string", actions, false));
		props.add("scope_kind", enumOf("string", SCOPE_KINDS, false));
		JsonObject scopeName = new JsonObject();
		scopeName.add("type", strings(List.of("string", "null")));
		scopeName.addProperty("maxLength", MAX_SCOPE_NAME);
		props.add("scope_name", scopeName);
		props.add("formation", enumOf(null, FORMATIONS, true));
		List<String> targets = new ArrayList<>();
		for (TargetRef ref : TargetRef.values()) {
			targets.add(ref.wireName());
		}
		props.add("target", enumOf(null, targets, true));
		JsonObject radius = new JsonObject();
		radius.add("type", strings(List.of("integer", "null")));
		radius.addProperty("minimum", Order.MIN_RADIUS);
		radius.addProperty("maximum", Order.MAX_RADIUS);
		props.add("radius", radius);
		item.add("properties", props);

		JsonObject orders = new JsonObject();
		orders.addProperty("type", "array");
		orders.addProperty("maxItems", MAX_ORDERS);
		orders.add("items", item);

		JsonObject reply = new JsonObject();
		reply.addProperty("type", "string");
		reply.addProperty("maxLength", MAX_REPLY);

		JsonObject parameters = new JsonObject();
		parameters.addProperty("type", "object");
		parameters.addProperty("additionalProperties", false);
		parameters.add("required", strings(List.of("orders", "reply")));
		JsonObject topProps = new JsonObject();
		topProps.add("orders", orders);
		topProps.add("reply", reply);
		parameters.add("properties", topProps);
		tool.add("parameters", parameters);
		return tool;
	}

	private static final List<String> ORDER_KEYS_IN_ORDER = List.of("action", "scope_kind", "scope_name", "formation", "target", "radius");

	/** Parses the {@code arguments} string of an {@code issue_orders} call. */
	public static Parsed parseArguments(String argumentsJson) throws MalformedException {
		JsonElement root;
		try {
			root = JsonParser.parseString(argumentsJson == null ? "" : argumentsJson);
		} catch (JsonParseException e) {
			throw new MalformedException("arguments are not JSON");
		}
		if (!root.isJsonObject()) {
			throw new MalformedException("arguments are not an object");
		}
		JsonObject object = root.getAsJsonObject();
		if (!object.keySet().equals(TOP_KEYS)) {
			throw new MalformedException("unexpected top-level fields");
		}
		JsonElement ordersElement = object.get("orders");
		JsonElement replyElement = object.get("reply");
		if (!ordersElement.isJsonArray() || !isString(replyElement)) {
			throw new MalformedException("orders must be an array and reply a string");
		}
		List<Order> orders = new ArrayList<>();
		List<String> rejected = new ArrayList<>();
		JsonArray array = ordersElement.getAsJsonArray();
		for (int i = 0; i < array.size(); i++) {
			if (i >= MAX_ORDERS) {
				rejected.add("too_many_orders");
				continue;
			}
			String problem = null;
			Order order = null;
			try {
				order = parseOrder(array.get(i));
			} catch (IllegalArgumentException e) {
				problem = e.getMessage();
			}
			if (order != null) {
				orders.add(order);
			} else {
				rejected.add(problem);
			}
		}
		return new Parsed(orders, rejected, OrderValidator.capReply(replyElement.getAsString()));
	}

	private static Order parseOrder(JsonElement element) {
		if (!element.isJsonObject()) {
			throw new IllegalArgumentException("order_not_object");
		}
		JsonObject object = element.getAsJsonObject();
		if (!object.keySet().equals(ORDER_KEYS)) {
			throw new IllegalArgumentException("unexpected_or_missing_fields");
		}
		Action action = exactEnum(object.get("action"), "action", value -> {
			for (Action a : Action.values()) {
				if (a.wireName().equals(value)) {
					return a;
				}
			}
			return null;
		});
		String kindName = requiredString(object.get("scope_kind"), "scope_kind");
		if (!SCOPE_KINDS.contains(kindName)) {
			throw new IllegalArgumentException("bad_scope_kind");
		}
		Scope.Kind kind = Scope.Kind.fromWireName(kindName).orElseThrow();
		String scopeName = nullableString(object.get("scope_name"), "scope_name");
		if (scopeName != null && scopeName.codePointCount(0, scopeName.length()) > MAX_SCOPE_NAME) {
			throw new IllegalArgumentException("scope_name_too_long");
		}
		Scope scope;
		if (kind.needsName()) {
			if (scopeName == null || scopeName.isBlank()) {
				throw new IllegalArgumentException("missing_scope_name");
			}
			scope = new Scope(kind, scopeName);
		} else {
			scope = kind == Scope.Kind.ALL ? Scope.ALL : Scope.COMMANDER;
		}

		String formationName = nullableString(object.get("formation"), "formation");
		Optional<FormationType> formation = Optional.empty();
		if (formationName != null) {
			if (!FORMATIONS.contains(formationName)) {
				throw new IllegalArgumentException("bad_formation");
			}
			formation = Optional.of(FormationType.valueOf(formationName.toUpperCase(Locale.ROOT)));
		}

		String targetName = nullableString(object.get("target"), "target");
		Optional<TargetRef> target = Optional.empty();
		if (targetName != null) {
			target = Optional.of(TargetRef.fromWireName(targetName)
					.filter(ref -> ref.wireName().equals(targetName))
					.orElseThrow(() -> new IllegalArgumentException("bad_target")));
		}

		OptionalInt radius = OptionalInt.empty();
		JsonElement radiusElement = object.get("radius");
		if (!radiusElement.isJsonNull()) {
			if (!radiusElement.isJsonPrimitive() || !radiusElement.getAsJsonPrimitive().isNumber()) {
				throw new IllegalArgumentException("bad_radius");
			}
			BigDecimal value = radiusElement.getAsBigDecimal();
			if (value.stripTrailingZeros().scale() > 0 || value.compareTo(BigDecimal.valueOf(Order.MIN_RADIUS)) < 0
					|| value.compareTo(BigDecimal.valueOf(Order.MAX_RADIUS)) > 0) {
				throw new IllegalArgumentException("bad_radius");
			}
			radius = OptionalInt.of(value.intValueExact());
		}
		return new Order(action, scope, formation, target, radius);
	}

	private interface Lookup<T> {
		T find(String value);
	}

	private static <T> T exactEnum(JsonElement element, String field, Lookup<T> lookup) {
		T value = lookup.find(requiredString(element, field));
		if (value == null) {
			throw new IllegalArgumentException("bad_" + field);
		}
		return value;
	}

	private static String requiredString(JsonElement element, String field) {
		if (!isString(element)) {
			throw new IllegalArgumentException("bad_" + field);
		}
		return element.getAsString();
	}

	private static String nullableString(JsonElement element, String field) {
		if (element == null || element.isJsonNull()) {
			return null;
		}
		return requiredString(element, field);
	}

	private static boolean isString(JsonElement element) {
		return element != null && element.isJsonPrimitive() && element.getAsJsonPrimitive().isString();
	}

	private static JsonArray strings(List<String> values) {
		JsonArray array = new JsonArray();
		for (String value : values) {
			array.add(value);
		}
		return array;
	}

	/** {@code {"type": [type,"null"]?, "enum": [...]}}; with a null type, uses ["string","null"] and adds null to the enum. */
	private static JsonObject enumOf(String type, List<String> values, boolean nullable) {
		JsonObject object = new JsonObject();
		if (nullable) {
			object.add("type", strings(List.of("string", "null")));
		} else {
			object.add("type", new JsonPrimitive(type));
		}
		JsonArray options = strings(values);
		if (nullable) {
			options.add(JsonNull.INSTANCE);
		}
		object.add("enum", options);
		return object;
	}
}
