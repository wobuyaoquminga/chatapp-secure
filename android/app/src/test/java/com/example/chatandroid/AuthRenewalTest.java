package com.example.chatandroid;

import org.junit.Test;
import static org.junit.Assert.*;

public class AuthRenewalTest {
    @Test public void longLifetimeRenewsFiveMinutesEarly() { assertEquals(10500000, AuthRenewal.delay(10800000, 0)); }
    @Test public void shortLifetimeRenewsHalfwayRatherThanBusyLooping() {
        assertEquals(30000, AuthRenewal.delay(60000, 0));
        assertEquals(3000, AuthRenewal.delay(6000, 0));
    }
    @Test public void retriesAreBoundedAndConstrainedByRemainingLifetime() {
        assertEquals(1000, AuthRenewal.delay(60000, 1));
        assertEquals(2000, AuthRenewal.delay(60000, 2));
        assertEquals(4000, AuthRenewal.delay(60000, 3));
        assertEquals(500, AuthRenewal.delay(1000, 3));
    }
}
