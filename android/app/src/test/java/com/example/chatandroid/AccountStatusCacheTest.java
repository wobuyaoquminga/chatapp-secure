package com.example.chatandroid;

import org.json.JSONObject;
import org.junit.Test;

import static org.junit.Assert.assertEquals;

public final class AccountStatusCacheTest {
    @Test public void usesServerClockForOfflineDeadline() throws Exception {
        JSONObject response = new JSONObject()
                .put("serverTime", "2026-10-03T10:00:00Z")
                .put("lastConnectedAt", "2026-10-03T09:59:00Z")
                .put("accountExpiresAt", "2026-10-10T10:00:00Z")
                .put("retentionDays", 7);
        long phoneClock = 1_000_000L;
        AccountStatusCache.Deadline deadline = AccountStatusCache.fromServer(response, phoneClock);
        assertEquals(7L * 86400000L, deadline.remainingMillis(phoneClock));
        assertEquals("2026-10-03T09:59:00Z", deadline.lastConnectedAt);
    }

    @Test(expected = Exception.class) public void rejectsWrongRetention() throws Exception {
        AccountStatusCache.fromServer(new JSONObject()
                .put("serverTime", "2026-10-03T10:00:00Z")
                .put("lastConnectedAt", "2026-10-03T10:00:00Z")
                .put("accountExpiresAt", "2026-10-10T10:00:00Z")
                .put("retentionDays", 30), 0);
    }
}
