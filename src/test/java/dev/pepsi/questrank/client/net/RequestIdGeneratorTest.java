package dev.pepsi.questrank.client.net;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

/** Contador de {@code requestId} (contrato §9.1): monotonía, no reutilización y agotamiento sin reinicio. */
class RequestIdGeneratorTest {

    @Test
    void startsAtOneAndIncrements() {
        RequestIdGenerator ids = new RequestIdGenerator();
        assertFalse(ids.isExhausted());
        assertEquals(1L, ids.take());
        assertEquals(2L, ids.take());
        assertEquals(3L, ids.take());
    }

    @Test
    void neverReusesIdsAcrossReconnections() {
        RequestIdGenerator ids = new RequestIdGenerator();
        long first = ids.take();
        long second = ids.take();
        assertEquals(first + 1L, second);
        assertEquals(first + 2L, ids.take(), "el contador no se reinicia al reconectar");
    }

    @Test
    void exhaustionStopsEmissionAndNeverResets() {
        RequestIdGenerator ids = new RequestIdGenerator(3L);
        assertEquals(1L, ids.take());
        assertEquals(2L, ids.take());
        assertFalse(ids.isExhausted());
        assertEquals(3L, ids.take());
        assertTrue(ids.isExhausted());
        assertThrows(IllegalStateException.class, ids::take);
        assertTrue(ids.isExhausted());
    }
}
