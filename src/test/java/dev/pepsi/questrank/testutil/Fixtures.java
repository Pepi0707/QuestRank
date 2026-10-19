package dev.pepsi.questrank.testutil;

import dev.pepsi.questrank.core.catalog.CatalogEntry;
import dev.pepsi.questrank.core.catalog.RankingPolicy;
import dev.pepsi.questrank.core.model.ParticipantData;
import dev.pepsi.questrank.core.model.ParticipantKind;
import dev.pepsi.questrank.integration.ftb.FtbCatalog;
import dev.pepsi.questrank.net.TimeSource;
import dev.pepsi.questrank.server.RankingService;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

/** Dobles y utilidades compartidas por las pruebas de F5-A. */
public final class Fixtures {

    private Fixtures() {
    }

    public static FtbCatalog catalog(long identity, long... questIds) {
        List<CatalogEntry> entries = new ArrayList<>();
        for (long questId : questIds) {
            entries.add(new CatalogEntry(questId, 1L, false, Set.of()));
        }
        return new FtbCatalog(identity, entries);
    }

    public static ParticipantData participant(long uuidLeast, String name, long... completed) {
        Set<Long> completedIds = new HashSet<>();
        for (long questId : completed) {
            completedIds.add(questId);
        }
        return new ParticipantData(new UUID(0, uuidLeast), ParticipantKind.PLAYER, name, 0xFFFFFF, completedIds,
                List.of());
    }

    public static List<ParticipantData> defaultParticipants() {
        return List.of(participant(1, "a", 1L), participant(2, "b", 2L, 3L));
    }

    /** Fuente de catálogo controlable (vacía = no disponible). */
    public static final class FakeCatalog implements RankingService.CatalogSource {

        public Optional<FtbCatalog> value = Optional.empty();

        @Override
        public Optional<FtbCatalog> read() {
            return value;
        }
    }

    /** Fuente de participantes controlable (vacía = no disponible). */
    public static final class FakeParticipants implements RankingService.ParticipantsSource {

        public Optional<List<ParticipantData>> value = Optional.empty();

        @Override
        public Optional<List<ParticipantData>> read() {
            return value;
        }
    }

    /** Política y revisión de configuración controlables. */
    public static final class FakePolicy implements RankingService.PolicySource {

        private RankingPolicy policy = RankingPolicy.defaultPolicy();
        private long configRevision;

        @Override
        public RankingPolicy policy() {
            return policy;
        }

        @Override
        public long configRevision() {
            return configRevision;
        }
    }

    /** Reloj monotónico simulado. */
    public static final class FakeClock implements TimeSource {

        private long now;

        @Override
        public long nowMillis() {
            return now;
        }

        public void advance(long millis) {
            now += millis;
        }
    }
}
