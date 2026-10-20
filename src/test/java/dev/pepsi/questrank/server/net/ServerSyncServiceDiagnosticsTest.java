package dev.pepsi.questrank.server.net;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.pepsi.questrank.net.RankingErrorPayload;
import dev.pepsi.questrank.net.RankingRequestPayload;
import dev.pepsi.questrank.net.RankingSnapshotPayload;
import dev.pepsi.questrank.net.RankingSubscribePayload;
import dev.pepsi.questrank.net.RankingUnchangedPayload;
import dev.pepsi.questrank.server.RankingService;
import dev.pepsi.questrank.testutil.DiagnosticsLogCapture;
import dev.pepsi.questrank.testutil.Fixtures;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class ServerSyncServiceDiagnosticsTest {

    private static final long EPOCH = 7L;
    private static final UUID PLAYER = new UUID(1, 1);

    @Test
    void directAndPushCountersAreSummarized() {
        Fixtures.FakeCatalog catalog = new Fixtures.FakeCatalog();
        Fixtures.FakeParticipants participants = new Fixtures.FakeParticipants();
        Fixtures.FakePolicy policy = new Fixtures.FakePolicy();
        catalog.value = Optional.of(Fixtures.catalog(1L, 1L, 2L, 3L));
        participants.value = Optional.of(Fixtures.defaultParticipants());

        RankingService ranking = new RankingService(EPOCH, catalog, participants, policy);
        RecordingTransport transport = new RecordingTransport();
        Fixtures.FakeClock clock = new Fixtures.FakeClock();
        ServerSyncService sync = new ServerSyncService(ranking, transport, clock);

        sync.handleRequest(PLAYER, RankingRequestPayload.of(1L, null, 0L));
        sync.handleRequest(PLAYER, RankingRequestPayload.of(2L, EPOCH, 1L));

        ServerSyncService.DiagnosticCounters afterDirect = sync.diagnosticCounters();
        assertEquals(1L, afterDirect.directSnapshots());
        assertEquals(1L, afterDirect.unchanged());
        assertEquals(0L, afterDirect.errors());
        assertEquals(0L, afterDirect.pushesSent());

        sync.handleSubscribe(PLAYER, new RankingSubscribePayload(true));
        participants.value = Optional.of(List.of(
                Fixtures.participant(1, "a", 1L),
                Fixtures.participant(2, "b", 2L, 3L),
                Fixtures.participant(3, "c", 1L)));
        ranking.onProgressChanged();
        ranking.requestState(null, 0L);

        ServerSyncService.DiagnosticCounters afterPush = sync.diagnosticCounters();
        assertEquals(1L, afterPush.pushesSent());

        String summary = sync.diagnosticSummaryLine();
        assertTrue(summary.contains("\"direct_snapshots\":1"));
        assertTrue(summary.contains("\"unchanged\":1"));
        assertTrue(summary.contains("\"pushes_sent\":1"));
        assertTrue(summary.contains("\"size_limit_note\""));

        catalog.value = Optional.empty();
        for (int i = 0; i < RankingService.CATALOG_MEMO_TICKS; i++) {
            ranking.onTick();
        }
        sync.handleRequest(PLAYER, RankingRequestPayload.of(3L, null, 0L));
        assertEquals(1L, sync.diagnosticCounters().errors());
        assertTrue(sync.diagnosticSummaryLine().contains("\"errors\":1"));

        try (DiagnosticsLogCapture capture = DiagnosticsLogCapture.start()) {
            sync.detach();
            String captured = capture.messages().stream()
                    .filter(message -> message.contains("\"event\":\"summary.sync\""))
                    .findFirst()
                    .orElseThrow();
            assertTrue(captured.contains("\"direct_snapshots\":1"));
            assertTrue(captured.contains("\"unchanged\":1"));
            assertTrue(captured.contains("\"errors\":1"));
            assertTrue(captured.contains("\"pushes_sent\":1"));
            assertTrue(captured.contains("\"send_semantics\":\"attempted\""));
        }
        assertEquals(0L, sync.diagnosticCounters().directSnapshots());
        assertEquals(0L, sync.diagnosticCounters().unchanged());
        assertEquals(0L, sync.diagnosticCounters().errors());
        assertEquals(0L, sync.diagnosticCounters().pushesSent());
    }

    private static final class RecordingTransport implements SyncTransport {

        @Override
        public void send(UUID playerId, RankingSnapshotPayload payload) {
        }

        @Override
        public void send(UUID playerId, RankingUnchangedPayload payload) {
        }

        @Override
        public void send(UUID playerId, RankingErrorPayload payload) {
        }
    }
}
