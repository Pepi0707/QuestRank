package dev.pepsi.questrank.net;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.pepsi.questrank.client.net.ClientRankingSession;
import dev.pepsi.questrank.client.net.ClientSessionView;
import dev.pepsi.questrank.client.net.ClientTransport;
import dev.pepsi.questrank.server.RankingService;
import dev.pepsi.questrank.server.net.ServerSyncService;
import dev.pepsi.questrank.server.net.SyncTransport;
import dev.pepsi.questrank.testutil.Fixtures;
import io.netty.buffer.Unpooled;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import org.junit.jupiter.api.Test;

/**
 * Bucle de integración a nivel de protocolo: autómata de cliente ↔ servicio de servidor con los
 * códecs reales de producción (sin red de Minecraft). Cubre establecimiento, push por cambio,
 * adopción tras reinicio de servidor, límite de peticiones y suscripción/keep-alive.
 */
class SyncLoopbackTest {

    private static final long EPOCH = 11L;
    private static final UUID PLAYER = new UUID(5, 5);

    private final Fixtures.FakeClock clock = new Fixtures.FakeClock();
    private final Fixtures.FakeCatalog catalog = new Fixtures.FakeCatalog();
    private final Fixtures.FakeParticipants participants = new Fixtures.FakeParticipants();
    private final Fixtures.FakePolicy policy = new Fixtures.FakePolicy();
    private final List<Long> sentRequestIds = new ArrayList<>();
    private ServerSyncService activeServer;
    private RankingService ranking;
    private ClientRankingSession client;

    @Test
    void initialSessionThroughRealCodecs() {
        wire();
        client.onConnect();
        client.open();
        ClientSessionView view = client.view();
        assertTrue(view.sessionValid());
        assertEquals(Long.valueOf(EPOCH), view.knownEpoch());
        assertEquals(1L, view.knownRev());
        assertEquals(2, view.data().participantCount());
        assertEquals(3L, view.data().total());
    }

    @Test
    void pushAfterServerChangeApplies() {
        wire();
        client.onConnect();
        client.open();
        changeParticipantsToThree();
        ranking.onProgressChanged();
        ranking.requestState(null, 0L);
        assertEquals(2L, client.view().knownRev());
        assertEquals(3, client.view().data().participantCount());
    }

    @Test
    void serverRestartAdoptsNewEpochThroughCorrelatedResponse() {
        wire();
        client.onConnect();
        client.open();

        Fixtures.FakeCatalog catalog2 = new Fixtures.FakeCatalog();
        Fixtures.FakeParticipants participants2 = new Fixtures.FakeParticipants();
        Fixtures.FakePolicy policy2 = new Fixtures.FakePolicy();
        catalog2.value = Optional.of(Fixtures.catalog(1L, 1L, 2L, 3L));
        participants2.value = Optional.of(Fixtures.defaultParticipants());
        RankingService ranking2 = new RankingService(EPOCH + 1, catalog2, participants2, policy2);
        activeServer = serverFor(ranking2);

        client.refresh();
        ClientSessionView view = client.view();
        assertTrue(view.sessionValid());
        assertEquals(Long.valueOf(EPOCH + 1), view.knownEpoch());
        assertEquals(1L, view.knownRev());
    }

    @Test
    void rateLimitDropsFourthRequestAndSessionSurvives() {
        wire();
        client.onConnect();
        client.open();
        client.refresh();
        client.refresh();
        client.refresh();
        assertEquals(List.of(1L, 2L, 3L, 4L), sentRequestIds);
        assertTrue(client.view().sessionValid());
        assertEquals(1L, client.view().knownRev());
        clock.advance(30_000);
        client.onTick();
        assertEquals(4, sentRequestIds.size(), "sesión válida ⇒ la caducidad del id descartado no recupera");
    }

    @Test
    void closeUnsubscribesAndStopsPushes() {
        wire();
        client.onConnect();
        client.open();
        client.close();
        changeParticipantsToThree();
        ranking.onProgressChanged();
        ranking.requestState(null, 0L);
        assertEquals(1L, client.view().knownRev());
        assertEquals(2, client.view().data().participantCount());
    }

    @Test
    void keepAliveKeepsSubscriptionEligibleForPushes() {
        wire();
        client.onConnect();
        client.open();
        clock.advance(60_000);
        client.onTick();
        changeParticipantsToThree();
        ranking.onProgressChanged();
        ranking.requestState(null, 0L);
        assertEquals(2L, client.view().knownRev(), "el push llega pese al tiempo transcurrido (A-1)");
    }

    private void wire() {
        catalog.value = Optional.of(Fixtures.catalog(1L, 1L, 2L, 3L));
        participants.value = Optional.of(Fixtures.defaultParticipants());
        ranking = new RankingService(EPOCH, catalog, participants, policy);
        activeServer = serverFor(ranking);
        client = new ClientRankingSession(new ClientTransport() {
            @Override
            public void sendRequest(long requestId, Long knownEpoch, long knownRev) {
                sentRequestIds.add(requestId);
                RankingRequestPayload payload = RankingRequestPayload.of(requestId, knownEpoch, knownRev);
                activeServer.handleRequest(PLAYER, roundTrip(RankingRequestPayload.CODEC, payload));
            }

            @Override
            public void sendSubscribe(boolean subscribed) {
                activeServer.handleSubscribe(PLAYER,
                        roundTrip(RankingSubscribePayload.CODEC, new RankingSubscribePayload(subscribed)));
            }
        }, clock);
    }

    private ServerSyncService serverFor(RankingService service) {
        return new ServerSyncService(service, new SyncTransport() {
            @Override
            public void send(UUID playerId, RankingSnapshotPayload payload) {
                client.onSnapshot(roundTrip(RankingSnapshotPayload.CODEC, payload));
            }

            @Override
            public void send(UUID playerId, RankingUnchangedPayload payload) {
                client.onUnchanged(roundTrip(RankingUnchangedPayload.CODEC, payload));
            }

            @Override
            public void send(UUID playerId, RankingErrorPayload payload) {
                client.onError(roundTrip(RankingErrorPayload.CODEC, payload));
            }
        }, clock);
    }

    private void changeParticipantsToThree() {
        participants.value = Optional.of(List.of(
                Fixtures.participant(1, "a", 1L),
                Fixtures.participant(2, "b", 2L, 3L),
                Fixtures.participant(3, "c", 1L)));
    }

    private static <T> T roundTrip(StreamCodec<? super FriendlyByteBuf, T> codec, T value) {
        FriendlyByteBuf buf = new FriendlyByteBuf(Unpooled.buffer());
        codec.encode(buf, value);
        return codec.decode(buf);
    }
}
