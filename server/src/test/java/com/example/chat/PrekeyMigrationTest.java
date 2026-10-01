package com.example.chat;

import java.util.UUID;
import org.flywaydb.core.Flyway;
import org.flywaydb.core.api.MigrationVersion;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import static org.assertj.core.api.Assertions.assertThat;

class PrekeyMigrationTest {
    @Test void upgradeRemovesConsumedBundlesAndDeviceDeletionClearsTheirIds() {
        String url = "jdbc:h2:mem:prekeys-upgrade-"+UUID.randomUUID()+";DB_CLOSE_DELAY=-1";
        Flyway.configure().dataSource(url, "sa", "").target(MigrationVersion.fromVersion("7")).load().migrate();
        var db = new JdbcTemplate(new DriverManagerDataSource(url, "sa", ""));
        db.update("INSERT INTO app_users(username,password_hash,account_id) VALUES ('alice','hash',?)", UUID.randomUUID().toString());
        db.update("INSERT INTO device_keys(username,identity_key,registration_id,signed_pre_key) VALUES ('alice','identity',10,'{}')");
        db.update("INSERT INTO one_time_keys(username,key_id,bundle,claimed) VALUES ('alice',1,'large-consumed-bundle',TRUE),('alice',2,'unused-bundle',FALSE)");
        Flyway.configure().dataSource(url, "sa", "").load().migrate();
        assertThat(db.queryForList("SELECT key_id FROM consumed_prekeys WHERE username='alice'", Integer.class)).containsExactly(1);
        assertThat(db.queryForList("SELECT key_id FROM one_time_keys WHERE username='alice'", Integer.class)).containsExactly(2);
        db.update("DELETE FROM one_time_keys WHERE username='alice'");
        db.update("DELETE FROM device_keys WHERE username='alice'");
        assertThat(db.queryForObject("SELECT COUNT(*) FROM consumed_prekeys", Integer.class)).isZero();
        db.update("INSERT INTO device_keys(username,identity_key,registration_id,signed_pre_key) VALUES ('alice','new-identity',11,'{}')");
        db.update("INSERT INTO one_time_keys(username,key_id,bundle) VALUES ('alice',1,'new-device-bundle')");
        assertThat(db.queryForList("SELECT key_id FROM one_time_keys WHERE username='alice'", Integer.class)).containsExactly(1);
    }
}
