package com.example.chat;

final class Username {
    private Username() {}

    static boolean valid(String name) {
        return name != null && name.length() <= 32
            && name.matches("[\\p{IsHan}a-z0-9_]{2,32}")
            && !name.matches("[a-z0-9_]{2}");
    }
}
