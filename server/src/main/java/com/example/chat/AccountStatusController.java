package com.example.chat;

import java.time.OffsetDateTime;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

@RestController
public class AccountStatusController {
    private static final int RETENTION_DAYS = 7;
    private final JdbcTemplate db;

    public AccountStatusController(JdbcTemplate db) { this.db = db; }

    public record AccountStatus(String serverTime, String lastConnectedAt,
                                String accountExpiresAt, int retentionDays) {}

    @GetMapping("/api/account/status")
    public AccountStatus status(@AuthenticationPrincipal Jwt jwt) {
        // The account generation is checked again with the read to cover deletion or
        // recreation between JWT validation and this query. Reading never renews it.
        return db.query("SELECT last_connected_at, CURRENT_TIMESTAMP AS server_time FROM app_users "
                + "WHERE username=? AND account_id=?",
            (rs, row) -> {
                OffsetDateTime connected = rs.getObject("last_connected_at", OffsetDateTime.class);
                OffsetDateTime now = rs.getObject("server_time", OffsetDateTime.class);
                return new AccountStatus(now.toInstant().toString(), connected.toInstant().toString(),
                    connected.plusDays(RETENTION_DAYS).toInstant().toString(), RETENTION_DAYS);
            }, jwt.getSubject(), jwt.getClaimAsString("account_id"))
            .stream().findFirst().orElseThrow(() ->
                new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Account no longer exists"));
    }
}
