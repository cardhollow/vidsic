package com.chxd.vidsicplayer;

import android.animation.ValueAnimator;
import android.content.Context;
import android.content.Intent;
import android.graphics.Bitmap;
import android.graphics.Matrix;
import android.graphics.drawable.AnimatedImageDrawable;
import android.graphics.drawable.BitmapDrawable;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.GradientDrawable;
import android.graphics.ImageDecoder;
import android.os.Handler;
import android.os.Looper;
import android.util.LruCache;
import android.view.Gravity;
import android.view.GestureDetector;
import android.view.MotionEvent;
import android.view.ScaleGestureDetector;
import android.view.VelocityTracker;
import android.view.View;
import android.view.WindowInsets;
import android.view.WindowInsetsController;
import android.view.WindowManager;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.SeekBar;
import android.widget.TextView;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Random;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

class PhotoViewer extends FrameLayout {

    static int sec(Store s) { return Math.max(1, Math.min(30, s.p.getInt("ss_sec", 4))); }
    static int fadeMs(Store s) { return Math.max(0, Math.min(3000, s.p.getInt("ss_fade", 500))); }

    static String fmtFade(int ms) { return ms <= 0 ? "Off" : (ms % 1000 == 0 ? ms / 1000 + " s" : (ms / 1000f) + " s"); }

    static void slideshowSettings(final MainActivity a, final Runnable changed) {
        final Store s = a.store;
        final Ui.Sheet sh = new Ui.Sheet(a, "Slideshow settings", "Used by every slideshow");
        LinearLayout body = new LinearLayout(a);
        body.setOrientation(LinearLayout.VERTICAL);
        body.setPadding(Ui.dp(a, 20), Ui.dp(a, 4), Ui.dp(a, 20), Ui.dp(a, 8));
        sh.box.addView(body, new LinearLayout.LayoutParams(Ui.MATCH, Ui.WRAP));

        body.addView(Ui.text(a, "Time per picture", 14, Ui.SUB, false));
        final TextView secV = Ui.text(a, sec(s) + " s", 26, Ui.ACC, true);
        secV.setGravity(Gravity.CENTER);
        body.addView(secV, new LinearLayout.LayoutParams(Ui.MATCH, Ui.WRAP));
        SeekBar sb = new SeekBar(a);
        sb.setMax(29);
        sb.setProgress(sec(s) - 1);
        Ui.tintSeek(sb);
        sb.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            public void onProgressChanged(SeekBar x, int p, boolean user) {
                secV.setText((p + 1) + " s");
                if (!user) return;
                s.p.edit().putInt("ss_sec", p + 1).apply();
                if (changed != null) changed.run();
            }
            public void onStartTrackingTouch(SeekBar x) { }
            public void onStopTrackingTouch(SeekBar x) { }
        });
        body.addView(sb, new LinearLayout.LayoutParams(Ui.MATCH, Ui.WRAP));

        TextView ft = Ui.text(a, "Fade between pictures", 14, Ui.SUB, false);
        ft.setPadding(0, Ui.dp(a, 16), 0, 0);
        body.addView(ft);
        final TextView fadeV = Ui.text(a, fmtFade(fadeMs(s)), 26, Ui.ACC, true);
        fadeV.setGravity(Gravity.CENTER);
        body.addView(fadeV, new LinearLayout.LayoutParams(Ui.MATCH, Ui.WRAP));
        SeekBar fb = new SeekBar(a);
        fb.setMax(30);
        fb.setProgress(fadeMs(s) / 100);
        Ui.tintSeek(fb);
        fb.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            public void onProgressChanged(SeekBar x, int p, boolean user) {
                fadeV.setText(fmtFade(p * 100));
                if (!user) return;
                s.p.edit().putInt("ss_fade", p * 100).apply();
                if (changed != null) changed.run();
            }
            public void onStartTrackingTouch(SeekBar x) { }
            public void onStopTrackingTouch(SeekBar x) { }
        });
        body.addView(fb, new LinearLayout.LayoutParams(Ui.MATCH, Ui.WRAP));
        LinearLayout ends = new LinearLayout(a);
        ends.addView(Ui.text(a, "Instant", 12, Ui.SUB, false), new LinearLayout.LayoutParams(0, Ui.WRAP, 1));
        ends.addView(Ui.text(a, "3 s", 12, Ui.SUB, false));
        body.addView(ends, new LinearLayout.LayoutParams(Ui.MATCH, Ui.WRAP));

        LinearLayout row = new LinearLayout(a);
        LinearLayout.LayoutParams rp = new LinearLayout.LayoutParams(Ui.MATCH, Ui.WRAP);
        rp.topMargin = Ui.dp(a, 16);
        body.addView(row, rp);
        row.addView(toggleChip(a, "Shuffle", "ss_shuf", false, changed), chipLp(a));
        row.addView(toggleChip(a, "Repeat", "ss_loop", true, changed), chipLp(a));
        sh.show();
    }

    static LinearLayout.LayoutParams chipLp(Context c) {
        LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(0, Ui.WRAP, 1);
        p.setMargins(Ui.dp(c, 3), 0, Ui.dp(c, 3), 0);
        return p;
    }

    static TextView toggleChip(final MainActivity a, final String label, final String key, final boolean def, final Runnable changed) {
        final TextView v = Ui.text(a, label, 14, Ui.TXT, true);
        v.setGravity(Gravity.CENTER);
        v.setPadding(Ui.dp(a, 4), Ui.dp(a, 10), Ui.dp(a, 4), Ui.dp(a, 10));
        final Runnable paint = () -> {
            boolean on = a.store.bool(key, def);
            v.setTextColor(on ? Ui.ONACC : Ui.TXT);
            v.setBackground(Ui.rip(on ? Ui.ACC : Ui.SURF2, 20, a));
        };
        paint.run();
        v.setOnClickListener(x -> {
            a.store.setBool(key, !a.store.bool(key, def));
            paint.run();
            if (changed != null) changed.run();
        });
        return v;
    }

    static final Handler H = new Handler(Looper.getMainLooper());

    static final ExecutorService FULLX = Executors.newSingleThreadExecutor(r -> new Thread(() -> {
        android.os.Process.setThreadPriority(android.os.Process.THREAD_PRIORITY_BACKGROUND);
        r.run();
    }));
    static final LruCache<String, BitmapDrawable> FULL = new LruCache<String, BitmapDrawable>(
            (int) Math.max(16L * 1024 * 1024, Runtime.getRuntime().maxMemory() / 8)) {
        @Override protected int sizeOf(String k, BitmapDrawable d) { return d.getBitmap().getAllocationByteCount(); }
    };

    static Drawable decode(Context c, Photo p, final int maxPx) throws Exception {
        ImageDecoder.Source src = ImageDecoder.createSource(c.getContentResolver(), p.uri);
        return ImageDecoder.decodeDrawable(src, (decoder, info, s) -> {
            int w = info.getSize().getWidth(), h = info.getSize().getHeight(), big = Math.max(w, h);
            if (big > maxPx) {
                float f = maxPx / (float) big;
                decoder.setTargetSize(Math.max(1, Math.round(w * f)), Math.max(1, Math.round(h * f)));
            }
        });
    }

    static class Zoom extends ImageView {
        Photo photo;
        boolean loaded, failed;
        Runnable whenReady;
        final Matrix m = new Matrix();
        float rel = 1f, tx, ty;
        float rot;
        ValueAnimator anim, rotAnim;

        Zoom(Context c) {
            super(c);
            setScaleType(ScaleType.MATRIX);
        }

        int dw() { Drawable d = getDrawable(); return d == null ? 0 : d.getIntrinsicWidth(); }
        int dh() { Drawable d = getDrawable(); return d == null ? 0 : d.getIntrinsicHeight(); }
        boolean zoomed() { return rel > 1.01f; }

        void show(Drawable d) {
            if (anim != null) anim.cancel();
            if (rotAnim != null) rotAnim.cancel();
            setImageDrawable(d);
            rel = 1f;
            rot = 0f;
            apply();
        }

        void snapRotation() {
            if (rotAnim != null) rotAnim.cancel();
            final float to = Math.round(rot / 90f) * 90f;
            if (to == rot) return;
            rotAnim = ValueAnimator.ofFloat(rot, to);
            rotAnim.setDuration(160);
            rotAnim.addUpdateListener(an -> { rot = (float) an.getAnimatedValue(); apply(); });
            rotAnim.start();
        }

        @Override
        protected void onSizeChanged(int w, int h, int ow, int oh) {
            super.onSizeChanged(w, h, ow, oh);
            apply();
        }

        void apply() {
            int W = getWidth(), H = getHeight(), dw = dw(), dh = dh();
            if (W == 0 || H == 0 || dw <= 0 || dh <= 0) return;
            double th = Math.toRadians(rot), cs = Math.abs(Math.cos(th)), sn = Math.abs(Math.sin(th));
            float bw0 = (float) (dw * cs + dh * sn), bh0 = (float) (dw * sn + dh * cs);
            float base = Math.min(W / bw0, H / bh0), s = base * rel, sw = bw0 * s, sh = bh0 * s;
            tx = sw <= W ? (W - sw) / 2f : Math.max(W - sw, Math.min(0, tx));
            ty = sh <= H ? (H - sh) / 2f : Math.max(H - sh, Math.min(0, ty));
            m.setTranslate(-dw / 2f, -dh / 2f);
            m.postScale(s, s);
            m.postRotate(rot);
            m.postTranslate(tx + sw / 2f, ty + sh / 2f);
            setImageMatrix(m);
        }

        void zoomBy(float f, float fx, float fy) {
            float nr = Math.max(1f, Math.min(6f, rel * f)), k = nr / rel;
            tx = fx - (fx - tx) * k;
            ty = fy - (fy - ty) * k;
            rel = nr;
            apply();
        }

        float panBy(float dx, float dy) {
            float ox = tx;
            tx += dx;
            ty += dy;
            apply();
            return tx - ox;
        }

        void toggleZoom(final float fx, final float fy) {
            if (anim != null) anim.cancel();
            final float target = rel > 1.05f ? 1f : 2.5f;
            anim = ValueAnimator.ofFloat(rel, target);
            anim.setDuration(180);
            anim.addUpdateListener(a -> zoomBy(((float) a.getAnimatedValue()) / rel, fx, fy));
            anim.start();
        }
    }

    class Pager extends FrameLayout {
        Pager(Context c) { super(c); }
        @Override public boolean onTouchEvent(MotionEvent e) { return touch(e); }
        @Override protected void onSizeChanged(int w, int h, int ow, int oh) {
            super.onSizeChanged(w, h, ow, oh);
            layoutPages();
        }
    }

    final MainActivity a;
    final Store s;
    final Pager pager;
    final Zoom[] pg = new Zoom[3];
    final LinearLayout top, bottom;
    final TextView title, count, hint;
    final ImageView fav, play, more;
    final GestureDetector gd;
    final ScaleGestureDetector sgd;
    final Random rnd = new Random();
    List<Photo> list = new ArrayList<>(), orig;
    int index;
    float offset;
    boolean chrome = true, playing, fading, touching;
    float lastX, lastY;
    VelocityTracker vt;
    ValueAnimator turn;
    String lastKey = "";
    android.webkit.WebView svg;
    String svgKey = "";
    float svgBase, svgScale;
    GestureDetector svgGd;

    PhotoViewer(MainActivity act) {
        super(act);
        a = act;
        s = act.store;
        setBackgroundColor(0xFF000000);
        setClickable(true);

        pager = new Pager(a);
        for (int i = 0; i < 3; i++) {
            pg[i] = new Zoom(a);
            pager.addView(pg[i], new FrameLayout.LayoutParams(Ui.MATCH, Ui.MATCH));
        }
        addView(pager, new FrameLayout.LayoutParams(Ui.MATCH, Ui.MATCH));
        buildSvg();

        gd = new GestureDetector(a, new GestureDetector.SimpleOnGestureListener() {
            @Override public boolean onDown(MotionEvent e) { return true; }
            @Override public boolean onSingleTapConfirmed(MotionEvent e) { toggleChrome(); return true; }
            @Override public boolean onDoubleTap(MotionEvent e) { pg[1].toggleZoom(e.getX(), e.getY()); return true; }
        });
        sgd = new ScaleGestureDetector(a, new ScaleGestureDetector.SimpleOnScaleGestureListener() {
            @Override public boolean onScale(ScaleGestureDetector d) {
                pg[1].zoomBy(d.getScaleFactor(), d.getFocusX(), d.getFocusY());
                return true;
            }
        });

        top = new LinearLayout(a);
        top.setGravity(Gravity.CENTER_VERTICAL);
        top.setPadding(Ui.dp(a, 4), Ui.dp(a, 8), Ui.dp(a, 4), Ui.dp(a, 20));
        top.setBackground(scrim(GradientDrawable.Orientation.TOP_BOTTOM));
        ImageView x = Ui.btn(a, R.drawable.ic_close, 0xFFFFFFFF, 24, 10);
        x.setOnClickListener(v -> close());
        top.addView(x);
        LinearLayout tc = new LinearLayout(a);
        tc.setOrientation(LinearLayout.VERTICAL);
        tc.setPadding(Ui.dp(a, 6), 0, Ui.dp(a, 6), 0);
        title = Ui.text(a, "", 16, 0xFFFFFFFF, true);
        count = Ui.text(a, "", 12, 0xFFBBBBBB, false);
        tc.addView(title);
        tc.addView(count);
        top.addView(tc, new LinearLayout.LayoutParams(0, Ui.WRAP, 1));
        more = Ui.btn(a, R.drawable.ic_more, 0xFFFFFFFF, 24, 10);
        more.setOnClickListener(v -> menu());
        top.addView(more);
        addView(top, new FrameLayout.LayoutParams(Ui.MATCH, Ui.WRAP, Gravity.TOP));

        bottom = new LinearLayout(a);
        bottom.setGravity(Gravity.CENTER);
        bottom.setPadding(Ui.dp(a, 8), Ui.dp(a, 20), Ui.dp(a, 8), Ui.dp(a, 12));
        bottom.setBackground(scrim(GradientDrawable.Orientation.BOTTOM_TOP));
        fav = bar(R.drawable.ic_heart, v -> { if (cur() != null) { a.pops.toggleFav(Collections.singletonList(cur())); updateChrome(); } });
        ImageView col = bar(R.drawable.ic_collections, v -> { if (cur() != null) a.pops.pickCollection(Collections.singletonList(cur()), null); });
        play = bar(R.drawable.ic_slideshow, v -> { if (playing) stopSlide(); else startSlide(); });
        ImageView ren = bar(R.drawable.ic_edit, v -> {
            Photo c = cur();
            if (c == null) return;
            a.openEditor(c);
        });
        ImageView del = bar(R.drawable.ic_delete, v -> delete());
        for (View b : new View[]{fav, col, play, ren, del}) {
            bottom.addView(b, new LinearLayout.LayoutParams(0, Ui.dp(a, 48), 1));
        }
        addView(bottom, new FrameLayout.LayoutParams(Ui.MATCH, Ui.WRAP, Gravity.BOTTOM));

        hint = Ui.text(a, "", 15, 0xFFFFFFFF, true);
        hint.setGravity(Gravity.CENTER);
        hint.setPadding(Ui.dp(a, 16), Ui.dp(a, 8), Ui.dp(a, 16), Ui.dp(a, 8));
        hint.setBackground(Ui.shape(0xCC000000, 20, a));
        hint.setAlpha(0f);
        hint.setVisibility(View.INVISIBLE);
        FrameLayout.LayoutParams hp = new FrameLayout.LayoutParams(Ui.WRAP, Ui.WRAP, Gravity.CENTER_HORIZONTAL | Gravity.BOTTOM);
        hp.bottomMargin = Ui.dp(a, 96);
        addView(hint, hp);
    }

    @android.annotation.SuppressLint("SetJavaScriptEnabled")
    void buildSvg() {
        try {
            svg = new android.webkit.WebView(a);
        } catch (Throwable t) { svg = null; return; }
        svg.setBackgroundColor(0xFFFFFFFF);
        android.webkit.WebSettings ws = svg.getSettings();
        ws.setJavaScriptEnabled(false);
        ws.setAllowFileAccess(false);
        ws.setAllowContentAccess(false);
        ws.setBlockNetworkLoads(true);
        ws.setSupportZoom(true);
        ws.setBuiltInZoomControls(true);
        ws.setDisplayZoomControls(false);
        ws.setUseWideViewPort(true);
        ws.setLoadWithOverviewMode(true);
        svg.setHorizontalScrollBarEnabled(false);
        svg.setVerticalScrollBarEnabled(false);
        svg.setVisibility(View.GONE);
        svg.setWebViewClient(new android.webkit.WebViewClient() {
            @SuppressWarnings("deprecation")
            @Override public void onPageFinished(android.webkit.WebView v, String url) { svgBase = svgScale = v.getScale(); }
            @Override public void onScaleChanged(android.webkit.WebView v, float o, float n) { svgScale = n; }
        });
        svgGd = new GestureDetector(a, new GestureDetector.SimpleOnGestureListener() {
            @Override public boolean onSingleTapConfirmed(MotionEvent e) { toggleChrome(); return true; }
            @Override public boolean onFling(MotionEvent e1, MotionEvent e2, float vx, float vy) {
                if (e1 == null || fading) return false;
                boolean unzoomed = svgBase <= 0 || svgScale <= svgBase * 1.05f;
                if (unzoomed && Math.abs(vx) > 900 && Math.abs(vx) > Math.abs(vy) * 2) {
                    int dir = vx < 0 ? 1 : -1;
                    if (index + dir < 0 || index + dir >= list.size()) return false;
                    move(dir);
                    if (cur() != null) s.addPhotoHistory(cur());
                    schedule();
                    return true;
                }
                return false;
            }
        });
        svg.setOnTouchListener((v, e) -> { svgGd.onTouchEvent(e); return false; });
        addView(svg, new FrameLayout.LayoutParams(Ui.MATCH, Ui.MATCH));
    }

    void syncSvg() {
        if (svg == null) return;
        final Photo p = cur();
        if (p == null || !p.svg) {
            if (svg.getVisibility() != View.GONE) { svg.setVisibility(View.GONE); svg.loadUrl("about:blank"); }
            svgKey = "";
            return;
        }
        svg.setVisibility(View.VISIBLE);
        if (p.key().equals(svgKey)) return;
        svgKey = p.key();
        svg.loadUrl("about:blank");
        FULLX.execute(() -> {
            String html = null;
            try (java.io.InputStream in = a.getContentResolver().openInputStream(p.uri)) {
                java.io.ByteArrayOutputStream bo = new java.io.ByteArrayOutputStream();
                byte[] buf = new byte[16384];
                int n, total = 0;
                while ((n = in.read(buf)) > 0 && total < 8 * 1024 * 1024) { bo.write(buf, 0, n); total += n; }
                String b64 = android.util.Base64.encodeToString(bo.toByteArray(), android.util.Base64.NO_WRAP);
                html = "<html><head><meta name='viewport' content='width=device-width, initial-scale=1, minimum-scale=1, maximum-scale=10, user-scalable=yes'>"
                        + "<style>html,body{margin:0;height:100%;background:#fff}"
                        + "img{position:absolute;left:0;top:0;width:100%;height:100%;object-fit:contain}</style></head>"
                        + "<body><img src='data:image/svg+xml;base64," + b64 + "'></body></html>";
            } catch (Throwable t) { }
            final String h = html;
            H.post(() -> {
                if (!p.key().equals(svgKey) || svg == null) return;
                if (h == null) { flash("Could not open this drawing"); return; }
                svg.loadDataWithBaseURL("about:blank", h, "text/html", "UTF-8", null);
            });
        });
    }

    ImageView bar(int icon, View.OnClickListener c) {
        ImageView v = Ui.btn(a, icon, 0xFFFFFFFF, 24, 12);
        v.setOnClickListener(c);
        return v;
    }

    GradientDrawable scrim(GradientDrawable.Orientation o) {
        return new GradientDrawable(o, new int[]{0xCC000000, 0x00000000});
    }

    Photo cur() { return index >= 0 && index < list.size() ? list.get(index) : null; }

    void open(List<Photo> l, int idx, boolean slideshow, int shuffle) {
        stopSlide();
        if (l.isEmpty()) return;
        list = new ArrayList<>(l);
        orig = null;
        boolean shuf = slideshow && (shuffle == 1 || (shuffle < 0 && s.bool("ss_shuf", false)));
        index = Math.max(0, Math.min(list.size() - 1, idx));
        if (shuf) shuffleList(idx >= 0 ? list.get(index) : null);
        offset = 0;
        chrome = true;
        for (Zoom z : pg) { z.photo = null; z.loaded = false; z.show(null); }
        setVisibility(View.VISIBLE);
        setAlpha(1f);
        immersive(true);
        bindAll();
        if (!slideshow && cur() != null) s.addPhotoHistory(cur());
        applyChrome(false);
        updateChrome();
        if (slideshow) startSlide();
    }

    void close() {
        stopSlide();
        if (turn != null) turn.cancel();
        setVisibility(View.GONE);
        if (svg != null) { svg.setVisibility(View.GONE); svg.loadUrl("about:blank"); svgKey = ""; }
        immersive(false);
        for (Zoom z : pg) { z.photo = null; z.loaded = false; z.show(null); }
        FULL.evictAll();
        a.onViewerClosed();
    }

    void onBack() {
        if (playing) { stopSlide(); applyChrome(true); return; }
        close();
    }

    void immersive(boolean on) {
        try {
            WindowInsetsController c = a.getWindow().getInsetsController();
            if (c == null) return;
            if (on) {
                c.setSystemBarsBehavior(WindowInsetsController.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE);
                c.hide(WindowInsets.Type.systemBars());
            } else c.show(WindowInsets.Type.systemBars());
        } catch (Exception e) { }
    }

    boolean keepAwake() { return getVisibility() == View.VISIBLE && playing; }

    int maxPx() {
        android.util.DisplayMetrics dm = a.getResources().getDisplayMetrics();
        return Math.min(3072, Math.round(Math.max(dm.widthPixels, dm.heightPixels) * 1.5f));
    }

    void layoutPages() {
        int W = pager.getWidth();
        for (int i = 0; i < 3; i++) {
            pg[i].setTranslationX(offset + (i - 1) * W);
            pg[i].setAlpha(1f);
        }
    }

    void bindAll() {
        bind(pg[1], cur());
        bind(pg[2], index + 1 < list.size() ? list.get(index + 1) : null);
        bind(pg[0], index > 0 ? list.get(index - 1) : null);
        layoutPages();
        syncSvg();
    }

    void bind(final Zoom z, final Photo p) {
        if (z.photo == p && (p == null || z.loaded || z.failed)) return;
        z.photo = p;
        z.loaded = false;
        z.failed = false;
        if (p == null) { z.show(null); return; }
        if (p.svg) { z.show(null); z.loaded = true; ready(z); return; }
        BitmapDrawable d = FULL.get(p.key());
        if (d != null) { z.show(d); z.loaded = true; ready(z); return; }

        Bitmap t = Ui.PCACHE.get(p.key() + "@" + Ui.dp(a, 120));
        z.show(t == null ? null : new BitmapDrawable(a.getResources(), t));
        final int px = maxPx();
        FULLX.execute(() -> {
            if (z.photo != p) return;
            Drawable dd = null;
            try { dd = decode(a, p, px); } catch (Throwable e) { }
            final Drawable res = dd;
            H.post(() -> {
                if (z.photo != p) return;
                if (res == null) { z.failed = true; z.show(null); }
                else {
                    if (res instanceof BitmapDrawable) FULL.put(p.key(), (BitmapDrawable) res);
                    z.show(res);
                    z.loaded = true;
                    if (res instanceof AnimatedImageDrawable) ((AnimatedImageDrawable) res).start();
                }
                ready(z);
            });
        });
    }

    void reload() {
        for (Zoom z : pg) { z.photo = null; z.loaded = false; }
        bindAll();
        updateChrome();
    }

    void ready(Zoom z) {
        Runnable r = z.whenReady;
        z.whenReady = null;
        if (r != null) r.run();
    }

    boolean touch(MotionEvent e) {
        if (fading) { gd.onTouchEvent(e); return true; }
        sgd.onTouchEvent(e);
        gd.onTouchEvent(e);
        if (vt == null) vt = VelocityTracker.obtain();
        vt.addMovement(e);
        switch (e.getActionMasked()) {
            case MotionEvent.ACTION_DOWN:
                if (turn != null) { turn.cancel(); }
                touching = true;
                lastX = e.getX();
                lastY = e.getY();
                h().removeCallbacks(tick);
                break;
            case MotionEvent.ACTION_POINTER_DOWN:
            case MotionEvent.ACTION_POINTER_UP: {
                if (e.getActionMasked() == MotionEvent.ACTION_POINTER_DOWN && e.getPointerCount() == 2) {
                    rotStart = angle(e);
                    rotBase = pg[1].rot;
                    rotating = false;
                } else if (e.getActionMasked() == MotionEvent.ACTION_POINTER_UP && e.getPointerCount() == 2) {
                    pg[1].snapRotation();
                    rotating = false;
                }
                int skip = e.getActionMasked() == MotionEvent.ACTION_POINTER_UP ? e.getActionIndex() : -1, n = e.getPointerCount(), k = 0;
                float sx = 0, sy = 0;
                for (int i = 0; i < n; i++) if (i != skip) { sx += e.getX(i); sy += e.getY(i); k++; }
                if (k > 0) { lastX = sx / k; lastY = sy / k; }
                break;
            }
            case MotionEvent.ACTION_MOVE: {
                if (sgd.isInProgress() || e.getPointerCount() > 1) {
                    if (e.getPointerCount() == 2) {
                        float d = norm(angle(e) - rotStart);
                        if (!rotating && Math.abs(d) > 8f) rotating = true;
                        if (rotating) {
                            if (pg[1].rotAnim != null) pg[1].rotAnim.cancel();
                            pg[1].rot = rotBase + d;
                            pg[1].apply();
                        }
                    }
                    lastX = e.getX();
                    lastY = e.getY();
                    break;
                }
                float dx = e.getX() - lastX, dy = e.getY() - lastY;
                lastX = e.getX();
                lastY = e.getY();
                drag(dx, dy);
                break;
            }
            case MotionEvent.ACTION_UP:
            case MotionEvent.ACTION_CANCEL: {
                touching = false;
                if (rotating || pg[1].rot % 90f != 0f) pg[1].snapRotation();
                rotating = false;
                vt.computeCurrentVelocity(1000);
                float vx = vt.getXVelocity();
                vt.recycle();
                vt = null;
                release(vx, e.getActionMasked() == MotionEvent.ACTION_CANCEL);
                break;
            }
            default:
                break;
        }
        return true;
    }

    float rotStart, rotBase;
    boolean rotating;

    static float angle(MotionEvent e) {
        return (float) Math.toDegrees(Math.atan2(e.getY(1) - e.getY(0), e.getX(1) - e.getX(0)));
    }

    static float norm(float d) {
        while (d > 180f) d -= 360f;
        while (d < -180f) d += 360f;
        return d;
    }

    Handler h() { return H; }

    void drag(float dx, float dy) {
        Zoom z = pg[1];
        int W = pager.getWidth();
        if (offset == 0 && z.zoomed()) {
            float used = z.panBy(dx, dy);
            dx -= used;
            if (dx == 0) return;
        } else if (offset != 0 && z.zoomed() && (offset + dx) * offset < 0) {
            offset = 0;
            layoutPages();
            return;
        }
        boolean atStart = index == 0 && offset + dx > 0, atEnd = index == list.size() - 1 && offset + dx < 0;
        offset += (atStart || atEnd) ? dx * 0.35f : dx;
        offset = Math.max(-W, Math.min(W, offset));
        layoutPages();
    }

    void release(float vx, boolean cancelled) {
        int W = Math.max(1, pager.getWidth());
        int dir = 0;
        if (!cancelled) {
            if (offset < -W / 5f || (vx < -900 && offset < 0)) dir = 1;
            else if (offset > W / 5f || (vx > 900 && offset > 0)) dir = -1;
        }
        if (dir == 1 && index >= list.size() - 1) dir = 0;
        if (dir == -1 && index <= 0) dir = 0;
        animateTurn(dir, W);
    }

    void animateTurn(final int dir, final int W) {
        final float to = dir == 0 ? 0 : -dir * W;
        if (turn != null) turn.cancel();
        turn = ValueAnimator.ofFloat(offset, to);
        turn.setDuration(Math.max(80, Math.min(240, Math.round(Math.abs(to - offset) / Math.max(1, W) * 260))));
        turn.addUpdateListener(an -> { offset = (float) an.getAnimatedValue(); layoutPages(); });
        turn.addListener(new android.animation.AnimatorListenerAdapter() {
            boolean cancelled;
            @Override public void onAnimationCancel(android.animation.Animator an) { cancelled = true; }
            @Override public void onAnimationEnd(android.animation.Animator an) {
                if (cancelled) return;
                if (dir != 0) {
                    move(dir);
                    if (cur() != null) s.addPhotoHistory(cur());
                }
                offset = 0;
                layoutPages();
                schedule();
            }
        });
        turn.start();
    }

    void move(int dir) {
        if (dir > 0) { Zoom t = pg[0]; pg[0] = pg[1]; pg[1] = pg[2]; pg[2] = t; }
        else { Zoom t = pg[2]; pg[2] = pg[1]; pg[1] = pg[0]; pg[0] = t; }
        index += dir;
        bindAll();
        updateChrome();
    }

    void toggleChrome() {
        applyChrome(!chrome);
    }

    final Runnable hideBars = () -> { if (playing && chrome) applyChrome(false); };

    int bgNow = 0xFF000000;
    android.animation.ValueAnimator bgAnim;

    void applyChrome(boolean show) {
        chrome = show;
        // slightly lighter backdrop while the controls are hidden, so transparent pictures are easy to spot
        final int from = bgNow, to = show ? 0xFF000000 : 0xFF2C2C2C;
        if (bgAnim != null) bgAnim.cancel();
        bgAnim = android.animation.ValueAnimator.ofObject(new android.animation.ArgbEvaluator(), from, to);
        bgAnim.setDuration(180);
        bgAnim.addUpdateListener(an -> { bgNow = (Integer) an.getAnimatedValue(); setBackgroundColor(bgNow); });
        bgAnim.start();
        top.animate().cancel();
        bottom.animate().cancel();
        top.setVisibility(View.VISIBLE);
        bottom.setVisibility(View.VISIBLE);
        top.animate().alpha(show ? 1f : 0f).setDuration(180).start();
        bottom.animate().alpha(show ? 1f : 0f).setDuration(180).start();
        top.setEnabled(show);
        for (int i = 0; i < bottom.getChildCount(); i++) bottom.getChildAt(i).setEnabled(show);
        H.removeCallbacks(hideBars);
        if (show && playing) H.postDelayed(hideBars, 3000);
    }

    void updateChrome() {
        Photo p = cur();
        title.setText(p == null ? "" : p.name);
        count.setText(p == null ? "" : (index + 1) + " / " + list.size() + (orig != null ? "   shuffled" : ""));
        boolean f = p != null && s.isPhotoFav(p);
        Ic.set(fav, f ? R.drawable.ic_heart_fill : R.drawable.ic_heart);
        fav.setColorFilter(f ? Ui.ACC : 0xFFFFFFFF);
        Ic.set(play, playing ? R.drawable.ic_pause : R.drawable.ic_slideshow);
        play.setColorFilter(playing ? Ui.ACC : 0xFFFFFFFF);
    }

    void flash(String t) {
        hint.setText(t);
        hint.animate().cancel();
        hint.setVisibility(View.VISIBLE);
        hint.setAlpha(1f);
        hint.animate().alpha(0f).setStartDelay(1200).setDuration(300).withEndAction(() -> hint.setVisibility(View.INVISIBLE)).start();
    }

    void menu() {
        final Photo p = cur();
        if (p == null) return;
        Ui.Sheet sh = new Ui.Sheet(a, p.name, p.folder);
        sh.item(playing ? R.drawable.ic_pause : R.drawable.ic_slideshow, playing ? "Pause slideshow" : "Start slideshow", Ui.TXT,
                () -> { if (playing) stopSlide(); else startSlide(); });
        sh.item(R.drawable.ic_shuffle, orig != null ? "Shuffle: on (tap to turn off)" : "Shuffle: off (tap to turn on)", Ui.TXT, () -> setShuffle(orig == null));
        sh.item(R.drawable.ic_settings, "Slideshow settings", Ui.TXT, () -> slideshowSettings(a, () -> { if (playing) schedule(); }));
        sh.item(R.drawable.ic_edit, p.svg || p.gif ? "Edit as PNG" : "Edit picture", Ui.TXT, () -> a.openEditor(p));
        sh.item(R.drawable.ic_edit, "Rename", Ui.TXT, this::rename);
        if (!p.svg) sh.item(R.drawable.ic_image, "Add as widget", Ui.TXT, () -> HomeAdd.pinPicture(a, p));
        sh.item(R.drawable.ic_info, "Info", Ui.TXT, () -> a.pops.info(p));
        sh.item(R.drawable.ic_add_queue, "Share", Ui.TXT, () -> {
            try {
                Intent i = new Intent(Intent.ACTION_SEND).setType("image/*").putExtra(Intent.EXTRA_STREAM, p.uri)
                        .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
                a.startActivity(Intent.createChooser(i, "Share picture"));
            } catch (Exception e) { a.toast("Can't share this picture"); }
        });
        sh.show();
    }

    void rename() {
        final Photo p = cur();
        if (p == null) return;
        a.promptName("Rename", p.title, n -> a.pops.rename(p, n, this::updateChrome));
    }

    void delete() {
        final Photo p = cur();
        if (p == null) return;
        boolean was = playing;
        stopSlide();
        a.pops.confirmDelete(Collections.singletonList(p), () -> removed(p));
        if (was) flash("Slideshow paused");
    }

    void removed(Photo p) {
        int i = list.indexOf(p);
        if (i < 0) return;
        list.remove(i);
        if (orig != null) orig.remove(p);
        if (list.isEmpty()) { close(); return; }
        if (i < index) index--;
        if (index >= list.size()) index = list.size() - 1;
        for (Zoom z : pg) { z.photo = null; z.loaded = false; }
        bindAll();
        updateChrome();
    }

    void shuffleList(Photo first) {
        orig = list;
        List<Photo> l = new ArrayList<>(list);
        Collections.shuffle(l, rnd);
        if (first != null && l.remove(first)) l.add(0, first);
        list = l;
        index = 0;
    }

    void setShuffle(boolean on) {
        Photo p = cur();
        if (on && orig == null) shuffleList(p);
        else if (!on && orig != null) {
            list = orig;
            orig = null;
            index = Math.max(0, list.indexOf(p));
        }
        s.setBool("ss_shuf", on);
        for (Zoom z : pg) { z.photo = null; z.loaded = false; }
        bindAll();
        updateChrome();
        flash(on ? "Shuffled" : "In order");
    }

    final Runnable tick = () -> advance();

    void startSlide() {
        if (list.size() < 2) { flash("Needs at least two pictures"); return; }
        playing = true;
        try { a.getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON); } catch (Exception e) { }
        applyChrome(false);
        updateChrome();
        schedule();
    }

    void stopSlide() {
        if (!playing) return;
        playing = false;
        H.removeCallbacks(tick);
        H.removeCallbacks(hideBars);
        a.player.updateKeepOn();
        updateChrome();
    }

    void pauseForBackground() {
        if (playing) { stopSlide(); }
    }

    void schedule() {
        H.removeCallbacks(tick);
        if (!playing || touching || fading || pg[1].zoomed() || getVisibility() != View.VISIBLE) return;
        H.postDelayed(tick, sec(s) * 1000L);
    }

    void advance() {
        if (!playing || fading) return;
        if (touching || pg[1].zoomed()) { schedule(); return; }
        int n = index + 1;
        if (n >= list.size()) {
            if (!s.bool("ss_loop", true)) { stopSlide(); applyChrome(true); flash("Slideshow finished"); return; }
            n = 0;
        }
        fadeTo(n);
    }

    void fadeTo(final int target) {
        final Photo p = list.get(target);
        final Zoom nz = pg[2];
        fading = true;
        final Runnable go = () -> {
            if (!playing || nz.photo != p) { fading = false; schedule(); return; }
            nz.setTranslationX(0);
            nz.setAlpha(0f);
            nz.bringToFront();
            final Runnable end = () -> {

                Zoom old = pg[1];
                pg[1] = nz;
                pg[2] = pg[0];
                pg[0] = old;
                index = target;
                fading = false;
                nz.setAlpha(1f);
                bindAll();
                updateChrome();
                schedule();
            };
            int ms = fadeMs(s);
            if (ms <= 0) end.run();
            else nz.animate().alpha(1f).setDuration(ms).withEndAction(end).start();
        };
        if (nz.photo != p) bind(nz, p);
        if (nz.loaded || nz.failed) go.run();
        else nz.whenReady = go;
    }
}
