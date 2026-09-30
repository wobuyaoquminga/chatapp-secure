package com.example.chatandroid;
import static org.junit.Assert.*;
import java.util.UUID;
import org.json.JSONObject;
import org.junit.Test;
public class LocationPayloadTest {
    private final long now = 1790500000000L;
    private final String session = UUID.randomUUID().toString();
    private String body(String kind,int seq,long expires) throws Exception { return LocationPayload.encode(kind,session,seq,31.2,121.5,40,now,expires); }
    @Test public void roundTripCoordinatesAndUtc() throws Exception {
        LocationPayload item = LocationPayload.parse(body("pin",0,now+3600000),now);
        assertNotNull(item); assertEquals(31.2,item.latitude,0.001); assertEquals(121.5,item.longitude,0.001);
        assertTrue(item.recordedAt.endsWith("Z")); assertTrue(item.mapsUrl().startsWith("https://www.openstreetmap.org/"));
        assertEquals("geo:31.2,121.5?q=31.2,121.5", item.geoUri());
        assertEquals("androidamap://viewMap?sourceApplication=Chat&poiname=Chat&lat=31.2&lon=121.5&dev=1", item.amapUri());
    }
    @Test public void expiredIsHistoricalButNeverActive() throws Exception {
        String text = body("live",0,now+1000);
        LocationPayload item = LocationPayload.parse(text,now+1000); assertTrue(item.expired);
        LocationPayload.Tracker tracker = new LocationPayload.Tracker(); assertTrue(tracker.accept("alice",item));
        assertFalse(tracker.active("alice",session,now+1000));
    }
    @Test public void stopIsTerminalIncludingOutOfOrderStop() throws Exception {
        LocationPayload.Tracker tracker = new LocationPayload.Tracker();
        assertTrue(tracker.accept("alice",LocationPayload.parse(body("live",3,now+1000),now)));
        assertFalse(tracker.accept("alice",LocationPayload.parse(body("live",2,now+1000),now)));
        assertTrue(tracker.accept("alice",LocationPayload.parse(body("stop",1,now),now)));
        assertFalse(tracker.accept("alice",LocationPayload.parse(body("live",5,now+1000),now)));
        assertFalse(tracker.active("alice",session,now));
    }
    @Test public void strictMalformedAndBounds() throws Exception {
        String valid = body("pin",0,now+3600000);
        for (String field : new String[]{"latitude","longitude","accuracy","seq","v"}) {
            JSONObject j = new JSONObject(valid.substring(LocationPayload.PREFIX.length())); j.put(field,"1");
            assertNull(LocationPayload.parse(LocationPayload.PREFIX+j,now));
        }
        assertNull(LocationPayload.parse(valid.replace(session,"not-uuid"),now));
        JSONObject bad = new JSONObject(valid.substring(LocationPayload.PREFIX.length())); bad.put("latitude",91);
        assertNull(LocationPayload.parse(LocationPayload.PREFIX+bad,now)); bad.put("latitude",31).put("accuracy",100001);
        assertNull(LocationPayload.parse(LocationPayload.PREFIX+bad,now));
        assertNull(LocationPayload.parse(LocationPayload.PREFIX+new JSONObject(valid.substring(LocationPayload.PREFIX.length())).put("extra",true),now));
        assertNull(LocationPayload.parse(valid,now-300001));
    }
    @Test public void sequenceCannotExtendSessionExpiry() throws Exception {
        LocationPayload.Tracker tracker = new LocationPayload.Tracker();
        assertTrue(tracker.accept("alice",LocationPayload.parse(body("live",0,now+1000),now)));
        assertFalse(tracker.accept("alice",LocationPayload.parse(body("live",1,now+2000),now)));
    }
    @Test public void javaNodeProtocolVectorsAgree() throws Exception {
        org.json.JSONArray vectors = new org.json.JSONArray();
        String valid = body("pin",0,now+3600000);
        vectors.put(valid);
        vectors.put(valid.replace(session,"00000000-0000-0000-0000-000000000000"));
        vectors.put(valid.replace(session,session.substring(0,14)+"9"+session.substring(15)));
        JSONObject source = new JSONObject(valid.substring(LocationPayload.PREFIX.length()));
        vectors.put(LocationPayload.PREFIX+new JSONObject(source.toString()).put("recordedAt","2026-02-30T00:00:00Z"));
        vectors.put(LocationPayload.PREFIX+new JSONObject(source.toString()).put("recordedAt","2026-09-27T00:00:00+00:00"));
        vectors.put(LocationPayload.PREFIX+new JSONObject(source.toString()).put("recordedAt","2026-09-27T00:00:00.1Z"));
        vectors.put(LocationPayload.PREFIX+new JSONObject(source.toString()).put("unknown",true));
        vectors.put(body("stop",1,now));
        java.io.File root = new java.io.File(System.getProperty("chat.root",System.getProperty("user.dir")));
        Process process = new ProcessBuilder("node",new java.io.File(root,"android/test/node-location-interop.cjs").getAbsolutePath()).directory(root).start();
        try(java.io.OutputStream output = process.getOutputStream()) {
            output.write(new JSONObject().put("now",now).put("bodies",vectors).toString().getBytes(java.nio.charset.StandardCharsets.UTF_8));
        }
        assertTrue(process.waitFor(30,java.util.concurrent.TimeUnit.SECONDS));
        String result = new String(process.getInputStream().readAllBytes(),java.nio.charset.StandardCharsets.UTF_8);
        assertEquals(new String(process.getErrorStream().readAllBytes(),java.nio.charset.StandardCharsets.UTF_8),0,process.exitValue());
        org.json.JSONArray decisions = new org.json.JSONArray(result);
        for(int i=0;i<vectors.length();i++) assertEquals("vector "+i,LocationPayload.parse(vectors.getString(i),now)!=null,decisions.getBoolean(i));
    }

}
