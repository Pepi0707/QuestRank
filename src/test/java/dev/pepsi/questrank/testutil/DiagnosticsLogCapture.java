package dev.pepsi.questrank.testutil;

import java.util.ArrayList;
import java.util.List;
import org.apache.logging.log4j.Level;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.core.LogEvent;
import org.apache.logging.log4j.core.Logger;
import org.apache.logging.log4j.core.appender.AbstractAppender;
import org.apache.logging.log4j.core.config.Configurator;
import org.apache.logging.log4j.core.layout.PatternLayout;

/**
 * Captura de logs de diagnóstico para pruebas. Fija temporalmente el nivel del logger
 * {@code QuestRankDiag} y restaura el nivel anterior al cerrar.
 */
public final class DiagnosticsLogCapture implements AutoCloseable {

    private final Logger logger;
    private final Level previousLevel;
    private final AbstractAppender appender;
    private final List<LogEvent> events = new ArrayList<>();

    private DiagnosticsLogCapture(Level level) {
        logger = (Logger) LogManager.getLogger("QuestRankDiag");
        previousLevel = logger.getLevel();
        Configurator.setLevel("QuestRankDiag", level);
        appender = new AbstractAppender("questrank-test-" + System.nanoTime(), null,
                PatternLayout.newBuilder().withPattern("%m%n").build(), false, null) {
            @Override
            public void append(LogEvent event) {
                events.add(event.toImmutable());
            }
        };
        appender.start();
        logger.addAppender(appender);
    }

    public static DiagnosticsLogCapture start() {
        return new DiagnosticsLogCapture(Level.DEBUG);
    }

    public static DiagnosticsLogCapture startAt(Level level) {
        return new DiagnosticsLogCapture(level);
    }

    public List<LogEvent> events() {
        return List.copyOf(events);
    }

    public List<String> messages() {
        List<String> messages = new ArrayList<>(events.size());
        for (LogEvent event : events) {
            messages.add(event.getMessage().getFormattedMessage());
        }
        return List.copyOf(messages);
    }

    @Override
    public void close() {
        logger.removeAppender(appender);
        appender.stop();
        Configurator.setLevel("QuestRankDiag", previousLevel == null ? Level.DEBUG : previousLevel);
    }
}
