package dev.pepsi.questrank.client.ui;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

/** Layout virtualizado (§10.6/§10.7): geometría, scroll y visibilidad sin Minecraft. */
class RankingLayoutTest {

    @Test
    void panelWidthClampsToContract() {
        assertEquals(300, QuestRankTheme.panelWidth(320));
        assertEquals(368, QuestRankTheme.panelWidth(400));
        assertEquals(440, QuestRankTheme.panelWidth(1920));
        assertEquals(272, QuestRankTheme.panelWidth(280), "no se sale de pantallas reducidas");
        assertEquals(92, QuestRankTheme.panelWidth(100));
    }

    @Test
    void rowHeightsHaveTallerLeader() {
        assertEquals(40, QuestRankTheme.rowHeight(0));
        assertEquals(34, QuestRankTheme.rowHeight(1));
        assertEquals(34, QuestRankTheme.rowHeight(500));
        assertEquals(40 + 34 * 3, RankingLayout.totalHeight(4));
    }

    @Test
    void scrollIsClampedToContent() {
        RankingLayout layout = new RankingLayout(100);
        assertEquals(0, layout.maxScrollPx(1));
        layout.scrollByPx(-50, 1);
        assertEquals(0, layout.scrollOffsetPx());
        layout.scrollByPx(9999, 10);
        assertEquals(RankingLayout.totalHeight(10) - 100, layout.scrollOffsetPx());
    }

    @Test
    void visibleWindowCoversViewport() {
        RankingLayout layout = new RankingLayout(100);
        assertEquals(0, layout.firstVisibleRow(100));
        assertEquals(2, layout.lastVisibleRow(100));
        layout.scrollByRows(3, 100);
        assertTrue(layout.firstVisibleRow(100) >= 2);
        assertTrue(layout.lastVisibleRow(100) > layout.firstVisibleRow(100));
    }

    @Test
    void bottomScrollShowsLastRow() {
        RankingLayout layout = new RankingLayout(100);
        layout.setScrollOffsetPx(Integer.MAX_VALUE, 100);
        assertEquals(99, layout.lastVisibleRow(100));
        assertTrue(layout.firstVisibleRow(100) < 99);
        layout.ensureVisible(99, 100);
        assertEquals(99, layout.lastVisibleRow(100));
        assertTrue(layout.rowY(99) + QuestRankTheme.rowHeight(99) <= 100);
    }

    @Test
    void ensureVisibleScrollsToSelectedRow() {
        RankingLayout layout = new RankingLayout(100);
        layout.ensureVisible(50, 100);
        assertTrue(layout.rowY(50) >= 0);
        assertTrue(layout.rowY(50) + QuestRankTheme.rowHeight(50) <= 100);
        layout.ensureVisible(0, 100);
        assertEquals(0, layout.scrollOffsetPx());
    }

    @Test
    void hundredRowsOnlyRenderVisibleWindow() {
        RankingLayout layout = new RankingLayout(340);
        int first = layout.firstVisibleRow(100);
        int last = layout.lastVisibleRow(100);
        int visible = last - first + 1;
        assertTrue(visible <= 12, "ventana acotada por el viewport, no por el total");
        assertEquals(0, first);
    }
}
