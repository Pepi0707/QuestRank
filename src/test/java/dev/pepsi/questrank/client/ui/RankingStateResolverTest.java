package dev.pepsi.questrank.client.ui;

import static org.junit.jupiter.api.Assertions.assertEquals;

import dev.pepsi.questrank.client.net.ClientSessionView;
import dev.pepsi.questrank.net.RankingSnapshotPayload;
import java.util.List;
import org.junit.jupiter.api.Test;

/** Mapeo estado de sesión → estado visual (§10.5) y §13.4-C7 (sin datos, error con reintento). */
class RankingStateResolverTest {

    @Test
    void cachedDataWinsOverError() {
        ClientSessionView view = view(true, snapshot(3L, 2, false), true, true, false, false);
        assertEquals(RankingUiState.DATA, RankingStateResolver.resolve(view),
                "D5a: con datos utilizables se muestran, aunque haya error");
    }

    @Test
    void errorWithoutDataShowsRetryState() {
        ClientSessionView view = view(true, null, false, false, false, false);
        assertEquals(RankingUiState.ERROR, RankingStateResolver.resolve(view));
    }

    @Test
    void loadingWithoutData() {
        ClientSessionView view = view(false, null, false, false, false, true);
        assertEquals(RankingUiState.LOADING, RankingStateResolver.resolve(view));
    }

    @Test
    void noDataWithoutLoadingNorErrorFallsBackToRetry() {
        ClientSessionView view = view(false, null, false, false, false, false);
        assertEquals(RankingUiState.ERROR, RankingStateResolver.resolve(view),
                "sin datos, error ni carga no hay progreso activo: reintento");
    }

    @Test
    void emptyCatalogWhenTotalZero() {
        ClientSessionView view = view(false, snapshot(0L, 0, false), true, false, false, false);
        assertEquals(RankingUiState.EMPTY_CATALOG, RankingStateResolver.resolve(view));
    }

    @Test
    void emptyListWhenNoEntriesButTotalPositive() {
        ClientSessionView view = view(false, snapshot(10L, 0, false), true, false, false, false);
        assertEquals(RankingUiState.EMPTY_LIST, RankingStateResolver.resolve(view));
    }

    @Test
    void dataStateWithStaleFlagStillShowsData() {
        ClientSessionView view = view(false, snapshot(10L, 2, false), false, true, false, false);
        assertEquals(RankingUiState.DATA, RankingStateResolver.resolve(view));
    }

    private static RankingSnapshotPayload snapshot(long total, int entries, boolean truncated) {
        List<RankingSnapshotPayload.Entry> list = new java.util.ArrayList<>();
        for (int i = 0; i < entries; i++) {
            list.add(new RankingSnapshotPayload.Entry(new java.util.UUID(0, i + 1), 0, "p" + i, 0, 0L, 0, false,
                    List.of()));
        }
        return new RankingSnapshotPayload(0L, 1L, 1L, 1L, total, entries, truncated, list);
    }

    private static ClientSessionView view(boolean error, RankingSnapshotPayload data, boolean sessionValid,
                                          boolean stale, boolean idsExhausted, boolean loading) {
        return new ClientSessionView(true, true, 1L, 1L, data, sessionValid, stale, loading, error, idsExhausted);
    }
}
