package com.example.chatandroid;

/** Renew five minutes before expiry, or halfway through a shorter access-token lifetime. */
final class AuthRenewal {
    private AuthRenewal() { }
    static long delay(long remainingMillis, int retry) {
        return retry == 0 ? Math.max(100, remainingMillis - Math.min(300000, Math.max(100, remainingMillis / 2)))
                : Math.min(1000L << Math.min(2, Math.max(0, retry - 1)), Math.max(100, remainingMillis / 2));
    }
}
