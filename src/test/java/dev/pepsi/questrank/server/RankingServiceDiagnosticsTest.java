package dev.pepsi.questrank.server;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.pepsi.questrank.net.RankingErrorPayload;
import dev.pepsi.questrank.net.RankingSnapshotPayload;
import dev.pepsi.questrank.net.RankingUnchangedPayload;
import dev.pepsi.questrank.server.net.ServerSyncService;
import dev.pepsi.questrank.server.net.SyncTransport;
import dev.pepsi.questrank.testutil.DiagnosticsLogCapture;
import dev.pepsi.questrank.testutil.Fixtures;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class RankingServiceDiagnosticsTest {

    @Test
    void countersAreScenarioScopedAndSummarized() {
        Fixtures.FakeCatalog catalog = new Fixtures.FakeCatalog();
        Fixtures.FakeParticipants participants = new Fixtures.FakeParticipants();
        Fixtures.FakePolicy policy = new Fixtures.FakePolicy();
        catalog.value = Optional.of(Fixtures.catalog(1L, 1L, 2L, 3L));
        participants.value = Optional.of(Fixtures.defaultParticipants());

        RankingService service = new RankingService(7L, catalog, participants, policy);
        service.requestState(null, 0L);

        RankingService.DiagnosticCounters initial = service.diagnosticCounters();
        assertEquals(0L, initial.ticks());
        assertEquals(0L, initial.catalogCycles());
        assertEquals(0L, initial.displayCycles());
        assertEquals(1L, initial.rebuildAttempts());
        assertEquals(1L, initial.rebuildSuccess());
        assertEquals(1L, initial.rebuildPublished());

        service.setSubscriberCount(1);
        for (int i = 0; i < RankingService.DISPLAY_TTL_TICKS; i++) {
            service.onTick();
        }

        RankingService.DiagnosticCounters afterTtl = service.diagnosticCounters();
        assertEquals(RankingService.DISPLAY_TTL_TICKS, afterTtl.ticks());
        assertEquals(5L, afterTtl.catalogCycles(), "TTL de catálogo cada 100 ticks");
        assertEquals(1L, afterTtl.displayCycles(), "TTL de display a los 600 ticks");
        assertEquals(2L, afterTtl.rebuildAttempts(), "la reconstrucción inicial y la del TTL");
        assertEquals(2L, afterTtl.rebuildSuccess());
        assertEquals(1L, afterTtl.rebuildPublished(), "sin cambio de firma no se publica otra revisión");

        String summary = service.diagnosticSummaryLine();
        assertTrue(summary.contains("\"ticks\":600"));
        assertTrue(summary.contains("\"catalog_cycles\":5"));
        assertTrue(summary.contains("\"display_cycles\":1"));
        assertTrue(summary.contains("\"rebuild_attempts\":2"));
        assertTrue(summary.contains("\"size_limit_note\""));
    }

    @Test
    void rankingSummaryDoesNotDependOnSyncDetachOrder() {
        Fixtures.FakeCatalog catalog = new Fixtures.FakeCatalog();
        Fixtures.FakeParticipants participants = new Fixtures.FakeParticipants();
        Fixtures.FakePolicy policy = new Fixtures.FakePolicy();
        catalog.value = Optional.of(Fixtures.catalog(1L, 1L, 2L, 3L));
        participants.value = Optional.of(Fixtures.defaultParticipants());

        RankingService service = new RankingService(7L, catalog, participants, policy);
        service.requestState(null, 0L);
        ServerSyncService sync = new ServerSyncService(service, new NoopTransport(), new Fixtures.FakeClock());

        String before = withoutTimestamp(service.diagnosticSummaryLine());
        sync.detach();
        String after = withoutTimestamp(service.diagnosticSummaryLine());

        assertEquals(before, after, "el resumen de ranking no depende de la parada de sincronización");
    }

    @Test
    void rankingSummaryIsLoggedAtInfoOnStop() {
        Fixtures.FakeCatalog catalog = new Fixtures.FakeCatalog();
        Fixtures.FakeParticipants participants = new Fixtures.FakeParticipants();
        Fixtures.FakePolicy policy = new Fixtures.FakePolicy();
        catalog.value = Optional.of(Fixtures.catalog(1L, 1L, 2L, 3L));
        participants.value = Optional.of(Fixtures.defaultParticipants());

        RankingService service = new RankingService(7L, catalog, participants, policy);
        service.requestState(null, 0L);
        try (DiagnosticsLogCapture capture = DiagnosticsLogCapture.start()) {
            service.logDiagnosticSummary();
            String summary = capture.messages().stream()
                    .filter(message -> message.contains("\"event\":\"summary.ranking\""))
                    .findFirst()
                    .orElseThrow();
            assertTrue(summary.contains("\"rebuild_attempts\":1"));
            assertTrue(summary.contains("\"rebuild_success\":1"));
            assertTrue(summary.contains("\"rev\":1"));
        }
    }

    @Test
    void failedRebuildCountsAsAttemptButNotAsSuccess() {
        Fixtures.FakeCatalog catalog = new Fixtures.FakeCatalog();
        Fixtures.FakeParticipants participants = new Fixtures.FakeParticipants();
        Fixtures.FakePolicy policy = new Fixtures.FakePolicy();
        catalog.value = Optional.of(Fixtures.catalog(1L, 1L, 2L, 3L));
        participants.value = Optional.of(Fixtures.defaultParticipants());

        RankingService service = new RankingService(7L, catalog, participants, policy);
        service.requestState(null, 0L);
        participants.value = Optional.empty();
        service.setSubscriberCount(1);
        service.onProgressChanged();
        for (int i = 0; i < RankingService.EVENT_DEBOUNCE_TICKS; i++) {
            service.onTick();
        }

        RankingService.DiagnosticCounters counters = service.diagnosticCounters();
        assertEquals(2L, counters.rebuildAttempts());
        assertEquals(1L, counters.rebuildSuccess(), "el rebuild fallido no incrementa el éxito");
        assertEquals(1L, counters.rebuildPublished(), "el rebuild fallido no publica revisión");
    }

    private static String withoutTimestamp(String line) {
        return line.replaceAll(",\"t_wall_ms\":\\d+", "");
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
