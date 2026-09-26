package com.example.chatandroid;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import android.content.Context;
import android.content.ContextWrapper;

import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;

import org.json.JSONObject;
import org.junit.Test;
import org.junit.runner.RunWith;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.Arrays;
import java.util.Collections;

@RunWith(AndroidJUnit4.class)
public class AccountRegistryTest {
    @Test public void serverScopedHintsAndVaultRetention() throws Exception {
        File directory = Files.createTempDirectory(
                InstrumentationRegistry.getInstrumentation().getTargetContext().getCacheDir().toPath(),
                "registry-test-").toFile();
        Context context = isolatedContext(directory);
        AccountRegistry registry = new AccountRegistry(context);
        String first = "https://one.example";
        String second = "https://two.example";

        assertEquals(Collections.emptyList(), registry.listServers());
        assertEquals("", registry.lastServer());
        registry.addServer(first);
        registry.addServer(second);
        registry.remember(first, "alice");
        registry.remember(first, "bob");
        registry.remember(second, "alice");
        assertEquals(Arrays.asList(second, first), registry.listServers());
        assertEquals(Arrays.asList("bob", "alice"), registry.listAccounts(first));
        assertEquals(Collections.singletonList("alice"), registry.listAccounts(second));
        assertEquals(second, registry.lastServer());

        SecureVault vault = new SecureVault(context, first, "alice");
        vault.write(new JSONObject().put("secret", "private key remains"));
        registry.forgetAccount(first, "alice");
        assertEquals(Collections.singletonList("bob"), registry.listAccounts(first));
        assertEquals("private key remains", vault.read().getString("secret"));
        registry.selectServer(first);
        assertEquals(first, registry.lastServer());
        registry.forgetServer(first);
        assertEquals(Collections.singletonList(second), registry.listServers());
        assertEquals(Collections.emptyList(), registry.listAccounts(first));
        assertEquals(second, registry.lastServer());
        assertEquals("private key remains", vault.read().getString("secret"));

        byte[] encrypted = Files.readAllBytes(new File(directory, "accounts.registry").toPath());
        String diskText = new String(encrypted, StandardCharsets.UTF_8);
        assertFalse(diskText.contains(second));
        assertFalse(diskText.contains("alice"));
        assertFalse(diskText.contains("private key remains"));

        byte[] tampered = encrypted.clone();
        tampered[tampered.length - 1] ^= 1;
        Files.write(new File(directory, "accounts.registry").toPath(), tampered);
        try { registry.listServers(); fail("tampered index accepted"); }
        catch (Exception expected) { assertTrue(expected.getMessage().contains("不会覆盖")); }
        try { registry.remember(second, "charlie"); fail("tampered index overwritten"); }
        catch (Exception expected) { assertTrue(expected.getMessage().contains("不会覆盖")); }
        assertTrue(Arrays.equals(tampered,
                Files.readAllBytes(new File(directory, "accounts.registry").toPath())));
        Files.write(new File(directory, "accounts.registry").toPath(), encrypted);
        assertEquals(Collections.singletonList("alice"), new AccountRegistry(context).listAccounts(second));
    }

    private static Context isolatedContext(File directory) {
        Context target = InstrumentationRegistry.getInstrumentation().getTargetContext();
        return new ContextWrapper(target) {
            @Override public Context getApplicationContext() { return this; }
            @Override public File getNoBackupFilesDir() { return directory; }
        };
    }
}
