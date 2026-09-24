CREATE TABLE device_keys (
 username VARCHAR(32) PRIMARY KEY REFERENCES app_users(username),
 identity_key VARCHAR(128) NOT NULL,
 registration_id INTEGER NOT NULL,
 signed_pre_key VARCHAR(1024) NOT NULL
);
CREATE TABLE one_time_keys (
 username VARCHAR(32) NOT NULL REFERENCES device_keys(username),
 key_id INTEGER NOT NULL,
 bundle VARCHAR(5000) NOT NULL,
 claimed BOOLEAN NOT NULL DEFAULT FALSE,
 PRIMARY KEY(username, key_id)
);
