package dev.pepsi.questrank.net;

import io.netty.buffer.ByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;

/**
 * Respuesta directa de error (contratos §9.2 y §9.9.B3).
 *
 * <p>Contiene el eco del {@code requestId} y un código compacto y estable, sin detalles internos
 * (decisión A-6). El cliente finaliza la solicitud y pasa al estado de error con reintento sin
 * ramificar su comportamiento según el código. La caducidad de suscripciones (§9.6) es silenciosa
 * y no genera este mensaje.
 */
public record RankingErrorPayload(long requestId, int code) implements CustomPacketPayload {

    /** Motivo estable: catálogo no disponible (contrato §8.5). */
    public static final int CODE_CATALOG_UNAVAILABLE = 0;

    /** Motivo estable: participantes no disponibles (contrato §8.5). */
    public static final int CODE_PARTICIPANTS_UNAVAILABLE = 1;

    public static final Type<RankingErrorPayload> TYPE = new Type<>(QuestRankChannel.ERROR_ID);

    public static final StreamCodec<ByteBuf, RankingErrorPayload> CODEC = StreamCodec.composite(
            ByteBufCodecs.VAR_LONG, RankingErrorPayload::requestId,
            ByteBufCodecs.VAR_INT, RankingErrorPayload::code,
            RankingErrorPayload::new);

    @Override
    public Type<RankingErrorPayload> type() {
        return TYPE;
    }
}
