package dev.pepsi.questrank.server.net;

/**
 * Cubo de fichas para el límite de peticiones por jugador (contrato §9.6 [PARAM]):
 * ráfaga 3, recarga 1/s. Determinista con un {@link dev.pepsi.questrank.net.TimeSource} inyectado.
 *
 * <p>El exceso se descarta en silencio (decisión A-2): no genera respuesta ni altera estado.
 */
public final class TokenBucket {

    private final int capacity;
    private final long refillIntervalMillis;
    private double tokens;
    private long lastRefillMillis;

    public TokenBucket(int capacity, long refillIntervalMillis, long nowMillis) {
        if (capacity < 1) {
            throw new IllegalArgumentException("capacity must be >= 1: " + capacity);
        }
        if (refillIntervalMillis < 1) {
            throw new IllegalArgumentException("refill interval must be >= 1 ms: " + refillIntervalMillis);
        }
        this.capacity = capacity;
        this.refillIntervalMillis = refillIntervalMillis;
        this.tokens = capacity;
        this.lastRefillMillis = nowMillis;
    }

    /** Consume una ficha si hay disponible; devuelve si la operación fue admitida. */
    public boolean tryConsume(long nowMillis) {
        refill(nowMillis);
        if (tokens >= 1.0d) {
            tokens -= 1.0d;
            return true;
        }
        return false;
    }

    private void refill(long nowMillis) {
        long elapsed = nowMillis - lastRefillMillis;
        if (elapsed < refillIntervalMillis) {
            return;
        }
        long intervals = elapsed / refillIntervalMillis;
        tokens = Math.min(capacity, tokens + intervals);
        lastRefillMillis += intervals * refillIntervalMillis;
    }
}
