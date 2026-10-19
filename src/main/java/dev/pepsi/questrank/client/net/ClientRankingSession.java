package dev.pepsi.questrank.client.net;

import dev.pepsi.questrank.net.RankingErrorPayload;
import dev.pepsi.questrank.net.RankingSnapshotPayload;
import dev.pepsi.questrank.net.RankingUnchangedPayload;
import dev.pepsi.questrank.net.TimeSource;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Autómata de sesión del cliente (contrato §9.9, bloques A/B/C/D) y espejo de monotonía (§9.8).
 *
 * <p>Modelo guiado por demanda (decisión A-7): abrir la pantalla solicita y suscribe; cerrar
 * desuscribe y cancela la recuperación automática; las respuestas en vuelo siguen siendo válidas
 * según las reglas de correlación. Mientras hay suscripción, una reafirmación de
 * {@code RankingSubscribe(true)} cada ≤60 s evita la caducidad por inactividad (§9.6): es una
 * decisión de diseño (A-1), no una obligación literal del contrato, y no consume el cubo de
 * peticiones (A-10).
 *
 * <p>Recuperación (D1–D3): las peticiones automáticas se emiten solo sin ninguna solicitud en
 * curso y con la sesión de la conexión actual no válida, como máximo una cada 5 s; caducan a los
 * 30 s y, tras dos caducidades automáticas consecutivas sin establecerse la sesión, el cliente
 * pasa al estado de error con reintento manual (D5b, decisión A-8).
 */
public final class ClientRankingSession {

    /** Caducidad de identificadores pendientes (contrato §9.9.D1 [PARAM]). */
    public static final long REQUEST_ID_EXPIRY_MILLIS = 30_000L;

    /** Throttle de recuperación automática (contrato §9.9.D3 [PARAM]). */
    public static final long RECOVERY_THROTTLE_MILLIS = 5_000L;

    /** Cadencia de reafirmación de suscripción (decisión de diseño A-1). */
    public static final long KEEP_ALIVE_INTERVAL_MILLIS = 60_000L;

    /** Caducidades automáticas consecutivas antes del estado de error (contrato §9.9.D5b [PARAM]). */
    public static final int MAX_EXPIRED_AUTO_RECOVERIES = 2;

    private static final Logger LOGGER = LoggerFactory.getLogger("QuestRankClientSync");
    private static final long NEVER = Long.MIN_VALUE;

    private final ClientTransport transport;
    private final TimeSource time;
    private final RequestIdGenerator requestIds;

    private boolean connected;
    private boolean demand;
    private boolean subscribed;
    private Long knownEpoch;
    private long knownRev;
    private RankingSnapshotPayload data;
    private boolean sessionValid;
    private boolean possiblyStale;
    private boolean error;
    private final Map<Long, PendingRequest> pending = new LinkedHashMap<>();
    private long lastAutoRecoveryMillis = NEVER;
    private int expiredAutoRecoveries;
    private long lastSubscribeMillis = NEVER;

    public ClientRankingSession(ClientTransport transport, TimeSource time) {
        this(transport, time, new RequestIdGenerator());
    }

    ClientRankingSession(ClientTransport transport, TimeSource time, RequestIdGenerator requestIds) {
        this.transport = Objects.requireNonNull(transport, "transport");
        this.time = Objects.requireNonNull(time, "time");
        this.requestIds = Objects.requireNonNull(requestIds, "requestIds");
    }

    /** Abrir la pantalla: solicita y suscribe (D4/A-7); idempotente mientras ya haya demanda. */
    public void open() {
        if (demand) {
            return;
        }
        demand = true;
        error = false;
        expiredAutoRecoveries = 0;
        if (connected) {
            long now = time.nowMillis();
            issueRequest(false, now);
            setSubscribed(true, now);
        }
    }

    /** Cerrar la pantalla: desuscribe y cancela la recuperación; no toca lo que está en vuelo. */
    public void close() {
        if (!demand) {
            return;
        }
        demand = false;
        if (subscribed) {
            transport.sendSubscribe(false);
            subscribed = false;
        }
    }

    /** Refresco manual (sin throttle de recuperación, sujeto a §9.6 en el servidor); re-arma el error. */
    public void refresh() {
        if (!connected) {
            return;
        }
        error = false;
        expiredAutoRecoveries = 0;
        issueRequest(false, time.nowMillis());
    }

    /** Conexión (D4): ciclo nuevo; con demanda, solicitud antes o junto con la suscripción. */
    public void onConnect() {
        connected = true;
        sessionValid = false;
        possiblyStale = data != null;
        pending.clear();
        expiredAutoRecoveries = 0;
        error = false;
        lastAutoRecoveryMillis = NEVER;
        if (demand) {
            long now = time.nowMillis();
            issueRequest(false, now);
            setSubscribed(true, now);
        }
    }

    /** Desconexión (D1): vaciar en `Curso`, cancelar recuperación; se conserva la caché marcada. */
    public void onDisconnect() {
        connected = false;
        subscribed = false;
        pending.clear();
        sessionValid = false;
        possiblyStale = data != null;
        expiredAutoRecoveries = 0;
    }

    /** Tick del cliente: caducidades (D1/D2), recuperación automática (D3) y keep-alive (A-1). */
    public void onTick() {
        long now = time.nowMillis();
        List<PendingRequest> expired = new ArrayList<>();
        pending.entrySet().removeIf(entry -> {
            if (entry.getValue().deadlineMillis() <= now) {
                expired.add(entry.getValue());
                return true;
            }
            return false;
        });
        if (!expired.isEmpty() && pending.isEmpty() && connected && demand && !error && !sessionValid) {
            boolean automatic = false;
            for (PendingRequest request : expired) {
                if (request.automatic()) {
                    automatic = true;
                    break;
                }
            }
            if (automatic) {
                expiredAutoRecoveries++;
                if (expiredAutoRecoveries >= MAX_EXPIRED_AUTO_RECOVERIES) {
                    error = true;
                    LOGGER.info("QuestRank client: two consecutive automatic recoveries expired; error with retry");
                }
            }
        }
        if (!error) {
            maybeAutoRecover(now);
        }
        maybeKeepAlive(now);
    }

    /** Aplicación de estado A1–A4 + monotonía §9.8 + pushes C1–C2. */
    public void onSnapshot(RankingSnapshotPayload payload) {
        boolean correlated = finalizeRequest(payload.requestId());
        Long epoch = knownEpoch;
        if (epoch == null) {
            if (correlated) {
                adopt(payload);
            }
            return; // A1: sin correlación se descarta
        }
        if (payload.epoch() == epoch) {
            boolean advanced = payload.rev() > knownRev;
            if (advanced) {
                knownRev = payload.rev();
                data = payload;
            }
            // H-2: solo una respuesta correlacionada o un avance real de rev confirman la sesión;
            // un push no correlacionado con rev igual o inferior no valida, no limpia la marca de
            // obsolescencia y no reinicia el contador de recuperación (§9.9.A2/B5/C1/D5a).
            if (correlated || advanced) {
                confirmSession(correlated);
            }
            return;
        }
        if (correlated) {
            adopt(payload); // A3: nueva sesión, caché anterior descartada
        } else {
            invalidate(true); // C2: push con epoch distinto ⇒ descartar + recuperación
            LOGGER.debug("QuestRank client: push with foreign epoch discarded");
        }
    }

    /** B4: `RankingUnchanged` solo válido con epoch y rev conocidos coincidentes. */
    public void onUnchanged(RankingUnchangedPayload payload) {
        if (!finalizeRequest(payload.requestId())) {
            return; // B4: no correlacionado ⇒ descartado sin más efectos
        }
        Long epoch = knownEpoch;
        if (epoch != null && payload.epoch() == epoch && payload.rev() == knownRev) {
            confirmSession(true);
            return;
        }
        sessionValid = false;
        if (epoch != null && payload.epoch() != epoch && data != null) {
            possiblyStale = true;
        }
        LOGGER.debug("QuestRank client: invalid Unchanged discarded; recovery armed");
    }

    /** B3: error correlacionado ⇒ fin de solicitud, caché intacta, estado de error con reintento. */
    public void onError(RankingErrorPayload payload) {
        if (finalizeRequest(payload.requestId())) {
            error = true;
            LOGGER.info("QuestRank client: server error (code={}); error state with retry", payload.code());
        }
    }

    /** Vista inmutable para la interfaz (D5). */
    public ClientSessionView view() {
        boolean recoveryWindow = connected && demand && !error && !sessionValid;
        boolean loading = data == null && (!pending.isEmpty() || recoveryWindow);
        return new ClientSessionView(connected, demand, knownEpoch, knownRev, data, sessionValid, possiblyStale,
                loading, error, requestIds.isExhausted());
    }

    private void issueRequest(boolean automatic, long now) {
        if (!connected || requestIds.isExhausted()) {
            return;
        }
        long id = requestIds.take();
        pending.put(id, new PendingRequest(now + REQUEST_ID_EXPIRY_MILLIS, automatic));
        if (automatic) {
            lastAutoRecoveryMillis = now;
        }
        transport.sendRequest(id, knownEpoch, knownRev);
        LOGGER.debug("QuestRank client: {} request id={} (knownEpoch={}, knownRev={})",
                automatic ? "recovery" : "manual", id, knownEpoch, knownRev);
    }

    private void maybeAutoRecover(long now) {
        if (!connected || !demand || error || sessionValid || !pending.isEmpty()) {
            return;
        }
        if (lastAutoRecoveryMillis != NEVER && now - lastAutoRecoveryMillis < RECOVERY_THROTTLE_MILLIS) {
            return;
        }
        issueRequest(true, now);
    }

    private void maybeKeepAlive(long now) {
        if (!connected || !subscribed) {
            return;
        }
        if (lastSubscribeMillis != NEVER && now - lastSubscribeMillis < KEEP_ALIVE_INTERVAL_MILLIS) {
            return;
        }
        transport.sendSubscribe(true);
        lastSubscribeMillis = now;
        LOGGER.debug("QuestRank client: subscription keep-alive");
    }

    private void setSubscribed(boolean value, long now) {
        if (subscribed == value) {
            return;
        }
        subscribed = value;
        transport.sendSubscribe(value);
        if (value) {
            lastSubscribeMillis = now;
        }
    }

    private boolean finalizeRequest(long requestId) {
        return requestId != 0L && pending.remove(requestId) != null;
    }

    private void adopt(RankingSnapshotPayload payload) {
        knownEpoch = payload.epoch();
        knownRev = payload.rev();
        data = payload;
        sessionValid = true;
        possiblyStale = false;
        error = false;
        expiredAutoRecoveries = 0;
        LOGGER.info("QuestRank client: session established (epoch={}, rev={})", payload.epoch(), payload.rev());
    }

    private void confirmSession(boolean clearError) {
        sessionValid = true;
        possiblyStale = false;
        expiredAutoRecoveries = 0;
        if (clearError) {
            error = false;
        }
    }

    private void invalidate(boolean stale) {
        sessionValid = false;
        if (stale && data != null) {
            possiblyStale = true;
        }
        LOGGER.debug("QuestRank client: session invalidated; recovery armed");
    }

    private record PendingRequest(long deadlineMillis, boolean automatic) {
    }
}
