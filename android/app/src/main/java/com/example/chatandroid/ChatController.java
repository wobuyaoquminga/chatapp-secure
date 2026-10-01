package com.example.chatandroid;

import android.content.Context;
import android.os.Handler;
import android.os.Looper;

import org.json.JSONArray;
import org.json.JSONObject;

import java.net.InetAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Iterator;
import java.util.List;
import java.util.TreeSet;
import java.util.concurrent.Callable;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.ThreadLocalRandom;

import okhttp3.MediaType;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;
import okhttp3.WebSocket;
import okhttp3.WebSocketListener;
import okio.ByteString;

/** Serializes all Signal operations, persistence and protocol events on one worker. */
final class ChatController {
    interface Listener {
        void onState(JSONObject snapshot, String error);
        void onSafety(String peer, JSONObject result);
        void onSent(String draftKey, String body, long draftRevision);
        void onCall(JSONObject frame, long context);
        void onCallReady(CallSession session, JSONArray iceServers, long context);
        void onCallContextLost();
    }

    private static final MediaType JSON = MediaType.get("application/json; charset=utf-8");
    private final Context context;
    private final Listener listener;
    private final AccountRegistry accounts;
    private final Handler main = new Handler(Looper.getMainLooper());
    private final ScheduledExecutorService worker = Executors.newSingleThreadScheduledExecutor();
    private final OkHttpClient client = new OkHttpClient.Builder()
            .followRedirects(false).followSslRedirects(false)
            .connectTimeout(10, TimeUnit.SECONDS).readTimeout(15, TimeUnit.SECONDS)
            .pingInterval(20, TimeUnit.SECONDS)
            .build();
    private volatile boolean closed;
    private String server;
    private String username;
    private String token; // Never persisted.
    private String refreshToken, authAccountId;
    private long tokenExpiresAt, authSession;
    private ScheduledFuture<?> refreshTask, refreshAckTimeout;
    private boolean foreground;
    private String visiblePeer = "";

    void setVisibleConversation(boolean visible, String peer) {
        execute(() -> {
            foreground = visible;
            visiblePeer = visible && peer != null ? peer : "";
            if (engine != null && !visiblePeer.isEmpty()) {
                JSONObject unread = engine.state().optJSONObject("unread");
                if (unread != null && unread.optInt(visiblePeer) > 0) {
                    transaction(() -> { unread.remove(visiblePeer); return null; });
                    MessageNotifier.cancel(context, visiblePeer);
                    publish("");
                }
            }
        });
    }

    private void acceptAuth(JSONObject auth) throws Exception {
        String nextToken = auth.getString("token"), nextAccount = auth.optString("accountId", "");
        String nextRefresh = auth.optString("refreshToken", "");
        long expiry = 0;
        if (nextToken.isEmpty()) throw new SecurityException("服务器认证凭据无效");
        if (!nextRefresh.isEmpty()) {
            if (!nextRefresh.matches("[A-Za-z0-9_-]{43}")) throw new SecurityException("服务器续期凭据无效");
            expiry = java.time.Instant.parse(auth.getString("expiresAt")).toEpochMilli();
            if (expiry <= System.currentTimeMillis()) throw new SecurityException("服务器认证凭据已过期");
        }
        token = nextToken; authAccountId = nextAccount; refreshToken = nextRefresh; tokenExpiresAt = expiry;
        if (nextRefresh.isEmpty()) cancelRefresh(); else scheduleRefresh(0);
    }

    private void scheduleRefresh(int retry) {
        if (refreshTask != null) refreshTask.cancel(false);
        if (closed || refreshToken == null || refreshToken.isEmpty() || token == null) return;
        long epoch = authSession;
        long remaining = tokenExpiresAt - System.currentTimeMillis();
        long delay = AuthRenewal.delay(remaining, retry);
        refreshTask = worker.schedule(() -> {
            refreshTask = null;
            if (closed || epoch != authSession || token == null) return;
            try {
                JSONObject auth = requestObject("/api/auth/refresh", "POST", new JSONObject().put("refreshToken", refreshToken));
                if (!username.equals(auth.getString("username")) || !authAccountId.equals(auth.getString("accountId"))
                        || auth.optString("refreshToken").isEmpty())
                    throw new SecurityException("续期返回的账号不匹配");
                acceptAuth(auth);
                WebSocket active = socket;
                if (online && active != null) {
                    if (!active.send(new JSONObject().put("type", "auth").put("token", token).toString()))
                        throw new Exception("连接续期发送失败");
                    if (refreshAckTimeout != null) refreshAckTimeout.cancel(false);
                    refreshAckTimeout = worker.schedule(() -> {
                        if (epoch == authSession && socket == active)
                            stopConnection("连接续期未获确认，请重新登录", new Exception("续期确认超时"));
                    }, 20, TimeUnit.SECONDS);
                }
            } catch (Exception error) {
                if (epoch != authSession) return;
                if (!(error instanceof SecurityException) && !isAuthenticationFailure(error)
                        && retry < 3 && System.currentTimeMillis() < tokenExpiresAt) scheduleRefresh(retry + 1);
                else stopConnection("认证续期失败，请重新登录", error);
            }
        }, delay, TimeUnit.MILLISECONDS);
    }

    private void cancelRefresh() {
        if (refreshTask != null) { refreshTask.cancel(false); refreshTask = null; }
        if (refreshAckTimeout != null) { refreshAckTimeout.cancel(false); refreshAckTimeout = null; }
    }
    private String selectedPeer = "";
    private final JSONObject relationships = new JSONObject();
    private String status = "未登录";
    private boolean online;
    private boolean storageFailed;
    private boolean reconnectPending;
    private final ReconnectBackoff backoff = new ReconnectBackoff();
    private ScheduledFuture<?> reconnectTask;
    private ScheduledFuture<?> authTimeout;
    private SecureVault vault;
    private SignalEngine engine;
    private volatile WebSocket socket;
    private volatile long generation;
    private String livePeer = "";
    private volatile String liveSession = "";
    private long liveExpiry, lastLiveSent;
    private int liveSeq;
    private java.util.concurrent.ScheduledFuture<?> liveTimeout;
    private volatile Runnable locationStopped;
    private long messagesRevision;
    private long cachedHistoryRevision = -1;
    private JSONArray cachedMessages = new JSONArray();
    private SignalEngine cachedEngine;
    private int messagesAppendFrom = -1;
    private long messagesBaseRevision;
    private final java.util.Map<String,Long> messageSourceCache = new java.util.HashMap<>();
    private final java.util.Map<String,JSONObject> cleanMessageCache = new java.util.HashMap<>();
    void setLocationStoppedListener(Runnable stopped) { locationStopped = stopped; }
    long locationContext() { return generation; }
    void prepareCall(String peer, String mode) {
        execute(() -> {
            String account = requireCallPeer(peer);
            CallSession session = new CallSession(java.util.UUID.randomUUID().toString(), peer, account, mode, true);
            JSONArray ice = iceServers();
            long context = generation;
            main.post(() -> listener.onCallReady(session, ice, context));
        });
    }
    void prepareIncomingCall(CallSession session, long expectedContext) {
        execute(() -> {
            if (expectedContext != generation || !session.accountId.equals(requireCallPeer(session.peer))) return;
            JSONArray ice = iceServers();
            main.post(() -> listener.onCallReady(session, ice, expectedContext));
        });
    }
    private JSONArray iceServers() {
        try {
            JSONArray list = requestObject("/api/calls/ice", "GET", null).optJSONArray("iceServers");
            if (list != null && list.length() > 0) return list;
        } catch (Exception ignored) { /* Public STUN is a best effort fallback. */ }
        return new JSONArray().put(new JSONObject(java.util.Map.of("urls", "stun:stun.l.google.com:19302")));
    }
    void sendCall(CallSession session, String action, String payload, long expectedContext) {
        execute(() -> {
            if (expectedContext != generation || !session.accountId.equals(requireCallPeer(session.peer))) return;
            WebSocket current = socket;
            if (current == null || !current.send(session.frame(action, payload).toString()))
                throw new Exception("通话连接已断开");
        });
    }
    private String requireCallPeer(String peer) throws Exception {
        requirePeer(peer);
        if (!online || !"accepted".equals(relationship(peer))) throw new Exception("通话需要双方已接受联系人请求并在线");
        if (engine.isDeleted(peer) || engine.state().getJSONObject("identityChanges").has(peer))
            throw new Exception("请先核对联系人设备身份");
        String account = engine.state().getJSONObject("peerAccountIds").optString(peer);
        if (account.isEmpty()) throw new Exception("请先在此会话发送消息并确认联系人身份");
        return account;
    }
    void sendPin(String peer, double latitude, double longitude, double accuracy) { sendPin(peer,latitude,longitude,accuracy,generation); }
    void sendPin(String peer, double latitude, double longitude, double accuracy, long expectedContext) {
        execute(() -> {
            if (expectedContext != generation) throw new Exception("账号或连接已改变，请重新获取位置");
            requireLocationPeer(peer);
            long now = System.currentTimeMillis();
            sendBody(peer, LocationPayload.encode("pin", java.util.UUID.randomUUID().toString(),
                    0, latitude, longitude, accuracy, now, now + LocationPayload.MAX_DURATION), false);
        });
    }
    void startLive(String peer) { startLive(peer,generation); }
    void startLive(String peer, long expectedContext) {
        execute(() -> {
            stopLiveInternal(false);
            try {
                if (expectedContext != generation) throw new Exception("账号或连接已改变，请重新发起分享");
                requireLocationPeer(peer);
                livePeer = peer; liveSession = java.util.UUID.randomUUID().toString();
                liveExpiry = System.currentTimeMillis() + LocationPayload.MAX_DURATION;
                liveSeq = 0; lastLiveSent = 0;
                String session = liveSession;
                liveTimeout = worker.schedule(() -> { if (session.equals(liveSession)) { stopLiveInternal(); publish(""); } }, LocationPayload.MAX_DURATION, TimeUnit.MILLISECONDS);
                publish("");
            } catch (Exception error) { notifyLocationStopped(); throw error; }
        });
    }
    void sendLiveLocation(double latitude, double longitude, double accuracy) {
        execute(() -> {
            long now = System.currentTimeMillis();
            if (liveSession.isEmpty()) return;
            if (now >= liveExpiry || !online) { stopLiveInternal(); publish(""); return; }
            if (now - lastLiveSent < 10000) return;
            try {
                requireLocationPeer(livePeer);
                sendBody(livePeer, LocationPayload.encode("live", liveSession, liveSeq++, latitude, longitude, accuracy, now, liveExpiry), true);
                lastLiveSent = now;
            } catch (Exception error) { stopLiveInternal(); throw error; }
        });
    }
    void stopLive() { execute(() -> { stopLiveInternal(); publish(""); }); }
    void stopLiveForPin() { execute(() -> { stopLiveInternal(false); publish(""); }); }
    private void requireLocationPeer(String peer) throws Exception {
        requirePeer(peer);
        if (!online || !relationship(peer).equals("accepted")) throw new Exception("实时位置仅可发送给已同意聊天的联系人，请先连接服务器");
        if (engine.isDeleted(peer) || engine.state().getJSONObject("identityChanges").has(peer)) throw new Exception("请先核对联系人设备身份");
    }
    private void notifyLocationStopped() {
        Runnable stopped = locationStopped;
        if (stopped != null) main.post(stopped);
    }
    private void stopLiveInternal() { stopLiveInternal(true); }
    private void stopLiveInternal(boolean notify) {
        if (liveTimeout != null) { liveTimeout.cancel(false); liveTimeout = null; }
        if (liveSession.isEmpty()) return;
        String peer = livePeer, session = liveSession; int sequence = liveSeq;
        long now = System.currentTimeMillis();
        livePeer = ""; liveSession = ""; liveExpiry = 0;
        if (notify) notifyLocationStopped();
        if (online && engine != null) {
            try { sendBody(peer, LocationPayload.encode("stop",session,sequence,0,0,0,now,now),true); }
            catch (Exception ignored) { /* Receiver expiry remains authoritative. */ }
        }
    }

    ChatController(Context context, Listener listener) {
        this.context = context.getApplicationContext();
        this.listener = listener;
        this.accounts = new AccountRegistry(this.context);
        worker.execute(() -> publish(""));
    }

    void login(String address, String user, String password, boolean register, boolean allowLanTest) {
        execute(() -> {
            clearSession();
            storageFailed = false;
            server = validateServer(address, allowLanTest);
            if (!validUser(user)) throw new Exception("用户名限 2–32 位汉字、小写字母、数字、下划线");
            if (password == null || password.length() < 8
                    || password.getBytes(StandardCharsets.UTF_8).length > 72)
                throw new Exception("密码至少 8 字符、最多 72 UTF-8 字节");
            JSONObject credentials = new JSONObject().put("username", user).put("password", password);
            try {
                JSONObject auth = requestObject("/api/auth/" + (register ? "register" : "login"),
                        "POST", credentials);
                acceptAuth(auth);
                username = user;
                vault = new SecureVault(context, server, username);
                JSONObject saved = vault.read();
                engine = saved == null ? SignalEngine.create(username) : new SignalEngine(saved);
                if (!username.equals(engine.state().getString("username")))
                    throw new Exception("本地账号与密钥不匹配");
                JSONObject own = requestObject("/api/keys/me", "GET", null);
                JSONObject bundle = engine.publicBundle(0);
                String remoteIdentity = own.optString("identityKey", "");
                if (!remoteIdentity.isEmpty() && !remoteIdentity.equals(bundle.getString("identityKey"))) {
                    SignalEngine replacement = engine.stageIdentityReset();
                    JSONObject resetBundle = replacement.publicBundle(
                            replacement.state().getJSONArray("pendingUpload").length() == 0 ? 30 : 0);
                    // Candidate keys and the old history stay in the existing encrypted vault.
                    engine.saveIdentityResetCandidate(replacement);
                    vault.write(engine.state());
                    if (!remoteIdentity.equals(resetBundle.getString("identityKey"))) {
                        JSONObject reset = requestObject("/api/keys/reset", "POST",
                                new JSONObject().put("password", password).put("bundle", resetBundle));
                        if (!username.equals(reset.getString("username")))
                            throw new Exception("服务器返回的账号不匹配");
                        acceptAuth(reset);
                        replacement.state().put("accountId", reset.getString("accountId"));
                    } else {
                        // A previous reset succeeded before the final local write completed.
                        replacement.state().put("accountId", auth.getString("accountId"));
                    }
                    replacement.state().put("pendingUpload", new JSONArray());
                    engine = replacement;
                    vault.write(engine.state());
                    own = requestObject("/api/keys/me", "GET", null);
                } else {
                    engine.state().remove("pendingIdentityReset");
                    if (auth.has("accountId")) engine.state().put("accountId", auth.getString("accountId"));
                }
                // Never publish a public key until its private half survives a durable write.
                vault.write(engine.state());
                accounts.remember(server, username);
                replenish(own);
                status = "连接中";
                publish("");
                connect();
            } catch (Exception error) {
                clearSession();
                throw error;
            }
        });
    }

    void logout() {
        execute(() -> { clearSession(); publish(""); });
    }

    void setServer(String address, boolean allowLanTest) {
        execute(() -> {
            // A previously saved debug LAN address can be selected again; login still
            // requires the explicit LAN HTTP checkbox before credentials are sent.
            boolean saved = accounts.listServers().contains(address);
            String chosen = validateServer(address, allowLanTest || saved);
            accounts.addServer(chosen);
            accounts.selectServer(chosen);
            if (server != null && !server.equals(chosen)) clearSession();
            publish("");
        });
    }

    void removeServer(String address) {
        execute(() -> {
            String chosen = accounts.lastServer();
            accounts.forgetServer(address);
            if (address.equals(server) || address.equals(chosen)) clearSession();
            publish("");
        });
    }

    void forgetAccount(String address, String user) {
        execute(() -> {
            accounts.forgetAccount(address, user);
            publish("");
        });
    }

    void openPeer(String peer) { openPeer(peer, false); }

    void openPeer(String peer, boolean reopen) {
        execute(() -> {
            requirePeer(peer);
            if (engine.isDeleted(peer) && !reopen) {
                selectedPeer = peer;
                publish("");
                return;
            }
            if (!online) throw new Exception("请等待连接恢复");
            JSONObject identity = requestObject("/api/keys/" + Usernames.path(peer), "GET", null);
            transaction(() -> {
                engine.bindPeer(peer, identity, reopen);
                engine.state().optJSONObject("hiddenContacts").remove(peer);
                engine.state().optJSONObject("hiddenConversations").remove(peer);
                return null;
            });
            selectedPeer = peer;
            JSONArray records = requestArray("/api/messages?peer=" + Usernames.path(peer));
            for (int i = records.length() - 1; i >= 0; i--) {
                JSONObject record = records.getJSONObject(i);
                JSONObject messages = engine.state().getJSONObject("messages");
                if (username.equals(record.optString("sender"))
                        && messages.has(username + ":" + record.optString("clientId"))) {
                    transaction(() -> { engine.accepted(record); return null; });
                }
            }
            publish("");
        });
    }

    void send(String peer, String body, String draftKey, long draftRevision) {
        execute(() -> sendBody(peer, body, false, draftKey, draftRevision));
    }

    private void sendBody(String peer, String body, boolean transientLocation) throws Exception {
        sendBody(peer, body, transientLocation, null, 0);
    }

    private void sendBody(String peer, String body, boolean transientLocation,
                          String draftKey, long draftRevision) throws Exception {
            requirePeer(peer);
            if (!online) throw new Exception("请等待连接恢复");
            if (engine.isDeleted(peer)) throw new Exception("该用户已销户，无法向不存在的账号发送消息");
            if (engine.state().getJSONObject("identityChanges").has(peer))
                throw new Exception("设备身份已更新，请先核对新的安全码");
            String relation = relationship(peer);
            if (relation.equals("pending_outgoing")) throw new Exception("已发送首条消息，请等待对方同意");
            if (relation.equals("pending_incoming")) throw new Exception("请先同意对方的聊天请求");
            if (body == null || body.trim().isEmpty() || body.length() > 4000)
                throw new Exception("消息须为 1–4000 字符");
            JSONObject identity = requestObject("/api/keys/" + Usernames.path(peer), "GET", null);
            transaction(() -> {
                engine.bindPeer(peer, identity, false);
                return null;
            });
            if (!engine.hasSession(peer)) {
                JSONObject bundle = requestObject("/api/keys/" + Usernames.path(peer) + "/claim", "POST", new JSONObject());
                transaction(() -> { engine.bindPeer(peer, bundle, false); engine.establish(peer, bundle); return null; });
            }
            JSONObject envelope = transaction(() -> {
                if (body.startsWith(LocationPayload.PREFIX) && engine.state().getJSONObject("peerAccountIds").optString(peer).isEmpty())
                    throw new Exception("联系人账号信息缺失，位置发送已停止");
                JSONObject result = engine.encrypt(peer, body);
                // Ratchet and history are durable; live packets must never replay after reconnect.
                if (transientLocation) engine.state().getJSONObject("outbox").remove(result.getString("clientId"));
                engine.state().optJSONObject("hiddenConversations").remove(peer);
                return result;
            });
            if (relation.isEmpty()) relationships.put(peer, new JSONObject()
                    .put("username", peer).put("status", "pending_outgoing").put("online", false));
            if (!body.startsWith(LocationPayload.PREFIX)) selectedPeer = peer;
            wire(envelope);
            if (draftKey != null) main.post(() -> listener.onSent(draftKey, body, draftRevision));
            publish("");
    }

    void safety(String peer, boolean confirm, String expectedCode) {
        execute(() -> {
            requirePeer(peer);
            if (!online) throw new Exception("请等待连接恢复");
            JSONObject identity = requestObject("/api/keys/" + Usernames.path(peer), "GET", null);
            JSONObject result = transaction(() -> {
                JSONObject checked = engine.bindPeer(peer, identity, false);
                if (confirm) {
                    if (expectedCode == null || !expectedCode.equals(checked.getString("code")))
                        throw new Exception("安全码已改变，请重新核对");
                    engine.state().getJSONObject("verified").put(peer, identity.getString("identityKey"));
                    engine.state().getJSONObject("identityChanges").remove(peer);
                    checked.put("verified", true);
                }
                return checked;
            });
            selectedPeer = peer;
            main.post(() -> listener.onSafety(peer, result));
            publish("");
        });
    }

    void acceptContact(String peer) {
        execute(() -> {
            requirePeer(peer);
            if (!online) throw new Exception("请等待连接恢复");
            if (!relationship(peer).equals("pending_incoming")) throw new Exception("没有待同意的聊天请求");
            requestObject("/api/contacts/accept", "POST", new JSONObject().put("peer", peer));
            JSONObject contact = relationships.optJSONObject(peer);
            contact.put("status", "accepted");
            clearHiddenContact(peer);
            bindAcceptedPeer(peer);
            publish("");
        });
    }

    /**
     * Accepting the request is the moment the peer becomes callable, so learn its account id and
     * safety number now. Without this a call fails with "请先在此会话发送消息" until the user
     * happens to open the chat again. A lookup failure must not undo the accepted relation.
     */
    private void bindAcceptedPeer(String peer) {
        try {
            JSONObject identity = requestObject("/api/keys/" + Usernames.path(peer), "GET", null);
            transaction(() -> { engine.bindPeer(peer, identity, false); return null; });
        } catch (Exception ignored) { }
    }

    void removeContact(String peer) {
        execute(() -> {
            requirePeer(peer);
            requestObject("/api/contacts/remove", "POST", new JSONObject().put("peer", peer));
            if (peer.equals(livePeer)) stopLiveInternal();
            relationships.remove(peer);
            // Older versions only hid contacts locally. A later request must be visible.
            clearHiddenContact(peer);
            publish("");
        });
    }

    void clearConversation(String peer) {
        execute(() -> {
            requirePeer(peer);
            transaction(() -> {
                engine.state().getJSONObject("hiddenConversations").put(peer, true);
                JSONObject unread = engine.state().optJSONObject("unread");
                if (unread != null) unread.remove(peer);
                return null;
            });
            MessageNotifier.cancel(context, peer);
            publish("");
        });
    }

    private String relationship(String peer) {
        JSONObject item = relationships.optJSONObject(peer);
        return item == null ? "" : item.optString("status");
    }

    private void clearHiddenContact(String peer) throws Exception {
        if (engine.state().getJSONObject("hiddenContacts").has(peer))
            transaction(() -> { engine.state().getJSONObject("hiddenContacts").remove(peer); return null; });
    }

    private void refreshContacts() throws Exception {
        JSONArray found = requestArray("/api/contacts");
        JSONObject updated = new JSONObject();
        for (int i = 0; i < found.length(); i++) {
            JSONObject item = found.optJSONObject(i);
            if (item != null && validUser(item.optString("username"))) {
                String peer = item.getString("username");
                prepareContact(peer, item);
                if (!engine.isDeleted(peer)) updated.put(peer, item);
                clearHiddenContact(peer);
            }
        }
        for (java.util.Iterator<String> keys = relationships.keys(); keys.hasNext();) {
            keys.next(); keys.remove();
        }
        for (java.util.Iterator<String> keys = updated.keys(); keys.hasNext();) {
            String peer = keys.next();
            relationships.put(peer, updated.getJSONObject(peer));
        }
    }

    private void prepareContact(String peer, JSONObject contact) throws Exception {
        if (engine.needsDeletedPeerRefresh(peer, contact.optString("status"))) {
            JSONObject identity = requestObject("/api/keys/" + Usernames.path(peer), "GET", null);
            transaction(() -> { engine.bindPeer(peer, identity, true); return null; });
        }
    }

    private void syncAccountEvents(JSONArray events) throws Exception {
        AccountEventSync.run(events, batch -> {
            transaction(() -> {
                for (int i = 0; i < batch.length(); i++) engine.applyAccountEvent(batch.getJSONObject(i));
                return null;
            });
            for (int i = 0; i < batch.length(); i++) {
                String peer = batch.getJSONObject(i).getString("username");
                if (engine.isDeleted(peer)) relationships.remove(peer);
            }
        }, ids -> request("/api/account-events/ack", "POST", new JSONObject().put("ids", ids)));
    }

    private void syncAccountEvents() throws Exception {
        JSONArray events;
        try { events = requestArray("/api/account-events"); }
        catch (HttpFailure error) { if (error.code == 404) return; throw error; }
        syncAccountEvents(events);
    }

    private void pauseAccountSync(Exception error) {
        if (storageFailed || isAuthenticationFailure(error)) {
            stopConnection(storageFailed ? "本地保存失败后已暂停加解密，请退出并重新登录"
                    : "认证已失效，请退出后重新登录", error);
            return;
        }
        online = false;
        stopLiveInternal();
        status = "账号状态同步失败，正在重连";
        WebSocket current = socket;
        if (current != null) current.cancel();
        disconnected(generation, 0);
        publish(status + "：" + readable(error));
    }

    synchronized void close() {
        if (closed) return;
        closed = true;
        notifyLocationStopped();
        worker.execute(this::clearSession);
        worker.shutdown();
        client.dispatcher().executorService().shutdown();
        client.connectionPool().evictAll();
    }

    private void replenish(JSONObject own) throws Exception {
        JSONArray pending = engine.state().optJSONArray("pendingUpload");
        int count = pending != null && pending.length() > 0 ? 0 :
                (own.optInt("remaining", 0) < 10 ? 30 : 0);
        JSONObject bundle = transaction(() -> engine.publicBundle(count));
        requestObject("/api/keys", "PUT", bundle);
        transaction(() -> { engine.state().put("pendingUpload", new JSONArray()); return null; });
    }

    private <T> T transaction(Callable<T> action) throws Exception {
        if (storageFailed) throw new Exception("本地保存失败后已暂停加解密，请退出并重新登录");
        if (engine == null || vault == null) throw new Exception("请先登录");
        boolean committed = false;
        JSONObject previous = SecureVault.protocolCopy(engine.state());
        HistoryRecords history = (HistoryRecords) engine.state().getJSONObject("messages");
        try {
            T result = action.call();
            try {
                vault.write(engine.state());
                committed = true;
                refreshMessageCache();
            } catch (Exception error) {
                storageFailed = true;
                throw error;
            }
            return result;
        } catch (Exception error) {
            if (!committed) {
                history.rollback();
                previous.put("messages", history);
                engine = new SignalEngine(previous);
            }
            throw error;
        }
    }

    private void connect() {
        if (closed || token == null || storageFailed) return;
        cancelReconnect();
        cancelAuthTimeout();
        long epoch = ++generation;
        reconnectPending = false;
        online = false;
        String wsUrl = (server.startsWith("https://") ? "wss://" : "ws://")
                + server.substring(server.indexOf("://") + 3) + "/ws";
        Request request = new Request.Builder().url(wsUrl).build();
        socket = client.newWebSocket(request, new WebSocketListener() {
            @Override public void onOpen(WebSocket webSocket, Response response) {
                post(() -> {
                    if (epoch == generation && token != null) {
                        try {
                            if (!webSocket.send(new JSONObject().put("type", "auth")
                                    .put("token", token).toString())) webSocket.cancel();
                        } catch (Exception error) { webSocket.cancel(); publish(readable(error)); }
                    } else {
                        webSocket.cancel();
                    }
                });
            }

            @Override public void onMessage(WebSocket webSocket, String text) {
                post(() -> {
                    if (epoch != generation) return;
                    try { event(new JSONObject(text)); }
                    catch (Exception error) {
                        if (storageFailed || isAuthenticationFailure(error))
                            stopConnection(storageFailed ? "本地保存失败后已暂停加解密，请退出并重新登录"
                                    : "认证已失效，请退出后重新登录", error);
                        else publish("消息未确认：" + readable(error));
                    }
                });
            }

            @Override public void onMessage(WebSocket webSocket, ByteString bytes) {
                webSocket.close(1003, "text only");
            }

            @Override public void onClosing(WebSocket webSocket, int code, String reason) {
                webSocket.close(code, reason);
            }

            @Override public void onClosed(WebSocket webSocket, int code, String reason) {
                post(() -> disconnected(epoch, code));
            }

            @Override public void onFailure(WebSocket webSocket, Throwable error, Response response) {
                post(() -> disconnected(epoch, response != null ? response.code() : 0));
            }
        });
        authTimeout = worker.schedule(() -> {
            if (epoch == generation && !online && socket != null) {
                socket.cancel();
                disconnected(epoch, 0);
            }
        }, 20, TimeUnit.SECONDS);
    }

    private void disconnected(long epoch, int code) {
        if (epoch != generation || reconnectPending || token == null) return;
        cancelAuthTimeout();
        online = false;
        main.post(listener::onCallContextLost);
        stopLiveInternal();
        notifyLocationStopped();
        socket = null;
        if (storageFailed) {
            status = "本地保存失败后已暂停加解密，请退出并重新登录";
            publish("");
            return;
        }
        if (code == 1008 || code == 401 || code == 403) {
            status = "认证已失效，请退出后重新登录";
            publish("");
            return;
        }
        status = "离线，正在重连";
        reconnectPending = true;
        publish("");
        long delay = backoff.nextDelayMillis(ThreadLocalRandom.current().nextDouble());
        reconnectTask = worker.schedule(() -> {
            reconnectTask = null;
            if (!closed && epoch == generation && reconnectPending) connect();
        }, delay, TimeUnit.MILLISECONDS);
    }

    private void event(JSONObject event) throws Exception {
        String type = event.optString("type", "");
        switch (type) {
            case "reauthenticated":
                if (refreshAckTimeout != null) { refreshAckTimeout.cancel(false); refreshAckTimeout = null; }
                return;
            case "ready":
                cancelAuthTimeout();
                online = false;
                try {
                    syncAccountEvents();
                    refreshContacts();
                } catch (Exception error) { pauseAccountSync(error); return; }
                online = true;
                backoff.reset();
                status = "在线 · 消息端到端加密";
                JSONObject outbox = engine.state().getJSONObject("outbox");
                Iterator<String> ids = outbox.keys();
                while (ids.hasNext()) wire(outbox.getJSONObject(ids.next()));
                break;
            case "account_deleted":
            case "identity_reset":
                try { syncAccountEvents(new JSONArray().put(event.getJSONObject("event"))); }
                catch (Exception error) { pauseAccountSync(error); return; }
                break;
            case "message":
                if (!online) return;
                JSONObject message = event.getJSONObject("message");
                String sender = message.getString("sender");
                JSONObject senderIdentity = requestObject("/api/keys/" + Usernames.path(sender), "GET", null);
                boolean newIncoming = !engine.state().getJSONObject("messages").has(sender + ":" + message.getString("clientId"));
                transaction(() -> {
                    engine.bindPeer(sender, senderIdentity, false);
                    String key = message.getString("sender") + ":" + message.getString("clientId");
                    boolean isNew = !engine.state().getJSONObject("messages").has(key);
                    engine.decrypt(message);
                    if (isNew) {
                        engine.state().getJSONObject("hiddenConversations").remove(sender);
                        if (!(foreground && sender.equals(visiblePeer))) {
                            JSONObject unread = engine.state().optJSONObject("unread");
                            if (unread == null) { unread = new JSONObject(); engine.state().put("unread", unread); }
                            unread.put(sender, Math.min(9999, unread.optInt(sender) + 1));
                        }
                    }
                    return null;
                });
                wire(new JSONObject().put("type", "ack").put("id", message.get("id")));
                if (newIncoming && !foreground) MessageNotifier.show(context, sender);
                break;
            case "contact":
                if (!online) return;
                JSONObject contact = event.getJSONObject("contact");
                String contactName = contact.getString("username");
                if (validUser(contactName)) {
                    if ("removed".equals(contact.optString("status"))) relationships.remove(contactName);
                    else {
                        prepareContact(contactName, contact);
                        if (!engine.isDeleted(contactName)) relationships.put(contactName, contact);
                        clearHiddenContact(contactName);
                    }
                }
                break;
            case "presence":
                JSONObject present = relationships.optJSONObject(event.optString("username"));
                if (present != null) present.put("online", event.optBoolean("online"));
                break;
            case "call":
                if (!online) return;
                String caller = event.optString("from");
                if (validUser(caller) && "accepted".equals(relationship(caller))
                        && engine != null && !engine.isDeleted(caller)
                        && !engine.state().getJSONObject("identityChanges").has(caller)
                        && event.optString("fromAccountId").equals(
                            engine.state().getJSONObject("peerAccountIds").optString(caller))) {
                    long context = generation;
                    main.post(() -> listener.onCall(event, context));
                }
                return;
            case "call_error":
                main.post(() -> listener.onCall(event, generation));
                return;
            case "accepted":
                transaction(() -> { engine.accepted(event.getJSONObject("message")); return null; });
                break;
            case "delivered":
                String id = event.getString("id");
                transaction(() -> {
                    JSONObject messages = engine.state().getJSONObject("messages");
                    Iterator<String> keys = messages.keys();
                    while (keys.hasNext()) {
                        JSONObject item = messages.getJSONObject(keys.next());
                        if (username.equals(item.optString("sender")) && id.equals(item.optString("id")))
                            item.put("status", "对方客户端已接收");
                    }
                    return null;
                });
                break;
            case "error":
                try { refreshContacts(); } catch (Exception ignored) { }
                publish(event.optString("error", "服务器错误") + "；未确认的密文仍在本地待发队列");
                return;
            default:
                break;
        }
        if (!liveSession.isEmpty()) {
            try { requireLocationPeer(livePeer); }
            catch (Exception invalid) { stopLiveInternal(); }
        }
        publish("");
    }

    private void wire(JSONObject frame) {
        WebSocket current = socket;
        if (current != null && online) current.send(frame.toString());
    }

    private JSONObject requestObject(String path, String method, JSONObject body) throws Exception {
        Object value = request(path, method, body);
        if (!(value instanceof JSONObject)) throw new Exception("服务器响应格式错误");
        return (JSONObject) value;
    }

    private JSONArray requestArray(String path) throws Exception {
        Object value = request(path, "GET", null);
        if (!(value instanceof JSONArray)) throw new Exception("服务器响应格式错误");
        return (JSONArray) value;
    }

    private Object request(String path, String method, JSONObject body) throws Exception {
        Request.Builder builder = new Request.Builder().url(server + path).header("Accept", "application/json");
        if (token != null && !path.equals("/api/auth/refresh")) builder.header("Authorization", "Bearer " + token);
        RequestBody requestBody = body == null ? null : RequestBody.create(body.toString(), JSON);
        builder.method(method, requestBody);
        try (Response response = client.newCall(builder.build()).execute()) {
            String raw = response.body() == null ? "" : response.body().string();
            Object decoded;
            try { decoded = raw.startsWith("[") ? new JSONArray(raw) : new JSONObject(raw); }
            catch (Exception error) { decoded = new JSONObject(); }
            if (!response.isSuccessful()) {
                String detail = decoded instanceof JSONObject ? ((JSONObject) decoded).optString("error", "") : "";
                throw new HttpFailure(response.code(), detail.isEmpty() ? "服务器请求失败 (" + response.code() + ")" : detail);
            }
            return decoded;
        }
    }

    private static final class HttpFailure extends Exception {
        final int code;
        HttpFailure(int code, String detail) { super(detail); this.code = code; }
    }

    private void requirePeer(String peer) throws Exception {
        if (engine == null) throw new Exception("请先登录");
        if (!validUser(peer) || peer.equals(username)) throw new Exception("请输入另一位有效用户");
    }

    private void clearSession() {
        ++authSession;
        cancelRefresh();
        refreshToken = null; authAccountId = null; tokenExpiresAt = 0;
        MessageNotifier.cancelAll(context);
        main.post(listener::onCallContextLost);
        cancelReconnect();
        cancelAuthTimeout();
        backoff.reset();
        stopLiveInternal();
        notifyLocationStopped();
        ++generation;
        WebSocket old = socket;
        socket = null;
        if (old != null) old.close(1000, "logout");
        token = null;
        username = null;
        server = null;
        engine = null;
        vault = null;
        selectedPeer = "";
        for (java.util.Iterator<String> keys = relationships.keys(); keys.hasNext();) {
            keys.next(); keys.remove();
        }
        online = false;
        reconnectPending = false;
        status = "未登录";
    }

    private void stopConnection(String reason, Exception error) {
        cancelRefresh();
        main.post(listener::onCallContextLost);
        ++generation;
        cancelReconnect();
        cancelAuthTimeout();
        online = false;
        reconnectPending = false;
        stopLiveInternal();
        notifyLocationStopped();
        WebSocket current = socket;
        socket = null;
        if (current != null) current.cancel();
        status = reason;
        publish(reason + "：" + readable(error));
    }

    private static boolean isAuthenticationFailure(Exception error) {
        return error instanceof HttpFailure
                && (((HttpFailure) error).code == 401 || ((HttpFailure) error).code == 403);
    }

    private void cancelReconnect() {
        if (reconnectTask != null) { reconnectTask.cancel(false); reconnectTask = null; }
    }

    private void cancelAuthTimeout() {
        if (authTimeout != null) { authTimeout.cancel(false); authTimeout = null; }
    }

    private void execute(CheckedAction action) {
        post(() -> {
            try { action.run(); }
            catch (Exception error) {
                if (token != null && (storageFailed || isAuthenticationFailure(error)))
                    stopConnection(storageFailed ? "本地保存失败后已暂停加解密，请退出并重新登录"
                            : "认证已失效，请退出后重新登录", error);
                else publish(readable(error));
            }
        });
    }

    private void post(Runnable task) {
        if (closed) return;
        try { worker.execute(task); }
        catch (RejectedExecutionException ignored) { /* Activity is closing. */ }
    }

    private void refreshMessageCache() throws Exception {
        JSONObject saved = engine == null ? new JSONObject() : engine.state().getJSONObject("messages");
        HistoryRecords history = saved instanceof HistoryRecords ? (HistoryRecords) saved : null;
        long revision = history == null ? 0 : history.revision();
        if (engine == cachedEngine && revision == cachedHistoryRevision) return;
        if (engine != cachedEngine) { cleanMessageCache.clear(); messageSourceCache.clear(); }
        cachedEngine = engine;
        cachedHistoryRevision = revision;
        messagesBaseRevision = messagesRevision; ++messagesRevision;
        List<JSONObject> ordered = new ArrayList<>();
        for (Iterator<String> keys = saved.keys(); keys.hasNext();) {
            String key = keys.next();
            long version = history == null ? 0 : history.version(key);
            JSONObject clean = cleanMessageCache.get(key);
            if (clean == null || !Long.valueOf(version).equals(messageSourceCache.get(key))) {
                clean = new JSONObject(saved.getJSONObject(key).toString()); clean.remove("ciphertext");
                messageSourceCache.put(key,version); cleanMessageCache.put(key,clean);
            }
            ordered.add(clean);
        }
        java.util.Set<String> retainedKeys = new java.util.HashSet<>();
        for (Iterator<String> keys = saved.keys(); keys.hasNext();) retainedKeys.add(keys.next());
        cleanMessageCache.keySet().retainAll(retainedKeys);
        messageSourceCache.keySet().retainAll(retainedKeys);
        Collections.sort(ordered, (a,b) -> a.optString("createdAt").compareTo(b.optString("createdAt")));
        messagesAppendFrom = cachedMessages.length() <= ordered.size() ? cachedMessages.length() : -1;
        for (int i=0; messagesAppendFrom >= 0 && i<cachedMessages.length(); i++) {
            if (cachedMessages.optJSONObject(i) != ordered.get(i)) messagesAppendFrom = -1;
        }
        if (engine == null) { cleanMessageCache.clear(); messageSourceCache.clear(); messagesAppendFrom = -1; }
        cachedMessages = new JSONArray();
        for (JSONObject item : ordered) cachedMessages.put(item);
    }

    private void publish(String error) {
        JSONObject snapshot = new JSONObject();
        try {
            JSONArray servers = new JSONArray();
            for (String address : accounts.listServers()) {
                JSONArray registered = new JSONArray();
                for (String name : accounts.listAccounts(address)) registered.put(name);
                servers.put(new JSONObject().put("address", address).put("accounts", registered));
            }
            snapshot.put("username", username == null ? "" : username)
                    .put("server", server == null ? "" : server)
                    .put("status", status).put("online", online)
                    .put("selectedPeer", selectedPeer)
                    .put("selectedServer", accounts.lastServer()).put("servers", servers);
            if (engine != cachedEngine) refreshMessageCache();
            JSONArray messages = cachedMessages;
            snapshot.put("messagesRevision", messagesRevision).put("messagesBaseRevision",messagesBaseRevision).put("messagesAppendFrom",messagesAppendFrom);
            snapshot.put("locationSharing", new JSONObject().put("active", !liveSession.isEmpty()).put("peer",livePeer)
                    .put("sessionId",liveSession).put("expiresAt",liveExpiry));
            TreeSet<String> contacts = new TreeSet<>();
            TreeSet<String> conversations = new TreeSet<>();
            if (engine != null) {
                snapshot.put("unread", engine.state().optJSONObject("unread") == null ? new JSONObject() : new JSONObject(engine.state().getJSONObject("unread").toString()));
                snapshot.put("deletedPeers", new JSONObject(engine.state().getJSONObject("deletedPeers").toString()));
                snapshot.put("identityChanges", new JSONObject(engine.state().getJSONObject("identityChanges").toString()));
                snapshot.put("peerAccountIds", new JSONObject(engine.state().getJSONObject("peerAccountIds").toString()));
                JSONObject hiddenContacts = engine.state().getJSONObject("hiddenContacts");
                JSONObject hiddenConversations = engine.state().getJSONObject("hiddenConversations");
                Iterator<String> deleted = engine.state().getJSONObject("deletedPeers").keys();
                while (deleted.hasNext()) {
                    String peer = deleted.next();
                    if (!hiddenConversations.optBoolean(peer)) conversations.add(peer);
                }
                Iterator<String> relations = relationships.keys();
                while (relations.hasNext()) {
                    String peer = relations.next();
                    JSONObject relation = relationships.optJSONObject(peer);
                    if (relation != null && relation.optString("status").equals("accepted")
                            && !hiddenContacts.optBoolean(peer) && !engine.isDeleted(peer)) contacts.add(peer);
                    if (!hiddenConversations.optBoolean(peer)) conversations.add(peer);
                }
                for (int i = 0; i < messages.length(); i++) {
                    JSONObject clean = messages.getJSONObject(i);
                    String sender = clean.optString("sender"), recipient = clean.optString("recipient");
                    if (validUser(sender) && !sender.equals(username) && !hiddenConversations.optBoolean(sender)) conversations.add(sender);
                    if (validUser(recipient) && !recipient.equals(username) && !hiddenConversations.optBoolean(recipient)) conversations.add(recipient);
                }
            }
            JSONArray contactList = new JSONArray();
            for (String contact : contacts) contactList.put(contact);
            JSONArray conversationList = new JSONArray();
            for (String peer : conversations) conversationList.put(peer);
            snapshot.put("contacts", contactList).put("conversations", conversationList)
                    .put("relationships", new JSONObject(relationships.toString()))
                    .put("messages", messages);
        } catch (Exception internal) {
            error = "本地会话读取失败：" + readable(internal);
        }
        final String notice = error;
        main.post(() -> listener.onState(snapshot, notice));
    }

    private static boolean validUser(String value) {
        return Usernames.valid(value);
    }

    private static String validateServer(String address, boolean allowLanTest) throws Exception {
        URI uri;
        try { uri = new URI(address == null ? "" : address.trim()); }
        catch (Exception error) { throw new Exception("请输入服务器根地址，例如 https://chat.example.com"); }
        String scheme = uri.getScheme();
        String host = uri.getHost();
        if (scheme == null || host == null || uri.getRawUserInfo() != null
                || uri.getRawQuery() != null || uri.getRawFragment() != null
                || !(uri.getRawPath() == null || uri.getRawPath().isEmpty() || uri.getRawPath().equals("/"))
                || uri.getPort() > 65535 || uri.getPort() == 0)
            throw new Exception("请输入服务器根地址，例如 https://chat.example.com");
        scheme = scheme.toLowerCase(java.util.Locale.ROOT);
        if (!scheme.equals("http") && !scheme.equals("https"))
            throw new Exception("服务器地址只支持 HTTP 或 HTTPS");
        boolean loopback = host.equalsIgnoreCase("localhost") || host.equals("127.0.0.1")
                || host.equals("::1") || host.equals("[::1]");
        if (scheme.equals("http") && !loopback) {
            if (!BuildConfig.DEBUG || !allowLanTest || !privateNumericAddress(host))
                throw new Exception("远程服务器必须使用 HTTPS；HTTP 仅允许本机或调试版显式局域网测试");
        }
        String authority = host.contains(":") && !host.startsWith("[") ? "[" + host + "]" : host;
        return scheme + "://" + authority + (uri.getPort() < 0 ? "" : ":" + uri.getPort());
    }

    private static boolean privateNumericAddress(String host) {
        try {
            if (host.matches("(?:[0-9]{1,3}\\.){3}[0-9]{1,3}")) {
                byte[] bytes = InetAddress.getByName(host).getAddress();
                int a = bytes[0] & 255, b = bytes[1] & 255;
                return a == 10 || a == 172 && b >= 16 && b <= 31
                        || a == 192 && b == 168 || a == 169 && b == 254;
            }
            if (host.contains(":")) {
                byte[] bytes = InetAddress.getByName(host).getAddress();
                int first = bytes[0] & 255;
                return (first & 254) == 252 || first == 254 && ((bytes[1] & 192) == 128);
            }
        } catch (Exception ignored) { }
        return false;
    }

    private static String readable(Throwable error) {
        return error.getMessage() == null || error.getMessage().isEmpty()
                ? "操作失败，请重试" : error.getMessage();
    }

    private interface CheckedAction { void run() throws Exception; }
}
