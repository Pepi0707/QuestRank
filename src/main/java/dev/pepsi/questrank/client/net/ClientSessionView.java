package dev.pepsi.questrank.client.net;

import dev.pepsi.questrank.net.RankingSnapshotPayload;

/**
 * Vista de solo lectura del estado de sesión del cliente para la interfaz de F5-B (contrato
 * §9.9.D5): datos utilizables, marca de posible obsolescencia, carga, error con reintento y
 * agotamiento de identificadores (aviso §9.1).
 */
public record ClientSessionView(
        boolean connected,
        boolean demand,
        Long knownEpoch,
        long knownRev,
        RankingSnapshotPayload data,
        boolean sessionValid,
        boolean possiblyStale,
        boolean loading,
        boolean error,
        boolean requestIdsExhausted) {

    public boolean hasData() {
        return data != null;
    }
}
