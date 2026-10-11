package com.guardvillagers.voice;

import java.util.concurrent.CompletableFuture;

/** Text-to-speech backend returning playback-ready 48 kHz mono PCM. */
public interface TextToSpeech {
	int OUTPUT_SAMPLE_RATE = 48_000;
	int MAX_TEXT_CODE_POINTS = 280;

	/** The future fails with {@link SpeechException}; the returned array is the caller's to keep. */
	CompletableFuture<short[]> synthesize(String text, String voice);

	/** Identifies model and delivery settings so cached audio is never reused across them. */
	String cacheNamespace();
}
