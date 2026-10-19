package dev.pepsi.questrank.server.net;

import dev.pepsi.questrank.net.RankingErrorPayload;
import dev.pepsi.questrank.net.RankingSnapshotPayload;
import dev.pepsi.questrank.net.RankingUnchangedPayload;
import java.util.UUID;

/** Salida S2C del servidor; puerto inyectable para pruebas deterministas. */
public interface SyncTransport {

    void send(UUID playerId, RankingSnapshotPayload payload);

    void send(UUID playerId, RankingUnchangedPayload payload);

    void send(UUID playerId, RankingErrorPayload payload);
}
