package com.example.chat;

import java.util.UUID;
import org.flywaydb.core.Flyway;
import org.flywaydb.core.api.MigrationVersion;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import static org.assertj.core.api.Assertions.assertThat;

class ContactMigrationTest {
    @Test void contactLookupPreservesStatusWithEitherStoredOrder() {
        String url = "jdbc:h2:mem:contact-lookup-" + UUID.randomUUID() + ";DB_CLOSE_DELAY=-1";
        Flyway.configure().dataSource(url, "sa", "").load().migrate();
        var db = new JdbcTemplate(new DriverManagerDataSource(url, "sa", ""));
        db.update("INSERT INTO app_users(username,password_hash,account_id) VALUES "
            + "('alice','hash',?),('bob','hash',?),('carol','hash',?)",
            UUID.randomUUID().toString(), UUID.randomUUID().toString(), UUID.randomUUID().toString());
        db.update("INSERT INTO contacts(user_a,user_b,initiator) VALUES ('bob','alice','bob')");
        db.update("INSERT INTO contacts(user_a,user_b,initiator,accepted) VALUES ('alice','carol','carol',TRUE)");

        var store = new MessageStore(db);
        assertThat(store.contact("alice", "bob").status()).isEqualTo("pending_incoming");
        assertThat(store.contact("bob", "alice").status()).isEqualTo("pending_outgoing");
        assertThat(store.contact("carol", "alice").status()).isEqualTo("accepted");
        assertThat(store.contact("bob", "carol")).isNull();
    }

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
        assertThat(db.queryForObject("SELECT COUNT(*) FROM history_peers WHERE user_a='old_a' AND user_b='old_b'", Integer.class)).isEqualTo(1);
    }
    @Test void existingDeletionEventsRetainTheirKindWhenUpgradingToResetEvents() {
        String url = "jdbc:h2:mem:events-upgrade-" + UUID.randomUUID() + ";DB_CLOSE_DELAY=-1";
        Flyway.configure().dataSource(url, "sa", "").target(MigrationVersion.fromVersion("6")).load().migrate();
        var db = new JdbcTemplate(new DriverManagerDataSource(url, "sa", ""));
        String recipientId = UUID.randomUUID().toString(), deletedId = UUID.randomUUID().toString();
        db.update("INSERT INTO app_users(username,password_hash,account_id) VALUES ('peer','hash',?)", recipientId);
        db.update("INSERT INTO account_deletion_events(id,recipient,recipient_account_id,username,account_id,identity_key,deleted_at) "
            + "VALUES (?,'peer',?,'old_user',?,'old-identity',CURRENT_TIMESTAMP)",
            UUID.randomUUID().toString(), recipientId, deletedId);
        Flyway.configure().dataSource(url, "sa", "").load().migrate();
        var event = new MessageStore(db).accountEvents("peer", recipientId).get(0);
        assertThat(event.kind()).isEqualTo("account_deleted");
        assertThat(event.accountId()).isEqualTo(deletedId);
        assertThat(event.identityKey()).isEqualTo("old-identity");
        assertThat(event.newAccountId()).isNull();
        assertThat(event.newIdentityKey()).isNull();
    }
}
