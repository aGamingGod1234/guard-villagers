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

}
