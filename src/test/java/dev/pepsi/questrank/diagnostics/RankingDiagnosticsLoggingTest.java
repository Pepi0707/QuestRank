package dev.pepsi.questrank.diagnostics;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.pepsi.questrank.net.RankingSnapshotPayload;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.apache.logging.log4j.Level;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.core.LogEvent;
import org.apache.logging.log4j.core.Logger;
import org.apache.logging.log4j.core.appender.AbstractAppender;
import org.apache.logging.log4j.core.config.Configurator;
import org.apache.logging.log4j.core.layout.PatternLayout;
import org.junit.jupiter.api.Test;

/**
 * Comprueba el camino real de logging de los eventos de diagnóstico y del resumen final. No
 * sustituye la comprobación operativa de rotación de logs de B0.
 */
class RankingDiagnosticsLoggingTest {

    @Test
    void detailedEventsAndSummaryReachTheLoggerAtExpectedLevels() {
        List<LogEvent> events = new ArrayList<>();
        Logger logger = (Logger) LogManager.getLogger("QuestRankDiag");
        Level previous = logger.getLevel();
        logger.setLevel(Level.DEBUG);
        AbstractAppender appender = appender(events);
        logger.addAppender(appender);
        try {
            RankingDiagnostics.catalogValidate(true, true, 2L, 10L, 20L, 30L);
            RankingDiagnostics.rebuild(true, 3, 10L, 4L, 2L, true, 10L, 20L, 30L, "request");
            RankingDiagnostics.requestPipeline("snapshot", 100L);
            RankingDiagnostics.directResponse(7L, 9L, 4L, "snapshot", 15L);
            RankingDiagnostics.push(UUID.fromString("00000000-0000-0000-0000-000000000007"),
                    9L, 4L, 3, 12L);
            RankingDiagnostics.clientReceived(payload(), null, 0L, 9L, 4L,
                    true, "APPLIED", 25L);
            RankingDiagnostics.uiModelBuilt(payload(), 3, 35L);
            RankingDiagnostics.uiFirstPaint(9L, 4L, 3, 2, 40L);
            RankingDiagnostics.logSummary(RankingDiagnostics.rankingSummaryLine(
                    9L, 600L, 5L, 1L, 2L, 2L, 1L, 4L, 2L));

            assertEquals(9, events.size());
            for (int i = 0; i < 8; i++) {
                assertEquals(Level.DEBUG, events.get(i).getLevel(), "evento detallado " + i);
            }
            assertEquals(Level.INFO, events.get(8).getLevel(), "el resumen final es INFO");
            assertTrue(events.get(5).getMessage().getFormattedMessage().contains("\"event\":\"client.received\""));
            assertTrue(events.get(5).getMessage().getFormattedMessage().contains("\"outcome\":\"APPLIED\""));
            assertTrue(events.get(5).getMessage().getFormattedMessage().contains("\"applied\":true"));
            assertTrue(events.get(8).getMessage().getFormattedMessage().contains("\"event\":\"summary.ranking\""));
            assertTrue(events.get(8).getMessage().getFormattedMessage().contains("\"size_limit_note\""));
            assertFalse(events.get(5).getMessage().getFormattedMessage().contains("\"event\":\"client.apply\""));
        } finally {
            logger.removeAppender(appender);
            appender.stop();
            logger.setLevel(previous);
        }
    }

    @Test
    void detailedEventsAreSuppressedWhenDebugIsOff() {
        List<LogEvent> events = new ArrayList<>();
        Logger logger = (Logger) LogManager.getLogger("QuestRankDiag");
        Level previous = logger.getLevel();
        Configurator.setLevel("QuestRankDiag", Level.INFO);
        AbstractAppender appender = appender(events);
        logger.addAppender(appender);
        try {
            assertFalse(RankingDiagnostics.enabled(),
                    "logger level after setting INFO: " + logger.getLevel());
            RankingDiagnostics.catalogValidate(true, false, 1L, 1L, 1L, 1L);
            RankingDiagnostics.requestPipeline("unchanged", 1L);
            RankingDiagnostics.directResponse(1L, 1L, 1L, "unchanged", 1L);
            RankingDiagnostics.push(UUID.fromString("00000000-0000-0000-0000-000000000001"),
                    1L, 1L, 1, 1L);
            RankingDiagnostics.clientReceived(payload(), 1L, 1L, 1L, 1L,
                    true, "IGNORED_REVISION", 1L);
            RankingDiagnostics.uiModelBuilt(payload(), 1, 1L);
            RankingDiagnostics.uiFirstPaint(1L, 1L, 1, 1, 1L);
            assertTrue(events.isEmpty(), "con DEBUG desactivado no se emiten eventos detallados");
        } finally {
            logger.removeAppender(appender);
            appender.stop();
            Configurator.setLevel("QuestRankDiag", previous == null ? Level.DEBUG : previous);
        }
    }

    private static AbstractAppender appender(List<LogEvent> events) {
        AbstractAppender appender = new AbstractAppender("questrank-log-test", null,
                PatternLayout.newBuilder().withPattern("%m%n").build(), false, null) {
            @Override
            public void append(LogEvent event) {
                events.add(event.toImmutable());
            }
        };
        appender.start();
        return appender;
    }

    private static RankingSnapshotPayload payload() {
        return new RankingSnapshotPayload(7L, 9L, 4L, 2L, 10L, 3, false, List.of());
    }
}
