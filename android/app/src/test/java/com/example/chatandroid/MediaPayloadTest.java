package com.example.chatandroid;

import static org.junit.Assert.*;
import java.nio.charset.StandardCharsets;
import org.junit.Test;

public class MediaPayloadTest {
    @Test public void providerNamesWithoutExtensionsKeepMediaType() {
        assertEquals("10001.jpg", MediaPayload.mediaName("10001", "image/jpeg", "image/*"));
        assertEquals("clip.mp4", MediaPayload.mediaName("clip", "video/mp4", "video/*"));
        assertEquals("PHOTO.JPEG", MediaPayload.mediaName("PHOTO.JPEG", null, "image/*"));
        try { MediaPayload.mediaName("clip.mp4", "video/mp4", "image/*"); fail(); }
        catch (IllegalArgumentException expected) { }
        try { MediaPayload.mediaName("unknown", "text/html", "video/*"); fail(); }
        catch (IllegalArgumentException expected) { }
    }
    @Test public void extensionIsOnlyAHintAndUnsafeBytesAreRejected() {
        assertEquals(MediaPayload.Kind.IMAGE, MediaPayload.kind("PHOTO.JPEG"));
        assertEquals(MediaPayload.Kind.IMAGE, MediaPayload.kind("a.webp"));
        assertEquals(MediaPayload.Kind.VIDEO, MediaPayload.kind("clip.MP4"));
        assertEquals(MediaPayload.Kind.VIDEO, MediaPayload.kind("clip.webm"));
        assertEquals(MediaPayload.Kind.FILE, MediaPayload.kind("vector.svg"));
        assertEquals(MediaPayload.Kind.FILE, MediaPayload.kind("clip.mp4.txt"));
        byte[] html = "<html><script>alert(1)</script>".getBytes(StandardCharsets.US_ASCII);
        for (String name : new String[]{"x.jpg", "x.png", "x.gif", "x.webp", "x.mp4", "x.webm"})
            assertFalse(name, MediaPayload.matches(name, html));
        assertFalse(MediaPayload.matches("x.jpg", new byte[0]));
    }

    @Test public void acceptsKnownHeadersOnlyForMatchingExtension() {
        byte[] jpeg = {(byte) 0xff, (byte) 0xd8, (byte) 0xff, 0, (byte) 0xff, (byte) 0xd9};
        assertTrue(MediaPayload.matches("a.jpeg", jpeg));
        assertFalse(MediaPayload.matches("a.png", jpeg));
        byte[] png = {(byte) 137,80,78,71,13,10,26,10,0,0,0,13,73,72,68,82,0,0,0,1,0,0,0,1};
        assertTrue(MediaPayload.matches("a.png", png));
        assertTrue(MediaPayload.matches("a.gif", "GIF89a0000".getBytes(StandardCharsets.US_ASCII)));
        assertTrue(MediaPayload.matches("a.webp", "RIFF0000WEBPVP8 ".getBytes(StandardCharsets.US_ASCII)));
        assertTrue(MediaPayload.matches("a.mp4", new byte[]{0,0,0,24,102,116,121,112,105,115,111,109,0,0,0,0}));
        assertTrue(MediaPayload.matches("a.webm", new byte[]{0x1a,0x45,(byte)0xdf,(byte)0xa3,0,0,0,0,119,101,98,109,0,0,0,0}));
    }

    @Test public void imageDimensionsStayWithinDecodeBudget() {
        assertEquals(0, MediaPayload.imageSampleSize(0, 100));
        assertEquals(0, MediaPayload.imageSampleSize(12001, 10));
        assertEquals(0, MediaPayload.imageSampleSize(10000, 10000));
        assertEquals(1, MediaPayload.imageSampleSize(2048, 2048));
        assertEquals(4, MediaPayload.imageSampleSize(6000, 2000));
    }
}
