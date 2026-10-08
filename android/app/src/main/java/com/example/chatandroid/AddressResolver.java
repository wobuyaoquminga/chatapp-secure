package com.example.chatandroid;

import android.content.Context;
import android.location.Address;
import android.location.Geocoder;
import android.location.Location;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;

/** Optional sender-side address lookup. The platform geocoder runs off the main thread. */
final class AddressResolver implements AutoCloseable {
    private final Context context;
    private final Handler main = new Handler(Looper.getMainLooper());
    private final ExecutorService worker = Executors.newSingleThreadExecutor();
    private double cachedLat = Double.NaN, cachedLon = Double.NaN;
    private String cachedAddress = "";
    private long cachedAt;
    private long lastLiveQuery = Long.MIN_VALUE / 2;
    private final AtomicBoolean busy = new AtomicBoolean();
    private boolean closed;

    AddressResolver(Context context) { this.context = context.getApplicationContext(); }

    void resolve(double latitude, double longitude, boolean live, Consumer<String> callback) {
        if (closed || !Geocoder.isPresent() || !Double.isFinite(latitude) || Math.abs(latitude)>90 ||
                !Double.isFinite(longitude) || Math.abs(longitude)>180) { callback.accept(""); return; }
        long now = SystemClock.elapsedRealtime();
        float[] distance = new float[1];
        if (Double.isFinite(cachedLat)) Location.distanceBetween(latitude,longitude,cachedLat,cachedLon,distance);
        if (Double.isFinite(cachedLat) && now-cachedAt <= 300000 && distance[0] <= 30 && !cachedAddress.isEmpty()) { callback.accept(cachedAddress); return; }
        if (live && now-lastLiveQuery < 60000) { callback.accept(""); return; }
        if (!busy.compareAndSet(false,true)) { callback.accept(""); return; }
        if (live) lastLiveQuery = now;
        AtomicBoolean done = new AtomicBoolean();
        Runnable fallback = () -> { if (done.compareAndSet(false,true)) callback.accept(""); };
        main.postDelayed(fallback,4000);
        worker.execute(() -> {
            String result = "";
            try {
                List<Address> places = new Geocoder(context,Locale.SIMPLIFIED_CHINESE).getFromLocation(latitude,longitude,1);
                if (places != null && !places.isEmpty()) result = format(places.get(0));
            } catch (Exception ignored) { /* A missing backend must not block location delivery. */ }
            finally { busy.set(false); }
            final String address = result;
            main.post(() -> {
                if (done.compareAndSet(false,true)) {
                    main.removeCallbacks(fallback);
                    if (!closed && !address.isEmpty()) { cachedLat=latitude; cachedLon=longitude; cachedAddress=address; cachedAt=SystemClock.elapsedRealtime(); }
                    callback.accept(closed ? "" : address);
                }
            });
        });
    }

    static String format(Address place) {
        if (place == null) return "";
        String road = place.getThoroughfare(), number = place.getSubThoroughfare();
        if (road == null || road.trim().isEmpty()) return "";
        String value = road.trim() + (number == null || number.trim().isEmpty() ? "" : " " + number.trim()) + "附近";
        return LocationPayload.validAddress(value) ? value : "";
    }

    @Override public void close() { closed=true; worker.shutdownNow(); }
}
