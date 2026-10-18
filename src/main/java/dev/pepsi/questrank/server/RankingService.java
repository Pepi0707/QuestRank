package dev.pepsi.questrank.server;

import dev.pepsi.questrank.core.catalog.ComputableUniverse;
import dev.pepsi.questrank.core.catalog.RankingPolicy;
import dev.pepsi.questrank.core.model.ParticipantData;
import dev.pepsi.questrank.core.model.Ranking;
import dev.pepsi.questrank.core.score.RankingCalculator;
import dev.pepsi.questrank.core.signature.CatalogFingerprint;
import dev.pepsi.questrank.core.signature.DisplaySignature;
import dev.pepsi.questrank.integration.ftb.FtbCatalog;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

/**
 * Servicio de servidor (contrato §8.5–§8.11): valida el catálogo, reconstruye la clasificación y
 * decide la respuesta, conservando por separado el catálogo validado, las revisiones y el snapshot
 * publicable.
 *
 * <p><strong>Decisión transaccional:</strong> la validación del catálogo se confirma de inmediato
 * al cambiar F_cat (§8.4: `catalogRevision` refleja el cambio, sin retrasarlo) junto con el
 * universo cacheado; la publicación del display es una transacción independiente que solo se
 * confirma con una reconstrucción completa (§8.10(b)). Si la reconstrucción falla (participantes
 * no disponibles), el estado publicado queda intacto — sin `rev`, sin snapshot, sin notificación —
 * y toda petición responde {@code Error} hasta que una reconstrucción completa vuelva a producir
 * un snapshot compatible con el catálogo validado; nunca se sirve un snapshot incompatible como
 * vigente (§8.5/§8.6).
 *
 * <p>Confinado al hilo del servidor: todas las llamadas deben realizarse desde él (el wiring
 * garantiza el marshalling y los adaptadores F3 verifican el hilo). Sin caché ajena a los memos y
 * TTLs normativos de §8.9.
 */
public final class RankingService {

    /** Constantes normativas de §8.9, expresadas en ticks (20 TPS). */
    public static final int TICKS_PER_SECOND = 20;
    public static final int EVENT_DEBOUNCE_TICKS = TICKS_PER_SECOND;
    public static final int CATALOG_MEMO_TICKS = TICKS_PER_SECOND;
    public static final int DISPLAY_MEMO_TICKS = TICKS_PER_SECOND;
    public static final int CATALOG_TTL_TICKS = 5 * TICKS_PER_SECOND;
    public static final int DISPLAY_TTL_TICKS = 30 * TICKS_PER_SECOND;

    /** Fuente del catálogo vivo (adaptador F3); {@code empty} = no disponible. */
    @FunctionalInterface
    public interface CatalogSource {
        Optional<FtbCatalog> read();
    }

    /** Fuente de participantes vivos (adaptador F3); {@code empty} = no disponible. */
    @FunctionalInterface
    public interface ParticipantsSource {
        Optional<List<ParticipantData>> read();
    }

    /** Fuente de política del universo y revisión de configuración (§8.2, §4.4). */
    public interface PolicySource {
        RankingPolicy policy();

        long configRevision();
    }

    /** Notificación de snapshot nuevo, tras publicarse de forma coherente (hilo del servidor). */
    @FunctionalInterface
    public interface SnapshotListener {
        void onSnapshot(ServiceSnapshot snapshot);
    }

    private final long epoch;
    private final CatalogSource catalogSource;
    private final ParticipantsSource participantsSource;
    private final PolicySource policySource;
    private SnapshotListener snapshotListener = snapshot -> {
    };

    // Catálogo validado (se confirma al validar; §8.4).
    private Long lastFCat;
    private long catalogRevision;
    private ComputableUniverse universe;

    // Display publicado (se confirma solo con reconstrucción completa).
    private Long lastSDisplay;
    private long rev;
    private ServiceSnapshot snapshot;

    // Planificación (memos, debounce y TTLs de §8.9).
    private boolean displayDirty = true;
    private boolean eventsPending;
    private boolean catalogReloadPending;
    private int catalogMemoTicks;
    private int displayMemoTicks;
    private int debounceTicks;
    private int catalogTtlTicks = CATALOG_TTL_TICKS;
    private int displayTtlTicks = DISPLAY_TTL_TICKS;
    private int subscribers;

    public RankingService(long epoch, CatalogSource catalogSource, ParticipantsSource participantsSource,
                          PolicySource policySource) {
        this.epoch = epoch;
        this.catalogSource = Objects.requireNonNull(catalogSource, "catalogSource");
        this.participantsSource = Objects.requireNonNull(participantsSource, "participantsSource");
        this.policySource = Objects.requireNonNull(policySource, "policySource");
    }

    /** Listener de snapshots nuevos (sustituye al no-op por defecto). */
    public void setSnapshotListener(SnapshotListener listener) {
        this.snapshotListener = Objects.requireNonNull(listener, "listener");
    }

    /** Número de suscriptores activos (lo mantiene F5); gatea los ciclos y TTLs (§8.7/§8.8). */
    public void setSubscriberCount(int count) {
        if (count < 0) {
            throw new IllegalArgumentException("subscriber count must be >= 0: " + count);
        }
        subscribers = count;
    }

    /** Señal de invalidación: cambios de equipos (§8.7: creación, borrado, unión, salida, login, propiedades). */
    public void onTeamsChanged() {
        markEventPending();
    }

    /** Señal de invalidación: finalizaciones de misiones (§8.7). */
    public void onProgressChanged() {
        markEventPending();
    }

    /** Señal de invalidación: recarga del catálogo; fuerza la validación aunque el memo esté vigente. */
    public void onCatalogReloaded() {
        catalogReloadPending = true;
        markEventPending();
    }

    private void markEventPending() {
        eventsPending = true;
        displayDirty = true;
        debounceTicks = EVENT_DEBOUNCE_TICKS;
    }

    /** Cronómetro por tick (§8.8): solo decrementa; los ciclos se disparan con suscriptores activos. */
    public void onTick() {
        catalogMemoTicks = decrement(catalogMemoTicks);
        displayMemoTicks = decrement(displayMemoTicks);
        debounceTicks = decrement(debounceTicks);
        catalogTtlTicks = decrement(catalogTtlTicks);
        displayTtlTicks = decrement(displayTtlTicks);
        if (subscribers <= 0) {
            if (debounceTicks == 0) {
                eventsPending = false;
            }
            return;
        }
        if ((debounceTicks == 0 && eventsPending) || displayTtlTicks == 0) {
            eventsPending = false;
            if (displayTtlTicks == 0) {
                displayTtlTicks = DISPLAY_TTL_TICKS;
            }
            displayCycle();
        } else if (catalogTtlTicks == 0) {
            catalogTtlTicks = CATALOG_TTL_TICKS;
            catalogCycle();
        }
    }

    /** Ciclo de display: validar y reconstruir (eventos agrupados o TTL de display, §8.11(b)). */
    private void displayCycle() {
        if (!validateCatalog(catalogReloadPending)) {
            return;
        }
        displayDirty = true;
        rebuild();
    }

    /** Ciclo de catálogo: validar y reconstruir solo si F_cat cambió o hay suciedad pendiente. */
    private void catalogCycle() {
        if (!validateCatalog(false)) {
            return;
        }
        if (displayDirty) {
            rebuild();
        }
    }

    /**
     * Pipeline completo de §8.10: (a) validar catálogo, (b) reconstruir si el display está sucio o
     * expiró su memo, (c) decidir Unchanged/Snapshot; {@code Error} mientras no pueda obtenerse un
     * estado coherente.
     */
    public RankingResult requestState(Long knownEpoch, long knownRev) {
        if (!validateCatalog(catalogReloadPending)) {
            return new RankingResult.Error(RankingResult.ErrorKind.CATALOG_UNAVAILABLE);
        }
        if (displayDirty || displayMemoTicks == 0) {
            if (!rebuild()) {
                return new RankingResult.Error(RankingResult.ErrorKind.PARTICIPANTS_UNAVAILABLE);
            }
        }
        ServiceSnapshot current = snapshot;
        if (current == null || current.catalogRevision() != catalogRevision) {
            // Invariante interna: cada camino de respuesta pasa por una reconstrucción completa o
            // por un memo válido tras ella. No puede alcanzarse; fail-fast si algún día se rompe.
            throw new IllegalStateException("QuestRank: published snapshot is not coherent with the validated catalog");
        }
        if (knownEpoch != null && knownEpoch.longValue() == epoch && knownRev == rev) {
            return RankingResult.Unchanged.INSTANCE;
        }
        return new RankingResult.Snapshot(current);
    }

    /**
     * §8.10(a). Devuelve {@code false} si el catálogo no está disponible (sin mutar estado y sin
     * memoizar el fallo). En éxito confirma F_cat y, si cambió, incrementa {@code catalogRevision},
     * actualiza el universo y marca el display como sucio (la reconstrucción queda obligada antes
     * de responder con ranking).
     */
    private boolean validateCatalog(boolean force) {
        if (!force && catalogMemoTicks > 0) {
            return true;
        }
        Optional<FtbCatalog> maybeCatalog = catalogSource.read();
        if (maybeCatalog.isEmpty()) {
            return false;
        }
        FtbCatalog catalog = maybeCatalog.get();
        long fcat = CatalogFingerprint.compute(catalog.identity(), policySource.configRevision(), catalog.entries());
        boolean changed = lastFCat == null || lastFCat.longValue() != fcat;
        if (changed) {
            lastFCat = fcat;
            catalogRevision++;
            universe = ComputableUniverse.compute(policySource.policy(), catalog.entries());
            displayDirty = true;
        }
        catalogMemoTicks = CATALOG_MEMO_TICKS;
        catalogTtlTicks = CATALOG_TTL_TICKS;
        catalogReloadPending = false;
        return true;
    }

    /**
     * §8.10(b). Reconstrucción completa. Devuelve {@code false} si los participantes no están
     * disponibles (sin mutar el estado publicado). En éxito confirma memos/TTLs y, si S_display
     * cambió, incrementa {@code rev}, sustituye el snapshot y notifica al listener una sola vez,
     * tras dejar el estado consistente.
     */
    private boolean rebuild() {
        ComputableUniverse currentUniverse = universe;
        if (currentUniverse == null) {
            // Invariante interna: solo se reconstruye tras una validación con éxito.
            throw new IllegalStateException("QuestRank: rebuild requested before a successful catalog validation");
        }
        Optional<List<ParticipantData>> maybeParticipants = participantsSource.read();
        if (maybeParticipants.isEmpty()) {
            return false;
        }
        Ranking ranking = RankingCalculator.compute(currentUniverse, maybeParticipants.get());
        long sdisplay = DisplaySignature.compute(catalogRevision, ranking);
        boolean published = false;
        if (lastSDisplay == null || lastSDisplay.longValue() != sdisplay) {
            lastSDisplay = sdisplay;
            rev++;
            snapshot = new ServiceSnapshot(epoch, rev, catalogRevision, ranking);
            published = true;
        }
        displayMemoTicks = DISPLAY_MEMO_TICKS;
        displayTtlTicks = DISPLAY_TTL_TICKS;
        displayDirty = false;
        eventsPending = false;
        debounceTicks = 0;
        if (published) {
            snapshotListener.onSnapshot(snapshot);
        }
        return true;
    }

    private static int decrement(int ticks) {
        return ticks > 0 ? ticks - 1 : 0;
    }

    // Accesores de paquete para pruebas; la frontera con F5 usa los resultados de requestState.
    long currentCatalogRevision() {
        return catalogRevision;
    }

    long currentRev() {
        return rev;
    }

    ServiceSnapshot currentSnapshot() {
        return snapshot;
    }
}
