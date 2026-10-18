package dev.pepsi.questrank.core.score;

import dev.pepsi.questrank.core.model.RankingEntry;
import java.util.Comparator;
import java.util.Locale;

/**
 * Orden del ranking (contrato §6.1–§6.3): razón exacta descendente por productos cruzados y
 * desempates deterministas (C descendente; nombre en minúsculas {@code Locale.ROOT}; nombre
 * exacto; UUID).
 *
 * <p>Precondición: identidades (UUID) únicas; el límite público del cálculo
 * ({@link RankingCalculator}) las valida. En esas condiciones es un orden total estricto y no
 * depende del orden de entrada. No usa restas ni punto flotante; los productos están acotados
 * por {@code MAX_SAFE_TOTAL^2 <= Long.MAX_VALUE}.
 */
public final class RankingComparator implements Comparator<RankingEntry> {

    public static final RankingComparator INSTANCE = new RankingComparator();

    private RankingComparator() {
    }

    @Override
    public int compare(RankingEntry a, RankingEntry b) {
        int byRatio = compareRatios(a, b);
        if (byRatio != 0) {
            return byRatio;
        }
        int byCompleted = Long.compare(b.completed(), a.completed());
        if (byCompleted != 0) {
            return byCompleted;
        }
        int byNormalizedName = a.name().toLowerCase(Locale.ROOT).compareTo(b.name().toLowerCase(Locale.ROOT));
        if (byNormalizedName != 0) {
            return byNormalizedName;
        }
        int byExactName = a.name().compareTo(b.name());
        if (byExactName != 0) {
            return byExactName;
        }
        return a.id().compareTo(b.id());
    }

    /**
     * Comparación exacta de razones {@code C/N} en descendente. Un total cero equivale a la razón
     * cero ({@code 0/0 ≡ 0 %}, §6.5).
     */
    private static int compareRatios(RankingEntry a, RankingEntry b) {
        long totalA = a.total() == 0 ? 1L : a.total();
        long completedA = a.total() == 0 ? 0L : a.completed();
        long totalB = b.total() == 0 ? 1L : b.total();
        long completedB = b.total() == 0 ? 0L : b.completed();
        return Long.compare(completedB * totalA, completedA * totalB);
    }
}
