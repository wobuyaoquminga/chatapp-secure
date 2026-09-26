package com.example.chatandroid;

import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.time.Instant;
import java.util.Base64;
import java.util.UUID;
import org.json.JSONArray;
import org.json.JSONObject;
import org.signal.libsignal.protocol.IdentityKey;
import org.signal.libsignal.protocol.IdentityKeyPair;
import org.signal.libsignal.protocol.SessionBuilder;
import org.signal.libsignal.protocol.SessionCipher;
import org.signal.libsignal.protocol.SignalProtocolAddress;
import org.signal.libsignal.protocol.ecc.ECKeyPair;
import org.signal.libsignal.protocol.ecc.ECPrivateKey;
import org.signal.libsignal.protocol.ecc.ECPublicKey;
import org.signal.libsignal.protocol.fingerprint.NumericFingerprintGenerator;
import org.signal.libsignal.protocol.kem.KEMKeyPair;
import org.signal.libsignal.protocol.kem.KEMKeyType;
import org.signal.libsignal.protocol.kem.KEMPublicKey;
import org.signal.libsignal.protocol.message.CiphertextMessage;
import org.signal.libsignal.protocol.message.PreKeySignalMessage;
import org.signal.libsignal.protocol.message.SignalMessage;
import org.signal.libsignal.protocol.state.KyberPreKeyRecord;
import org.signal.libsignal.protocol.state.PreKeyBundle;
import org.signal.libsignal.protocol.state.PreKeyRecord;
import org.signal.libsignal.protocol.state.SessionRecord;
import org.signal.libsignal.protocol.state.SignedPreKeyRecord;

/** Signal Protocol operations and wire format shared with client/signal.cjs. */
public final class SignalEngine {
    private final JSONObject data;
    private final SignalStore store;

    public SignalEngine(JSONObject state) {
        if (state == null || state.optInt("version", -1) != 1 ||
                state.optString("username", "").isEmpty())
            throw new IllegalArgumentException("不支持的本地加密状态");
        data = state;
        for (String field : new String[] {"signed", "pre", "kyber", "usedKyber", "sessions",
                "trusted", "verified", "messages", "outbox", "hiddenContacts", "hiddenConversations", "peerAccountIds", "deletedPeers", "appliedAccountEvents"}) {
            if (data.optJSONObject(field) == null) put(data, field, new JSONObject());
        }
        if (data.optJSONArray("pendingUpload") == null)
            put(data, "pendingUpload", new JSONArray());
        store = new SignalStore(data);
    }

    public static SignalEngine create(String username) {
        if (username == null || username.isEmpty()) throw new IllegalArgumentException("账号不能为空");
        ECPrivateKey privateKey = ECPrivateKey.generate();
        ECKeyPair signedPair = ECKeyPair.generate();
        SignedPreKeyRecord signed = new SignedPreKeyRecord(1, System.currentTimeMillis(),
                signedPair, privateKey.calculateSignature(signedPair.getPublicKey().serialize()));
        JSONObject state = new JSONObject();
        put(state, "version", 1);
        put(state, "username", username);
        put(state, "registrationId", new SecureRandom().nextInt(16383) + 1);
        put(state, "identity", encode(privateKey.serialize()));
        JSONObject signedMap = new JSONObject();
        put(signedMap, "1", encode(signed.serialize()));
        put(state, "signed", signedMap);
        for (String field : new String[] {"pre", "kyber", "usedKyber", "sessions", "trusted",
                "verified", "messages", "outbox", "hiddenContacts", "hiddenConversations", "peerAccountIds", "deletedPeers", "appliedAccountEvents"}) put(state, field, new JSONObject());
        put(state, "nextKey", 1);
        put(state, "pendingUpload", new JSONArray());
        return new SignalEngine(state);
    }

    /** Caller must persist this complete state atomically before network I/O. */
    public synchronized JSONObject state() { return data; }

    public synchronized JSONObject publicBundle(int count) {
        if (count < 0 || count > 50) throw new IllegalArgumentException("每批最多 50 组预密钥");
        ECPrivateKey identity = localPrivate();
        SignedPreKeyRecord signed = loadSigned();
        JSONArray pending = data.optJSONArray("pendingUpload");
        for (int i = 0; i < count; i++) {
            int id = integer(data, "nextKey");
            if (id < 1 || id == Integer.MAX_VALUE) throw new IllegalStateException("预密钥编号已耗尽");
            put(data, "nextKey", id + 1);
            ECKeyPair ec = ECKeyPair.generate();
            KEMKeyPair kem = KEMKeyPair.generate(KEMKeyType.KYBER_1024);
            KyberPreKeyRecord kyber = new KyberPreKeyRecord(id, System.currentTimeMillis(),
                    kem, identity.calculateSignature(kem.getPublicKey().serialize()));
            store.storePreKey(id, new PreKeyRecord(id, ec));
            store.storeKyberPreKey(id, kyber);
            JSONObject item = new JSONObject();
            put(item, "id", id);
            put(item, "publicKey", encode(ec.getPublicKey().serialize()));
            put(item, "kyberPublicKey", encode(kem.getPublicKey().serialize()));
            put(item, "kyberSignature", encode(kyber.getSignature()));
            pending.put(item);
        }
        JSONObject signedPublic = new JSONObject();
        put(signedPublic, "id", 1);
        try { put(signedPublic, "publicKey", encode(signed.getKeyPair().getPublicKey().serialize())); }
        catch (Exception e) { throw new IllegalStateException("本地签名预密钥损坏", e); }
        put(signedPublic, "signature", encode(signed.getSignature()));
        JSONObject bundle = new JSONObject();
        put(bundle, "identityKey", encode(identity.publicKey().serialize()));
        put(bundle, "registrationId", integer(data, "registrationId"));
        put(bundle, "signedPreKey", signedPublic);
        put(bundle, "preKeys", pending);
        return bundle;
    }

    public synchronized boolean hasSession(String peer) {
        SessionRecord record = store.loadSession(address(peer));
        return record != null && record.hasSenderChain();
    }

    public synchronized void establish(String peer, JSONObject bundle) {
        if (bundle == null || !peer.equals(string(bundle, "username")))
            throw new IllegalArgumentException("预密钥账号不匹配");
        JSONObject pre = object(bundle, "preKey");
        JSONObject signed = object(bundle, "signedPreKey");
        try {
            int id = integer(pre, "id");
            PreKeyBundle remote = new PreKeyBundle(integer(bundle, "registrationId"), 1,
                    id, new ECPublicKey(decode(string(pre, "publicKey"))),
                    integer(signed, "id"), new ECPublicKey(decode(string(signed, "publicKey"))),
                    decode(string(signed, "signature")),
                    new IdentityKey(decode(string(bundle, "identityKey"))), id,
                    new KEMPublicKey(decode(string(pre, "kyberPublicKey"))),
                    decode(string(pre, "kyberSignature")));
            new SessionBuilder(store, store, store, store, address(peer), localAddress()).process(remote);
        } catch (Exception e) { throw failure("建立加密会话失败", e); }
    }

    public synchronized JSONObject encrypt(String to, String body) {
        if (isDeleted(to)) throw new SecurityException("该用户已销户，不能发送消息");
        if (body == null) throw new IllegalArgumentException("消息不能为空");
        String clientId = UUID.randomUUID().toString();
        JSONObject content = new JSONObject();
        put(content, "v", 1);
        put(content, "clientId", clientId);
        put(content, "sender", string(data, "username"));
        put(content, "recipient", to);
        put(content, "body", body);
        put(content, "createdAt", Instant.now().toString());
        try {
            SessionCipher cipher = new SessionCipher(store, store, store, store, store,
                    localAddress(), address(to));
            CiphertextMessage encrypted = cipher.encrypt(content.toString().getBytes(StandardCharsets.UTF_8));
            JSONObject envelope = new JSONObject();
            put(envelope, "v", 1);
            put(envelope, "type", encrypted.getType());
            put(envelope, "data", encode(encrypted.serialize()));
            String ciphertext = envelope.toString();
            JSONObject request = new JSONObject();
            put(request, "type", "send");
            put(request, "clientId", clientId);
            put(request, "to", to);
            put(request, "ciphertext", ciphertext);
            put(object(data, "outbox"), clientId, request);
            put(content, "status", "待发送");
            put(content, "ciphertext", ciphertext);
            put(object(data, "messages"), string(data, "username") + ":" + clientId, content);
            return request;
        } catch (Exception e) { throw failure("消息加密失败", e); }
    }

    public synchronized JSONObject decrypt(JSONObject message) {
        if (message == null) throw new IllegalArgumentException("消息不能为空");
        String sender = string(message, "sender");
        String clientId = string(message, "clientId");
        String ciphertext = string(message, "ciphertext");
        String recipient = string(message, "recipient");
        String cacheKey = sender + ":" + clientId;
        JSONObject existing = object(data, "messages").optJSONObject(cacheKey);
        if (existing != null) {
            if (!ciphertext.equals(string(existing, "ciphertext")) ||
                    !recipient.equals(string(existing, "recipient")))
                throw new SecurityException("重复消息内容不一致");
            return existing;
        }
        if (isDeleted(sender)) throw new SecurityException("该用户已销户，旧身份消息已阻止");
        JSONObject envelope = parse(ciphertext);
        int type = integer(envelope, "type");
        if (integer(envelope, "v") != 1 ||
                (type != CiphertextMessage.WHISPER_TYPE && type != CiphertextMessage.PREKEY_TYPE))
            throw new IllegalArgumentException("不支持的加密消息格式");
        try {
            SessionCipher cipher = new SessionCipher(store, store, store, store, store,
                    localAddress(), address(sender));
            byte[] encrypted = decode(string(envelope, "data"));
            byte[] plain = type == CiphertextMessage.PREKEY_TYPE
                    ? cipher.decrypt(new PreKeySignalMessage(encrypted))
                    : cipher.decrypt(new SignalMessage(encrypted));
            JSONObject content = parse(new String(plain, StandardCharsets.UTF_8));
            if (integer(content, "v") != 1 || !sender.equals(string(content, "sender")) ||
                    !string(data, "username").equals(string(content, "recipient")) ||
                    !clientId.equals(string(content, "clientId")) ||
                    !string(data, "username").equals(recipient) ||
                    !(content.opt("body") instanceof String))
                throw new SecurityException("加密正文与路由信息不匹配");
            put(content, "id", message.opt("id"));
            put(content, "status", "已接收并安全保存");
            put(content, "ciphertext", ciphertext);
            put(object(data, "messages"), cacheKey, content);
            return content;
        } catch (Exception e) { throw failure("消息解密失败", e); }
    }

    public synchronized void accepted(JSONObject message) {
        String clientId = string(message, "clientId");
        JSONObject local = object(data, "messages").optJSONObject(string(data, "username") + ":" + clientId);
        if (local == null || !string(local, "ciphertext").equals(string(message, "ciphertext")) ||
                !string(local, "recipient").equals(string(message, "recipient")))
            throw new SecurityException("服务器确认与本地消息不一致");
        put(local, "id", message.opt("id"));
        put(local, "status", message.optBoolean("acknowledged", false)
                ? "对方客户端已接收" : "服务器已保存密文");
        object(data, "outbox").remove(clientId);
    }

    /** Only a server deletion proof permits retiring a pinned identity. */
    public synchronized boolean applyAccountDeletion(JSONObject event) {
        String id = string(event, "id");
        String peer = string(event, "username");
        String accountId = string(event, "accountId");
        if (id.isEmpty() || accountId.isEmpty() || !Usernames.valid(peer)
                || peer.equals(string(data, "username"))) throw new IllegalArgumentException("无效账号删除事件");
        JSONObject applied = object(data, "appliedAccountEvents");
        if (applied.has(id)) return false;
        String bound = object(data, "peerAccountIds").optString(peer, "");
        String trusted = object(data, "trusted").optString(address(peer).toString(), "");
        String oldPublic = event.optString("identityKey", "");
        boolean stale = (!bound.isEmpty() && !bound.equals(accountId))
                || (!trusted.isEmpty() && !oldPublic.isEmpty() && !trusted.equals(oldPublic));
        put(applied, id, true);
        if (stale) return false;
        store.deleteAllSessions(peer);
        object(data, "trusted").remove(address(peer).toString());
        object(data, "verified").remove(peer);
        object(data, "peerAccountIds").remove(peer);
        object(data, "hiddenContacts").remove(peer);
        // Keep the conversation and plaintext history, but never replay old ciphertext.
        object(data, "hiddenConversations").remove(peer);
        JSONObject outbox = object(data, "outbox");
        java.util.List<String> remove = new java.util.ArrayList<>();
        java.util.Iterator<String> ids = outbox.keys();
        while (ids.hasNext()) {
            String clientId = ids.next();
            if (!peer.equals(outbox.optJSONObject(clientId).optString("to"))) continue;
            remove.add(clientId);
            JSONObject message = object(data, "messages").optJSONObject(string(data, "username") + ":" + clientId);
            if (message != null) put(message, "status", "身份已失效 · 未发送");
        }
        for (String clientId : remove) outbox.remove(clientId);
        JSONObject history = object(data, "messages");
        java.util.Iterator<String> records = history.keys();
        while (records.hasNext()) {
            JSONObject message = history.optJSONObject(records.next());
            if (message == null || !peer.equals(message.optString("sender"))
                    && !peer.equals(message.optString("recipient"))) continue;
            if (message.has("id")) {
                put(message, "archivedServerId", message.opt("id"));
                message.remove("id");
            }
        }
        put(object(data, "deletedPeers"), peer, event);
        return true;
    }

    public synchronized boolean isDeleted(String peer) { return object(data, "deletedPeers").has(peer); }

    public synchronized boolean needsDeletedPeerRefresh(String peer, String relation) {
        return isDeleted(peer) && ("accepted".equals(relation)
                || "pending_incoming".equals(relation) || "pending_outgoing".equals(relation));
    }

    /** Reopening a deleted name requires proof that it now denotes a new account. */
    public synchronized JSONObject bindPeer(String peer, JSONObject identity, boolean reopen) {
        String accountId = identity.optString("accountId", "");
        JSONObject tombstone = object(data, "deletedPeers").optJSONObject(peer);
        if (tombstone != null) {
            if (!reopen || accountId.isEmpty() || accountId.equals(tombstone.optString("accountId")))
                throw new SecurityException("该用户已销户，无法向不存在的账号发送消息");
        }
        JSONObject result = safety(peer, string(identity, "identityKey"));
        if (tombstone != null) object(data, "deletedPeers").remove(peer);
        if (!accountId.isEmpty()) put(object(data, "peerAccountIds"), peer, accountId);
        return result;
    }

    public synchronized JSONObject safety(String peer, String remotePublic) {
        SignalProtocolAddress address = address(peer);
        JSONObject trusted = object(data, "trusted");
        String old = trusted.optString(address.toString(), "");
        if (!old.isEmpty() && !old.equals(remotePublic))
            throw new SecurityException("对方身份密钥已改变，已阻止通信");
        try {
            IdentityKey local = new IdentityKey(localPrivate().publicKey());
            IdentityKey remote = new IdentityKey(decode(remotePublic));
            String code = new NumericFingerprintGenerator(5200).createFor(2,
                    string(data, "username").getBytes(StandardCharsets.UTF_8), local,
                    peer.getBytes(StandardCharsets.UTF_8), remote)
                    .getDisplayableFingerprint().getDisplayText();
            put(trusted, address.toString(), remotePublic);
            JSONObject result = new JSONObject();
            put(result, "code", code);
            put(result, "verified", remotePublic.equals(object(data, "verified").optString(peer, "")));
            put(result, "publicKey", remotePublic);
            return result;
        } catch (Exception e) { throw failure("安全码生成失败", e); }
    }

    private ECPrivateKey localPrivate() {
        try { return new ECPrivateKey(decode(string(data, "identity"))); }
        catch (Exception e) { throw new IllegalStateException("本地身份密钥损坏", e); }
    }

    private SignedPreKeyRecord loadSigned() {
        try { return store.loadSignedPreKey(1); }
        catch (Exception e) { throw new IllegalStateException("本地签名预密钥缺失", e); }
    }

    private SignalProtocolAddress localAddress() { return address(string(data, "username")); }

    private static SignalProtocolAddress address(String name) {
        if (name == null || name.isEmpty()) throw new IllegalArgumentException("账号不能为空");
        return new SignalProtocolAddress(name, 1);
    }

    static String encode(byte[] value) { return Base64.getEncoder().encodeToString(value); }

    static byte[] decode(String value) { return Base64.getDecoder().decode(value); }

    static JSONObject object(JSONObject value, String key) {
        JSONObject result = value.optJSONObject(key);
        if (result == null) throw new IllegalArgumentException("缺少对象字段: " + key);
        return result;
    }

    static String string(JSONObject value, String key) {
        Object result = value.opt(key);
        if (!(result instanceof String)) throw new IllegalArgumentException("缺少文本字段: " + key);
        return (String) result;
    }

    static int integer(JSONObject value, String key) {
        Object result = value.opt(key);
        if (!(result instanceof Number)) throw new IllegalArgumentException("缺少数字字段: " + key);
        return ((Number) result).intValue();
    }

    static void put(JSONObject value, String key, Object item) {
        try { value.put(key, item); }
        catch (Exception e) { throw new IllegalStateException("本地状态无法更新: " + key, e); }
    }

    private static JSONObject parse(String value) {
        try { return new JSONObject(value); }
        catch (Exception e) { throw new IllegalArgumentException("无效 JSON 数据", e); }
    }

    private static RuntimeException failure(String message, Exception cause) {
        return cause instanceof RuntimeException ? (RuntimeException) cause
                : new IllegalStateException(message, cause);
    }
}
