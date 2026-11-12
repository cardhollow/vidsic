package com.chxd.vidsicplayer;

import android.appwidget.AppWidgetManager;
import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Canvas;
import android.graphics.drawable.Drawable;
import android.os.Handler;
import android.os.Looper;
import java.io.File;
import java.io.FileOutputStream;
import java.util.HashMap;
import java.util.Map;
import java.util.WeakHashMap;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

final class WidgetCovers {
    interface Source { Bitmap load() throws Exception; }

    private static final long RETRY_AFTER_MS = 10 * 60 * 1000L;
    private static final long REFRESH_DELAY_MS = 1200L;

    private static final ExecutorService WORKER = Executors.newSingleThreadExecutor();
    private static final Set<String> QUEUED = ConcurrentHashMap.newKeySet();
    private static final Map<Integer, Runnable> REFRESHERS = new ConcurrentHashMap<>();
    private static final Map<String, Bitmap> GLYPHS = new HashMap<>();
    private static final WeakHashMap<Bitmap, Map<String, Bitmap>> ROUNDED = new WeakHashMap<>();
    private static final Handler MAIN = new Handler(Looper.getMainLooper());

    private WidgetCovers() { }

    static void clearGlyphs() { synchronized (GLYPHS) { GLYPHS.clear(); } }

    static Bitmap glyph(Context c, int res, int color, int px, boolean padded) {
        String key = res + ":" + color + ":" + px + ":" + padded;
        synchronized (GLYPHS) {
            Bitmap cached = GLYPHS.get(key);
            if (cached != null) return cached;
            Bitmap out = Bitmap.createBitmap(px, px, Bitmap.Config.ARGB_8888);
            Drawable d = Ic.get(c, res);
            if (d != null) {
                d = d.mutate();
                d.setTint(color);
                int inset = padded ? px / 4 : 0;
                d.setBounds(inset, inset, px - inset, px - inset);
                d.draw(new Canvas(out));
            }
            GLYPHS.put(key, out);
            return out;
        }
    }

    static Bitmap cached(Context c, String key) {
        File f = file(c, key, ".jpg");
        return f.isFile() ? BitmapFactory.decodeFile(f.getPath()) : null;
    }

    /** Creates a cached rounded bitmap. Use this only when the ImageView will not crop it afterward. */
    static Bitmap rounded(Bitmap source, float cornerFraction) {
        if (source == null || source.isRecycled()) return source;
        int q = Math.max(1, Math.min(450, Math.round(cornerFraction * 1000f)));
        String cacheKey = "rounded:" + q;
        Bitmap cached = getRounded(source, cacheKey);
        if (cached != null) return cached;
        Bitmap out = null;
        try {
            int w = source.getWidth(), h = source.getHeight();
            if (w <= 0 || h <= 0) return source;
            out = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888);
            Canvas canvas = new Canvas(out);
            android.graphics.Paint paint = new android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG
                    | android.graphics.Paint.FILTER_BITMAP_FLAG);
            paint.setShader(new android.graphics.BitmapShader(source, android.graphics.Shader.TileMode.CLAMP,
                    android.graphics.Shader.TileMode.CLAMP));
            float radius = Math.min(w, h) * (q / 1000f);
            canvas.drawRoundRect(0, 0, w, h, radius, radius, paint);
        } catch (Throwable e) {
            if (out != null && !out.isRecycled()) out.recycle();
            return source;
        }
        putRounded(source, cacheKey, out);
        return out;
    }

    /** Center-crops first and rounds the final display-sized bitmap, so ImageView scaling cannot cut the corners off. */
    static Bitmap roundedCrop(Bitmap source, int width, int height, float radiusPx) {
        if (source == null || source.isRecycled()) return source;
        width = Math.max(1, width);
        height = Math.max(1, height);
        float radius = Math.max(0f, Math.min(radiusPx, Math.min(width, height) / 2f));
        String cacheKey = "crop:" + width + ":" + height + ":" + Math.round(radius * 10f);
        Bitmap cached = getRounded(source, cacheKey);
        if (cached != null) return cached;
        Bitmap crop = null, out = null;
        try {
            int sw = source.getWidth(), sh = source.getHeight();
            if (sw <= 0 || sh <= 0) return source;
            crop = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888);
            Canvas cropCanvas = new Canvas(crop);
            android.graphics.Paint cropPaint = new android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG
                    | android.graphics.Paint.FILTER_BITMAP_FLAG);
            float scale = Math.max(width / (float) sw, height / (float) sh);
            float drawnW = sw * scale, drawnH = sh * scale;
            float left = (width - drawnW) / 2f, top = (height - drawnH) / 2f;
            cropCanvas.drawBitmap(source, null,
                    new android.graphics.RectF(left, top, left + drawnW, top + drawnH), cropPaint);

            // Render the mask with an anti-aliased round-rect rather than clipping the ImageView later.
            out = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888);
            Canvas roundCanvas = new Canvas(out);
            android.graphics.Paint roundPaint = new android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG
                    | android.graphics.Paint.FILTER_BITMAP_FLAG);
            roundPaint.setShader(new android.graphics.BitmapShader(crop,
                    android.graphics.Shader.TileMode.CLAMP, android.graphics.Shader.TileMode.CLAMP));
            roundCanvas.drawRoundRect(0, 0, width, height, radius, radius, roundPaint);
        } catch (Throwable e) {
            if (out != null && !out.isRecycled()) out.recycle();
            return source;
        } finally {
            if (crop != null && !crop.isRecycled()) crop.recycle();
        }
        putRounded(source, cacheKey, out);
        return out;
    }

    private static Bitmap getRounded(Bitmap source, String key) {
        synchronized (ROUNDED) {
            Map<String, Bitmap> map = ROUNDED.get(source);
            Bitmap b = map == null ? null : map.get(key);
            return b != null && !b.isRecycled() ? b : null;
        }
    }

    private static void putRounded(Bitmap source, String key, Bitmap result) {
        synchronized (ROUNDED) {
            Map<String, Bitmap> map = ROUNDED.get(source);
            if (map == null) {
                map = new HashMap<>();
                ROUNDED.put(source, map);
            }
            map.put(key, result);
        }
    }

    static void request(final Context c, final int widgetId, final String key, final Source source) {
        final Context app = c.getApplicationContext();
        if (file(app, key, ".jpg").isFile() || recentlyFailed(app, key)) return;
        if (!QUEUED.add(key)) return;
        WORKER.execute(() -> {
            boolean written = store(app, key, source);
            QUEUED.remove(key);
            if (written) scheduleRefresh(app, widgetId);
        });
    }

    private static boolean store(Context c, String key, Source source) {
        try {
            Bitmap b = source.load();
            if (b == null) throw new IllegalStateException("no cover");
            try (FileOutputStream os = new FileOutputStream(file(c, key, ".jpg"))) {
                b.compress(Bitmap.CompressFormat.JPEG, 85, os);
            }
            return true;
        } catch (Throwable e) {
            try { file(c, key, ".no").createNewFile(); } catch (Exception ignored) { }
            file(c, key, ".no").setLastModified(System.currentTimeMillis());
            return false;
        }
    }

    private static boolean recentlyFailed(Context c, String key) {
        File f = file(c, key, ".no");
        if (!f.isFile()) return false;
        if (System.currentTimeMillis() - f.lastModified() < RETRY_AFTER_MS) return true;
        f.delete();
        return false;
    }

    private static void scheduleRefresh(final Context c, int widgetId) {
        Runnable r = REFRESHERS.computeIfAbsent(widgetId, id -> () -> {
            try { AppWidgetManager.getInstance(c).notifyAppWidgetViewDataChanged(id, R.id.w_list); } catch (Exception ignored) { }
        });
        MAIN.removeCallbacks(r);
        MAIN.postDelayed(r, REFRESH_DELAY_MS);
    }

    private static File file(Context c, String key, String ext) {
        File dir = new File(c.getCacheDir(), "widget_covers");
        if (!dir.isDirectory()) dir.mkdirs();
        return new File(dir, key.replaceAll("[^A-Za-z0-9]", "_") + ext);
    }
}
