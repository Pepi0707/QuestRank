package dev.pepsi.questrank.core.catalog;

import java.util.Objects;
import java.util.Set;

/**
 * Proyección estática de una misión para el universo computable (contrato §4, §4.8):
 * identidad, capítulo, visibilidad permanente del capítulo y etiquetas.
 *
 * <p>No admite banderas de opcional/repetible: el contrato incluye siempre ambas clases de
 * misión (§4.2, §4.3) y no admite interruptores. Los identificadores no se restringen.
 */
public record CatalogEntry(long questId, long chapterId, boolean chapterAlwaysInvisible, Set<String> tags) {

    public CatalogEntry {
        tags = Set.copyOf(Objects.requireNonNull(tags, "tags"));
    }
}
