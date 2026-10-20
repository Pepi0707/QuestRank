package dev.pepsi.questrank.server;

import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.pepsi.questrank.testutil.DiagnosticsLogCapture;
import dev.pepsi.questrank.testutil.Fixtures;
import java.lang.reflect.Field;
import java.util.Optional;
import net.neoforged.neoforge.event.server.ServerStoppingEvent;
import org.junit.jupiter.api.Test;

class ServerRankingHooksDiagnosticsTest {

    @Test
    void stoppingHookLogsRankingSummary() throws Exception {
        Fixtures.FakeCatalog catalog = new Fixtures.FakeCatalog();
        Fixtures.FakeParticipants participants = new Fixtures.FakeParticipants();
        Fixtures.FakePolicy policy = new Fixtures.FakePolicy();
        catalog.value = Optional.of(Fixtures.catalog(1L, 1L, 2L, 3L));
        participants.value = Optional.of(Fixtures.defaultParticipants());
        RankingService service = new RankingService(7L, catalog, participants, policy);
        service.requestState(null, 0L);

        Field serviceField = ServerRankingHooks.class.getDeclaredField("service");
        Field serverField = ServerRankingHooks.class.getDeclaredField("boundServer");
        serviceField.setAccessible(true);
        serverField.setAccessible(true);
        serviceField.set(null, service);
        serverField.set(null, null);

        try (DiagnosticsLogCapture capture = DiagnosticsLogCapture.start()) {
            invokeStoppingHook();
            String summary = capture.messages().stream()
                    .filter(message -> message.contains("\"event\":\"summary.ranking\""))
                    .findFirst()
                    .orElseThrow();
            assertTrue(summary.contains("\"rebuild_attempts\":1"));
            assertTrue(summary.contains("\"rebuild_success\":1"));
            assertTrue(summary.contains("\"rebuild_published\":1"));
            assertTrue(summary.contains("\"rev\":1"));
            assertTrue(summary.contains("\"catalog_revision\":1"));
        } finally {
            serviceField.set(null, null);
            serverField.set(null, null);
        }
    }

    private static void invokeStoppingHook() throws Exception {
        var method = ServerRankingHooks.class.getDeclaredMethod("onServerStopping", ServerStoppingEvent.class);
        method.setAccessible(true);
        method.invoke(null, new ServerStoppingEvent(null));
    }
}
