package dev.pepsi.questrank.integration.ftb;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;

import org.junit.jupiter.api.Test;

/**
 * Ciclo de vida del puente de eventos (sin FTB en ejecución): attach/detach son seguros e
 * idempotentes. El despacho real de eventos FTB se verifica con el servidor en marcha.
 */
class FtbEventBridgeTest {

    private static final Runnable NOOP = () -> {
    };

    @Test
    void attachIsSafeAndIdempotent() {
        assertDoesNotThrow(() -> {
            FtbEventBridge.attach(NOOP, NOOP, NOOP);
            FtbEventBridge.attach(NOOP, NOOP, NOOP);
        });
    }

    @Test
    void detachIsSafe() {
        assertDoesNotThrow(FtbEventBridge::detach);
    }
}
