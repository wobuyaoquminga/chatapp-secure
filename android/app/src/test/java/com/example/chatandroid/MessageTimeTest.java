package com.example.chatandroid;

import static org.junit.Assert.assertEquals;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import org.junit.Test;

public class MessageTimeTest {
    private final Clock clock = Clock.fixed(Instant.parse("2026-09-26T02:00:00Z"), ZoneId.of("Asia/Shanghai"));

    @Test public void usesDeviceDateAndZoneForToday() {
        assertEquals("00:30", MessageTime.format("2026-09-25T16:30:00Z", clock));
        assertEquals("10:00", MessageTime.format("2026-09-26T10:00:00+08:00", clock));
    }

    @Test public void retainsOriginalHistoricalDateIncludingYear() {
        assertEquals("09-25 23:30", MessageTime.format("2026-09-25T15:30:00Z", clock));
        assertEquals("2025-09-26 10:00", MessageTime.format("2025-09-26T02:00:00Z", clock));
    }

    @Test public void missingAndInvalidTimesAreNeverReplacedWithNow() {
        for (String value : new String[] { null, "", " ", "null", "invalid", "2026-02-30T10:00:00Z" })
            assertEquals("时间未知", MessageTime.format(value, clock));
    }
}
