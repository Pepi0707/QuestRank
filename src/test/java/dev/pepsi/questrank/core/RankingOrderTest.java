package dev.pepsi.questrank.core;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import dev.pepsi.questrank.core.catalog.CatalogEntry;
import dev.pepsi.questrank.core.catalog.ComputableUniverse;
import dev.pepsi.questrank.core.catalog.RankingPolicy;
import dev.pepsi.questrank.core.model.ParticipantData;
import dev.pepsi.questrank.core.model.ParticipantKind;
import dev.pepsi.questrank.core.model.Ranking;
import dev.pepsi.questrank.core.model.RankingEntry;
import dev.pepsi.questrank.core.score.RankingCalculator;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Random;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/**
 * U3 (contrato §13.1): orden, desempates, N=0 y determinismo por la vía pública del cálculo
 * (§6.1–§6.3, §6.5). Las propiedades matemáticas del comparador se cubren en
 * {@link RankingComparatorContractTest} sin duplicar responsabilidades.
 */
class RankingOrderTest {

    private static final long SEED = 42L;
    private static final int COLOR = 0xFFFFFF;

    @Test
    void ordersByExactRatioDescending() {
        ComputableUniverse universe = universeOf(100);
        Ranking ranking = RankingCalculator.compute(universe, List.of(
                participant(new UUID(0, 1), "A", 5),
                participant(new UUID(0, 2), "B", 10),
                participant(new UUID(0, 3), "C", 2)
        ));
        assertEquals(List.of("B", "A", "C"), names(ranking));
        assertEquals(100, ranking.total());
    }

    @Test
    void tiesFallToNormalizedNameRootLocale() {
        ComputableUniverse universe = universeOf(100);
        Ranking ranking = RankingCalculator.compute(universe, List.of(
                participant(new UUID(0, 1), "Beta", 5),
                participant(new UUID(0, 2), "alpha", 5)
        ));
        assertEquals(List.of("alpha", "Beta"), names(ranking));
    }

    @Test
    void exactNameTieBreakAfterNormalizedTie() {
        ComputableUniverse universe = universeOf(100);
        Ranking ranking = RankingCalculator.compute(universe, List.of(
                participant(new UUID(0, 1), "Team", 5),
                participant(new UUID(0, 2), "TEAM", 5)
        ));
        assertEquals(List.of("TEAM", "Team"), names(ranking));
    }

    @Test
    void uuidTieBreakWhenNamesAndCountsIdentical() {
        ComputableUniverse universe = universeOf(100);
        Ranking ranking = RankingCalculator.compute(universe, List.of(
                participant(new UUID(0, 2), "same", 5),
                participant(new UUID(0, 1), "same", 5)
        ));
        assertEquals(List.of(new UUID(0, 1), new UUID(0, 2)),
                ranking.entries().stream().map(RankingEntry::id).toList());
    }

    @Test
    void emptyUniverseOrdersByTieBreaksAndZeroTotal() {
        ComputableUniverse universe = universeOf(0);
        Ranking ranking = RankingCalculator.compute(universe, List.of(
                participant(new UUID(0, 2), "b", 0),
                participant(new UUID(0, 1), "a", 0)
        ));
        assertEquals(List.of("a", "b"), names(ranking));
        assertEquals(0, ranking.total());
        ranking.entries().forEach(entry -> assertEquals(0, entry.total()));
    }

    @Test
    void noProgressStillRanked() {
        ComputableUniverse universe = universeOf(10);
        Ranking ranking = RankingCalculator.compute(universe, List.of(
                participant(new UUID(0, 2), "zeta", 0),
                participant(new UUID(0, 1), "alfa", 0)
        ));
        assertEquals(List.of("alfa", "zeta"), names(ranking));
        ranking.entries().forEach(entry -> assertEquals(0, entry.completed()));
    }

    @Test
    void completedIdsOutsideUniverseAreIgnored() {
        ComputableUniverse universe = universeOf(2);
        ParticipantData participant = new ParticipantData(
                new UUID(0, 1), ParticipantKind.PLAYER, "p", COLOR, Set.of(1L, 2L, 999L), List.of());
        Ranking ranking = RankingCalculator.compute(universe, List.of(participant));
        assertEquals(1, ranking.entries().size());
        assertEquals(2, ranking.entries().get(0).completed());
        assertEquals(2, ranking.entries().get(0).total());
    }

    @Test
    void shuffledInputProducesIdenticalRanking() {
        ComputableUniverse universe = universeOf(50);
        List<ParticipantData> base = List.of(
                participant(new UUID(0, 1), "uno", 10),
                participant(new UUID(0, 2), "dos", 20),
                participant(new UUID(0, 3), "tres", 20),
                participant(new UUID(0, 4), "cuatro", 5),
                participant(new UUID(0, 5), "cinco", 0),
                participant(new UUID(0, 6), "seis", 50)
        );
        List<ParticipantData> first = new ArrayList<>(base);
        Collections.shuffle(first, new Random(SEED));
        List<ParticipantData> second = new ArrayList<>(base);
        Collections.shuffle(second, new Random(SEED + 1));
        Ranking rankingFirst = RankingCalculator.compute(universe, first);
        Ranking rankingSecond = RankingCalculator.compute(universe, second);
        assertEquals(rankingFirst, rankingSecond);
    }

    @Test
    void duplicateParticipantIdRejected() {
        ComputableUniverse universe = universeOf(10);
        UUID duplicated = new UUID(0, 1);
        List<ParticipantData> participants = List.of(
                participant(duplicated, "a", 1),
                participant(duplicated, "b", 2)
        );
        assertThrows(IllegalArgumentException.class, () -> RankingCalculator.compute(universe, participants));
    }

    @Test
    void commonTotalCoherence() {
        ComputableUniverse universe = universeOf(7);
        Ranking ranking = RankingCalculator.compute(universe, List.of(
                participant(new UUID(0, 1), "x", 3),
                participant(new UUID(0, 2), "y", 7)
        ));
        assertEquals(7, ranking.total());
        ranking.entries().forEach(entry -> assertEquals(ranking.total(), entry.total()));
    }

    private static ComputableUniverse universeOf(int quests) {
        List<CatalogEntry> entries = new ArrayList<>();
        for (long id = 1; id <= quests; id++) {
            entries.add(new CatalogEntry(id, 1L, false, Set.of()));
        }
        return ComputableUniverse.compute(RankingPolicy.defaultPolicy(), entries);
    }

    private static ParticipantData participant(UUID id, String name, int completedCount) {
        Set<Long> completed = new HashSet<>();
        for (long quest = 1; quest <= completedCount; quest++) {
            completed.add(quest);
        }
        return new ParticipantData(id, ParticipantKind.PLAYER, name, COLOR, completed, List.of());
    }

    private static List<String> names(Ranking ranking) {
        return ranking.entries().stream().map(RankingEntry::name).toList();
    }
}
