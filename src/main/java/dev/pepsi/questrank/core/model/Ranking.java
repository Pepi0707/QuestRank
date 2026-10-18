package dev.pepsi.questrank.core.model;

import java.util.List;
import java.util.Objects;

/**
 * Resultado del cálculo del núcleo (contrato §4, §6): total común N y entradas ya ordenadas.
 * La lista es inmutable y su orden es el definido por
 * {@link dev.pepsi.questrank.core.score.RankingComparator} (§6.1–§6.2).
 */
public record Ranking(long total, List<RankingEntry> entries) {

    public Ranking {
        if (total < 0 || total > RankingEntry.MAX_SAFE_TOTAL) {
            throw new IllegalArgumentException(
                    "invalid total: " + total + " (expected 0 <= total <= " + RankingEntry.MAX_SAFE_TOTAL + ")");
        }
        entries = List.copyOf(Objects.requireNonNull(entries, "entries"));
    }
}
