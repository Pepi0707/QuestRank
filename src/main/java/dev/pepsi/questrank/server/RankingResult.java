package dev.pepsi.questrank.server;

import java.util.Objects;

/**
 * Resultado de una petición de estado (contrato §8.6), sin serialización ni {@code requestId}:
 * esa capa corresponde a F5 (§9.2).
 */
public sealed interface RankingResult {

    /** Causas de error internas; F5 las convierte en RankingError (§9.2). */
    enum ErrorKind {
        CATALOG_UNAVAILABLE,
        PARTICIPANTS_UNAVAILABLE
    }

    /** El (epoch, rev) del cliente coincide con el snapshot vigente (§8.6). */
    record Unchanged() implements RankingResult {

        public static final Unchanged INSTANCE = new Unchanged();
    }

    /** Snapshot vigente, coherente con el catálogo validado. */
    record Snapshot(ServiceSnapshot snapshot) implements RankingResult {

        public Snapshot {
            Objects.requireNonNull(snapshot, "snapshot");
        }
    }

    /** Estado no disponible: ningún snapshot puede afirmarse como vigente (§8.5). */
    record Error(ErrorKind kind) implements RankingResult {

        public Error {
            Objects.requireNonNull(kind, "kind");
        }
    }
}
