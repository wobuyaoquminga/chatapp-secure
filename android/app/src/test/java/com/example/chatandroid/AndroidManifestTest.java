package com.example.chatandroid;

import java.nio.file.Path;
import java.util.HashSet;
import java.util.Set;
import javax.xml.parsers.DocumentBuilderFactory;
import org.junit.Test;
import org.w3c.dom.Element;
import org.w3c.dom.NodeList;

import static org.junit.Assert.assertTrue;

public final class AndroidManifestTest {
    @Test public void webRtcRuntimePermissionsAreDeclared() throws Exception {
        Path root = Path.of(System.getProperty("chat.root"));
        NodeList declarations = DocumentBuilderFactory.newInstance().newDocumentBuilder()
                .parse(root.resolve("android/app/src/main/AndroidManifest.xml").toFile())
                .getElementsByTagName("uses-permission");
        Set<String> permissions = new HashSet<>();
        for (int i = 0; i < declarations.getLength(); i++) {
            Element declaration = (Element) declarations.item(i);
            permissions.add(declaration.getAttribute("android:name"));
        }
        for (String permission : new String[] {
                "android.permission.INTERNET",
                "android.permission.ACCESS_NETWORK_STATE",
                "android.permission.RECORD_AUDIO",
                "android.permission.MODIFY_AUDIO_SETTINGS",
                "android.permission.CAMERA"
        }) assertTrue("missing " + permission, permissions.contains(permission));
    }
    @Test public void runtimeConfigurationChangesPreserveActivityAndLiveSession() throws Exception {
        Path root = Path.of(System.getProperty("chat.root"));
        Element activity = (Element) DocumentBuilderFactory.newInstance().newDocumentBuilder()
                .parse(root.resolve("android/app/src/main/AndroidManifest.xml").toFile())
                .getElementsByTagName("activity").item(0);
        Set<String> handled = new HashSet<>(java.util.Arrays.asList(activity.getAttribute("android:configChanges").split("\\|")));
        for (String change : new String[]{"fontScale", "density", "uiMode", "locale", "layoutDirection", "orientation", "screenSize"})
            assertTrue("Activity would recreate on " + change, handled.contains(change));
    }
}
