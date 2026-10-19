package dev.pepsi.questrank.client.net;

/**
 * Generador de {@code requestId} del cliente (contrato §9.1): contador monotónico en
 * {@code 1..2^63−1} que comienza en 1 y nunca se reinicia ni se reutiliza, tampoco al reconectar.
 *
 * <p>Al agotarse, deja de emitir identificadores ({@link #isExhausted()}) sin reiniciar el
 * contador; el aviso en interfaz es responsabilidad de F5-B.
 */
public final class RequestIdGenerator {

    private final long max;
    private long next = 1L;
    private boolean exhausted;

    public RequestIdGenerator() {
        this(Long.MAX_VALUE);
    }

    RequestIdGenerator(long max) {
        if (max < 1L) {
            throw new IllegalArgumentException("max must be >= 1: " + max);
        }
        this.max = max;
    }

    /** {@code true} cuando ya se emitió el último identificador representable. */
    public boolean isExhausted() {
        return exhausted;
    }

    /** Emite el siguiente identificador; falla si el espacio está agotado. */
    public long take() {
        if (exhausted) {
            throw new IllegalStateException("request id space exhausted");
        }
        long id = next;
        if (next >= max) {
            exhausted = true;
        } else {
            next++;
        }
        return id;
    }
}
