package dev.pepsi.questrank.client.ui;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class FirstPaintTrackerTest {

    @Test
    void emitsOnlyOncePerEpochAndRevision() {
        FirstPaintTracker tracker = new FirstPaintTracker();

        tracker.onModelBuilt(7L, 5L);
        assertTrue(tracker.hasPending());
        assertEquals(new FirstPaintTracker.SnapshotKey(7L, 5L), tracker.claimFirstPaint());
        assertFalse(tracker.hasPending());
        assertNull(tracker.claimFirstPaint(), "la segunda llamada no duplica el evento");

        tracker.onModelBuilt(7L, 5L);
        assertFalse(tracker.hasPending(), "la misma (epoch, rev) ya registrada no vuelve a pendiente");

        tracker.onModelBuilt(8L, 5L);
        assertEquals(new FirstPaintTracker.SnapshotKey(8L, 5L), tracker.claimFirstPaint(),
                "el reinicio de servidor puede repetir rev; epoch distingue la identidad");
    }

    @Test
    void latestModelBeforePaintWins() {
        FirstPaintTracker tracker = new FirstPaintTracker();

        tracker.onModelBuilt(7L, 5L);
        tracker.onModelBuilt(7L, 6L);

        assertEquals(new FirstPaintTracker.SnapshotKey(7L, 6L), tracker.claimFirstPaint());
        assertNull(tracker.claimFirstPaint());
    }

    @Test
    void clearingPendingPreventsAStalePaintEvent() {
        FirstPaintTracker tracker = new FirstPaintTracker();

        tracker.onModelBuilt(7L, 5L);
        tracker.clearPending();

        assertFalse(tracker.hasPending());
        assertNull(tracker.claimFirstPaint());
    }

    @Test
    void claimIfCurrentDropsAKeyThatDoesNotMatchTheShownRevision() {
        FirstPaintTracker tracker = new FirstPaintTracker();
        tracker.onModelBuilt(7L, 5L);

        assertNull(tracker.claimIfCurrent(7L, 6L), "no se atribuye a una revisión distinta");
        assertFalse(tracker.hasPending(), "el pendiente obsoleto se descarta");

        tracker.onModelBuilt(7L, 6L);
        assertEquals(new FirstPaintTracker.SnapshotKey(7L, 6L), tracker.claimIfCurrent(7L, 6L));
        assertNull(tracker.claimFirstPaint());
    }

    @Test
    void returningToAnAlreadyLoggedRevisionClearsAStalePending() {
        FirstPaintTracker tracker = new FirstPaintTracker();
        tracker.onModelBuilt(7L, 5L);
        tracker.claimFirstPaint();

        tracker.onModelBuilt(7L, 6L);
        assertTrue(tracker.hasPending());
        tracker.onModelBuilt(7L, 5L);

        assertFalse(tracker.hasPending(), "la revisión ya registrada no deja un pendiente anterior");
    }
}
