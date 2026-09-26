package com.example.chat;

import java.util.UUID;
import org.flywaydb.core.Flyway;
import org.flywaydb.core.api.MigrationVersion;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import static org.assertj.core.api.Assertions.assertThat;

class ContactMigrationTest {
    @Test void existingMessagesBecomeAcceptedContacts() {
        String url = "jdbc:h2:mem:upgrade-" + UUID.randomUUID() + ";DB_CLOSE_DELAY=-1";
        Flyway.configure().dataSource(url, "sa", "").target(MigrationVersion.fromVersion("2")).load().migrate();
        var db = new JdbcTemplate(new DriverManagerDataSource(url, "sa", ""));
        db.update("INSERT INTO app_users(username,password_hash) VALUES ('old_a','hash'),('old_b','hash')");
        db.update("INSERT INTO messages(client_id,sender,recipient,ciphertext,created_at) VALUES (?,?,?,?,CURRENT_TIMESTAMP)",
            UUID.randomUUID().toString(), "old_a", "old_b", "legacy");
        db.update("INSERT INTO messages(client_id,sender,recipient,ciphertext,created_at) VALUES (?,?,?,?,CURRENT_TIMESTAMP)",
            UUID.randomUUID().toString(), "old_b", "old_a", "legacy");
        Flyway.configure().dataSource(url, "sa", "").load().migrate();
        assertThat(db.queryForObject("SELECT COUNT(*) FROM contacts WHERE accepted=TRUE", Integer.class)).isEqualTo(1);
        assertThat(new MessageStore(db).contacts("old_b").get(0).status()).isEqualTo("accepted");
    }
}
