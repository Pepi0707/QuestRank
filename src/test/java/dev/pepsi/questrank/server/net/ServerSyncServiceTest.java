package dev.pepsi.questrank.server.net;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.pepsi.questrank.core.model.ParticipantData;
import dev.pepsi.questrank.net.RankingErrorPayload;
import dev.pepsi.questrank.net.RankingRequestPayload;
import dev.pepsi.questrank.net.RankingSnapshotPayload;
import dev.pepsi.questrank.net.RankingSubscribePayload;
import dev.pepsi.questrank.net.RankingUnchangedPayload;
import dev.pepsi.questrank.server.RankingService;
import dev.pepsi.questrank.testutil.Fixtures;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/**
 * Servicio de sincronización del servidor (§9): respuestas con eco, límites, suscripciones,
 * pushes con throttle y caducidad por inactividad. Determinista por reloj simulado.
 */
class ServerSyncServiceTest {

    private static final long EPOCH = 7L;
    private static final UUID PLAYER_A = new UUID(1, 1);
    private static final UUID PLAYER_B = new UUID(1, 2);

    private final Fixtures.FakeCatalog catalog = new Fixtures.FakeCatalog();
    private final Fixtures.FakeParticipants participants = new Fixtures.FakeParticipants();
    private final Fixtures.FakePolicy policy = new Fixtures.FakePolicy();
    private final RecordingTransport transport = new RecordingTransport();
    private final Fixtures.FakeClock clock = new Fixtures.FakeClock();
    private final RankingService ranking = new RankingService(EPOCH, catalog, participants, policy);
    private final ServerSyncService sync = new ServerSyncService(ranking, transport, clock);

    @Test
    void requestReturnsSnapshotWithEchoAndTruncationData() {
        baseSources();
        sync.handleRequest(PLAYER_A, request(9L, null, 0L));
        assertEquals(1, transport.snapshots.size());
        RankingSnapshotPayload snap = transport.snapshots.get(0);
        assertEquals(9L, snap.requestId());
        assertEquals(EPOCH, snap.epoch());
        assertEquals(1L, snap.rev());
        assertEquals(2, snap.participantCount());
        assertEquals(3L, snap.total());
        assertFalse(snap.participantsTruncated());
    }

    @Test
    void unchangedEchoesEpochAndRev() {
        baseSources();
        sync.handleRequest(PLAYER_A, request(1L, null, 0L));
        sync.handleRequest(PLAYER_A, request(2L, EPOCH, 1L));
        assertEquals(1, transport.unchanged.size());
        RankingUnchangedPayload payload = transport.unchanged.get(0);
        assertEquals(2L, payload.requestId());
        assertEquals(EPOCH, payload.epoch());
        assertEquals(1L, payload.rev());
    }

    @Test
    void availabilityErrorsMapToStableCodes() {
        baseSources();
        ranking.requestState(null, 0L);
        catalog.value = Optional.empty();
        expireMemos();
        sync.handleRequest(PLAYER_A, request(1L, null, 0L));
        assertEquals(1, transport.errors.size());
        assertEquals(RankingErrorPayload.CODE_CATALOG_UNAVAILABLE, transport.errors.get(0).code());

        catalog.value = Optional.of(Fixtures.catalog(1L, 1L, 2L, 3L));
        participants.value = Optional.empty();
        sync.handleRequest(PLAYER_A, request(2L, null, 0L));
        assertEquals(2, transport.errors.size());
        assertEquals(RankingErrorPayload.CODE_PARTICIPANTS_UNAVAILABLE, transport.errors.get(1).code());
    }

    @Test
    void rateLimitDropsSilentlyThenRefills() {
        baseSources();
        sync.handleRequest(PLAYER_A, request(1L, null, 0L));
        sync.handleRequest(PLAYER_A, request(2L, null, 0L));
        sync.handleRequest(PLAYER_A, request(3L, null, 0L));
        assertEquals(3, transport.total());
        sync.handleRequest(PLAYER_A, request(4L, null, 0L));
        assertEquals(3, transport.total(), "el exceso se descarta en silencio (A-2)");
        clock.advance(1000);
        sync.handleRequest(PLAYER_A, request(5L, null, 0L));
        assertEquals(4, transport.total());
    }

    @Test
    void invalidRequestsAreIgnoredWithoutConsumingBucket() {
        baseSources();
        for (int i = 0; i < 10; i++) {
            sync.handleRequest(PLAYER_A, request(0L, null, 0L));
            sync.handleRequest(PLAYER_A, request(1L, null, -1L));
        }
        assertEquals(0, transport.total());
        sync.handleRequest(PLAYER_A, request(1L, null, 0L));
        sync.handleRequest(PLAYER_A, request(2L, null, 0L));
        sync.handleRequest(PLAYER_A, request(3L, null, 0L));
        assertEquals(3, transport.total(), "los inválidos no consumen cubo");
    }

    @Test
    void subscribeIsIdempotentAndCounted() {
        assertEquals(0, sync.subscriberCount());
        sync.handleSubscribe(PLAYER_A, subscribe(true));
        assertEquals(1, sync.subscriberCount());
        sync.handleSubscribe(PLAYER_A, subscribe(true));
        assertEquals(1, sync.subscriberCount(), "idempotente (A-10)");
        sync.handleSubscribe(PLAYER_B, subscribe(true));
        assertEquals(2, sync.subscriberCount());
        sync.handleSubscribe(PLAYER_A, subscribe(false));
        assertEquals(1, sync.subscriberCount());
        sync.handleSubscribe(PLAYER_A, subscribe(false));
        assertEquals(1, sync.subscriberCount());
        assertEquals(0, transport.total(), "la suscripción no genera respuestas ni pushes (D4)");
    }

    @Test
    void catalogShrinkRecalculatesDenominator() {
        baseSources();
        sync.handleRequest(PLAYER_A, request(1L, null, 0L));
        assertEquals(3L, transport.snapshots.get(0).total());
        catalog.value = Optional.of(Fixtures.catalog(1L, 1L, 2L));
        expireMemos();
        sync.handleRequest(PLAYER_A, request(2L, EPOCH, 1L));
        assertEquals(2, transport.snapshots.size());
        RankingSnapshotPayload after = transport.snapshots.get(1);
        assertEquals(2L, after.total(), "A3: baja de misión recalcula el denominador");
        assertEquals(2L, after.catalogRevision());
        assertEquals(2L, after.rev());
    }

    @Test
    void sameCountCatalogSwapBumpsRevisions() {
        baseSources();
        sync.handleRequest(PLAYER_A, request(1L, null, 0L));
        catalog.value = Optional.of(Fixtures.catalog(1L, 1L, 2L, 7L));
        expireMemos();
        sync.handleRequest(PLAYER_A, request(2L, EPOCH, 1L));
        assertEquals(2, transport.snapshots.size());
        RankingSnapshotPayload after = transport.snapshots.get(1);
        assertEquals(3L, after.total(), "A4: misma N");
        assertEquals(2L, after.catalogRevision());
        assertEquals(2L, after.rev());
    }

    @Test
    void pushOnlyToSubscribedWithRequestIdZero() {
        baseSources();
        ranking.requestState(null, 0L);
        sync.handleSubscribe(PLAYER_A, subscribe(true));
        setParticipantCount(3);
        publish();
        assertEquals(1, transport.snapshots.size());
        RankingSnapshotPayload push = transport.snapshots.get(0);
        assertEquals(0L, push.requestId());
        assertEquals(2L, push.rev());
        assertEquals(PLAYER_A, transport.snapshotPlayers.get(0));
    }

    @Test
    void pushThrottleCoalescesToLatest() {
        baseSources();
        ranking.requestState(null, 0L);
        sync.handleSubscribe(PLAYER_A, subscribe(true));
        setParticipantCount(3);
        publish();
        assertEquals(1, transport.snapshots.size());

        setParticipantCount(4);
        publish();
        setParticipantCount(5);
        publish();
        assertEquals(1, transport.snapshots.size(), "dentro de la ventana de 2 s no hay pushes");

        clock.advance(2000);
        sync.onTick();
        assertEquals(2, transport.snapshots.size());
        assertEquals(4L, transport.snapshots.get(1).rev(), "se envía el último estado, no los intermedios");
    }

    @Test
    void noPushWithoutChangesEvenAcrossTtlCycles() {
        baseSources();
        ranking.requestState(null, 0L);
        sync.handleSubscribe(PLAYER_A, subscribe(true));
        setParticipantCount(3);
        publish();
        assertEquals(1, transport.snapshots.size());
        for (int i = 0; i < 2000; i++) {
            clock.advance(50);
            ranking.onTick();
            sync.onTick();
        }
        assertEquals(1, transport.snapshots.size(), "sin cambios no hay envío periódico ciego (§12.2)");
        assertEquals(2L, transport.snapshots.get(0).rev());
    }

    @Test
    void requestTriggeredSnapshotPushesToOtherSubscribers() {
        baseSources();
        ranking.requestState(null, 0L);
        sync.handleSubscribe(PLAYER_A, subscribe(true));
        sync.handleSubscribe(PLAYER_B, subscribe(true));
        sync.handleRequest(PLAYER_A, request(1L, null, 0L));
        setParticipantCount(3);
        expireMemos();
        sync.handleRequest(PLAYER_A, request(2L, EPOCH, 1L));

        boolean bPushed = false;
        boolean aDirect = false;
        for (int i = 0; i < transport.snapshots.size(); i++) {
            UUID player = transport.snapshotPlayers.get(i);
            RankingSnapshotPayload payload = transport.snapshots.get(i);
            if (player.equals(PLAYER_B)) {
                bPushed = payload.requestId() == 0L && payload.rev() == 2L;
            }
            if (player.equals(PLAYER_A) && payload.requestId() == 2L) {
                aDirect = payload.rev() == 2L;
            }
        }
        assertTrue(bPushed, "la reconstrucción disparada por una petición difunde push al resto");
        assertTrue(aDirect, "el solicitante recibe la respuesta directa con eco");
    }

    @Test
    void requesterReceivesOnlyItsDirectResponseNotItsOwnPush() {
        baseSources();
        ranking.requestState(null, 0L);
        sync.handleSubscribe(PLAYER_A, subscribe(true));
        sync.handleSubscribe(PLAYER_B, subscribe(true));
        setParticipantCount(3);
        expireMemos();
        sync.handleRequest(PLAYER_A, request(1L, EPOCH, 1L));

        int deliveriesToA = 0;
        int deliveriesToB = 0;
        for (int i = 0; i < transport.snapshots.size(); i++) {
            UUID player = transport.snapshotPlayers.get(i);
            RankingSnapshotPayload payload = transport.snapshots.get(i);
            if (player.equals(PLAYER_A)) {
                deliveriesToA++;
                assertEquals(1L, payload.requestId(), "la única entrega a A debe ser su respuesta directa");
            } else if (player.equals(PLAYER_B)) {
                deliveriesToB++;
                assertEquals(0L, payload.requestId());
            }
        }
        assertEquals(1, deliveriesToA, "H-1: el solicitante no recibe el push de su propia petición");
        assertEquals(1, deliveriesToB, "los demás suscriptores reciben el push");
    }

    @Test
    void keepAliveExtendsSubscriptionBeyondInactivityWindow() {
        baseSources();
        sync.handleSubscribe(PLAYER_A, subscribe(true));
        for (int i = 0; i < 3; i++) {
            clock.advance(60_000);
            sync.handleSubscribe(PLAYER_A, subscribe(true));
            sync.onTick();
            assertEquals(1, sync.subscriberCount(), "el keep-alive renueva la ventana de 120 s (A-1)");
        }
    }

    @Test
    void resubscribeAfterExpiryRestoresPushes() {
        baseSources();
        ranking.requestState(null, 0L);
        sync.handleSubscribe(PLAYER_A, subscribe(true));
        clock.advance(120_000);
        sync.onTick();
        assertEquals(0, sync.subscriberCount(), "caducidad silenciosa");
        sync.handleSubscribe(PLAYER_A, subscribe(true));
        assertEquals(1, sync.subscriberCount(), "la re-suscripción vuelve a contar");
        setParticipantCount(3);
        publish();
        assertEquals(1, transport.snapshots.size(), "la re-suscripción restaura los pushes");
    }

    @Test
    void inactivityExpiryIsSilentAndStopsPushes() {
        baseSources();
        ranking.requestState(null, 0L);
        sync.handleSubscribe(PLAYER_A, subscribe(true));
        clock.advance(119_000);
        sync.onTick();
        assertEquals(1, sync.subscriberCount());
        clock.advance(1_000);
        sync.onTick();
        assertEquals(0, sync.subscriberCount(), "caducidad silenciosa (§9.6)");
        setParticipantCount(3);
        publish();
        assertTrue(transport.snapshots.isEmpty(), "sin suscripción no hay pushes");
    }

    @Test
    void resubscribeRefreshesActivityWindow() {
        baseSources();
        sync.handleSubscribe(PLAYER_A, subscribe(true));
        clock.advance(119_000);
        sync.handleSubscribe(PLAYER_A, subscribe(true));
        clock.advance(119_000);
        sync.onTick();
        assertEquals(1, sync.subscriberCount());
        clock.advance(1_000);
        sync.onTick();
        assertEquals(0, sync.subscriberCount());
    }

    @Test
    void logoutDropsStateAndStopsPushes() {
        baseSources();
        ranking.requestState(null, 0L);
        sync.handleSubscribe(PLAYER_A, subscribe(true));
        assertEquals(1, sync.subscriberCount());
        sync.onPlayerLogout(PLAYER_A);
        assertEquals(0, sync.subscriberCount());
        setParticipantCount(3);
        publish();
        assertTrue(transport.snapshots.isEmpty());
    }

    @Test
    void detachClearsSubscribers() {
        baseSources();
        sync.handleSubscribe(PLAYER_A, subscribe(true));
        sync.detach();
        assertEquals(0, sync.subscriberCount());
    }

    @Test
    void freshServiceAfterRestartStartsWithEmptyState() {
        baseSources();
        ranking.requestState(null, 0L);
        sync.handleSubscribe(PLAYER_A, subscribe(true));

        Fixtures.FakeCatalog catalog2 = new Fixtures.FakeCatalog();
        Fixtures.FakeParticipants participants2 = new Fixtures.FakeParticipants();
        Fixtures.FakePolicy policy2 = new Fixtures.FakePolicy();
        catalog2.value = Optional.of(Fixtures.catalog(1L, 1L, 2L, 3L));
        participants2.value = Optional.of(Fixtures.defaultParticipants());
        RankingService ranking2 = new RankingService(EPOCH + 1, catalog2, participants2, policy2);
        RecordingTransport transport2 = new RecordingTransport();
        ServerSyncService sync2 = new ServerSyncService(ranking2, transport2, clock);
        sync2.handleSubscribe(PLAYER_A, subscribe(true));
        ranking2.requestState(null, 0L);

        assertEquals(1, transport2.snapshots.size());
        assertEquals(EPOCH + 1, transport2.snapshots.get(0).epoch());
        assertEquals(1L, transport2.snapshots.get(0).rev());
        assertTrue(transport.snapshots.isEmpty(), "el servicio anterior no envía nada tras el reinicio");
    }

    private void baseSources() {
        catalog.value = Optional.of(Fixtures.catalog(1L, 1L, 2L, 3L));
        participants.value = Optional.of(Fixtures.defaultParticipants());
    }

    private void setParticipantCount(int count) {
        List<ParticipantData> list = new ArrayList<>();
        for (int i = 1; i <= count; i++) {
            list.add(Fixtures.participant(i, "p" + i, 1L));
        }
        participants.value = Optional.of(list);
    }

    private void publish() {
        ranking.onProgressChanged();
        ranking.requestState(null, 0L);
    }

    private void expireMemos() {
        for (int i = 0; i < RankingService.CATALOG_MEMO_TICKS; i++) {
            ranking.onTick();
        }
    }

    private static RankingRequestPayload request(long id, Long epoch, long rev) {
        return RankingRequestPayload.of(id, epoch, rev);
    }

    private static RankingSubscribePayload subscribe(boolean value) {
        return new RankingSubscribePayload(value);
    }

    private static final class RecordingTransport implements SyncTransport {

        private final List<RankingSnapshotPayload> snapshots = new ArrayList<>();
        private final List<UUID> snapshotPlayers = new ArrayList<>();
        private final List<RankingUnchangedPayload> unchanged = new ArrayList<>();
        private final List<RankingErrorPayload> errors = new ArrayList<>();

        int total() {
            return snapshots.size() + unchanged.size() + errors.size();
        }

        @Override
        public void send(UUID playerId, RankingSnapshotPayload payload) {
            snapshots.add(payload);
            snapshotPlayers.add(playerId);
        }

        @Override
        public void send(UUID playerId, RankingUnchangedPayload payload) {
            unchanged.add(payload);
        }

        @Override
        public void send(UUID playerId, RankingErrorPayload payload) {
            errors.add(payload);
        }
    }
}
