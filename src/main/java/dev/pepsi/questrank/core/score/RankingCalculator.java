package dev.pepsi.questrank.core.score;

import dev.pepsi.questrank.core.catalog.ComputableUniverse;
import dev.pepsi.questrank.core.model.ParticipantData;
import dev.pepsi.questrank.core.model.Ranking;
import dev.pepsi.questrank.core.model.RankingEntry;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;

/**
 * Cálculo del ranking (contrato §2.6, §6.1–§6.3): {@code C = |completed ∩ U|} por participante y
 * orden según {@link RankingComparator}. El total de todas las entradas es el tamaño del universo
 * computable (N común, §4/§6).
 *
 * <p>Límite público del cálculo: rechaza UUID de participante duplicados (§2.3) con
 * {@link IllegalArgumentException}, lo que garantiza que el comparador reciba un orden total.
 */
public final class RankingCalculator {

    private RankingCalculator() {
    }

    public static Ranking compute(ComputableUniverse universe, List<ParticipantData> participants) {
        Objects.requireNonNull(universe, "universe");
        Objects.requireNonNull(participants, "participants");
        long total = universe.size();
        Set<UUID> seen = new HashSet<>();
        List<RankingEntry> entries = new ArrayList<>(participants.size());
        for (ParticipantData participant : participants) {
            Objects.requireNonNull(participant, "participant");
            if (!seen.add(participant.id())) {
                throw new IllegalArgumentException("duplicate participant id: " + participant.id());
            }
            long completed = participant.completedQuestIds().stream()
                    .filter(universe::contains)
                    .count();
            entries.add(new RankingEntry(
                    participant.id(),
                    participant.kind(),
                    participant.name(),
                    participant.color(),
                    completed,
                    total,
                    participant.members()));
        }
        entries.sort(RankingComparator.INSTANCE);
        return new Ranking(total, entries);
    }
}
