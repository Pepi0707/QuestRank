package dev.pepsi.questrank.client.net;

import com.mojang.logging.LogUtils;
import dev.pepsi.questrank.diagnostics.RankingDiagnostics;
import dev.pepsi.questrank.net.ClientReceiverHook;
import dev.pepsi.questrank.net.RankingErrorPayload;
import dev.pepsi.questrank.net.RankingRequestPayload;
import dev.pepsi.questrank.net.RankingSnapshotPayload;
import dev.pepsi.questrank.net.RankingSubscribePayload;
import dev.pepsi.questrank.net.RankingUnchangedPayload;
import dev.pepsi.questrank.net.TimeSource;
import net.neoforged.neoforge.client.event.ClientPlayerNetworkEvent;
import net.neoforged.neoforge.client.event.ClientTickEvent;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.network.PacketDistributor;
import org.slf4j.Logger;

/**
 * Wiring de sincronización del cliente (contrato §9): instala el receptor S2C común, alimenta el
 * autómata con el tick y los eventos de conexión, y expone la API de demanda a la interfaz de
 * F5-B ({@link #openRanking()}, {@link #closeRanking()}, {@link #refreshRanking()},
 * {@link #rankingView()}).
 *
 * <p>Clase exclusiva de cliente (§11.6): solo la carga {@code QuestRankClient} (dist CLIENT) y no
 * es accesible desde el código común.
 */
public final class ClientSyncHooks {

    private static final Logger LOGGER = LogUtils.getLogger();

    private static final ClientTransport TRANSPORT = new ClientTransport() {
        @Override
        public void sendRequest(long requestId, Long knownEpoch, long knownRev) {
            PacketDistributor.sendToServer(RankingRequestPayload.of(requestId, knownEpoch, knownRev));
        }

        @Override
        public void sendSubscribe(boolean subscribed) {
            PacketDistributor.sendToServer(new RankingSubscribePayload(subscribed));
        }
    };

    private static final ClientRankingSession SESSION = new ClientRankingSession(TRANSPORT, TimeSource.MONOTONIC);

    private static boolean registered;

    private ClientSyncHooks() {
    }

    /** Registro único, invocado desde el constructor del mod cliente. */
    public static synchronized void register() {
        if (registered) {
            return;
        }
        registered = true;
        ClientReceiverHook.install(new ClientReceiverHook.Receiver() {
            @Override
            public void onSnapshot(RankingSnapshotPayload payload) {
                applyWithDiagnostics(SESSION, payload);
            }

            @Override
            public void onUnchanged(RankingUnchangedPayload payload) {
                SESSION.onUnchanged(payload);
            }

            @Override
            public void onError(RankingErrorPayload payload) {
                SESSION.onError(payload);
            }
        });
        NeoForge.EVENT_BUS.addListener(ClientSyncHooks::onClientTick);
        NeoForge.EVENT_BUS.addListener(ClientSyncHooks::onLoggingIn);
        NeoForge.EVENT_BUS.addListener(ClientSyncHooks::onLoggingOut);
        LOGGER.debug("QuestRank client sync hooks registered");
    }

    /** F5-B: pantalla de ranking abierta. */
    public static void openRanking() {
        SESSION.open();
    }

    /** F5-B: pantalla de ranking cerrada. */
    public static void closeRanking() {
        SESSION.close();
    }

    /** F5-B: botón de actualización manual. */
    public static void refreshRanking() {
        SESSION.refresh();
    }

    /** F5-B: estado actual de sesión/datos. */
    public static ClientSessionView rankingView() {
        return SESSION.view();
    }

    /** Punto único de aplicación y diagnóstico; separado para poder probar el mapeo de outcomes. */
    static void applyWithDiagnostics(ClientRankingSession session, RankingSnapshotPayload payload) {
        if (!RankingDiagnostics.enabled()) {
            session.onSnapshot(payload);
            return;
        }
        ClientSessionView before = session.view();
        long start = System.nanoTime();
        ClientRankingSession.SnapshotOutcome outcome = session.onSnapshot(payload);
        ClientSessionView after = session.view();
        RankingDiagnostics.clientReceived(payload, before.knownEpoch(), before.knownRev(),
                after.knownEpoch(), after.knownRev(), after.sessionValid(), outcome.name(),
                System.nanoTime() - start);
    }

    private static void onClientTick(ClientTickEvent.Post event) {
        SESSION.onTick();
    }

    private static void onLoggingIn(ClientPlayerNetworkEvent.LoggingIn event) {
        SESSION.onConnect();
    }

    private static void onLoggingOut(ClientPlayerNetworkEvent.LoggingOut event) {
        SESSION.onDisconnect();
    }
}
