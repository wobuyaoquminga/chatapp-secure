CREATE TABLE contacts (
 user_a VARCHAR(32) NOT NULL REFERENCES app_users(username),
 user_b VARCHAR(32) NOT NULL REFERENCES app_users(username),
 initiator VARCHAR(32) NOT NULL REFERENCES app_users(username),
 accepted BOOLEAN NOT NULL DEFAULT FALSE,
 PRIMARY KEY (user_a, user_b),
 CHECK (user_a <> user_b)
);

-- Conversations created before contact requests existed remain usable.
INSERT INTO contacts (user_a, user_b, initiator, accepted)
SELECT DISTINCT
 CASE WHEN sender < recipient THEN sender ELSE recipient END,
 CASE WHEN sender < recipient THEN recipient ELSE sender END,
 CASE WHEN sender < recipient THEN sender ELSE recipient END,
 TRUE
FROM messages
WHERE sender <> recipient;
