package dev.pepsi.questrank.server.net;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

/** Cubo de fichas del límite de peticiones (§9.6 [PARAM]): ráfaga 3, recarga 1/s. */
class TokenBucketTest {

    @Test
    void burstThenRefill() {
        TokenBucket bucket = new TokenBucket(3, 1000L, 0L);
        assertTrue(bucket.tryConsume(0L));
        assertTrue(bucket.tryConsume(0L));
        assertTrue(bucket.tryConsume(0L));
        assertFalse(bucket.tryConsume(0L));
        assertTrue(bucket.tryConsume(1000L));
        assertFalse(bucket.tryConsume(1000L));
        assertTrue(bucket.tryConsume(2000L));
    }

    @Test
    void refillIsCappedAtCapacity() {
        TokenBucket bucket = new TokenBucket(3, 1000L, 0L);
        assertTrue(bucket.tryConsume(60_000L));
        assertTrue(bucket.tryConsume(60_000L));
        assertTrue(bucket.tryConsume(60_000L));
        assertFalse(bucket.tryConsume(60_000L));
    }

    @Test
    void instancesAreIndependent() {
        TokenBucket a = new TokenBucket(1, 1000L, 0L);
        TokenBucket b = new TokenBucket(1, 1000L, 0L);
        assertTrue(a.tryConsume(0L));
        assertFalse(a.tryConsume(0L));
        assertTrue(b.tryConsume(0L));
    }

    @Test
    void clockGoingBackwardsDoesNotRefill() {
        TokenBucket bucket = new TokenBucket(1, 1000L, 10_000L);
        assertTrue(bucket.tryConsume(10_000L));
        assertFalse(bucket.tryConsume(9_000L));
        assertFalse(bucket.tryConsume(10_999L));
        assertTrue(bucket.tryConsume(11_000L));
    }
}
