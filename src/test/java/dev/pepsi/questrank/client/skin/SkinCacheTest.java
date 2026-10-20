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
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;

/** Caché acotada de skins (§11.4/§12.1): LRU, deduplicación, no bloqueo, backoff y concurrencia. */
class SkinCacheTest {

    private static SkinRequest req(long most, long least) {
        return new SkinRequest(new UUID(most, least), "player-" + most + "-" + least);
    }

    @Test
    void requestsAreDeduplicated() {
        AtomicInteger loads = new AtomicInteger();
        SkinCache<String> cache = new SkinCache<>(8, request -> {
            loads.incrementAndGet();
            return CompletableFuture.completedFuture("skin:" + request.id());
        });
        SkinRequest request = req(1, 1);
        CompletableFuture<String> first = cache.request(request);
        CompletableFuture<String> second = cache.request(request);
        assertSame(first, second);
        assertEquals(1, loads.get());
        assertEquals("skin:" + request.id(), cache.peek(request));
    }

    @Test
    void capacityEvictsLeastRecentlyUsed() {
        SkinCache<String> cache = new SkinCache<>(2, request -> CompletableFuture.completedFuture("s"));
        SkinRequest a = req(1, 1);
        SkinRequest b = req(1, 2);
        SkinRequest c = req(1, 3);
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
        SkinCache<String> cache = new SkinCache<>(4, request -> {
            loads.incrementAndGet();
            return new CompletableFuture<>();
        });
        SkinRequest request = req(2, 2);
        assertNull(cache.peek(request));
        assertEquals(0, loads.get());
        cache.request(request);
        assertEquals(1, loads.get());
        assertNull(cache.peek(request), "aún no completada ⇒ marcador, sin bloquear");
    }

    @Test
    void failedLoadIsRetriedAfterBackoff() {
        AtomicInteger loads = new AtomicInteger();
        AtomicLong now = new AtomicLong();
        SkinCache<String> cache = new SkinCache<>(4, request -> {
            loads.incrementAndGet();
            return CompletableFuture.failedFuture(new IllegalStateException("boom"));
        }, now::get);
        SkinRequest request = req(3, 3);
        cache.request(request);
        assertNull(cache.peek(request), "el fallo se observa y programa el reintento");
        cache.request(request);
        assertEquals(1, loads.get(), "dentro del backoff no se recarga");
        now.set(SkinCache.RETRY_BACKOFF_MILLIS - 1);
        cache.request(request);
        assertEquals(1, loads.get());
        now.set(SkinCache.RETRY_BACKOFF_MILLIS);
        cache.request(request);
        assertEquals(2, loads.get(), "al expirar el backoff se reintenta");
    }

    @Test
    void firstFailureObservationSchedulesRetryWithoutImmediateReload() {
        AtomicInteger loads = new AtomicInteger();
        AtomicLong now = new AtomicLong();
        SkinCache<String> cache = new SkinCache<>(4, request -> {
            loads.incrementAndGet();
            return CompletableFuture.failedFuture(new IllegalStateException("boom"));
        }, now::get);
        SkinRequest request = req(5, 5);
        cache.request(request);
        cache.request(request);
        assertEquals(1, loads.get(), "la primera observación del fallo programa el backoff");
        now.set(SkinCache.RETRY_BACKOFF_MILLIS);
        cache.request(request);
        assertEquals(2, loads.get());
    }

    @Test
    void concurrentExpiredRetriesLoadOnlyOnce() throws Exception {
        AtomicInteger loads = new AtomicInteger();
        AtomicLong now = new AtomicLong();
        SkinCache<String> cache = new SkinCache<>(8, request -> {
            int attempt = loads.incrementAndGet();
            return attempt == 1
                    ? CompletableFuture.failedFuture(new IllegalStateException("boom"))
                    : new CompletableFuture<>();
        }, now::get);
        SkinRequest request = req(6, 6);
        cache.request(request);
        assertNull(cache.peek(request));
        now.set(SkinCache.RETRY_BACKOFF_MILLIS);

        int threads = 8;
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        CyclicBarrier barrier = new CyclicBarrier(threads);
        List<Future<CompletableFuture<String>>> results = new ArrayList<>();
        try {
            for (int i = 0; i < threads; i++) {
                results.add(pool.submit(() -> {
                    barrier.await();
                    return cache.request(request);
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
        SkinCache<String> cache = new SkinCache<>(4, request -> CompletableFuture.completedFuture("s"));
        cache.request(req(4, 4));
        cache.clear();
        assertEquals(0, cache.size());
    }

    @Test
    void clearForcesFreshLoad() {
        AtomicInteger loads = new AtomicInteger();
        SkinCache<String> cache = new SkinCache<>(4, request -> {
            loads.incrementAndGet();
            return CompletableFuture.completedFuture("s" + loads.get());
        });
        SkinRequest request = req(7, 7);
        assertEquals("s1", cache.request(request).join());
        cache.clear();
        assertEquals("s2", cache.request(request).join());
        assertEquals(2, loads.get());
    }

    @Test
    void loaderReceivesRealProfileName() {
        AtomicReference<SkinRequest> seen = new AtomicReference<>();
        SkinCache<String> cache = new SkinCache<>(4, request -> {
            seen.set(request);
            return CompletableFuture.completedFuture("skin:" + request.profileName());
        });
        SkinRequest request = new SkinRequest(new UUID(8, 8), "TestPlayer");

        assertEquals("skin:TestPlayer", cache.request(request).join());
        assertSame(request, seen.get());
        assertEquals("TestPlayer", seen.get().profileName());
    }

    @Test
    void sameUuidWithDifferentNamesLoadsSeparately() {
        AtomicInteger loads = new AtomicInteger();
        SkinCache<String> cache = new SkinCache<>(4, request -> {
            loads.incrementAndGet();
            return CompletableFuture.completedFuture("skin:" + request.profileName());
        });
        UUID id = new UUID(9, 9);
        SkinRequest first = new SkinRequest(id, "TestPlayer");
        SkinRequest second = new SkinRequest(id, "Marga");

        assertEquals("skin:TestPlayer", cache.request(first).join());
        assertEquals("skin:Marga", cache.request(second).join());
        assertEquals(2, loads.get());
        assertEquals("skin:TestPlayer", cache.peek(first));
        assertEquals("skin:Marga", cache.peek(second));
    }

    @Test
    void blankNameFallsBackToUuid() {
        SkinRequest request = new SkinRequest(new UUID(10, 10), " ");
        assertEquals(request.id().toString(), request.profileName());
    }
}
