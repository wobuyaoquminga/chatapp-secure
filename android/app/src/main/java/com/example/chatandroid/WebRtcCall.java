package com.example.chatandroid;

import android.app.Activity;
import android.app.AlertDialog;
import android.app.Dialog;
import android.content.Context;
import android.content.res.ColorStateList;
import android.graphics.Color;
import android.graphics.drawable.ColorDrawable;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.RippleDrawable;
import android.media.AudioManager;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewConfiguration;
import android.view.Window;
import android.view.WindowManager;
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
import org.webrtc.DefaultVideoDecoderFactory;
import org.webrtc.DefaultVideoEncoderFactory;
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
import org.webrtc.VideoCodecInfo;
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
    private TextView status, miniLabel, duration;
    private FrameLayout callRoot, videoFrame, miniTouch;
    private LinearLayout controls, audioInfo;
    private CallVideoView localView, remoteView;
    private EglBase egl;
    private PeerConnectionFactory factory;
    private PeerConnection peer;
    private AudioSource audioSource;
    private AudioTrack audioTrack;
    private VideoSource videoSource;
    private VideoTrack videoTrack;
    private VideoTrack remoteVideoTrack;
    private CameraVideoCapturer camera;
    private SurfaceTextureHelper textureHelper;
    private final List<IceCandidate> earlyIce = new ArrayList<>();
    private final List<String> pendingLocalIce = new ArrayList<>();
    // Local candidates are trickled: a phone with Wi-Fi, mobile data and a mirroring link can
    // gather enough candidates to trip the server's call-signal budget in a single burst.
    private final CallSignalPacer outboundIce = new CallSignalPacer();
    private boolean icePumpScheduled;
    private final Runnable icePump = new Runnable() {
        @Override public void run() {
            icePumpScheduled = false;
            if (session == null) { outboundIce.clear(); return; }
            String next = outboundIce.poll(SystemClock.elapsedRealtime());
            if (next != null) controller.sendCall(session, "ice", next, context);
            if (outboundIce.size() > 0) scheduleIcePump();
        }
    };
    private boolean remoteDescriptionSet, descriptionSent, muted, cameraEnabled = true;
    private AudioManager audioManager;
    private int previousMode;
    private boolean previousSpeaker, previousMicMute;
    private Runnable timeout;
    private Runnable disconnectTimeout;
    private long connectedAt;
    private boolean minimized, incomingAccepted, videoSwapped, remoteVideoReady;
    private View activityContent;
    private int miniX = -1, miniY = -1;
    private float dragX, dragY;
    private int dragStartX, dragStartY;
    private boolean dragged;
    private int insetX = -1, insetY = -1;
    private float insetDownX, insetDownY;
    private int insetStartX, insetStartY;
    private boolean insetDragged, insetMultiTouch;
    private final Runnable durationTick = new Runnable() {
        @Override public void run() {
            if (session == null) return;
            String value = elapsedLabel();
            if (duration != null) duration.setText(value);
            if (miniLabel != null) miniLabel.setText(session.peer + "\n" + value);
            main.postDelayed(this, 1000);
        }
    };

    WebRtcCall(Activity activity, ChatController controller) { this.activity = activity; this.controller = controller; }
    boolean busy() { return session != null; }
    CallSession session() { return session; }
    long context() { return context; }

    void ring(CallSession incoming, String offer, long epoch, boolean visible) {
        if (busy()) return;
        session = incoming; context = epoch; remoteOffer = offer;
        incomingAccepted = false;
        timeout = () -> finish("来电已超时", true, "reject");
        main.postDelayed(timeout, 30000);
        if (!visible) {
            CallNotifier.showIncoming(activity, incoming);
            return;
        }
        showPendingIncoming();
    }

    void showPendingIncoming() {
        if (session == null || session.outgoing || incomingAccepted || ringDialog != null || dialog != null) return;
        CallNotifier.cancel(activity);
        CallSession incoming = session;
        ringDialog = new AlertDialog.Builder(activity).setTitle(incoming.peer + " 邀请你" + (incoming.mode.equals("video") ? "视频" : "语音") + "通话")
                .setMessage("通话媒体由 WebRTC 加密传输。")
                .setNegativeButton("拒绝", (d, w) -> finish("", true, "reject"))
                .setPositiveButton("接听", (d, w) -> {
                    if (session != incoming) return;
                    incomingAccepted = true;
                    ((MainActivity) activity).requestCallPermissions(incoming.mode);
                })
                .setOnCancelListener(d -> finish("", true, "reject")).show();
        android.widget.Button reject = ringDialog.getButton(AlertDialog.BUTTON_NEGATIVE);
        if (reject != null) {
            reject.setTextColor(Color.WHITE);
            reject.setBackgroundResource(R.drawable.dialog_destructive_button);
        }
    }

    void onBackgrounded() {
        if (session == null || session.outgoing || incomingAccepted || dialog != null) return;
        if (ringDialog != null) {
            AlertDialog previous = ringDialog;
            ringDialog = null;
            previous.setOnCancelListener(null);
            previous.dismiss();
        }
        CallNotifier.showIncoming(activity, session);
    }

    void start(CallSession ready, JSONArray iceServers, long epoch) {
        if (session != null && session != ready) return;
        if (epoch != controller.locationContext()) { finish("连接已改变", false, null); return; }
        session = ready; context = epoch;
        cancelTimeout();
        timeout = () -> finish(connectedAt == 0
                ? "通话连接超时：双方网络可能无法直连，需要 TURN 中继"
                : "通话连接超时", true, "hangup");
        main.postDelayed(timeout, 45000);
        try {
            CallNotifier.cancel(activity);
            showDialog();
            PeerConnectionFactory.initialize(PeerConnectionFactory.InitializationOptions.builder(activity).createInitializationOptions());
            egl = EglBase.create();
            localView.init(egl.getEglBaseContext());
            remoteView.init(egl.getEglBaseContext());
            localView.setMirror(true);
            PeerConnectionFactory.Builder factoryBuilder = PeerConnectionFactory.builder();
            if (ready.mode.equals("video")) {
                DefaultVideoEncoderFactory encoder = new DefaultVideoEncoderFactory(
                        egl.getEglBaseContext(), true, false);
                DefaultVideoDecoderFactory decoder = new DefaultVideoDecoderFactory(egl.getEglBaseContext());
                if (!supportsVp8(encoder.getSupportedCodecs()) || !supportsVp8(decoder.getSupportedCodecs()))
                    throw new IllegalStateException("此设备缺少 VP8 视频编解码器");
                factoryBuilder.setVideoEncoderFactory(encoder).setVideoDecoderFactory(decoder);
            }
            factory = factoryBuilder.createPeerConnectionFactory();
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
            // Starting the service before camera/WebRTC setup can starve its main-thread
            // onStartCommand long enough for Android to kill the process.
            CallKeepAliveService.start(activity, ready.mode);
            CallKeepAliveService.setOnTaskRemoved(() -> main.post(() -> finish("", true, "hangup")));
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

    private static boolean supportsVp8(VideoCodecInfo[] codecs) {
        for (VideoCodecInfo codec : codecs) if ("VP8".equalsIgnoreCase(codec.name)) return true;
        return false;
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
        dialog = new Dialog(activity, android.R.style.Theme_Black_NoTitleBar_Fullscreen);
        callRoot = new FrameLayout(activity);
        callRoot.setBackgroundColor(Color.rgb(20, 26, 29));
        videoFrame = new FrameLayout(activity);
        remoteView = new CallVideoView(activity);
        localView = new CallVideoView(activity);
        videoFrame.addView(remoteView, new FrameLayout.LayoutParams(-1, -1));
        videoFrame.addView(localView, new FrameLayout.LayoutParams(-1, -1));
        videoFrame.addOnLayoutChangeListener((view, left, top, right, bottom,
                oldLeft, oldTop, oldRight, oldBottom) -> {
            if (right - left != oldRight - oldLeft || bottom - top != oldBottom - oldTop)
                positionInset();
        });
        videoFrame.setVisibility(session.mode.equals("video") ? View.VISIBLE : View.GONE);
        callRoot.addView(videoFrame, new FrameLayout.LayoutParams(-1, -1));

        audioInfo = new LinearLayout(activity);
        audioInfo.setOrientation(LinearLayout.VERTICAL);
        audioInfo.setGravity(Gravity.CENTER);
        TextView avatar = new TextView(activity);
        avatar.setText(session.peer.isEmpty() ? "?" : session.peer.substring(0, 1));
        avatar.setTextSize(42);
        avatar.setTextColor(Color.WHITE);
        avatar.setGravity(Gravity.CENTER);
        avatar.setBackground(roundBackground(Color.rgb(39, 91, 79), dp(48)));
        audioInfo.addView(avatar, new LinearLayout.LayoutParams(dp(96), dp(96)));
        TextView peerName = new TextView(activity);
        peerName.setText(session.peer);
        peerName.setTextColor(Color.WHITE);
        peerName.setTextSize(28);
        peerName.setGravity(Gravity.CENTER);
        LinearLayout.LayoutParams nameParams = new LinearLayout.LayoutParams(-1, -2);
        nameParams.topMargin = dp(24);
        audioInfo.addView(peerName, nameParams);
        audioInfo.setVisibility(session.mode.equals("audio") ? View.VISIBLE : View.GONE);
        callRoot.addView(audioInfo, new FrameLayout.LayoutParams(-1, -1));

        status = new TextView(activity);
        status.setText(session.peer + " · " + (session.mode.equals("video") ? "视频" : "语音") + "通话连接中");
        status.setTextColor(Color.WHITE); status.setTextSize(17);
        status.setGravity(Gravity.CENTER);
        status.setBackgroundColor(Color.argb(190, 20, 26, 29));
        status.setPadding(dp(16), dp(24), dp(16), dp(12));
        callRoot.addView(status, new FrameLayout.LayoutParams(-1, -2, Gravity.TOP));

        duration = new TextView(activity);
        duration.setTextColor(Color.WHITE);
        duration.setTextSize(20);
        duration.setGravity(Gravity.CENTER);
        FrameLayout.LayoutParams durationParams = new FrameLayout.LayoutParams(-1, -2, Gravity.CENTER);
        durationParams.topMargin = dp(160);
        callRoot.addView(duration, durationParams);
        duration.setVisibility(session.mode.equals("audio") ? View.VISIBLE : View.GONE);

        controls = new LinearLayout(activity);
        controls.setGravity(Gravity.CENTER);
        controls.setBackgroundColor(Color.argb(190, 20, 26, 29));
        controls.setPadding(dp(8), dp(16), dp(8), dp(28));
        addButton(controls, "最小化", () -> setMinimized(true));
        addButton(controls, "挂断", () -> finish("", true, "hangup"));
        addButton(controls, "静音", () -> { muted = !muted; if (audioTrack != null) audioTrack.setEnabled(!muted); });
        if (session.mode.equals("video")) {
            addButton(controls, "摄像头", () -> { cameraEnabled = !cameraEnabled; if (videoTrack != null) videoTrack.setEnabled(cameraEnabled); });
            addButton(controls, "切换", () -> { if (camera != null) camera.switchCamera(null); });
        }
        callRoot.addView(controls, new FrameLayout.LayoutParams(-1, -2, Gravity.BOTTOM));

        miniLabel = new TextView(activity);
        miniLabel.setTextColor(Color.WHITE);
        miniLabel.setTextSize(14);
        miniLabel.setGravity(Gravity.CENTER);
        miniLabel.setVisibility(View.GONE);
        miniLabel.setBackground(roundBackground(Color.rgb(29, 46, 49), dp(16)));
        callRoot.addView(miniLabel, new FrameLayout.LayoutParams(-1, -1));
        miniTouch = new FrameLayout(activity);
        miniTouch.setContentDescription("拖动通话小窗，点击返回全屏");
        miniTouch.setVisibility(View.GONE);
        miniTouch.setOnTouchListener(this::onMiniTouch);
        callRoot.addView(miniTouch, new FrameLayout.LayoutParams(-1, -1));
        callRoot.addOnLayoutChangeListener((view, left, top, right, bottom,
                oldLeft, oldTop, oldRight, oldBottom) -> {
            if (minimized && (right - left != oldRight - oldLeft || bottom - top != oldBottom - oldTop))
                clampMiniWindow();
        });

        dialog.setContentView(callRoot);
        dialog.setCanceledOnTouchOutside(false);
        dialog.setOnKeyListener((d, keyCode, event) -> {
            if (keyCode != KeyEvent.KEYCODE_BACK) return false;
            if (event.getAction() == KeyEvent.ACTION_UP) setMinimized(true);
            return true;
        });
        Window window = dialog.getWindow();
        if (window != null) window.setWindowAnimations(R.style.ChatCallAnimation);
        dialog.show();
        if (window != null) {
            window.setBackgroundDrawableResource(android.R.color.transparent);
            window.clearFlags(WindowManager.LayoutParams.FLAG_DIM_BEHIND);
            window.addFlags(WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL);
            setMinimized(false);
        }
        main.post(durationTick);
    }

    private GradientDrawable roundBackground(int color, int radius) {
        GradientDrawable shape = new GradientDrawable();
        shape.setColor(color);
        shape.setCornerRadius(radius);
        return shape;
    }

    private String elapsedLabel() {
        if (connectedAt == 0) return "连接中";
        long seconds = (SystemClock.elapsedRealtime() - connectedAt) / 1000;
        if (seconds >= 3600) return String.format(java.util.Locale.ROOT, "%02d:%02d:%02d",
                seconds / 3600, (seconds / 60) % 60, seconds % 60);
        return String.format(java.util.Locale.ROOT, "%02d:%02d", seconds / 60, seconds % 60);
    }

    private void setMinimized(boolean value) {
        if (dialog == null || dialog.getWindow() == null) return;
        minimized = value;
        Window window = dialog.getWindow();
        if (activityContent == null) activityContent = activity.findViewById(android.R.id.content);
        if (activityContent != null) activityContent.setVisibility(value ? View.VISIBLE : View.INVISIBLE);
        activity.getWindow().getDecorView().setBackgroundColor(value ? Color.WHITE : Color.rgb(20, 26, 29));
        status.setVisibility(value ? View.GONE : View.VISIBLE);
        controls.setVisibility(value ? View.GONE : View.VISIBLE);
        audioInfo.setVisibility(value ? View.GONE : (session.mode.equals("audio") ? View.VISIBLE : View.GONE));
        duration.setVisibility(value ? View.GONE : (session.mode.equals("audio") ? View.VISIBLE : View.GONE));
        miniLabel.setVisibility(value && session.mode.equals("audio") ? View.VISIBLE : View.GONE);
        miniTouch.setVisibility(value ? View.VISIBLE : View.GONE);
        if (session.mode.equals("video")) updateVideoLayout();
        if (value) {
            window.setBackgroundDrawableResource(android.R.color.transparent);
            callRoot.setBackground(roundBackground(Color.rgb(20, 26, 29), dp(18)));
            callRoot.setClipToOutline(true);
            window.clearFlags(WindowManager.LayoutParams.FLAG_FULLSCREEN);
            window.clearFlags(WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS);
            window.addFlags(WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                    | WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL);
            window.getDecorView().setSystemUiVisibility(0);
            WindowManager.LayoutParams params = window.getAttributes();
            params.gravity = Gravity.TOP | Gravity.START;
            params.width = dp(session.mode.equals("video") ? 190 : 126);
            params.height = dp(session.mode.equals("video") ? 142 : 126);
            if (miniX < 0) miniX = Math.max(0, activity.getResources().getDisplayMetrics().widthPixels - params.width - dp(16));
            if (miniY < 0) miniY = dp(72);
            miniX = clamp(miniX, activity.getResources().getDisplayMetrics().widthPixels - params.width);
            miniY = clamp(miniY, activity.getResources().getDisplayMetrics().heightPixels - params.height);
            params.x = miniX; params.y = miniY;
            window.setAttributes(params);
        } else {
            window.setBackgroundDrawable(new ColorDrawable(Color.rgb(20, 26, 29)));
            callRoot.setBackgroundColor(Color.rgb(20, 26, 29));
            callRoot.setClipToOutline(false);
            window.clearFlags(WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE);
            window.addFlags(WindowManager.LayoutParams.FLAG_FULLSCREEN | WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL
                    | WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS);
            window.getDecorView().setSystemUiVisibility(View.SYSTEM_UI_FLAG_FULLSCREEN
                    | View.SYSTEM_UI_FLAG_HIDE_NAVIGATION | View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
                    | View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN | View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
                    | View.SYSTEM_UI_FLAG_LAYOUT_STABLE);
            WindowManager.LayoutParams params = window.getAttributes();
            params.gravity = Gravity.CENTER;
            params.x = params.y = 0;
            params.width = params.height = -1;
            window.setAttributes(params);
        }
    }

    private void swapVideo() {
        if (session == null || minimized || !remoteVideoReady || !"video".equals(session.mode)) return;
        videoSwapped = !videoSwapped;
        updateVideoLayout();
    }

    private void updateVideoLayout() {
        if (localView == null || remoteView == null) return;
        // Keep the local preview full size until an actual peer frame arrives.
        // A track can exist well before ICE connects and would otherwise show black.
        boolean localMain = !remoteVideoReady || (!minimized && videoSwapped);
        CallVideoView mainView = localMain ? localView : remoteView;
        CallVideoView insetView = localMain ? remoteView : localView;
        mainView.setVisibility(View.VISIBLE);
        mainView.setRounded(minimized);
        mainView.setLayoutParams(new FrameLayout.LayoutParams(-1, -1));
        mainView.setOnClickListener(null);
        mainView.setOnTouchListener(null);
        insetView.setVisibility(minimized || !remoteVideoReady ? View.GONE : View.VISIBLE);
        insetView.setRounded(!minimized);
        insetView.setOnClickListener(v -> swapVideo());
        insetView.setOnTouchListener(this::onInsetTouch);
        FrameLayout.LayoutParams inset = new FrameLayout.LayoutParams(dp(110), dp(150),
                Gravity.TOP | Gravity.START);
        inset.leftMargin = insetX < 0
                ? Math.max(0, activity.getResources().getDisplayMetrics().widthPixels - dp(126)) : insetX;
        inset.topMargin = insetY < 0 ? dp(24) : insetY;
        insetView.setLayoutParams(inset);
        mainView.bringToFront();
        insetView.bringToFront();
        if (!minimized) videoFrame.post(this::positionInset);
    }

    private boolean onInsetTouch(View view, MotionEvent event) {
        if (minimized || !remoteVideoReady) return false;
        switch (event.getActionMasked()) {
            case MotionEvent.ACTION_DOWN:
                positionInset();
                insetDownX = event.getRawX(); insetDownY = event.getRawY();
                insetStartX = Math.max(0, insetX); insetStartY = Math.max(0, insetY);
                insetDragged = insetMultiTouch = false;
                return true;
            case MotionEvent.ACTION_POINTER_DOWN:
                insetMultiTouch = true;
                return true;
            case MotionEvent.ACTION_MOVE:
                if (insetMultiTouch) return true;
                int dx = Math.round(event.getRawX() - insetDownX);
                int dy = Math.round(event.getRawY() - insetDownY);
                int slop = ViewConfiguration.get(activity).getScaledTouchSlop();
                if (Math.abs(dx) > slop || Math.abs(dy) > slop) insetDragged = true;
                if (insetDragged) {
                    insetX = clamp(insetStartX + dx, videoFrame.getWidth() - view.getWidth());
                    insetY = clamp(insetStartY + dy, videoFrame.getHeight() - view.getHeight());
                    FrameLayout.LayoutParams params = (FrameLayout.LayoutParams) view.getLayoutParams();
                    params.leftMargin = insetX; params.topMargin = insetY;
                    view.setLayoutParams(params);
                }
                return true;
            case MotionEvent.ACTION_UP:
                if (!insetDragged && !insetMultiTouch) view.performClick();
                return true;
            case MotionEvent.ACTION_CANCEL:
                return true;
            default:
                return true;
        }
    }

    private void positionInset() {
        if (minimized || !remoteVideoReady || videoFrame == null || videoFrame.getWidth() <= dp(190)
                || videoFrame.getHeight() <= dp(150))
            return;
        CallVideoView inset = videoSwapped ? remoteView : localView;
        if (inset == null || inset.getVisibility() != View.VISIBLE) return;
        int width = dp(110);
        int height = dp(150);
        insetX = clamp(insetX < 0 ? videoFrame.getWidth() - width - dp(16) : insetX,
                videoFrame.getWidth() - width);
        insetY = clamp(insetY < 0 ? dp(24) : insetY, videoFrame.getHeight() - height);
        FrameLayout.LayoutParams params = (FrameLayout.LayoutParams) inset.getLayoutParams();
        if (params.leftMargin != insetX || params.topMargin != insetY) {
            params.leftMargin = insetX; params.topMargin = insetY;
            inset.setLayoutParams(params);
        }
    }

    private void clampMiniWindow() {
        if (dialog == null || dialog.getWindow() == null) return;
        Window window = dialog.getWindow();
        WindowManager.LayoutParams params = window.getAttributes();
        miniX = clamp(miniX, activity.getResources().getDisplayMetrics().widthPixels - params.width);
        miniY = clamp(miniY, activity.getResources().getDisplayMetrics().heightPixels - params.height);
        if (params.x != miniX || params.y != miniY) {
            params.x = miniX; params.y = miniY;
            window.setAttributes(params);
        }
    }

    private static int clamp(int value, int maximum) {
        return Math.max(0, Math.min(value, Math.max(0, maximum)));
    }

    private boolean onMiniTouch(View view, MotionEvent event) {
        if (!minimized || dialog == null || dialog.getWindow() == null) return false;
        Window window = dialog.getWindow();
        if (event.getActionMasked() == MotionEvent.ACTION_DOWN) {
            dragX = event.getRawX(); dragY = event.getRawY();
            dragStartX = miniX; dragStartY = miniY;
            dragged = false;
            return true;
        }
        if (event.getActionMasked() == MotionEvent.ACTION_MOVE) {
            int x = Math.round(event.getRawX() - dragX);
            int y = Math.round(event.getRawY() - dragY);
            if (Math.abs(x) > dp(4) || Math.abs(y) > dp(4)) dragged = true;
            if (dragged) {
                WindowManager.LayoutParams params = window.getAttributes();
                miniX = Math.max(0, Math.min(dragStartX + x,
                        activity.getResources().getDisplayMetrics().widthPixels - params.width));
                miniY = Math.max(0, Math.min(dragStartY + y,
                        activity.getResources().getDisplayMetrics().heightPixels - params.height));
                params.x = miniX; params.y = miniY;
                window.setAttributes(params);
            }
            return true;
        }
        if (event.getActionMasked() == MotionEvent.ACTION_UP) {
            if (!dragged) setMinimized(false);
            return true;
        }
        return true;
    }

    private void addButton(LinearLayout row, String label, Runnable action) {
        TextView button = new TextView(activity);
        button.setText(label); button.setTextColor(Color.WHITE); button.setTextSize(15);
        button.setGravity(Gravity.CENTER); button.setPadding(dp(4), dp(10), dp(4), dp(10));
        int fill = label.equals("挂断") ? Color.rgb(184, 53, 48) : Color.rgb(49, 74, 73);
        button.setBackground(new RippleDrawable(ColorStateList.valueOf(Color.rgb(144, 178, 162)),
                roundBackground(fill, dp(11)), null));
        button.setOnClickListener(v -> action.run());
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(0, dp(48), 1);
        params.setMargins(dp(2), 0, dp(2), 0);
        row.addView(button, params);
    }

    private int dp(int size) { return Math.round(size * activity.getResources().getDisplayMetrics().density); }

    private void scheduleIcePump() {
        if (icePumpScheduled || session == null) return;
        icePumpScheduled = true;
        main.postDelayed(icePump, CallSignalPacer.INTERVAL_MS);
    }
    private void queueIce(String payload) { if (outboundIce.enqueue(payload)) scheduleIcePump(); }

    private PeerConnection.Observer observer() {
        CallSession owner = session;
        return new PeerConnection.Observer() {
            @Override public void onSignalingChange(PeerConnection.SignalingState state) { }
            @Override public void onIceConnectionChange(PeerConnection.IceConnectionState state) {
                main.post(() -> {
                    if (session != owner) return;
                    if (state == PeerConnection.IceConnectionState.CONNECTED || state == PeerConnection.IceConnectionState.COMPLETED) {
                        session.connected();
                        if (connectedAt == 0) connectedAt = SystemClock.elapsedRealtime();
                        cancelTimeout();
                        cancelDisconnectTimeout();
                        if (status != null) status.setText(session.peer + " · 通话中");
                        setMinimized(minimized);
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
                        finish(connectedAt == 0
                                ? "通话无法建立：双方网络无法直连，可能需要配置 TURN 中继"
                                : "通话已断开", true, "hangup");
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
                    if (descriptionSent) queueIce(data.toString());
                    else if (pendingLocalIce.size() < 128) pendingLocalIce.add(data.toString());
                });
            }
            @Override public void onIceCandidatesRemoved(IceCandidate[] candidates) { }
            @Override public void onAddStream(MediaStream stream) { }
            @Override public void onRemoveStream(MediaStream stream) { }
            @Override public void onDataChannel(org.webrtc.DataChannel channel) { }
            @Override public void onRenegotiationNeeded() { }
            @Override public void onAddTrack(RtpReceiver receiver, MediaStream[] streams) {
                // Read the receiver on WebRTC's callback thread. Its native handle can
                // be invalid by the time a posted UI callback runs.
                org.webrtc.MediaStreamTrack track = receiver.track();
                if (!(track instanceof VideoTrack)) return;
                VideoTrack incoming = (VideoTrack) track;
                main.post(() -> {
                    if (session != owner || remoteView == null || remoteVideoTrack == incoming) return;
                    if (remoteVideoTrack != null) remoteVideoTrack.removeSink(remoteView);
                    remoteVideoTrack = incoming;
                    remoteVideoReady = false;
                    remoteView.onFirstFrame(() -> main.post(() -> {
                        if (session != owner || remoteVideoTrack != incoming) return;
                        remoteVideoReady = true;
                        updateVideoLayout();
                    }));
                    incoming.addSink(remoteView);
                    updateVideoLayout();
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
                    for (String candidate : pendingLocalIce) queueIce(candidate);
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
        main.removeCallbacks(durationTick);
        if (notify && action != null) controller.sendCall(old, action, null, context);
        CallNotifier.cancel(activity);
        CallKeepAliveService.stop(activity);
        if (ringDialog != null) {
            AlertDialog previous = ringDialog; ringDialog = null;
            previous.setOnCancelListener(null); previous.dismiss();
        }
        if (dialog != null) { Dialog previous = dialog; dialog = null; previous.setOnCancelListener(null); previous.dismiss(); }
        if (activityContent != null) { activityContent.setVisibility(View.VISIBLE); activityContent = null; }
        activity.getWindow().getDecorView().setBackgroundColor(Color.WHITE);
        callRoot = videoFrame = miniTouch = null;
        controls = audioInfo = null;
        status = miniLabel = duration = null;
        connectedAt = 0;
        minimized = false;
        videoSwapped = false;
        remoteVideoReady = false;
        incomingAccepted = false;
        miniX = miniY = -1;
        insetX = insetY = -1;
        if (camera != null) { try { camera.stopCapture(); } catch (InterruptedException ignored) { Thread.currentThread().interrupt(); } camera.dispose(); camera = null; }
        if (videoTrack != null) { videoTrack.dispose(); videoTrack = null; }
        if (videoSource != null) { videoSource.dispose(); videoSource = null; }
        if (textureHelper != null) { textureHelper.dispose(); textureHelper = null; }
        if (audioTrack != null) { audioTrack.dispose(); audioTrack = null; }
        if (audioSource != null) { audioSource.dispose(); audioSource = null; }
        if (remoteVideoTrack != null) {
            if (remoteView != null) remoteVideoTrack.removeSink(remoteView);
            remoteVideoTrack = null;
        }
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
        earlyIce.clear(); pendingLocalIce.clear(); outboundIce.clear();
        main.removeCallbacks(icePump); icePumpScheduled = false;
        remoteOffer = null;
        remoteDescriptionSet = false; descriptionSent = false;
        if (!message.isEmpty()) Toast.makeText(activity, message, Toast.LENGTH_SHORT).show();
    }
    private void cancelTimeout() { if (timeout != null) { main.removeCallbacks(timeout); timeout = null; } }
    private void cancelDisconnectTimeout() {
        if (disconnectTimeout != null) { main.removeCallbacks(disconnectTimeout); disconnectTimeout = null; }
    }
}
