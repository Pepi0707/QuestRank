package dev.pepsi.questrank.client.net;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.pepsi.questrank.net.RankingSnapshotPayload;
import dev.pepsi.questrank.net.RankingUnchangedPayload;
import dev.pepsi.questrank.testutil.Fixtures;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * Matriz explícita de aceptación §13.4 C1–C7 sobre el autómata de sesión (F5-A), con reloj y
 * transporte simulados. No sustituye a las pruebas de integración I1–I9 (§13.2), que requieren
 * servidor real y siguen pendientes.
 */
class ClientContract13_4Test {

    private static final long EPOCH = 7L;

    private final RecordingTransport transport = new RecordingTransport();
    private final Fixtures.FakeClock clock = new Fixtures.FakeClock();

    @Test
    void c1_initialSnapshotAndUnchangedOnSecondRequest() {
        ClientRankingSession session = establish();
        RankingSnapshotPayload first = session.view().data();
        assertTrue(session.view().sessionValid());

        session.refresh();
        long id = transport.lastRequestId();
        session.onUnchanged(unchanged(id, EPOCH, 1L));

        assertSame(first, session.view().data());
        assertEquals(1L, session.view().knownRev());
        assertTrue(session.view().sessionValid());
        assertEquals(2, transport.requests.size());
    }

    @Test
    void c2_oldRevisionsDiscardedAndNewEpochRefreshesFully() {
        ClientRankingSession session = establish();
        session.onSnapshot(snapshot(0L, EPOCH, 3L));
        assertEquals(3L, session.view().knownRev());

        session.onSnapshot(snapshot(0L, EPOCH, 2L));
        assertEquals(3L, session.view().knownRev(), "revisión antigua descartada");

        session.refresh();
        long id = transport.lastRequestId();
        RankingSnapshotPayload restarted = snapshot(id, EPOCH + 1, 1L);
        session.onSnapshot(restarted);

        assertEquals(Long.valueOf(EPOCH + 1), session.view().knownEpoch());
        assertEquals(1L, session.view().knownRev());
        assertSame(restarted, session.view().data());
        assertTrue(session.view().sessionValid());
    }

    @Test
    void c3_foreignEpochWithoutCorrelationIsDiscardedAndRecoveryIsRequested() {
        ClientRankingSession session = establish();
        RankingSnapshotPayload cached = session.view().data();

        session.onSnapshot(snapshot(0L, EPOCH + 1, 9L));
        assertFalse(session.view().sessionValid());
        assertTrue(session.view().possiblyStale());
        assertEquals(Long.valueOf(EPOCH), session.view().knownEpoch());
        assertSame(cached, session.view().data(), "sin adopción ni retroceso");

        clock.advance(100L);
        session.onTick();
        assertEquals(2, transport.requests.size(), "push extranjero ⇒ recuperación");

        long recoveryId = transport.lastRequestId();
        session.onSnapshot(snapshot(recoveryId + 1_000L, EPOCH + 2, 9L));
        assertEquals(Long.valueOf(EPOCH), session.view().knownEpoch(), "id desconocido descartado");

        session.onSnapshot(snapshot(recoveryId, EPOCH + 1, 5L));
        assertEquals(Long.valueOf(EPOCH + 1), session.view().knownEpoch());
        RankingSnapshotPayload adopted = session.view().data();

        session.onSnapshot(snapshot(recoveryId, EPOCH + 2, 5L));
        assertEquals(Long.valueOf(EPOCH + 1), session.view().knownEpoch(), "id ya consumido descartado");
        assertSame(adopted, session.view().data());
    }

    @Test
    void c4_correlatedNewEpochReplacesCacheAndConsumesId() {
        ClientRankingSession session = establish();
        RankingSnapshotPayload old = session.view().data();

        session.refresh();
        long id = transport.lastRequestId();
        RankingSnapshotPayload next = snapshot(id, EPOCH + 1, 7L);
        session.onSnapshot(next);

        assertNotSame(old, session.view().data(), "caché anterior descartada");
        assertSame(next, session.view().data());
        assertEquals(Long.valueOf(EPOCH + 1), session.view().knownEpoch());
        assertEquals(7L, session.view().knownRev());
        assertTrue(session.view().sessionValid());
        assertFalse(session.view().possiblyStale());

        session.onSnapshot(snapshot(id, EPOCH + 2, 1L));
        assertEquals(Long.valueOf(EPOCH + 1), session.view().knownEpoch(), "id consumido");
        assertSame(next, session.view().data());
    }

    @Test
    void c5_duplicatesAndDelayedMessagesAreIgnored() {
        ClientRankingSession session = establish();
        RankingSnapshotPayload first = session.view().data();

        session.onSnapshot(snapshot(0L, EPOCH, 1L));
        assertSame(first, session.view().data(), "rev igual no cambia");
        session.onSnapshot(snapshot(0L, EPOCH, 0L));
        assertSame(first, session.view().data(), "rev menor no cambia");
        assertEquals(1L, session.view().knownRev());

        session.refresh();
        long id = transport.lastRequestId();
        RankingSnapshotPayload adopted = snapshot(id, EPOCH + 1, 4L);
        session.onSnapshot(adopted);
        session.onSnapshot(snapshot(id, EPOCH + 2, 4L));

        assertEquals(Long.valueOf(EPOCH + 1), session.view().knownEpoch(),
                "epoch distinto con id consumido descartado");
        assertSame(adopted, session.view().data());
    }

    @Test
    void c6_foreignEpochPushRecoveryIsThrottledToFiveSeconds() {
        ClientRankingSession session = establish();
        session.onSnapshot(snapshot(0L, EPOCH + 1, 9L));

        clock.advance(100L);
        session.onTick();
        assertEquals(2, transport.requests.size(), "recuperación #1 inmediata");
        long recoveryId = transport.lastRequestId();
        session.onUnchanged(unchanged(recoveryId, EPOCH + 1, 1L));
        assertFalse(session.view().sessionValid());

        clock.advance(1_000L);
        session.onTick();
        assertEquals(2, transport.requests.size(), "throttle de 5 s");

        clock.advance(4_000L);
        session.onTick();
        assertEquals(3, transport.requests.size(), "recuperación #2 a los 5 s");
    }

    @Test
    void c7_withoutCacheExpiryLeadsToErrorAndManualRetry() {
        ClientRankingSession session = new ClientRankingSession(transport, clock);
        session.onConnect();
        session.open();
        assertFalse(session.view().hasData());

        clock.advance(30_000L);
        session.onTick();
        assertEquals(2, transport.requests.size(), "primera recuperación automática");

        clock.advance(30_000L);
        session.onTick();
        assertEquals(3, transport.requests.size(), "segunda recuperación automática");
        assertFalse(session.view().error());

        clock.advance(30_000L);
        session.onTick();
        assertTrue(session.view().error(), "dos caducidades automáticas consecutivas ⇒ error");
        assertEquals(3, transport.requests.size());

        session.refresh();
        assertFalse(session.view().error());
        assertEquals(4, transport.requests.size(), "el reintento manual re-arma el ciclo");
    }

    @Test
    void c7_disconnectKeepsCacheStaleAndReconnectRequestsAgain() {
        ClientRankingSession session = establish();
        assertFalse(session.view().possiblyStale());

        session.onDisconnect();
        assertFalse(session.view().connected());
        assertTrue(session.view().hasData());
        assertTrue(session.view().possiblyStale(), "con caché ⇒ posiblemente obsoleta");

        session.onConnect();
        assertTrue(session.view().possiblyStale());
        assertEquals(2, transport.requests.size(), "nuevo ciclo con solicitud");

        long id = transport.lastRequestId();
        session.onSnapshot(snapshot(id, EPOCH, 2L));
        assertFalse(session.view().possiblyStale());
        assertEquals(2L, session.view().knownRev());
    }

    @Test
    void c7_cachedDataSurvivesExpiryMarkedStale() {
        ClientRankingSession session = establish();
        session.onDisconnect();
        session.onConnect(); // id2, sesión no válida, caché marcada
        assertEquals(2, transport.requests.size());
        assertTrue(session.view().possiblyStale());

        clock.advance(30_000L);
        session.onTick(); // caduca id2 ⇒ recuperación id3
        assertEquals(3, transport.requests.size());
        assertTrue(session.view().hasData(), "la caché se conserva");
        assertTrue(session.view().possiblyStale(), "y sigue marcada como posiblemente obsoleta");
        assertFalse(session.view().sessionValid());

        clock.advance(30_000L);
        session.onTick(); // caduca id3 ⇒ recuperación id4
        assertEquals(4, transport.requests.size());

        clock.advance(30_000L);
        session.onTick(); // caduca id4 ⇒ error tras dos automáticas
        assertTrue(session.view().error());
        assertTrue(session.view().hasData(), "con caché, el estado de datos sigue disponible");
        assertTrue(session.view().possiblyStale());
        assertEquals(4, transport.requests.size(), "el error detiene la recuperación automática");
    }

    private ClientRankingSession establish() {
        ClientRankingSession session = new ClientRankingSession(transport, clock);
        session.onConnect();
        session.open();
        session.onSnapshot(snapshot(transport.lastRequestId(), EPOCH, 1L));
        return session;
    }

    private static RankingSnapshotPayload snapshot(long requestId, long epoch, long rev) {
        return new RankingSnapshotPayload(requestId, epoch, rev, 1L, 10L, 1, false, List.of());
    }

    private static RankingUnchangedPayload unchanged(long requestId, long epoch, long rev) {
        return new RankingUnchangedPayload(requestId, epoch, rev);
    }

    private static final class RecordingTransport implements ClientTransport {

        private final List<Long> requests = new ArrayList<>();

        @Override
        public void sendRequest(long requestId, Long knownEpoch, long knownRev) {
            requests.add(requestId);
        }

        @Override
        public void sendSubscribe(boolean subscribed) {
        }

        private long lastRequestId() {
            return requests.get(requests.size() - 1);
        }
    }
}
