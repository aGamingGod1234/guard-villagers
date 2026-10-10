package com.guardvillagers.voice;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpConnectTimeoutException;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.HttpTimeoutException;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.List;
import java.util.Objects;
import java.util.OptionalLong;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.Executors;
import java.util.concurrent.Flow;

/**
 * Minimal OpenAI HTTP transport shared by speech-to-text, text-to-speech and the LLM interpreter.
 * Every response body is read with a hard byte cap, every request has a timeout, and 429 responses carry a
 * capped Retry-After. The key goes only into the Authorization header.
 */
public final class OpenAiHttp implements AutoCloseable {
	public static final long MAX_RETRY_AFTER_MILLIS = 300_000L;
	private static final long DEFAULT_RATE_LIMIT_BACKOFF_MILLIS = 1_000L;
	private final URI baseUrl;
	private final String apiKey;
	private final HttpClient client;
	private final java.util.function.LongSupplier wallClockMillis;
	/** The executor {@link #create} made for the client; null when the caller supplied the client. */
	private java.util.concurrent.ExecutorService ownedExecutor;

	public OpenAiHttp(String baseUrl, String apiKey, HttpClient client) {
		this(baseUrl, apiKey, client, System::currentTimeMillis);
	}

	OpenAiHttp(String baseUrl, String apiKey, HttpClient client, java.util.function.LongSupplier wallClockMillis) {
		Objects.requireNonNull(baseUrl, "baseUrl");
		if (apiKey == null || apiKey.isBlank()) throw new IllegalArgumentException("OpenAI API key must not be blank");
		this.baseUrl = URI.create(baseUrl.endsWith("/") ? baseUrl : baseUrl + "/");
		this.apiKey = apiKey.trim();
		this.client = Objects.requireNonNull(client, "client");
		this.wallClockMillis = wallClockMillis;
	}

	/** A client on virtual threads with a 5 s connect timeout. */
	public static OpenAiHttp create(String baseUrl, String apiKey) {
		java.util.concurrent.ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor();
		HttpClient client = HttpClient.newBuilder()
				.connectTimeout(Duration.ofSeconds(5))
				.followRedirects(HttpClient.Redirect.NEVER)
				.executor(executor)
				.build();
		OpenAiHttp http = new OpenAiHttp(baseUrl, apiKey, client);
		http.ownedExecutor = executor;
		return http;
	}

	/** Aborts open exchanges and stops the client's selector thread and executor. */
	@Override
	public void close() {
		client.shutdownNow();
		if (ownedExecutor != null) ownedExecutor.shutdownNow();
	}

	public CompletableFuture<byte[]> postJson(String endpoint, String json, Duration timeout, int maxBytes,
			SpeechException.Channel channel) {
		return post(endpoint, "application/json", HttpRequest.BodyPublishers.ofString(json, StandardCharsets.UTF_8),
				timeout, maxBytes, channel);
	}

	/**
	 * POSTs to {@code baseUrl/endpoint}. The future fails with {@link SpeechException} for every error; a
	 * cancelled future aborts the exchange.
	 */
	public CompletableFuture<byte[]> post(String endpoint, String contentType, HttpRequest.BodyPublisher body,
			Duration timeout, int maxBytes, SpeechException.Channel channel) {
		if (maxBytes < 1) throw new IllegalArgumentException("maxBytes must be positive");
		HttpRequest request;
		try {
			request = HttpRequest.newBuilder(baseUrl.resolve(endpoint))
					.timeout(timeout)
					.header("Authorization", "Bearer " + apiKey)
					.header("Content-Type", contentType)
					.POST(body)
					.build();
		} catch (IllegalArgumentException exception) {
			return CompletableFuture.failedFuture(new SpeechException(channel, SpeechException.Kind.REJECTED,
					"invalid request URL"));
		}
		CompletableFuture<HttpResponse<byte[]>> exchange;
		try {
			exchange = client.sendAsync(request, info -> handler(info, maxBytes, channel));
		} catch (RuntimeException exception) {
			return CompletableFuture.failedFuture(new SpeechException(channel, SpeechException.Kind.UNAVAILABLE,
					"request could not be sent", 0, 0L, null));
		}
		CompletableFuture<byte[]> result = exchange.handle((response, failure) -> {
			if (failure != null) throw classify(failure, channel);
			return response.body();
		});
		// Cancelling the caller's future aborts the HTTP exchange.
		result.whenComplete((ignored, failure) -> {
			if (result.isCancelled()) exchange.cancel(true);
		});
		return result;
	}

	private HttpResponse.BodySubscriber<byte[]> handler(HttpResponse.ResponseInfo info, int maxBytes,
			SpeechException.Channel channel) {
		int status = info.statusCode();
		if (status < 200 || status > 299) {
			SpeechException.Kind kind = status == 401 || status == 403 ? SpeechException.Kind.AUTHENTICATION
					: status == 429 ? SpeechException.Kind.RATE_LIMITED : SpeechException.Kind.PROVIDER;
			long retryAfter = kind == SpeechException.Kind.RATE_LIMITED
					? retryAfterMillis(info.headers().firstValue("retry-after").orElse(null))
					: 0L;
			SpeechException error = new SpeechException(channel, kind, "OpenAI returned HTTP " + status,
					status, retryAfter, null);
			// Error bodies are discarded unread; only the status matters.
			return new FailingSubscriber(error);
		}
		OptionalLong declared = info.headers().firstValueAsLong("content-length");
		if (declared.isPresent() && declared.getAsLong() > maxBytes) {
			return new FailingSubscriber(new SpeechException(channel, SpeechException.Kind.RESPONSE_TOO_LARGE,
					"response exceeds " + maxBytes + " bytes"));
		}
		return new BoundedSubscriber(maxBytes, channel);
	}

	/** {@code source.thenApply(mapper)}, but cancelling the result also cancels {@code source} (and its HTTP exchange). */
	public static <T> CompletableFuture<T> map(CompletableFuture<byte[]> source, java.util.function.Function<byte[], T> mapper) {
		CompletableFuture<T> mapped = source.thenApply(mapper);
		mapped.whenComplete((ignored, failure) -> {
			if (mapped.isCancelled()) source.cancel(true);
		});
		return mapped;
	}

	long retryAfterMillis(String header) {
		if (header == null || header.isBlank()) return DEFAULT_RATE_LIMIT_BACKOFF_MILLIS;
		String value = header.trim();
		long millis;
		try {
			double seconds = Double.parseDouble(value);
			millis = Double.isFinite(seconds) ? (long) Math.ceil(seconds * 1000.0D) : DEFAULT_RATE_LIMIT_BACKOFF_MILLIS;
		} catch (NumberFormatException notSeconds) {
			try {
				millis = ZonedDateTime.parse(value, DateTimeFormatter.RFC_1123_DATE_TIME).toInstant().toEpochMilli()
						- wallClockMillis.getAsLong();
			} catch (DateTimeParseException notDate) {
				millis = DEFAULT_RATE_LIMIT_BACKOFF_MILLIS;
			}
		}
		return Math.max(DEFAULT_RATE_LIMIT_BACKOFF_MILLIS, Math.min(MAX_RETRY_AFTER_MILLIS, millis));
	}

	private static SpeechException classify(Throwable failure, SpeechException.Channel channel) {
		SpeechException speech = SpeechException.find(failure);
		if (speech != null) return speech;
		Throwable cause = failure;
		while (cause.getCause() != null && !(cause instanceof IOException)) cause = cause.getCause();
		if (cause instanceof HttpTimeoutException && !(cause instanceof HttpConnectTimeoutException)) {
			return new SpeechException(channel, SpeechException.Kind.TIMEOUT, "request timed out");
		}
		if (cause instanceof java.util.concurrent.CancellationException) {
			return new SpeechException(channel, SpeechException.Kind.REJECTED, "request cancelled");
		}
		return new SpeechException(channel, SpeechException.Kind.UNAVAILABLE,
				"transport failed (" + cause.getClass().getSimpleName() + ")");
	}

	/** Completes exceptionally without reading the body. */
	private static final class FailingSubscriber implements HttpResponse.BodySubscriber<byte[]> {
		private final CompletableFuture<byte[]> body = new CompletableFuture<>();
		private final SpeechException error;

		private FailingSubscriber(SpeechException error) {
			this.error = error;
		}

		@Override
		public CompletionStage<byte[]> getBody() {
			return body;
		}

		@Override
		public void onSubscribe(Flow.Subscription subscription) {
			subscription.cancel();
			body.completeExceptionally(error);
		}

		@Override
		public void onNext(List<ByteBuffer> item) {
		}

		@Override
		public void onError(Throwable throwable) {
			body.completeExceptionally(error);
		}

		@Override
		public void onComplete() {
			body.completeExceptionally(error);
		}
	}

	/** Collects the body and aborts the exchange as soon as it passes the cap. */
	private static final class BoundedSubscriber implements HttpResponse.BodySubscriber<byte[]> {
		private final CompletableFuture<byte[]> body = new CompletableFuture<>();
		private final ByteArrayOutputStream buffer = new ByteArrayOutputStream();
		private final int maxBytes;
		private final SpeechException.Channel channel;
		private Flow.Subscription subscription;

		private BoundedSubscriber(int maxBytes, SpeechException.Channel channel) {
			this.maxBytes = maxBytes;
			this.channel = channel;
		}

		@Override
		public CompletionStage<byte[]> getBody() {
			return body;
		}

		@Override
		public void onSubscribe(Flow.Subscription subscription) {
			this.subscription = subscription;
			subscription.request(Long.MAX_VALUE);
		}

		@Override
		public void onNext(List<ByteBuffer> items) {
			if (body.isDone()) return;
			for (ByteBuffer item : items) {
				if (buffer.size() + item.remaining() > maxBytes) {
					subscription.cancel();
					body.completeExceptionally(new SpeechException(channel, SpeechException.Kind.RESPONSE_TOO_LARGE,
							"response exceeds " + maxBytes + " bytes"));
					return;
				}
				byte[] chunk = new byte[item.remaining()];
				item.get(chunk);
				buffer.write(chunk, 0, chunk.length);
			}
		}

		@Override
		public void onError(Throwable throwable) {
			body.completeExceptionally(throwable);
		}

		@Override
		public void onComplete() {
			body.complete(buffer.toByteArray());
		}
	}
}
