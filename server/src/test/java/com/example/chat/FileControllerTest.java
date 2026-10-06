package com.example.chat;

import com.fasterxml.jackson.databind.JsonNode;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.Arrays;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.web.server.ResponseStatusException;
import static org.assertj.core.api.Assertions.*;

@SpringBootTest(webEnvironment=SpringBootTest.WebEnvironment.RANDOM_PORT, properties={
    "spring.datasource.url=jdbc:h2:mem:file-test;DB_CLOSE_DELAY=-1", "chat.jwt-secret=test-only-secret-at-least-thirty-two-bytes"})
class FileControllerTest {
    static final Path directory;
    static { try { directory=Files.createTempDirectory("chat-file-test-"); } catch (Exception e) { throw new ExceptionInInitializerError(e); } }
    @DynamicPropertySource static void properties(DynamicPropertyRegistry registry) {
        registry.add("chat.attachments-directory", directory::toString);
    }
    @Autowired TestRestTemplate rest;
    @Autowired JdbcTemplate db;
    @Autowired AuthController auth;
    @Autowired FileController files;
    @LocalServerPort int port;
    record Account(String username, String id, String token) {}
    Account account() {
        String username="f"+UUID.randomUUID().toString().replace("-", "").substring(0, 14);
        var response=rest.postForEntity("/api/auth/register", Map.of("username",username,"password","password123"), JsonNode.class);
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        JsonNode body=response.getBody();
        return new Account(username,body.path("accountId").asText(),body.path("token").asText());
    }
    void accepted(Account a, Account b) {
        String first=a.username().compareTo(b.username())<0?a.username():b.username();
        String second=first.equals(a.username())?b.username():a.username();
        db.update("INSERT INTO contacts(user_a,user_b,initiator,accepted) VALUES (?,?,?,TRUE)",first,second,first);
    }
    String url(String id, Account recipient) {
        return "/api/files/"+id+"?to="+recipient.username()+"&toAccountId="+recipient.id();
    }
    HttpHeaders headers(Account user) {
        var h=new HttpHeaders();h.setBearerAuth(user.token());h.setContentType(MediaType.APPLICATION_OCTET_STREAM);return h;
    }
    org.springframework.http.ResponseEntity<JsonNode> put(String id,Account sender,Account recipient,byte[] body) {
        return rest.exchange(url(id,recipient),HttpMethod.PUT,new HttpEntity<>(body,headers(sender)),JsonNode.class);
    }
    org.springframework.http.ResponseEntity<byte[]> get(String id,Account user) {
        return rest.exchange("/api/files/"+id,HttpMethod.GET,new HttpEntity<>(headers(user)),byte[].class);
    }
    @Test void acceptedContactCanRetryAndOnlyExactParticipantsCanRead() throws Exception {
        Account sender=account(),recipient=account(),outsider=account();
        byte[] ciphertext=new byte[48];Arrays.fill(ciphertext,(byte)0xA5);
        String id=UUID.randomUUID().toString();
        assertThat(put(id,sender,recipient,ciphertext).getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
        assertThat(rest.getForEntity("/api/files/"+id,String.class).getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
        accepted(sender,recipient);
        var first=put(id,sender,recipient,ciphertext);
        assertThat(first.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(first.getBody().path("id").asText()).isEqualTo(id);
        assertThat(Instant.parse(first.getBody().path("expiresAt").asText())).isAfter(Instant.now().plusSeconds(6*86400));
        assertThat(put(id,sender,recipient,ciphertext).getBody()).isEqualTo(first.getBody());
        assertThat(get(id,recipient).getBody()).isEqualTo(ciphertext);
        assertThat(get(id,sender).getBody()).isEqualTo(ciphertext);
        assertThat(get(id,outsider).getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(Files.readAllBytes(directory.resolve(id))).isEqualTo(ciphertext);
        assertThat(db.queryForObject("SELECT COUNT(*) FROM attachments WHERE id=?",Integer.class,id)).isEqualTo(1);
        byte[] different=ciphertext.clone();different[0]++;
        assertThat(put(id,sender,recipient,different).getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
        assertThat(put(id,sender,outsider,ciphertext).getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
        assertThat(get(id,recipient).getBody()).isEqualTo(ciphertext);
    }
    @Test void generationsExpiryCleanupAndSenderDelete() throws Exception {
        Account sender=account(),recipient=account();accepted(sender,recipient);
        String id=UUID.randomUUID().toString();
        assertThat(put(id,sender,recipient,new byte[16]).getStatusCode()).isEqualTo(HttpStatus.OK);
        var h=headers(recipient);
        assertThat(rest.exchange("/api/files/"+id,HttpMethod.DELETE,new HttpEntity<>(h),String.class).getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
        db.update("UPDATE app_users SET account_id=? WHERE username=?",UUID.randomUUID().toString(),recipient.username());
        assertThat(get(id,sender).getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
        var newToken=auth.token(recipient.username(),db.queryForObject("SELECT account_id FROM app_users WHERE username=?",String.class,recipient.username()));
        Account replacement=new Account(recipient.username(),newToken.get("accountId"),newToken.get("token"));
        assertThat(get(id,replacement).getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
        files.cleanExpired();
        assertThat(Files.exists(directory.resolve(id))).isFalse();
        String second=UUID.randomUUID().toString();
        assertThat(put(second,sender,replacement,new byte[16]).getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(rest.exchange("/api/files/"+second,HttpMethod.DELETE,new HttpEntity<>(headers(sender)),String.class).getStatusCode()).isEqualTo(HttpStatus.NO_CONTENT);
        assertThat(Files.exists(directory.resolve(second))).isFalse();
        String third=UUID.randomUUID().toString();
        assertThat(put(third,sender,replacement,new byte[16]).getStatusCode()).isEqualTo(HttpStatus.OK);
        db.update("UPDATE attachments SET expires_at=? WHERE id=?",Timestamp.from(Instant.now().minusSeconds(1)),third);
        assertThat(get(third,replacement).getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(put(third,sender,replacement,new byte[16]).getStatusCode()).isEqualTo(HttpStatus.GONE);
        files.cleanExpired();
        assertThat(Files.exists(directory.resolve(third))).isFalse();
    }
    @Test void exactRetryRepairsMissingOrDamagedCiphertext() throws Exception {
        Account sender=account(),recipient=account();accepted(sender,recipient);
        String id=UUID.randomUUID().toString();
        byte[] ciphertext=new byte[48];Arrays.fill(ciphertext,(byte)0x5a);
        assertThat(put(id,sender,recipient,ciphertext).getStatusCode()).isEqualTo(HttpStatus.OK);
        Path stored=directory.resolve(id);
        Files.delete(stored);
        assertThat(put(id,sender,recipient,ciphertext).getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(get(id,recipient).getBody()).isEqualTo(ciphertext);
        Files.write(stored,new byte[48]);
        assertThat(get(id,recipient).getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(put(id,sender,recipient,ciphertext).getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(get(id,recipient).getBody()).isEqualTo(ciphertext);
    }
    @Test void actualLimitAndMalformedBodiesLeaveNoStoredFile() throws Exception {
        Account sender=account(),recipient=account();accepted(sender,recipient);
        String id=UUID.randomUUID().toString();
        assertThat(put(id,sender,recipient,new byte[15]).getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        byte[] oversized=new byte[(int)FileController.MAX_BYTES+1];
        var request=new MockHttpServletRequest("PUT","/api/files/"+id) {
            @Override public long getContentLengthLong() { return -1; }
        };
        request.setContentType("application/octet-stream");request.setContent(oversized);
        Jwt jwt=Jwt.withTokenValue("test").header("alg","none").subject(sender.username())
            .claim("account_id",sender.id()).build();
        assertThatThrownBy(()->files.upload(id,recipient.username(),recipient.id(),jwt,request))
            .isInstanceOf(ResponseStatusException.class)
            .satisfies(e->assertThat(((ResponseStatusException)e).getStatusCode()).isEqualTo(HttpStatus.PAYLOAD_TOO_LARGE));
        assertThat(Files.exists(directory.resolve(id))).isFalse();
        assertThat(db.queryForObject("SELECT COUNT(*) FROM attachments WHERE id=?",Integer.class,id)).isZero();
    }
    @Test void numberQuotasLimitTinyFilesWhileAllowingExactRetry() {
        Account sender=account(),recipient=account();accepted(sender,recipient);
        Account filler=account(),fresh=account();accepted(fresh,recipient);
        String first=UUID.randomUUID().toString();
        try {
            assertThat(put(first,sender,recipient,new byte[16]).getStatusCode()).isEqualTo(HttpStatus.OK);
            insertMetadata(sender,recipient,99);
            assertThat(put(first,sender,recipient,new byte[16]).getStatusCode()).isEqualTo(HttpStatus.OK);
            assertThat(put(UUID.randomUUID().toString(),sender,recipient,new byte[16]).getStatusCode())
                .isEqualTo(HttpStatus.INSUFFICIENT_STORAGE);

            int existing=db.queryForObject("SELECT COUNT(*) FROM attachments",Integer.class);
            insertMetadata(filler,recipient,10_000-existing);
            assertThat(db.queryForObject("SELECT COUNT(*) FROM attachments",Integer.class)).isEqualTo(10_000);
            assertThat(put(UUID.randomUUID().toString(),fresh,recipient,new byte[16]).getStatusCode())
                .isEqualTo(HttpStatus.INSUFFICIENT_STORAGE);
        } finally {
            db.update("DELETE FROM attachments WHERE sender=? OR sender=?",sender.username(),filler.username());
        }
    }
    void insertMetadata(Account sender,Account recipient,int count) {
        db.update("INSERT INTO attachments(id,sender,sender_account_id,recipient,recipient_account_id,size_bytes,sha256,expires_at) "
            + "SELECT CAST(RANDOM_UUID() AS VARCHAR(36)),?,?,?,?,16,REPEAT('0',64),CURRENT_TIMESTAMP + INTERVAL '7' DAY "
            + "FROM SYSTEM_RANGE(1,?)",sender.username(),sender.id(),recipient.username(),recipient.id(),count);
    }
}
