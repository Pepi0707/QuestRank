package dev.pepsi.questrank.core;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.pepsi.questrank.core.catalog.CatalogEntry;
import dev.pepsi.questrank.core.catalog.ComputableUniverse;
import dev.pepsi.questrank.core.catalog.RankingPolicy;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;

/**
 * Reglas del universo computable (contrato §4.1–§4.5): exclusiones, política por defecto y
 * personalizada, catálogo vacío y unicidad de identificadores de misión.
 */
class ComputableUniverseTest {

    private static CatalogEntry quest(long id, long chapter, boolean alwaysInvisible, String... tags) {
        return new CatalogEntry(id, chapter, alwaysInvisible, Set.of(tags));
    }

    @Test
    void defaultPolicyUsesContractTag() {
        assertEquals(Set.of("questrank:excluded"), RankingPolicy.defaultPolicy().excludedTags());
    }

    @Test
    void excludesAlwaysInvisibleChapters() {
        ComputableUniverse universe = ComputableUniverse.compute(RankingPolicy.defaultPolicy(), List.of(
                quest(1, 10, true),
                quest(2, 10, false)
        ));
        assertEquals(1, universe.size());
        assertFalse(universe.contains(1));
        assertTrue(universe.contains(2));
    }

    @Test
    void excludesTaggedQuests() {
        ComputableUniverse universe = ComputableUniverse.compute(RankingPolicy.defaultPolicy(), List.of(
                quest(1, 10, false, "questrank:excluded"),
                quest(2, 10, false, "other:tag"),
                quest(3, 10, false, "questrank:excluded", "other:tag")
        ));
        assertEquals(1, universe.size());
        assertTrue(universe.contains(2));
    }

    @Test
    void customPolicyTagsExclude() {
        RankingPolicy policy = new RankingPolicy(Set.of("a", "b"));
        ComputableUniverse universe = ComputableUniverse.compute(policy, List.of(
                quest(1, 10, false, "a"),
                quest(2, 10, false, "b"),
                quest(3, 10, false, "c"),
                quest(4, 10, false)
        ));
        assertEquals(2, universe.size());
        assertTrue(universe.contains(3));
        assertTrue(universe.contains(4));
    }

    @Test
    void inclusionDoesNotDependOnOptionalOrRepeatableMarks() {
        // §4.2/§4.3: no existen banderas ni interruptores; el universo solo mira capítulo y etiquetas.
        ComputableUniverse universe = ComputableUniverse.compute(RankingPolicy.defaultPolicy(), List.of(
                quest(1, 10, false, "optional"),
                quest(2, 10, false, "repeatable"),
                quest(3, 10, false, "optional", "repeatable", "hidden")
        ));
        assertEquals(3, universe.size());
    }

    @Test
    void emptyCatalogGivesEmptyUniverse() {
        ComputableUniverse universe = ComputableUniverse.compute(RankingPolicy.defaultPolicy(), List.of());
        assertEquals(0, universe.size());
        assertFalse(universe.contains(1));
    }

    @Test
    void duplicateQuestIdsRejected() {
        List<CatalogEntry> quests = List.of(
                quest(1, 10, false),
                quest(1, 11, false)
        );
        assertThrows(IllegalArgumentException.class,
                () -> ComputableUniverse.compute(RankingPolicy.defaultPolicy(), quests));
    }
}
