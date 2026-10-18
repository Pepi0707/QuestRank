package dev.pepsi.questrank.server;

import com.mojang.logging.LogUtils;
import dev.pepsi.questrank.config.QuestRankConfig;
import dev.pepsi.questrank.core.catalog.RankingPolicy;
import dev.pepsi.questrank.integration.ftb.FtbCatalogReader;
import dev.pepsi.questrank.integration.ftb.FtbEventBridge;
import dev.pepsi.questrank.integration.ftb.FtbParticipantsReader;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ThreadLocalRandom;
import java.util.function.Consumer;
import net.minecraft.server.MinecraftServer;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.event.server.ServerStartedEvent;
import net.neoforged.neoforge.event.server.ServerStoppingEvent;
import net.neoforged.neoforge.event.tick.ServerTickEvent;
import org.slf4j.Logger;

/**
 * Wiring del servicio de servidor (contrato §8.5–§8.11): registra una sola vez los listeners del
 * bus de juego y el puente de eventos FTB, crea el servicio por arranque (epoch nuevo por
 * arranque, §2.7), lo descarta en la parada y reencamina los eventos al hilo del servidor.
 *
 * <p>La referencia estática única sigue el modelo de la plataforma (un servidor por JVM; cf.
 * {@code ServerLifecycleHooks.currentServer}), pero el ligamen se comprueba por identidad de
 * servidor y se limpia al parar, de modo que un servidor integrado, una parada o un reinicio no
 * dejan estado obsoleto ni eventos dirigidos a un servicio muerto.
 */
public final class ServerRankingHooks {

    private static final Logger LOGGER = LogUtils.getLogger();

    private static final RankingService.PolicySource CONFIG_POLICY = new RankingService.PolicySource() {
        @Override
        public RankingPolicy policy() {
            return QuestRankConfig.rankingPolicy();
        }

        @Override
        public long configRevision() {
            return QuestRankConfig.configRevision();
        }
    };

    private static boolean registered;
    private static volatile MinecraftServer boundServer;
    private static volatile RankingService service;

    private ServerRankingHooks() {
    }

    /** Registro único, invocado desde el constructor del mod. */
    public static synchronized void register() {
        if (registered) {
            return;
        }
        registered = true;
        FtbEventBridge.register();
        NeoForge.EVENT_BUS.addListener(ServerRankingHooks::onServerStarted);
        NeoForge.EVENT_BUS.addListener(ServerRankingHooks::onServerStopping);
        NeoForge.EVENT_BUS.addListener(ServerRankingHooks::onServerTick);
    }

    /** Servicio vigente para un servidor dado (frontera con F5); vacío si no está ligado. */
    public static Optional<RankingService> serviceFor(MinecraftServer server) {
        Objects.requireNonNull(server, "server");
        RankingService current = service;
        return current != null && boundServer == server ? Optional.of(current) : Optional.empty();
    }

    private static void onServerStarted(ServerStartedEvent event) {
        MinecraftServer server = event.getServer();
        if (service != null) {
            LOGGER.warn("QuestRank: replacing a ranking service of a server that did not stop (defensive)");
            detach();
        }
        long epoch = ThreadLocalRandom.current().nextLong();
        RankingService created = new RankingService(epoch, FtbCatalogReader::read,
                FtbParticipantsReader::read, CONFIG_POLICY);
        boundServer = server;
        service = created;
        FtbEventBridge.attach(
                () -> dispatch(server, created, RankingService::onTeamsChanged),
                () -> dispatch(server, created, RankingService::onProgressChanged),
                () -> dispatch(server, created, RankingService::onCatalogReloaded));
        LOGGER.info("QuestRank ranking service started (epoch={})", epoch);
    }

    private static void onServerStopping(ServerStoppingEvent event) {
        if (service != null && boundServer == event.getServer()) {
            detach();
            LOGGER.info("QuestRank ranking service stopped");
        }
    }

    private static void onServerTick(ServerTickEvent.Post event) {
        RankingService current = service;
        if (current != null && boundServer == event.getServer()) {
            current.onTick();
        }
    }

    private static void detach() {
        FtbEventBridge.detach();
        service = null;
        boundServer = null;
    }

    private static void dispatch(MinecraftServer server, RankingService expected, Consumer<RankingService> action) {
        if (server.isSameThread()) {
            dispatchNow(server, expected, action);
        } else {
            try {
                server.execute(() -> dispatchNow(server, expected, action));
            } catch (RejectedExecutionException e) {
                // El servidor ya no acepta trabajo (parada en curso): el evento se descarta.
            }
        }
    }

    private static void dispatchNow(MinecraftServer server, RankingService expected, Consumer<RankingService> action) {
        if (service == expected && boundServer == server) {
            action.accept(expected);
        }
    }
}
