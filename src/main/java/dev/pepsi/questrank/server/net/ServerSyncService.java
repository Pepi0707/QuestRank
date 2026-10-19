package dev.pepsi.questrank.server.net;

import dev.pepsi.questrank.net.RankingErrorPayload;
import dev.pepsi.questrank.net.RankingRequestPayload;
import dev.pepsi.questrank.net.RankingSnapshotPayload;
import dev.pepsi.questrank.net.RankingSubscribePayload;
import dev.pepsi.questrank.net.RankingUnchangedPayload;
import dev.pepsi.questrank.net.TimeSource;
import dev.pepsi.questrank.server.RankingResult;
import dev.pepsi.questrank.server.RankingService;
import dev.pepsi.questrank.server.ServiceSnapshot;
import java.util.HashMap;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Servicio de sincronización por servidor (contrato §9): responde peticiones con el pipeline de
 * F4, gestiona suscripciones y difunde pushes con el throttle de §8.9, el límite de §9.6 y la
 * caducidad por inactividad.
 *
 * <p>Confinado al hilo del servidor: todas las llamadas deben realizarse desde él. El estado por
 * jugador (cubo, suscripción, ventana de inactividad, último rev enviado) se elimina al
 * desconectar el jugador y al desvincular el servicio.
 *
 * <p>Nota de diseño (A-1): la caducidad por inactividad usa la ventana de §9.6 sobre el último
 * mensaje de protocolo válido; el cliente reafirma la suscripción cada ≤60 s mientras haya
 * demanda (no consume el cubo de peticiones).
 */
public final class ServerSyncService {

    /** Ráfaga de peticiones por jugador (contrato §9.6 [PARAM]). */
    public static final int REQUEST_BURST = 3;

    /** Recarga del cubo de peticiones (contrato §9.6 [PARAM]). */
    public static final long REQUEST_REFILL_MILLIS = 1_000L;

    /** Intervalo mínimo entre pushes por cliente (contrato §8.9 [PARAM]). */
    public static final long PUSH_INTERVAL_MILLIS = 2_000L;

    /** Caducidad de suscripciones por inactividad (contrato §9.6 [PARAM]). */
    public static final long SUBSCRIPTION_INACTIVITY_MILLIS = 120_000L;

    private static final Logger LOGGER = LoggerFactory.getLogger("QuestRankSync");

    private final RankingService service;
    private final SyncTransport transport;
    private final TimeSource time;
    private final Map<UUID, PlayerState> players = new HashMap<>();
    private ServiceSnapshot latest;
    private int subscribers;

    public ServerSyncService(RankingService service, SyncTransport transport, TimeSource time) {
        this.service = Objects.requireNonNull(service, "service");
        this.transport = Objects.requireNonNull(transport, "transport");
        this.time = Objects.requireNonNull(time, "time");
        service.setSnapshotListener(this::onSnapshotPublished);
        service.setSubscriberCount(0);
    }

    /** Desvincula el servicio (parada o reemplazo): sin contadores ni referencias vivas. */
    public void detach() {
        service.setSnapshotListener(snapshot -> {
        });
        service.setSubscriberCount(0);
        players.clear();
        subscribers = 0;
        latest = null;
    }

    /** Número de jugadores suscritos (uso interno y de pruebas). */
    int subscriberCount() {
        return subscribers;
    }

    public void onPlayerLogout(UUID playerId) {
        PlayerState state = players.remove(playerId);
        if (state != null && state.subscribed) {
            subscribers = Math.max(0, subscribers - 1);
            service.setSubscriberCount(subscribers);
            LOGGER.info("QuestRank sync: subscriber left (subscribers={})", subscribers);
        }
    }

    public void handleRequest(UUID playerId, RankingRequestPayload request) {
        if (!request.isValid()) {
            LOGGER.debug("QuestRank sync: structurally invalid request ignored");
            return;
        }
        PlayerState state = state(playerId);
        long now = time.nowMillis();
        state.lastActivityMillis = now;
        if (!state.bucket.tryConsume(now)) {
            LOGGER.debug("QuestRank sync: request over rate limit silently dropped");
            return;
        }
        // Fail-fast: una anomalía interna del pipeline de F4 se propaga (decisión A-3).
        // H-1: el solicitante queda excluido del fan-out del snapshot que su propia petición
        // publique; recibirá la respuesta directa con el eco de su requestId.
        state.requestInFlight = true;
        RankingResult result;
        try {
            result = service.requestState(request.knownEpochOrNull(), request.knownRev());
        } finally {
            state.requestInFlight = false;
        }
        if (result instanceof RankingResult.Unchanged) {
            transport.send(playerId, new RankingUnchangedPayload(request.requestId(), request.knownEpoch(), request.knownRev()));
        } else if (result instanceof RankingResult.Snapshot snapshotResult) {
            RankingSnapshotPayload payload = toPayload(request.requestId(), snapshotResult.snapshot());
            state.lastSentEpoch = payload.epoch();
            state.lastSentRev = payload.rev();
            transport.send(playerId, payload);
        } else if (result instanceof RankingResult.Error error) {
            transport.send(playerId, new RankingErrorPayload(request.requestId(), codeFor(error.kind())));
        }
    }

    public void handleSubscribe(UUID playerId, RankingSubscribePayload payload) {
        PlayerState state = state(playerId);
        state.lastActivityMillis = time.nowMillis();
        if (payload.subscribed() == state.subscribed) {
            return; // idempotente (decisión A-10)
        }
        state.subscribed = payload.subscribed();
        if (state.subscribed) {
            subscribers++;
        } else {
            subscribers = Math.max(0, subscribers - 1);
            state.pushPending = false;
        }
        service.setSubscriberCount(subscribers);
        LOGGER.info("QuestRank sync: subscription {} (subscribers={})", state.subscribed ? "on" : "off", subscribers);
    }

    /** Tick del servidor: caducidad por inactividad y vaciado de pushes pendientes. */
    public void onTick() {
        long now = time.nowMillis();
        for (Map.Entry<UUID, PlayerState> entry : players.entrySet()) {
            PlayerState state = entry.getValue();
            if (state.subscribed && now - state.lastActivityMillis >= SUBSCRIPTION_INACTIVITY_MILLIS) {
                state.subscribed = false;
                state.pushPending = false;
                subscribers = Math.max(0, subscribers - 1);
                service.setSubscriberCount(subscribers);
                LOGGER.info("QuestRank sync: subscription expired by inactivity (subscribers={})", subscribers);
            }
            if (state.pushPending) {
                if (latest != null && isNewer(latest, state) && now - state.lastPushMillis >= PUSH_INTERVAL_MILLIS) {
                    sendPush(entry.getKey(), state, latest, now);
                } else if (latest == null || !isNewer(latest, state)) {
                    state.pushPending = false;
                }
            }
        }
    }

    private void onSnapshotPublished(ServiceSnapshot snapshot) {
        latest = snapshot;
        long now = time.nowMillis();
        for (Map.Entry<UUID, PlayerState> entry : players.entrySet()) {
            PlayerState state = entry.getValue();
            if (!state.subscribed || state.requestInFlight || !isNewer(snapshot, state)) {
                continue;
            }
            if (now - state.lastPushMillis >= PUSH_INTERVAL_MILLIS) {
                sendPush(entry.getKey(), state, snapshot, now);
            } else {
                state.pushPending = true;
            }
        }
    }

    private void sendPush(UUID playerId, PlayerState state, ServiceSnapshot snapshot, long now) {
        RankingSnapshotPayload payload = toPayload(0L, snapshot);
        transport.send(playerId, payload);
        state.lastPushMillis = now;
        state.lastSentEpoch = payload.epoch();
        state.lastSentRev = payload.rev();
        state.pushPending = false;
        LOGGER.debug("QuestRank sync: push epoch={} rev={}", payload.epoch(), payload.rev());
    }

    private static RankingSnapshotPayload toPayload(long requestId, ServiceSnapshot snapshot) {
        return RankingSnapshotPayload.of(requestId, snapshot.epoch(), snapshot.rev(), snapshot.catalogRevision(),
                snapshot.ranking());
    }

    private static boolean isNewer(ServiceSnapshot snapshot, PlayerState state) {
        return state.lastSentEpoch == null
                || snapshot.epoch() != state.lastSentEpoch
                || snapshot.rev() > state.lastSentRev;
    }

    private static int codeFor(RankingResult.ErrorKind kind) {
        return switch (kind) {
            case CATALOG_UNAVAILABLE -> RankingErrorPayload.CODE_CATALOG_UNAVAILABLE;
            case PARTICIPANTS_UNAVAILABLE -> RankingErrorPayload.CODE_PARTICIPANTS_UNAVAILABLE;
        };
    }

    private PlayerState state(UUID playerId) {
        return players.computeIfAbsent(playerId, id -> new PlayerState(time.nowMillis()));
    }

    private static final class PlayerState {

        private final TokenBucket bucket;
        private boolean subscribed;
        private boolean requestInFlight;
        private long lastActivityMillis;
        private long lastPushMillis;
        private boolean pushPending;
        private Long lastSentEpoch;
        private long lastSentRev;

        private PlayerState(long nowMillis) {
            this.bucket = new TokenBucket(REQUEST_BURST, REQUEST_REFILL_MILLIS, nowMillis);
            this.lastActivityMillis = nowMillis;
            this.lastPushMillis = nowMillis - PUSH_INTERVAL_MILLIS;
        }
    }
}
