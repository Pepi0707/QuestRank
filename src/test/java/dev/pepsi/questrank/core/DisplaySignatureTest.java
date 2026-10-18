package dev.pepsi.questrank.core;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;

import dev.pepsi.questrank.core.model.MemberInfo;
import dev.pepsi.questrank.core.model.ParticipantKind;
import dev.pepsi.questrank.core.model.Ranking;
import dev.pepsi.questrank.core.model.RankingEntry;
import dev.pepsi.questrank.core.signature.DisplaySignature;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Random;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/**
 * U2 (contrato §13.1): detección de cambios por la firma de display (§8.3): estabilidad,
 * independencia del orden, sensibilidad a cada campo y consideración de todos los miembros.
 */
class DisplaySignatureTest {

    private static final long REV = 5L;
    private static final long TOTAL = 100L;
    private static final int COLOR = 0x334455;

    private static MemberInfo member(long most, long least, String name, boolean online) {
        return new MemberInfo(new UUID(most, least), name, online);
    }

    private static RankingEntry entry(UUID id, ParticipantKind kind, String name, int color,
                                      long completed, List<MemberInfo> members) {
        return new RankingEntry(id, kind, name, color, completed, TOTAL, members);
    }

    private static RankingEntry simpleEntry(UUID id, String name, long completed) {
        return entry(id, ParticipantKind.PARTY, name, COLOR, completed, List.of());
    }

    private static long sig(long catalogRevision, RankingEntry... entries) {
        return DisplaySignature.compute(catalogRevision, new Ranking(TOTAL, List.of(entries)));
    }

    @Test
    void stableAndOrderIndependent() {
        RankingEntry first = entry(new UUID(0, 1), ParticipantKind.PARTY, "uno", COLOR, 10,
                List.of(member(0, 11, "m1", true), member(0, 12, "m2", false)));
        RankingEntry second = entry(new UUID(0, 2), ParticipantKind.PLAYER, "dos", 0x112233, 20,
                List.of(member(0, 21, "m3", true)));
        RankingEntry third = simpleEntry(new UUID(0, 3), "tres", 30);

        List<RankingEntry> entries = new ArrayList<>(List.of(first, second, third));
        Collections.shuffle(entries, new Random(21L));

        long reference = DisplaySignature.compute(REV, new Ranking(TOTAL, List.of(first, second, third)));
        assertEquals(reference, DisplaySignature.compute(REV, new Ranking(TOTAL, entries)));

        RankingEntry firstReorderedMembers = entry(new UUID(0, 1), ParticipantKind.PARTY, "uno", COLOR, 10,
                List.of(member(0, 12, "m2", false), member(0, 11, "m1", true)));
        assertEquals(reference, DisplaySignature.compute(REV,
                new Ranking(TOTAL, List.of(firstReorderedMembers, second, third))));
    }

    @Test
    void sensitiveToCatalogRevision() {
        assertNotEquals(sig(REV, simpleEntry(new UUID(0, 1), "a", 10)),
                sig(REV + 1, simpleEntry(new UUID(0, 1), "a", 10)));
    }

    @Test
    void sensitiveToParticipantFields() {
        long base = sig(REV, simpleEntry(new UUID(0, 1), "a", 10));
        assertNotEquals(base, sig(REV, simpleEntry(new UUID(0, 2), "a", 10)));
        assertNotEquals(base, sig(REV, entry(new UUID(0, 1), ParticipantKind.PLAYER, "a", COLOR, 10, List.of())));
        assertNotEquals(base, sig(REV, simpleEntry(new UUID(0, 1), "b", 10)));
        assertNotEquals(base, sig(REV, entry(new UUID(0, 1), ParticipantKind.PARTY, "a", 0x000001, 10, List.of())));
        assertNotEquals(base, sig(REV, simpleEntry(new UUID(0, 1), "a", 11)));
        assertNotEquals(base, sig(REV, simpleEntry(new UUID(0, 1), "a", 10), simpleEntry(new UUID(0, 2), "b", 1)));
    }

    @Test
    void sensitiveToMemberFields() {
        RankingEntry base = entry(new UUID(0, 1), ParticipantKind.PARTY, "a", COLOR, 10,
                List.of(member(0, 11, "m1", true)));
        assertNotEquals(sig(REV, base), sig(REV, entry(new UUID(0, 1), ParticipantKind.PARTY, "a", COLOR, 10,
                List.of(member(0, 11, "m1", false)))));
        assertNotEquals(sig(REV, base), sig(REV, entry(new UUID(0, 1), ParticipantKind.PARTY, "a", COLOR, 10,
                List.of(member(0, 11, "m2", true)))));
        assertNotEquals(sig(REV, base), sig(REV, entry(new UUID(0, 1), ParticipantKind.PARTY, "a", COLOR, 10,
                List.of(member(0, 12, "m1", true)))));
        assertNotEquals(sig(REV, base), sig(REV, entry(new UUID(0, 1), ParticipantKind.PARTY, "a", COLOR, 10,
                List.of(member(0, 11, "m1", true), member(0, 12, "m2", true)))));
        assertNotEquals(sig(REV, base), sig(REV, entry(new UUID(0, 1), ParticipantKind.PARTY, "a", COLOR, 10,
                List.of())));
    }

    @Test
    void kindMappingAnchored() {
        assertEquals(0, DisplaySignature.kindByte(ParticipantKind.PARTY));
        assertEquals(1, DisplaySignature.kindByte(ParticipantKind.PLAYER));
        assertNotEquals(
                sig(REV, entry(new UUID(0, 1), ParticipantKind.PARTY, "a", COLOR, 10, List.of())),
                sig(REV, entry(new UUID(0, 1), ParticipantKind.PLAYER, "a", COLOR, 10, List.of())));
    }

    @Test
    void considersAllMembersBeyondAnyDisplayCap() {
        List<MemberInfo> members = new ArrayList<>();
        for (long index = 11; index <= 25; index++) {
            members.add(member(0, index, "m" + index, true));
        }
        List<MemberInfo> toggled = new ArrayList<>(members.subList(0, members.size() - 1));
        toggled.add(member(0, 25, "m25", false));
        RankingEntry full = entry(new UUID(0, 1), ParticipantKind.PARTY, "a", COLOR, 10, members);
        RankingEntry changedLast = entry(new UUID(0, 1), ParticipantKind.PARTY, "a", COLOR, 10, toggled);
        assertNotEquals(sig(REV, full), sig(REV, changedLast));
    }

    @Test
    void duplicateMembersPreservedAndDeterministic() {
        MemberInfo duplicated = member(0, 11, "m1", true);
        RankingEntry one = entry(new UUID(0, 1), ParticipantKind.PARTY, "a", COLOR, 10, List.of(duplicated));
        RankingEntry two = entry(new UUID(0, 1), ParticipantKind.PARTY, "a", COLOR, 10,
                List.of(duplicated, duplicated));
        assertNotEquals(sig(REV, one), sig(REV, two));
        assertEquals(sig(REV, two), sig(REV, entry(new UUID(0, 1), ParticipantKind.PARTY, "a", COLOR, 10,
                List.of(duplicated, duplicated))));
    }

    @Test
    void emptyNamesAndZeroTotalsSucceed() {
        long empty = sig(REV, simpleEntry(new UUID(0, 1), "", 0));
        assertNotEquals(empty, sig(REV, simpleEntry(new UUID(0, 1), "a", 0)));
        Ranking zero = new Ranking(0,
                List.of(new RankingEntry(new UUID(0, 1), ParticipantKind.PLAYER, "", 0, 0, 0, List.of())));
        assertEquals(DisplaySignature.compute(REV, zero), DisplaySignature.compute(REV, zero));
    }

    @Test
    void signedColorEncodedDistinctly() {
        assertNotEquals(
                sig(REV, entry(new UUID(0, 1), ParticipantKind.PARTY, "a", -1, 10, List.of())),
                sig(REV, entry(new UUID(0, 1), ParticipantKind.PARTY, "a", 0xFFFFFF, 10, List.of())));
    }
}
