package com.example.chatandroid;

import android.content.Context;
import android.content.SharedPreferences;

import org.json.JSONObject;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.Base64;

/** Last server-reported account deadline. It is a local hint, not proof the account still exists. */
final class AccountStatusCache {
    private static final String FILE = "account-deadlines";

    static final class Deadline {
        final long localExpiryMillis;
        final String lastConnectedAt;
        Deadline(long localExpiryMillis, String lastConnectedAt) {
            this.localExpiryMillis = localExpiryMillis;
            this.lastConnectedAt = lastConnectedAt;
        }
        long remainingMillis(long now) { return localExpiryMillis - now; }
    }

    static Deadline fromServer(JSONObject response, long receivedAtMillis) throws Exception {
        if (response.getInt("retentionDays") != 7) throw new Exception("账号保留期不匹配");
        long serverNow = Instant.parse(response.getString("serverTime")).toEpochMilli();
        long expires = Instant.parse(response.getString("accountExpiresAt")).toEpochMilli();
        String last = response.getString("lastConnectedAt");
        Instant.parse(last);
        if (expires < serverNow || expires - serverNow > 8L * 86400000L)
            throw new Exception("账号到期时间无效");
        return new Deadline(receivedAtMillis + expires - serverNow, last);
    }

    static void save(Context context, String server, String user, Deadline deadline) {
        context.getSharedPreferences(FILE, Context.MODE_PRIVATE).edit()
                .putLong(key(server, user) + ".expiry", deadline.localExpiryMillis)
                .putString(key(server, user) + ".connected", deadline.lastConnectedAt).commit();
    }

    static Deadline load(Context context, String server, String user) {
        if (server == null || server.isEmpty() || user == null || user.isEmpty()) return null;
        SharedPreferences prefs = context.getSharedPreferences(FILE, Context.MODE_PRIVATE);
        String prefix = key(server, user);
        if (!prefs.contains(prefix + ".expiry")) return null;
        return new Deadline(prefs.getLong(prefix + ".expiry", 0), prefs.getString(prefix + ".connected", ""));
    }

    private static String key(String server, String user) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256")
                    .digest((server + '\0' + user).getBytes(StandardCharsets.UTF_8));
            return Base64.getUrlEncoder().withoutPadding().encodeToString(digest);
        } catch (Exception impossible) { throw new AssertionError(impossible); }
    }
}
