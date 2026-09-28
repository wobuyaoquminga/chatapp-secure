package com.example.chatandroid;

import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertThrows;

public class ReconnectBackoffTest {
    @Test public void growsWithJitterAndCapsAtThirtySeconds() {
        ReconnectBackoff backoff = new ReconnectBackoff();
        assertEquals(750, backoff.nextDelayMillis(0));
        assertEquals(2000, backoff.nextDelayMillis(0.5));
        assertEquals(5000, backoff.nextDelayMillis(0.999999));
        assertEquals(8000, backoff.nextDelayMillis(0.5));
        assertEquals(16000, backoff.nextDelayMillis(0.5));
        assertEquals(30000, backoff.nextDelayMillis(0.5));
        for (int i = 0; i < 100; i++) assertEquals(30000, backoff.nextDelayMillis(0.999999));
    }

    @Test public void successfulReadySyncRestartsAtInitialDelay() {
        ReconnectBackoff backoff = new ReconnectBackoff();
        backoff.nextDelayMillis(0.5);
        backoff.nextDelayMillis(0.5);
        backoff.reset();
        assertEquals(1000, backoff.nextDelayMillis(0.5));
    }

    @Test public void invalidJitterCannotScheduleUnexpectedDelay() {
        ReconnectBackoff backoff = new ReconnectBackoff();
        assertThrows(IllegalArgumentException.class, () -> backoff.nextDelayMillis(Double.NaN));
        assertThrows(IllegalArgumentException.class, () -> backoff.nextDelayMillis(1));
    }
}
