package dev.pepsi.questrank.client.ui;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.pepsi.questrank.core.model.RankingEntry;
import dev.pepsi.questrank.net.RankingSnapshotPayload;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/** Modelo visual y estados (§10.2–§10.5): porcentaje, C/N, truncado, N=0, caras y tooltip. */
class RankingViewModelTest {

    @Test
    void scoreAndPercentageUseCoreRule() {
        assertEquals("1 / 8", RankingViewModel.scoreText(1, 8));
        assertEquals("13%", RankingViewModel.percentageText(1, 8));
        assertEquals("50%", RankingViewModel.percentageText(99, 200));
        assertEquals("49%", RankingViewModel.percentageText(99, 201));
        assertEquals("\u2014", RankingViewModel.percentageText(0, 0));
    }

    @Test
    void progressAtObservedRankingValues() {
        assertEquals("0%", RankingViewModel.percentageText(4, 2119));
        assertEquals(0, RankingViewModel.barFill(4, 2119, 400));
        assertEquals("47%", RankingViewModel.percentageText(1000, 2119));
        assertEquals(188, RankingViewModel.barFill(1000, 2119, 400));
        assertEquals("100%", RankingViewModel.percentageText(2119, 2119));
        assertEquals(400, RankingViewModel.barFill(2119, 2119, 400));
        assertEquals("0%", RankingViewModel.percentageText(0, 2119));
        assertEquals(0, RankingViewModel.barFill(0, 2119, 400));
        assertEquals("\u2014", RankingViewModel.percentageText(0, 0));
        assertEquals(0, RankingViewModel.barFill(0, 0, 400));
    }

    @Test
    void invalidScoresDoNotThrowAndShowDash() {
        assertEquals("\u2014", RankingViewModel.percentageText(11L, 10L));
        assertEquals("\u2014", RankingViewModel.percentageText(-1L, 10L));
        assertEquals("\u2014", RankingViewModel.percentageText(0L, -1L));
        assertEquals("\u2014", RankingViewModel.percentageText(0L, Long.MAX_VALUE));
        assertFalse(RankingViewModel.isScoreValid(11L, 10L));
        assertTrue(RankingViewModel.isScoreValid(0L, 0L));
    }

    @Test
    void barFillIsSafeAndProportional() {
        assertEquals(0, RankingViewModel.barFill(0L, 0L, 100));
        assertEquals(0, RankingViewModel.barFill(5L, 10L, 0));
        assertEquals(0, RankingViewModel.barFill(5L, 10L, -1));
        assertEquals(0, RankingViewModel.barFill(11L, 10L, 100));
        assertEquals(0, RankingViewModel.barFill(0L, Long.MAX_VALUE, 100));
        assertEquals(50, RankingViewModel.barFill(5L, 10L, 100));
        assertEquals(100, RankingViewModel.barFill(10L, 10L, 100));
        assertEquals(33, RankingViewModel.barFill(1L, 3L, 100));
        assertEquals(Integer.MAX_VALUE, RankingViewModel.barFill(
                RankingEntry.MAX_SAFE_TOTAL, RankingEntry.MAX_SAFE_TOTAL, Integer.MAX_VALUE));
    }

    @Test
    void rowsCopyServerValuesAndCapFaces() {
        RankingSnapshotPayload snapshot = new RankingSnapshotPayload(0L, 1L, 1L, 1L, 10L, 1, false, List.of(
                new RankingSnapshotPayload.Entry(new UUID(1, 1), 0, "Team", 0xFFFFFF, 7L, 6, true,
                        List.of(member(1), member(2), member(3), member(4), member(5)))));
        RankingViewModel model = RankingViewModel.of(snapshot);
        RankingRow row = model.rows().get(0);
        assertEquals("Team", row.name());
        assertTrue(row.party());
        assertEquals(7L, row.completed());
        assertEquals(10L, row.total());
        assertEquals(6, row.memberCount());
        assertEquals(5, row.sentMembers(), "el tooltip conserva todos los miembros recibidos");
        assertEquals(5, row.members().size());
        assertEquals(1, row.hiddenMembers(), "memberCount menos los recibidos (§10.4)");
        assertEquals(4, row.faces().size(), "hasta 4 caras (§10.4)");
        assertEquals(2, row.extraMembers(), "indicador +N respecto a las caras visibles");
        assertEquals("70%", RankingViewModel.percentageText(row.completed(), row.total()));
    }

    @Test
    void malformedMemberCountsDoNotOverflow() {
        RankingSnapshotPayload low = new RankingSnapshotPayload(0L, 1L, 1L, 1L, 10L, 1, false, List.of(
                new RankingSnapshotPayload.Entry(new UUID(7, 7), 0, "Bad", 0, 0L, Integer.MIN_VALUE, false,
                        List.of(member(1)))));
        RankingRow lowRow = RankingViewModel.of(low).rows().get(0);
        assertEquals(0, lowRow.hiddenMembers());
        assertEquals(0, lowRow.extraMembers());

        List<RankingSnapshotPayload.Member> members = new ArrayList<>();
        for (int i = 1; i <= 12; i++) {
            members.add(member(i));
        }
        RankingSnapshotPayload high = new RankingSnapshotPayload(0L, 1L, 1L, 1L, 10L, 1, false, List.of(
                new RankingSnapshotPayload.Entry(new UUID(8, 8), 1, "Many", 0, 0L, Integer.MAX_VALUE, true, members)));
        RankingRow highRow = RankingViewModel.of(high).rows().get(0);
        assertEquals(Integer.MAX_VALUE - 12, highRow.hiddenMembers());
        assertEquals(Integer.MAX_VALUE - 4, highRow.extraMembers());
    }

    @Test
    void tooltipKeepsAllSentMembersWhenMoreThanFour() {
        List<RankingSnapshotPayload.Member> members = new ArrayList<>();
        for (int i = 1; i <= 12; i++) {
            members.add(member(i));
        }
        RankingSnapshotPayload snapshot = new RankingSnapshotPayload(0L, 1L, 1L, 1L, 10L, 1, false, List.of(
                new RankingSnapshotPayload.Entry(new UUID(2, 2), 1, "Solo", 0, 3L, 20, true, members)));
        RankingRow row = RankingViewModel.of(snapshot).rows().get(0);
        assertFalse(row.party());
        assertEquals(12, row.members().size());
        assertEquals(4, row.faces().size());
        assertEquals(8, row.hiddenMembers());
        assertEquals(16, row.extraMembers());
    }

    @Test
    void emptyCatalogIsRecognised() {
        RankingSnapshotPayload snapshot = new RankingSnapshotPayload(0L, 1L, 1L, 1L, 0L, 0, false, List.of());
        RankingViewModel model = RankingViewModel.of(snapshot);
        assertEquals(0L, model.total());
        assertTrue(model.rows().isEmpty());
    }

    @Test
    void truncationFlagIsExposed() {
        RankingSnapshotPayload snapshot = new RankingSnapshotPayload(0L, 1L, 1L, 1L, 10L, 600, true, List.of());
        assertTrue(RankingViewModel.of(snapshot).participantsTruncated());
        assertFalse(RankingViewModel.of(new RankingSnapshotPayload(0L, 1L, 1L, 1L, 10L, 2, false, List.of()))
                .participantsTruncated());
    }

    @Test
    void kindKeysMatchContractGrammar() {
        assertEquals("questrank.kind.party", RankingViewModel.kindKey(true));
        assertEquals("questrank.kind.player", RankingViewModel.kindKey(false));
        assertEquals("team", RankingViewModel.normalizeName("TeAm"));
    }

    private static RankingSnapshotPayload.Member member(int index) {
        return new RankingSnapshotPayload.Member(new UUID(0, index), "m" + index, index % 2 == 0);
    }
}
