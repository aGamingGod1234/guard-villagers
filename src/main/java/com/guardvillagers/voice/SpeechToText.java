package com.guardvillagers.voice;

import java.util.UUID;
import java.util.concurrent.CompletableFuture;

/** Speech-to-text backend. OpenAI today; a local backend can implement this later without touching callers. */
public interface SpeechToText {
	/** 48 kHz mono signed 16-bit samples, the rate Simple Voice Chat decodes to. */
	int INPUT_SAMPLE_RATE = 48_000;

	/**
	 * Transcribes one utterance. {@code promptHint} may carry the speaker's group and guard names so they are
	 * spelled right; it may be null. The future fails with {@link SpeechException}.
	 */
	CompletableFuture<Transcript> transcribe(UUID player, short[] pcm48k, String promptHint);

	default CompletableFuture<Transcript> transcribe(UUID player, short[] pcm48k) {
		return transcribe(player, pcm48k, null);
	}

	/** OpenAI returns no calibrated confidence, so none is reported; the grammar's match score decides. */
	record Transcript(String text) {
		public Transcript {
			text = text == null ? "" : text;
		}

		public boolean isBlank() {
			return text.isBlank();
		}
	}
}
