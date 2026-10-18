package dev.pepsi.questrank.core;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;

import dev.pepsi.questrank.core.catalog.CatalogEntry;
import dev.pepsi.questrank.core.signature.CatalogFingerprint;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Random;
import java.util.Set;
import org.junit.jupiter.api.Test;

/**
 * Fingerprint de catálogo (contrato §8.2): estabilidad, independencia del orden, sensibilidad a
 * cada campo, codificación canónica sin ambigüedades y determinismo en entradas vacías o grandes.
 * Las colisiones del hash de 64 bits son inherentes y no se afirman ausentes.
 */
class CatalogFingerprintTest {

    private static final long FILE = 111L;
    private static final long CONFIG = 1L;

    private static CatalogEntry quest(long id, long chapter, boolean alwaysInvisible, String... tags) {
        return new CatalogEntry(id, chapter, alwaysInvisible, Set.of(tags));
    }

    private static long fingerprint(long fileIdentity, long configRevision, CatalogEntry... quests) {
        return CatalogFingerprint.compute(fileIdentity, configRevision, List.of(quests));
    }

    @Test
    void stableForSameInput() {
        CatalogEntry[] base = {
                quest(1, 2, false, "a", "b"),
                quest(2, 3, true),
                quest(3, 4, false, "x")
        };
        assertEquals(fingerprint(FILE, CONFIG, base), fingerprint(FILE, CONFIG, base));
    }

    @Test
    void independentOfInputOrder() {
        List<CatalogEntry> base = new ArrayList<>(List.of(
                quest(1, 2, false, "a", "b"),
                quest(2, 3, true),
                quest(3, 4, false, "x"),
                quest(4, 5, false, "y", "z")
        ));
        List<CatalogEntry> shuffled = new ArrayList<>(base);
        Collections.shuffle(shuffled, new Random(11L));
        assertEquals(
                CatalogFingerprint.compute(FILE, CONFIG, base),
                CatalogFingerprint.compute(FILE, CONFIG, shuffled));
    }

    @Test
    void sensitiveToFileIdentity() {
        assertNotEquals(fingerprint(1L, CONFIG, quest(1, 2, false)),
                fingerprint(2L, CONFIG, quest(1, 2, false)));
    }

    @Test
    void sensitiveToConfigRevision() {
        assertNotEquals(fingerprint(FILE, 1L, quest(1, 2, false)),
                fingerprint(FILE, 2L, quest(1, 2, false)));
    }

    @Test
    void sensitiveToQuestId() {
        assertNotEquals(fingerprint(FILE, CONFIG, quest(1, 2, false)),
                fingerprint(FILE, CONFIG, quest(9, 2, false)));
    }

    @Test
    void sensitiveToChapterId() {
        assertNotEquals(fingerprint(FILE, CONFIG, quest(1, 2, false)),
                fingerprint(FILE, CONFIG, quest(1, 9, false)));
    }

    @Test
    void sensitiveToAlwaysInvisibleFlag() {
        assertNotEquals(fingerprint(FILE, CONFIG, quest(1, 2, false)),
                fingerprint(FILE, CONFIG, quest(1, 2, true)));
    }

    @Test
    void sensitiveToTagChanges() {
        long base = fingerprint(FILE, CONFIG, quest(1, 2, false, "a"));
        assertNotEquals(base, fingerprint(FILE, CONFIG, quest(1, 2, false, "a", "b")));
        assertNotEquals(base, fingerprint(FILE, CONFIG, quest(1, 2, false, "b")));
        assertNotEquals(base, fingerprint(FILE, CONFIG, quest(1, 2, false)));
    }

    @Test
    void tagOrderDoesNotMatter() {
        assertEquals(
                fingerprint(FILE, CONFIG, quest(1, 2, false, "b", "a", "c")),
                fingerprint(FILE, CONFIG, quest(1, 2, false, "c", "a", "b")));
    }

    @Test
    void lengthPrefixesAvoidConcatenationAmbiguity() {
        assertNotEquals(
                fingerprint(FILE, CONFIG, quest(1, 2, false, "ab")),
                fingerprint(FILE, CONFIG, quest(1, 2, false, "a", "b")));
        assertNotEquals(
                fingerprint(FILE, CONFIG, quest(1, 2, false, "", "a")),
                fingerprint(FILE, CONFIG, quest(1, 2, false, "a")));
        assertNotEquals(
                fingerprint(FILE, CONFIG, quest(1, 2, false)),
                fingerprint(FILE, CONFIG, quest(1, 2, false, "")));
    }

    @Test
    void multibyteAndSignedValuesEncodedDistinctly() {
        assertNotEquals(
                fingerprint(FILE, CONFIG, quest(1, 2, false, "ñ")),
                fingerprint(FILE, CONFIG, quest(1, 2, false, "n")));
        assertNotEquals(
                fingerprint(FILE, CONFIG, quest(-1, 2, false)),
                fingerprint(FILE, CONFIG, quest(1, 2, false)));
        assertNotEquals(
                fingerprint(FILE, CONFIG, quest(1, -2, false)),
                fingerprint(FILE, CONFIG, quest(1, 2, false)));
    }

    @Test
    void emptyCatalogDeterministic() {
        assertEquals(
                CatalogFingerprint.compute(FILE, CONFIG, List.of()),
                CatalogFingerprint.compute(FILE, CONFIG, List.of()));
    }

    @Test
    void largeCatalogDeterministicAndOrderIndependent() {
        List<CatalogEntry> entries = new ArrayList<>();
        for (long id = 1; id <= 1000; id++) {
            entries.add(quest(id, id % 7, id % 11 == 0, "tag-" + (id % 5)));
        }
        List<CatalogEntry> reversed = new ArrayList<>(entries);
        Collections.reverse(reversed);
        long first = CatalogFingerprint.compute(FILE, CONFIG, entries);
        long second = CatalogFingerprint.compute(FILE, CONFIG, entries);
        long reversedHash = CatalogFingerprint.compute(FILE, CONFIG, reversed);
        assertEquals(first, second);
        assertEquals(first, reversedHash);
    }
}
