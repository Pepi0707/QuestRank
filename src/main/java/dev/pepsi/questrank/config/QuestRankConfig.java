package dev.pepsi.questrank.config;

import dev.pepsi.questrank.core.catalog.RankingPolicy;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import net.neoforged.neoforge.common.ModConfigSpec;

/**
 * Configuración del mod (contrato §4.4, §8.2): etiqueta(s) de exclusión del universo computable.
 *
 * <p>Única opción: {@code excluded_tags}, lista de cadenas con predeterminado
 * {@code ["questrank:excluded"]}. La lista vacía es válida (sin exclusiones) y la pertenencia es
 * exacta (comparación literal, sin normalización). El fichero COMMON lo registra
 * {@link dev.pepsi.questrank.QuestRank} en su constructor; este binding es de solo lectura.
 * Cualquier recarga que gestione FML se refleja sin trabajo adicional porque la revisión se deriva
 * en cada consulta (sin contadores ni eventos).
 */
public final class QuestRankConfig {

    /** Valor predeterminado obligatorio (contrato §4.4). */
    public static final String DEFAULT_EXCLUDED_TAG = RankingPolicy.DEFAULT_EXCLUDED_TAG;

    public static final ModConfigSpec SPEC;
    private static final ModConfigSpec.ConfigValue<List<? extends String>> EXCLUDED_TAGS;

    static {
        ModConfigSpec.Builder builder = new ModConfigSpec.Builder();
        EXCLUDED_TAGS = builder
                .comment("Quest tags excluded from the computable universe.",
                        "Exact, case-sensitive membership; the empty list disables exclusions.")
                .defineListAllowEmpty("excluded_tags", List.of(DEFAULT_EXCLUDED_TAG),
                        () -> DEFAULT_EXCLUDED_TAG,
                        entry -> entry instanceof String tag && !tag.isBlank());
        SPEC = builder.build();
    }

    private QuestRankConfig() {
    }

    /** Conjunto efectivo de etiquetas de exclusión, en el orden en que aparecen en el fichero. */
    public static Set<String> excludedTags() {
        List<? extends String> value = EXCLUDED_TAGS.get();
        Set<String> tags = new LinkedHashSet<>();
        for (String tag : value) {
            tags.add(Objects.requireNonNull(tag, "tag"));
        }
        return Collections.unmodifiableSet(tags);
    }

    /** Política del universo computable derivada de la configuración vigente. */
    public static RankingPolicy rankingPolicy() {
        return new RankingPolicy(excludedTags());
    }

    /** Revisión de configuración (§8.2) derivada de las etiquetas vigentes. */
    public static long configRevision() {
        return ConfigRevision.of(excludedTags());
    }
}
