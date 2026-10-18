package dev.pepsi.questrank.core.model;

import java.util.List;
import java.util.Objects;
import java.util.UUID;

/**
 * Entrada calculada del ranking (contrato §6, §8.3, §9.3).
 *
 * <p>Invariante de dominio: {@code 0 <= completed <= total <= MAX_SAFE_TOTAL}. El tope
 * ({@code floor(sqrt(Long.MAX_VALUE))}) garantiza que ni el porcentaje de §6.4
 * ({@code 200*C + N}) ni los productos cruzados del comparador de §6.1 puedan desbordar.
 * Valores fuera del dominio se rechazan con {@link IllegalArgumentException} (fail-fast).
 */
public record RankingEntry(
        UUID id,
        ParticipantKind kind,
        String name,
        int color,
        long completed,
        long total,
        List<MemberInfo> members) {

    /** Cota superior segura de puntuaciones: {@code floor(sqrt(Long.MAX_VALUE))} = 3_037_000_499. */
    public static final long MAX_SAFE_TOTAL = 3_037_000_499L;

    public RankingEntry {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(kind, "kind");
        Objects.requireNonNull(name, "name");
        members = List.copyOf(Objects.requireNonNull(members, "members"));
        if (completed < 0 || total < 0 || completed > total || total > MAX_SAFE_TOTAL) {
            throw new IllegalArgumentException(
                    "invalid score: completed=" + completed + ", total=" + total
                            + " (expected 0 <= completed <= total <= " + MAX_SAFE_TOTAL + ")");
        }
    }
}
