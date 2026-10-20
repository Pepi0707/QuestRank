package dev.pepsi.questrank.client.net;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.pepsi.questrank.net.RankingSnapshotPayload;
import dev.pepsi.questrank.testutil.DiagnosticsLogCapture;
import dev.pepsi.questrank.testutil.Fixtures;
import java.util.List;
import org.junit.jupiter.api.Test;

class ClientSyncHooksDiagnosticsTest {

    private static final long EPOCH = 7L;

    @Test
    void mapsAppliedIgnoredAndDiscardedSnapshotsToExplicitOutcomes() {
        Fixtures.FakeClock clock = new Fixtures.FakeClock();
        ClientTransport transport = new ClientTransport() {
            @Override
            public void sendRequest(long requestId, Long knownEpoch, long knownRev) {
            }

            @Override
            public void sendSubscribe(boolean subscribed) {
            }
        };
        ClientRankingSession session = new ClientRankingSession(transport, clock);
        session.onConnect();
        session.open();

        try (DiagnosticsLogCapture capture = DiagnosticsLogCapture.start()) {
            ClientSyncHooks.applyWithDiagnostics(session, snapshot(1L, EPOCH, 1L));
            ClientSyncHooks.applyWithDiagnostics(session, snapshot(0L, EPOCH, 1L));
            ClientSyncHooks.applyWithDiagnostics(session, snapshot(0L, EPOCH + 1, 9L));

            List<String> messages = capture.messages();
            assertEquals(3, messages.size());
            assertTrue(messages.get(0).contains("\"event\":\"client.received\""));
            assertTrue(messages.get(0).contains("\"outcome\":\"APPLIED\""));
            assertTrue(messages.get(0).contains("\"applied\":true"));
            assertTrue(messages.get(0).contains("\"known_rev_before\":0"));
            assertTrue(messages.get(0).contains("\"known_rev_after\":1"));
            assertTrue(messages.get(1).contains("\"outcome\":\"IGNORED_REVISION\""));
            assertTrue(messages.get(1).contains("\"applied\":false"));
            assertTrue(messages.get(2).contains("\"outcome\":\"DISCARDED_FOREIGN_EPOCH\""));
            assertTrue(messages.get(2).contains("\"applied\":false"));
        }
    }

    @Test
    void uncorrelatedInitialPushIsNotMarkedAsApplied() {
        Fixtures.FakeClock clock = new Fixtures.FakeClock();
        ClientRankingSession session = new ClientRankingSession(new NoopTransport(), clock);
        session.onConnect();
        session.open();

        try (DiagnosticsLogCapture capture = DiagnosticsLogCapture.start()) {
            ClientSyncHooks.applyWithDiagnostics(session, snapshot(0L, EPOCH, 1L));
            String message = capture.messages().get(0);
            assertTrue(message.contains("\"outcome\":\"DISCARDED_UNKNOWN_EPOCH\""));
            assertTrue(message.contains("\"applied\":false"));
            assertFalse(session.view().hasData());
        }
    }

    private static RankingSnapshotPayload snapshot(long requestId, long epoch, long rev) {
        return new RankingSnapshotPayload(requestId, epoch, rev, 1L, 10L, 1, false, List.of());
    }

    private static final class NoopTransport implements ClientTransport {

        @Override
        public void sendRequest(long requestId, Long knownEpoch, long knownRev) {
        }

        @Override
        public void sendSubscribe(boolean subscribed) {
        }
    }
}
