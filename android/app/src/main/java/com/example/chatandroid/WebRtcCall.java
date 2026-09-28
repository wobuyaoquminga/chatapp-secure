package com.example.chatandroid;

import android.app.Activity;
import android.app.AlertDialog;
import android.app.Dialog;
import android.content.Context;
import android.graphics.Color;
import android.media.AudioManager;
import android.os.Handler;
import android.os.Looper;
import android.view.Gravity;
import android.view.View;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;
import org.json.JSONArray;
import org.json.JSONObject;
import org.webrtc.AudioSource;
import org.webrtc.AudioTrack;
import org.webrtc.Camera2Enumerator;
import org.webrtc.CameraVideoCapturer;
import org.webrtc.EglBase;
import org.webrtc.IceCandidate;
import org.webrtc.MediaConstraints;
import org.webrtc.MediaStream;
import org.webrtc.PeerConnection;
import org.webrtc.PeerConnectionFactory;
import org.webrtc.RtpReceiver;
import org.webrtc.SdpObserver;
import org.webrtc.SessionDescription;
import org.webrtc.SurfaceTextureHelper;
import org.webrtc.SurfaceViewRenderer;
import org.webrtc.VideoSource;
import org.webrtc.VideoTrack;
import java.util.ArrayList;
import java.util.List;

/** Owns one foreground WebRTC call and releases all media resources on termination. */
final class WebRtcCall {
    private final Activity activity;
    private final ChatController controller;
    private final Handler main = new Handler(Looper.getMainLooper());
    private CallSession session;
    private long context;
    private String remoteOffer;
    private Dialog dialog;
    private AlertDialog ringDialog;
    private TextView status;
    private SurfaceViewRenderer localView, remoteView;
    private EglBase egl;
    private PeerConnectionFactory factory;
    private PeerConnection peer;
    private AudioSource audioSource;
    private AudioTrack audioTrack;
    private VideoSource videoSource;
    private VideoTrack videoTrack;
    private CameraVideoCapturer camera;
    private SurfaceTextureHelper textureHelper;
    private final List<IceCandidate> earlyIce = new ArrayList<>();
    private final List<String> pendingLocalIce = new ArrayList<>();
    private boolean remoteDescriptionSet, descriptionSent, muted, cameraEnabled = true;
    private AudioManager audioManager;
    private int previousMode;
    private boolean previousSpeaker, previousMicMute;
    private Runnable timeout;
    private Runnable disconnectTimeout;

    WebRtcCall(Activity activity, ChatController controller) { this.activity = activity; this.controller = controller; }
    boolean busy() { return session != null; }
    CallSession session() { return session; }
    long context() { return context; }

    void ring(CallSession incoming, String offer, long epoch) {
        if (busy()) return;
        session = incoming; context = epoch; remoteOffer = offer;
        timeout = () -> finish("来电已超时", true, "reject");
        main.postDelayed(timeout, 30000);
        ringDialog = new AlertDialog.Builder(activity).setTitle(incoming.peer + " 邀请你" + (incoming.mode.equals("video") ? "视频" : "语音") + "通话")
                .setMessage("通话媒体由 WebRTC 加密传输。")
                .setNegativeButton("拒绝", (d, w) -> finish("", true, "reject"))
                .setPositiveButton("接听", (d, w) -> {
                    if (session != incoming) return;
                    ((MainActivity) activity).requestCallPermissions(incoming.mode);
                })
                .setOnCancelListener(d -> finish("", true, "reject")).show();
    }

    void start(CallSession ready, JSONArray iceServers, long epoch) {
        if (session != null && session != ready) return;
        if (epoch != controller.locationContext()) { finish("连接已改变", false, null); return; }
        session = ready; context = epoch;
        cancelTimeout();
        timeout = () -> finish("通话连接超时", true, "hangup");
        main.postDelayed(timeout, 45000);
        try {
            showDialog();
            PeerConnectionFactory.initialize(PeerConnectionFactory.InitializationOptions.builder(activity).createInitializationOptions());
            egl = EglBase.create();
            localView.init(egl.getEglBaseContext(), null);
            remoteView.init(egl.getEglBaseContext(), null);
            localView.setMirror(true);
            factory = PeerConnectionFactory.builder().createPeerConnectionFactory();
            List<PeerConnection.IceServer> servers = parseIce(iceServers);
            PeerConnection.RTCConfiguration config = new PeerConnection.RTCConfiguration(servers);
            config.sdpSemantics = PeerConnection.SdpSemantics.UNIFIED_PLAN;
            peer = factory.createPeerConnection(config, observer());
            if (peer == null) throw new IllegalStateException("无法建立媒体连接");
            audioManager = (AudioManager) activity.getSystemService(Context.AUDIO_SERVICE);
            if (audioManager != null) {
                previousMode = audioManager.getMode(); previousSpeaker = audioManager.isSpeakerphoneOn();
                previousMicMute = audioManager.isMicrophoneMute();
                audioManager.setMode(AudioManager.MODE_IN_COMMUNICATION);
                audioManager.setSpeakerphoneOn(ready.mode.equals("video"));
                audioManager.setMicrophoneMute(false);
            }
            audioSource = factory.createAudioSource(new MediaConstraints());
            audioTrack = factory.createAudioTrack("audio", audioSource);
            peer.addTrack(audioTrack, List.of("chat"));
            if (ready.mode.equals("video")) startCamera();
            if (ready.outgoing) createOffer();
            else {
                if (remoteOffer == null) throw new IllegalStateException("来电信令缺失");
                setRemote(new SessionDescription(SessionDescription.Type.OFFER, remoteOffer), this::createAnswer);
            }
        } catch (Exception error) { finish("通话启动失败：" + error.getMessage(), true, "hangup"); }
    }

    private static List<PeerConnection.IceServer> parseIce(JSONArray items) {
        List<PeerConnection.IceServer> result = new ArrayList<>();
        for (int i = 0; items != null && i < items.length(); i++) {
            JSONObject item = items.optJSONObject(i);
            if (item == null) continue;
            List<String> urls = new ArrayList<>();
            Object value = item.opt("urls");
            if (value instanceof String) urls.add((String) value);
            else if (value instanceof JSONArray) for (int j = 0; j < ((JSONArray) value).length(); j++)
                urls.add(((JSONArray) value).optString(j));
            if (!urls.isEmpty()) result.add(PeerConnection.IceServer.builder(urls)
                    .setUsername(item.optString("username"))
                    .setPassword(item.optString("credential")).createIceServer());
        }
        if (result.isEmpty()) result.add(PeerConnection.IceServer.builder("stun:stun.l.google.com:19302").createIceServer());
        return result;
    }

    private void startCamera() throws Exception {
        Camera2Enumerator enumerator = new Camera2Enumerator(activity);
        String[] names = enumerator.getDeviceNames();
        String chosen = null;
        for (String name : names) if (enumerator.isFrontFacing(name)) { chosen = name; break; }
        if (chosen == null && names.length > 0) chosen = names[0];
        if (chosen == null) throw new IllegalStateException("设备没有可用摄像头");
        camera = enumerator.createCapturer(chosen, null);
        textureHelper = SurfaceTextureHelper.create("CameraCapture", egl.getEglBaseContext());
        videoSource = factory.createVideoSource(false);
        camera.initialize(textureHelper, activity, videoSource.getCapturerObserver());
        camera.startCapture(640, 480, 24);
        videoTrack = factory.createVideoTrack("video", videoSource);
        videoTrack.addSink(localView);
        peer.addTrack(videoTrack, List.of("chat"));
    }

    private void showDialog() {
        dialog = new Dialog(activity);
        LinearLayout root = new LinearLayout(activity);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(16, 16, 16, 16);
        root.setBackgroundColor(Color.rgb(20, 26, 29));
        status = new TextView(activity);
        status.setText(session.peer + " · " + (session.mode.equals("video") ? "视频" : "语音") + "通话连接中");
        status.setTextColor(Color.WHITE); status.setTextSize(18);
        root.addView(status);
        FrameLayout video = new FrameLayout(activity);
        remoteView = new SurfaceViewRenderer(activity);
        localView = new SurfaceViewRenderer(activity);
        localView.setZOrderMediaOverlay(true);
        video.addView(remoteView, new FrameLayout.LayoutParams(-1, -1));
        FrameLayout.LayoutParams thumbnail = new FrameLayout.LayoutParams(dp(110), dp(150), Gravity.TOP | Gravity.RIGHT);
        video.addView(localView, thumbnail);
        video.setVisibility(session.mode.equals("video") ? View.VISIBLE : View.GONE);
        root.addView(video, new LinearLayout.LayoutParams(-1, 0, 1));
        LinearLayout controls = new LinearLayout(activity);
        addButton(controls, "挂断", () -> finish("", true, "hangup"));
        addButton(controls, "静音", () -> { muted = !muted; if (audioTrack != null) audioTrack.setEnabled(!muted); });
        if (session.mode.equals("video")) {
            addButton(controls, "摄像头", () -> { cameraEnabled = !cameraEnabled; if (videoTrack != null) videoTrack.setEnabled(cameraEnabled); });
            addButton(controls, "切换", () -> { if (camera != null) camera.switchCamera(null); });
        }
        root.addView(controls);
        dialog.setContentView(root);
        dialog.setOnCancelListener(d -> finish("", true, "hangup"));
        dialog.show();
        if (dialog.getWindow() != null) dialog.getWindow().setLayout(-1, -1);
    }

    private void addButton(LinearLayout row, String label, Runnable action) {
        TextView button = new TextView(activity);
        button.setText(label); button.setTextColor(Color.WHITE); button.setTextSize(15);
        button.setGravity(Gravity.CENTER); button.setPadding(10, 20, 10, 20);
        button.setOnClickListener(v -> action.run());
        row.addView(button, new LinearLayout.LayoutParams(0, -2, 1));
    }

    private int dp(int size) { return Math.round(size * activity.getResources().getDisplayMetrics().density); }

    private PeerConnection.Observer observer() {
        CallSession owner = session;
        return new PeerConnection.Observer() {
            @Override public void onSignalingChange(PeerConnection.SignalingState state) { }
            @Override public void onIceConnectionChange(PeerConnection.IceConnectionState state) {
                main.post(() -> {
                    if (session != owner) return;
                    if (state == PeerConnection.IceConnectionState.CONNECTED || state == PeerConnection.IceConnectionState.COMPLETED) {
                        session.connected();
                        cancelTimeout();
                        cancelDisconnectTimeout();
                        if (status != null) status.setText(session.peer + " · 通话中");
                    } else if (state == PeerConnection.IceConnectionState.DISCONNECTED) {
                        session.disconnected();
                        if (status != null) status.setText(session.peer + " · 连接中断，正在恢复");
                        if (disconnectTimeout == null) {
                            disconnectTimeout = () -> {
                                disconnectTimeout = null;
                                if (session == owner && session.state == CallSession.State.INTERRUPTED)
                                    finish("通话连接已断开", true, "hangup");
                            };
                            main.postDelayed(disconnectTimeout, 15000);
                        }
                    } else if (state == PeerConnection.IceConnectionState.FAILED || state == PeerConnection.IceConnectionState.CLOSED)
                        finish("通话已断开", true, "hangup");
                });
            }
            @Override public void onIceConnectionReceivingChange(boolean receiving) { }
            @Override public void onIceGatheringChange(PeerConnection.IceGatheringState state) { }
            @Override public void onIceCandidate(IceCandidate candidate) {
                main.post(() -> {
                    if (session != owner) return;
                    JSONObject data = new JSONObject();
                    try { data.put("sdpMid", candidate.sdpMid).put("sdpMLineIndex", candidate.sdpMLineIndex)
                            .put("candidate", candidate.sdp); }
                    catch (Exception ignored) { return; }
                    if (descriptionSent) controller.sendCall(session, "ice", data.toString(), context);
                    else if (pendingLocalIce.size() < 128) pendingLocalIce.add(data.toString());
                });
            }
            @Override public void onIceCandidatesRemoved(IceCandidate[] candidates) { }
            @Override public void onAddStream(MediaStream stream) { }
            @Override public void onRemoveStream(MediaStream stream) { }
            @Override public void onDataChannel(org.webrtc.DataChannel channel) { }
            @Override public void onRenegotiationNeeded() { }
            @Override public void onAddTrack(RtpReceiver receiver, MediaStream[] streams) {
                main.post(() -> {
                    if (session == owner && receiver.track() instanceof VideoTrack && remoteView != null)
                        ((VideoTrack) receiver.track()).addSink(remoteView);
                });
            }
        };
    }

    private SdpObserver sdp(Runnable success) {
        CallSession owner = session;
        return new SdpObserver() {
            @Override public void onCreateSuccess(SessionDescription description) { main.post(() -> {
                if (session != owner || peer == null) return;
                String action = description.type == SessionDescription.Type.OFFER ? "offer" : "answer";
                peer.setLocalDescription(sdp(() -> {
                    controller.sendCall(owner, action, CallSession.sdpPayload(action, description.description), context);
                    descriptionSent = true;
                    for (String candidate : pendingLocalIce) controller.sendCall(owner, "ice", candidate, context);
                    pendingLocalIce.clear();
                }), description);
            }); }
            @Override public void onSetSuccess() { main.post(() -> { if (session == owner) success.run(); }); }
            @Override public void onCreateFailure(String error) { main.post(() -> { if (session == owner) finish(error, true, "hangup"); }); }
            @Override public void onSetFailure(String error) { main.post(() -> { if (session == owner) finish(error, true, "hangup"); }); }
        };
    }
    private void createOffer() { peer.createOffer(sdp(() -> { }), new MediaConstraints()); }
    private void createAnswer() { if (peer != null) peer.createAnswer(sdp(() -> { }), new MediaConstraints()); }
    private void setRemote(SessionDescription description, Runnable next) {
        if (peer == null) return;
        peer.setRemoteDescription(sdp(() -> {
            remoteDescriptionSet = true;
            for (IceCandidate candidate : earlyIce) peer.addIceCandidate(candidate);
            earlyIce.clear();
            next.run();
        }), description);
    }

    void signal(JSONObject frame) {
        if (session == null || !session.matches(frame)) return;
        String action = frame.optString("action");
        if (action.equals("ice")) {
            try {
                JSONObject data = new JSONObject(frame.getString("payload"));
                IceCandidate candidate = new IceCandidate(data.getString("sdpMid"), data.getInt("sdpMLineIndex"), data.getString("candidate"));
                if (peer != null && remoteDescriptionSet) peer.addIceCandidate(candidate);
                else if (earlyIce.size() < 128) earlyIce.add(candidate);
            } catch (Exception ignored) { }
        } else if (action.equals("answer") && session.accept(action) && peer != null) {
            try {
                String sdp = CallSession.parseSdpPayload("answer", frame.optString("payload"));
                setRemote(new SessionDescription(SessionDescription.Type.ANSWER, sdp), () -> { });
            } catch (IllegalArgumentException invalid) { finish("对方应答格式无效", true, "hangup"); }
        }
        else if (action.equals("hangup") || action.equals("reject") || action.equals("busy"))
            finish(action.equals("busy") ? "对方正在通话" : "通话已结束", false, null);
    }

    void error(JSONObject frame) {
        if (session != null && session.id.equals(frame.optString("callId")))
            finish(frame.optString("error", "通话失败"), false, null);
    }

    void finish(String message, boolean notify, String action) {
        CallSession old = session;
        if (old == null) return;
        session = null;
        old.state = CallSession.State.ENDED;
        cancelTimeout();
        cancelDisconnectTimeout();
        if (notify && action != null) controller.sendCall(old, action, null, context);
        if (ringDialog != null) {
            AlertDialog previous = ringDialog; ringDialog = null;
            previous.setOnCancelListener(null); previous.dismiss();
        }
        if (dialog != null) { Dialog previous = dialog; dialog = null; previous.setOnCancelListener(null); previous.dismiss(); }
        if (camera != null) { try { camera.stopCapture(); } catch (InterruptedException ignored) { Thread.currentThread().interrupt(); } camera.dispose(); camera = null; }
        if (videoTrack != null) { videoTrack.dispose(); videoTrack = null; }
        if (videoSource != null) { videoSource.dispose(); videoSource = null; }
        if (textureHelper != null) { textureHelper.dispose(); textureHelper = null; }
        if (audioTrack != null) { audioTrack.dispose(); audioTrack = null; }
        if (audioSource != null) { audioSource.dispose(); audioSource = null; }
        if (peer != null) { peer.close(); peer.dispose(); peer = null; }
        if (factory != null) { factory.dispose(); factory = null; }
        if (localView != null) { localView.release(); localView = null; }
        if (remoteView != null) { remoteView.release(); remoteView = null; }
        if (egl != null) { egl.release(); egl = null; }
        if (audioManager != null) {
            audioManager.setMicrophoneMute(previousMicMute);
            audioManager.setSpeakerphoneOn(previousSpeaker);
            audioManager.setMode(previousMode);
            audioManager = null;
        }
        earlyIce.clear(); pendingLocalIce.clear(); remoteOffer = null;
        remoteDescriptionSet = false; descriptionSent = false;
        if (!message.isEmpty()) Toast.makeText(activity, message, Toast.LENGTH_SHORT).show();
    }
    private void cancelTimeout() { if (timeout != null) { main.removeCallbacks(timeout); timeout = null; } }
    private void cancelDisconnectTimeout() {
        if (disconnectTimeout != null) { main.removeCallbacks(disconnectTimeout); disconnectTimeout = null; }
    }
}
