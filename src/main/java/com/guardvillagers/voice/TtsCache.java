package com.guardvillagers.voice;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HashMap;
import java.util.HexFormat;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.function.Supplier;

/**
 * In-memory LRU cache of synthesized PCM, bounded by total bytes (port of agent-arena {@code tts-cache.mjs}).
 * Callouts and acks are short fixed phrases, so after warm-up most lines cost no API call. Identical
 * concurrent requests share one synthesis. Nothing is written to disk.
 */
public final class TtsCache {
	private final long maxBytes;
	private final LinkedHashMap<String, short[]> entries = new LinkedHashMap<>(16, 0.75F, true);
	private final Map<String, CompletableFuture<short[]>> inFlight = new HashMap<>();
	private long bytes;
	private long hits;
	private long misses;

	public TtsCache(long maxBytes) {
		if (maxBytes < 0) throw new IllegalArgumentException("maxBytes must not be negative");
		this.maxBytes = maxBytes;
	}

	public static TtsCache ofMiB(int mebibytes) {
		return new TtsCache((long) mebibytes * 1024L * 1024L);
	}

	/** sha256 over the length-prefixed parts, so ("ab","c") and ("a","bc") differ. */
	public static String key(String... parts) {
		try {
			MessageDigest digest = MessageDigest.getInstance("SHA-256");
			for (String part : parts) {
				byte[] bytes = part.getBytes(StandardCharsets.UTF_8);
				digest.update((bytes.length + ":").getBytes(StandardCharsets.US_ASCII));
				digest.update(bytes);
			}
			return HexFormat.of().formatHex(digest.digest());
		} catch (NoSuchAlgorithmException impossible) {
			throw new IllegalStateException(impossible);
		}
	}

	/** A copy of the cached samples, or null. */
	public synchronized short[] get(String key) {
		short[] value = entries.get(key);
		if (value == null) {
			misses++;
			return null;
		}
		hits++;
		return value.clone();
	}

	public synchronized void put(String key, short[] samples) {
		short[] previous = entries.remove(key);
		if (previous != null) bytes -= sizeOf(previous);
		long size = sizeOf(samples);
		if (size > maxBytes) return;
		entries.put(key, samples.clone());
		bytes += size;
		Iterator<Map.Entry<String, short[]>> eldest = entries.entrySet().iterator();
		while (bytes > maxBytes && eldest.hasNext()) {
			bytes -= sizeOf(eldest.next().getValue());
			eldest.remove();
		}
	}

	/**
	 * Returns cached audio, joins an identical synthesis already running, or starts one with {@code synthesize}.
	 * Failures are not cached. Every caller receives its own copy.
	 */
	public CompletableFuture<short[]> getOrSynthesize(String key, Supplier<CompletableFuture<short[]>> synthesize) {
		CompletableFuture<short[]> shared;
		boolean owner = false;
		synchronized (this) {
			short[] cached = get(key);
			if (cached != null) return CompletableFuture.completedFuture(cached);
			shared = inFlight.get(key);
			if (shared == null) {
				shared = new CompletableFuture<>();
				inFlight.put(key, shared);
				owner = true;
			}
		}
		if (owner) {
			CompletableFuture<short[]> target = shared;
			CompletableFuture<short[]> source;
			try {
				source = synthesize.get();
			} catch (RuntimeException failure) {
				source = CompletableFuture.failedFuture(failure);
			}
			source.whenComplete((samples, failure) -> {
				synchronized (this) {
					inFlight.remove(key, target);
					if (failure == null && samples != null && samples.length > 0) put(key, samples);
				}
				if (failure != null) target.completeExceptionally(failure);
				else target.complete(samples);
			});
		}
		return shared.thenApply(short[]::clone);
	}

	public synchronized long sizeBytes() {
		return bytes;
	}

	public synchronized int size() {
		return entries.size();
	}

	public synchronized long hits() {
		return hits;
	}

	public synchronized long misses() {
		return misses;
	}

	public synchronized void clear() {
		entries.clear();
		bytes = 0;
	}

	private static long sizeOf(short[] samples) {
		return (long) samples.length * Short.BYTES;
	}
}
