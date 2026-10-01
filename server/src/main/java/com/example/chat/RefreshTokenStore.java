package com.example.chat;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.Base64;
import java.util.HexFormat;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

/** Opaque rotating credentials; only their SHA-256 hashes live on the server. */
@Component
public class RefreshTokenStore {
    private final JdbcTemplate db;
    private final SecureRandom random = new SecureRandom();
    private final int days;
    public record Grant(String username, String accountId, String refreshToken) {}
    private record Stored(String username, String accountId, Instant expiry) {}
    public RefreshTokenStore(JdbcTemplate db, @Value("${chat.refresh-token-days:30}") int days) {
        if (days < 1 || days > 90) throw new IllegalArgumentException("Invalid refresh credential lifetime");
        this.db = db; this.days = days;
    }
    private String digest(String token) {
        try { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(token.getBytes(StandardCharsets.US_ASCII))); }
        catch (java.security.NoSuchAlgorithmException e) { throw new IllegalStateException("SHA-256 unavailable"); }
    }
    private ResponseStatusException invalid() { return new ResponseStatusException(HttpStatus.UNAUTHORIZED,"登录凭证已失效，请重新登录"); }
    private String insert(String user, String accountId, Instant expiry) {
        byte[] bytes = new byte[32]; random.nextBytes(bytes);
        String token = Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
        db.update("INSERT INTO refresh_tokens(token_hash,username,account_id,created_at,expires_at) VALUES (?,?,?,?,?)",
            digest(token),user,accountId,Timestamp.from(Instant.now()),Timestamp.from(expiry));
        return token;
    }
    @Transactional
    public String issue(String user, String accountId) {
        var current = db.queryForList("SELECT account_id FROM app_users WHERE username=? FOR UPDATE",String.class,user);
        if (current.isEmpty() || !accountId.equals(current.get(0))) throw invalid();
        db.update("DELETE FROM refresh_tokens WHERE username=? AND (account_id<>? OR expires_at<=CURRENT_TIMESTAMP)",user,accountId);
        var existing = db.queryForList("SELECT token_hash FROM refresh_tokens WHERE username=? ORDER BY created_at,token_hash",String.class,user);
        for (int i=0;i<Math.max(0,existing.size()-7);i++) db.update("DELETE FROM refresh_tokens WHERE token_hash=?",existing.get(i));
        return insert(user,accountId,Instant.now().plusSeconds(days*86400L));
    }
    @Transactional
    public Grant rotate(String token) {
        if (token==null || !token.matches("[A-Za-z0-9_-]{43}")) throw invalid();
        String hash=digest(token);
        var candidates=db.query("SELECT username,account_id,expires_at FROM refresh_tokens WHERE token_hash=?",
            (r,n)->new Stored(r.getString(1),r.getString(2),r.getTimestamp(3).toInstant()),hash);
        if (candidates.isEmpty()) throw invalid();
        Stored candidate=candidates.get(0);
        // User first, credential second: matches login/identity reset lock order.
        var current=db.queryForList("SELECT account_id FROM app_users WHERE username=? FOR UPDATE",String.class,candidate.username());
        if (current.isEmpty() || !candidate.accountId().equals(current.get(0))) throw invalid();
        var locked=db.query("SELECT username,account_id,expires_at FROM refresh_tokens WHERE token_hash=? FOR UPDATE",
            (r,n)->new Stored(r.getString(1),r.getString(2),r.getTimestamp(3).toInstant()),hash);
        if (locked.isEmpty() || !Instant.now().isBefore(locked.get(0).expiry())) throw invalid();
        db.update("DELETE FROM refresh_tokens WHERE token_hash=?",hash);
        // Rotations never extend the original absolute lifetime.
        return new Grant(candidate.username(),candidate.accountId(),insert(candidate.username(),candidate.accountId(),locked.get(0).expiry()));
    }
    public void removeExpired() { db.update("DELETE FROM refresh_tokens WHERE expires_at<=CURRENT_TIMESTAMP"); }
}
