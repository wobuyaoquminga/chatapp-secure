package com.example.chat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.JwtException;
import org.springframework.stereotype.Component;
import org.springframework.web.socket.*;
import org.springframework.web.socket.handler.ConcurrentWebSocketSessionDecorator;
import org.springframework.web.socket.handler.TextWebSocketHandler;

@Component
public class ChatSocket extends TextWebSocketHandler {
    private static final Logger log = LoggerFactory.getLogger(ChatSocket.class);
    private static final int OUTBOUND_LIMIT = 256;
    private static final int OUTBOUND_BYTES = 512 * 1024;
    private static final ExecutorService writers = new ThreadPoolExecutor(16, 16, 0L, TimeUnit.MILLISECONDS,
        new ArrayBlockingQueue<>(2048), r -> { Thread t = new Thread(r, "chat-ws-writer"); t.setDaemon(true); return t; },
        new ThreadPoolExecutor.AbortPolicy());
    private final ObjectMapper json;
    private final JwtDecoder jwt;
    private final MessageStore store;
    private final Map<String, Connection> connections = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, Set<Connection>> users = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, Rate> rates = new ConcurrentHashMap<>();
    @Value("${chat.ws.send-per-second:20}") private int sendPerSecond = 20;
    @Value("${chat.ws.send-burst:200}") private int sendBurst = 200;
    @Value("${chat.ws.ack-per-second:200}") private int ackPerSecond = 200;
    @Value("${chat.ws.ack-burst:600}") private int ackBurst = 600;
    @Value("${chat.ws.ping-per-second:2}") private int pingPerSecond = 2;
    @Value("${chat.ws.ping-burst:10}") private int pingBurst = 10;
    private static class Rate {
        private volatile long updated = System.nanoTime();
        private double tokens = -1;
        synchronized boolean allow(int rate, int burst) {
            long now = System.nanoTime();
            if (tokens < 0) tokens = burst;
            tokens = Math.min(burst, tokens + (now - updated) / 1_000_000_000d * rate);
            updated = now;
            if (tokens < 1) return false;
            tokens--; return true;
        }
    }
    private void limit(String user, String kind, int rate, int burst) {
        if (!rates.computeIfAbsent(user + ':' + kind, ignored -> new Rate()).allow(Math.max(1, rate), Math.max(1, burst)))
            throw new IllegalArgumentException("操作过于频繁，请稍后重试");
    }
    private static class Connection {
        final WebSocketSession socket;
        final Instant opened = Instant.now();
        volatile String user;
        volatile String accountId;
        volatile Instant expires;
        volatile long sendStarted;
        long cursor;
        long callWindowStarted = System.nanoTime();
        int callsInWindow;
        final Set<String> inFlight = new HashSet<>();
        final AtomicBoolean closed = new AtomicBoolean();
        final AtomicBoolean writing = new AtomicBoolean();
        final Queue<TextMessage> outbound = new ArrayDeque<>();
        int outboundBytes;
        final Object inputLock = new Object();
        final Object deliveryLock = new Object();
        Connection(WebSocketSession socket) { this.socket = new ConcurrentWebSocketSessionDecorator(socket, 5000, 128 * 1024); }
    }
    public ChatSocket(ObjectMapper json, JwtDecoder jwt, MessageStore store) {
        this.json = json; this.jwt = jwt; this.store = store;
    }
    @Override public void afterConnectionEstablished(WebSocketSession socket) {
        socket.setTextMessageSizeLimit(131072);
        connections.put(socket.getId(), new Connection(socket));
    }
    @Override protected void handleTextMessage(WebSocketSession socket, TextMessage frame) {
        Connection c = connections.get(socket.getId());
        if (c == null) return;
        synchronized (c.inputLock) { if (!c.closed.get()) process(c, frame); }
    }
    private void process(Connection c, TextMessage frame) {
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
                AtomicBoolean becameOnline = new AtomicBoolean();
                c.user = token.getSubject(); c.accountId = token.getClaimAsString("account_id"); c.expires = token.getExpiresAt();
                users.compute(c.user, (user, online) -> {
                    if (online == null) { online = ConcurrentHashMap.newKeySet(); becameOnline.set(true); }
                    online.add(c); return online;
                });
                if (!emit(c, Map.of("type", "ready", "username", c.user))) return;
                if (becameOnline.get()) notifyPresence(c.user, true);
                pump(c);
                return;
            }
            if (!Instant.now().isBefore(c.expires)) { close(c, "token expired"); return; }
            if (!store.currentGeneration(c.user, c.accountId)) { close(c, "identity reset"); return; }
            switch (type) {
                case "auth" -> {
                    var token = jwt.decode(field(request, "token"));
                    if (!Objects.equals(c.user, token.getSubject()) || !Objects.equals(c.accountId, token.getClaimAsString("account_id"))
                        || token.getExpiresAt() == null || !Instant.now().isBefore(token.getExpiresAt())) {
                        close(c, "invalid token"); return;
                    }
                    // A continuously connected client renews its token without reconnecting.
                    // Count that authenticated connection before the inactive-account sweep.
                    if (!store.recordConnection(c.user, c.accountId)) {
                        close(c, "identity reset"); return;
                    }
                    c.expires = token.getExpiresAt();
                    emit(c, Map.of("type", "reauthenticated"));
                }
                case "send" -> {
                    clientId = field(request, "clientId");
                    limit(c.user, "send", sendPerSecond, sendBurst);
                    if (!clientId.matches("[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}")) throw new IllegalArgumentException("clientId 必须为 UUID");
                    String peer = field(request, "to"), ciphertext = field(request, "ciphertext");
                    if (!Username.valid(peer) || peer.equals(c.user)) throw new IllegalArgumentException("请选择另一位有效用户");
                    validateCiphertext(ciphertext);
                    if (!store.keysExist(c.user) || !store.keysExist(peer)) throw new IllegalArgumentException("双方必须先初始化加密设备");
                    String toAccountId = field(request, "toAccountId");
                    if (!toAccountId.matches("[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}"))
                        throw new IllegalArgumentException("toAccountId 必须为 UUID");
                    var saved = store.save(c.user, peer, clientId, ciphertext, c.accountId, toAccountId);
                    emit(c, Map.of("type", "accepted", "message", saved.message()));
                    if (saved.newContact()) notifyContactChanged(c.user, peer);
                    for (Connection target : userConnections(peer)) pump(target);
                }
                case "ack" -> {
                    limit(c.user, "ack", ackPerSecond, ackBurst);
                    String id = field(request, "id");
                    var message = store.acknowledge(c.user, Long.parseLong(id), c.accountId);
                    emit(c, Map.of("type", "ack_ok", "id", id));
                    for (Connection target : userConnections(message.sender())) emit(target, Map.of("type", "delivered", "id", id));
                    for (Connection target : userConnections(c.user)) {
                        synchronized (target.deliveryLock) { target.inFlight.remove(id); pump(target); }
                    }
                }
                case "ping" -> { limit(c.user, "ping", pingPerSecond, pingBurst); emit(c, Map.of("type", "pong")); }
                case "call" -> handleCall(c, request);
                default -> throw new IllegalArgumentException("未知消息类型");
            }
        } catch (JwtException e) { close(c, "invalid token"); }
        catch (IllegalArgumentException e) { emit(c, Map.of("type", "error", "error", Objects.requireNonNullElse(e.getMessage(), "请求参数无效"), "clientId", clientId)); }
        catch (IOException e) { emit(c, Map.of("type", "error", "error", "JSON 格式错误", "clientId", clientId)); }
        catch (Exception e) {
            // Only the class is logged: exception messages and causes can contain SQL or credentials.
            log.warn("WebSocket operation failed user={} exception={}", c.user, e.getClass().getSimpleName());
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
        synchronized (c.deliveryLock) {
        if (c.closed.get() || !c.socket.isOpen() || c.user == null || !Instant.now().isBefore(c.expires)) return;
        if (!store.currentGeneration(c.user, c.accountId)) { close(c, "identity reset"); return; }
        int capacity = 100 - c.inFlight.size();
        if (capacity <= 0) return;
        for (var message : store.pending(c.user, c.cursor, capacity)) {
            // A full queue pauses this cursor; the writer pumps again after each successful send.
            // Delivery is only acknowledged by the recipient, never by enqueueing a frame.
            if (!emit(c, Map.of("type", "message", "message", message), false)) return;
            c.cursor = Long.parseLong(message.id()); c.inFlight.add(message.id());
        }
        }
    }
    private boolean emit(Connection c, Object event) {
        return emit(c, event, true);
    }
    private boolean emit(Connection c, Object event, boolean closeOnOverflow) {
        try {
            TextMessage message = new TextMessage(json.writeValueAsString(event));
            boolean full;
            synchronized (c.outbound) {
                if (c.closed.get() || !c.socket.isOpen()) return false;
                full = c.outbound.size() >= OUTBOUND_LIMIT || c.outboundBytes + message.getPayloadLength() > OUTBOUND_BYTES;
                if (!full) { c.outbound.add(message); c.outboundBytes += message.getPayloadLength(); }
            }
            if (full) { if (closeOnOverflow) close(c, 1011, "outbound queue full"); return false; }
            if (c.writing.compareAndSet(false, true)) writers.execute(() -> drain(c));
            return true;
        } catch (Exception e) { close(c, 1011, "connection unavailable"); return false; }
    }
    private void drain(Connection c) {
        try {
            while (!c.closed.get()) {
                TextMessage next;
                synchronized (c.outbound) {
                    next = c.outbound.poll();
                    if (next == null) { c.writing.set(false); return; }
                    c.outboundBytes -= next.getPayloadLength();
                }
                c.sendStarted = System.nanoTime();
                try { c.socket.sendMessage(next); }
                finally { c.sendStarted = 0; }
                pump(c);
            }
        } catch (Exception e) { close(c, 1011, "connection unavailable"); }
        finally { if (c.closed.get()) synchronized (c.outbound) { c.outbound.clear(); c.outboundBytes = 0; c.writing.set(false); } }
    }
    private List<Connection> userConnections(String user) {
        Set<Connection> online = users.get(user);
        return online == null ? List.of() : List.copyOf(online);
    }
    public boolean isOnline(String user) {
        Instant now = Instant.now();
        return userConnections(user).stream().anyMatch(c -> !c.closed.get() && c.socket.isOpen() && c.expires != null && now.isBefore(c.expires));
    }
    public void notifyContactChanged(String a, String b) {
        List<Connection> targets = new ArrayList<>(userConnections(a)); targets.addAll(userConnections(b));
        for (Connection c : targets) {
            if (!a.equals(c.user) && !b.equals(c.user)) continue;
            String peer = a.equals(c.user) ? b : a;
            var contact = store.contact(c.user, peer);
            emit(c, Map.of("type", "contact", "contact", contact == null
                ? new MessageStore.Contact(peer, "removed", false)
                : new MessageStore.Contact(peer, contact.status(), isOnline(peer))));
        }
    }
    public void notifyAccountDeleted(String user, String accountId, List<String> peers) {
        for (String peer : peers) for (Connection c : userConnections(peer)) {
            if (c.user == null || !peers.contains(c.user)) continue;
            for (var event : store.accountEvents(c.user, c.accountId)) {
                if (user.equals(event.username()) && accountId.equals(event.accountId()))
                    emit(c, Map.of("type", event.kind(), "event", event));
            }
        }
    }
    private void notifyPresence(String user, boolean online) {
        for (var contact : store.contacts(user)) {
            for (Connection c : userConnections(contact.username()))
                emit(c, Map.of("type", "presence", "username", user, "online", online));
        }
    }
    private boolean remove(Connection c) {
        if (!c.closed.compareAndSet(false, true)) return false;
        connections.remove(c.socket.getId(), c);
        if (c.user != null) {
            AtomicBoolean last = new AtomicBoolean();
            users.computeIfPresent(c.user, (user, online) -> {
                online.remove(c);
                if (online.isEmpty()) { last.set(true); return null; }
                return online;
            });
            if (last.get()) try { notifyPresence(c.user, false); }
            catch (Exception e) { log.warn("WebSocket presence failed user={} exception={}", c.user, e.getClass().getSimpleName()); }
        }
        return true;
    }
    private void close(Connection c, String reason) {
        close(c, 1008, reason);
    }
    private void handleCall(Connection sender, JsonNode request) {
        String callId = request.path("callId").isTextual() ? request.path("callId").textValue() : "";
        try {
            if (!request.isObject() || request.size() > 7) throw new IllegalArgumentException("通话字段无效");
            var fields = request.fieldNames();
            while (fields.hasNext())
                if (!Set.of("type", "to", "toAccountId", "callId", "action", "mode", "payload").contains(fields.next()))
                    throw new IllegalArgumentException("通话字段无效");
            callId = field(request, "callId");
            if (callId.length() != 36 || !callId.matches("[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}"))
                throw new IllegalArgumentException("callId 必须为 UUID");
            String peer = field(request, "to");
            if (!Username.valid(peer) || peer.equals(sender.user)) throw new IllegalArgumentException("通话对象无效");
            String accountId = field(request, "toAccountId");
            if (accountId.length() != 36 || !accountId.matches("[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}"))
                throw new IllegalArgumentException("toAccountId 必须为 UUID");
            String action = field(request, "action"), mode = field(request, "mode");
            if (!Set.of("offer", "answer", "ice", "reject", "busy", "hangup").contains(action))
                throw new IllegalArgumentException("通话动作无效");
            if (!Set.of("audio", "video").contains(mode)) throw new IllegalArgumentException("通话模式无效");
            boolean hasPayload = request.has("payload");
            String payload = hasPayload ? field(request, "payload") : null;
            if (Set.of("offer", "answer", "ice").contains(action) && (payload == null || payload.isBlank()))
                throw new IllegalArgumentException("通话信令内容缺失");
            if (!Set.of("offer", "answer", "ice").contains(action) && hasPayload)
                throw new IllegalArgumentException("此通话动作不允许携带内容");
            int payloadLimit = "ice".equals(action) ? 4096 : 65536;
            if (payload != null && (payload.length() > payloadLimit || payload.isBlank()))
                throw new IllegalArgumentException("通话信令内容无效");
            long now = System.nanoTime();
            if (now - sender.callWindowStarted >= 1_000_000_000L) {
                sender.callWindowStarted = now; sender.callsInWindow = 0;
            }
            // A multi-homed Windows host legitimately gathers ~30 ICE candidates before a call can
            // start; clients pace their trickle at 20 signals per second, so this bound only stops
            // abuse while leaving headroom for one unpaced candidate burst.
            if (++sender.callsInWindow > 60) throw new IllegalArgumentException("通话信令发送过于频繁");
            if (!store.currentGeneration(peer, accountId)) throw new IllegalArgumentException("对方设备身份已更新");
            var contact = store.contact(sender.user, peer);
            if (contact == null || !"accepted".equals(contact.status())) throw new IllegalArgumentException("双方须先互相接受联系人请求");
            List<Connection> targets = userConnections(peer).stream()
                .filter(c -> peer.equals(c.user) && accountId.equals(c.accountId) && c.socket.isOpen()
                    && c.expires != null && Instant.now().isBefore(c.expires))
                .toList();
            if (targets.isEmpty()) throw new IllegalArgumentException("对方当前不在线");
            Map<String, Object> event = new HashMap<>(Map.of("type", "call", "from", sender.user,
                "fromAccountId", sender.accountId, "callId", callId, "action", action, "mode", mode));
            if (payload != null) event.put("payload", payload);
            boolean delivered = false;
            for (Connection target : targets) delivered |= emit(target, event);
            if (!delivered) throw new IllegalArgumentException("对方当前不在线");
        } catch (IllegalArgumentException e) {
            emit(sender, Map.of("type", "call_error", "callId", callId.length() <= 36 ? callId : "", "error", e.getMessage()));
        }
    }
    private void close(Connection c, int code, String reason) {
        if (!remove(c)) return;
        try { c.socket.close(new CloseStatus(code, reason)); } catch (IOException ignored) { }
    }
    public void disconnectDeletedAccount(String user, String accountId) {
        for (Connection c : userConnections(user))
            if (user.equals(c.user) && accountId.equals(c.accountId)) close(c, "account deleted");
    }
    public void disconnectResetIdentity(String user, String accountId) {
        for (Connection c : userConnections(user))
            if (user.equals(c.user) && accountId.equals(c.accountId)) close(c, "identity reset");
    }
    @Scheduled(fixedDelay=1000)
    public void expireConnections() {
        Instant now = Instant.now();
        rates.entrySet().removeIf(e -> System.nanoTime() - e.getValue().updated > TimeUnit.MINUTES.toNanos(5));
        for (Connection c : List.copyOf(connections.values())) {
            if (!c.socket.isOpen()) remove(c);
            else if (c.sendStarted != 0 && System.nanoTime() - c.sendStarted > TimeUnit.SECONDS.toNanos(5))
                close(c, 1011, "send timeout");
            else if (c.user == null && now.isAfter(c.opened.plusSeconds(5))) close(c, "authentication timeout");
            else if (c.expires != null && !now.isBefore(c.expires)) close(c, "token expired");
        }
    }
    @Override public void afterConnectionClosed(WebSocketSession socket, CloseStatus status) {
        Connection c = connections.get(socket.getId()); if (c != null) remove(c);
    }
    @Override public void handleTransportError(WebSocketSession socket, Throwable error) {
        Connection c = connections.get(socket.getId()); if (c != null) close(c, 1011, "transport error");
    }
}
