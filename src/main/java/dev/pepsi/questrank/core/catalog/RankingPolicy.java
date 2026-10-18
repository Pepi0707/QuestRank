package dev.pepsi.questrank.core.catalog;

import java.util.Objects;
import java.util.Set;

/**
 * Política del universo computable (contrato §4.2–§4.4).
 *
 * <p>La única personalización admitida por el contrato es la etiqueta de exclusión configurable;
 * por defecto {@link #DEFAULT_EXCLUDED_TAG}. No existen interruptores para misiones opcionales
 * ni repetibles.
 */
public record RankingPolicy(Set<String> excludedTags) {

    /** Etiqueta de exclusión por defecto (contrato §4.4). */
    public static final String DEFAULT_EXCLUDED_TAG = "questrank:excluded";

    public RankingPolicy {
        excludedTags = Set.copyOf(Objects.requireNonNull(excludedTags, "excludedTags"));
    }

    /** Política por defecto: excluye únicamente {@link #DEFAULT_EXCLUDED_TAG}. */
    public static RankingPolicy defaultPolicy() {
        return new RankingPolicy(Set.of(DEFAULT_EXCLUDED_TAG));
    }
}
