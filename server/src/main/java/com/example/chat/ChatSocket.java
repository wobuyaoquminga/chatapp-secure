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
                if (token.getExpiresAt() == null || !Instant.now().isBefore(token.getExpiresAt()) || !store.userExists(token.getSubject())) {
                    close(c, "invalid token"); return;
                }
                c.user = token.getSubject(); c.expires = token.getExpiresAt();
                emit(c, Map.of("type", "ready", "username", c.user));
                pump(c);
                return;
            }
            if (!Instant.now().isBefore(c.expires)) { close(c, "token expired"); return; }
            switch (type) {
                case "send" -> {
                    clientId = field(request, "clientId");
                    if (!clientId.matches("[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}")) throw new IllegalArgumentException("clientId 必须为 UUID");
                    String peer = field(request, "to"), ciphertext = field(request, "ciphertext");
                    if (!peer.matches("[a-z0-9_]{3,32}") || peer.equals(c.user)) throw new IllegalArgumentException("请选择另一位有效用户");
                    validateCiphertext(ciphertext);
                    if (!store.keysExist(c.user) || !store.keysExist(peer)) throw new IllegalArgumentException("双方必须先初始化加密设备");
                    var message = store.save(c.user, peer, clientId, ciphertext);
                    emit(c, Map.of("type", "accepted", "message", message));
                    for (Connection target : List.copyOf(connections.values())) if (peer.equals(target.user)) pump(target);
                }
                case "ack" -> {
                    String id = field(request, "id");
                    var message = store.acknowledge(c.user, Long.parseLong(id));
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
        } catch (Exception e) { close(c, "connection unavailable"); return false; }
    }
    private void close(Connection c, String reason) {
        connections.remove(c.socket.getId());
        try { c.socket.close(new CloseStatus(1008, reason)); } catch (IOException ignored) { }
    }
    @Scheduled(fixedDelay=1000)
    public synchronized void expireConnections() {
        Instant now = Instant.now();
        for (Connection c : List.copyOf(connections.values())) {
            if (c.user == null && now.isAfter(c.opened.plusSeconds(5))) close(c, "authentication timeout");
            else if (c.expires != null && !now.isBefore(c.expires)) close(c, "token expired");
        }
    }
    @Override public synchronized void afterConnectionClosed(WebSocketSession socket, CloseStatus status) { connections.remove(socket.getId()); }
    @Override public synchronized void handleTransportError(WebSocketSession socket, Throwable error) {
        Connection c = connections.get(socket.getId()); if (c != null) close(c, "transport error");
    }
}
