package com.guardvillagers.voice;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.time.Duration;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;

class OpenAiHttpCloseTest {
	@Test
	void closedClientFailsFastInsteadOfSending() {
		OpenAiHttp http = OpenAiHttp.create("http://127.0.0.1:9", "test-key");
		http.close();
		ExecutionException failure = assertThrows(ExecutionException.class, () -> http
				.postJson("audio/transcriptions", "{}", Duration.ofSeconds(5), 1024, SpeechException.Channel.STT)
				.get(5, TimeUnit.SECONDS));
		assertEquals(SpeechException.Kind.UNAVAILABLE, SpeechException.find(failure).kind());
	}

	@Test
	void deadlineCoversAResponseBodyThatNeverFinishes() throws Exception {
		com.sun.net.httpserver.HttpServer server = com.sun.net.httpserver.HttpServer.create(
				new java.net.InetSocketAddress("127.0.0.1", 0), 0);
		java.util.concurrent.CountDownLatch bodyStarted = new java.util.concurrent.CountDownLatch(1);
		java.util.concurrent.CountDownLatch releaseBody = new java.util.concurrent.CountDownLatch(1);
		server.createContext("/stall", exchange -> {
			exchange.getRequestBody().readAllBytes();
			exchange.sendResponseHeaders(200, 0);
			try (var body = exchange.getResponseBody()) {
				body.write(new byte[] {1, 2});
				body.flush();
				bodyStarted.countDown();
				try { releaseBody.await(10, TimeUnit.SECONDS); }
				catch (InterruptedException interrupted) { Thread.currentThread().interrupt(); }
			}
		});
		server.start();
		try (OpenAiHttp http = OpenAiHttp.create("http://127.0.0.1:" + server.getAddress().getPort(), "test-key")) {
			var result = http.postJson("stall", "{}", Duration.ofSeconds(2), 1024, SpeechException.Channel.TTS);
			org.junit.jupiter.api.Assertions.assertTrue(bodyStarted.await(2, TimeUnit.SECONDS));
			ExecutionException failure = assertThrows(ExecutionException.class, () -> result.get(5, TimeUnit.SECONDS));
			assertEquals(SpeechException.Kind.TIMEOUT, SpeechException.find(failure).kind());
			assertEquals(SpeechException.Channel.TTS, SpeechException.find(failure).channel());
		} finally {
			releaseBody.countDown();
			server.stop(0);
		}
	}
}
