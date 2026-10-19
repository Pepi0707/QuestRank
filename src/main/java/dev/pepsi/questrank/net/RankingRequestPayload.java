package dev.pepsi.questrank.net;

import io.netty.buffer.ByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;

/**
 * Petición de estado C2S (contrato §9.1): {@code (knownEpoch opcional, knownRev, requestId)}.
 *
 * <p>{@code requestId} es un contador monotónico del cliente en 1..2^63−1, único durante toda la
 * vida del proceso, que nunca se reinicia ni se reutiliza (§9.1). El servidor valida el rango de
 * forma estructural (§9.5) y descarta en silencio lo inválido (§9.7).
 */
public record RankingRequestPayload(long requestId, boolean hasKnownEpoch, long knownEpoch, long knownRev)
        implements CustomPacketPayload {

    public static final Type<RankingRequestPayload> TYPE = new Type<>(QuestRankChannel.REQUEST_ID);

    public static final StreamCodec<ByteBuf, RankingRequestPayload> CODEC = StreamCodec.composite(
            ByteBufCodecs.VAR_LONG, RankingRequestPayload::requestId,
            ByteBufCodecs.BOOL, RankingRequestPayload::hasKnownEpoch,
            ByteBufCodecs.VAR_LONG, RankingRequestPayload::knownEpoch,
            ByteBufCodecs.VAR_LONG, RankingRequestPayload::knownRev,
            RankingRequestPayload::new);

    /** Construye una petición normalizando el epoch ausente (no hay caché en el cliente). */
    public static RankingRequestPayload of(long requestId, Long knownEpoch, long knownRev) {
        return new RankingRequestPayload(requestId, knownEpoch != null, knownEpoch != null ? knownEpoch : 0L, knownRev);
    }

    /** Validación estructural del servidor (§9.5): {@code requestId ∈ [1, 2^63−1]} y {@code knownRev >= 0}. */
    public boolean isValid() {
        return requestId >= 1L && knownRev >= 0L;
    }

    /** Epoch conocido como valor opcional; {@code null} si el cliente no tiene caché (§9.1). */
    public Long knownEpochOrNull() {
        return hasKnownEpoch ? knownEpoch : null;
    }

    @Override
    public Type<RankingRequestPayload> type() {
        return TYPE;
    }
}
