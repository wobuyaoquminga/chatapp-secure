package com.example.chatandroid;

import android.Manifest;
import android.content.Context;
import android.content.pm.PackageManager;
import android.location.Location;
import android.location.LocationListener;
import android.location.LocationManager;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;

/** Foreground-only location acquisition; runtime permission belongs to the Activity. */
public final class LocationSharing implements AutoCloseable {
    public interface Listener { void onLocationState(boolean live, String peer, String notice); }
    private final Context context;
    private final ChatController controller;
    private final Listener listener;
    private final LocationManager manager;
    private final AddressResolver addresses;
    private final Handler main = new Handler(Looper.getMainLooper());
    private LocationListener updates;
    private String peer = "";
    private boolean live, closed;
    private long started, lastFix, requestContext, requestId;
    private final Runnable timeout = () -> finish("定位超时，请检查定位服务后重试");
    private final Runnable expiry = () -> finish("实时位置分享已到期");
    public LocationSharing(Context context, ChatController controller, Listener listener) {
        this.context = context.getApplicationContext(); this.controller = controller; this.listener = listener;
        manager = (LocationManager) this.context.getSystemService(Context.LOCATION_SERVICE);
        addresses = new AddressResolver(this.context);
        controller.setLocationStoppedListener(() -> {
            if (live || updates != null) { release(); listener.onLocationState(false, "", "位置分享已停止"); }
        });
    }
    private boolean locationEnabled() {
        if (manager == null) return false;
        if (android.os.Build.VERSION.SDK_INT >= 28) return manager.isLocationEnabled();
        return manager.isProviderEnabled(LocationManager.GPS_PROVIDER) || manager.isProviderEnabled(LocationManager.NETWORK_PROVIDER);
    }
    public boolean hasPermission() {
        return context.checkSelfPermission(Manifest.permission.ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_GRANTED
                || context.checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED;
    }
    public void requestPin(String peer) { begin(peer, false); }
    public void startLive(String peer) { begin(peer, true); }
    public boolean isLive() { return live; }
    public String peer() { return peer; }
    private void begin(String target, boolean continuous) {
        if (closed) return;
        release();
        if (!continuous) controller.stopLiveForPin();
        if (!hasPermission()) { finish("请先允许定位权限"); return; }
        if (manager == null || !locationEnabled()) { finish("请在系统设置中开启定位服务"); return; }
        peer = target; live = continuous; requestContext = controller.locationContext(); started = SystemClock.elapsedRealtime(); lastFix = 0;
        if (continuous) controller.startLive(target,requestContext);
        updates = new LocationListener() {
            @Override public void onLocationChanged(Location position) {
                if (updates != this || closed) return;
                if (!hasPermission()) { finish("定位权限已撤销"); return; }
                long age = (SystemClock.elapsedRealtimeNanos() - position.getElapsedRealtimeNanos()) / 1000000L;
                if (age < 0 || age > 30000 || position.getElapsedRealtimeNanos()/1000000L < started - 2000 || !position.hasAccuracy() || position.getAccuracy() > 100000) return;
                long now = SystemClock.elapsedRealtime();
                if (live && lastFix > 0 && now - lastFix < 10000) return;
                main.removeCallbacks(timeout); lastFix = now;
                if (live) {
                    long expectedRequest=requestId, expectedContext=requestContext;
                    String recipient=peer;
                    addresses.resolve(position.getLatitude(),position.getLongitude(),true,address -> {
                        if (closed || !live || requestId!=expectedRequest || !recipient.equals(peer) || controller.locationContext()!=expectedContext) return;
                        controller.sendLiveLocation(position.getLatitude(),position.getLongitude(),position.getAccuracy(),address);
                        listener.onLocationState(true,peer,"正在分享实时位置 · 误差约 " + Math.round(position.getAccuracy()) + " 米");
                    });
                } else {
                    String recipient = peer;
                    long expectedContext = requestContext;
                    release();
                    long expectedRequest=requestId;
                    listener.onLocationState(false,"","正在解析地址并发送位置…");
                    addresses.resolve(position.getLatitude(),position.getLongitude(),false,address -> {
                        if (closed || requestId!=expectedRequest || controller.locationContext()!=expectedContext) return;
                        controller.sendPin(recipient,position.getLatitude(),position.getLongitude(),position.getAccuracy(),expectedContext,address);
                        listener.onLocationState(false,"","位置已发送 · 误差约 " + Math.round(position.getAccuracy()) + " 米");
                    });
                }
            }
            @Override public void onProviderDisabled(String provider) {
                if (!locationEnabled()) finish("系统定位已关闭，位置分享已停止");
            }
            @Override public void onProviderEnabled(String provider) { }
            @Override public void onStatusChanged(String provider, int status, Bundle extras) { }
        };
        boolean registered = false;
        try {
            if (context.checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED
                    && manager.isProviderEnabled(LocationManager.GPS_PROVIDER)) {
                manager.requestLocationUpdates(LocationManager.GPS_PROVIDER,continuous ? 10000 : 0,0,updates,Looper.getMainLooper()); registered = true;
            }
            if (manager.isProviderEnabled(LocationManager.NETWORK_PROVIDER)) {
                manager.requestLocationUpdates(LocationManager.NETWORK_PROVIDER,continuous ? 10000 : 0,0,updates,Looper.getMainLooper()); registered = true;
            }
        } catch (SecurityException error) { finish("定位权限已撤销，请重新授权"); return; }
        catch (RuntimeException error) { finish("系统定位暂不可用，请重试"); return; }
        if (!registered) { finish("没有可用的定位来源，请开启定位或手动输入坐标"); return; }
        main.postDelayed(timeout,30000);
        if (continuous) main.postDelayed(expiry,LocationPayload.MAX_DURATION);
        listener.onLocationState(continuous,target,continuous ? "正在获取位置，分享最长一小时" : "正在获取当前位置");
    }
    private void release() {
        requestId++;
        if (updates != null && manager != null) { try { manager.removeUpdates(updates); } catch (RuntimeException ignored) { } }
        updates = null; live = false; peer = "";
        main.removeCallbacks(timeout); main.removeCallbacks(expiry);
    }
    private void finish(String notice) { release(); controller.stopLive(); listener.onLocationState(false,"",notice); }
    public void stopLive() { release(); controller.stopLive(); }
    @Override public void close() { if (closed) return; stopLive(); closed = true; addresses.close(); controller.setLocationStoppedListener(null); }
}
