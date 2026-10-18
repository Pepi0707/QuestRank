package dev.pepsi.questrank.core.catalog;

import java.util.Collection;
import java.util.Collections;
import java.util.HashSet;
import java.util.Objects;
import java.util.Set;

/**
 * Universo computable U (contrato §4): el conjunto de misiones puntuables, idéntico para todos
 * los participantes (§4.1). Se excluyen las misiones de capítulos {@code always_invisible}
 * (§4.5) y las etiquetadas por la política (§4.4, §4.2, §4.3). Límite público que valida la
 * unicidad de identificadores de misión (ids duplicados → {@link IllegalArgumentException}).
 */
public final class ComputableUniverse {

    private final Set<Long> questIds;

    private ComputableUniverse(Set<Long> questIds) {
        this.questIds = Set.copyOf(questIds);
    }

    public static ComputableUniverse compute(RankingPolicy policy, Collection<CatalogEntry> quests) {
        Objects.requireNonNull(policy, "policy");
        Objects.requireNonNull(quests, "quests");
        Set<Long> seen = new HashSet<>();
        Set<Long> selected = new HashSet<>();
        for (CatalogEntry quest : quests) {
            Objects.requireNonNull(quest, "quest");
            if (!seen.add(quest.questId())) {
                throw new IllegalArgumentException("duplicate quest id: " + quest.questId());
            }
            if (quest.chapterAlwaysInvisible()) {
                continue;
            }
            if (Collections.disjoint(quest.tags(), policy.excludedTags())) {
                selected.add(quest.questId());
            }
        }
        return new ComputableUniverse(selected);
    }

    /** Tamaño del universo (N). */
    public long size() {
        return questIds.size();
    }

    /** Pertenece al universo computable. */
    public boolean contains(long questId) {
        return questIds.contains(questId);
    }
}
