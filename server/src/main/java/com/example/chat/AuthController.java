package com.example.chat;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Map;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.oauth2.jose.jws.MacAlgorithm;
import org.springframework.security.oauth2.jwt.*;
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
        if (c.username() == null || !c.username().matches("[a-z0-9_]{3,32}") || c.password() == null
            || c.password().length() < 8 || c.password().getBytes(StandardCharsets.UTF_8).length > 72)
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "用户名限 3–32 位小写字母/数字/下划线；密码至少 8 字符、最多 72 UTF-8 字节");
    }
    @PostMapping("/register")
    @ResponseStatus(HttpStatus.CREATED)
    public Map<String, String> register(@RequestBody Credentials c) {
        validate(c);
        try { db.update("INSERT INTO app_users(username,password_hash) VALUES (?,?)", c.username(), passwords.encode(c.password())); }
        catch (DuplicateKeyException e) { throw new ResponseStatusException(HttpStatus.CONFLICT, "用户名已存在"); }
        return token(c.username());
    }
    @PostMapping("/login")
    public Map<String, String> login(@RequestBody Credentials c) {
        validate(c);
        var hashes = db.queryForList("SELECT password_hash FROM app_users WHERE username=?", String.class, c.username());
        boolean valid = passwords.matches(c.password(), hashes.isEmpty() ? dummyHash : hashes.get(0));
        if (!valid || hashes.isEmpty()) throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "用户名或密码错误");
        return token(c.username());
    }
    private Map<String, String> token(String user) {
        Instant now = Instant.now();
        var claims = JwtClaimsSet.builder().issuer("chatapp-secure").subject(user).issuedAt(now).expiresAt(now.plusSeconds(3600)).build();
        String token = encoder.encode(JwtEncoderParameters.from(JwsHeader.with(MacAlgorithm.HS256).build(), claims)).getTokenValue();
        return Map.of("token", token, "username", user);
    }
}
