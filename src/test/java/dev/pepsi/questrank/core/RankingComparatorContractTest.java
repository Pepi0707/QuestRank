package dev.pepsi.questrank.core;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.pepsi.questrank.core.model.ParticipantKind;
import dev.pepsi.questrank.core.model.RankingEntry;
import dev.pepsi.questrank.core.score.RankingComparator;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/**
 * U3 (propiedades, contrato §6.1–§6.2): contrato matemático del comparador sobre vectores
 * generados con semilla fija (antisimetría, orden total, totales cero y fronteras). El orden
 * funcional por la vía pública se cubre en {@link RankingOrderTest}.
 */
class RankingComparatorContractTest {

    private static final long SEED = 7L;
    private static final long MAX = RankingEntry.MAX_SAFE_TOTAL;

    @Test
    void antisymmetric() {
        List<RankingEntry> entries = generated(64);
        for (RankingEntry a : entries) {
            for (RankingEntry b : entries) {
                int forward = RankingComparator.INSTANCE.compare(a, b);
                int backward = RankingComparator.INSTANCE.compare(b, a);
                assertEquals(-Integer.signum(forward), Integer.signum(backward));
            }
        }
    }

    @Test
    void sortedOutputIsStrictTotalOrder() {
        List<RankingEntry> sorted = new ArrayList<>(generated(200));
        sorted.sort(RankingComparator.INSTANCE);
        for (int i = 0; i < sorted.size(); i++) {
            for (int j = i + 1; j < sorted.size(); j++) {
                assertTrue(RankingComparator.INSTANCE.compare(sorted.get(i), sorted.get(j)) < 0);
            }
        }
    }

    @Test
    void zeroTotalMeansZeroRatio() {
        RankingEntry zero = entry(new UUID(0, 1), "a", 0, 0);
        RankingEntry half = entry(new UUID(0, 2), "b", 5, 10);
        assertTrue(RankingComparator.INSTANCE.compare(half, zero) < 0);
        assertTrue(RankingComparator.INSTANCE.compare(zero, half) > 0);
    }

    @Test
    void equalRatiosDifferentTotalsFallToCompletedDescending() {
        RankingEntry oneOfTwo = entry(new UUID(0, 1), "x", 1, 2);
        RankingEntry fiftyOfHundred = entry(new UUID(0, 2), "y", 50, 100);
        assertTrue(RankingComparator.INSTANCE.compare(fiftyOfHundred, oneOfTwo) < 0);
    }

    @Test
    void distinctIdsNeverCompareEqual() {
        RankingEntry a = entry(new UUID(0, 1), "same", 3, 10);
        RankingEntry b = entry(new UUID(0, 2), "same", 3, 10);
        assertNotEquals(0, RankingComparator.INSTANCE.compare(a, b));
    }

    @Test
    void boundaryTotalsDoNotOverflow() {
        RankingEntry maxed = entry(new UUID(0, 1), "max", MAX, MAX);
        RankingEntry zeroOfMax = entry(new UUID(0, 2), "zero", 0, MAX);
        RankingEntry nearlyMax = entry(new UUID(0, 3), "near", MAX - 1, MAX);
        assertTrue(RankingComparator.INSTANCE.compare(maxed, zeroOfMax) < 0);
        assertTrue(RankingComparator.INSTANCE.compare(maxed, nearlyMax) < 0);
        assertTrue(RankingComparator.INSTANCE.compare(nearlyMax, zeroOfMax) < 0);
    }

    private static RankingEntry entry(UUID id, String name, long completed, long total) {
        return new RankingEntry(id, ParticipantKind.PARTY, name, 0x123456, completed, total, List.of());
    }

    private static List<RankingEntry> generated(int count) {
        Random random = new Random(SEED);
        List<RankingEntry> entries = new ArrayList<>(count);
        for (int i = 0; i < count; i++) {
            long total = random.nextInt(3) == 0 ? 0L : 1L + random.nextInt(10_000);
            long completed = total == 0 ? 0L : random.nextLong(total + 1);
            entries.add(entry(new UUID(0, i + 1), "name-" + random.nextInt(5), completed, total));
        }
        return entries;
    }
}
