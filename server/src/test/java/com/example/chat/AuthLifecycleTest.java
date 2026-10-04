package com.example.chat;

import com.fasterxml.jackson.databind.JsonNode;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.http.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.web.server.ResponseStatusException;
import static org.assertj.core.api.Assertions.*;

@SpringBootTest(webEnvironment=SpringBootTest.WebEnvironment.RANDOM_PORT,properties={
    "spring.datasource.url=jdbc:h2:mem:auth-audit;DB_CLOSE_DELAY=-1",
    "chat.jwt-secret=audit-test-secret-not-for-production-thirty-two-bytes"})
class AuthLifecycleTest {
    @Autowired TestRestTemplate rest;
    @Autowired JdbcTemplate db;
    @Autowired JwtDecoder jwt;
    @Autowired MessageRetention retention;
    @Autowired MessageStore store;
    JsonNode account() {
        var result=rest.postForEntity("/api/auth/register",Map.of("username","audit_"+UUID.randomUUID().toString().replace("-","").substring(0,14),"password","audit_password_123"),JsonNode.class);
        assertThat(result.getStatusCode()).isEqualTo(HttpStatus.CREATED);return result.getBody();
    }
    private ResponseEntity<JsonNode> status(String token) {
        var headers=new HttpHeaders();headers.setBearerAuth(token);
        return rest.exchange("/api/account/status",HttpMethod.GET,new HttpEntity<>(headers),JsonNode.class);
    }
    @Test void accountStatusReadsPersistedDeadlineWithoutRenewingIt() {
        JsonNode account=account();String user=account.path("username").asText();
        Instant connected=Instant.now().minusSeconds(3*86400L).truncatedTo(java.time.temporal.ChronoUnit.MICROS);
        db.update("UPDATE app_users SET last_connected_at=? WHERE username=?",Timestamp.from(connected),user);
        String token=account.path("token").asText();
        for(int i=0;i<2;i++) {
            var response=status(token);
            assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
            JsonNode body=response.getBody();
            assertThat(body.path("retentionDays").asInt()).isEqualTo(7);
            assertThat(Instant.parse(body.path("lastConnectedAt").asText())).isEqualTo(connected);
            assertThat(Instant.parse(body.path("accountExpiresAt").asText())).isEqualTo(connected.plusSeconds(7*86400L));
            assertThat(Instant.parse(body.path("serverTime").asText())).isBetween(Instant.now().minusSeconds(5),Instant.now().plusSeconds(5));
        }
        assertThat(db.queryForObject("SELECT last_connected_at FROM app_users WHERE username=?",Timestamp.class,user).toInstant()).isEqualTo(connected);
        assertThat(rest.getForEntity("/api/account/status",String.class).getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
    }
    @Test void oldTokenCannotReadStatusAfterInactiveAccountIsRecreated() {
        JsonNode old=account();String user=old.path("username").asText();
        db.update("UPDATE app_users SET last_connected_at=? WHERE username=?",Timestamp.from(Instant.now().minusSeconds(8*86400L)),user);
        assertThat(store.deleteIfInactive(user)).isPresent();
        var replacement=rest.postForEntity("/api/auth/register",Map.of("username",user,"password","replacement_password_123"),JsonNode.class);
        assertThat(replacement.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        assertThat(replacement.getBody().path("accountId")).isNotEqualTo(old.path("accountId"));
        assertThat(status(old.path("token").asText()).getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
        assertThat(status(replacement.getBody().path("token").asText()).getStatusCode()).isEqualTo(HttpStatus.OK);
    }
    @Test void refreshRotatesHashedCredentialAndPreservesIdentity() {
        JsonNode original=account();String credential=original.path("refreshToken").asText();
        assertThat(credential).matches("[A-Za-z0-9_-]{43}");
        assertThat(jwt.decode(original.path("token").asText()).getId()).isNotBlank();
        assertThat(db.queryForObject("SELECT COUNT(*) FROM refresh_tokens WHERE token_hash=?",Integer.class,credential)).isZero();
        var response=rest.postForEntity("/api/auth/refresh",Map.of("refreshToken",credential),JsonNode.class);
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody().path("accountId")).isEqualTo(original.path("accountId"));
        assertThat(response.getBody().path("refreshToken").asText()).isNotEqualTo(credential);
        assertThat(jwt.decode(response.getBody().path("token").asText()).getId()).isNotEqualTo(jwt.decode(original.path("token").asText()).getId());
        assertThat(rest.postForEntity("/api/auth/refresh",Map.of("refreshToken",credential),String.class).getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
    }
    @Test void resetGenerationAndExpiredCredentialCannotRenew() {
        JsonNode first=account();
        db.update("UPDATE app_users SET account_id=? WHERE username=?",UUID.randomUUID().toString(),first.path("username").asText());
        assertThat(rest.postForEntity("/api/auth/refresh",Map.of("refreshToken",first.path("refreshToken").asText()),String.class).getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
        JsonNode second=account();
        db.update("UPDATE refresh_tokens SET expires_at=? WHERE username=?",Timestamp.from(Instant.now().minusSeconds(10)),second.path("username").asText());
        assertThat(rest.postForEntity("/api/auth/refresh",Map.of("refreshToken",second.path("refreshToken").asText()),String.class).getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
    }
    @Test void rotationDoesNotExtendAbsoluteRefreshExpiry() {
        JsonNode original=account();String user=original.path("username").asText();
        Instant before=db.queryForObject("SELECT expires_at FROM refresh_tokens WHERE username=?",Timestamp.class,user).toInstant();
        assertThat(rest.postForEntity("/api/auth/refresh",Map.of("refreshToken",original.path("refreshToken").asText()),String.class).getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(db.queryForObject("SELECT expires_at FROM refresh_tokens WHERE username=?",Timestamp.class,user).toInstant()).isEqualTo(before);
    }
    @Test void deliveredRetentionUsesFirstAckTimeAndKeepsPendingCiphertext() {
        JsonNode a=account(),b=account();String sender=a.path("username").asText(),receiver=b.path("username").asText();
        Instant now=Instant.now().truncatedTo(java.time.temporal.ChronoUnit.MICROS),old=now.minusSeconds(8*86400L),recent=now.minusSeconds(86400);
        for(int i=0;i<3;i++)db.update("INSERT INTO messages(client_id,sender,recipient,ciphertext,created_at,acknowledged,acknowledged_at) VALUES(?,?,?,?,?,?,?)",
            UUID.randomUUID().toString(),sender,receiver,"encrypted-test",Timestamp.from(old),i!=2,i==2?null:Timestamp.from(i==0?old:recent));
        long oldest=db.queryForObject("SELECT MIN(id) FROM messages WHERE sender=?",Long.class,sender);
        store.acknowledge(receiver,oldest,b.path("accountId").asText());
        assertThat(db.queryForObject("SELECT acknowledged_at FROM messages WHERE id=?",Timestamp.class,oldest).toInstant()).isEqualTo(old.truncatedTo(java.time.temporal.ChronoUnit.MICROS));
        assertThat(retention.removeDeliveredBefore(now.minusSeconds(7*86400L))).isEqualTo(1);
        assertThat(db.queryForObject("SELECT COUNT(*) FROM messages WHERE sender=?",Integer.class,sender)).isEqualTo(2);
        assertThat(db.queryForObject("SELECT COUNT(*) FROM messages WHERE sender=? AND acknowledged=FALSE",Integer.class,sender)).isEqualTo(1);
    }
    @Test void knownOversizedJsonIsRejectedBeforeParsing() {
        String oversized="{\"username\":\"audit\",\"password\":\""+"x".repeat(ApiBodyLimit.MAX_BYTES)+"\"}";
        var headers=new HttpHeaders();headers.setContentType(MediaType.APPLICATION_JSON);
        assertThat(rest.postForEntity("/api/auth/login",new HttpEntity<>(oversized,headers),String.class).getStatusCode()).isEqualTo(HttpStatus.PAYLOAD_TOO_LARGE);
    }
    @Test void repeatedPasswordFailuresAreBoundedPerAddressAndUser() {
        var attempts=new AuthAttempts();String ip="192.0.2.10",user="some_user";
        for(int i=0;i<5;i++){attempts.admit(ip,user);attempts.failure(ip,user);}
        assertThatThrownBy(()->attempts.admit(ip,user)).isInstanceOfSatisfying(ResponseStatusException.class,e->assertThat(e.getStatusCode()).isEqualTo(HttpStatus.TOO_MANY_REQUESTS));
        attempts.admit("192.0.2.11",user);
        attempts.success(ip,user);attempts.admit(ip,user);
    }
    @Test void proxyAddressIsTrustedOnlyFromLoopbackAndWhenEnabled() throws Exception {
        AuthController controller=org.springframework.test.util.AopTestUtils.getTargetObject(authController);
        var method=AuthController.class.getDeclaredMethod("clientAddress",jakarta.servlet.http.HttpServletRequest.class);
        method.setAccessible(true);
        var request=new org.springframework.mock.web.MockHttpServletRequest();
        request.setRemoteAddr("192.0.2.10");request.addHeader("X-Real-IP","198.51.100.10");
        org.springframework.test.util.ReflectionTestUtils.setField(controller,"trustLoopbackProxy",true);
        try {
            assertThat(method.invoke(controller,request)).isEqualTo("192.0.2.10");
            request.setRemoteAddr("127.0.0.1");
            assertThat(method.invoke(controller,request)).isEqualTo("198.51.100.10");
            request.removeHeader("X-Real-IP");request.addHeader("X-Real-IP","198.51.100.10, 192.0.2.20");
            assertThat(method.invoke(controller,request)).isEqualTo("127.0.0.1");
            org.springframework.test.util.ReflectionTestUtils.setField(controller,"trustLoopbackProxy",false);
            request.removeHeader("X-Real-IP");request.addHeader("X-Real-IP","198.51.100.10");
            assertThat(method.invoke(controller,request)).isEqualTo("127.0.0.1");
        } finally { org.springframework.test.util.ReflectionTestUtils.setField(controller,"trustLoopbackProxy",false); }
    }
    @org.springframework.beans.factory.annotation.Autowired private AuthController authController;
}
