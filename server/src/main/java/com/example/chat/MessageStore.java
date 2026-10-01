package com.example.chat;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

@Repository
public class MessageStore {
    private final JdbcTemplate db;
    public MessageStore(JdbcTemplate db) { this.db = db; }
    public record Message(String id, String clientId, String sender, String recipient, String ciphertext,
                          String createdAt, boolean acknowledged) {}
    public record SaveResult(Message message, boolean newContact) {}
    public record Contact(String username, String status, boolean online) {}
    public record DeletedAccount(String accountId, List<String> peers) {}
    public record AccountDeletionEvent(String id, String username, String accountId, String identityKey, String deletedAt,
                                       String kind, String newAccountId, String newIdentityKey) {}
    private record EventRecipient(String username, String accountId) {}
    private Message map(ResultSet r, int row) throws SQLException {
        return new Message(r.getString("id"), r.getString("client_id"), r.getString("sender"),
            r.getString("recipient"), r.getString("ciphertext"), r.getObject("created_at", OffsetDateTime.class).toString(), r.getBoolean("acknowledged"));
    }
    public boolean keysExist(String user) {
        return Boolean.TRUE.equals(db.queryForObject("SELECT COUNT(*) > 0 FROM device_keys WHERE username=?", Boolean.class, user));
    }
    public boolean recordConnection(String user, String accountId) {
        return db.update("UPDATE app_users SET last_connected_at=CURRENT_TIMESTAMP WHERE username=? AND account_id=?",
            user, accountId) == 1;
    }
    public List<String> expiredAccounts() {
        return db.queryForList("SELECT username FROM app_users WHERE last_connected_at <= CURRENT_TIMESTAMP - INTERVAL '7' DAY ORDER BY username",
            String.class);
    }
    @Transactional
    public Optional<DeletedAccount> deleteIfInactive(String user) {
        var locked = db.queryForList("SELECT account_id FROM app_users WHERE username=? AND last_connected_at <= CURRENT_TIMESTAMP - INTERVAL '7' DAY FOR UPDATE",
            String.class, user);
        if (locked.isEmpty()) return Optional.empty();
        String accountId = locked.get(0);
        var identities = db.queryForList("SELECT identity_key FROM device_keys WHERE username=?", String.class, user);
        String identityKey = identities.isEmpty() ? "" : identities.get(0);
        var recipients = db.query("SELECT username,account_id FROM app_users WHERE username<>? AND username IN ("
            + "SELECT CASE WHEN user_a=? THEN user_b ELSE user_a END FROM contacts WHERE user_a=? OR user_b=? "
            + "UNION SELECT CASE WHEN sender=? THEN recipient ELSE sender END FROM messages WHERE sender=? OR recipient=? "
            + "UNION SELECT CASE WHEN user_a=? THEN user_b ELSE user_a END FROM history_peers WHERE user_a=? OR user_b=?) ORDER BY username",
            (r, i) -> new EventRecipient(r.getString("username"), r.getString("account_id")), user, user, user, user, user, user, user, user, user, user);
        OffsetDateTime deletedAt = OffsetDateTime.now(ZoneOffset.UTC);
        for (var peer : recipients) {
            db.update("INSERT INTO account_deletion_events(id,recipient,recipient_account_id,username,account_id,identity_key,deleted_at) "
                + "SELECT ?,username,account_id,?,?,?,? FROM app_users WHERE username=? AND account_id=?",
                UUID.randomUUID().toString(), user, accountId, identityKey, deletedAt, peer.username(), peer.accountId());
        }
        db.update("DELETE FROM messages WHERE sender=? OR recipient=?", user, user);
        db.update("DELETE FROM contacts WHERE user_a=? OR user_b=?", user, user);
        db.update("DELETE FROM one_time_keys WHERE username=?", user);
        db.update("DELETE FROM device_keys WHERE username=?", user);
        db.update("DELETE FROM app_users WHERE username=?", user);
        return Optional.of(new DeletedAccount(accountId, recipients.stream().map(EventRecipient::username).toList()));
    }
    // Called within the reset transaction after the user row has been locked and verified.
    public List<String> resetIdentity(String user, String oldAccountId, String oldIdentity,
                                      String newAccountId, String newIdentity) {
        var recipients = db.query("SELECT username,account_id FROM app_users WHERE username<>? AND username IN ("
            + "SELECT CASE WHEN user_a=? THEN user_b ELSE user_a END FROM contacts WHERE user_a=? OR user_b=? "
            + "UNION SELECT CASE WHEN sender=? THEN recipient ELSE sender END FROM messages WHERE sender=? OR recipient=? "
            + "UNION SELECT CASE WHEN user_a=? THEN user_b ELSE user_a END FROM history_peers WHERE user_a=? OR user_b=?) ORDER BY username",
            (r, i) -> new EventRecipient(r.getString("username"), r.getString("account_id")),
            user, user, user, user, user, user, user, user, user, user);
        OffsetDateTime changedAt = OffsetDateTime.now(ZoneOffset.UTC);
        for (var peer : recipients) {
            db.update("INSERT INTO account_deletion_events(id,recipient,recipient_account_id,username,account_id,identity_key,deleted_at,kind,new_account_id,new_identity_key) "
                + "SELECT ?,username,account_id,?,?,?,?,'identity_reset',?,? FROM app_users WHERE username=? AND account_id=?",
                UUID.randomUUID().toString(), user, oldAccountId, oldIdentity, changedAt,
                newAccountId, newIdentity, peer.username(), peer.accountId());
        }
        // Ciphertexts and delivery receipts belong to the lost device generation.
        db.update("DELETE FROM messages WHERE sender=? OR recipient=?", user, user);
        // Password-verified recovery preserves pending peer identity proofs for this logical account.
        db.update("UPDATE account_deletion_events SET recipient_account_id=? WHERE recipient=? AND recipient_account_id=?",
            newAccountId, user, oldAccountId);
        db.update("UPDATE app_users SET account_id=? WHERE username=? AND account_id=?", newAccountId, user, oldAccountId);
        return recipients.stream().map(EventRecipient::username).toList();
    }
    public boolean currentGeneration(String user, String accountId) {
        return db.queryForList("SELECT account_id FROM app_users WHERE username=?", String.class, user).contains(accountId);
    }
    public List<AccountDeletionEvent> accountEvents(String user, String accountId) {
        return db.query("SELECT e.* FROM account_deletion_events e JOIN app_users u ON u.username=e.recipient AND u.account_id=e.recipient_account_id "
            + "WHERE e.recipient=? AND e.recipient_account_id=? ORDER BY e.deleted_at,e.id",
            (r, i) -> new AccountDeletionEvent(r.getString("id"), r.getString("username"), r.getString("account_id"),
                r.getString("identity_key"), r.getObject("deleted_at", OffsetDateTime.class).toString(),
                r.getString("kind"), r.getString("new_account_id"), r.getString("new_identity_key")), user, accountId);
    }
    @Transactional
    public void acknowledgeAccountEvents(String user, String accountId, List<String> ids) {
        for (String id : ids) db.update("DELETE FROM account_deletion_events WHERE id=? AND recipient=? AND recipient_account_id=?",
            id, user, accountId);
    }
    // Lock both user rows in a stable order so concurrent server instances cannot create
    // two pending messages or opposite-direction requests for the same pair.
    private boolean lockPair(String a, String b) {
        String first = a.compareTo(b) < 0 ? a : b, second = first.equals(a) ? b : a;
        if (db.queryForList("SELECT username FROM app_users WHERE username=? FOR UPDATE", String.class, first).isEmpty()) return false;
        return !db.queryForList("SELECT username FROM app_users WHERE username=? FOR UPDATE", String.class, second).isEmpty();
    }
    private String[] pair(String a, String b) {
        return a.compareTo(b) < 0 ? new String[]{a, b} : new String[]{b, a};
    }
    @Transactional
    public SaveResult save(String sender, String recipient, String clientId, String ciphertext) {
        return save(sender, recipient, clientId, ciphertext, null, null);
    }
    @Transactional
    public SaveResult save(String sender, String recipient, String clientId, String ciphertext, String senderAccountId) {
        return save(sender, recipient, clientId, ciphertext, senderAccountId, null);
    }
    @Transactional
    public SaveResult save(String sender, String recipient, String clientId, String ciphertext, String senderAccountId, String recipientAccountId) {
        if (!lockPair(sender, recipient)) throw new IllegalArgumentException("用户不存在，请重新登录");
        if (senderAccountId != null && !currentGeneration(sender, senderAccountId))
            throw new IllegalArgumentException("设备身份已更新，请重新登录");
        if (recipientAccountId != null && !currentGeneration(recipient, recipientAccountId))
            throw new IllegalArgumentException("对方设备身份已更新，请重新核对后发送");
        var existing = db.query("SELECT * FROM messages WHERE sender=? AND client_id=?", this::map, sender, clientId);
        if (!existing.isEmpty()) {
            Message old = existing.get(0);
            if (!old.recipient().equals(recipient) || !old.ciphertext().equals(ciphertext)) throw new IllegalArgumentException("clientId 已用于另一条消息");
            return new SaveResult(old, false);
        }
        String[] pair = pair(sender, recipient);
        var relations = db.queryForList("SELECT accepted FROM contacts WHERE (user_a=? AND user_b=?) OR (user_a=? AND user_b=?)",
            Boolean.class, sender, recipient, recipient, sender);
        boolean newContact = relations.isEmpty();
        if (newContact) db.update("INSERT INTO contacts(user_a,user_b,initiator) VALUES (?,?,?)", pair[0], pair[1], sender);
        else if (!relations.get(0)) throw new IllegalArgumentException("等待对方接受聊天请求");
        if (db.queryForList("SELECT user_a FROM history_peers WHERE (user_a=? AND user_b=?) OR (user_a=? AND user_b=?)", String.class, pair[0], pair[1], pair[1], pair[0]).isEmpty())
            db.update("INSERT INTO history_peers(user_a,user_b) VALUES (?,?)", pair[0], pair[1]);
        db.update("INSERT INTO messages(client_id,sender,recipient,ciphertext,created_at) VALUES (?,?,?,?,?)",
            clientId, sender, recipient, ciphertext, OffsetDateTime.now(ZoneOffset.UTC));
        return new SaveResult(db.query("SELECT * FROM messages WHERE sender=? AND client_id=?", this::map, sender, clientId).get(0), newContact);
    }
    public List<Contact> contacts(String user) {
        return db.query("SELECT user_a,user_b,initiator,accepted FROM contacts WHERE user_a=? OR user_b=? ORDER BY user_a,user_b",
            (r, i) -> {
                String peer = user.equals(r.getString("user_a")) ? r.getString("user_b") : r.getString("user_a");
                String status = r.getBoolean("accepted") ? "accepted" : user.equals(r.getString("initiator")) ? "pending_outgoing" : "pending_incoming";
                return new Contact(peer, status, false);
            }, user, user);
    }
    public Contact contact(String user, String peer) {
        return db.query("SELECT initiator,accepted FROM contacts WHERE (user_a=? AND user_b=?) OR (user_a=? AND user_b=?) "
                + "ORDER BY user_a,user_b LIMIT 1",
            (r, i) -> new Contact(peer, r.getBoolean("accepted") ? "accepted"
                : user.equals(r.getString("initiator")) ? "pending_outgoing" : "pending_incoming", false),
            user, peer, peer, user).stream().findFirst().orElse(null);
    }
    @Transactional
    public void accept(String user, String peer) {
        if (user.equals(peer) || !lockPair(user, peer)) throw new ResponseStatusException(HttpStatus.NOT_FOUND, "聊天请求不存在");
        int changed = db.update("UPDATE contacts SET accepted=TRUE WHERE ((user_a=? AND user_b=?) OR (user_a=? AND user_b=?)) AND initiator=? AND accepted=FALSE",
            user, peer, peer, user, peer);
        if (changed == 0) {
            var state = contact(user, peer);
            if (state == null || !state.status().equals("accepted")) throw new ResponseStatusException(HttpStatus.NOT_FOUND, "聊天请求不存在");
        }
    }
    @Transactional
    public boolean remove(String user, String peer) {
        if (user.equals(peer)) throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "不能移除自己");
        if (!lockPair(user, peer)) throw new ResponseStatusException(HttpStatus.NOT_FOUND, "用户不存在");
        String[] pair = pair(user, peer);
        var relation = db.queryForList("SELECT accepted FROM contacts WHERE user_a=? AND user_b=?",
            Boolean.class, pair[0], pair[1]);
        if (!relation.isEmpty() && !relation.get(0))
            throw new ResponseStatusException(HttpStatus.CONFLICT, "聊天请求尚未同意");
        return db.update("DELETE FROM contacts WHERE user_a=? AND user_b=?", pair[0], pair[1]) > 0;
    }
    public List<Message> pending(String user, long afterId, int limit) {
        return db.query("SELECT * FROM messages WHERE recipient=? AND acknowledged=FALSE AND id>? ORDER BY id LIMIT ?", this::map, user, afterId, limit);
    }
    @Transactional
    public Message acknowledge(String user, long id) {
        return acknowledge(user, id, null);
    }
    @Transactional
    public Message acknowledge(String user, long id, String accountId) {
        if (db.queryForList("SELECT username FROM app_users WHERE username=? FOR UPDATE", String.class, user).isEmpty())
            throw new IllegalArgumentException("用户不存在，请重新登录");
        if (accountId != null && !currentGeneration(user, accountId))
            throw new IllegalArgumentException("设备身份已更新，请重新登录");
        var found = db.query("SELECT * FROM messages WHERE id=? AND recipient=?", this::map, id, user);
        if (found.isEmpty()) throw new IllegalArgumentException("消息不存在或无权确认");
        db.update("UPDATE messages SET acknowledged=TRUE, acknowledged_at=COALESCE(acknowledged_at,CURRENT_TIMESTAMP) WHERE id=? AND recipient=?", id, user);
        return found.get(0);
    }
    public List<Message> history(String user, String peer, long beforeId) {
        return db.query("SELECT * FROM messages WHERE ((sender=? AND recipient=?) OR (sender=? AND recipient=?)) AND id<? ORDER BY id DESC LIMIT 100",
            this::map, user, peer, peer, user, beforeId);
    }
}
