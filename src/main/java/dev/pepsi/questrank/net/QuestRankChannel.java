package dev.pepsi.questrank.net;

import dev.pepsi.questrank.QuestRank;
import java.util.Objects;
import net.minecraft.resources.ResourceLocation;

/**
 * Canal de sincronización del mod (contrato §9) y parámetros del protocolo.
 *
 * <p>Versión del canal «1» (§9.7). Los identificadores de tipo y los topes de §9.4 son estables;
 * los topes son {@code [PARAM]} y solo pueden cambiarse con revisión del propietario.
 */
public final class QuestRankChannel {

    /** Versión del canal registrada ante NeoForge (§9.7). */
    public static final String VERSION = "1";

    /** Identificadores de mensaje (dirección en {@link QuestRankNetworking}). */
    public static final ResourceLocation REQUEST_ID = id("rank_request");

    public static final ResourceLocation SUBSCRIBE_ID = id("rank_subscribe");

    public static final ResourceLocation SNAPSHOT_ID = id("rank_snapshot");

    public static final ResourceLocation UNCHANGED_ID = id("rank_unchanged");

    public static final ResourceLocation ERROR_ID = id("rank_error");

    /** Máximo de participantes enviados por snapshot (contrato §9.4 [PARAM]). */
    public static final int MAX_PARTICIPANTS = 512;

    /** Máximo de miembros detallados por participante (contrato §9.4 [PARAM]). */
    public static final int MAX_MEMBERS = 12;

    /** Longitud máxima del nombre de participante, en unidades UTF-16 (contrato §9.4 [PARAM]). */
    public static final int MAX_PARTICIPANT_NAME = 64;

    /** Longitud máxima del nombre de miembro, en unidades UTF-16 (contrato §9.4 [PARAM]). */
    public static final int MAX_MEMBER_NAME = 16;

    private QuestRankChannel() {
    }

    private static ResourceLocation id(String path) {
        return ResourceLocation.fromNamespaceAndPath(QuestRank.MOD_ID, path);
    }

    /**
     * Trunca un texto a un máximo de unidades UTF-16 sin partir pares surrogados (contrato §9.4:
     * «nombres ≤64 caracteres y ≤16, truncados»). El resultado cumple {@code length() <= maxUnits}
     * y es compatible con los límites de {@code FriendlyByteBuf.writeUtf(String, int)}.
     */
    public static String truncate(String value, int maxUnits) {
        Objects.requireNonNull(value, "value");
        if (maxUnits < 0) {
            throw new IllegalArgumentException("maxUnits must be >= 0: " + maxUnits);
        }
        if (value.length() <= maxUnits) {
            return value;
        }
        int end = maxUnits;
        if (end > 0 && Character.isHighSurrogate(value.charAt(end - 1))
                && end < value.length() && Character.isLowSurrogate(value.charAt(end))) {
            end--;
        }
        return value.substring(0, end);
    }
}
