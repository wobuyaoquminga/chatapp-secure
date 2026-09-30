package com.example.chatandroid;

import org.junit.Test;
import java.util.ArrayList;
import java.util.List;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

/** A multi-homed device must not exceed the server's per-second call-signal budget. */
public class CallSignalPacerTest {
    @Test public void releasesOnePayloadPerIntervalAndKeepsTheRestQueued() {
        CallSignalPacer pacer = new CallSignalPacer(50, 128);
        for (int i = 0; i < 30; i++) assertTrue(pacer.enqueue("candidate-" + i));
        assertEquals(30, pacer.size());
        long now = 1000;
        List<String> sent = new ArrayList<>();
        // The first payload leaves immediately, then exactly one per 50 ms interval.
        String first = pacer.poll(now);
        assertNotNull(first);
        sent.add(first);
        assertNull(pacer.poll(now));
        assertNull(pacer.poll(now + 49));
        for (int tick = 1; tick <= 20; tick++) {          // one second of pacing
            String next = pacer.poll(now + tick * 50L);
            if (next != null) sent.add(next);
        }
        assertTrue("released " + sent.size() + " in the first second", sent.size() <= 21);
        for (int tick = 21; tick <= 40; tick++) {         // the burst drains in the second second
            String next = pacer.poll(now + tick * 50L);
            if (next != null) sent.add(next);
        }
        assertEquals(30, sent.size());
        for (int i = 0; i < 30; i++) assertEquals("candidate-" + i, sent.get(i));
        assertEquals(0, pacer.size());
        assertNull(pacer.poll(now + 10000));
    }

    @Test public void dropsNothingUntilTheBoundedQueueIsFull() {
        CallSignalPacer pacer = new CallSignalPacer(50, 2);
        assertTrue(pacer.enqueue("a"));
        assertTrue(pacer.enqueue("b"));
        assertFalse(pacer.enqueue("c"));
        assertFalse(pacer.enqueue(null));
        assertFalse(pacer.enqueue(""));
        assertEquals("a", pacer.poll(0));
        assertTrue(pacer.enqueue("c"));
        assertEquals("b", pacer.poll(50));
        assertEquals("c", pacer.poll(100));
    }

    @Test public void clearStopsPendingWorkAndRejectsInvalidIntervals() {
        CallSignalPacer pacer = new CallSignalPacer(50, 8);
        pacer.enqueue("a");
        pacer.clear();
        assertEquals(0, pacer.size());
        assertNull(pacer.poll(0));
        try { new CallSignalPacer(0, 8); throw new AssertionError("expected rejection"); }
        catch (IllegalArgumentException expected) { }
        try { new CallSignalPacer(50, 0); throw new AssertionError("expected rejection"); }
        catch (IllegalArgumentException expected) { }
    }
}
