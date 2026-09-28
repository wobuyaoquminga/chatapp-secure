package com.example.chatandroid;

import org.json.JSONObject;
import org.junit.Test;
import java.util.UUID;
import static org.junit.Assert.*;

public final class CallSessionTest {
    private final String id = UUID.randomUUID().toString();
    private final String account = UUID.randomUUID().toString();

    @Test public void wireFrameHasRecipientGenerationAndOptionalPayload() {
        CallSession session = new CallSession(id, "alice", account, "video", true);
        String desktopPayload = "{\"type\":\"offer\",\"sdp\":\"v=0\\r\\n\"}";
        JSONObject offer = session.frame("offer", desktopPayload);
        assertEquals("call", offer.optString("type"));
        assertEquals("alice", offer.optString("to"));
        assertEquals(account, offer.optString("toAccountId"));
        assertEquals(id, offer.optString("callId"));
        assertEquals("video", offer.optString("mode"));
        assertEquals(desktopPayload, offer.optString("payload"));
        assertEquals("v=0\r\n", CallSession.parseSdpPayload("offer", desktopPayload));
        assertFalse(session.frame("hangup", null).has("payload"));
    }

    @Test public void staleOrWrongGenerationCannotMatch() throws Exception {
        CallSession session = new CallSession(id, "alice", account, "audio", true);
        JSONObject incoming = new JSONObject().put("callId", id).put("from", "alice")
                .put("fromAccountId", account).put("mode", "audio");
        assertTrue(session.matches(incoming));
        incoming.put("fromAccountId", UUID.randomUUID().toString());
        assertFalse(session.matches(incoming));
        incoming.put("fromAccountId", account).put("callId", UUID.randomUUID().toString());
        assertFalse(session.matches(incoming));
    }

    @Test public void outgoingAnswerAndTerminalEventsAreOneWay() {
        CallSession session = new CallSession(id, "alice", account, "audio", true);
        assertFalse(session.accept("offer"));
        assertTrue(session.accept("ice"));
        assertTrue(session.accept("answer"));
        assertEquals(CallSession.State.CONNECTING, session.state);
        assertFalse(session.accept("answer"));
        assertTrue(session.accept("hangup"));
        assertFalse(session.accept("ice"));
    }

    @Test public void incomingCannotAcceptAnAnswer() {
        CallSession session = new CallSession(id, "alice", account, "audio", false);
        assertFalse(session.accept("answer"));
        assertTrue(session.accept("reject"));
    }

    @Test public void transientIceDisconnectionCanRecoverButEndedCallCannot() {
        CallSession session = new CallSession(id, "alice", account, "audio", true);
        assertTrue(session.connected());
        assertTrue(session.disconnected());
        assertEquals(CallSession.State.INTERRUPTED, session.state);
        assertTrue(session.connected());
        assertEquals(CallSession.State.ACTIVE, session.state);
        assertTrue(session.accept("hangup"));
        assertFalse(session.connected());
        assertFalse(session.disconnected());
    }

    @Test public void desktopSdpFormatRoundTripsAndRejectsMismatches() {
        String sdp = "v=0\r\no=- 123 2 IN IP4 127.0.0.1\r\n";
        assertEquals(sdp, CallSession.parseSdpPayload("answer", CallSession.sdpPayload("answer", sdp)));
        assertInvalid(() -> CallSession.parseSdpPayload("answer", CallSession.sdpPayload("offer", sdp)));
        assertInvalid(() -> CallSession.parseSdpPayload("offer", sdp));
        assertInvalid(() -> CallSession.parseSdpPayload("offer", "{\"type\":\"offer\",\"sdp\":\"\"}"));
        assertInvalid(() -> CallSession.parseSdpPayload("offer", "{\"type\":\"offer\",\"sdp\":\"v=0\",\"extra\":true}"));
        assertInvalid(() -> CallSession.sdpPayload("offer", "v=0".repeat(30000)));
    }

    private static void assertInvalid(Runnable action) {
        try { action.run(); fail("invalid SDP accepted"); }
        catch (IllegalArgumentException expected) { }
    }
}
