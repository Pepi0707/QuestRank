package dev.pepsi.questrank.server.net;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.pepsi.questrank.net.RankingErrorPayload;
import dev.pepsi.questrank.net.RankingRequestPayload;
import dev.pepsi.questrank.net.RankingSnapshotPayload;
import dev.pepsi.questrank.net.RankingUnchangedPayload;
import dev.pepsi.questrank.server.RankingService;
import dev.pepsi.questrank.testutil.DiagnosticsLogCapture;
import dev.pepsi.questrank.testutil.Fixtures;
import java.lang.reflect.Field;
import java.util.Optional;
import java.util.UUID;
import net.neoforged.neoforge.event.server.ServerStoppingEvent;
import org.junit.jupiter.api.Test;

class ServerSyncHooksDiagnosticsTest {

    private static final UUID PLAYER = new UUID(1, 1);

    @Test
    void replacementDetachesPreviousSyncAndKeepsSummaryWithCounters() {
        Fixtures.FakeCatalog catalog = new Fixtures.FakeCatalog();
        Fixtures.FakeParticipants participants = new Fixtures.FakeParticipants();
        Fixtures.FakePolicy policy = new Fixtures.FakePolicy();
        catalog.value = Optional.of(Fixtures.catalog(1L, 1L, 2L, 3L));
        participants.value = Optional.of(Fixtures.defaultParticipants());

        RankingService ranking = new RankingService(7L, catalog, participants, policy);
        ServerSyncService previous = new ServerSyncService(ranking, new NoopTransport(), new Fixtures.FakeClock());
        previous.handleRequest(PLAYER, RankingRequestPayload.of(1L, null, 0L));
        assertEquals(1L, previous.diagnosticCounters().directSnapshots());

        RankingService replacementRanking = new RankingService(8L, catalog, participants, policy);
        try (DiagnosticsLogCapture capture = DiagnosticsLogCapture.start()) {
            ServerSyncService replacement = ServerSyncHooks.attachOrReplace(
                    previous, null, null, null, replacementRanking);
            assertNotSame(previous, replacement);

            String summary = capture.messages().stream()
                    .filter(message -> message.contains("\"event\":\"summary.sync\""))
                    .findFirst()
                    .orElseThrow();
            assertTrue(summary.contains("\"direct_snapshots\":1"),
                    "el resumen conserva la actividad anterior");
            assertTrue(summary.contains("\"send_semantics\":\"attempted\""));
        }

        assertEquals(0L, previous.diagnosticCounters().directSnapshots());
        assertEquals(0L, previous.diagnosticCounters().unchanged());
        assertEquals(0L, previous.diagnosticCounters().errors());
        assertEquals(0L, previous.diagnosticCounters().pushesSent());
    }

    @Test
    void stoppingHookEmitsSyncSummary() throws Exception {
        Fixtures.FakeCatalog catalog = new Fixtures.FakeCatalog();
        Fixtures.FakeParticipants participants = new Fixtures.FakeParticipants();
        Fixtures.FakePolicy policy = new Fixtures.FakePolicy();
        catalog.value = Optional.of(Fixtures.catalog(1L, 1L, 2L, 3L));
        participants.value = Optional.of(Fixtures.defaultParticipants());

        RankingService ranking = new RankingService(7L, catalog, participants, policy);
        ServerSyncService sync = new ServerSyncService(ranking, new NoopTransport(), new Fixtures.FakeClock());
        sync.handleRequest(PLAYER, RankingRequestPayload.of(1L, null, 0L));

        Field syncField = ServerSyncHooks.class.getDeclaredField("sync");
        Field serverField = ServerSyncHooks.class.getDeclaredField("boundServer");
        syncField.setAccessible(true);
        serverField.setAccessible(true);
        syncField.set(null, sync);
        serverField.set(null, null);
        try (DiagnosticsLogCapture capture = DiagnosticsLogCapture.start()) {
            var method = ServerSyncHooks.class.getDeclaredMethod("onServerStopping", ServerStoppingEvent.class);
            method.setAccessible(true);
            method.invoke(null, new ServerStoppingEvent(null));

            String summary = capture.messages().stream()
                    .filter(message -> message.contains("\"event\":\"summary.sync\""))
                    .findFirst()
                    .orElseThrow();
            assertTrue(summary.contains("\"direct_snapshots\":1"));
        } finally {
            syncField.set(null, null);
            serverField.set(null, null);
        }
        assertEquals(0L, sync.diagnosticCounters().directSnapshots());
    }

    private static final class NoopTransport implements SyncTransport {

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
