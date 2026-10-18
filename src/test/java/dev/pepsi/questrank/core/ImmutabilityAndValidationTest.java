package dev.pepsi.questrank.core;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import dev.pepsi.questrank.core.catalog.CatalogEntry;
import dev.pepsi.questrank.core.catalog.ComputableUniverse;
import dev.pepsi.questrank.core.catalog.RankingPolicy;
import dev.pepsi.questrank.core.model.MemberInfo;
import dev.pepsi.questrank.core.model.ParticipantData;
import dev.pepsi.questrank.core.model.ParticipantKind;
import dev.pepsi.questrank.core.model.Ranking;
import dev.pepsi.questrank.core.model.RankingEntry;
import dev.pepsi.questrank.core.score.RankingCalculator;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/**
 * Inmutabilidad e invariantes de los modelos (contrato §2, §6.4): copias defensivas, rechazo de
 * nulos, nombres vacíos permitidos, miembros duplicados conservados y validación de puntuaciones
 * y del total. La unicidad de IDs de misión/participante se cubre en sus tests de reglas.
 */
class ImmutabilityAndValidationTest {

    private static final long MAX = RankingEntry.MAX_SAFE_TOTAL;

    @Test
    void participantUsesDefensiveCopiesAndUnmodifiableViews() {
        Set<Long> completed = new HashSet<>();
        completed.add(1L);
        List<MemberInfo> members = new ArrayList<>();
        members.add(new MemberInfo(new UUID(0, 1), "m1", true));

        ParticipantData participant = new ParticipantData(
                new UUID(0, 9), ParticipantKind.PARTY, "t", 0, completed, members);
        completed.add(2L);
        members.add(new MemberInfo(new UUID(0, 2), "m2", false));

        assertEquals(1, participant.completedQuestIds().size());
        assertEquals(1, participant.members().size());
        assertThrows(UnsupportedOperationException.class, () -> participant.completedQuestIds().add(3L));
        assertThrows(UnsupportedOperationException.class,
                () -> participant.members().add(new MemberInfo(new UUID(0, 3), "m3", false)));
    }

    @Test
    void catalogEntryAndPolicyUseDefensiveCopies() {
        Set<String> tags = new HashSet<>();
        tags.add("a");
        CatalogEntry entry = new CatalogEntry(1L, 2L, false, tags);
        tags.add("b");
        assertEquals(Set.of("a"), entry.tags());
        assertThrows(UnsupportedOperationException.class, () -> entry.tags().add("c"));

        Set<String> excluded = new HashSet<>();
        excluded.add("x");
        RankingPolicy policy = new RankingPolicy(excluded);
        excluded.add("y");
        assertEquals(Set.of("x"), policy.excludedTags());
        assertThrows(UnsupportedOperationException.class, () -> policy.excludedTags().add("z"));
    }

    @Test
    void rankingUsesDefensiveCopy() {
        List<RankingEntry> entries = new ArrayList<>();
        entries.add(entry(new UUID(0, 1), "a"));
        Ranking ranking = new Ranking(10L, entries);
        entries.add(entry(new UUID(0, 2), "b"));
        assertEquals(1, ranking.entries().size());
        assertThrows(UnsupportedOperationException.class, () -> ranking.entries().add(entry(new UUID(0, 3), "c")));
    }

    @Test
    void nullsRejected() {
        UUID id = new UUID(0, 1);
        MemberInfo member = new MemberInfo(id, "m", true);
        assertThrows(NullPointerException.class, () -> new MemberInfo(null, "m", true));
        assertThrows(NullPointerException.class, () -> new MemberInfo(id, null, true));
        assertThrows(NullPointerException.class, () -> new ParticipantData(
                null, ParticipantKind.PLAYER, "n", 0, Set.of(), List.of()));
        assertThrows(NullPointerException.class, () -> new ParticipantData(
                id, null, "n", 0, Set.of(), List.of()));
        assertThrows(NullPointerException.class, () -> new ParticipantData(
                id, ParticipantKind.PLAYER, null, 0, Set.of(), List.of()));
        assertThrows(NullPointerException.class, () -> new ParticipantData(
                id, ParticipantKind.PLAYER, "n", 0, null, List.of()));
        assertThrows(NullPointerException.class, () -> new ParticipantData(
                id, ParticipantKind.PLAYER, "n", 0, Set.of(), null));
        assertThrows(NullPointerException.class, () -> new CatalogEntry(1L, 2L, false, null));
        assertThrows(NullPointerException.class, () -> new RankingPolicy(null));
        assertThrows(NullPointerException.class, () -> new Ranking(0L, null));
        assertThrows(NullPointerException.class, () -> entry(null, "a"));
        assertThrows(NullPointerException.class, () -> entry(id, null));
        assertThrows(NullPointerException.class,
                () -> RankingCalculator.compute(null, List.of()));
        assertThrows(NullPointerException.class,
                () -> RankingCalculator.compute(ComputableUniverse.compute(RankingPolicy.defaultPolicy(), List.of()),
                        null));
        assertThrows(NullPointerException.class,
                () -> RankingCalculator.compute(ComputableUniverse.compute(RankingPolicy.defaultPolicy(), List.of()),
                        java.util.Arrays.asList((ParticipantData) null)));
        assertThrows(NullPointerException.class, () -> ComputableUniverse.compute(null, List.of()));
        assertThrows(NullPointerException.class,
                () -> ComputableUniverse.compute(RankingPolicy.defaultPolicy(), null));
        assertEquals(true, member.online());
    }

    @Test
    void emptyNamesAllowed() {
        new MemberInfo(new UUID(0, 1), "", true);
        new ParticipantData(new UUID(0, 2), ParticipantKind.PLAYER, "", 0, Set.of(), List.of());
        entry(new UUID(0, 3), "");
    }

    @Test
    void duplicateMembersPreserved() {
        MemberInfo duplicated = new MemberInfo(new UUID(0, 1), "m", true);
        ParticipantData participant = new ParticipantData(
                new UUID(0, 2), ParticipantKind.PARTY, "t", 0, Set.of(), List.of(duplicated, duplicated));
        assertEquals(2, participant.members().size());
    }

    @Test
    void rankingEntryScoreValidation() {
        entry(new UUID(0, 1), "zero", 0L, 0L);
        entry(new UUID(0, 2), "max", MAX, MAX);
        assertThrows(IllegalArgumentException.class, () -> entry(new UUID(0, 3), "neg", -1L, 0L));
        assertThrows(IllegalArgumentException.class, () -> entry(new UUID(0, 4), "gt", 1L, 0L));
        assertThrows(IllegalArgumentException.class, () -> entry(new UUID(0, 5), "negtotal", 0L, -1L));
        assertThrows(IllegalArgumentException.class, () -> entry(new UUID(0, 6), "over", 0L, MAX + 1));
        assertThrows(IllegalArgumentException.class, () -> entry(new UUID(0, 7), "bothover", MAX + 1, MAX + 1));
    }

    @Test
    void rankingTotalValidation() {
        new Ranking(0L, List.of());
        new Ranking(MAX, List.of());
        assertThrows(IllegalArgumentException.class, () -> new Ranking(-1L, List.of()));
        assertThrows(IllegalArgumentException.class, () -> new Ranking(MAX + 1, List.of()));
    }

    private static RankingEntry entry(UUID id, String name) {
        return entry(id, name, 0L, 10L);
    }

    private static RankingEntry entry(UUID id, String name, long completed, long total) {
        return new RankingEntry(id, ParticipantKind.PLAYER, name, 0, completed, total, List.of());
    }
}
