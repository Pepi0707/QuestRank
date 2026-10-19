package dev.pepsi.questrank.client.skin;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.Test;

/** Caché acotada de skins (§11.4/§12.1): LRU, deduplicación, no bloqueo, backoff y concurrencia. */
class SkinCacheTest {

    @Test
    void requestsAreDeduplicated() {
        AtomicInteger loads = new AtomicInteger();
        SkinCache<String> cache = new SkinCache<>(8, id -> {
            loads.incrementAndGet();
            return CompletableFuture.completedFuture("skin:" + id);
        });
        UUID id = new UUID(1, 1);
        CompletableFuture<String> first = cache.request(id);
        CompletableFuture<String> second = cache.request(id);
        assertSame(first, second);
        assertEquals(1, loads.get());
        assertEquals("skin:" + id, cache.peek(id));
    }

    @Test
    void capacityEvictsLeastRecentlyUsed() {
        SkinCache<String> cache = new SkinCache<>(2, id -> CompletableFuture.completedFuture("s"));
        UUID a = new UUID(1, 1);
        UUID b = new UUID(1, 2);
        UUID c = new UUID(1, 3);
        cache.request(a);
        cache.request(b);
        cache.peek(a);
        cache.request(c);
        assertEquals(2, cache.size());
        assertNotNull(cache.peek(a));
        assertNull(cache.peek(b));
    }

    @Test
    void peekDoesNotTriggerLoad() {
        AtomicInteger loads = new AtomicInteger();
        SkinCache<String> cache = new SkinCache<>(4, id -> {
            loads.incrementAndGet();
            return new CompletableFuture<>();
        });
        assertNull(cache.peek(new UUID(2, 2)));
        assertEquals(0, loads.get());
        cache.request(new UUID(2, 2));
        assertEquals(1, loads.get());
        assertNull(cache.peek(new UUID(2, 2)), "aún no completada ⇒ marcador, sin bloquear");
    }

    @Test
    void failedLoadIsRetriedAfterBackoff() {
        AtomicInteger loads = new AtomicInteger();
        AtomicLong now = new AtomicLong();
        SkinCache<String> cache = new SkinCache<>(4, id -> {
            loads.incrementAndGet();
            return CompletableFuture.failedFuture(new IllegalStateException("boom"));
        }, now::get);
        UUID id = new UUID(3, 3);
        cache.request(id);
        assertNull(cache.peek(id), "el fallo se observa y programa el reintento");
        cache.request(id);
        assertEquals(1, loads.get(), "dentro del backoff no se recarga");
        now.set(SkinCache.RETRY_BACKOFF_MILLIS - 1);
        cache.request(id);
        assertEquals(1, loads.get());
        now.set(SkinCache.RETRY_BACKOFF_MILLIS);
        cache.request(id);
        assertEquals(2, loads.get(), "al expirar el backoff se reintenta");
    }

    @Test
    void firstFailureObservationSchedulesRetryWithoutImmediateReload() {
        AtomicInteger loads = new AtomicInteger();
        AtomicLong now = new AtomicLong();
        SkinCache<String> cache = new SkinCache<>(4, id -> {
            loads.incrementAndGet();
            return CompletableFuture.failedFuture(new IllegalStateException("boom"));
        }, now::get);
        UUID id = new UUID(5, 5);
        cache.request(id);
        cache.request(id);
        assertEquals(1, loads.get(), "la primera observación del fallo programa el backoff");
        now.set(SkinCache.RETRY_BACKOFF_MILLIS);
        cache.request(id);
        assertEquals(2, loads.get());
    }

    @Test
    void concurrentExpiredRetriesLoadOnlyOnce() throws Exception {
        AtomicInteger loads = new AtomicInteger();
        AtomicLong now = new AtomicLong();
        SkinCache<String> cache = new SkinCache<>(8, id -> {
            int attempt = loads.incrementAndGet();
            return attempt == 1
                    ? CompletableFuture.failedFuture(new IllegalStateException("boom"))
                    : new CompletableFuture<>();
        }, now::get);
        UUID id = new UUID(6, 6);
        cache.request(id);
        assertNull(cache.peek(id));
        now.set(SkinCache.RETRY_BACKOFF_MILLIS);

        int threads = 8;
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        CyclicBarrier barrier = new CyclicBarrier(threads);
        List<Future<CompletableFuture<String>>> results = new ArrayList<>();
        try {
            for (int i = 0; i < threads; i++) {
                results.add(pool.submit(() -> {
                    barrier.await();
                    return cache.request(id);
                }));
            }
            Set<CompletableFuture<String>> distinct = new HashSet<>();
            for (Future<CompletableFuture<String>> result : results) {
                distinct.add(result.get(5, TimeUnit.SECONDS));
            }
            assertEquals(1, distinct.size(), "todas reutilizan la única carga nueva");
            assertEquals(2, loads.get());
        } finally {
            pool.shutdownNow();
        }
    }

    @Test
    void clearEmptiesCache() {
        SkinCache<String> cache = new SkinCache<>(4, id -> CompletableFuture.completedFuture("s"));
        cache.request(new UUID(4, 4));
        cache.clear();
        assertEquals(0, cache.size());
    }
}
