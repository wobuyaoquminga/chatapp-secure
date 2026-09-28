package com.example.chatandroid;

import static org.junit.Assert.*;
import org.junit.Test;

public class HistoryWindowTest {
    @Test public void jumpNearBeginningNeverCreatesUnboundedRange() {
        HistoryWindow window = HistoryWindow.around(50_000, 5);
        assertEquals(0, window.start);
        assertEquals(200, window.end);
        assertTrue(window.start <= 5 && 5 < window.end);
    }

    @Test public void pagingKeepsRangeBoundedAndCanReturnToLatest() {
        HistoryWindow window = HistoryWindow.latest(50_000);
        assertEquals(49_920, window.start);
        for (int i = 0; i < 1000; i++) {
            window = window.older();
            assertTrue(window.end - window.start <= HistoryWindow.MAX_VISIBLE);
        }
        for (int i = 0; i < 1000; i++) {
            window = window.newer(50_000);
            assertTrue(window.end - window.start <= HistoryWindow.MAX_VISIBLE);
        }
        assertEquals(50_000, window.end);
        assertEquals(49_800, window.start);
        window = HistoryWindow.latest(50_000);
        assertEquals(49_920, window.start);
    }

    @Test public void incomingMessagesDoNotMoveAWindowBeingRead() {
        HistoryWindow reading = HistoryWindow.around(1000, 100);
        assertTrue(reading.hasNewer(1000)); // Even if the scroll is at this window's bottom.
        HistoryWindow afterAppend = reading.retain(1100);
        assertEquals(reading.start, afterAppend.start);
        assertEquals(reading.end, afterAppend.end);
        assertTrue(afterAppend.hasNewer(1100));
        assertFalse(HistoryWindow.latest(1100).hasNewer(1100));
        assertEquals(1020, HistoryWindow.latest(1100).start);
    }
}
