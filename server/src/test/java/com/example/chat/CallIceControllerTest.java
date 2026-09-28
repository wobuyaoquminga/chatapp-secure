package com.example.chat;

import java.time.Instant;
import org.junit.jupiter.api.Test;
import org.springframework.security.oauth2.jwt.Jwt;
import static org.assertj.core.api.Assertions.*;

class CallIceControllerTest {
    @Test void coturnHmacSha1MatchesFixedVector() {
        assertThat(CallIceController.turnCredential("test-secret", "1700000600:alice"))
            .isEqualTo("8/V0EGCxarQgfHU3LEN7qNsx46Q=");
    }

    @Test void turnCredentialsUseCoturnRestTimestampAndSharedSecret() {
        var controller = new CallIceController("stun:stun.example.org:3478",
            "turn:turn.example.org:3478?transport=udp,turn:turn.example.org:3478?transport=tcp", "test-secret", 600);
        var jwt = Jwt.withTokenValue("test").header("alg", "none").subject("alice")
            .issuedAt(Instant.now()).expiresAt(Instant.now().plusSeconds(3600)).build();
        long before = Instant.now().getEpochSecond();
        var ice = controller.ice(jwt);
        var servers = (java.util.List<?>) ice.get("iceServers");
        assertThat(servers).hasSize(2);
        var turn = (java.util.Map<?, ?>) servers.get(1);
        assertThat(turn.get("urls")).isEqualTo(java.util.List.of(
            "turn:turn.example.org:3478?transport=udp", "turn:turn.example.org:3478?transport=tcp"));
        String username = (String) turn.get("username");
        long expiry = Long.parseLong(username.substring(0, username.indexOf(':')));
        assertThat(username).endsWith(":alice");
        assertThat(expiry).isBetween(before + 600, Instant.now().getEpochSecond() + 600);
        assertThat(turn.get("credential")).isEqualTo(CallIceController.turnCredential("test-secret", username));
    }

    @Test void turnConfigurationNeedsSecretAndBoundedTtl() {
        assertThatThrownBy(() -> new CallIceController("", "turn:host:3478", "", 600))
            .isInstanceOf(IllegalStateException.class).hasMessageContaining("CHAT_TURN_SHARED_SECRET");
        assertThatThrownBy(() -> new CallIceController("", "", "", 59))
            .isInstanceOf(IllegalStateException.class).hasMessageContaining("CHAT_TURN_TTL_SECONDS");
    }
}
