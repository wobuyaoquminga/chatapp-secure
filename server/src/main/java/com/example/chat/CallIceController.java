package com.example.chat;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class CallIceController {
    private final List<String> stunUrls;
    private final List<String> turnUrls;
    private final String turnSecret;
    private final long ttlSeconds;

    public CallIceController(@Value("${chat.stun-urls}") String stunUrls,
                             @Value("${chat.turn-urls}") String turnUrls,
                             @Value("${chat.turn-shared-secret}") String turnSecret,
                             @Value("${chat.turn-ttl-seconds}") long ttlSeconds) {
        this.stunUrls = urls(stunUrls, "stun:", "stuns:");
        this.turnUrls = urls(turnUrls, "turn:", "turns:");
        if (!this.turnUrls.isEmpty() && turnSecret.isBlank())
            throw new IllegalStateException("CHAT_TURN_SHARED_SECRET is required when CHAT_TURN_URLS is set");
        if (ttlSeconds < 60 || ttlSeconds > 86400)
            throw new IllegalStateException("CHAT_TURN_TTL_SECONDS must be between 60 and 86400");
        this.turnSecret = turnSecret;
        this.ttlSeconds = ttlSeconds;
    }

    private static List<String> urls(String configured, String... schemes) {
        if (configured.isBlank()) return List.of();
        return Arrays.stream(configured.split(",", -1)).map(String::trim).map(url -> {
            if (url.isEmpty() || url.length() > 512 || url.chars().anyMatch(Character::isWhitespace)
                || Arrays.stream(schemes).noneMatch(url::startsWith))
                throw new IllegalStateException("Invalid ICE server URL: " + url);
            return url;
        }).toList();
    }

    @GetMapping("/api/calls/ice")
    public Map<String, Object> ice(@AuthenticationPrincipal Jwt jwt) {
        var servers = new java.util.ArrayList<Map<String, Object>>();
        if (!stunUrls.isEmpty()) servers.add(Map.of("urls", stunUrls));
        if (!turnUrls.isEmpty()) {
            String username = (Instant.now().getEpochSecond() + ttlSeconds) + ":" + jwt.getSubject();
            servers.add(Map.of("urls", turnUrls, "username", username,
                "credential", turnCredential(turnSecret, username)));
        }
        return Map.of("iceServers", servers);
    }

    static String turnCredential(String secret, String username) {
        try {
            Mac mac = Mac.getInstance("HmacSHA1");
            mac.init(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), "HmacSHA1"));
            return java.util.Base64.getEncoder().encodeToString(mac.doFinal(username.getBytes(StandardCharsets.UTF_8)));
        } catch (java.security.GeneralSecurityException e) {
            throw new IllegalStateException("Could not generate TURN credentials", e);
        }
    }
}
