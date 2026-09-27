ALTER TABLE account_deletion_events ADD COLUMN kind VARCHAR(32) NOT NULL DEFAULT 'account_deleted';
ALTER TABLE account_deletion_events ADD COLUMN new_account_id VARCHAR(36);
ALTER TABLE account_deletion_events ADD COLUMN new_identity_key VARCHAR(128);

-- Keep only historical partner membership when lost-device ciphertexts are erased.
CREATE TABLE history_peers (
    user_a VARCHAR(32) NOT NULL REFERENCES app_users(username) ON DELETE CASCADE,
    user_b VARCHAR(32) NOT NULL REFERENCES app_users(username) ON DELETE CASCADE,
    PRIMARY KEY (user_a, user_b),
    CHECK (user_a <> user_b)
);
INSERT INTO history_peers(user_a,user_b)
SELECT DISTINCT CASE WHEN sender < recipient THEN sender ELSE recipient END,
                CASE WHEN sender < recipient THEN recipient ELSE sender END
FROM messages WHERE sender <> recipient;
