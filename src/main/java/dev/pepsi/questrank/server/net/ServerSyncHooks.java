package dev.pepsi.questrank.server.net;

import com.mojang.logging.LogUtils;
import dev.pepsi.questrank.net.RankingErrorPayload;
import dev.pepsi.questrank.net.RankingRequestPayload;
import dev.pepsi.questrank.net.RankingSnapshotPayload;
import dev.pepsi.questrank.net.RankingSubscribePayload;
import dev.pepsi.questrank.net.RankingUnchangedPayload;
import dev.pepsi.questrank.net.TimeSource;
import dev.pepsi.questrank.server.RankingService;
import dev.pepsi.questrank.server.ServerRankingHooks;
import java.util.Optional;
import java.util.UUID;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.event.entity.player.PlayerEvent;
import net.neoforged.neoforge.event.server.ServerStoppingEvent;
import net.neoforged.neoforge.event.tick.ServerTickEvent;
import net.neoforged.neoforge.network.PacketDistributor;
import net.neoforged.neoforge.network.handling.IPayloadContext;
import org.slf4j.Logger;

/**
 * Wiring de sincronización del servidor (contrato §9): enlaza el {@link ServerSyncService} con el
 * ciclo del servidor de F4 usando solo su frontera pública (§11.6), reencamina los mensajes C2S
 * recibidos y envía las respuestas y pushes por el sistema de payloads de NeoForge.
 *
 * <p>El enlace es perezoso y por identidad de servidor+servicio: se crea en el primer tick o
 * mensaje tras arrancar F4 y se suelta al parar, de forma que un reinicio no deja estado residual.
 * Todo el flujo ocurre en el hilo del servidor (los handlers hacen {@code enqueueWork}).
 */
public final class ServerSyncHooks {

    private static final Logger LOGGER = LogUtils.getLogger();
    private static final TimeSource TIME = TimeSource.MONOTONIC;

    private static boolean registered;
    private static MinecraftServer boundServer;
    private static RankingService boundRanking;
    private static ServerSyncService sync;

    private ServerSyncHooks() {
    }

    /** Registro único, invocado desde el constructor del mod (después de los hooks de F4). */
    public static synchronized void register() {
        if (registered) {
            return;
        }
        registered = true;
        NeoForge.EVENT_BUS.addListener(ServerSyncHooks::onServerTick);
        NeoForge.EVENT_BUS.addListener(ServerSyncHooks::onServerStopping);
        NeoForge.EVENT_BUS.addListener(ServerSyncHooks::onPlayerLoggedOut);
    }

    /** Handler C2S de {@code RankingRequest}: valida, enlaza si procede y delega. */
    public static void handleRequest(IPayloadContext context, RankingRequestPayload payload) {
        if (!(context.player() instanceof ServerPlayer player)) {
            return;
        }
        ServerSyncService current = ensureAttached(player.server);
        if (current != null) {
            current.handleRequest(player.getUUID(), payload);
        }
    }

    /** Handler C2S de {@code RankingSubscribe}: idempotente, no consume el cubo (§9.6, A-10). */
    public static void handleSubscribe(IPayloadContext context, RankingSubscribePayload payload) {
        if (!(context.player() instanceof ServerPlayer player)) {
            return;
        }
        ServerSyncService current = ensureAttached(player.server);
        if (current != null) {
            current.handleSubscribe(player.getUUID(), payload);
        }
    }

    private static synchronized ServerSyncService ensureAttached(MinecraftServer server) {
        Optional<RankingService> service = ServerRankingHooks.serviceFor(server);
        if (service.isEmpty()) {
            return null;
        }
        if (sync != null && boundServer == server && boundRanking == service.get()) {
            return sync;
        }
        boundServer = server;
        boundRanking = service.get();
        sync = new ServerSyncService(boundRanking, TRANSPORT, TIME);
        LOGGER.info("QuestRank server sync attached");
        return sync;
    }

    private static synchronized void onServerStopping(ServerStoppingEvent event) {
        if (boundServer == event.getServer() && sync != null) {
            sync.detach();
            sync = null;
            boundServer = null;
            boundRanking = null;
            LOGGER.info("QuestRank server sync detached");
        }
    }

    private static void onServerTick(ServerTickEvent.Post event) {
        ServerSyncService current = ensureAttached(event.getServer());
        if (current != null) {
            current.onTick();
        }
    }

    private static void onPlayerLoggedOut(PlayerEvent.PlayerLoggedOutEvent event) {
        if (event.getEntity() instanceof ServerPlayer player) {
            ServerSyncService current = sync;
            if (current != null && boundServer == player.server) {
                current.onPlayerLogout(player.getUUID());
            }
        }
    }

    private static void sendTo(UUID playerId, CustomPacketPayload payload) {
        MinecraftServer server = boundServer;
        if (server == null) {
            return;
        }
        ServerPlayer player = server.getPlayerList().getPlayer(playerId);
        if (player != null) {
            PacketDistributor.sendToPlayer(player, payload);
        }
    }

    private static final SyncTransport TRANSPORT = new SyncTransport() {
        @Override
        public void send(UUID playerId, RankingSnapshotPayload payload) {
            sendTo(playerId, payload);
        }

        @Override
        public void send(UUID playerId, RankingUnchangedPayload payload) {
            sendTo(playerId, payload);
        }

        @Override
        public void send(UUID playerId, RankingErrorPayload payload) {
            sendTo(playerId, payload);
        }
    };
}
