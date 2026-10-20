package dev.pepsi.questrank.server;

import dev.pepsi.questrank.core.catalog.ComputableUniverse;
import dev.pepsi.questrank.core.catalog.RankingPolicy;
import dev.pepsi.questrank.core.model.ParticipantData;
import dev.pepsi.questrank.core.model.Ranking;
import dev.pepsi.questrank.core.score.RankingCalculator;
import dev.pepsi.questrank.core.signature.CatalogFingerprint;
import dev.pepsi.questrank.core.signature.DisplaySignature;
import dev.pepsi.questrank.diagnostics.RankingDiagnostics;
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

    // Contadores de diagnóstico (G0); se reinician con cada instancia de servicio.
    private long diagTicks;
    private long diagCatalogCycles;
    private long diagDisplayCycles;
    private long diagRebuildAttempts;
    private long diagRebuildSuccess;
    private long diagRebuildPublished;
    private String diagCause = "request";

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
        diagTicks++;
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
            boolean displayTtl = displayTtlTicks == 0;
            if (displayTtl) {
                displayTtlTicks = DISPLAY_TTL_TICKS;
            }
            diagCause = displayTtl ? "ttl-display" : (catalogReloadPending ? "catalog-reload" : "event");
            displayCycle();
        } else if (catalogTtlTicks == 0) {
            catalogTtlTicks = CATALOG_TTL_TICKS;
            diagCause = "ttl-catalog";
            catalogCycle();
        }
    }

    /** Ciclo de display: validar y reconstruir (eventos agrupados o TTL de display, §8.11(b)). */
    private void displayCycle() {
        diagDisplayCycles++;
        if (!validateCatalog(catalogReloadPending)) {
            return;
        }
        displayDirty = true;
        rebuild();
    }

    /** Ciclo de catálogo: validar y reconstruir solo si F_cat cambió o hay suciedad pendiente. */
    private void catalogCycle() {
        diagCatalogCycles++;
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
        boolean diag = RankingDiagnostics.enabled();
        long start = diag ? System.nanoTime() : 0L;
        RankingResult result = requestStateInternal(knownEpoch, knownRev);
        if (diag) {
            RankingDiagnostics.requestPipeline(resultKind(result), System.nanoTime() - start);
        }
        return result;
    }

    private RankingResult requestStateInternal(Long knownEpoch, long knownRev) {
        diagCause = "request";
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

    private static String resultKind(RankingResult result) {
        if (result instanceof RankingResult.Snapshot) {
            return "snapshot";
        }
        if (result instanceof RankingResult.Unchanged) {
            return "unchanged";
        }
        return "error";
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
        boolean diag = RankingDiagnostics.enabled();
        long readStart = diag ? System.nanoTime() : 0L;
        Optional<FtbCatalog> maybeCatalog = catalogSource.read();
        long readNs = diag ? System.nanoTime() - readStart : 0L;
        if (maybeCatalog.isEmpty()) {
            RankingDiagnostics.catalogValidate(false, false, catalogRevision, readNs, 0L, 0L);
            return false;
        }
        FtbCatalog catalog = maybeCatalog.get();
        long fingerprintStart = diag ? System.nanoTime() : 0L;
        long fcat = CatalogFingerprint.compute(catalog.identity(), policySource.configRevision(), catalog.entries());
        long fingerprintNs = diag ? System.nanoTime() - fingerprintStart : 0L;
        boolean changed = lastFCat == null || lastFCat.longValue() != fcat;
        long universeNs = 0L;
        if (changed) {
            lastFCat = fcat;
            catalogRevision++;
            long universeStart = diag ? System.nanoTime() : 0L;
            universe = ComputableUniverse.compute(policySource.policy(), catalog.entries());
            universeNs = diag ? System.nanoTime() - universeStart : 0L;
            displayDirty = true;
        }
        catalogMemoTicks = CATALOG_MEMO_TICKS;
        catalogTtlTicks = CATALOG_TTL_TICKS;
        catalogReloadPending = false;
        RankingDiagnostics.catalogValidate(true, changed, catalogRevision, readNs, fingerprintNs, universeNs);
        return true;
    }

    /**
     * §8.10(b). Reconstrucción completa. Devuelve {@code false} si los participantes no están
     * disponibles (sin mutar el estado publicado). En éxito confirma memos/TTLs y, si S_display
     * cambió, incrementa {@code rev}, sustituye el snapshot y notifica al listener una sola vez,
     * tras dejar el estado consistente.
     */
    private boolean rebuild() {
        diagRebuildAttempts++;
        ComputableUniverse currentUniverse = universe;
        if (currentUniverse == null) {
            // Invariante interna: solo se reconstruye tras una validación con éxito.
            throw new IllegalStateException("QuestRank: rebuild requested before a successful catalog validation");
        }
        boolean diag = RankingDiagnostics.enabled();
        long participantsStart = diag ? System.nanoTime() : 0L;
        Optional<List<ParticipantData>> maybeParticipants = participantsSource.read();
        long participantsNs = diag ? System.nanoTime() - participantsStart : 0L;
        if (maybeParticipants.isEmpty()) {
            RankingDiagnostics.rebuild(false, 0, 0L, rev, catalogRevision, false,
                    participantsNs, 0L, 0L, diagCause);
            return false;
        }
        long scoreStart = diag ? System.nanoTime() : 0L;
        Ranking ranking = RankingCalculator.compute(currentUniverse, maybeParticipants.get());
        long scoreNs = diag ? System.nanoTime() - scoreStart : 0L;
        long signatureStart = diag ? System.nanoTime() : 0L;
        long sdisplay = DisplaySignature.compute(catalogRevision, ranking);
        long signatureNs = diag ? System.nanoTime() - signatureStart : 0L;
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
        diagRebuildSuccess++;
        if (published) {
            diagRebuildPublished++;
        }
        RankingDiagnostics.rebuild(true, maybeParticipants.get().size(), ranking.total(), rev,
                catalogRevision, published, participantsNs, scoreNs, signatureNs, diagCause);
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

    /** Contadores del escenario actual; se reinician con una instancia nueva del servicio. */
    DiagnosticCounters diagnosticCounters() {
        return new DiagnosticCounters(diagTicks, diagCatalogCycles, diagDisplayCycles,
                diagRebuildAttempts, diagRebuildSuccess, diagRebuildPublished);
    }

    String diagnosticSummaryLine() {
        return RankingDiagnostics.rankingSummaryLine(epoch, diagTicks, diagCatalogCycles, diagDisplayCycles,
                diagRebuildAttempts, diagRebuildSuccess, diagRebuildPublished, rev, catalogRevision);
    }

    /** Resumen único a INFO; lo invoca el wiring al parar el servidor. */
    void logDiagnosticSummary() {
        RankingDiagnostics.logSummary(diagnosticSummaryLine());
    }

    record DiagnosticCounters(long ticks, long catalogCycles, long displayCycles,
                              long rebuildAttempts, long rebuildSuccess, long rebuildPublished) {
    }
}
