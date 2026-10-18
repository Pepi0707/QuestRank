package dev.pepsi.questrank.server;

import dev.pepsi.questrank.core.model.Ranking;
import java.util.Objects;

/**
 * Snapshot publicado en memoria (frontera con F5): epoch, revisiones y ranking completo, sin
 * truncar. {@code participantCount} es {@code ranking.entries().size()}; los topes, nombres
 * truncados y banderas de truncamiento (§9.4) pertenecen a la capa de red.
 */
public record ServiceSnapshot(long epoch, long rev, long catalogRevision, Ranking ranking) {

    public ServiceSnapshot {
        Objects.requireNonNull(ranking, "ranking");
    }
}
