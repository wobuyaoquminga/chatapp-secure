package com.example.chatandroid;

/** Bounded range of message positions shown in a conversation. */
final class HistoryWindow {
    static final int PAGE = 80;
    static final int MAX_VISIBLE = 200;
    final int start, end;

    private HistoryWindow(int start, int end) {
        this.start = start;
        this.end = end;
    }

    static HistoryWindow latest(int size) {
        return new HistoryWindow(Math.max(0, size - PAGE), size);
    }

    static HistoryWindow around(int size, int target) {
        if (size <= 0) return latest(0);
        int position = Math.max(0, Math.min(target, size - 1));
        int start = Math.max(0, position - PAGE / 2);
        return new HistoryWindow(start, Math.min(size, start + MAX_VISIBLE));
    }

    HistoryWindow older() {
        int nextStart = Math.max(0, start - PAGE);
        return new HistoryWindow(nextStart, Math.min(end, nextStart + MAX_VISIBLE));
    }

    HistoryWindow newer(int size) {
        int nextEnd = Math.min(size, end + PAGE);
        return new HistoryWindow(Math.max(start, nextEnd - MAX_VISIBLE), nextEnd);
    }

    HistoryWindow retain(int size) {
        int nextStart = Math.min(start, size);
        int nextEnd = Math.min(Math.max(nextStart, end), size);
        return new HistoryWindow(nextStart, nextEnd);
    }

    boolean hasNewer(int size) { return end < size; }
}
