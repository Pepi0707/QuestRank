package dev.pepsi.questrank.net;

import io.netty.buffer.ByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;

/**
 * Suscripción C2S (contrato §9.1): activa o desactiva los pushes para el jugador.
 *
 * <p>Es idempotente y no ejecuta el pipeline de §8.10 (no consume el cubo de peticiones de §9.6;
 * decisión A-10). Una suscripción por sí sola no establece sesión ni autoriza adopción de epoch
 * (§9.9.D4). Mientras la pantalla está abierta, el cliente la reafirma cada ≤60 s para evitar la
 * caducidad por inactividad (decisión de diseño A-1, no obligación literal del contrato).
 */
public record RankingSubscribePayload(boolean subscribed) implements CustomPacketPayload {

    public static final Type<RankingSubscribePayload> TYPE = new Type<>(QuestRankChannel.SUBSCRIBE_ID);

    public static final StreamCodec<ByteBuf, RankingSubscribePayload> CODEC =
            ByteBufCodecs.BOOL.map(RankingSubscribePayload::new, RankingSubscribePayload::subscribed);

    @Override
    public Type<RankingSubscribePayload> type() {
        return TYPE;
    }
}
