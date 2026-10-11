package com.guardvillagers.voice;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

class TtsCacheTest {
	@Test
	void evictsLeastRecentlyUsedByBytes() {
		TtsCache cache = new TtsCache(20); // 10 samples
		cache.put("a", new short[4]);
		cache.put("b", new short[4]);
		assertNotNull(cache.get("a")); // a is now most recent
		cache.put("c", new short[4]); // 24 bytes > 20: evict b
		assertNull(cache.get("b"));
		assertNotNull(cache.get("a"));
		assertNotNull(cache.get("c"));
		assertEquals(16, cache.sizeBytes());
	}

	@Test
	void entriesLargerThanTheCacheAreNotStored() {
		TtsCache cache = new TtsCache(10);
		cache.put("big", new short[6]);
		assertEquals(0, cache.size());
		assertEquals(0, cache.sizeBytes());
	}

	@Test
	void replacingAKeyKeepsByteAccountingExact() {
		TtsCache cache = new TtsCache(100);
		cache.put("a", new short[10]);
		cache.put("a", new short[3]);
		assertEquals(6, cache.sizeBytes());
	}

	@Test
	void returnsCopiesSoCallersCannotCorruptTheCache() {
		TtsCache cache = new TtsCache(100);
		short[] original = {1, 2, 3};
		cache.put("a", original);
		original[0] = 9;
		short[] first = cache.get("a");
		first[1] = 9;
		assertArrayEquals(new short[] {1, 2, 3}, cache.get("a"));
	}

	@Test
	void identicalConcurrentRequestsShareOneSynthesisAndHitsCostNothing() {
		TtsCache cache = new TtsCache(1_000);
		AtomicInteger calls = new AtomicInteger();
		CompletableFuture<short[]> pending = new CompletableFuture<>();
		CompletableFuture<short[]> first = cache.getOrSynthesize("k", () -> {
			calls.incrementAndGet();
			return pending;
		});
		CompletableFuture<short[]> second = cache.getOrSynthesize("k", () -> {
			calls.incrementAndGet();
			return pending;
		});
		pending.complete(new short[] {7, 8});
		assertArrayEquals(new short[] {7, 8}, first.join());
		assertArrayEquals(new short[] {7, 8}, second.join());
		cache.getOrSynthesize("k", () -> {
			calls.incrementAndGet();
			return CompletableFuture.completedFuture(new short[1]);
		}).join();
		assertEquals(1, calls.get());
		first.join()[0] = 0;
		assertArrayEquals(new short[] {7, 8}, cache.get("k"), "joined results are independent copies");
	}

	@Test
	void failuresAreNotCached() {
		TtsCache cache = new TtsCache(1_000);
		RuntimeException boom = new RuntimeException("boom");
		CompletableFuture<short[]> failed = cache.getOrSynthesize("k", () -> CompletableFuture.failedFuture(boom));
		CompletionException thrown = assertThrows(CompletionException.class, failed::join);
		assertSame(boom, thrown.getCause());
		assertArrayEquals(new short[] {1},
				cache.getOrSynthesize("k", () -> CompletableFuture.completedFuture(new short[] {1})).join());
	}

	@Test
	void zeroSizedCacheStoresNothing() {
		TtsCache cache = TtsCache.ofMiB(0);
		cache.put("a", new short[1]);
		assertNull(cache.get("a"));
	}
}
