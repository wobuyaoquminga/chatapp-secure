CREATE TABLE refresh_tokens (
 token_hash VARCHAR(64) PRIMARY KEY,
 username VARCHAR(32) NOT NULL REFERENCES app_users(username) ON DELETE CASCADE,
 account_id VARCHAR(36) NOT NULL,
 created_at TIMESTAMP WITH TIME ZONE NOT NULL,
 expires_at TIMESTAMP WITH TIME ZONE NOT NULL
);
CREATE INDEX idx_refresh_user ON refresh_tokens(username,created_at);
CREATE INDEX idx_refresh_expiry ON refresh_tokens(expires_at);
