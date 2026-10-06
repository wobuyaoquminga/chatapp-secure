package com.example.chatandroid;

import java.util.Locale;

/** Client-side media hints; a filename never makes decrypted bytes trusted media. */
final class MediaPayload {
    enum Kind { FILE, IMAGE, VIDEO }

    static String mediaName(String name, String mime, String selectedType) {
        String extension = switch (mime == null ? "" : mime) {
            case "image/jpeg" -> ".jpg";
            case "image/png" -> ".png";
            case "image/gif" -> ".gif";
            case "image/webp" -> ".webp";
            case "video/mp4" -> ".mp4";
            case "video/webm" -> ".webm";
            default -> "";
        };
        Kind expected = "image/*".equals(selectedType) ? Kind.IMAGE : Kind.VIDEO;
        if (!"media".equals(selectedType) && kind(name) != Kind.FILE && kind(name) != expected)
            throw new IllegalArgumentException("所选媒体类型不符");
        if (kind(name) != Kind.FILE) return name;
        if (extension.isEmpty() || !"media".equals(selectedType) && kind("media" + extension) != expected)
            throw new IllegalArgumentException("媒体格式不支持，可使用文件入口发送");
        // System photo providers may expose numeric names without any extension.
        String base = name.codePointCount(0, name.length()) > 120
                ? name.substring(0, name.offsetByCodePoints(0, 120)) : name;
        return FilePayload.safeName(base + extension);
    }

    static Kind kind(String name) {
        String lower = name.toLowerCase(Locale.ROOT);
        if (lower.matches(".*\\.(jpg|jpeg|png|gif|webp)$")) return Kind.IMAGE;
        if (lower.matches(".*\\.(mp4|webm)$")) return Kind.VIDEO;
        return Kind.FILE;
    }

    static boolean matches(String name, byte[] bytes) {
        String lower = name.toLowerCase(Locale.ROOT);
        if (lower.endsWith(".jpg") || lower.endsWith(".jpeg"))
            return bytes.length >= 4 && u(bytes, 0) == 0xff && u(bytes, 1) == 0xd8
                    && u(bytes, 2) == 0xff && u(bytes, bytes.length - 2) == 0xff
                    && u(bytes, bytes.length - 1) == 0xd9;
        if (lower.endsWith(".png")) return bytes.length >= 24
                && prefix(bytes, new int[]{137,80,78,71,13,10,26,10})
                && u(bytes, 12) == 'I' && u(bytes, 13) == 'H' && u(bytes, 14) == 'D' && u(bytes, 15) == 'R';
        if (lower.endsWith(".gif")) return bytes.length >= 10
                && (ascii(bytes, 0, "GIF87a") || ascii(bytes, 0, "GIF89a"));
        if (lower.endsWith(".webp")) return bytes.length >= 16 && ascii(bytes, 0, "RIFF")
                && ascii(bytes, 8, "WEBP") && (ascii(bytes, 12, "VP8 ")
                || ascii(bytes, 12, "VP8L") || ascii(bytes, 12, "VP8X"));
        if (lower.endsWith(".mp4")) return bytes.length >= 16 && ascii(bytes, 4, "ftyp")
                && (ascii(bytes, 8, "isom") || ascii(bytes, 8, "iso2")
                || ascii(bytes, 8, "mp41") || ascii(bytes, 8, "mp42")
                || ascii(bytes, 8, "avc1") || ascii(bytes, 8, "M4V "));
        if (lower.endsWith(".webm")) return bytes.length >= 16
                && prefix(bytes, new int[]{0x1a,0x45,0xdf,0xa3})
                && contains(bytes, "webm", Math.min(bytes.length, 4096));
        return false;
    }

    static int imageSampleSize(int width, int height) {
        if (width <= 0 || height <= 0 || width > 12000 || height > 12000
                || (long) width * height > 50000000L) return 0;
        int sample = 1;
        while (width / sample > 2048 || height / sample > 2048) sample *= 2;
        return sample;
    }

    private static int u(byte[] bytes, int index) { return bytes[index] & 255; }
    private static boolean prefix(byte[] bytes, int[] expected) {
        for (int i = 0; i < expected.length; i++) if (u(bytes, i) != expected[i]) return false;
        return true;
    }
    private static boolean ascii(byte[] bytes, int offset, String value) {
        if (bytes.length < offset + value.length()) return false;
        for (int i = 0; i < value.length(); i++) if (u(bytes, offset + i) != value.charAt(i)) return false;
        return true;
    }
    private static boolean contains(byte[] bytes, String value, int limit) {
        for (int i = 0; i <= limit - value.length(); i++) if (ascii(bytes, i, value)) return true;
        return false;
    }
}
