package com.example.chatandroid;

import android.content.Context;
import android.graphics.Outline;
import android.graphics.SurfaceTexture;
import android.view.TextureView;
import android.view.View;
import android.view.ViewOutlineProvider;
import org.webrtc.EglBase;
import org.webrtc.EglRenderer;
import org.webrtc.GlRectDrawer;
import org.webrtc.VideoFrame;
import org.webrtc.VideoSink;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicBoolean;

/** WebRTC video in a TextureView so Android can actually clip live pixels to rounded corners. */
final class CallVideoView extends TextureView implements VideoSink, TextureView.SurfaceTextureListener {
    private volatile EglRenderer renderer;
    private volatile Runnable firstFrameListener;
    private final AtomicBoolean firstFrameSeen = new AtomicBoolean();
    private boolean mirror;

    CallVideoView(Context context) {
        super(context);
        setSurfaceTextureListener(this);
        setOutlineProvider(new ViewOutlineProvider() {
            @Override public void getOutline(View view, Outline outline) {
                outline.setRoundRect(0, 0, view.getWidth(), view.getHeight(),
                        18 * getResources().getDisplayMetrics().density);
            }
        });
    }

    void setRounded(boolean value) {
        setClipToOutline(value);
        invalidateOutline();
    }

    void init(EglBase.Context context) {
        renderer = new EglRenderer("ChatCallVideo");
        renderer.init(context, EglBase.CONFIG_PLAIN, new GlRectDrawer());
        renderer.setMirror(mirror);
        if (isAvailable()) renderer.createEglSurface(getSurfaceTexture());
    }

    void setMirror(boolean value) {
        mirror = value;
        if (renderer != null) renderer.setMirror(value);
    }

    void onFirstFrame(Runnable listener) {
        firstFrameListener = listener;
        firstFrameSeen.set(false);
    }

    @Override public void onFrame(VideoFrame frame) {
        EglRenderer active = renderer;
        if (active != null) {
            active.onFrame(frame);
            if (firstFrameSeen.compareAndSet(false, true)) {
                Runnable callback = firstFrameListener;
                if (callback != null) callback.run();
            }
        }
    }

    @Override public void onSurfaceTextureAvailable(SurfaceTexture texture, int width, int height) {
        if (renderer != null) renderer.createEglSurface(texture);
    }

    @Override public void onSurfaceTextureSizeChanged(SurfaceTexture texture, int width, int height) { }

    @Override public boolean onSurfaceTextureDestroyed(SurfaceTexture texture) {
        EglRenderer active = renderer;
        if (active != null) {
            CountDownLatch released = new CountDownLatch(1);
            active.releaseEglSurface(released::countDown);
            boolean interrupted = false;
            while (true) {
                try { released.await(); break; }
                catch (InterruptedException ignored) { interrupted = true; }
            }
            if (interrupted) Thread.currentThread().interrupt();
        }
        return true;
    }

    @Override public void onSurfaceTextureUpdated(SurfaceTexture texture) { }

    void release() {
        EglRenderer active = renderer;
        renderer = null;
        firstFrameListener = null;
        if (active != null) active.release();
    }
}
