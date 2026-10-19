package dev.pepsi.questrank.net;

import io.netty.buffer.ByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;

/**
 * Respuesta directa «sin cambios» (contratos §9.2 y §9.9.B4).
 *
 * <p>Transporta el eco del {@code requestId} y los valores vigentes de {@code epoch} y {@code rev}.
 * La inclusión de epoch y rev completa un esquema insuficientemente especificado en §9.2/§9.3
 * (solo se enumeran los campos de {@code RankingSnapshot}); es un requisito derivado de §9.9.B4,
 * que exige al cliente comparar «el epoch y la rev conocidos» y detectar el caso «epoch distinto
 * del conocido». No se cita como campo literal del contrato y la aclaración normativa se propone
 * por separado (decisión A-5). Un {@code RankingUnchanged} nunca es un push ni adopta, establece o
 * cambia la sesión.
 */
public record RankingUnchangedPayload(long requestId, long epoch, long rev) implements CustomPacketPayload {

    public static final Type<RankingUnchangedPayload> TYPE = new Type<>(QuestRankChannel.UNCHANGED_ID);

    public static final StreamCodec<ByteBuf, RankingUnchangedPayload> CODEC = StreamCodec.composite(
            ByteBufCodecs.VAR_LONG, RankingUnchangedPayload::requestId,
            ByteBufCodecs.VAR_LONG, RankingUnchangedPayload::epoch,
            ByteBufCodecs.VAR_LONG, RankingUnchangedPayload::rev,
            RankingUnchangedPayload::new);

    @Override
    public Type<RankingUnchangedPayload> type() {
        return TYPE;
    }
}
