package com.example.chatandroid;

import java.net.URLEncoder;
import java.io.UnsupportedEncodingException;

final class Usernames {
    private Usernames() { }

    static boolean valid(String value) {
        if (value == null || value.length() < 2 || value.length() > 32
                || !value.matches("[\\p{IsHan}a-z0-9_]+")) return false;
        return true;
    }

    static String path(String value) {
        try {
            return URLEncoder.encode(value, "UTF-8").replace("+", "%20");
        } catch (UnsupportedEncodingException impossible) {
            throw new AssertionError(impossible);
        }
    }
}
