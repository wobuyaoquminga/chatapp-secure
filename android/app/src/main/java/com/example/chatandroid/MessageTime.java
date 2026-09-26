package com.example.chatandroid;

import java.time.Clock;
import java.time.DateTimeException;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.Locale;

/** Formats the original encrypted message timestamp in the device time zone. */
final class MessageTime {
    private MessageTime() { }

    static String format(String createdAt) {
        return format(createdAt, Clock.systemDefaultZone());
    }

    static String format(String createdAt, Clock clock) {
        if (createdAt == null || createdAt.trim().isEmpty()) return "时间未知";
        try {
            ZonedDateTime sent = Instant.parse(createdAt.trim()).atZone(clock.getZone());
            LocalDate today = LocalDate.now(clock);
            String pattern = sent.toLocalDate().equals(today) ? "HH:mm"
                    : sent.getYear() == today.getYear() ? "MM-dd HH:mm" : "yyyy-MM-dd HH:mm";
            return sent.format(DateTimeFormatter.ofPattern(pattern, Locale.ROOT));
        } catch (DateTimeException e) {
            return "时间未知";
        }
    }
}
