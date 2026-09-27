package com.example.chatandroid;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.view.View;

/** Network-free coordinate preview. Grid is illustrative, never represented as road data. */
final class LocationPreview extends View {
    private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final double latitude, longitude;
    LocationPreview(Context context, double latitude, double longitude) {
        super(context); this.latitude = latitude; this.longitude = longitude;
        setContentDescription("位置坐标预览；不含地图道路数据");
    }
    @Override protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);
        float w = getWidth(), h = getHeight(), density = getResources().getDisplayMetrics().density;
        canvas.drawColor(Color.rgb(231, 241, 233));
        paint.setColor(Color.rgb(204, 222, 208)); paint.setStrokeWidth(density);
        float spacing = 27 * density;
        float xOffset = (float) ((longitude + 180) % 1) * spacing;
        float yOffset = (float) ((90 - latitude) % 1) * spacing;
        for (float x = xOffset; x < w; x += spacing) canvas.drawLine(x, 0, x, h, paint);
        for (float y = yOffset; y < h; y += spacing) canvas.drawLine(0, y, w, y, paint);
        paint.setColor(Color.rgb(7, 166, 96));
        canvas.drawCircle(w / 2, h / 2 - 8 * density, 12 * density, paint);
        android.graphics.Path tip = new android.graphics.Path();
        tip.moveTo(w / 2 - 9 * density, h / 2); tip.lineTo(w / 2, h / 2 + 17 * density);
        tip.lineTo(w / 2 + 9 * density, h / 2); tip.close(); canvas.drawPath(tip, paint);
        paint.setColor(Color.WHITE); canvas.drawCircle(w / 2, h / 2 - 8 * density, 4 * density, paint);
        paint.setColor(Color.rgb(87, 111, 94)); paint.setTextSize(10 * density);
        canvas.drawText("坐标预览 · 点击查看外部地图", 8 * density, h - 9 * density, paint);
    }
}
