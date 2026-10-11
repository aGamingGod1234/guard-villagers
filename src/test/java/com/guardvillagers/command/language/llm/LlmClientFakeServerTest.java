package com.guardvillagers.command.language.llm;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.guardvillagers.command.order.Action;
import com.guardvillagers.command.order.Order;
import com.guardvillagers.command.order.OwnerRoster;
import com.guardvillagers.command.order.Scope;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/** The LLM client against a local fake OpenAI server: request shape, timeout, 429, auth, size cap, malformed JSON. */
final class LlmClientFakeServerTest {
	private static final OwnerRoster ROSTER = OwnerRoster.of(List.of("Alpha", "Archers"), List.of("Liam", "Ava"));
	private static final UUID PLAYER = UUID.randomUUID();

	private interface Handler {
		void handle(HttpExchange exchange) throws IOException, InterruptedException;
	}

	private HttpServer server;
	private ExecutorService serverThreads;
	private final AtomicReference<Handler> handler = new AtomicReference<>();
	private final AtomicInteger hits = new AtomicInteger();
	private final AtomicReference<String> lastBody = new AtomicReference<>();
	private final AtomicReference<String> lastAuth = new AtomicReference<>();
	private final AtomicReference<String> lastPath = new AtomicReference<>();
	private final AtomicLong clock = new AtomicLong(1_000_000L);
	private OpenAiLlmTransport transport;
	private LlmInterpreter interpreter;

	@BeforeEach
	void start() throws IOException {
		this.server = HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0);
		this.serverThreads = Executors.newCachedThreadPool();
		this.server.setExecutor(this.serverThreads);
		this.server.createContext("/", exchange -> {
			this.hits.incrementAndGet();
			this.lastPath.set(exchange.getRequestURI().getPath());
			this.lastAuth.set(exchange.getRequestHeaders().getFirst("Authorization"));
			this.lastBody.set(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
			try {
				this.handler.get().handle(exchange);
			} catch (InterruptedException e) {
				Thread.currentThread().interrupt();
			} finally {
				exchange.close();
			}
		});
		this.server.start();
		useTimeout(Duration.ofSeconds(5));
	}

	private void useTimeout(Duration timeout) {
		if (this.transport != null) {
			this.transport.close();
		}
		String base = "http://127.0.0.1:" + this.server.getAddress().getPort() + "/v1";
		LlmConfig config = LlmConfig.defaults().withBaseUrl(base).withTimeout(timeout);
		this.transport = new OpenAiLlmTransport(base, () -> Optional.of("test-key"), config.timeout(), this.clock::get);
		this.interpreter = new LlmInterpreter(this.transport, config, this.clock::get);
	}

	@AfterEach
	void stop() {
		this.transport.close();
		this.server.stop(0);
		this.serverThreads.shutdownNow();
	}

	private static void respond(HttpExchange exchange, int status, String body, Map<String, String> headers) throws IOException {
		byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
		headers.forEach((k, v) -> exchange.getResponseHeaders().add(k, v));
		exchange.getResponseHeaders().add("Content-Type", "application/json");
		exchange.sendResponseHeaders(status, bytes.length == 0 ? -1 : bytes.length);
		if (bytes.length > 0) {
			try (OutputStream out = exchange.getResponseBody()) {
				out.write(bytes);
			}
		}
	}

	/** A Responses API body with a reasoning item followed by the issue_orders call. */
	static String responseWithArguments(String arguments) {
		JsonObject call = new JsonObject();
		call.addProperty("type", "function_call");
		call.addProperty("call_id", "call_1");
		call.addProperty("name", "issue_orders");
		call.addProperty("arguments", arguments);
		return "{\"id\":\"resp_1\",\"object\":\"response\",\"status\":\"completed\",\"output\":["
				+ "{\"type\":\"reasoning\",\"id\":\"rs_1\",\"summary\":[]}," + call + "]}";
	}

	private LlmResult call(String transcript) throws Exception {
		return this.interpreter.interpret(PLAYER, transcript, ROSTER).get(10, TimeUnit.SECONDS);
	}

	@Test
	void sendsStrictForcedToolRequestAndParsesCall() throws Exception {
		String args = OrderSchemaTest.args("Alpha holding, archers on you.",
				OrderSchemaTest.order("hold", "group", "Alpha", null, null, null),
				OrderSchemaTest.order("follow", "group", "Archers", null, null, null));
		this.handler.set(exchange -> respond(exchange, 200, responseWithArguments(args), Map.of()));

		LlmResult result = call("alpha dig in, bow guys stick to me");

		assertEquals(LlmResult.Status.OK, result.status());
		assertEquals(List.of(Order.of(Action.HOLD, Scope.group("Alpha")), Order.of(Action.FOLLOW, Scope.group("Archers"))), result.orders());
		assertEquals(Optional.of("Alpha holding, archers on you."), result.reply());

		assertEquals("/v1/responses", this.lastPath.get());
		assertEquals("Bearer test-key", this.lastAuth.get());
		JsonObject request = JsonParser.parseString(this.lastBody.get()).getAsJsonObject();
		assertEquals(LlmConfig.DEFAULT_MODEL, request.get("model").getAsString());
		assertEquals(1, request.getAsJsonArray("tools").size());
		JsonObject tool = request.getAsJsonArray("tools").get(0).getAsJsonObject();
		assertEquals(OrderSchema.tool(), tool);
		assertTrue(tool.get("strict").getAsBoolean());
		JsonObject choice = request.getAsJsonObject("tool_choice");
		assertEquals("function", choice.get("type").getAsString());
		assertEquals("issue_orders", choice.get("name").getAsString());
		assertFalse(request.get("parallel_tool_calls").getAsBoolean());
		assertFalse(request.get("store").getAsBoolean());
		assertEquals("low", request.getAsJsonObject("reasoning").get("effort").getAsString());
		assertTrue(request.get("instructions").getAsString().contains("untrusted"));
		assertEquals(List.of("model", "instructions", "input", "tools", "tool_choice", "parallel_tool_calls", "store",
				"max_output_tokens", "reasoning"), List.copyOf(request.keySet()));

		// The only user content is the transcript plus the owner's own names.
		JsonObject message = request.getAsJsonArray("input").get(0).getAsJsonObject();
		assertEquals("user", message.get("role").getAsString());
		JsonObject context = JsonParser.parseString(message.get("content").getAsString()).getAsJsonObject();
		assertEquals(List.of("transcript", "groups", "guards"), List.copyOf(context.keySet()));
		assertEquals("alpha dig in, bow guys stick to me", context.get("transcript").getAsString());
		assertEquals(2, context.getAsJsonArray("groups").size());
		assertEquals(2, context.getAsJsonArray("guards").size());
		assertFalse(this.lastBody.get().contains("test-key"));
	}

	@Test
	void capsTranscriptAndNames() {
		List<String> manyGuards = java.util.stream.IntStream.range(0, 40).mapToObj(i -> "Guard" + i).toList();
		String body = this.interpreter.buildRequest("x".repeat(2000) + "\u0000", OwnerRoster.of(List.of("y".repeat(60)), manyGuards));
		JsonObject request = JsonParser.parseString(body).getAsJsonObject();
		JsonObject context = JsonParser.parseString(request.getAsJsonArray("input").get(0).getAsJsonObject().get("content").getAsString())
				.getAsJsonObject();
		assertEquals(LlmInterpreter.MAX_TRANSCRIPT_CHARS, context.get("transcript").getAsString().length());
		assertEquals(LlmInterpreter.MAX_GUARD_NAMES, context.getAsJsonArray("guards").size());
		assertEquals("y".repeat(60), context.getAsJsonArray("groups").get(0).getAsString());
	}

	@Test
	void timesOut() throws Exception {
		useTimeout(Duration.ofMillis(600));
		this.handler.set(exchange -> {
			Thread.sleep(3000);
			respond(exchange, 200, responseWithArguments(OrderSchemaTest.args("")), Map.of());
		});
		long started = System.nanoTime();
		LlmResult result = call("alpha dig in");
		long tookMillis = (System.nanoTime() - started) / 1_000_000L;
		assertEquals(LlmResult.Status.TIMEOUT, result.status());
		assertTrue(tookMillis < 2500, "timed out after " + tookMillis + " ms");
	}

	@Test
	void honoursRetryAfterOn429() throws Exception {
		this.handler.set(exchange -> respond(exchange, 429, "{\"error\":{\"message\":\"slow down\"}}", Map.of("Retry-After", "7")));
		LlmResult first = call("alpha dig in");
		assertEquals(LlmResult.Status.RATE_LIMITED, first.status());
		assertEquals(Optional.of(Duration.ofSeconds(7)), first.retryAfter());
		assertEquals(1, this.hits.get());

		// Inside the window: fail fast without touching the server.
		this.clock.addAndGet(3_000L);
		LlmResult second = call("alpha dig in");
		assertEquals(LlmResult.Status.RATE_LIMITED, second.status());
		assertEquals(Optional.of(Duration.ofSeconds(4)), second.retryAfter());
		assertEquals(1, this.hits.get());

		this.clock.addAndGet(4_000L);
		this.handler.set(exchange -> respond(exchange, 200, responseWithArguments(OrderSchemaTest.args("What should they do?")), Map.of()));
		LlmResult third = call("alpha dig in");
		assertEquals(LlmResult.Status.OK, third.status());
		assertEquals(2, this.hits.get());
	}

	@Test
	void retryAfterIsCappedAndDefaulted() throws Exception {
		this.handler.set(exchange -> respond(exchange, 429, "{}", Map.of("Retry-After", "99999")));
		assertEquals(Optional.of(OpenAiLlmTransport.MAX_RETRY_AFTER), call("x").retryAfter());
		this.clock.addAndGet(OpenAiLlmTransport.MAX_RETRY_AFTER.toMillis());
		this.handler.set(exchange -> respond(exchange, 429, "{}", Map.of()));
		assertEquals(Optional.of(OpenAiLlmTransport.DEFAULT_RETRY_AFTER), call("x").retryAfter());
	}

	@Test
	void unauthorizedDisablesUntilReset() throws Exception {
		this.handler.set(exchange -> respond(exchange, 401, "{\"error\":{}}", Map.of()));
		assertEquals(LlmResult.Status.UNAUTHORIZED, call("x").status());
		assertEquals(LlmResult.Status.UNAUTHORIZED, call("x").status());
		assertEquals(1, this.hits.get());
		assertTrue(this.transport.isAuthFailed());
		this.transport.resetAuthFailure();
		this.handler.set(exchange -> respond(exchange, 200, responseWithArguments(OrderSchemaTest.args("")), Map.of()));
		assertEquals(LlmResult.Status.OK, call("x").status());
	}

	@Test
	void oversizedAndErrorResponsesAreUnavailable() throws Exception {
		this.handler.set(exchange -> respond(exchange, 200, "{\"pad\":\"" + "a".repeat(OpenAiLlmTransport.MAX_RESPONSE_BYTES) + "\"}", Map.of()));
		assertEquals(LlmResult.Status.UNAVAILABLE, call("x").status());
		this.handler.set(exchange -> respond(exchange, 500, "{}", Map.of()));
		assertEquals(LlmResult.Status.UNAVAILABLE, call("x").status());
	}

	@Test
	void malformedJsonIsReported() throws Exception {
		for (String body : List.of(
				"not json at all",
				"{\"output\":\"nope\"}",
				"{\"output\":[{\"type\":\"message\",\"content\":[{\"type\":\"output_text\",\"text\":\"hold\"}]}]}",
				responseWithArguments("{\"orders\":[{\"action\":"),
				responseWithArguments("{\"orders\":[],\"reply\":\"x\",\"shell\":\"rm -rf\"}"))) {
			this.handler.set(exchange -> respond(exchange, 200, body, Map.of()));
			assertEquals(LlmResult.Status.MALFORMED, call("x").status(), body);
		}
	}

	@Test
	void missingKeyIsUnavailableWithoutRequest() throws Exception {
		String base = "http://127.0.0.1:" + this.server.getAddress().getPort() + "/v1";
		try (OpenAiLlmTransport keyless = new OpenAiLlmTransport(base, Optional::empty, Duration.ofSeconds(2), this.clock::get)) {
			LlmInterpreter noKey = new LlmInterpreter(keyless, LlmConfig.defaults(), this.clock::get);
			assertEquals(LlmResult.Status.UNAVAILABLE, noKey.interpret(PLAYER, "x", ROSTER).get(5, TimeUnit.SECONDS).status());
		}
		assertEquals(0, this.hits.get());
	}

	@Test
	void perPlayerRateLimit() throws Exception {
		AtomicInteger sent = new AtomicInteger();
		LlmTransport fake = body -> {
			sent.incrementAndGet();
			return CompletableFuture.completedFuture(responseWithArguments(OrderSchemaTest.args("ok")));
		};
		LlmInterpreter limited = new LlmInterpreter(fake, LlmConfig.defaults().withMaxPerPlayerPerMinute(2), this.clock::get);
		UUID other = UUID.randomUUID();
		assertEquals(LlmResult.Status.OK, limited.interpret(PLAYER, "a", ROSTER).get().status());
		assertEquals(LlmResult.Status.OK, limited.interpret(PLAYER, "b", ROSTER).get().status());
		assertEquals(LlmResult.Status.PLAYER_RATE_LIMITED, limited.interpret(PLAYER, "c", ROSTER).get().status());
		assertEquals(LlmResult.Status.OK, limited.interpret(other, "d", ROSTER).get().status());
		this.clock.addAndGet(60_000L);
		assertEquals(LlmResult.Status.OK, limited.interpret(PLAYER, "e", ROSTER).get().status());
		assertEquals(4, sent.get());
	}

	@Test
	void baseUrlMustBeHttpsOrLoopback() {
		OpenAiLlmTransport.validateBaseUrl("https://api.openai.com/v1");
		OpenAiLlmTransport.validateBaseUrl("http://127.0.0.1:8080/v1");
		OpenAiLlmTransport.validateBaseUrl("http://localhost/v1");
		assertThrows(IllegalArgumentException.class, () -> OpenAiLlmTransport.validateBaseUrl("http://example.com/v1"));
		assertThrows(IllegalArgumentException.class, () -> OpenAiLlmTransport.validateBaseUrl("ftp://api.openai.com"));
		assertThrows(IllegalArgumentException.class, () -> OpenAiLlmTransport.validateBaseUrl("https://user:pw@api.openai.com/v1"));
		assertThrows(IllegalArgumentException.class, () -> OpenAiLlmTransport.validateBaseUrl("not a url"));
	}

	@Test
	void createNeedsEnabledConfigAndKey() {
		Map<String, String> env = Map.of("OPENAI_API_KEY", "sk-test");
		assertTrue(LlmInterpreter.create(LlmConfig.defaults(), Map.<String, String>of()::get).isEmpty());
		assertTrue(LlmInterpreter.create(LlmConfig.defaults(), Map.of("OPENAI_API_KEY", "  ")::get).isEmpty());
		LlmConfig disabled = new LlmConfig(false, null, null, null, null, 6, null, 1024);
		assertTrue(LlmInterpreter.create(disabled, env::get).isEmpty());
		Optional<LlmInterpreter> created = LlmInterpreter.create(LlmConfig.defaults(), env::get);
		assertTrue(created.isPresent());
		created.get().close();
	}

	@Test
	void configClampsBadValues() {
		LlmConfig config = new LlmConfig(true, "bad model name!", "1BAD", " ", Duration.ofMinutes(10), 999, "turbo", 5);
		assertEquals(LlmConfig.DEFAULT_MODEL, config.model());
		assertEquals(LlmConfig.DEFAULT_API_KEY_ENV, config.apiKeyEnv());
		assertEquals(LlmConfig.DEFAULT_BASE_URL, config.baseUrl());
		assertEquals(Duration.ofSeconds(30), config.timeout());
		assertEquals(60, config.maxPerPlayerPerMinute());
		assertEquals(LlmConfig.DEFAULT_REASONING_EFFORT, config.reasoningEffort());
		assertEquals(256, config.maxOutputTokens());
		assertEquals("gpt-4.1-mini", new LlmConfig(true, "gpt-4.1-mini", null, null, null, 6, "", 1024).model());
	}
	@Test
	void timedOutBodyClosesConnectionWithoutClosingTransport() throws Exception {
		try (var peer = new java.net.ServerSocket(0, 1, InetAddress.getLoopbackAddress());
				var threads = Executors.newVirtualThreadPerTaskExecutor();
				var client = new OpenAiLlmTransport("http://127.0.0.1:" + peer.getLocalPort(),
						() -> Optional.of("test"), Duration.ofMillis(1500), System::currentTimeMillis)) {
			var eof = threads.submit(() -> {
				try (var socket = peer.accept()) {
					socket.setSoTimeout(7000);
					var in = new java.io.BufferedReader(new java.io.InputStreamReader(socket.getInputStream(), StandardCharsets.UTF_8));
					int length = 0;
					String line;
					while (!(line = in.readLine()).isEmpty()) {
						if (line.toLowerCase(java.util.Locale.ROOT).startsWith("content-length:")) length = Integer.parseInt(line.substring(15).strip());
					}
					for (int i = 0; i < length; i++) in.read();
					socket.getOutputStream().write("HTTP/1.1 200 OK\r\nContent-Length: 10000\r\n\r\n{".getBytes(StandardCharsets.UTF_8));
					socket.getOutputStream().flush();
					return in.read();
				}
			});
			var failure = client.send("{}").handle((value, error) -> error).get(10, TimeUnit.SECONDS);
			assertEquals(LlmTransportException.Kind.TIMEOUT, ((LlmTransportException) failure).kind());
			assertEquals(-1, eof.get(10, TimeUnit.SECONDS));
		}
	}

	@Test
	void fullGuardIdentityIsSentAndAccepted() throws Exception {
		String name = "Sir Bartholomew the Brave Knight";
		var roster = OwnerRoster.of(List.of(), List.of(name, name.substring(0, 24)));
		var request = JsonParser.parseString(interpreter.buildRequest(name + " dig in", roster)).getAsJsonObject();
		var context = JsonParser.parseString(request.getAsJsonArray("input").get(0).getAsJsonObject().get("content").getAsString()).getAsJsonObject();
		assertEquals(name, context.getAsJsonArray("guards").get(0).getAsString());
		var parsed = OrderSchema.parseArguments(OrderSchemaTest.args("", OrderSchemaTest.order("hold", "guard", name, null, null, null)));
		var validated = new com.guardvillagers.command.order.OrderValidator().validateAll(parsed.orders(), roster);
		assertEquals(List.of(Order.of(Action.HOLD, Scope.guard(name))), validated.accepted());
	}

}
