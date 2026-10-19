package dev.pepsi.questrank.net;

import dev.pepsi.questrank.server.net.ServerSyncHooks;
import net.neoforged.neoforge.network.event.RegisterPayloadHandlersEvent;
import net.neoforged.neoforge.network.registration.PayloadRegistrar;

/**
 * Registro del canal de sincronización en el bus de mods (contrato §9.7: canal versionado «1»).
 *
 * <p>Los handlers C2S reencaminan al servicio de servidor; los S2C entregan al
 * {@link ClientReceiverHook} común, de modo que el código común no referencia clases de cliente
 * (§11.6). El glue del servidor vive en {@link dev.pepsi.questrank.server.net.ServerSyncHooks}.
 */
public final class QuestRankNetworking {

    private QuestRankNetworking() {
    }

    /** Invocado desde el constructor del mod con el bus de mods. */
    public static void register(RegisterPayloadHandlersEvent event) {
        PayloadRegistrar registrar = event.registrar(QuestRankChannel.VERSION);

        registrar.playToServer(RankingRequestPayload.TYPE, RankingRequestPayload.CODEC,
                (payload, context) -> context.enqueueWork(() -> ServerSyncHooks.handleRequest(context, payload)));
        registrar.playToServer(RankingSubscribePayload.TYPE, RankingSubscribePayload.CODEC,
                (payload, context) -> context.enqueueWork(() -> ServerSyncHooks.handleSubscribe(context, payload)));

        registrar.playToClient(RankingSnapshotPayload.TYPE, RankingSnapshotPayload.CODEC,
                (payload, context) -> context.enqueueWork(() -> ClientReceiverHook.receiveSnapshot(payload)));
        registrar.playToClient(RankingUnchangedPayload.TYPE, RankingUnchangedPayload.CODEC,
                (payload, context) -> context.enqueueWork(() -> ClientReceiverHook.receiveUnchanged(payload)));
        registrar.playToClient(RankingErrorPayload.TYPE, RankingErrorPayload.CODEC,
                (payload, context) -> context.enqueueWork(() -> ClientReceiverHook.receiveError(payload)));
    }
}
