-- Keep only consumed IDs; the large public bundles need not remain on the server.
-- The device foreign key removes tombstones on reset/deletion, allowing new devices to start at 1.
CREATE TABLE consumed_prekeys (
 username VARCHAR(32) NOT NULL REFERENCES device_keys(username) ON DELETE CASCADE,
 key_id INTEGER NOT NULL,
 PRIMARY KEY(username, key_id)
);
INSERT INTO consumed_prekeys(username,key_id)
 SELECT username,key_id FROM one_time_keys WHERE claimed=TRUE;
DELETE FROM one_time_keys WHERE claimed=TRUE;
