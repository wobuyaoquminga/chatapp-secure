package com.example.chat;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.oauth2.jose.jws.MacAlgorithm;
import org.springframework.security.oauth2.jwt.*;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

@RestController
@RequestMapping("/api/auth")
public class AuthController {
    private final JdbcTemplate db;
    private final PasswordEncoder passwords;
    private final JwtEncoder encoder;
    private final String dummyHash;
    private final RefreshTokenStore refresh;
    private final AuthAttempts attempts;
    private final long accessSeconds;
    @Value("${chat.trust-loopback-proxy:false}")
    private boolean trustLoopbackProxy;
    private String clientAddress(HttpServletRequest request) {
        String remote=request.getRemoteAddr();
        if (trustLoopbackProxy && ("127.0.0.1".equals(remote) || "::1".equals(remote)
                || "0:0:0:0:0:0:0:1".equals(remote))) {
            String real=request.getHeader("X-Real-IP");
            // Only a local proxy which overwrites this header is trusted. Never trust X-Forwarded-For.
            if (real!=null && real.length()<=45 && real.matches("[0-9a-fA-F:.]+")) {
                try { return java.net.InetAddress.getByName(real).getHostAddress(); }
                catch (java.net.UnknownHostException ignored) { }
            }
        }
        return remote;
    }
    public AuthController(JdbcTemplate db, PasswordEncoder passwords, JwtEncoder encoder,
                          RefreshTokenStore refresh, AuthAttempts attempts,
                          @Value("${chat.access-token-seconds:3600}") long accessSeconds) {
        this.db = db; this.passwords = passwords; this.encoder = encoder;
        this.refresh=refresh;this.attempts=attempts;
        if(accessSeconds<2 || accessSeconds>86400)throw new IllegalArgumentException("Invalid access token lifetime");
        this.accessSeconds=accessSeconds;
        dummyHash = passwords.encode("dummy-password-for-timing");
    }
    public record Credentials(String username, String password) {}
    private void validate(Credentials c) {
        if (c == null || !Username.valid(c.username()) || c.password() == null
            || c.password().length() < 8 || c.password().getBytes(StandardCharsets.UTF_8).length > 72)
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "用户名可用 2–32 位汉字/小写字母/数字/下划线；密码至少 8 字符、最多 72 UTF-8 字节");
    }
    @PostMapping("/register")
    @ResponseStatus(HttpStatus.CREATED)
    @Transactional
    public Map<String, String> register(@RequestBody Credentials c, HttpServletRequest request) {
        attempts.admit(clientAddress(request),null);
        validate(c);
        String accountId = UUID.randomUUID().toString();
        try { db.update("INSERT INTO app_users(username,password_hash,account_id) VALUES (?,?,?)", c.username(), passwords.encode(c.password()), accountId); }
        catch (DuplicateKeyException e) { throw new ResponseStatusException(HttpStatus.CONFLICT, "用户名已存在"); }
        return token(c.username(), accountId);
    }
    private record Account(String passwordHash, String accountId) {}
    @PostMapping("/login")
    @Transactional
    public Map<String, String> login(@RequestBody Credentials c, HttpServletRequest request) {
        validate(c);
        String ip=clientAddress(request);attempts.admit(ip,c.username());
        var accounts = db.query("SELECT password_hash,account_id FROM app_users WHERE username=? FOR UPDATE",
            (r, i) -> new Account(r.getString(1), r.getString(2)), c.username());
        boolean valid = passwords.matches(c.password(), accounts.isEmpty() ? dummyHash : accounts.get(0).passwordHash());
        if (!valid || accounts.isEmpty()) { attempts.failure(ip,c.username());throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "用户名或密码错误"); }
        attempts.success(ip,c.username());
        return token(c.username(), accounts.get(0).accountId());
    }
    public Map<String, String> token(String user, String accountId) {
        return token(user,accountId,refresh.issue(user,accountId));
    }
    public record RefreshRequest(String refreshToken) {}
    @PostMapping("/refresh")
    @Transactional
    public Map<String,String> refresh(@RequestBody RefreshRequest body,HttpServletRequest request) {
        attempts.admit(clientAddress(request),null);
        RefreshTokenStore.Grant grant=refresh.rotate(body==null?null:body.refreshToken());
        return token(grant.username(),grant.accountId(),grant.refreshToken());
    }
    private Map<String,String> token(String user,String accountId,String refreshToken) {
        Instant now = Instant.now();
        var claims = JwtClaimsSet.builder().issuer("chat").subject(user).claim("account_id", accountId)
            .id(UUID.randomUUID().toString()).issuedAt(now).expiresAt(now.plusSeconds(accessSeconds)).build();
        String token = encoder.encode(JwtEncoderParameters.from(JwsHeader.with(MacAlgorithm.HS256).build(), claims)).getTokenValue();
        return Map.of("token", token, "username", user, "accountId", accountId,
            "refreshToken",refreshToken,"expiresAt",now.plusSeconds(accessSeconds).toString());
    }
}
