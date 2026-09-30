package com.example.chatandroid;

import java.util.ArrayDeque;

/**
 * Paces outgoing ICE candidates so a multi-homed device cannot exceed the server's per-second
 * call-signal budget. One payload leaves per interval; the owner drives polling from the
 * scheduler it already uses, so no thread is created and no candidate is dropped silently.
 */
final class CallSignalPacer {
    /** 20 messages per second — below the server's per-connection call-signal bound. */
    static final long INTERVAL_MS = 50;
    static final int MAX_QUEUED = 128;

    private final ArrayDeque<String> pending = new ArrayDeque<>();
    private final long intervalMs;
    private final int maxQueued;
    private long nextSendAt = Long.MIN_VALUE;

    CallSignalPacer() { this(INTERVAL_MS, MAX_QUEUED); }

    CallSignalPacer(long intervalMs, int maxQueued) {
        if (intervalMs <= 0 || maxQueued <= 0) throw new IllegalArgumentException("节奏参数无效");
        this.intervalMs = intervalMs; this.maxQueued = maxQueued;
    }

    /** Queues one payload; false when it is empty or the bounded queue is already full. */
    boolean enqueue(String payload) {
        if (payload == null || payload.isEmpty() || pending.size() >= maxQueued) return false;
        pending.add(payload);
        return true;
    }

    /** Next payload that may leave at {@code nowMs}, or null while empty or still too early. */
    String poll(long nowMs) {
        if (pending.isEmpty() || nowMs < nextSendAt) return null;
        nextSendAt = nowMs + intervalMs;
        return pending.poll();
    }

    int size() { return pending.size(); }

    void clear() { pending.clear(); nextSendAt = Long.MIN_VALUE; }
}
