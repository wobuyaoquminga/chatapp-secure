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
    @Autowired AccountCleanup cleanup;
    @Autowired MessageStore store;
    record Account(String name, String token) {}
    Account account() {
        return account("u" + UUID.randomUUID().toString().replace("-", "").substring(0, 15));
    }
    Account account(String name) {
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
    JsonNode contacts(Account a) {
        return rest.exchange("/api/contacts", HttpMethod.GET, auth(a), JsonNode.class).getBody();
    }
    void accept(Account receiver, Account sender) {
        assertThat(rest.exchange("/api/contacts/accept", HttpMethod.POST,
            new HttpEntity<>(Map.of("peer", sender.name()), bearer(receiver)), JsonNode.class).getStatusCode()).isEqualTo(HttpStatus.OK);
    }
    HttpStatusCode remove(Account user, Account peer) {
        return rest.exchange("/api/contacts/remove", HttpMethod.POST,
            new HttpEntity<>(Map.of("peer", peer.name()), bearer(user)), Void.class).getStatusCode();
    }
    HttpHeaders bearer(Account a) { var h = new HttpHeaders(); h.setBearerAuth(a.token()); return h; }
    String wire(String body) throws Exception {
        return json.writeValueAsString(Map.of("v", 1, "type", 2, "data", Base64.getEncoder().encodeToString(("opaque-test-payload-padding-" + body).getBytes(java.nio.charset.StandardCharsets.UTF_8))));
    }
    String send(Client c, Account recipient, String body, String clientId) throws Exception {
        c.send(Map.of("type", "send", "to", recipient.name(), "toAccountId", accountId(recipient), "ciphertext", wire(body), "clientId", clientId));
        return c.await("accepted").path("message").path("id").asText();
    }
    @Test void chineseUsernamesCanRegisterLoginAndSend() throws Exception {
        var a = account("小明");
        var b = account("小红");
        assertThat(rest.postForEntity("/api/auth/register", Map.of("username", "a", "password", "password123"), String.class).getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(rest.postForEntity("/api/auth/login", Map.of("username", a.name(), "password", "password123"), String.class).getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(rest.exchange("/api/keys/" + b.name(), HttpMethod.GET, auth(a), JsonNode.class).getBody().path("username").asText()).isEqualTo(b.name());
        try (var alice = new Client(a); var bob = new Client(b)) {
            String id = send(alice, b, "hello", UUID.randomUUID().toString());
            assertThat(bob.await("message").path("message").path("id").asText()).isEqualTo(id);
        }
        assertThat(contacts(b).get(0).path("status").asText()).isEqualTo("pending_incoming");
    }
    @Test void pendingRequestAllowsOneMessageAndRequiresReceiverAcceptance() throws Exception {
        var a = account(); var b = account();
        String id = UUID.randomUUID().toString();
        try (var alice = new Client(a); var bob = new Client(b)) {
            String first = send(alice, b, "invitation", id);
            assertThat(send(alice, b, "invitation", id)).isEqualTo(first);
            alice.send(Map.of("type", "send", "to", b.name(), "ciphertext", wire("second"), "clientId", UUID.randomUUID().toString()));
            assertThat(alice.await("error").path("error").asText()).contains("接受");
            bob.send(Map.of("type", "send", "to", a.name(), "ciphertext", wire("reverse"), "clientId", UUID.randomUUID().toString()));
            assertThat(bob.await("error").path("error").asText()).contains("接受");
            assertThat(contacts(a).get(0).path("status").asText()).isEqualTo("pending_outgoing");
            assertThat(contacts(b).get(0).path("status").asText()).isEqualTo("pending_incoming");
            assertThat(rest.exchange("/api/contacts/remove", HttpMethod.POST,
                new HttpEntity<>(Map.of("peer", b.name()), bearer(a)), String.class).getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
            assertThat(rest.exchange("/api/contacts/accept", HttpMethod.POST,
                new HttpEntity<>(Map.of("peer", b.name()), bearer(a)), String.class).getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
            accept(b, a);
            assertThat(contacts(a).get(0).path("status").asText()).isEqualTo("accepted");
            send(alice, b, "second", UUID.randomUUID().toString());
            send(bob, a, "reverse", UUID.randomUUID().toString());
            assertThat(history(a, b.name()).size()).isEqualTo(3);
        }
    }
    @Test void removedContactRequiresFreshConsentAndPreservesHistory() throws Exception {
        var alice = account(); var bob = account();
        assertThat(rest.postForEntity("/api/contacts/remove", Map.of("peer", bob.name()), String.class).getStatusCode())
            .isEqualTo(HttpStatus.UNAUTHORIZED);
        assertThat(remove(alice, bob)).isEqualTo(HttpStatus.NO_CONTENT);
        assertThat(remove(alice, alice)).isEqualTo(HttpStatus.BAD_REQUEST);
        try (var a = new Client(alice); var b = new Client(bob)) {
            String initial = send(a, bob, "initial request", UUID.randomUUID().toString());
            assertThat(b.await("message").path("message").path("id").asText()).isEqualTo(initial);
            accept(bob, alice);
            String reply = send(b, alice, "accepted reply", UUID.randomUUID().toString());
            assertThat(a.await("message").path("message").path("id").asText()).isEqualTo(reply);
            assertThat(remove(alice, bob)).isEqualTo(HttpStatus.NO_CONTENT);
            assertThat(a.awaitContactStatus(bob.name(), "removed").path("username").asText()).isEqualTo(bob.name());
            assertThat(b.awaitContactStatus(alice.name(), "removed").path("username").asText()).isEqualTo(alice.name());
            assertThat(contacts(alice)).isEmpty();
            assertThat(contacts(bob)).isEmpty();
            assertThat(history(alice, bob.name()).size()).isEqualTo(2);
            assertThat(history(bob, alice.name()).size()).isEqualTo(2);
            assertThat(history(alice, bob.name()).get(0).path("id").asText()).isEqualTo(reply);
            assertThat(history(alice, bob.name()).get(1).path("id").asText()).isEqualTo(initial);
            assertThat(remove(bob, alice)).isEqualTo(HttpStatus.NO_CONTENT);

            String fresh = send(b, alice, "new request", UUID.randomUUID().toString());
            assertThat(a.await("message").path("message").path("id").asText()).isEqualTo(fresh);
            assertThat(contacts(bob).get(0).path("status").asText()).isEqualTo("pending_outgoing");
            assertThat(contacts(alice).get(0).path("status").asText()).isEqualTo("pending_incoming");
            b.send(Map.of("type", "send", "to", alice.name(), "ciphertext", wire("blocked sender"), "clientId", UUID.randomUUID().toString()));
            assertThat(b.await("error").path("error").asText()).contains("接受");
            a.send(Map.of("type", "send", "to", bob.name(), "ciphertext", wire("blocked receiver"), "clientId", UUID.randomUUID().toString()));
            assertThat(a.await("error").path("error").asText()).contains("接受");
            assertThat(rest.exchange("/api/contacts/accept", HttpMethod.POST,
                new HttpEntity<>(Map.of("peer", alice.name()), bearer(bob)), String.class).getStatusCode())
                .isEqualTo(HttpStatus.NOT_FOUND);
            accept(alice, bob);
            String afterAcceptance = send(b, alice, "after acceptance", UUID.randomUUID().toString());
            assertThat(a.await("message").path("message").path("id").asText()).isEqualTo(afterAcceptance);
            assertThat(history(alice, bob.name()).size()).isEqualTo(4);
            assertThat(history(bob, alice.name()).size()).isEqualTo(4);
        }
    }
    @Test void contactsReportAndPushPresence() throws Exception {
        var a = account(); var b = account();
        try (var alice = new Client(a)) {
            send(alice, b, "invite", UUID.randomUUID().toString());
            assertThat(contacts(a).get(0).path("online").asBoolean()).isFalse();
            try (var bob = new Client(b)) {
                assertThat(alice.await("presence").path("online").asBoolean()).isTrue();
                assertThat(contacts(a).get(0).path("online").asBoolean()).isTrue();
            }
            assertThat(alice.await("presence").path("online").asBoolean()).isFalse();
            assertThat(contacts(a).get(0).path("online").asBoolean()).isFalse();
        }
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
            accept(b, a);
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
            send(alice, b, "message 0", UUID.randomUUID().toString());
            accept(b, a);
            for (int i = 1; i < 105; i++) send(alice, b, "message " + i, UUID.randomUUID().toString());
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
        var claimed=rest.exchange("/api/keys/"+b.name()+"/claim",HttpMethod.POST,auth(a),JsonNode.class);
        assertThat(claimed.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(claimed.getBody().path("accountId").asText()).isEqualTo(accountId(b));
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
    JsonNode accountEvents(Account account) {
        var response = rest.exchange("/api/account-events", HttpMethod.GET, auth(account), JsonNode.class);
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        return response.getBody();
    }
    HttpStatusCode ackAccountEvents(Account account, List<String> ids) {
        return rest.exchange("/api/account-events/ack", HttpMethod.POST,
            new HttpEntity<>(Map.of("ids", ids), bearer(account)), Void.class).getStatusCode();
    }
    void expire(Account account) {
        db.update("UPDATE app_users SET last_connected_at=CURRENT_TIMESTAMP - INTERVAL '8' DAY WHERE username=?", account.name());
    }
    String accountId(Account account) {
        return db.queryForObject("SELECT account_id FROM app_users WHERE username=?", String.class, account.name());
    }
    @Test void accountCleanupPushesFullDeletionEventAndRemovesAccountData() throws Exception {
        var deleted = account(); var peer = account();
        String generation = accountId(deleted);
        String identity = db.queryForObject("SELECT identity_key FROM device_keys WHERE username=?", String.class, deleted.name());
        try (var old = new Client(deleted); var receiver = new Client(peer)) {
            send(old, peer, "old account", UUID.randomUUID().toString());
            receiver.await("message");
            expire(deleted);
            cleanup.removeInactiveAccounts();
            var event = receiver.await("account_deleted").path("event");
            assertThat(UUID.fromString(event.path("id").asText())).isNotNull();
            assertThat(event.path("username").asText()).isEqualTo(deleted.name());
            assertThat(event.path("accountId").asText()).isEqualTo(generation);
            assertThat(event.path("identityKey").asText()).isEqualTo(identity);
            assertThat(Instant.parse(event.path("deletedAt").asText())).isBeforeOrEqualTo(Instant.now());
            receiver.awaitContactStatus(deleted.name(), "removed");
            assertThat(old.closed.get(5, TimeUnit.SECONDS)).isEqualTo(1008);
            assertThat(accountEvents(peer).get(0)).isEqualTo(event);
        }
        assertThat(db.queryForObject("SELECT COUNT(*) FROM app_users WHERE username=?", Integer.class, deleted.name())).isZero();
        assertThat(store.keysExist(deleted.name())).isFalse();
        assertThat(contacts(peer)).isEmpty();
        assertThat(history(peer, deleted.name())).isEmpty();
    }
    @Test void offlineDeletionPersistsAcrossReconnectUntilAuthorizedIdempotentAck() throws Exception {
        var deleted = account(); var peer = account(); var stranger = account();
        try (var sender = new Client(deleted)) { send(sender, peer, "offline", UUID.randomUUID().toString()); }
        expire(deleted);
        cleanup.removeInactiveAccounts();
        String id = accountEvents(peer).get(0).path("id").asText();
        assertThat(rest.getForEntity("/api/account-events", String.class).getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
        assertThat(accountEvents(stranger)).isEmpty();
        assertThat(ackAccountEvents(stranger, List.of(id))).isEqualTo(HttpStatus.NO_CONTENT);
        assertThat(accountEvents(peer)).hasSize(1);
        for (int i = 0; i < 2; i++) {
            try (var receiver = new Client(peer)) {
                assertThat(accountEvents(peer).get(0).path("id").asText()).isEqualTo(id);
            }
        }
        assertThat(ackAccountEvents(peer, List.of(id))).isEqualTo(HttpStatus.NO_CONTENT);
        assertThat(ackAccountEvents(peer, List.of(id))).isEqualTo(HttpStatus.NO_CONTENT);
        assertThat(accountEvents(peer)).isEmpty();
        assertThat(ackAccountEvents(peer, List.of("malformed"))).isEqualTo(HttpStatus.BAD_REQUEST);
    }
    @Test void cleanupNotifiesRemovedContactsWithHistoricalMessagesAndCapturesDeletedGeneration() throws Exception {
        var deleted = account(); var formerPeer = account();
        String oldGeneration = accountId(deleted);
        try (var sender = new Client(deleted)) { send(sender, formerPeer, "history", UUID.randomUUID().toString()); }
        accept(formerPeer, deleted);
        assertThat(remove(formerPeer, deleted)).isEqualTo(HttpStatus.NO_CONTENT);
        assertThat(contacts(formerPeer)).isEmpty();
        expire(deleted);
        cleanup.removeInactiveAccounts();
        var replacement = account(deleted.name());
        var event = accountEvents(formerPeer).get(0);
        assertThat(event.path("accountId").asText()).isEqualTo(oldGeneration).isNotEqualTo(accountId(replacement));
        var bundle = rest.exchange("/api/keys/" + replacement.name(), HttpMethod.GET, auth(formerPeer), JsonNode.class).getBody();
        assertThat(bundle.path("accountId").asText()).isEqualTo(accountId(replacement));
        assertThat(rest.exchange("/api/account-events", HttpMethod.GET, auth(deleted), String.class).getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
        assertThat(accountEvents(formerPeer)).hasSize(1);
    }
    @Test void recipientReRegistrationCannotInheritEventsOrUseItsOldToken() throws Exception {
        var deleted = account(); var receiver = account(); var laterDeleted = account();
        store.save(deleted.name(), receiver.name(), UUID.randomUUID().toString(), wire("old event"));
        expire(deleted); cleanup.removeInactiveAccounts();
        String oldEventId = accountEvents(receiver).get(0).path("id").asText();
        String oldRecipientGeneration = accountId(receiver);
        expire(receiver); cleanup.removeInactiveAccounts();
        var replacement = account(receiver.name());
        assertThat(accountId(replacement)).isNotEqualTo(oldRecipientGeneration);
        assertThat(accountEvents(replacement)).isEmpty();
        store.save(laterDeleted.name(), replacement.name(), UUID.randomUUID().toString(), wire("new event"));
        expire(laterDeleted); cleanup.removeInactiveAccounts();
        String newEventId = accountEvents(replacement).get(0).path("id").asText();
        assertThat(newEventId).isNotEqualTo(oldEventId);
        assertThat(rest.exchange("/api/account-events", HttpMethod.GET, auth(receiver), String.class).getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
        assertThat(ackAccountEvents(receiver, List.of(newEventId))).isEqualTo(HttpStatus.UNAUTHORIZED);
        assertThat(store.accountEvents(receiver.name(), oldRecipientGeneration)).isEmpty();
        store.acknowledgeAccountEvents(receiver.name(), oldRecipientGeneration, List.of(newEventId));
        assertThat(accountEvents(replacement)).hasSize(1);
    }
    @Test void cleanupHonorsSevenDaysAndOnlyWebSocketConnectionRenewsActivity() throws Exception {
        var expired = account(); var recent = account(); var reconnected = account();
        expire(expired); expire(reconnected);
        db.update("UPDATE app_users SET last_connected_at=CURRENT_TIMESTAMP - INTERVAL '6' DAY WHERE username=?", recent.name());
        assertThat(rest.postForEntity("/api/auth/login", Map.of("username", expired.name(), "password", "password123"), String.class).getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(contacts(expired)).isEmpty();
        try (var active = new Client(reconnected)) {
            cleanup.removeInactiveAccounts();
            assertThat(db.queryForObject("SELECT COUNT(*) FROM app_users WHERE username=?", Integer.class, expired.name())).isZero();
            assertThat(accountId(recent)).isNotBlank();
            assertThat(accountId(reconnected)).isNotBlank();
            assertThat(store.deleteIfInactive(recent.name())).isEmpty();
            assertThat(store.deleteIfInactive(reconnected.name())).isEmpty();
            active.send(Map.of("type", "ping")); active.await("pong");
        }
        var uninitialized = rest.postForEntity("/api/auth/register", Map.of("username", "n" + UUID.randomUUID().toString().replace("-", "").substring(0, 15), "password", "password123"), JsonNode.class).getBody();
        String name = uninitialized.path("username").asText();
        store.save(name, recent.name(), UUID.randomUUID().toString(), wire("no identity published"));
        db.update("UPDATE app_users SET last_connected_at=CURRENT_TIMESTAMP - INTERVAL '8' DAY WHERE username=?", name);
        assertThat(store.deleteIfInactive(name)).isPresent();
        assertThat(accountEvents(recent).get(0).path("identityKey").asText()).isEmpty();
        var boundary = account();
        db.update("UPDATE app_users SET last_connected_at=CURRENT_TIMESTAMP - INTERVAL '7' DAY WHERE username=?", boundary.name());
        assertThat(store.deleteIfInactive(boundary.name())).isPresent();
    }
    Map<String,Object> resetBundle(int marker) {
        byte[] ec = new byte[33]; ec[0] = 5; ec[1] = (byte) marker;
        String key = Base64.getEncoder().encodeToString(ec);
        String signature = Base64.getEncoder().encodeToString(new byte[64]);
        return Map.of("identityKey", key, "registrationId", 12,
            "signedPreKey", Map.of("id", 2, "publicKey", key, "signature", signature),
            "preKeys", List.of(Map.of("id", 1, "publicKey", key,
                "kyberPublicKey", Base64.getEncoder().encodeToString(new byte[1569]), "kyberSignature", signature)));
    }
    ResponseEntity<JsonNode> reset(Account a, String password, Map<String,Object> bundle) {
        return rest.exchange("/api/keys/reset", HttpMethod.POST,
            new HttpEntity<>(Map.of("password", password, "bundle", bundle), bearer(a)), JsonNode.class);
    }
    Account resetAccount(Account a, int marker) {
        var result = reset(a, "password123", resetBundle(marker));
        assertThat(result.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(result.getBody().path("accountId").asText()).isEqualTo(accountId(a));
        return new Account(a.name(), result.getBody().path("token").asText());
    }
    @Test void twoCharacterAsciiAndChineseNamesUseTheSameMinimum() {
        var a = account("ab");
        var b = account("中a");
        assertThat(rest.postForEntity("/api/auth/login", Map.of("username", a.name(), "password", "password123"), JsonNode.class).getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(rest.exchange("/api/keys/" + b.name(), HttpMethod.GET, auth(a), JsonNode.class).getStatusCode()).isEqualTo(HttpStatus.OK);
        for (String invalid : List.of("a", "中", "a".repeat(33)))
            assertThat(rest.postForEntity("/api/auth/register", Map.of("username", invalid, "password", "password123"), JsonNode.class).getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
    }
    @Test void identityResetRequiresPasswordAndRollsBackInvalidBundle() throws Exception {
        var a = account(); var b = account();
        store.save(b.name(), a.name(), UUID.randomUUID().toString(), wire("pending"));
        String original = accountId(a);
        String originalHash = db.queryForObject("SELECT password_hash FROM app_users WHERE username=?", String.class, a.name());
        String originalIdentity = db.queryForObject("SELECT identity_key FROM device_keys WHERE username=?", String.class, a.name());
        assertThat(rest.postForEntity("/api/keys/reset", Map.of("password", "password123", "bundle", resetBundle(10)), JsonNode.class).getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
        assertThat(reset(a, "wrongpassword", resetBundle(10)).getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
        var malformed = new HashMap<>(resetBundle(10)); malformed.put("preKeys", List.of(Map.of("id", 1)));
        assertThat(reset(a, "password123", malformed).getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(accountId(a)).isEqualTo(original);
        assertThat(db.queryForObject("SELECT identity_key FROM device_keys WHERE username=?", String.class, a.name())).isEqualTo(originalIdentity);
        assertThat(db.queryForObject("SELECT password_hash FROM app_users WHERE username=?", String.class, a.name())).isEqualTo(originalHash);
        assertThat(history(a, b.name())).hasSize(1);
        assertThat(accountEvents(b)).isEmpty();
    }
    @Test void resetKeepsAccountAndConsentClosesOldSocketAndPushesIdentityReset() throws Exception {
        var a = account(); var b = account();
        String oldGeneration = accountId(a);
        String oldIdentity = db.queryForObject("SELECT identity_key FROM device_keys WHERE username=?", String.class, a.name());
        try (var oldDevice = new Client(a); var peer = new Client(b)) {
            send(oldDevice, b, "previous conversation", UUID.randomUUID().toString()); peer.await("message");
            accept(b, a);
            var replacement = resetAccount(a, 21);
            assertThat(oldDevice.closed.get(5, TimeUnit.SECONDS)).isEqualTo(1008);
            var event = peer.await("identity_reset").path("event");
            assertThat(event.path("kind").asText()).isEqualTo("identity_reset");
            assertThat(event.path("username").asText()).isEqualTo(a.name());
            assertThat(event.path("accountId").asText()).isEqualTo(oldGeneration);
            assertThat(event.path("identityKey").asText()).isEqualTo(oldIdentity);
            assertThat(event.path("newAccountId").asText()).isEqualTo(accountId(replacement)).isNotEqualTo(oldGeneration);
            assertThat(event.path("newIdentityKey").asText()).isEqualTo(resetBundle(21).get("identityKey"));
            assertThat(accountEvents(b).get(0)).isEqualTo(event);
            assertThat(contacts(replacement).get(0).path("status").asText()).isEqualTo("accepted");
            assertThat(contacts(b).get(0).path("status").asText()).isEqualTo("accepted");
            assertThat(rest.exchange("/api/keys/me", HttpMethod.GET, auth(a), JsonNode.class).getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
            assertThat(reset(a, "password123", resetBundle(22)).getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
            var own = rest.exchange("/api/keys/me", HttpMethod.GET, auth(replacement), JsonNode.class).getBody();
            assertThat(own.path("accountId").asText()).isEqualTo(accountId(replacement));
            assertThat(own.path("identityKey").asText()).isEqualTo(resetBundle(21).get("identityKey"));
            var login = rest.postForEntity("/api/auth/login", Map.of("username", a.name(), "password", "password123"), JsonNode.class);
            assertThat(login.getStatusCode()).isEqualTo(HttpStatus.OK);
            assertThat(login.getBody().path("accountId").asText()).isEqualTo(accountId(replacement));
            try (var newDevice = new Client(replacement)) {
                send(newDevice, b, "new conversation", UUID.randomUUID().toString()); peer.await("message");
            }
        }
    }
    @Test void offlineResetEventsAreDurableAndOldCiphertextsNeverReachReplacement() throws Exception {
        var a = account(); var b = account(); var stranger = account();
        store.save(b.name(), a.name(), UUID.randomUUID().toString(), wire("old pending"));
        var replacement = resetAccount(a, 30);
        String eventId = accountEvents(b).get(0).path("id").asText();
        assertThat(accountEvents(stranger)).isEmpty();
        assertThat(contacts(replacement).get(0).path("status").asText()).isEqualTo("pending_incoming");
        assertThat(history(replacement, b.name())).isEmpty();
        assertThat(history(b, a.name())).isEmpty();
        assertThat(store.pending(replacement.name(), 0, 100)).isEmpty();
        try (var fresh = new Client(replacement)) {
            fresh.send(Map.of("type", "ping")); fresh.await("pong");
            assertThat(fresh.events.stream().filter(e -> e.path("type").asText().equals("message"))).isEmpty();
        }
        for (int attempt = 0; attempt < 2; attempt++) {
            try (var offlinePeer = new Client(b)) {
                assertThat(accountEvents(b).get(0).path("id").asText()).isEqualTo(eventId);
            }
        }
        assertThat(ackAccountEvents(stranger, List.of(eventId))).isEqualTo(HttpStatus.NO_CONTENT);
        assertThat(accountEvents(b)).hasSize(1);
        assertThat(ackAccountEvents(b, List.of(eventId))).isEqualTo(HttpStatus.NO_CONTENT);
        assertThat(ackAccountEvents(b, List.of(eventId))).isEqualTo(HttpStatus.NO_CONTENT);
        assertThat(accountEvents(b)).isEmpty();
    }
    @Test void passwordVerifiedResetPreservesPendingPeerProofsButRejectsOldRecipientToken() throws Exception {
        var a = account(); var b = account();
        store.save(a.name(), b.name(), UUID.randomUUID().toString(), wire("peer"));
        var nextA = resetAccount(a, 41);
        String oldBGeneration = accountId(b);
        String oldEvent = accountEvents(b).get(0).path("id").asText();
        var nextB = resetAccount(b, 42);
        assertThat(accountEvents(nextB)).hasSize(1);
        assertThat(accountEvents(nextB).get(0).path("id").asText()).isEqualTo(oldEvent);
        assertThat(accountEvents(nextB).get(0).path("newAccountId").asText()).isEqualTo(accountId(nextA));
        assertThat(rest.exchange("/api/account-events", HttpMethod.GET, auth(b), JsonNode.class).getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
        assertThat(ackAccountEvents(b, List.of(oldEvent))).isEqualTo(HttpStatus.UNAUTHORIZED);
        var latestA = resetAccount(nextA, 43);
        var events = accountEvents(nextB);
        assertThat(events).hasSize(2);
        String newEvent = events.get(1).path("id").asText();
        assertThat(newEvent).isNotEqualTo(oldEvent);
        assertThat(ackAccountEvents(b, List.of(newEvent))).isEqualTo(HttpStatus.UNAUTHORIZED);
        store.acknowledgeAccountEvents(b.name(), oldBGeneration, List.of(newEvent));
        assertThat(accountEvents(nextB)).hasSize(2);
        assertThat(accountEvents(nextB).get(0).path("id").asText()).isEqualTo(oldEvent);
        assertThat(store.accountEvents(b.name(), oldBGeneration)).isEmpty();
        assertThat(rest.exchange("/api/keys", HttpMethod.PUT, new HttpEntity<>(resetBundle(44), bearer(a)), JsonNode.class).getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
        assertThat(rest.exchange("/api/keys/" + latestA.name() + "/claim", HttpMethod.POST, auth(nextB), JsonNode.class).getBody().path("accountId").asText()).isEqualTo(accountId(latestA));
    }
    @Test void resetNotifiesFormerHistoricalPeerWithoutRecreatingContact() throws Exception {
        var a = account(); var b = account();
        store.save(a.name(), b.name(), UUID.randomUUID().toString(), wire("former conversation"));
        accept(b, a); remove(a, b);
        var next = resetAccount(a, 51);
        assertThat(accountEvents(b).get(0).path("kind").asText()).isEqualTo("identity_reset");
        String firstEventId = accountEvents(b).get(0).path("id").asText();
        ackAccountEvents(b, List.of(firstEventId));
        assertThat(accountEvents(b)).isEmpty();
        resetAccount(next, 52);
        assertThat(accountEvents(b)).hasSize(1);
        assertThat(accountEvents(b).get(0).path("id").asText()).isNotEqualTo(firstEventId);
        assertThat(contacts(b)).isEmpty();
        expire(b); cleanup.removeInactiveAccounts();
        assertThat(db.queryForObject("SELECT COUNT(*) FROM history_peers WHERE user_a=? OR user_b=?", Integer.class, b.name(), b.name())).isZero();
    }
    @Test void oldRecipientGenerationCannotReceiveDelayedCiphertextAfterReset() throws Exception {
        var sender = account(); var receiver = account();
        store.save(sender.name(), receiver.name(), UUID.randomUUID().toString(), wire("invite"));
        accept(receiver, sender);
        String oldGeneration = accountId(receiver);
        var fresh = resetAccount(receiver, 61);
        try (var peer = new Client(sender); var device = new Client(fresh)) {
            peer.send(Map.of("type", "send", "to", receiver.name(), "toAccountId", oldGeneration,
                "ciphertext", wire("old device ciphertext"), "clientId", UUID.randomUUID().toString()));
            assertThat(peer.await("error").path("error").asText()).contains("身份已更新");
            assertThat(store.pending(receiver.name(), 0, 100)).isEmpty();
            String id = send(peer, fresh, "new device ciphertext", UUID.randomUUID().toString());
            assertThat(device.await("message").path("message").path("id").asText()).isEqualTo(id);
            assertThat(history(fresh, sender.name())).hasSize(1);
        }
    }
    private String token(String username, Instant expiry) {
        var claims = JwtClaimsSet.builder().issuer("chat").subject(username)
            .claim("account_id", db.queryForObject("SELECT account_id FROM app_users WHERE username=?", String.class, username))
            .issuedAt(Instant.now().minusSeconds(300)).expiresAt(expiry).build();
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
        JsonNode awaitContactStatus(String peer, String status) throws Exception {
            long end = System.nanoTime() + TimeUnit.SECONDS.toNanos(6);
            while (System.nanoTime() < end) {
                var e = events.poll(Math.max(1, end - System.nanoTime()), TimeUnit.NANOSECONDS);
                if (e != null && e.path("type").asText().equals("contact")
                    && e.path("contact").path("username").asText().equals(peer)
                    && e.path("contact").path("status").asText().equals(status)) return e.path("contact");
                if (e != null && e.path("type").asText().equals("error")) throw new AssertionError(e.toString());
            }
            throw new AssertionError("Timed out awaiting contact status " + status);
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
