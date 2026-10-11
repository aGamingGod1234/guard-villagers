package com.guardvillagers.command.language.llm;

import java.util.concurrent.CompletableFuture;

/**
 * Sends one JSON request body to the model endpoint and returns the response body. Implementations fail the future
 * with {@link LlmTransportException} (possibly wrapped in a {@link java.util.concurrent.CompletionException}).
 * {@link OpenAiLlmTransport} is the production implementation; tests can supply a lambda.
 */
@FunctionalInterface
public interface LlmTransport {
	CompletableFuture<String> send(String requestJson);
}
