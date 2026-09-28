package com.example.chat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.time.Instant;
import java.util.*;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.JwtException;
import org.springframework.stereotype.Component;
import org.springframework.web.socket.*;
import org.springframework.web.socket.handler.ConcurrentWebSocketSessionDecorator;
import org.springframework.web.socket.handler.TextWebSocketHandler;

@Component
public class ChatSocket extends TextWebSocketHandler {
    private final ObjectMapper json;
    private final JwtDecoder jwt;
    private final MessageStore store;
    private final Map<String, Connection> connections = new HashMap<>();
    private static class Connection {
        final WebSocketSession socket;
        final Instant opened = Instant.now();
        String user;
        String accountId;
        Instant expires;
        long cursor;
        final Set<String> inFlight = new HashSet<>();
        Connection(WebSocketSession socket) { this.socket = new ConcurrentWebSocketSessionDecorator(socket, 5000, 128 * 1024); }
    }
    public ChatSocket(ObjectMapper json, JwtDecoder jwt, MessageStore store) {
        this.json = json; this.jwt = jwt; this.store = store;
    }
    @Override public synchronized void afterConnectionEstablished(WebSocketSession socket) {
        socket.setTextMessageSizeLimit(131072);
        connections.put(socket.getId(), new Connection(socket));
    }
    @Override protected synchronized void handleTextMessage(WebSocketSession socket, TextMessage frame) {
        Connection c = connections.get(socket.getId());
        if (c == null) return;
        String clientId = "";
        try {
            JsonNode request = json.readTree(frame.getPayload());
            String type = field(request, "type");
            if (c.user == null) {
                if (!type.equals("auth")) { close(c, "authentication required"); return; }
                var token = jwt.decode(field(request, "token"));
                if (token.getExpiresAt() == null || !Instant.now().isBefore(token.getExpiresAt()) ||
                    !store.recordConnection(token.getSubject(), token.getClaimAsString("account_id"))) {
                    close(c, "invalid token"); return;
                }
                boolean becameOnline = !isOnline(token.getSubject());
                c.user = token.getSubject(); c.accountId = token.getClaimAsString("account_id"); c.expires = token.getExpiresAt();
                if (!emit(c, Map.of("type", "ready", "username", c.user))) return;
                if (becameOnline) notifyPresence(c.user, true);
                pump(c);
                return;
            }
            if (!Instant.now().isBefore(c.expires)) { close(c, "token expired"); return; }
            if (!store.currentGeneration(c.user, c.accountId)) { close(c, "identity reset"); return; }
            switch (type) {
                case "send" -> {
                    clientId = field(request, "clientId");
                    if (!clientId.matches("[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}")) throw new IllegalArgumentException("clientId 必须为 UUID");
                    String peer = field(request, "to"), ciphertext = field(request, "ciphertext");
                    if (!Username.valid(peer) || peer.equals(c.user)) throw new IllegalArgumentException("请选择另一位有效用户");
                    validateCiphertext(ciphertext);
                    if (!store.keysExist(c.user) || !store.keysExist(peer)) throw new IllegalArgumentException("双方必须先初始化加密设备");
                    String toAccountId = request.has("toAccountId") ? field(request, "toAccountId") : null;
                    if (toAccountId != null && !toAccountId.matches("[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}"))
                        throw new IllegalArgumentException("toAccountId 必须为 UUID");
                    var saved = store.save(c.user, peer, clientId, ciphertext, c.accountId, toAccountId);
                    emit(c, Map.of("type", "accepted", "message", saved.message()));
                    if (saved.newContact()) notifyContactChanged(c.user, peer);
                    for (Connection target : List.copyOf(connections.values())) if (peer.equals(target.user)) pump(target);
                }
                case "ack" -> {
                    String id = field(request, "id");
                    var message = store.acknowledge(c.user, Long.parseLong(id), c.accountId);
                    emit(c, Map.of("type", "ack_ok", "id", id));
                    for (Connection target : List.copyOf(connections.values())) {
                        if (message.sender().equals(target.user)) emit(target, Map.of("type", "delivered", "id", id));
                        if (c.user.equals(target.user)) { target.inFlight.remove(id); pump(target); }
                    }
                }
                case "ping" -> emit(c, Map.of("type", "pong"));
                default -> throw new IllegalArgumentException("未知消息类型");
            }
        } catch (JwtException e) { close(c, "invalid token"); }
        catch (IllegalArgumentException e) { emit(c, Map.of("type", "error", "error", e.getMessage(), "clientId", clientId)); }
        catch (IOException e) { emit(c, Map.of("type", "error", "error", "JSON 格式错误", "clientId", clientId)); }
        catch (Exception e) {
            // Do not log message bodies or credentials. The client can retry using the same clientId.
            emit(c, Map.of("type", "error", "error", "服务暂时不可用，请重连后重试", "clientId", clientId));
        }
    }
    private void validateCiphertext(String ciphertext) throws IOException {
        if (ciphertext.length() > 65536) throw new IllegalArgumentException("密文超出大小限制");
        JsonNode envelope = json.readTree(ciphertext);
        if (envelope == null || envelope.path("v").asInt() != 1 ||
            (envelope.path("type").asInt() != 2 && envelope.path("type").asInt() != 3))
            throw new IllegalArgumentException("只接受 Signal 密文信封 v1");
        String data = field(envelope, "data");
        if (data.length() < 32 || data.length() > 60000) throw new IllegalArgumentException("密文长度无效");
        Base64.getDecoder().decode(data);
    }
    private String field(JsonNode node, String name) {
        if (node == null || !node.path(name).isTextual()) throw new IllegalArgumentException("字段缺失或类型错误: " + name);
        return node.path(name).textValue();
    }
    private void pump(Connection c) {
        if (!c.socket.isOpen() || c.user == null || !Instant.now().isBefore(c.expires)) return;
        if (!store.currentGeneration(c.user, c.accountId)) { close(c, "identity reset"); return; }
        int capacity = 100 - c.inFlight.size();
        if (capacity <= 0) return;
        for (var message : store.pending(c.user, c.cursor, capacity)) {
            if (!emit(c, Map.of("type", "message", "message", message))) return;
            c.cursor = Long.parseLong(message.id()); c.inFlight.add(message.id());
        }
    }
    private boolean emit(Connection c, Object event) {
        try {
            if (!c.socket.isOpen()) return false;
            c.socket.sendMessage(new TextMessage(json.writeValueAsString(event))); return true;
        } catch (Exception e) { close(c, 1011, "connection unavailable"); return false; }
    }
    public synchronized boolean isOnline(String user) {
        Instant now = Instant.now();
        return connections.values().stream().anyMatch(c -> user.equals(c.user) && c.socket.isOpen() && c.expires != null && now.isBefore(c.expires));
    }
    public synchronized void notifyContactChanged(String a, String b) {
        for (Connection c : List.copyOf(connections.values())) {
            if (!a.equals(c.user) && !b.equals(c.user)) continue;
            String peer = a.equals(c.user) ? b : a;
            var contact = store.contact(c.user, peer);
            emit(c, Map.of("type", "contact", "contact", contact == null
                ? new MessageStore.Contact(peer, "removed", false)
                : new MessageStore.Contact(peer, contact.status(), isOnline(peer))));
        }
    }
    public synchronized void notifyAccountDeleted(String user, String accountId, List<String> peers) {
        for (Connection c : List.copyOf(connections.values())) {
            if (c.user == null || !peers.contains(c.user)) continue;
            for (var event : store.accountEvents(c.user, c.accountId)) {
                if (user.equals(event.username()) && accountId.equals(event.accountId()))
                    emit(c, Map.of("type", event.kind(), "event", event));
            }
        }
    }
    private void notifyPresence(String user, boolean online) {
        for (var contact : store.contacts(user)) {
            for (Connection c : List.copyOf(connections.values()))
                if (contact.username().equals(c.user)) emit(c, Map.of("type", "presence", "username", user, "online", online));
        }
    }
    private void remove(Connection c) {
        if (connections.remove(c.socket.getId()) != null && c.user != null && !isOnline(c.user))
            notifyPresence(c.user, false);
    }
    private void close(Connection c, String reason) {
        close(c, 1008, reason);
    }
    private void close(Connection c, int code, String reason) {
        remove(c);
        try { c.socket.close(new CloseStatus(code, reason)); } catch (IOException ignored) { }
    }
    public synchronized void disconnectDeletedAccount(String user, String accountId) {
        for (Connection c : List.copyOf(connections.values()))
            if (user.equals(c.user) && accountId.equals(c.accountId)) close(c, "account deleted");
    }
    public synchronized void disconnectResetIdentity(String user, String accountId) {
        for (Connection c : List.copyOf(connections.values()))
            if (user.equals(c.user) && accountId.equals(c.accountId)) close(c, "identity reset");
    }
    @Scheduled(fixedDelay=1000)
    public synchronized void expireConnections() {
        Instant now = Instant.now();
        for (Connection c : List.copyOf(connections.values())) {
            if (c.user == null && now.isAfter(c.opened.plusSeconds(5))) close(c, "authentication timeout");
            else if (c.expires != null && !now.isBefore(c.expires)) close(c, "token expired");
        }
    }
    @Override public synchronized void afterConnectionClosed(WebSocketSession socket, CloseStatus status) {
        Connection c = connections.get(socket.getId()); if (c != null) remove(c);
    }
    @Override public synchronized void handleTransportError(WebSocketSession socket, Throwable error) {
        Connection c = connections.get(socket.getId()); if (c != null) close(c, 1011, "transport error");
    }
}
