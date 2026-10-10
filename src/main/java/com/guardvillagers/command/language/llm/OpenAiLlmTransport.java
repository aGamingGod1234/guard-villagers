package com.guardvillagers.command.language.llm;

import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.net.URISyntaxException;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.HttpTimeoutException;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.Locale;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.function.Function;
import java.util.function.LongSupplier;
import java.util.function.Supplier;

/**
 * POSTs to {@code <baseUrl>/responses} with the JDK HTTP client. Enforces a whole-request timeout, caps the response
 * at {@link #MAX_RESPONSE_BYTES}, honours 429 Retry-After (capped at 300 s) by failing fast until it passes, and stops
 * calling after a 401/403 until {@link #resetAuthFailure()}. The key is read from its environment variable on every
 * call and is never logged or stored.
 */
public final class OpenAiLlmTransport implements LlmTransport, AutoCloseable {
	public static final int MAX_RESPONSE_BYTES = 64 * 1024;
	public static final Duration MAX_RETRY_AFTER = Duration.ofSeconds(300);
	public static final Duration DEFAULT_RETRY_AFTER = Duration.ofSeconds(1);

	private final URI endpoint;
	private final Supplier<Optional<String>> apiKey;
	private final Duration timeout;
	private final LongSupplier clockMillis;
	private final ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor();
	private final HttpClient client;
	private volatile long backoffUntilMillis;
	private volatile boolean authFailed;

	/**
	 * @param baseUrl     https URL, or http on 127.0.0.1/localhost for tests and local backends
	 * @param apiKey      supplies the key at call time; empty means not configured
	 * @param clockMillis wall-clock epoch millis, used for Retry-After
	 */
	public OpenAiLlmTransport(String baseUrl, Supplier<Optional<String>> apiKey, Duration timeout, LongSupplier clockMillis) {
		URI base = validateBaseUrl(baseUrl);
		String path = base.getPath() == null ? "" : base.getPath();
		this.endpoint = base.resolve((path.endsWith("/") ? path : path + "/") + "responses");
		this.apiKey = apiKey;
		this.timeout = timeout;
		this.clockMillis = clockMillis;
		HttpClient.Builder builder = HttpClient.newBuilder()
				.connectTimeout(timeout)
				.executor(this.executor)
				.followRedirects(HttpClient.Redirect.NEVER);
		if ("http".equals(base.getScheme())) {
			builder.version(HttpClient.Version.HTTP_1_1);
		}
		this.client = builder.build();
	}

	/** Production transport for a config, reading the key from the named environment variable via {@code env}. */
	public static OpenAiLlmTransport fromConfig(LlmConfig config, Function<String, String> env) {
		return new OpenAiLlmTransport(config.baseUrl(), envKey(config.apiKeyEnv(), env), config.timeout(), System::currentTimeMillis);
	}

	public static Supplier<Optional<String>> envKey(String variable, Function<String, String> env) {
		return () -> Optional.ofNullable(env.apply(variable)).map(String::strip).filter(key -> !key.isEmpty());
	}

	/** Accepts https anywhere and plain http only on loopback hosts. */
	public static URI validateBaseUrl(String baseUrl) {
		URI uri;
		try {
			uri = new URI(baseUrl == null ? "" : baseUrl.strip());
		} catch (URISyntaxException e) {
			throw new IllegalArgumentException("Invalid LLM base URL");
		}
		String scheme = uri.getScheme() == null ? "" : uri.getScheme().toLowerCase(Locale.ROOT);
		String host = uri.getHost() == null ? "" : uri.getHost().toLowerCase(Locale.ROOT);
		if (host.isEmpty() || uri.getUserInfo() != null || uri.getQuery() != null || uri.getFragment() != null) {
			throw new IllegalArgumentException("Invalid LLM base URL");
		}
		boolean loopback = host.equals("127.0.0.1") || host.equals("localhost") || host.equals("[::1]");
		if (!scheme.equals("https") && !(scheme.equals("http") && loopback)) {
			throw new IllegalArgumentException("LLM base URL must use https (http only on localhost)");
		}
		return uri;
	}

	@Override
	public CompletableFuture<String> send(String requestJson) {
		if (this.authFailed) {
			return CompletableFuture.failedFuture(new LlmTransportException(LlmTransportException.Kind.UNAUTHORIZED, "LLM key rejected earlier"));
		}
		long now = this.clockMillis.getAsLong();
		if (now < this.backoffUntilMillis) {
			Duration wait = Duration.ofMillis(this.backoffUntilMillis - now);
			return CompletableFuture.failedFuture(new LlmTransportException(LlmTransportException.Kind.RATE_LIMITED, 429, wait, "LLM backing off"));
		}
		Optional<String> key = this.apiKey.get();
		if (key.isEmpty()) {
			return CompletableFuture.failedFuture(new LlmTransportException(LlmTransportException.Kind.NOT_CONFIGURED, "No LLM API key"));
		}
		HttpRequest request = HttpRequest.newBuilder(this.endpoint)
				.timeout(this.timeout)
				.header("Authorization", "Bearer " + key.get())
				.header("Content-Type", "application/json")
				.header("Accept", "application/json")
				.POST(HttpRequest.BodyPublishers.ofString(requestJson, StandardCharsets.UTF_8))
				.build();

		CompletableFuture<HttpResponse<InputStream>> inFlight = this.client.sendAsync(request, HttpResponse.BodyHandlers.ofInputStream());
		CompletableFuture<String> body = inFlight.thenApplyAsync(this::readResponse, this.executor);
		CompletableFuture<String> result = new CompletableFuture<>();
		body.orTimeout(this.timeout.toMillis(), TimeUnit.MILLISECONDS).whenComplete((value, error) -> {
			if (error == null) {
				result.complete(value);
				return;
			}
			inFlight.cancel(true);
			body.cancel(true);
			result.completeExceptionally(map(error));
		});
		return result;
	}

	/** Re-enables calls after an auth failure, e.g. on {@code /guards voice reload}. */
	public void resetAuthFailure() {
		this.authFailed = false;
	}

	public boolean isAuthFailed() {
		return this.authFailed;
	}

	public Optional<Duration> backoffRemaining() {
		long left = this.backoffUntilMillis - this.clockMillis.getAsLong();
		return left > 0 ? Optional.of(Duration.ofMillis(left)) : Optional.empty();
	}

	@Override
	public void close() {
		this.client.shutdownNow();
		this.executor.shutdownNow();
	}

	private String readResponse(HttpResponse<InputStream> response) {
		int status = response.statusCode();
		try (InputStream in = response.body()) {
			if (status == 429) {
				Duration retry = retryAfter(response);
				this.backoffUntilMillis = this.clockMillis.getAsLong() + retry.toMillis();
				throw new LlmTransportException(LlmTransportException.Kind.RATE_LIMITED, status, retry, "LLM rate limited");
			}
			if (status == 401 || status == 403) {
				this.authFailed = true;
				throw new LlmTransportException(LlmTransportException.Kind.UNAUTHORIZED, status, null, "LLM key rejected (HTTP " + status + ")");
			}
			long declared = response.headers().firstValueAsLong("content-length").orElse(-1L);
			if (declared > MAX_RESPONSE_BYTES) {
				throw new LlmTransportException(LlmTransportException.Kind.RESPONSE_TOO_LARGE, status, null, "LLM response too large");
			}
			byte[] bytes = in.readNBytes(MAX_RESPONSE_BYTES + 1);
			if (bytes.length > MAX_RESPONSE_BYTES) {
				throw new LlmTransportException(LlmTransportException.Kind.RESPONSE_TOO_LARGE, status, null, "LLM response too large");
			}
			if (status / 100 != 2) {
				throw new LlmTransportException(LlmTransportException.Kind.HTTP_ERROR, status, null, "LLM HTTP " + status);
			}
			return new String(bytes, StandardCharsets.UTF_8);
		} catch (IOException e) {
			throw new LlmTransportException(LlmTransportException.Kind.NETWORK, status, null, "LLM read failed: " + e.getClass().getSimpleName());
		}
	}

	private Duration retryAfter(HttpResponse<?> response) {
		Optional<String> millis = response.headers().firstValue("retry-after-ms");
		if (millis.isPresent()) {
			try {
				return cap(Duration.ofMillis(Long.parseLong(millis.get().strip())));
			} catch (NumberFormatException ignored) {
				// fall through to Retry-After
			}
		}
		Optional<String> header = response.headers().firstValue("retry-after");
		if (header.isEmpty()) {
			return DEFAULT_RETRY_AFTER;
		}
		String value = header.get().strip();
		try {
			return cap(Duration.ofSeconds(Long.parseLong(value)));
		} catch (NumberFormatException ignored) {
			// HTTP-date form
		}
		try {
			long at = ZonedDateTime.parse(value, DateTimeFormatter.RFC_1123_DATE_TIME).toInstant().toEpochMilli();
			return cap(Duration.ofMillis(at - this.clockMillis.getAsLong()));
		} catch (DateTimeParseException e) {
			return DEFAULT_RETRY_AFTER;
		}
	}

	private static Duration cap(Duration value) {
		if (value.isNegative() || value.isZero()) {
			return DEFAULT_RETRY_AFTER;
		}
		return value.compareTo(MAX_RETRY_AFTER) > 0 ? MAX_RETRY_AFTER : value;
	}

	private static LlmTransportException map(Throwable error) {
		Throwable cause = error;
		while ((cause instanceof CompletionException || cause instanceof ExecutionException) && cause.getCause() != null) {
			cause = cause.getCause();
		}
		if (cause instanceof LlmTransportException transport) {
			return transport;
		}
		if (cause instanceof TimeoutException || cause instanceof HttpTimeoutException) {
			return new LlmTransportException(LlmTransportException.Kind.TIMEOUT, "LLM request timed out");
		}
		return new LlmTransportException(LlmTransportException.Kind.NETWORK, "LLM request failed: " + cause.getClass().getSimpleName());
	}
}
