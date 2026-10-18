package dev.pepsi.questrank.core.model;

import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;

/**
 * Entrada del cálculo del ranking para un participante (contrato §2.2, §8.3).
 *
 * <p>{@code completedQuestIds} son los identificadores de misión que la integración considera
 * completados según el predicado de §5 ({@link dev.pepsi.questrank.core.score.CompletionRule});
 * el núcleo solo cuenta los que pertenecen al universo computable U (§4).
 *
 * <p>Colecciones copiadas de forma defensiva e inmutables. Los miembros duplicados se conservan
 * (el contrato no impone unicidad en la lista de miembros); los nombres vacíos son válidos.
 */
public record ParticipantData(
        UUID id,
        ParticipantKind kind,
        String name,
        int color,
        Set<Long> completedQuestIds,
        List<MemberInfo> members) {

    public ParticipantData {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(kind, "kind");
        Objects.requireNonNull(name, "name");
        completedQuestIds = Set.copyOf(Objects.requireNonNull(completedQuestIds, "completedQuestIds"));
        members = List.copyOf(Objects.requireNonNull(members, "members"));
    }
}
