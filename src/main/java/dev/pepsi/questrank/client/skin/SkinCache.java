package dev.pepsi.questrank.client.skin;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.function.Function;
import java.util.function.LongSupplier;

/**
 * Caché acotada (LRU) de skins de jugador (contrato §10.4, §11.4, §12.1).
 *
 * <p>La resolución es asíncrona y delegada (en producción, {@code SkinManager.getOrLoad} de la API
 * vanilla); esta clase no descarga nada, no usa CustomSkinLoader y no crea hilos. El render nunca
 * bloquea: consulta {@link #peek(UUID)} y, si aún no está, solicita la carga.
 *
 * <p>Tras una carga fallida se aplica un backoff acotado antes de reintentar: la primera
 * observación del fallo fija el momento de reintento y no se vuelve a llamar al loader hasta que
 * expire, de modo que las peticiones concurrentes al expirar reutilizan una única carga nueva.
 */
public final class SkinCache<T> {

    /** Límite por defecto de entradas (caché acotada). */
    public static final int DEFAULT_CAPACITY = 128;

    /** Backoff acotado tras una carga fallida (parámetro operativo). */
    public static final long RETRY_BACKOFF_MILLIS = 5_000L;

    private final int capacity;
    private final Function<UUID, CompletableFuture<T>> loader;
    private final LongSupplier clock;
    private final Map<UUID, Entry<T>> cache;

    public SkinCache(int capacity, Function<UUID, CompletableFuture<T>> loader) {
        this(capacity, loader, System::currentTimeMillis);
    }

    SkinCache(int capacity, Function<UUID, CompletableFuture<T>> loader, LongSupplier clock) {
        if (capacity < 1) {
            throw new IllegalArgumentException("capacity must be >= 1: " + capacity);
        }
        this.capacity = capacity;
        this.loader = Objects.requireNonNull(loader, "loader");
        this.clock = Objects.requireNonNull(clock, "clock");
        this.cache = new LinkedHashMap<>(capacity, 0.75f, true) {
            @Override
            protected boolean removeEldestEntry(Map.Entry<UUID, Entry<T>> eldest) {
                return size() > SkinCache.this.capacity;
            }
        };
    }

    /**
     * Resultado ya disponible, sin disparar carga; {@code null} si no está cacheado, si aún no
     * ha terminado o si la carga falló. La primera observación de un fallo programa el backoff.
     */
    public synchronized T peek(UUID playerId) {
        Entry<T> entry = cache.get(playerId);
        if (entry == null) {
            return null;
        }
        if (entry.future.isCompletedExceptionally()) {
            scheduleRetry(entry);
            return null;
        }
        return entry.future.isDone() ? entry.future.getNow(null) : null;
    }

    /**
     * Solicita la carga (o reutiliza la existente). Tras un fallo, no se vuelve a cargar hasta
     * que expira el backoff; las peticiones concurrentes que expiran el backoff reutilizan la
     * única carga nueva creada por la primera de ellas.
     */
    public synchronized CompletableFuture<T> request(UUID playerId) {
        long now = clock.getAsLong();
        Entry<T> entry = cache.get(playerId);
        if (entry == null) {
            Entry<T> created = new Entry<>(load(playerId));
            cache.put(playerId, created);
            return created.future;
        }
        if (!entry.future.isCompletedExceptionally()) {
            return entry.future;
        }
        if (!entry.retryPending) {
            entry.retryPending = true;
            entry.retryAfterMillis = now + RETRY_BACKOFF_MILLIS;
            return entry.future;
        }
        if (now < entry.retryAfterMillis) {
            return entry.future;
        }
        Entry<T> replacement = new Entry<>(load(playerId));
        cache.put(playerId, replacement);
        return replacement.future;
    }

    public synchronized int size() {
        return cache.size();
    }

    public int capacity() {
        return capacity;
    }

    /** Vacía la caché (cambio de conexión o desconexión). */
    public synchronized void clear() {
        cache.clear();
    }

    private void scheduleRetry(Entry<T> entry) {
        if (!entry.retryPending) {
            entry.retryPending = true;
            entry.retryAfterMillis = clock.getAsLong() + RETRY_BACKOFF_MILLIS;
        }
    }

    private CompletableFuture<T> load(UUID playerId) {
        CompletableFuture<T> future = loader.apply(playerId);
        return future != null
                ? future
                : CompletableFuture.failedFuture(new NullPointerException("skin loader returned null"));
    }

    /** Entrada mutable: el backoff se actualiza bajo el lock de la caché. */
    private static final class Entry<T> {

        private final CompletableFuture<T> future;
        private boolean retryPending;
        private long retryAfterMillis;

        private Entry(CompletableFuture<T> future) {
            this.future = Objects.requireNonNull(future, "future");
        }
    }
}
