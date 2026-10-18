package dev.pepsi.questrank.core.signature;

import dev.pepsi.questrank.core.catalog.CatalogEntry;
import dev.pepsi.questrank.core.util.Fnv1a64;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;
import java.util.Set;

/**
 * Fingerprint de catálogo {@code F_cat} (contrato §8.2), exclusivamente para invalidación en
 * memoria. El resultado no depende del orden de entrada.
 *
 * <p>Codificación canónica:
 * <pre>
 *   putByte(0x01)                 // prefijo de dominio (catálogo)
 *   putLong(fileIdentity)         // identidad del catálogo vivo (la suministra la integración)
 *   putLong(configRevision)       // revisión de configuración (parámetros que alteran U)
 *   putInt(count)                 // número de misiones
 *   por misión (orden total por questId, chapterId, alwaysInvisible y etiquetas canónicas):
 *     putLong(questId); putLong(chapterId); putBoolean(chapterAlwaysInvisible);
 *     putInt(tagCount); por etiqueta en orden String.compareTo: putString(tag)
 * </pre>
 *
 * <p>La prefijación por longitud y cardinalidad elimina ambigüedades de concatenación; no debe
 * confundirse con las colisiones inherentes del hash de 64 bits, que se aceptan para invalidación.
 */
public final class CatalogFingerprint {

    private static final int DOMAIN_TAG = 0x01;

    private CatalogFingerprint() {
    }

    public static long compute(long fileIdentity, long configRevision, Collection<CatalogEntry> quests) {
        Objects.requireNonNull(quests, "quests");
        List<CatalogEntry> sorted = new ArrayList<>(quests.size());
        for (CatalogEntry quest : quests) {
            sorted.add(Objects.requireNonNull(quest, "quest"));
        }
        sorted.sort(CatalogFingerprint::compareEntries);
        Fnv1a64 hash = new Fnv1a64();
        hash.putByte(DOMAIN_TAG);
        hash.putLong(fileIdentity);
        hash.putLong(configRevision);
        hash.putInt(sorted.size());
        for (CatalogEntry quest : sorted) {
            hash.putLong(quest.questId());
            hash.putLong(quest.chapterId());
            hash.putBoolean(quest.chapterAlwaysInvisible());
            List<String> tags = sortedTags(quest.tags());
            hash.putInt(tags.size());
            for (String tag : tags) {
                hash.putString(tag);
            }
        }
        return hash.value();
    }

    private static int compareEntries(CatalogEntry a, CatalogEntry b) {
        int result = Long.compare(a.questId(), b.questId());
        if (result != 0) {
            return result;
        }
        result = Long.compare(a.chapterId(), b.chapterId());
        if (result != 0) {
            return result;
        }
        result = Boolean.compare(a.chapterAlwaysInvisible(), b.chapterAlwaysInvisible());
        if (result != 0) {
            return result;
        }
        List<String> tagsA = sortedTags(a.tags());
        List<String> tagsB = sortedTags(b.tags());
        int shared = Math.min(tagsA.size(), tagsB.size());
        for (int i = 0; i < shared; i++) {
            result = tagsA.get(i).compareTo(tagsB.get(i));
            if (result != 0) {
                return result;
            }
        }
        return Integer.compare(tagsA.size(), tagsB.size());
    }

    private static List<String> sortedTags(Set<String> tags) {
        List<String> sorted = new ArrayList<>(tags);
        sorted.sort(Comparator.naturalOrder());
        return sorted;
    }
}
