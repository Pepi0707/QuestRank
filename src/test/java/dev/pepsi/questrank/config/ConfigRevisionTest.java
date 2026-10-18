package dev.pepsi.questrank.config;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;

/**
 * Pruebas puras de la revisión de configuración (contrato §8.2): determinismo, independencia del
 * orden y de duplicados, sensibilidad a cambios y codificación canónica sin ambigüedades.
 * Las colisiones del hash de 64 bits son inherentes y no se afirman ausentes.
 */
class ConfigRevisionTest {

    @Test
    void stableForSameEffectiveSet() {
        assertEquals(
                ConfigRevision.of(Set.of("questrank:excluded", "foo:bar")),
                ConfigRevision.of(Set.of("questrank:excluded", "foo:bar")));
    }

    @Test
    void independentOfOrderAndDuplicates() {
        long reference = ConfigRevision.of(Set.of("questrank:excluded", "foo:bar"));
        assertEquals(reference, ConfigRevision.of(List.of("foo:bar", "questrank:excluded")));
        assertEquals(reference, ConfigRevision.of(List.of("questrank:excluded", "foo:bar", "foo:bar")));
    }

    @Test
    void sensitiveToChanges() {
        long base = ConfigRevision.of(Set.of("questrank:excluded"));
        assertNotEquals(base, ConfigRevision.of(Set.of()));
        assertNotEquals(base, ConfigRevision.of(Set.of("questrank:excluded", "extra:tag")));
        assertNotEquals(base, ConfigRevision.of(Set.of("questrank:other")));
        assertNotEquals(base, ConfigRevision.of(Set.of("Questrank:excluded")));
    }

    @Test
    void emptySetIsDeterministic() {
        assertEquals(ConfigRevision.of(Set.of()), ConfigRevision.of(List.of()));
    }

    @Test
    void encodingIsUnambiguousAtBoundaries() {
        assertNotEquals(ConfigRevision.of(Set.of("a b")), ConfigRevision.of(Set.of("a", "b")));
        assertNotEquals(ConfigRevision.of(Set.of("ñ")), ConfigRevision.of(Set.of("n")));
        assertNotEquals(ConfigRevision.of(Set.of("")), ConfigRevision.of(Set.of()));
    }

    @Test
    void nullsRejected() {
        assertThrows(NullPointerException.class, () -> ConfigRevision.of(null));
        assertThrows(NullPointerException.class, () -> ConfigRevision.of(Collections.singletonList(null)));
        assertThrows(NullPointerException.class, () -> ConfigRevision.of(Arrays.asList("ok", null)));
    }
}
