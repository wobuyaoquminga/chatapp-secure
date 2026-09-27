package com.example.chat;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;
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
    public AuthController(JdbcTemplate db, PasswordEncoder passwords, JwtEncoder encoder) {
        this.db = db; this.passwords = passwords; this.encoder = encoder;
        dummyHash = passwords.encode("dummy-password-for-timing");
    }
    public record Credentials(String username, String password) {}
    private void validate(Credentials c) {
        if (!Username.valid(c.username()) || c.password() == null
            || c.password().length() < 8 || c.password().getBytes(StandardCharsets.UTF_8).length > 72)
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "用户名可用 2–32 位汉字/小写字母/数字/下划线；密码至少 8 字符、最多 72 UTF-8 字节");
    }
    @PostMapping("/register")
    @ResponseStatus(HttpStatus.CREATED)
    public Map<String, String> register(@RequestBody Credentials c) {
        validate(c);
        String accountId = UUID.randomUUID().toString();
        try { db.update("INSERT INTO app_users(username,password_hash,account_id) VALUES (?,?,?)", c.username(), passwords.encode(c.password()), accountId); }
        catch (DuplicateKeyException e) { throw new ResponseStatusException(HttpStatus.CONFLICT, "用户名已存在"); }
        return token(c.username(), accountId);
    }
    private record Account(String passwordHash, String accountId) {}
    @PostMapping("/login")
    @Transactional
    public Map<String, String> login(@RequestBody Credentials c) {
        validate(c);
        var accounts = db.query("SELECT password_hash,account_id FROM app_users WHERE username=? FOR UPDATE",
            (r, i) -> new Account(r.getString(1), r.getString(2)), c.username());
        boolean valid = passwords.matches(c.password(), accounts.isEmpty() ? dummyHash : accounts.get(0).passwordHash());
        if (!valid || accounts.isEmpty()) throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "用户名或密码错误");
        return token(c.username(), accounts.get(0).accountId());
    }
    public Map<String, String> token(String user, String accountId) {
        Instant now = Instant.now();
        var claims = JwtClaimsSet.builder().issuer("chat").subject(user).claim("account_id", accountId)
            .issuedAt(now).expiresAt(now.plusSeconds(3600)).build();
        String token = encoder.encode(JwtEncoderParameters.from(JwsHeader.with(MacAlgorithm.HS256).build(), claims)).getTokenValue();
        return Map.of("token", token, "username", user, "accountId", accountId);
    }
}
