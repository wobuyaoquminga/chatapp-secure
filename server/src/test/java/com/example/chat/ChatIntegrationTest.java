package com.example.chat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.WebSocket;
import java.time.Duration;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.*;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.http.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.oauth2.jose.jws.MacAlgorithm;
import org.springframework.security.oauth2.jwt.*;
import static org.assertj.core.api.Assertions.*;

@SpringBootTest(webEnvironment=SpringBootTest.WebEnvironment.RANDOM_PORT, properties={
    "spring.datasource.url=jdbc:h2:mem:chat-test;DB_CLOSE_DELAY=-1", "chat.jwt-secret=test-only-secret-at-least-thirty-two-bytes"})
class ChatIntegrationTest {
    @LocalServerPort int port;
    @Autowired TestRestTemplate rest;
    @Autowired ObjectMapper json;
    @Autowired JdbcTemplate db;
    @Autowired JwtEncoder encoder;
    record Account(String name, String token) {}
    Account account() {
        String name = "u" + UUID.randomUUID().toString().replace("-", "").substring(0, 15);
        var response = rest.postForEntity("/api/auth/register", Map.of("username", name, "password", "password123"), JsonNode.class);
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        Account a = new Account(name, response.getBody().path("token").asText());
        var headers = new HttpHeaders(); headers.setBearerAuth(a.token());
        var b = Map.of("identityKey", Base64.getEncoder().encodeToString(new byte[33]), "registrationId", 10,
            "signedPreKey", Map.of("id", 1, "publicKey", Base64.getEncoder().encodeToString(new byte[33]), "signature", Base64.getEncoder().encodeToString(new byte[64])), "preKeys", List.of());
        assertThat(rest.exchange("/api/keys", HttpMethod.PUT, new HttpEntity<>(b, headers), String.class).getStatusCode()).isEqualTo(HttpStatus.OK);
        return a;
    }
    HttpEntity<Void> auth(Account a) {
        var h = new HttpHeaders(); h.setBearerAuth(a.token()); return new HttpEntity<>(h);
    }
    JsonNode history(Account a, String peer) {
        return rest.exchange("/api/messages?peer=" + peer, HttpMethod.GET, auth(a), JsonNode.class).getBody();
    }
    String wire(String body) throws Exception {
        return json.writeValueAsString(Map.of("v", 1, "type", 2, "data", Base64.getEncoder().encodeToString(("opaque-test-payload-padding-" + body).getBytes(java.nio.charset.StandardCharsets.UTF_8))));
    }
    String send(Client c, Account recipient, String body, String clientId) throws Exception {
        c.send(Map.of("type", "send", "to", recipient.name(), "ciphertext", wire(body), "clientId", clientId));
        return c.await("accepted").path("message").path("id").asText();
    }
    @Test void registrationLoginAndPasswordHash() {
        var a = account();
        var credentials = Map.of("username", a.name(), "password", "password123");
        assertThat(rest.postForEntity("/api/auth/register", credentials, String.class).getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
        assertThat(rest.postForEntity("/api/auth/login", credentials, String.class).getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(rest.postForEntity("/api/auth/login", Map.of("username", a.name(), "password", "wrongpassword"), String.class).getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
        assertThat(rest.postForEntity("/api/auth/register", Map.of("username", "BAD", "password", "short"), String.class).getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(db.queryForObject("SELECT password_hash FROM app_users WHERE username=?", String.class, a.name())).startsWith("$2").doesNotContain("password123");
    }
    @Test void realtimeBidirectionalAndAck() throws Exception {
        var a = account(); var b = account();
        try (var alice = new Client(a); var bob = new Client(b)) {
            String id = send(alice, b, "你好 Bob 👋", UUID.randomUUID().toString());
            var received = bob.await("message").path("message");
            assertThat(received.path("id").asText()).isEqualTo(id);
            assertThat(received.path("sender").asText()).isEqualTo(a.name());
            assertThat(received.path("ciphertext").asText()).isEqualTo(wire("你好 Bob 👋"));
            bob.send(Map.of("type", "ack", "id", id)); bob.await("ack_ok");
            assertThat(alice.await("delivered").path("id").asText()).isEqualTo(id);
            send(bob, a, "回复 Alice", UUID.randomUUID().toString());
            assertThat(alice.await("message").path("message").path("ciphertext").asText()).isEqualTo(wire("回复 Alice"));
            assertThat(history(a, b.name()).get(1).path("acknowledged").asBoolean()).isTrue();
        }
    }
    @Test void offlineReplayUntilAckAndNoReplayAfterAck() throws Exception {
        var a = account(); var b = account(); String id;
        try (var alice = new Client(a)) { id = send(alice, b, "离线消息", UUID.randomUUID().toString()); }
        try (var bob = new Client(b)) { assertThat(bob.await("message").path("message").path("id").asText()).isEqualTo(id); }
        try (var bob = new Client(b)) {
            assertThat(bob.await("message").path("message").path("id").asText()).isEqualTo(id);
            bob.send(Map.of("type", "ack", "id", id)); bob.await("ack_ok");
        }
        try (var bob = new Client(b)) { assertThat(bob.events.poll(400, TimeUnit.MILLISECONDS)).isNull(); }
        assertThat(history(b, a.name()).get(0).path("acknowledged").asBoolean()).isTrue();
    }
    @Test void duplicateSendIsIdempotentAndConflictingRetryFails() throws Exception {
        var a = account(); var b = account(); String clientId = UUID.randomUUID().toString();
        try (var alice = new Client(a)) {
            String first = send(alice, b, "once", clientId);
            assertThat(send(alice, b, "once", clientId)).isEqualTo(first);
            alice.send(Map.of("type", "send", "to", b.name(), "ciphertext", wire("changed"), "clientId", clientId));
            assertThat(alice.await("error").path("error").asText()).contains("clientId");
        }
        assertThat(history(a, b.name()).size()).isEqualTo(1);
    }
    @Test void thirdPartyCannotReadOrAckAndSenderCannotBeSpoofed() throws Exception {
        var a = account(); var b = account(); var c = account();
        try (var alice = new Client(a); var attacker = new Client(c)) {
            alice.send(Map.of("type", "send", "to", b.name(), "ciphertext", wire("private"), "sender", c.name(), "clientId", UUID.randomUUID().toString()));
            var m = alice.await("accepted").path("message");
            assertThat(m.path("sender").asText()).isEqualTo(a.name());
            attacker.send(Map.of("type", "ack", "id", m.path("id").asText())); attacker.await("error");
            assertThat(history(c, a.name()).size()).isZero();
            assertThat(history(c, b.name()).size()).isZero();
            assertThat(history(a, b.name()).get(0).path("acknowledged").asBoolean()).isFalse();
        }
    }
    @Test void rejectsUnauthenticatedAndInvalidTokens() throws Exception {
        assertThat(rest.getForEntity("/api/messages?peer=alice", String.class).getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
        try (var client = new Client()) {
            client.send(Map.of("type", "send", "body", "unauthenticated"));
            assertThat(client.closed.get(5, TimeUnit.SECONDS)).isEqualTo(1008);
        }
        try (var client = new Client()) {
            client.send(Map.of("type", "auth", "token", "invalid.jwt.token"));
            assertThat(client.closed.get(5, TimeUnit.SECONDS)).isEqualTo(1008);
        }
    }
    @Test void rejectsExpiredTokenAndClosesExistingConnectionOnExpiry() throws Exception {
        var a = account(); Instant now = Instant.now();
        try (var client = new Client()) {
            client.send(Map.of("type", "auth", "token", token(a.name(), now.minusSeconds(120))));
            assertThat(client.closed.get(5, TimeUnit.SECONDS)).isEqualTo(1008);
        }
        try (var client = new Client(new Account(a.name(), token(a.name(), now.plusSeconds(2))))) {
            assertThat(client.closed.get(6, TimeUnit.SECONDS)).isEqualTo(1008);
        }
    }
    @Test void missingAuthTimesOut() throws Exception {
        try (var client = new Client()) { assertThat(client.closed.get(8, TimeUnit.SECONDS)).isEqualTo(1008); }
    }
    @Test void rejectsCrossOriginHandshake() {
        var client = HttpClient.newHttpClient();
        assertThatThrownBy(() -> client.newWebSocketBuilder().header("Origin", "https://evil.example").buildAsync(URI.create("ws://localhost:" + port + "/ws"), new WebSocket.Listener() {}).join()).isInstanceOf(CompletionException.class);
    }
    @Test void validatesMessagesAndKeepsSocketUsable() throws Exception {
        var a = account(); var b = account();
        try (var alice = new Client(a)) {
            alice.socket.sendText("not-json", true).join(); alice.await("error");
            for (String body : List.of(" ", "x".repeat(66000))) {
                alice.send(Map.of("type", "send", "to", b.name(), "ciphertext", body, "clientId", UUID.randomUUID().toString())); alice.await("error");
            }
            alice.send(Map.of("type", "send", "to", "missing_user", "ciphertext", wire("test"), "clientId", UUID.randomUUID().toString())); alice.await("error");
            send(alice, b, "still connected", UUID.randomUUID().toString());
        }
    }
    @Test void replaysMoreThanOneHundredOfflineMessagesAndPaginatesHistory() throws Exception {
        var a = account(); var b = account();
        try (var alice = new Client(a)) {
            for (int i = 0; i < 105; i++) send(alice, b, "message " + i, UUID.randomUUID().toString());
        }
        Set<String> ids = new HashSet<>();
        try (var bob = new Client(b)) {
            for (int i = 0; i < 105; i++) {
                String id = bob.await("message").path("message").path("id").asText(); ids.add(id);
                bob.send(Map.of("type", "ack", "id", id));
            }
            bob.send(Map.of("type", "ping")); bob.await("pong");
        }
        assertThat(ids).hasSize(105);
        var latest = history(a, b.name()); assertThat(latest.size()).isEqualTo(100);
        String before = latest.get(99).path("id").asText();
        var older = rest.exchange("/api/messages?peer=" + b.name() + "&beforeId=" + before, HttpMethod.GET, auth(a), JsonNode.class).getBody();
        assertThat(older.size()).isEqualTo(5);
        assertThat(db.queryForObject("SELECT COUNT(*) FROM messages WHERE recipient=? AND acknowledged=FALSE", Integer.class, b.name())).isZero();
    }
    @Test void deviceIdentityIsImmutableAndKeysAreClaimedOnlyOnce() {
        var a=account(); var b=account();
        var headers=new HttpHeaders(); headers.setBearerAuth(b.token());
        String ec=Base64.getEncoder().encodeToString(new byte[33]),sig=Base64.getEncoder().encodeToString(new byte[64]);
        var batch=List.of(Map.of("id",1,"publicKey",ec,"kyberPublicKey",Base64.getEncoder().encodeToString(new byte[1569]),"kyberSignature",sig));
        var body=new HashMap<String,Object>();body.put("identityKey",ec);body.put("registrationId",10);
        body.put("signedPreKey",Map.of("id",1,"publicKey",ec,"signature",sig));body.put("preKeys",batch);
        assertThat(rest.exchange("/api/keys",HttpMethod.PUT,new HttpEntity<>(body,headers),String.class).getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(rest.exchange("/api/keys/"+b.name()+"/claim",HttpMethod.POST,auth(a),String.class).getStatusCode()).isEqualTo(HttpStatus.OK);
        // Retrying publication must not make an already claimed key available again.
        assertThat(rest.exchange("/api/keys",HttpMethod.PUT,new HttpEntity<>(body,headers),String.class).getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(rest.exchange("/api/keys/"+b.name()+"/claim",HttpMethod.POST,auth(a),String.class).getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
        byte[] changed=new byte[33];changed[1]=1;body.put("identityKey",Base64.getEncoder().encodeToString(changed));
        assertThat(rest.exchange("/api/keys",HttpMethod.PUT,new HttpEntity<>(body,headers),String.class).getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
        assertThat(rest.getForEntity("/api/keys/"+b.name(),String.class).getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
    }
    @Test void legacyPlaintextMessagesAreRejected() throws Exception {
        var a=account();var b=account();
        try(var alice=new Client(a)) {
            alice.send(Map.of("type","send","to",b.name(),"clientId",UUID.randomUUID().toString(),"body","plaintext-must-not-be-stored"));
            alice.await("error");
        }
        assertThat(history(a,b.name()).size()).isZero();
    }
    private String token(String username, Instant expiry) {
        var claims = JwtClaimsSet.builder().issuer("chatapp-secure").subject(username).issuedAt(Instant.now().minusSeconds(300)).expiresAt(expiry).build();
        return encoder.encode(JwtEncoderParameters.from(JwsHeader.with(MacAlgorithm.HS256).build(), claims)).getTokenValue();
    }
    class Client implements WebSocket.Listener, AutoCloseable {
        final BlockingQueue<JsonNode> events = new LinkedBlockingQueue<>();
        final CompletableFuture<Integer> closed = new CompletableFuture<>();
        final StringBuilder buffer = new StringBuilder();
        final WebSocket socket;
        Client() { socket = HttpClient.newHttpClient().newWebSocketBuilder().connectTimeout(Duration.ofSeconds(5)).buildAsync(URI.create("ws://localhost:" + port + "/ws"), this).join(); }
        Client(Account account) throws Exception { this(); send(Map.of("type", "auth", "token", account.token())); await("ready"); }
        void send(Object value) throws Exception { socket.sendText(json.writeValueAsString(value), true).join(); }
        JsonNode await(String type) throws Exception {
            long end = System.nanoTime() + TimeUnit.SECONDS.toNanos(6);
            while (System.nanoTime() < end) {
                var e = events.poll(Math.max(1, end - System.nanoTime()), TimeUnit.NANOSECONDS);
                if (e != null && e.path("type").asText().equals(type)) return e;
                if (e != null && e.path("type").asText().equals("error")) throw new AssertionError(e.toString());
            }
            throw new AssertionError("Timed out awaiting " + type);
        }
        @Override public void onOpen(WebSocket socket) { socket.request(1); }
        @Override public CompletionStage<?> onText(WebSocket socket, CharSequence data, boolean last) {
            buffer.append(data);
            if (last) {
                try { events.add(json.readTree(buffer.toString())); } catch (Exception e) { closed.completeExceptionally(e); }
                buffer.setLength(0);
            }
            socket.request(1); return null;
        }
        @Override public CompletionStage<?> onClose(WebSocket socket, int code, String reason) { closed.complete(code); return null; }
        @Override public void onError(WebSocket socket, Throwable error) { closed.completeExceptionally(error); }
        @Override public void close() {
            if (!closed.isDone() && !socket.isOutputClosed()) socket.sendClose(1000, "test complete").join();
        }
    }
}
