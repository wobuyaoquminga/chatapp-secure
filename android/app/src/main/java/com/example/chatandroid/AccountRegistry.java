package com.example.chatandroid;

import android.content.Context;
import android.security.keystore.KeyGenParameterSpec;
import android.security.keystore.KeyProperties;
import android.util.AtomicFile;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.KeyStore;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import javax.crypto.Cipher;
import javax.crypto.KeyGenerator;
import javax.crypto.SecretKey;
import javax.crypto.spec.GCMParameterSpec;

/** Encrypted sign-in hints. Passwords, tokens and Signal keys never enter this file. */
final class AccountRegistry {
    private static final byte[] MAGIC = {'C', 'H', 'A', 'I', 1};
    private static final String KEYSTORE = "AndroidKeyStore";
    private static final String ALIAS = "chatapp.account-registry.v1";
    private static final int MAX_FILE_BYTES = 1024 * 1024;
    private final AtomicFile file;

    AccountRegistry(Context context) {
        file = new AtomicFile(new File(context.getApplicationContext().getNoBackupFilesDir(),
                "accounts.registry"));
    }

    synchronized List<String> listServers() throws Exception {
        return new ArrayList<>(read().servers);
    }

    synchronized List<String> listAccounts(String server) throws Exception {
        requireServer(server);
        State state = read();
        for (Server entry : state.entries) {
            if (entry.address.equals(server)) return new ArrayList<>(entry.accounts);
        }
        return Collections.emptyList();
    }

    synchronized String lastServer() throws Exception {
        return read().selectedServer;
    }

    synchronized void addServer(String server) throws Exception {
        requireServer(server);
        State state = read();
        if (state.find(server) != null) return;
        state.servers.add(server);
        state.entries.add(new Server(server));
        if (state.selectedServer.isEmpty()) state.selectedServer = server;
        write(state);
    }

    synchronized void selectServer(String server) throws Exception {
        requireServer(server);
        State state = read();
        if (state.find(server) == null) throw new Exception("服务器不在已保存列表");
        if (server.equals(state.selectedServer)) return;
        state.selectedServer = server;
        state.servers.remove(server);
        state.servers.add(0, server);
        write(state);
    }

    synchronized void remember(String server, String username) throws Exception {
        requireServer(server);
        requireUsername(username);
        State state = read();
        Server entry = state.find(server);
        if (entry == null) {
            entry = new Server(server);
            state.entries.add(entry);
            state.servers.add(server);
        }
        entry.accounts.remove(username);
        entry.accounts.add(0, username);
        state.selectedServer = server;
        state.servers.remove(server);
        state.servers.add(0, server);
        write(state);
    }

    synchronized void forgetAccount(String server, String username) throws Exception {
        requireServer(server);
        requireUsername(username);
        State state = read();
        Server entry = state.find(server);
        if (entry == null || !entry.accounts.remove(username)) return;
        // Only the sign-in hint is removed. SecureVault and its Keystore key stay intact.
        write(state);
    }

    synchronized void forgetServer(String server) throws Exception {
        requireServer(server);
        State state = read();
        Server entry = state.find(server);
        if (entry == null) return;
        state.entries.remove(entry);
        state.servers.remove(server);
        if (server.equals(state.selectedServer)) {
            state.selectedServer = state.servers.isEmpty() ? "" : state.servers.get(0);
        }
        // Account hints disappear with the server entry; no Signal vault is touched.
        write(state);
    }

    private State read() throws Exception {
        if (!fileExists()) return new State();
        FileInputStream input = null;
        try {
            SecretKey key = existingKey();
            if (key == null) throw new Exception("本地账号索引密钥已丢失");
            input = file.openRead();
            ByteArrayOutputStream bytes = new ByteArrayOutputStream();
            byte[] chunk = new byte[8192];
            int count;
            while ((count = input.read(chunk)) != -1) {
                if (bytes.size() + count > MAX_FILE_BYTES) throw new Exception("本地账号索引异常大");
                bytes.write(chunk, 0, count);
            }
            byte[] data = bytes.toByteArray();
            if (data.length < MAGIC.length + 12 + 16) throw new Exception("本地账号索引不完整");
            for (int i = 0; i < MAGIC.length; i++) {
                if (data[i] != MAGIC[i]) throw new Exception("本地账号索引版本不受支持");
            }
            byte[] nonce = new byte[12];
            System.arraycopy(data, MAGIC.length, nonce, 0, nonce.length);
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.DECRYPT_MODE, key, new GCMParameterSpec(128, nonce));
            cipher.updateAAD(ALIAS.getBytes(StandardCharsets.UTF_8));
            byte[] plain = cipher.doFinal(data, MAGIC.length + nonce.length,
                    data.length - MAGIC.length - nonce.length);
            return State.parse(new JSONObject(new String(plain, StandardCharsets.UTF_8)));
        } catch (Exception error) {
            throw new Exception("本地账号索引损坏或密钥已丢失；不会覆盖旧索引", error);
        } finally {
            if (input != null) input.close();
        }
    }

    private void write(State state) throws Exception {
        byte[] plain = state.json().toString().getBytes(StandardCharsets.UTF_8);
        if (plain.length > MAX_FILE_BYTES - 64) throw new Exception("账号列表超过存储限制");
        SecretKey key = existingKey();
        if (key == null) {
            if (fileExists()) throw new Exception("本地账号索引密钥已丢失，拒绝覆盖旧索引");
            key = newKey();
        }
        Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
        cipher.init(Cipher.ENCRYPT_MODE, key);
        byte[] nonce = cipher.getIV();
        if (nonce == null || nonce.length != 12) throw new Exception("安全存储随机数生成失败");
        cipher.updateAAD(ALIAS.getBytes(StandardCharsets.UTF_8));
        byte[] encrypted = cipher.doFinal(plain);
        ByteBuffer output = ByteBuffer.allocate(MAGIC.length + nonce.length + encrypted.length);
        output.put(MAGIC).put(nonce).put(encrypted);
        FileOutputStream stream = null;
        try {
            stream = file.startWrite();
            stream.write(output.array());
            file.finishWrite(stream);
        } catch (Exception error) {
            if (stream != null) file.failWrite(stream);
            throw new Exception("本地账号索引保存失败", error);
        }
    }

    private boolean fileExists() {
        File base = file.getBaseFile();
        return base.exists() || new File(base.getPath() + ".bak").exists()
                || new File(base.getPath() + ".new").exists();
    }

    private SecretKey existingKey() throws Exception {
        KeyStore store = KeyStore.getInstance(KEYSTORE);
        store.load(null);
        KeyStore.Entry entry = store.getEntry(ALIAS, null);
        if (entry == null) return null;
        if (!(entry instanceof KeyStore.SecretKeyEntry)) throw new Exception("账号索引密钥类型错误");
        return ((KeyStore.SecretKeyEntry) entry).getSecretKey();
    }

    private SecretKey newKey() throws Exception {
        KeyGenerator generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, KEYSTORE);
        generator.init(new KeyGenParameterSpec.Builder(ALIAS,
                KeyProperties.PURPOSE_ENCRYPT | KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256)
                .setRandomizedEncryptionRequired(true)
                .build());
        return generator.generateKey();
    }

    private static void requireServer(String server) throws Exception {
        if (server == null || server.isEmpty() || server.length() > 2048
                || !(server.startsWith("https://") || server.startsWith("http://"))
                || hasControlOrSpace(server)) throw new Exception("服务器地址无效");
    }

    private static void requireUsername(String username) throws Exception {
        if (!Usernames.valid(username))
            throw new Exception("用户名无效");
    }

    private static boolean hasControlOrSpace(String value) {
        for (int i = 0; i < value.length(); i++) {
            if (Character.isWhitespace(value.charAt(i)) || Character.isISOControl(value.charAt(i)))
                return true;
        }
        return false;
    }

    private static final class Server {
        final String address;
        final List<String> accounts = new ArrayList<>();
        Server(String address) { this.address = address; }
    }

    private static final class State {
        final List<String> servers = new ArrayList<>();
        final List<Server> entries = new ArrayList<>();
        String selectedServer = "";

        Server find(String address) {
            for (Server entry : entries) if (entry.address.equals(address)) return entry;
            return null;
        }

        JSONObject json() throws Exception {
            JSONArray array = new JSONArray();
            for (String address : servers) {
                Server entry = find(address);
                JSONArray accounts = new JSONArray();
                for (String user : entry.accounts) accounts.put(user);
                array.put(new JSONObject().put("address", address).put("accounts", accounts));
            }
            return new JSONObject().put("version", 1).put("servers", array)
                    .put("selectedServer", selectedServer);
        }

        static State parse(JSONObject json) throws Exception {
            if (json.getInt("version") != 1) throw new Exception("账号索引版本不受支持");
            JSONArray array = json.getJSONArray("servers");
            String selected = json.getString("selectedServer");
            State state = new State();
            Set<String> seenServers = new HashSet<>();
            for (int i = 0; i < array.length(); i++) {
                JSONObject object = array.getJSONObject(i);
                String address = object.getString("address");
                requireServer(address);
                if (!seenServers.add(address)) throw new Exception("重复服务器入口");
                Server entry = new Server(address);
                Set<String> seenUsers = new HashSet<>();
                JSONArray users = object.getJSONArray("accounts");
                for (int j = 0; j < users.length(); j++) {
                    String user = users.getString(j);
                    requireUsername(user);
                    if (!seenUsers.add(user)) throw new Exception("重复账号入口");
                    entry.accounts.add(user);
                }
                state.servers.add(address);
                state.entries.add(entry);
            }
            if (!selected.isEmpty() && !seenServers.contains(selected))
                throw new Exception("所选服务器不在索引中");
            if (selected.isEmpty() && !state.servers.isEmpty())
                throw new Exception("所选服务器缺失");
            state.selectedServer = selected;
            return state;
        }
    }
}
