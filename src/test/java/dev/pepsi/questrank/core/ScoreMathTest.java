package dev.pepsi.questrank.core;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.pepsi.questrank.core.model.RankingEntry;
import dev.pepsi.questrank.core.score.ScoreMath;
import java.math.BigDecimal;
import java.math.BigInteger;
import java.math.RoundingMode;
import java.util.OptionalInt;
import org.junit.jupiter.api.Test;

/**
 * U4 (contrato §13.1): redondeo entero exacto de §6.4 y catálogo vacío de §6.5.
 * El dominio se demuestra con pruebas de frontera; {@code BigInteger} se usa solo como oráculo
 * de la frontera del dominio y de los valores dentro de él, nunca en producción.
 */
class ScoreMathTest {

    private static final long MAX = RankingEntry.MAX_SAFE_TOTAL;

    @Test
    void contractExamples() {
        assertEquals(13, ScoreMath.percentage(1, 8));
        assertEquals(50, ScoreMath.percentage(99, 200));
        assertEquals(49, ScoreMath.percentage(99, 201));
    }

    @Test
    void halfUpBoundaries() {
        assertEquals(0, ScoreMath.percentage(0, 100));
        assertEquals(100, ScoreMath.percentage(100, 100));
        assertEquals(50, ScoreMath.percentage(1, 2));
        assertEquals(33, ScoreMath.percentage(1, 3));
        assertEquals(67, ScoreMath.percentage(2, 3));
        assertEquals(50, ScoreMath.percentage(50, 100));
    }

    @Test
    void displayedEmptyWhenTotalZero() {
        assertEquals(OptionalInt.empty(), ScoreMath.displayed(0, 0));
        assertEquals(OptionalInt.of(13), ScoreMath.displayed(1, 8));
        assertEquals(OptionalInt.of(0), ScoreMath.displayed(0, 100));
    }

    @Test
    void domainBoundaries() {
        assertEquals(100, ScoreMath.percentage(MAX, MAX));
        assertEquals(0, ScoreMath.percentage(0, MAX));
        assertEquals(100, ScoreMath.percentage(MAX - 1, MAX));
    }

    @Test
    void invalidScoresRejected() {
        assertThrows(IllegalArgumentException.class, () -> ScoreMath.percentage(-1, 0));
        assertThrows(IllegalArgumentException.class, () -> ScoreMath.percentage(0, -1));
        assertThrows(IllegalArgumentException.class, () -> ScoreMath.percentage(1, 0));
        assertThrows(IllegalArgumentException.class, () -> ScoreMath.percentage(0, MAX + 1));
        assertThrows(IllegalArgumentException.class, () -> ScoreMath.percentage(0, 0));
        assertThrows(IllegalArgumentException.class, () -> ScoreMath.displayed(-1, 1));
        assertThrows(IllegalArgumentException.class, () -> ScoreMath.displayed(2, 1));
    }

    @Test
    void domainCapIsSqrtBoundary() {
        BigInteger max = BigInteger.valueOf(MAX);
        BigInteger next = max.add(BigInteger.ONE);
        assertTrue(max.multiply(max).compareTo(BigInteger.valueOf(Long.MAX_VALUE)) <= 0);
        assertTrue(next.multiply(next).compareTo(BigInteger.valueOf(Long.MAX_VALUE)) > 0);
        assertEquals(3_037_000_499L, MAX);
    }

    @Test
    void exhaustiveSmallRangeMatchesExactHalfUpOracle() {
        for (int total = 1; total <= 200; total++) {
            for (int completed = 0; completed <= total; completed++) {
                int expected = new BigDecimal(100L * completed)
                        .divide(new BigDecimal(total), 0, RoundingMode.HALF_UP)
                        .intValueExact();
                assertEquals(expected, ScoreMath.percentage(completed, total),
                        "C=" + completed + " N=" + total);
            }
        }
    }

    @Test
    void largeTotalsWithoutPrecisionLoss() {
        long total = (1L << 24) + 1;
        assertEquals(0, ScoreMath.percentage(1, total));
        assertEquals(100, ScoreMath.percentage(total, total));
        assertEquals(50, ScoreMath.percentage(total / 2, total));
    }
}
