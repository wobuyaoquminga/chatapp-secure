CREATE TABLE attachments (
 id VARCHAR(36) PRIMARY KEY,
 sender VARCHAR(32) NOT NULL REFERENCES app_users(username) ON DELETE CASCADE,
 sender_account_id VARCHAR(36) NOT NULL,
 recipient VARCHAR(32) NOT NULL REFERENCES app_users(username) ON DELETE CASCADE,
 recipient_account_id VARCHAR(36) NOT NULL,
 size_bytes BIGINT NOT NULL CHECK (size_bytes BETWEEN 16 AND 10485776),
 sha256 VARCHAR(64) NOT NULL,
 expires_at TIMESTAMP WITH TIME ZONE NOT NULL
);
CREATE INDEX idx_attachments_expiry ON attachments(expires_at);
CREATE INDEX idx_attachments_sender ON attachments(sender, sender_account_id);
