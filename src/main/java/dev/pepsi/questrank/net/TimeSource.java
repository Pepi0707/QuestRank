package dev.pepsi.questrank.net;

/**
 * Reloj monotónico en milisegundos, inyectable para pruebas deterministas.
 *
 * <p>El diseño usa tiempo monotónico (no reloj de pared) para expiraciones, throttles y ventanas
 * del protocolo de sesión (§9.6, §9.9.D).
 */
@FunctionalInterface
public interface TimeSource {

    long nowMillis();

    /** Reloj monotónico del sistema. */
    TimeSource MONOTONIC = () -> System.nanoTime() / 1_000_000L;
}
