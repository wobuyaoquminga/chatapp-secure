package db.migration;

import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.UUID;
import org.flywaydb.core.api.migration.BaseJavaMigration;
import org.flywaydb.core.api.migration.Context;

public class V5__backfill_account_ids extends BaseJavaMigration {
    @Override public void migrate(Context context) throws Exception {
        var connection = context.getConnection();
        try (Statement read = connection.createStatement();
             ResultSet users = read.executeQuery("SELECT username FROM app_users");
             PreparedStatement update = connection.prepareStatement("UPDATE app_users SET account_id=? WHERE username=?")) {
            while (users.next()) {
                update.setString(1, UUID.randomUUID().toString());
                update.setString(2, users.getString(1));
                update.executeUpdate();
            }
        }
        try (Statement schema = connection.createStatement()) {
            schema.execute("ALTER TABLE app_users ALTER COLUMN account_id SET NOT NULL");
        }
    }
}
