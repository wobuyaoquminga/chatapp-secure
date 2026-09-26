package com.example.chatandroid;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import org.json.JSONObject;
import org.signal.libsignal.protocol.IdentityKey;
import org.signal.libsignal.protocol.IdentityKeyPair;
import org.signal.libsignal.protocol.InvalidKeyIdException;
import org.signal.libsignal.protocol.NoSessionException;
import org.signal.libsignal.protocol.ReusedBaseKeyException;
import org.signal.libsignal.protocol.SignalProtocolAddress;
import org.signal.libsignal.protocol.ecc.ECPrivateKey;
import org.signal.libsignal.protocol.ecc.ECPublicKey;
import org.signal.libsignal.protocol.state.IdentityKeyStore;
import org.signal.libsignal.protocol.state.KyberPreKeyRecord;
import org.signal.libsignal.protocol.state.KyberPreKeyStore;
import org.signal.libsignal.protocol.state.PreKeyRecord;
import org.signal.libsignal.protocol.state.PreKeyStore;
import org.signal.libsignal.protocol.state.SessionRecord;
import org.signal.libsignal.protocol.state.SessionStore;
import org.signal.libsignal.protocol.state.SignedPreKeyRecord;
import org.signal.libsignal.protocol.state.SignedPreKeyStore;

/** The five official libsignal stores, backed by the engine's serializable JSON state. */
final class SignalStore implements SessionStore, IdentityKeyStore, PreKeyStore,
        SignedPreKeyStore, KyberPreKeyStore {
    private final JSONObject state;

    SignalStore(JSONObject state) { this.state = state; }

    private JSONObject map(String name) { return SignalEngine.object(state, name); }

    private static String address(SignalProtocolAddress a) { return a.toString(); }

    @Override public IdentityKeyPair getIdentityKeyPair() {
        try {
            ECPrivateKey privateKey = new ECPrivateKey(SignalEngine.decode(SignalEngine.string(state, "identity")));
            return new IdentityKeyPair(new IdentityKey(privateKey.publicKey()), privateKey);
        } catch (Exception e) { throw new IllegalStateException("本地身份密钥损坏", e); }
    }

    @Override public int getLocalRegistrationId() { return SignalEngine.integer(state, "registrationId"); }

    @Override public IdentityChange saveIdentity(SignalProtocolAddress address, IdentityKey key) {
        JSONObject trusted = map("trusted");
        String old = trusted.optString(address(address), "");
        String next = SignalEngine.encode(key.serialize());
        if (!old.isEmpty() && !old.equals(next))
            throw new SecurityException("对方身份密钥已改变，已阻止通信");
        SignalEngine.put(trusted, address(address), next);
        return IdentityChange.NEW_OR_UNCHANGED;
    }

    @Override public boolean isTrustedIdentity(SignalProtocolAddress address, IdentityKey key,
            Direction direction) {
        String old = map("trusted").optString(address(address), "");
        return old.isEmpty() || old.equals(SignalEngine.encode(key.serialize()));
    }

    @Override public IdentityKey getIdentity(SignalProtocolAddress address) {
        String encoded = map("trusted").optString(address(address), "");
        if (encoded.isEmpty()) return null;
        try { return new IdentityKey(SignalEngine.decode(encoded)); }
        catch (Exception e) { throw new IllegalStateException("本地身份密钥损坏", e); }
    }

    @Override public SessionRecord loadSession(SignalProtocolAddress address) {
        String encoded = map("sessions").optString(address(address), "");
        if (encoded.isEmpty()) return null;
        try { return new SessionRecord(SignalEngine.decode(encoded)); }
        catch (Exception e) { throw new IllegalStateException("本地会话损坏", e); }
    }

    @Override public List<SessionRecord> loadExistingSessions(List<SignalProtocolAddress> addresses)
            throws NoSessionException {
        List<SessionRecord> records = new ArrayList<>();
        for (SignalProtocolAddress address : addresses) {
            SessionRecord record = loadSession(address);
            if (record == null) throw new NoSessionException(address, "本地会话缺失");
            records.add(record);
        }
        return records;
    }

    @Override public List<Integer> getSubDeviceSessions(String name) {
        List<Integer> devices = new ArrayList<>();
        String prefix = name + ".";
        Iterator<String> keys = map("sessions").keys();
        while (keys.hasNext()) {
            String key = keys.next();
            if (key.startsWith(prefix)) {
                try {
                    int device = Integer.parseInt(key.substring(prefix.length()));
                    if (device != 1) devices.add(device);
                } catch (NumberFormatException ignored) { /* Ignore unrelated names. */ }
            }
        }
        return devices;
    }

    @Override public void storeSession(SignalProtocolAddress address, SessionRecord record) {
        SignalEngine.put(map("sessions"), address(address), SignalEngine.encode(record.serialize()));
    }

    @Override public boolean containsSession(SignalProtocolAddress address) {
        return map("sessions").has(address(address));
    }

    @Override public void deleteSession(SignalProtocolAddress address) {
        map("sessions").remove(address(address));
    }

    @Override public void deleteAllSessions(String name) {
        String prefix = name + ".";
        List<String> remove = new ArrayList<>();
        Iterator<String> keys = map("sessions").keys();
        while (keys.hasNext()) {
            String key = keys.next();
            if (key.startsWith(prefix)) remove.add(key);
        }
        for (String key : remove) map("sessions").remove(key);
    }

    @Override public PreKeyRecord loadPreKey(int id) throws InvalidKeyIdException {
        String encoded = map("pre").optString(Integer.toString(id), "");
        if (encoded.isEmpty()) throw new InvalidKeyIdException("本地预密钥缺失: " + id);
        try { return new PreKeyRecord(SignalEngine.decode(encoded)); }
        catch (Exception e) { throw new IllegalStateException("本地预密钥损坏", e); }
    }

    @Override public void storePreKey(int id, PreKeyRecord record) {
        SignalEngine.put(map("pre"), Integer.toString(id), SignalEngine.encode(record.serialize()));
    }

    @Override public boolean containsPreKey(int id) { return map("pre").has(Integer.toString(id)); }

    @Override public void removePreKey(int id) { map("pre").remove(Integer.toString(id)); }

    @Override public SignedPreKeyRecord loadSignedPreKey(int id) throws InvalidKeyIdException {
        String encoded = map("signed").optString(Integer.toString(id), "");
        if (encoded.isEmpty()) throw new InvalidKeyIdException("本地签名预密钥缺失: " + id);
        try { return new SignedPreKeyRecord(SignalEngine.decode(encoded)); }
        catch (Exception e) { throw new IllegalStateException("本地签名预密钥损坏", e); }
    }

    @Override public List<SignedPreKeyRecord> loadSignedPreKeys() {
        List<SignedPreKeyRecord> records = new ArrayList<>();
        Iterator<String> keys = map("signed").keys();
        while (keys.hasNext()) {
            try { records.add(loadSignedPreKey(Integer.parseInt(keys.next()))); }
            catch (InvalidKeyIdException e) { throw new IllegalStateException(e); }
        }
        return records;
    }

    @Override public void storeSignedPreKey(int id, SignedPreKeyRecord record) {
        SignalEngine.put(map("signed"), Integer.toString(id), SignalEngine.encode(record.serialize()));
    }

    @Override public boolean containsSignedPreKey(int id) {
        return map("signed").has(Integer.toString(id));
    }

    @Override public void removeSignedPreKey(int id) {
        map("signed").remove(Integer.toString(id));
    }

    @Override public KyberPreKeyRecord loadKyberPreKey(int id) throws InvalidKeyIdException {
        String encoded = map("kyber").optString(Integer.toString(id), "");
        if (encoded.isEmpty()) throw new InvalidKeyIdException("本地 Kyber 预密钥缺失: " + id);
        try { return new KyberPreKeyRecord(SignalEngine.decode(encoded)); }
        catch (Exception e) { throw new IllegalStateException("本地 Kyber 预密钥损坏", e); }
    }

    @Override public List<KyberPreKeyRecord> loadKyberPreKeys() {
        List<KyberPreKeyRecord> records = new ArrayList<>();
        Iterator<String> keys = map("kyber").keys();
        while (keys.hasNext()) {
            try { records.add(loadKyberPreKey(Integer.parseInt(keys.next()))); }
            catch (InvalidKeyIdException e) { throw new IllegalStateException(e); }
        }
        return records;
    }

    @Override public void storeKyberPreKey(int id, KyberPreKeyRecord record) {
        SignalEngine.put(map("kyber"), Integer.toString(id), SignalEngine.encode(record.serialize()));
    }

    @Override public boolean containsKyberPreKey(int id) { return map("kyber").has(Integer.toString(id)); }

    @Override public void markKyberPreKeyUsed(int id, int signedId, ECPublicKey baseKey)
            throws ReusedBaseKeyException {
        String key = Integer.toString(id);
        String use = signedId + ":" + SignalEngine.encode(baseKey.serialize());
        String previous = map("usedKyber").optString(key, "");
        if (!previous.isEmpty() && !previous.equals(use))
            throw new ReusedBaseKeyException();
        SignalEngine.put(map("usedKyber"), key, use);
    }
}
