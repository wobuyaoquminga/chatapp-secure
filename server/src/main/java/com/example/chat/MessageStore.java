package com.example.chat;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

@Repository
public class MessageStore {
    private final JdbcTemplate db;
    public MessageStore(JdbcTemplate db) { this.db = db; }
    public record Message(String id, String clientId, String sender, String recipient, String ciphertext,
                          String createdAt, boolean acknowledged) {}
    private Message map(ResultSet r, int row) throws SQLException {
        return new Message(r.getString("id"), r.getString("client_id"), r.getString("sender"),
            r.getString("recipient"), r.getString("ciphertext"), r.getObject("created_at", OffsetDateTime.class).toString(), r.getBoolean("acknowledged"));
    }
    public boolean keysExist(String user) {
        return Boolean.TRUE.equals(db.queryForObject("SELECT COUNT(*) > 0 FROM device_keys WHERE username=?", Boolean.class, user));
    }
    public boolean userExists(String name) {
        return Boolean.TRUE.equals(db.queryForObject("SELECT COUNT(*) > 0 FROM app_users WHERE username=?", Boolean.class, name));
    }
    // Called under ChatSocket's single-node lock: commit precedes network delivery.
    public Message save(String sender, String recipient, String clientId, String ciphertext) {
        var existing = db.query("SELECT * FROM messages WHERE sender=? AND client_id=?", this::map, sender, clientId);
        if (!existing.isEmpty()) {
            Message old = existing.get(0);
            if (!old.recipient().equals(recipient) || !old.ciphertext().equals(ciphertext)) throw new IllegalArgumentException("clientId 已用于另一条消息");
            return old;
        }
        if (!userExists(recipient)) throw new IllegalArgumentException("接收用户不存在");
        db.update("INSERT INTO messages(client_id,sender,recipient,ciphertext,created_at) VALUES (?,?,?,?,?)",
            clientId, sender, recipient, ciphertext, OffsetDateTime.now(ZoneOffset.UTC));
        return db.query("SELECT * FROM messages WHERE sender=? AND client_id=?", this::map, sender, clientId).get(0);
    }
    public List<Message> pending(String user, long afterId, int limit) {
        return db.query("SELECT * FROM messages WHERE recipient=? AND acknowledged=FALSE AND id>? ORDER BY id LIMIT ?", this::map, user, afterId, limit);
    }
    public Message acknowledge(String user, long id) {
        var found = db.query("SELECT * FROM messages WHERE id=? AND recipient=?", this::map, id, user);
        if (found.isEmpty()) throw new IllegalArgumentException("消息不存在或无权确认");
        db.update("UPDATE messages SET acknowledged=TRUE WHERE id=? AND recipient=?", id, user);
        return found.get(0);
    }
    public List<Message> history(String user, String peer, long beforeId) {
        return db.query("SELECT * FROM messages WHERE ((sender=? AND recipient=?) OR (sender=? AND recipient=?)) AND id<? ORDER BY id DESC LIMIT 100",
            this::map, user, peer, peer, user, beforeId);
    }
}
