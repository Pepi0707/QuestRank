package dev.pepsi.questrank.config;

import dev.pepsi.questrank.core.util.Fnv1a64;
import java.util.Collection;
import java.util.Objects;
import java.util.TreeSet;

/**
 * Revisión de configuración (contrato §8.2): fingerprint determinista del conjunto efectivo de
 * etiquetas de exclusión, exclusivamente para invalidación en memoria.
 *
 * <p>Codificación canónica: byte de dominio {@code 0x03}; cardinalidad (32 bits big-endian);
 * por etiqueta, en orden natural de {@link String}: longitud en bytes UTF-8 (32 bits big-endian)
 * seguida de los bytes UTF-8. El valor es el FNV-1a de 64 bits del flujo anterior.
 *
 * <p>Propiedades: conjuntos iguales producen el mismo valor; conjuntos distintos producen valores
 * distintos salvo colisión del hash de 64 bits, inherente y aceptada igual que en §8.2/§8.3.
 * La colección se reduce a su conjunto (los duplicados colapsan) y se ordena canónicamente.
 */
public final class ConfigRevision {

    private static final int DOMAIN_TAG = 0x03;

    private ConfigRevision() {
    }

    /** Fingerprint del conjunto efectivo de etiquetas (independiente del orden y de duplicados). */
    public static long of(Collection<String> excludedTags) {
        Objects.requireNonNull(excludedTags, "excludedTags");
        TreeSet<String> canonical = new TreeSet<>();
        for (String tag : excludedTags) {
            canonical.add(Objects.requireNonNull(tag, "tag"));
        }
        Fnv1a64 hash = new Fnv1a64();
        hash.putByte(DOMAIN_TAG);
        hash.putInt(canonical.size());
        for (String tag : canonical) {
            hash.putString(tag);
        }
        return hash.value();
    }
}
