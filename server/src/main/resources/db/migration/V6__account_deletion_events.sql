CREATE TABLE account_deletion_events (
    id VARCHAR(36) PRIMARY KEY,
    recipient VARCHAR(32) NOT NULL REFERENCES app_users(username) ON DELETE CASCADE,
    recipient_account_id VARCHAR(36) NOT NULL,
    username VARCHAR(32) NOT NULL,
    account_id VARCHAR(36) NOT NULL,
    identity_key VARCHAR(128) NOT NULL,
    deleted_at TIMESTAMP WITH TIME ZONE NOT NULL
);
CREATE INDEX idx_account_events_recipient ON account_deletion_events(recipient, recipient_account_id, deleted_at);
