package com.example.chatandroid;

/** Retry delay for successive failed connection attempts. Reset only after ready sync completes. */
final class ReconnectBackoff {
    private static final long BASE_MS = 1000;
    private static final long MAX_MS = 30000;
    private int failures;

    long nextDelayMillis(double jitter) {
        if (Double.isNaN(jitter) || jitter < 0 || jitter >= 1)
            throw new IllegalArgumentException("jitter must be in [0, 1)");
        long exponential = BASE_MS << Math.min(failures, 5);
        if (failures < 5) failures++;
        return Math.min(MAX_MS, Math.round(exponential * (0.75 + jitter * 0.5)));
    }

    void reset() { failures = 0; }
}
