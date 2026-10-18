package dev.pepsi.questrank.core.score;

import dev.pepsi.questrank.core.model.RankingEntry;
import java.util.OptionalInt;

/**
 * Aritmética entera exacta de la puntuación (contrato §6.4, §6.5), sin punto flotante.
 *
 * <p>Dominio válido: {@code 0 <= completed <= total <= MAX_SAFE_TOTAL}. Dentro de él,
 * {@code 200*C + N <= Long.MAX_VALUE} y no hay desbordamiento. Fuera del dominio se lanza
 * {@link IllegalArgumentException}.
 */
public final class ScoreMath {

    private ScoreMath() {
    }

    /**
     * Porcentaje mostrado con redondeo mitad hacia arriba: {@code floor((200*C + N) / (2*N))}
     * (§6.4). Exige {@code total > 0}; para {@code total == 0} la interfaz usa {@link #displayed}.
     */
    public static int percentage(long completed, long total) {
        validate(completed, total);
        if (total == 0) {
            throw new IllegalArgumentException("percentage requires total > 0; use displayed(...) for total == 0");
        }
        return halfUpPercentage(completed, total);
    }

    /**
     * Porcentaje a mostrar: vacío cuando {@code N == 0} (la interfaz muestra «—», §6.5).
     */
    public static OptionalInt displayed(long completed, long total) {
        validate(completed, total);
        return total == 0 ? OptionalInt.empty() : OptionalInt.of(halfUpPercentage(completed, total));
    }

    private static int halfUpPercentage(long completed, long total) {
        return (int) Math.floorDiv(200L * completed + total, 2L * total);
    }

    private static void validate(long completed, long total) {
        if (completed < 0 || total < 0 || completed > total || total > RankingEntry.MAX_SAFE_TOTAL) {
            throw new IllegalArgumentException(
                    "invalid score: completed=" + completed + ", total=" + total
                            + " (expected 0 <= completed <= total <= " + RankingEntry.MAX_SAFE_TOTAL + ")");
        }
    }
}
