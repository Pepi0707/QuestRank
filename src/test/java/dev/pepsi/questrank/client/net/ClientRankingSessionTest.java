package dev.pepsi.questrank.client.net;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.pepsi.questrank.net.RankingErrorPayload;
import dev.pepsi.questrank.net.RankingSnapshotPayload;
import dev.pepsi.questrank.net.RankingUnchangedPayload;
import dev.pepsi.questrank.testutil.Fixtures;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * Matriz del autómata de sesión del cliente (contrato §9.9, bloques A/B/C/D) con reloj y
 * transporte simulados: correlación, monotonía, adopciones, recuperación y ciclo de vida.
 */
class ClientRankingSessionTest {

    private static final long EPOCH = 7L;

    private final RecordingClientTransport transport = new RecordingClientTransport();
    private final Fixtures.FakeClock clock = new Fixtures.FakeClock();

    @Test
    void connectWithoutDemandSendsNothing() {
        ClientRankingSession session = new ClientRankingSession(transport, clock);
        session.onConnect();
        session.onTick();
        assertTrue(transport.requests.isEmpty());
        assertTrue(transport.subscribes.isEmpty());
    }

    @Test
    void openRequestsThenSubscribesAndAdoptsInitialSnapshot() {
        ClientRankingSession session = new ClientRankingSession(transport, clock);
        session.onConnect();
        session.open();
        assertEquals(List.of("request:1", "subscribe:true"), transport.order,
                "solicitud antes o junto con la suscripción (D4)");
        assertNull(transport.requests.get(0).knownEpoch());
        assertEquals(ClientRankingSession.SnapshotOutcome.APPLIED,
                session.onSnapshot(snapshot(1L, EPOCH, 1L)));
        ClientSessionView view = session.view();
        assertTrue(view.sessionValid());
        assertTrue(view.hasData());
        assertEquals(Long.valueOf(EPOCH), view.knownEpoch());
        assertEquals(1L, view.knownRev());
        assertFalse(view.possiblyStale());
        assertFalse(view.loading());
    }

    @Test
    void initialAdoptionRequiresCorrelation() {
        ClientRankingSession session = new ClientRankingSession(transport, clock);
        session.onConnect();
        session.open();
        assertEquals(ClientRankingSession.SnapshotOutcome.DISCARDED_UNKNOWN_EPOCH,
                session.onSnapshot(snapshot(0L, EPOCH, 1L)));
        assertFalse(session.view().hasData());
        assertNull(session.view().knownEpoch());
    }

    @Test
    void sameEpochAppliesMonotonicity() {
        ClientRankingSession session = establish(1L);
        assertEquals(ClientRankingSession.SnapshotOutcome.APPLIED,
                session.onSnapshot(snapshot(0L, EPOCH, 3L)));
        assertEquals(3L, session.view().knownRev());
        RankingSnapshotPayload rev3 = session.view().data();
        assertEquals(ClientRankingSession.SnapshotOutcome.IGNORED_REVISION,
                session.onSnapshot(snapshot(0L, EPOCH, 3L)));
        assertSame(rev3, session.view().data(), "rev igual se ignora");
        assertEquals(ClientRankingSession.SnapshotOutcome.IGNORED_REVISION,
                session.onSnapshot(snapshot(0L, EPOCH, 2L)));
        assertSame(rev3, session.view().data(), "rev menor se descarta");
        assertEquals(3L, session.view().knownRev());
    }

    @Test
    void foreignEpochPushIsDiscardedMarksStaleAndRecoversImmediately() {
        ClientRankingSession session = establish(1L);
        assertEquals(ClientRankingSession.SnapshotOutcome.DISCARDED_FOREIGN_EPOCH,
                session.onSnapshot(snapshot(0L, EPOCH + 1, 5L)));
        assertFalse(session.view().sessionValid());
        assertTrue(session.view().possiblyStale());
        assertEquals(Long.valueOf(EPOCH), session.view().knownEpoch());
        clock.advance(100);
        session.onTick();
        assertEquals(2, transport.requests.size(), "primera recuperación inmediata (D3/C2)");
    }

    @Test
    void newEpochAdoptedOnlyWhenCorrelated() {
        ClientRankingSession session = establish(1L);
        session.refresh();
        long id = transport.lastRequestId();
        assertEquals(ClientRankingSession.SnapshotOutcome.DISCARDED_FOREIGN_EPOCH,
                session.onSnapshot(snapshot(0L, EPOCH + 1, 5L)));
        assertEquals(Long.valueOf(EPOCH), session.view().knownEpoch(), "push sin correlación no adopta (A3)");
        assertEquals(ClientRankingSession.SnapshotOutcome.APPLIED,
                session.onSnapshot(snapshot(id, EPOCH + 1, 5L)));
        assertEquals(Long.valueOf(EPOCH + 1), session.view().knownEpoch());
        assertEquals(5L, session.view().knownRev());
        assertTrue(session.view().sessionValid());
        assertFalse(session.view().possiblyStale());
    }

    @Test
    void correlationFinalizesRequestAndArmsRecovery() {
        ClientRankingSession session = new ClientRankingSession(transport, clock);
        session.onConnect();
        session.open();
        session.onUnchanged(unchanged(1L, EPOCH, 1L));
        assertNull(session.view().knownEpoch());
        clock.advance(100);
        session.onTick();
        assertEquals(2, transport.requests.size(), "id finalizado ⇒ la recuperación puede emitirse (B1/D3)");
    }

    @Test
    void validUnchangedFinalizesWithoutStateChange() {
        ClientRankingSession session = establish(1L);
        RankingSnapshotPayload data = session.view().data();
        session.refresh();
        long id = transport.lastRequestId();
        session.onUnchanged(unchanged(id, EPOCH, 1L));
        assertSame(data, session.view().data());
        assertEquals(1L, session.view().knownRev());
        assertTrue(session.view().sessionValid());
        clock.advance(10_000);
        session.onTick();
        assertEquals(2, transport.requests.size(), "sin recuperación con sesión confirmada");
    }

    @Test
    void invalidUnchangedEpochMismatchMarksStaleAndRecovers() {
        ClientRankingSession session = establish(1L);
        session.refresh();
        long id = transport.lastRequestId();
        session.onUnchanged(unchanged(id, EPOCH + 1, 1L));
        assertFalse(session.view().sessionValid());
        assertTrue(session.view().possiblyStale(), "B4: epoch distinto marca la caché como posiblemente obsoleta");
        clock.advance(100);
        session.onTick();
        assertEquals(3, transport.requests.size());
    }

    @Test
    void invalidUnchangedRevMismatchRecoversWithoutStaleMark() {
        ClientRankingSession session = establish(1L);
        session.refresh();
        long id = transport.lastRequestId();
        session.onUnchanged(unchanged(id, EPOCH, 5L));
        assertFalse(session.view().sessionValid());
        assertFalse(session.view().possiblyStale());
        clock.advance(100);
        session.onTick();
        assertEquals(3, transport.requests.size());
    }

    @Test
    void uncorrelatedUnchangedHasNoEffect() {
        ClientRankingSession session = establish(1L);
        session.onUnchanged(unchanged(0L, EPOCH + 1, 9L));
        assertTrue(session.view().sessionValid());
        assertFalse(session.view().possiblyStale());
        clock.advance(10_000);
        session.onTick();
        assertEquals(1, transport.requests.size(), "un Unchanged no correlacionado no dispara recuperación");
    }

    @Test
    void correlatedErrorStopsAutoRecoveryUntilManualRetry() {
        ClientRankingSession session = establish(1L);
        session.refresh();
        long id = transport.lastRequestId();
        session.onError(new RankingErrorPayload(id, RankingErrorPayload.CODE_CATALOG_UNAVAILABLE));
        assertTrue(session.view().error());
        clock.advance(120_000);
        session.onTick();
        assertEquals(2, transport.requests.size(), "B3/D5b: la recuperación automática se detiene en error");
        session.refresh();
        assertFalse(session.view().error());
        assertEquals(3, transport.requests.size(), "el reintento manual re-arma el ciclo");
    }

    @Test
    void automaticRecoveryThrottleIsFiveSeconds() {
        ClientRankingSession session = establish(1L);
        session.onSnapshot(snapshot(0L, EPOCH + 1, 9L));
        clock.advance(100);
        session.onTick();
        assertEquals(2, transport.requests.size(), "recuperación #1 inmediata");
        long recovery = transport.lastRequestId();
        clock.advance(1000);
        session.onUnchanged(unchanged(recovery, EPOCH + 1, 1L));
        clock.advance(1000);
        session.onTick();
        assertEquals(2, transport.requests.size(), "throttle de 5 s (§9.9.D3)");
        clock.advance(4000);
        session.onTick();
        assertEquals(3, transport.requests.size(), "a los 5 s de la anterior se permite la siguiente");
    }

    @Test
    void twoExpiredAutomaticRecoveriesEnterErrorState() {
        ClientRankingSession session = establish(1L);
        session.onSnapshot(snapshot(0L, EPOCH + 1, 9L));
        clock.advance(100);
        session.onTick();
        assertEquals(2, transport.requests.size());
        clock.advance(30_000);
        session.onTick();
        assertEquals(3, transport.requests.size(), "caduca #1 ⇒ fallo 1 y recuperación #2");
        assertFalse(session.view().error());
        clock.advance(30_000);
        session.onTick();
        assertTrue(session.view().error(), "dos caducidades automáticas consecutivas ⇒ error (D5b)");
        clock.advance(120_000);
        session.onTick();
        assertEquals(3, transport.requests.size(), "el estado de error detiene la recuperación automática");
    }

    @Test
    void manualRefreshIsExemptFromRecoveryThrottle() {
        ClientRankingSession session = establish(1L);
        session.onSnapshot(snapshot(0L, EPOCH + 1, 9L));
        clock.advance(100);
        session.onTick();
        clock.advance(500);
        session.refresh();
        assertEquals(3, transport.requests.size(), "la solicitud manual no está sujeta al throttle");
    }

    @Test
    void expiryTakesEffectOnlyWhenNoRequestRemainsInFlight() {
        ClientRankingSession session = new ClientRankingSession(transport, clock);
        session.onConnect();
        session.open();
        clock.advance(10_000);
        session.refresh();
        clock.advance(20_000);
        session.onTick();
        assertEquals(2, transport.requests.size(), "D2: con otra solicitud en curso no se hace nada");
        clock.advance(10_000);
        session.onTick();
        assertEquals(3, transport.requests.size(), "al vaciarse enCurso se emite la recuperación");
    }

    @Test
    void expiryWithAnotherRequestInFlightDoesNotCountTowardsError() {
        ClientRankingSession session = new ClientRankingSession(transport, clock);
        session.onConnect();
        session.open();                       // id1 manual, caduca a los 30 s
        clock.advance(10_000);
        session.refresh();                    // id2 manual, caduca a los 40 s
        clock.advance(21_000);                // t=31 s: id1 caduca, id2 sigue en curso
        session.onTick();
        assertEquals(2, transport.requests.size(), "D2: la caducidad de id1 no surte efecto");
        clock.advance(10_000);                // t=41 s: id2 caduca (manual, no cuenta)
        session.onTick();
        assertEquals(3, transport.requests.size(), "recuperación automática #1");
        clock.advance(30_000);                // t=71 s: caduca #1 ⇒ fallo 1
        session.onTick();
        assertFalse(session.view().error(), "un solo fallo automático; D2 no contó las manuales");
        assertEquals(4, transport.requests.size());
        clock.advance(30_000);                // t=101 s: caduca #2 ⇒ fallo 2
        session.onTick();
        assertTrue(session.view().error());
    }

    @Test
    void uncorrelatedStalePushDoesNotValidateOrClearStaleMark() {
        ClientRankingSession session = establish(1L);
        session.onDisconnect();
        session.onConnect();
        assertTrue(session.view().possiblyStale(), "tras reconectar la caché está marcada (D4/D5a)");
        session.onSnapshot(snapshot(0L, EPOCH, 1L));
        assertFalse(session.view().sessionValid(), "H-2: un push no correlacionado no valida la sesión");
        assertTrue(session.view().possiblyStale(), "H-2: no borra la marca de obsolescencia");
        clock.advance(30_000);
        session.onTick();
        assertEquals(3, transport.requests.size(), "la recuperación sigue armada tras la caducidad");
    }

    @Test
    void uncorrelatedOlderPushDoesNotValidateOrClearStaleMark() {
        ClientRankingSession session = establish(1L);
        session.onDisconnect();
        session.onConnect();
        session.onSnapshot(snapshot(0L, EPOCH, 0L));
        assertFalse(session.view().sessionValid(), "H-2: rev inferior tampoco valida la sesión");
        assertTrue(session.view().possiblyStale(), "H-2: rev inferior no borra la marca");
        assertEquals(1L, session.view().knownRev(), "A2: no retrocede la rev conocida");
    }

    @Test
    void closeCancelsRecoveryAndKeepsInFlightResponses() {
        ClientRankingSession session = establish(1L);
        session.refresh();
        long id = transport.lastRequestId();
        session.close();
        assertEquals(Boolean.FALSE, transport.subscribes.get(transport.subscribes.size() - 1));
        clock.advance(120_000);
        session.onTick();
        assertEquals(2, transport.requests.size(), "sin demanda no hay recuperación");
        session.onSnapshot(snapshot(id, EPOCH, 2L));
        assertEquals(2L, session.view().knownRev(), "las respuestas en vuelo siguen aplicándose (A-7)");
    }

    @Test
    void reopenAfterCloseRequestsAgain() {
        ClientRankingSession session = establish(1L);
        session.close();
        session.open();
        assertEquals(2, transport.requests.size());
        assertEquals("request:2", transport.order.get(transport.order.size() - 2));
        assertEquals("subscribe:true", transport.order.get(transport.order.size() - 1));
    }

    @Test
    void reconnectStartsNewCycleAndKeepsCacheMarkedStaleUntilAdoption() {
        ClientRankingSession session = establish(1L);
        session.refresh();
        session.onDisconnect();
        session.onConnect();
        assertEquals(3, transport.requests.size(), "D4: ciclo nuevo con solicitud");
        assertEquals("request:3", transport.order.get(transport.order.size() - 2));
        assertEquals("subscribe:true", transport.order.get(transport.order.size() - 1));
        assertTrue(session.view().possiblyStale());
        long id = transport.requests.get(2).id();
        session.onSnapshot(snapshot(id, EPOCH, 2L));
        assertFalse(session.view().possiblyStale());
        assertEquals(2L, session.view().knownRev());
    }

    @Test
    void saturationStopsRequestsButKeepsKeepAlive() {
        RequestIdGenerator ids = new RequestIdGenerator(2L);
        ClientRankingSession session = new ClientRankingSession(transport, clock, ids);
        session.onConnect();
        session.open();
        session.onSnapshot(snapshot(1L, EPOCH, 1L));
        session.refresh();
        session.onUnchanged(unchanged(2L, EPOCH + 1, 1L));
        clock.advance(10_000);
        session.onTick();
        assertEquals(2, transport.requests.size(), "agotado: no se emiten nuevas solicitudes (§9.1)");
        assertTrue(session.view().requestIdsExhausted());
        int subscribes = transport.subscribes.size();
        clock.advance(60_000);
        session.onTick();
        assertEquals(subscribes + 1, transport.subscribes.size(),
                "el keep-alive de suscripción sigue (no es una solicitud)");
    }

    @Test
    void loadingStateWhileNoDataAndRequestOrRecoveryWindowActive() {
        ClientRankingSession session = new ClientRankingSession(transport, clock);
        session.onConnect();
        session.open();
        assertTrue(session.view().loading());
        assertFalse(session.view().hasData());
        session.onSnapshot(snapshot(1L, EPOCH, 1L));
        assertFalse(session.view().loading());
    }

    private ClientRankingSession establish(long requestId) {
        ClientRankingSession session = new ClientRankingSession(transport, clock);
        session.onConnect();
        session.open();
        session.onSnapshot(snapshot(requestId, EPOCH, 1L));
        return session;
    }

    private static RankingSnapshotPayload snapshot(long requestId, long epoch, long rev) {
        return new RankingSnapshotPayload(requestId, epoch, rev, 1L, 10L, 1, false, List.of());
    }

    private static RankingUnchangedPayload unchanged(long requestId, long epoch, long rev) {
        return new RankingUnchangedPayload(requestId, epoch, rev);
    }

    private static final class RecordingClientTransport implements ClientTransport {

        private final List<SentRequest> requests = new ArrayList<>();
        private final List<Boolean> subscribes = new ArrayList<>();
        private final List<String> order = new ArrayList<>();

        @Override
        public void sendRequest(long requestId, Long knownEpoch, long knownRev) {
            requests.add(new SentRequest(requestId, knownEpoch, knownRev));
            order.add("request:" + requestId);
        }

        @Override
        public void sendSubscribe(boolean subscribed) {
            subscribes.add(subscribed);
            order.add("subscribe:" + subscribed);
        }

        private long lastRequestId() {
            return requests.get(requests.size() - 1).id();
        }

        private record SentRequest(long id, Long knownEpoch, long knownRev) {
        }
    }
}
