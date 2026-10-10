package com.guardvillagers.voice;

import java.util.UUID;

/** Receives recognised speech. Both methods run on the server thread. */
public interface VoiceTranscriptListener {
	/** A non-blank transcript from a consented player near their own guards. */
	void onTranscript(VoiceTranscript transcript);

	/**
	 * Recognition failed for a transient reason (rate limit, timeout, OpenAI error). Sent at most once per 30 s
	 * per player, so it can be shown as "Commander couldn't hear you, try again".
	 */
	default void onRecognitionFailed(UUID player) {
	}

	/**
	 * @param endpointMs time from the player's last audio packet to end-of-speech detection
	 * @param sttMs time spent in speech-to-text
	 * @param deliveredNanos {@link System#nanoTime()} when the transcript was handed over, for measuring parse time
	 */
	record VoiceTranscript(UUID player, String text, long endpointMs, long sttMs, long deliveredNanos) {
	}
}
