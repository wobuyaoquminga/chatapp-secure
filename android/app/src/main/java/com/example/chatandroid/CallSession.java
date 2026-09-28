package com.example.chatandroid;

import org.json.JSONObject;
import java.util.UUID;

/** Ephemeral call identity and transition checks. Never placed in the Signal vault. */
final class CallSession {
    enum State { RINGING, CONNECTING, ACTIVE, INTERRUPTED, ENDED }
    final String id, peer, accountId, mode;
    final boolean outgoing;
    State state = State.RINGING;

    CallSession(String id, String peer, String accountId, String mode, boolean outgoing) {
        UUID.fromString(id);
        if (!Usernames.valid(peer) || accountId == null || accountId.isEmpty()
                || !(mode.equals("audio") || mode.equals("video")))
            throw new IllegalArgumentException("无效通话身份");
        this.id = id; this.peer = peer; this.accountId = accountId;
        this.mode = mode; this.outgoing = outgoing;
    }

    boolean matches(JSONObject frame) {
        return id.equals(frame.optString("callId")) && peer.equals(frame.optString("from"))
                && accountId.equals(frame.optString("fromAccountId"))
                && mode.equals(frame.optString("mode"));
    }

    boolean accept(String action) {
        if (state == State.ENDED) return false;
        if (action.equals("hangup") || action.equals("reject") || action.equals("busy")) {
            state = State.ENDED; return true;
        }
        if (action.equals("answer") && outgoing && state == State.RINGING) {
            state = State.CONNECTING; return true;
        }
        if (action.equals("ice") && state != State.ENDED) return true;
        return false;
    }

    boolean connected() {
        if (state == State.ENDED) return false;
        state = State.ACTIVE;
        return true;
    }

    boolean disconnected() {
        if (state == State.ENDED) return false;
        state = State.INTERRUPTED;
        return true;
    }

    JSONObject frame(String action, String payload) {
        if (!(action.equals("offer") || action.equals("answer") || action.equals("ice")
                || action.equals("reject") || action.equals("busy") || action.equals("hangup")))
            throw new IllegalArgumentException("无效通话操作");
        if (action.equals("offer") || action.equals("answer")) parseSdpPayload(action, payload);
        JSONObject frame = new JSONObject();
        try {
            frame.put("type", "call").put("to", peer).put("toAccountId", accountId)
                    .put("callId", id).put("action", action).put("mode", mode);
            if (payload != null) frame.put("payload", payload);
        } catch (Exception error) { throw new IllegalStateException(error); }
        return frame;
    }

    /** Desktop RTCPeerConnection sends JSON.stringify(RTCSessionDescription). */
    static String sdpPayload(String type, String sdp) {
        validateSdp(type, sdp);
        try {
            String result = new JSONObject().put("type", type).put("sdp", sdp).toString();
            if (result.length() > 65536) throw new IllegalArgumentException("SDP 信令过长");
            return result;
        } catch (org.json.JSONException error) { throw new IllegalArgumentException("SDP 信令编码失败", error); }
    }

    static String parseSdpPayload(String action, String payload) {
        if (payload == null || payload.length() > 65536) throw new IllegalArgumentException("SDP 信令无效");
        try {
            JSONObject value = new JSONObject(payload);
            if (value.length() != 2 || !action.equals(value.optString("type"))
                    || !(value.opt("sdp") instanceof String)) throw new IllegalArgumentException("SDP 类型不匹配");
            String sdp = value.getString("sdp");
            validateSdp(action, sdp);
            return sdp;
        } catch (org.json.JSONException error) { throw new IllegalArgumentException("SDP 信令无效", error); }
    }

    private static void validateSdp(String type, String sdp) {
        if (!("offer".equals(type) || "answer".equals(type)) || sdp == null
                || sdp.isBlank() || sdp.length() > 60000 || !sdp.startsWith("v=0"))
            throw new IllegalArgumentException("SDP 信令无效");
    }
}
