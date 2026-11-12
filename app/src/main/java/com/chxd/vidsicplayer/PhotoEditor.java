package com.chxd.vidsicplayer;

import android.app.Activity;
import android.app.PendingIntent;
import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.ColorMatrix;
import android.graphics.ColorMatrixColorFilter;
import android.graphics.ImageDecoder;
import android.graphics.Matrix;
import android.graphics.Paint;
import android.graphics.Typeface;
import android.graphics.RectF;
import android.net.Uri;
import android.provider.MediaStore;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.SeekBar;
import android.widget.TextView;
import java.io.OutputStream;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Locale;

final class PhotoEditor extends FrameLayout {
    static final int REQ_WRITE = 86, REQ_FONT = 87;
    static final int T_NONE = 0, T_CROP = 1, T_ROTATE = 2, T_FLIP = 3, T_ADJUST = 4, T_FILTER = 5, T_TEXT = 6, T_DRAW = 7, T_CHROMA = 8;
    static final int[] PALETTE = {0xFFFFFFFF, 0xFF000000, 0xFFFF5C5C, 0xFFF4D35E, 0xFF4CD964, 0xFF4DA3FF, 0xFF8B6BFF, 0xFFFF8AD8};

    final MainActivity a;
    final EditView ev;
    final LinearLayout opts;
    final TextView[] tools = new TextView[8];
    final TextView undoBtn, title;
    final TextView loading;
    Photo photo;
    Bitmap bmp;
    final ArrayList<Bitmap> undo = new ArrayList<>();
    int tool = T_NONE;
    boolean changed;

    int adjB, adjC, adjS;
    boolean usesAlpha;
    float rotDeg;
    TextView degLabel;
    android.webkit.WebView svgWv;
    Runnable fontDone;

    Bitmap pendingBmp;
    Photo pendingPhoto;

    PhotoEditor(MainActivity act) {
        super(act);
        a = act;
        setBackgroundColor(0xFF000000);
        setClickable(true);

        ev = new EditView(a);
        addView(ev, new LayoutParams(Ui.MATCH, Ui.MATCH));

        LinearLayout top = new LinearLayout(a);
        top.setGravity(Gravity.CENTER_VERTICAL);
        top.setBackgroundColor(0xE6000000);
        top.setPadding(Ui.dp(a, 4), Ui.dp(a, 6), Ui.dp(a, 8), Ui.dp(a, 6));
        android.widget.ImageView x = Ui.btn(a, R.drawable.ic_close, 0xFFFFFFFF, 24, 10);
        x.setOnClickListener(v -> onBack());
        top.addView(x);
        title = Ui.text(a, "Edit picture", 17, 0xFFFFFFFF, true);
        title.setPadding(Ui.dp(a, 6), 0, 0, 0);
        top.addView(title, new LinearLayout.LayoutParams(0, Ui.WRAP, 1));
        undoBtn = pill("Undo", 0xFFFFFFFF, 0x33FFFFFF);
        undoBtn.setOnClickListener(v -> undoStep());
        top.addView(undoBtn);
        TextView save = pill("Save", Ui.ONACC, Ui.ACC);
        save.setOnClickListener(v -> askSave());
        LinearLayout.LayoutParams sp = new LinearLayout.LayoutParams(Ui.WRAP, Ui.WRAP);
        sp.leftMargin = Ui.dp(a, 8);
        top.addView(save, sp);
        addView(top, new LayoutParams(Ui.MATCH, Ui.WRAP, Gravity.TOP));

        LinearLayout bottom = new LinearLayout(a);
        bottom.setOrientation(LinearLayout.VERTICAL);
        bottom.setBackgroundColor(0xE6000000);
        opts = new LinearLayout(a);
        opts.setOrientation(LinearLayout.VERTICAL);
        bottom.addView(opts, new LinearLayout.LayoutParams(Ui.MATCH, Ui.WRAP));
        android.widget.HorizontalScrollView tsv = new android.widget.HorizontalScrollView(a);
        tsv.setHorizontalScrollBarEnabled(false);
        LinearLayout tr = new LinearLayout(a);
        tsv.addView(tr);
        String[] names = {"Crop", "Rotate", "Flip", "Adjust", "Filters", "Text", "Draw", "Chroma"};
        for (int i = 0; i < 8; i++) {
            final int t = i + 1;
            tools[i] = Ui.text(a, names[i], 15, 0xFFFFFFFF, true);
            tools[i].setGravity(Gravity.CENTER);
            tools[i].setPadding(0, Ui.dp(a, 14), 0, Ui.dp(a, 14));
            tools[i].setBackground(Ui.rip(0, 0, a));
            tools[i].setOnClickListener(v -> pickTool(tool == t ? T_NONE : t));
            tr.addView(tools[i], new LinearLayout.LayoutParams(Ui.dp(a, 76), Ui.WRAP));
        }
        bottom.addView(tsv, new LinearLayout.LayoutParams(Ui.MATCH, Ui.WRAP));
        addView(bottom, new LayoutParams(Ui.MATCH, Ui.WRAP, Gravity.BOTTOM));
        bottom.addOnLayoutChangeListener((v, l, t, r, b, ol, ot, orr, ob) -> ev.setBottomPx(b - t));

        loading = Ui.text(a, "Loading...", 16, 0xFFFFFFFF, true);
        loading.setGravity(Gravity.CENTER);
        addView(loading, new LayoutParams(Ui.MATCH, Ui.MATCH));
        setVisibility(View.GONE);
    }

    TextView pill(String t, int fg, int bg) {
        TextView v = Ui.text(a, t, 14, fg, true);
        v.setGravity(Gravity.CENTER);
        v.setPadding(Ui.dp(a, 16), Ui.dp(a, 8), Ui.dp(a, 16), Ui.dp(a, 8));
        v.setBackground(Ui.rip(bg, 20, a));
        return v;
    }

    void open(final Photo p) {
        if (p == null) return;
        photo = p;
        usesAlpha = p.svg || p.gif;
        ev.checker = true;
        rotDeg = 0;
        bmp = null;
        undo.clear();
        changed = false;
        adjB = adjC = adjS = 0;
        tool = T_NONE;
        ev.setBitmap(null);
        loading.setVisibility(View.VISIBLE);
        setVisibility(View.VISIBLE);
        pickTool(T_NONE);
        updateTop();
        if (p.svg) loadSvg(p); else FULL_LOAD(p);
    }

    /** Rasterise an SVG with a transparent background so it can be edited as a picture. */
    private void loadSvg(final Photo p) {
        PhotoViewer.FULLX.execute(() -> {
            byte[] data = null;
            int w = 1024, h = 1024;
            try (java.io.InputStream in = a.getContentResolver().openInputStream(p.uri)) {
                java.io.ByteArrayOutputStream bo = new java.io.ByteArrayOutputStream();
                byte[] buf = new byte[16384];
                int n, total = 0;
                while ((n = in.read(buf)) > 0 && total < 8 * 1024 * 1024) { bo.write(buf, 0, n); total += n; }
                data = bo.toByteArray();
                String head = new String(data, 0, Math.min(data.length, 6000), "UTF-8");
                int at = head.indexOf("<svg");
                String tag = at >= 0 ? head.substring(at, Math.max(at, head.indexOf('>', at) < 0 ? head.length() : head.indexOf('>', at))) : "";
                float[] d = svgSize(tag);
                if (d != null) { w = Math.round(d[0]); h = Math.round(d[1]); }
            } catch (Throwable e) { data = null; }
            float f = 1536f / Math.max(w, h);
            final int rw = Math.max(64, Math.min(2048, Math.round(w * f))), rh = Math.max(64, Math.min(2048, Math.round(h * f)));
            final byte[] fd = data;
            PhotoViewer.H.post(() -> {
                if (photo != p || getVisibility() != View.VISIBLE) return;
                if (fd == null) { a.toast("Could not open this picture"); close(); return; }
                renderSvg(p, fd, rw, rh);
            });
        });
    }

    static float[] svgSize(String tag) {
        try {
            java.util.regex.Matcher vb = java.util.regex.Pattern.compile("viewBox\\s*=\\s*[\"']([^\"']+)[\"']").matcher(tag);
            if (vb.find()) {
                String[] q = vb.group(1).trim().split("[\\s,]+");
                if (q.length == 4) {
                    float w = Float.parseFloat(q[2]), h = Float.parseFloat(q[3]);
                    if (w > 0 && h > 0) return new float[]{w, h};
                }
            }
            java.util.regex.Matcher mw = java.util.regex.Pattern.compile("\\swidth\\s*=\\s*[\"']([0-9.]+)").matcher(tag);
            java.util.regex.Matcher mh = java.util.regex.Pattern.compile("\\sheight\\s*=\\s*[\"']([0-9.]+)").matcher(tag);
            if (mw.find() && mh.find()) {
                float w = Float.parseFloat(mw.group(1)), h = Float.parseFloat(mh.group(1));
                if (w > 0 && h > 0) return new float[]{w, h};
            }
        } catch (Throwable e) { }
        return null;
    }

    private void renderSvg(final Photo p, byte[] data, final int w, final int h) {
        try {
            final android.webkit.WebView wv = new android.webkit.WebView(a);
            svgWv = wv;
            android.webkit.WebSettings ws = wv.getSettings();
            ws.setJavaScriptEnabled(false);
            ws.setAllowFileAccess(false);
            ws.setAllowContentAccess(false);
            ws.setBlockNetworkLoads(true);
            wv.setBackgroundColor(0);
            wv.setLayerType(View.LAYER_TYPE_SOFTWARE, null);
            wv.setAlpha(0.01f);
            final boolean[] done = {false};
            final Runnable finish = () -> {
                if (done[0]) return;
                done[0] = true;
                Bitmap out = null;
                try {
                    out = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888);
                    out.eraseColor(0);
                    wv.draw(new Canvas(out));
                } catch (Throwable e) { out = null; }
                try { removeView(wv); wv.destroy(); } catch (Throwable e) { }
                svgWv = null;
                if (photo != p || getVisibility() != View.VISIBLE) return;
                if (out == null) { a.toast("Could not open this picture"); close(); return; }
                bmp = out;
                loading.setVisibility(View.GONE);
                ev.setBitmap(bmp);
            };
            wv.setWebViewClient(new android.webkit.WebViewClient() {
                @Override public void onPageFinished(android.webkit.WebView v, String url) { v.postDelayed(finish, 350); }
            });
            addView(wv, 0, new LayoutParams(w, h));
            String b64 = android.util.Base64.encodeToString(data, android.util.Base64.NO_WRAP);
            String html = "<html><head><meta name='viewport' content='width=device-width, initial-scale=1'>"
                    + "<style>html,body{margin:0;height:100%;background:transparent;overflow:hidden}"
                    + "img{position:absolute;left:0;top:0;width:100%;height:100%;object-fit:fill}</style></head>"
                    + "<body><img src='data:image/svg+xml;base64," + b64 + "'></body></html>";
            wv.loadDataWithBaseURL("about:blank", html, "text/html", "UTF-8", null);
            wv.postDelayed(finish, 6000);
        } catch (Throwable e) { a.toast("Could not open this picture"); close(); }
    }

    private void FULL_LOAD(final Photo p) {
        PhotoViewer.FULLX.execute(() -> {
            Bitmap b = null;
            try { b = decodeSoft(a, p.uri, 3072); } catch (Throwable e) { }
            final Bitmap res = b;
            PhotoViewer.H.post(() -> {
                if (photo != p || getVisibility() != View.VISIBLE) return;
                if (res == null) { a.toast("Could not open this picture"); close(); return; }
                bmp = res;
                loading.setVisibility(View.GONE);
                ev.setBitmap(bmp);
            });
        });
    }

    static Bitmap decodeSoft(Context c, Uri u, final int maxPx) throws Exception {
        return ImageDecoder.decodeBitmap(ImageDecoder.createSource(c.getContentResolver(), u), (decoder, info, s) -> {
            decoder.setAllocator(ImageDecoder.ALLOCATOR_SOFTWARE);
            int w = info.getSize().getWidth(), h = info.getSize().getHeight(), big = Math.max(w, h);
            if (big > maxPx) {
                float f = maxPx / (float) big;
                decoder.setTargetSize(Math.max(1, Math.round(w * f)), Math.max(1, Math.round(h * f)));
            }
        });
    }

    boolean showing() { return getVisibility() == View.VISIBLE; }

    void close() {
        if (svgWv != null) { try { removeView(svgWv); svgWv.destroy(); } catch (Throwable e) { } svgWv = null; }
        setVisibility(View.GONE);
        ev.setBitmap(null);
        bmp = null;
        undo.clear();
        photo = null;
    }

    void onBack() {
        if (tool != T_NONE) { pickTool(T_NONE); return; }
        if (changed) a.confirmDanger("Discard changes?", "Your edits have not been saved.", "Discard", this::close);
        else close();
    }

    void push() {
        undo.add(bmp);
        if (undo.size() > 3) undo.remove(0);
        changed = true;
        updateTop();
    }

    void undoStep() {
        if (undo.isEmpty()) return;
        bmp = undo.remove(undo.size() - 1);
        ev.setBitmap(bmp);
        if (tool == T_CROP) ev.startCrop(ev.ratio);
        updateTop();
    }

    void updateTop() {
        undoBtn.setAlpha(undo.isEmpty() ? 0.35f : 1f);
    }

    void pickTool(int t) {
        tool = t;
        for (int i = 0; i < tools.length; i++) tools[i].setTextColor(t == i + 1 ? Ui.ACC : 0xFFFFFFFF);
        opts.removeAllViews();
        ev.cropping = false;
        ev.filter = null;
        ev.text = null;
        ev.drawing = false;
        ev.strokes.clear();
        ev.rot = 0;
        ev.pickMode = false;
        ev.chromaPrev = null;
        ev.chromaKey = 0;
        ev.chromaSet = false;
        ev.checker = true;
        rotDeg = 0;
        adjB = adjC = adjS = 0;
        if (bmp == null) { ev.invalidate(); return; }
        switch (t) {
            case T_CROP: buildCrop(); ev.startCrop(0); break;
            case T_ROTATE: buildRotate(); break;
            case T_FLIP: buildFlip(); break;
            case T_ADJUST: buildAdjust(); break;
            case T_FILTER: buildFilters(); break;
            case T_TEXT: buildText(); break;
            case T_DRAW: buildDraw(); break;
            case T_CHROMA: buildChroma(); break;
            default: break;
        }
        ev.invalidate();
    }

    LinearLayout row() {
        LinearLayout r = new LinearLayout(a);
        r.setGravity(Gravity.CENTER);
        r.setPadding(Ui.dp(a, 8), Ui.dp(a, 8), Ui.dp(a, 8), Ui.dp(a, 4));
        return r;
    }

    TextView chip(String t, boolean on, View.OnClickListener l) {
        TextView v = Ui.text(a, t, 14, on ? Ui.ONACC : 0xFFFFFFFF, true);
        v.setGravity(Gravity.CENTER);
        v.setPadding(Ui.dp(a, 14), Ui.dp(a, 8), Ui.dp(a, 14), Ui.dp(a, 8));
        v.setBackground(Ui.rip(on ? Ui.ACC : 0x33FFFFFF, 20, a));
        v.setOnClickListener(l);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(Ui.WRAP, Ui.WRAP);
        lp.setMargins(Ui.dp(a, 4), 0, Ui.dp(a, 4), 0);
        v.setLayoutParams(lp);
        return v;
    }

    void buildCrop() {
        final String[] names = {"Free", "1:1", "4:3", "3:4", "16:9", "9:16"};
        final float[] rs = {0, 1f, 4f / 3f, 3f / 4f, 16f / 9f, 9f / 16f};
        android.widget.HorizontalScrollView hs = new android.widget.HorizontalScrollView(a);
        hs.setHorizontalScrollBarEnabled(false);
        final LinearLayout r = row();
        for (int i = 0; i < names.length; i++) {
            final float ratio = rs[i];
            r.addView(chip(names[i], Math.abs(ev.ratio - ratio) < 0.001f, v -> { ev.startCrop(ratio); buildCropChips(r, names, rs); }));
        }
        hs.addView(r);
        opts.addView(hs);
        LinearLayout ar = row();
        ar.addView(chip("Apply crop", true, v -> { applyCrop(); }));
        opts.addView(ar);
    }

    void buildCropChips(LinearLayout r, String[] names, float[] rs) {
        r.removeAllViews();
        for (int i = 0; i < names.length; i++) {
            final float ratio = rs[i];
            final LinearLayout rr = r;
            r.addView(chip(names[i], Math.abs(ev.ratio - ratio) < 0.001f, v -> { ev.startCrop(ratio); buildCropChips(rr, names, rs); }));
        }
    }

    void buildRotate() {
        rotDeg = 0;
        degLabel = Ui.text(a, "0.0\u00B0", 22, 0xFFFFFFFF, true);
        degLabel.setGravity(Gravity.CENTER);
        degLabel.setPadding(0, Ui.dp(a, 8), 0, 0);
        opts.addView(degLabel, new LinearLayout.LayoutParams(Ui.MATCH, Ui.WRAP));
        LinearLayout sr = new LinearLayout(a);
        sr.setGravity(Gravity.CENTER_VERTICAL);
        sr.setPadding(Ui.dp(a, 16), Ui.dp(a, 2), Ui.dp(a, 16), Ui.dp(a, 2));
        final SeekBar sb = new SeekBar(a);
        sb.setMax(3600);
        sb.setProgress(1800);
        Ui.tintSeek(sb);
        sb.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override public void onProgressChanged(SeekBar s, int p, boolean user) {
                setRotDeg((p - 1800) / 10f);
            }
            @Override public void onStartTrackingTouch(SeekBar s) { }
            @Override public void onStopTrackingTouch(SeekBar s) { }
        });
        sr.addView(sb, new LinearLayout.LayoutParams(0, Ui.WRAP, 1));
        opts.addView(sr);
        final LinearLayout r = row();
        r.addView(chip("\u221290\u00B0", false, v -> transform(-90, false, false)));
        r.addView(chip("+90\u00B0", false, v -> transform(90, false, false)));
        r.addView(chip("-0.1\u00B0", false, v -> sb.setProgress(Math.max(0, sb.getProgress() - 1))));
        r.addView(chip("+0.1\u00B0", false, v -> sb.setProgress(Math.min(3600, sb.getProgress() + 1))));
        r.addView(chip("Reset", false, v -> sb.setProgress(1800)));
        r.addView(chip("Apply", true, v -> applyRotate()));
        android.widget.HorizontalScrollView hs = new android.widget.HorizontalScrollView(a);
        hs.setHorizontalScrollBarEnabled(false);
        hs.addView(r);
        opts.addView(hs);
    }

    void setRotDeg(float d) {
        rotDeg = d;
        ev.rot = d;
        ev.checker = true;
        if (degLabel != null) degLabel.setText(String.format(Locale.US, "%.1f\u00B0", d));
        ev.invalidate();
    }

    /** Rotate by any angle; the canvas grows to fit and the new corners are transparent. */
    void applyRotate() {
        if (bmp == null) return;
        if (Math.abs(rotDeg) < 0.05f) { pickTool(T_NONE); return; }
        try {
            double r = Math.toRadians(rotDeg), cs = Math.abs(Math.cos(r)), sn = Math.abs(Math.sin(r));
            float bw = (float) (bmp.getWidth() * cs + bmp.getHeight() * sn), bh = (float) (bmp.getWidth() * sn + bmp.getHeight() * cs);
            float f = Math.min(1f, 4096f / Math.max(bw, bh));
            Bitmap nb = Bitmap.createBitmap(Math.max(1, Math.round(bw * f)), Math.max(1, Math.round(bh * f)), Bitmap.Config.ARGB_8888);
            Canvas c = new Canvas(nb);
            c.translate(nb.getWidth() / 2f, nb.getHeight() / 2f);
            c.scale(f, f);
            c.rotate(rotDeg);
            c.drawBitmap(bmp, -bmp.getWidth() / 2f, -bmp.getHeight() / 2f, new Paint(Paint.FILTER_BITMAP_FLAG | Paint.ANTI_ALIAS_FLAG));
            push();
            bmp = nb;
            if (Math.abs(rotDeg % 90f) > 0.001f) usesAlpha = true;
            ev.setBitmap(bmp);
            pickTool(T_NONE);
        } catch (Throwable e) { a.toast("Not enough memory"); }
    }

    void buildFlip() {
        LinearLayout r = row();
        r.addView(chip("Flip horizontally", false, v -> transform(0, true, false)));
        r.addView(chip("Flip vertically", false, v -> transform(0, false, true)));
        opts.addView(r);
    }

    SeekBar slider(String label, int cur, final int which) {
        LinearLayout r = new LinearLayout(a);
        r.setGravity(Gravity.CENTER_VERTICAL);
        r.setPadding(Ui.dp(a, 16), Ui.dp(a, 2), Ui.dp(a, 16), Ui.dp(a, 2));
        TextView l = Ui.text(a, label, 14, 0xFFFFFFFF, false);
        r.addView(l, new LinearLayout.LayoutParams(Ui.dp(a, 90), Ui.WRAP));
        SeekBar sb = new SeekBar(a);
        sb.setMax(200);
        sb.setProgress(cur + 100);
        Ui.tintSeek(sb);
        sb.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override public void onProgressChanged(SeekBar s, int p, boolean user) {
                if (which == 0) adjB = p - 100; else if (which == 1) adjC = p - 100; else adjS = p - 100;
                ev.filter = filter(adjB, adjC, adjS);
                ev.invalidate();
            }
            @Override public void onStartTrackingTouch(SeekBar s) { }
            @Override public void onStopTrackingTouch(SeekBar s) { }
        });
        r.addView(sb, new LinearLayout.LayoutParams(0, Ui.WRAP, 1));
        opts.addView(r);
        return sb;
    }

    void buildAdjust() {
        slider("Brightness", 0, 0);
        slider("Contrast", 0, 1);
        slider("Saturation", 0, 2);
        LinearLayout ar = row();
        ar.addView(chip("Apply", true, v -> bakeFilter()));
        opts.addView(ar);
    }

    static ColorMatrixColorFilter filter(int b, int c, int s) {
        ColorMatrix sat = new ColorMatrix();
        sat.setSaturation(Math.max(0f, 1f + s / 100f));
        float k = 1f + c / 100f;
        float off = 128f * (1f - k) + b * 1.5f;
        ColorMatrix bc = new ColorMatrix(new float[]{
                k, 0, 0, 0, off,
                0, k, 0, 0, off,
                0, 0, k, 0, off,
                0, 0, 0, 1, 0});
        sat.postConcat(bc);
        return new ColorMatrixColorFilter(sat);
    }

    static ColorMatrixColorFilter preset(int i) {
        ColorMatrix m = new ColorMatrix();
        switch (i) {
            case 1: m.setSaturation(0f); break;
            case 2: m.set(new float[]{0.393f, 0.769f, 0.189f, 0, 0, 0.349f, 0.686f, 0.168f, 0, 0, 0.272f, 0.534f, 0.131f, 0, 0, 0, 0, 0, 1, 0}); break;
            case 3: m.set(new float[]{0.9f, 0, 0, 0, 0, 0, 1f, 0, 0, 0, 0, 0, 1.15f, 0, 0, 0, 0, 0, 1, 0}); break;
            case 4: m.set(new float[]{1.15f, 0, 0, 0, 0, 0, 1f, 0, 0, 0, 0, 0, 0.88f, 0, 0, 0, 0, 0, 1, 0}); break;
            case 5: return filter(0, 15, 45);
            case 6: return filter(15, -20, -25);
            case 7: {
                m.setSaturation(0.6f);
                m.postConcat(new ColorMatrix(new float[]{1.0f, 0, 0, 0, 12, 0, 0.92f, 0, 0, 6, 0, 0, 0.78f, 0, 0, 0, 0, 0, 1, 0}));
                break;
            }
            default: return null;
        }
        return new ColorMatrixColorFilter(m);
    }

    void buildFilters() {
        final String[] names = {"Original", "B&W", "Sepia", "Cool", "Warm", "Vivid", "Faded", "Vintage"};
        android.widget.HorizontalScrollView hs = new android.widget.HorizontalScrollView(a);
        hs.setHorizontalScrollBarEnabled(false);
        final LinearLayout r = row();
        for (int i = 0; i < names.length; i++) {
            final int k = i;
            r.addView(chip(names[i], false, v -> {
                ev.filter = preset(k);
                ev.invalidate();
                for (int j = 0; j < r.getChildCount(); j++) {
                    TextView c = (TextView) r.getChildAt(j);
                    boolean on = j == k;
                    c.setTextColor(on ? Ui.ONACC : 0xFFFFFFFF);
                    c.setBackground(Ui.rip(on ? Ui.ACC : 0x33FFFFFF, 20, a));
                }
            }));
        }
        hs.addView(r);
        opts.addView(hs);
        LinearLayout ar = row();
        ar.addView(chip("Apply", true, v -> bakeFilter()));
        opts.addView(ar);
    }

    LinearLayout swatches(final int cur, final java.util.function.IntConsumer on) {
        LinearLayout r = row();
        for (final int col : PALETTE) {
            TextView sw = new TextView(a);
            android.graphics.drawable.GradientDrawable g = new android.graphics.drawable.GradientDrawable();
            g.setShape(android.graphics.drawable.GradientDrawable.OVAL);
            g.setColor(col);
            g.setStroke(Ui.dp(a, col == cur ? 3 : 1), col == cur ? Ui.ACC : 0x66FFFFFF);
            sw.setBackground(g);
            sw.setOnClickListener(v -> on.accept(col));
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(Ui.dp(a, 30), Ui.dp(a, 30));
            lp.setMargins(Ui.dp(a, 5), 0, Ui.dp(a, 5), 0);
            r.addView(sw, lp);
        }
        return r;
    }

    void buildText() {
        opts.removeAllViews();
        if (ev.text == null) {
            LinearLayout r0 = row();
            r0.addView(chip("Add text", true, v -> askText("")));
            opts.addView(r0);
            askText("");
            return;
        }
        opts.addView(swatches(ev.tcolor, c -> { ev.tcolor = c; ev.invalidate(); buildText(); }));
        opts.addView(seekRow("Size", Math.round((ev.tsize - 0.02f) / 0.28f * 100), 100, p -> { ev.tsize = 0.02f + 0.28f * p / 100f; ev.invalidate(); }, null));
        opts.addView(seekRow("Rotate", Math.round(ev.trot) + 180, 360, p -> { ev.trot = p - 180; ev.invalidate(); }, "\u00B0"));
        if (ev.tbg) {
            TextView bl = Ui.text(a, "Background colour", 12, 0xFFBBBBBB, false);
            bl.setPadding(Ui.dp(a, 16), Ui.dp(a, 4), 0, 0);
            opts.addView(bl);
            opts.addView(swatches(ev.tbgColor, c -> { ev.tbgColor = c; ev.invalidate(); buildText(); }));
        }
        android.widget.HorizontalScrollView hs = new android.widget.HorizontalScrollView(a);
        hs.setHorizontalScrollBarEnabled(false);
        LinearLayout ar = row();
        ar.addView(chip("Edit text", false, v -> askText(ev.text)));
        ar.addView(chip("Font", false, v -> pickFont()));
        ar.addView(chip(ev.tbold ? "Bold: on" : "Bold: off", ev.tbold, v -> { ev.tbold = !ev.tbold; ev.invalidate(); buildText(); }));
        ar.addView(chip(ev.tbg ? "Background: on" : "Background: off", ev.tbg, v -> { ev.tbg = !ev.tbg; if (ev.tbg && ev.tbgColor == ev.tcolor) ev.tbgColor = ev.tcolor == 0xFF000000 ? 0xFFFFFFFF : 0xFF000000; ev.invalidate(); buildText(); }));
        ar.addView(chip("Apply", true, v -> bakeOverlay()));
        hs.addView(ar);
        opts.addView(hs);
    }

    interface IntCb { void on(int p); }

    /** A labelled slider row. {@code unit} non-null shows the value (minus the 180 offset for angles) next to the label. */
    LinearLayout seekRow(final String label, int cur, int max, final IntCb cb, final String unit) {
        LinearLayout sr = new LinearLayout(a);
        sr.setGravity(Gravity.CENTER_VERTICAL);
        sr.setPadding(Ui.dp(a, 16), Ui.dp(a, 2), Ui.dp(a, 16), Ui.dp(a, 2));
        final TextView l = Ui.text(a, label, 14, 0xFFFFFFFF, false);
        sr.addView(l, new LinearLayout.LayoutParams(Ui.dp(a, unit == null ? 60 : 96), Ui.WRAP));
        SeekBar sb = new SeekBar(a);
        sb.setMax(max);
        sb.setProgress(cur);
        Ui.tintSeek(sb);
        if (unit != null) l.setText(label + " " + (cur - 180) + unit);
        sb.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override public void onProgressChanged(SeekBar s, int p, boolean user) {
                cb.on(p);
                if (unit != null) l.setText(label + " " + (p - 180) + unit);
            }
            @Override public void onStartTrackingTouch(SeekBar s) { }
            @Override public void onStopTrackingTouch(SeekBar s) { }
        });
        sr.addView(sb, new LinearLayout.LayoutParams(0, Ui.WRAP, 1));
        return sr;
    }

    void askText(String initial) {
        Ui.prompt(a, "Text", initial, "Type here", "OK", t -> {
            if (bmp == null) return;
            if (ev.text == null) {
                ev.tx = bmp.getWidth() / 2f;
                ev.ty = bmp.getHeight() / 2f;
                ev.tsize = 0.08f;
                ev.tcolor = 0xFFFFFFFF;
                ev.tbg = false;
                ev.trot = 0;
            }
            ev.text = t;
            buildText();
            ev.invalidate();
        });
    }

    // ---- fonts
    java.io.File fontDir() {
        java.io.File d = new java.io.File(a.getFilesDir(), "fonts");
        if (!d.isDirectory()) d.mkdirs();
        return d;
    }

    void pickFont() {
        Ui.Sheet sh = new Ui.Sheet(a, "Font", "Pick a font, or add your own .ttf file");
        final String[][] built = {{"Default", ""}, {"Sans", "sans-serif"}, {"Condensed", "sans-serif-condensed"}, {"Serif", "serif"},
                {"Monospace", "monospace"}, {"Casual", "casual"}, {"Cursive", "cursive"}, {"Small caps", "sans-serif-smallcaps"}};
        for (final String[] f : built) {
            boolean on = ev.fontId.equals("b:" + f[1]);
            sh.item(on ? R.drawable.ic_check : R.drawable.ic_edit, f[0], on ? Ui.ACC : Ui.TXT, () -> {
                ev.fontId = "b:" + f[1];
                ev.tface = f[1].isEmpty() ? null : Typeface.create(f[1], Typeface.NORMAL);
                ev.invalidate();
            });
        }
        java.io.File[] mine = fontDir().listFiles();
        if (mine != null) {
            java.util.Arrays.sort(mine);
            for (final java.io.File ff : mine) {
                if (!ff.getName().toLowerCase(Locale.US).endsWith(".ttf") && !ff.getName().toLowerCase(Locale.US).endsWith(".otf")) continue;
                boolean on = ev.fontId.equals("f:" + ff.getName());
                sh.item(on ? R.drawable.ic_check : R.drawable.ic_edit, ff.getName().replaceAll("\\.(ttf|otf)$", ""), on ? Ui.ACC : Ui.TXT, () -> useFontFile(ff));
            }
        }
        sh.item(R.drawable.ic_add, "Upload a .ttf file", Ui.ACC, () -> {
            try {
                android.content.Intent i = new android.content.Intent(android.content.Intent.ACTION_GET_CONTENT);
                i.setType("*/*");
                i.addCategory(android.content.Intent.CATEGORY_OPENABLE);
                a.startActivityForResult(i, REQ_FONT);
            } catch (Exception e) { a.toast("No app available to pick a file"); }
        });
        sh.show();
    }

    void useFontFile(java.io.File f) {
        try {
            Typeface t = Typeface.createFromFile(f);
            ev.tface = t;
            ev.fontId = "f:" + f.getName();
            ev.invalidate();
        } catch (Throwable e) { a.toast("That font can't be used"); }
    }

    void onFontResult(int res, android.content.Intent data) {
        if (res != Activity.RESULT_OK || data == null || data.getData() == null) return;
        try {
            Uri u = data.getData();
            String name = "font.ttf";
            try (android.database.Cursor cu = a.getContentResolver().query(u, new String[]{android.provider.OpenableColumns.DISPLAY_NAME}, null, null, null)) {
                if (cu != null && cu.moveToFirst() && cu.getString(0) != null) name = cu.getString(0);
            } catch (Exception e) { }
            name = name.replaceAll("[^A-Za-z0-9._ -]", "_");
            if (!name.toLowerCase(Locale.US).endsWith(".ttf") && !name.toLowerCase(Locale.US).endsWith(".otf")) name += ".ttf";
            java.io.File out = new java.io.File(fontDir(), name);
            try (java.io.InputStream in = a.getContentResolver().openInputStream(u); java.io.FileOutputStream os = new java.io.FileOutputStream(out)) {
                byte[] buf = new byte[16384];
                int n;
                while ((n = in.read(buf)) > 0) os.write(buf, 0, n);
            }
            try { Typeface.createFromFile(out); } catch (Throwable bad) { out.delete(); a.toast("That file isn't a usable font"); return; }
            useFontFile(out);
            if (tool == T_TEXT) buildText();
        } catch (Throwable e) { a.toast("Could not add that font"); }
    }

    static final String[] PEN_NAMES = {"Pen", "Marker", "Highlighter", "Dotted", "Neon"};

    void buildDraw() {
        ev.drawing = true;
        opts.removeAllViews();
        LinearLayout mode = row();
        mode.addView(chip("Pencil", !ev.erasing, v -> { ev.erasing = false; buildDraw(); }));
        mode.addView(chip("Eraser", ev.erasing, v -> { ev.erasing = true; buildDraw(); }));
        opts.addView(mode);
        if (!ev.erasing) {
            android.widget.HorizontalScrollView hs = new android.widget.HorizontalScrollView(a);
            hs.setHorizontalScrollBarEnabled(false);
            LinearLayout types = row();
            for (int i = 0; i < PEN_NAMES.length; i++) {
                final int k = i;
                types.addView(chip(PEN_NAMES[i], ev.pen == i, v -> { ev.pen = k; buildDraw(); }));
            }
            hs.addView(types);
            opts.addView(hs);
            opts.addView(swatches(ev.brush, c -> { ev.brush = c; buildDraw(); }));
        } else {
            TextView n = Ui.text(a, "The eraser only removes what you drew since the last Apply.", 12, 0xFFBBBBBB, false);
            n.setPadding(Ui.dp(a, 16), Ui.dp(a, 6), Ui.dp(a, 16), Ui.dp(a, 2));
            n.setSingleLine(false);
            n.setEllipsize(null);
            opts.addView(n);
        }
        opts.addView(seekRow(ev.erasing ? "Eraser" : "Brush", Math.round((ev.brushSize - 0.003f) / 0.057f * 100), 100,
                p -> ev.brushSize = 0.003f + 0.057f * p / 100f, null));
        LinearLayout ar = row();
        ar.addView(chip("Undo stroke", false, v -> { if (!ev.strokes.isEmpty()) { ev.strokes.remove(ev.strokes.size() - 1); ev.invalidate(); } }));
        ar.addView(chip("Apply", true, v -> bakeOverlay()));
        opts.addView(ar);
    }

    // ---- chroma key: select by colour, by alpha, or by area (bucket), then clear or replace the selection
    static final int M_COLOR = 0, M_ALPHA = 1, M_AREA = 2;
    int chromaTol = 30, chromaFill = 0xFFFFFFFF, chromaMode = M_COLOR, chromaSmooth = 25;
    boolean chromaTransparent = true;

    void buildChroma() {
        ev.pickMode = true;
        opts.removeAllViews();
        LinearLayout sel = row();
        TextView sl = Ui.text(a, "Select", 14, 0xFFBBBBBB, true);
        sl.setPadding(0, 0, Ui.dp(a, 6), 0);
        sel.addView(sl);
        sel.addView(chip("Color", chromaMode == M_COLOR, v -> { chromaMode = M_COLOR; chromaPreview(); buildChroma(); }));
        sel.addView(chip("Alpha", chromaMode == M_ALPHA, v -> { chromaMode = M_ALPHA; chromaPreview(); buildChroma(); }));
        sel.addView(chip("Area", chromaMode == M_AREA, v -> { chromaMode = M_AREA; chromaPreview(); buildChroma(); }));
        opts.addView(sel);
        String what = chromaMode == M_COLOR ? "every pixel of that colour"
                : chromaMode == M_ALPHA ? "every pixel with that transparency" : "only the connected area, like a bucket";
        TextView hint = Ui.text(a, (ev.chromaSet ? "Tap or drag again to pick another. " : "Tap the picture to pick. ") + "Selects " + what + ".",
                12, 0xFFBBBBBB, false);
        hint.setPadding(Ui.dp(a, 16), Ui.dp(a, 2), Ui.dp(a, 16), Ui.dp(a, 0));
        hint.setSingleLine(false);
        hint.setEllipsize(null);
        opts.addView(hint);
        opts.addView(seekRow("Tolerance", chromaTol, 100, p -> { chromaTol = p; chromaPreview(); }, null));
        opts.addView(seekRow("Smooth", chromaSmooth, 100, p -> { chromaSmooth = p; chromaPreview(); }, null));
        android.widget.HorizontalScrollView hs = new android.widget.HorizontalScrollView(a);
        hs.setHorizontalScrollBarEnabled(false);
        LinearLayout mode = row();
        mode.addView(chip("Transparent", chromaTransparent, v -> { chromaTransparent = true; chromaPreview(); buildChroma(); }));
        mode.addView(chip("Replace with colour", !chromaTransparent, v -> { chromaTransparent = false; chromaPreview(); buildChroma(); }));
        mode.addView(chip("Apply", true, v -> applyChroma()));
        hs.addView(mode);
        opts.addView(hs);
        if (!chromaTransparent) opts.addView(swatches(chromaFill, c -> { chromaFill = c; chromaPreview(); buildChroma(); }));
        ev.onPick = () -> { chromaPreview(); buildChroma(); };
    }

    /** Recompute the live preview on a small copy so sliding stays smooth. */
    void chromaPreview() {
        if (bmp == null || !ev.chromaSet) { ev.chromaPrev = null; ev.invalidate(); return; }
        try {
            float f = Math.min(1f, 640f / Math.max(bmp.getWidth(), bmp.getHeight()));
            Bitmap small = f < 1f ? Bitmap.createScaledBitmap(bmp, Math.max(1, Math.round(bmp.getWidth() * f)), Math.max(1, Math.round(bmp.getHeight() * f)), true)
                    : bmp.copy(Bitmap.Config.ARGB_8888, true);
            if (!small.isMutable()) small = small.copy(Bitmap.Config.ARGB_8888, true);
            small.setHasAlpha(true);
            chromaKey(small, chromaMode, ev.chromaKey, Math.round(ev.chromaX * f), Math.round(ev.chromaY * f), chromaTol, chromaSmooth, chromaTransparent, chromaFill);
            ev.chromaPrev = small;
        } catch (Throwable e) { ev.chromaPrev = null; }
        ev.invalidate();
    }

    static int dist(int p, int key, int mode) {
        if (mode == M_ALPHA) return Math.round(Math.abs((p >>> 24) - (key >>> 24)) * 1.7f);
        int dr = ((p >> 16) & 255) - ((key >> 16) & 255), dg = ((p >> 8) & 255) - ((key >> 8) & 255), db = (p & 255) - (key & 255);
        return Math.round((float) Math.sqrt((dr * dr * 0.3f + dg * dg * 0.59f + db * db * 0.11f) * 3f));
    }

    /** Selection strength 0..255 for one pixel: full inside the tolerance, fading out over a short band. */
    static int weight(int d, float lo, float feather) {
        if (d <= lo) return 255;
        if (d >= lo + feather) return 0;
        return Math.round(255f * (1f - (d - lo) / feather));
    }

    static void chromaKey(Bitmap b, int mode, int key, int sx, int sy, int tol, int smooth, boolean transparent, int fill) {
        int w = b.getWidth(), h = b.getHeight();
        int[] px = new int[w * h];
        b.getPixels(px, 0, w, 0, 0, w, h);
        float lo = tol * 2.4f, feather = 8f + lo * 0.2f;
        byte[] mask = new byte[w * h];
        if (mode == M_AREA) {
            sx = Math.max(0, Math.min(w - 1, sx));
            sy = Math.max(0, Math.min(h - 1, sy));
            int seed = px[sy * w + sx];
            boolean[] seen = new boolean[w * h];
            int[] stack = new int[1024];
            int sp = 0;
            stack[sp++] = sy * w + sx;
            seen[sy * w + sx] = true;
            while (sp > 0) {
                int i = stack[--sp];
                int wt = weight(dist(px[i], seed, M_COLOR), lo, feather);
                if (wt == 0) continue;
                mask[i] = (byte) wt;
                int x = i % w, y = i / w;
                int[] nb = {x > 0 ? i - 1 : -1, x < w - 1 ? i + 1 : -1, y > 0 ? i - w : -1, y < h - 1 ? i + w : -1};
                for (int n : nb) {
                    if (n < 0 || seen[n]) continue;
                    seen[n] = true;
                    if (sp + 1 >= stack.length) stack = java.util.Arrays.copyOf(stack, stack.length * 2);
                    stack[sp++] = n;
                }
            }
        } else {
            for (int i = 0; i < px.length; i++) mask[i] = (byte) weight(dist(px[i], key, mode), lo, feather);
        }
        int r = Math.round(smooth / 100f * 0.006f * Math.max(w, h));
        if (smooth > 0 && r < 1) r = 1;
        if (r > 0) { blur(mask, w, h, r); blur(mask, w, h, r); }
        int fr = (fill >> 16) & 255, fg = (fill >> 8) & 255, fb = fill & 255;
        for (int i = 0; i < px.length; i++) {
            int m = mask[i] & 255;
            if (m == 0) continue;
            int p = px[i], al = p >>> 24;
            if (transparent) px[i] = ((al * (255 - m) / 255) << 24) | (p & 0x00FFFFFF);
            else {
                int nr = (((p >> 16) & 255) * (255 - m) + fr * m) / 255, ng = (((p >> 8) & 255) * (255 - m) + fg * m) / 255,
                        nb = ((p & 255) * (255 - m) + fb * m) / 255, na = al + (255 - al) * m / 255;
                px[i] = (na << 24) | (nr << 16) | (ng << 8) | nb;
            }
        }
        b.setPixels(px, 0, w, 0, 0, w, h);
    }

    /** In-place box blur of a 0..255 mask (horizontal then vertical), edges clamped. */
    static void blur(byte[] m, int w, int h, int r) {
        byte[] t = new byte[m.length];
        int n = 2 * r + 1;
        for (int y = 0; y < h; y++) {
            int o = y * w, sum = 0;
            for (int k = -r; k <= r; k++) sum += m[o + Math.max(0, Math.min(w - 1, k))] & 255;
            for (int x = 0; x < w; x++) {
                t[o + x] = (byte) (sum / n);
                sum += (m[o + Math.min(w - 1, x + r + 1)] & 255) - (m[o + Math.max(0, x - r)] & 255);
            }
        }
        for (int x = 0; x < w; x++) {
            int sum = 0;
            for (int k = -r; k <= r; k++) sum += t[Math.max(0, Math.min(h - 1, k)) * w + x] & 255;
            for (int y = 0; y < h; y++) {
                m[y * w + x] = (byte) (sum / n);
                sum += (t[Math.min(h - 1, y + r + 1) * w + x] & 255) - (t[Math.max(0, y - r) * w + x] & 255);
            }
        }
    }

    void applyChroma() {
        if (bmp == null) return;
        if (!ev.chromaSet) { a.toast("Tap the picture to pick first"); return; }
        try {
            Bitmap nb = bmp.copy(Bitmap.Config.ARGB_8888, true);
            nb.setHasAlpha(true);
            chromaKey(nb, chromaMode, ev.chromaKey, ev.chromaX, ev.chromaY, chromaTol, chromaSmooth, chromaTransparent, chromaFill);
            push();
            bmp = nb;
            if (chromaTransparent) usesAlpha = true;
            ev.setBitmap(bmp);
            pickTool(T_NONE);
        } catch (Throwable e) { a.toast("Not enough memory"); }
    }

    void bakeOverlay() {
        if (bmp == null) return;
        if (ev.text == null && ev.strokes.isEmpty()) { pickTool(T_NONE); return; }
        try {
            Bitmap nb = bmp.copy(Bitmap.Config.ARGB_8888, true);
            nb.setHasAlpha(true);
            ev.paintOverlays(new Canvas(nb), nb.getWidth(), nb.getHeight(), false);
            push();
            bmp = nb;
            ev.setBitmap(bmp);
            pickTool(T_NONE);
        } catch (Throwable e) { a.toast("Not enough memory"); }
    }

    void transform(float deg, boolean fh, boolean fv) {
        if (bmp == null) return;
        Matrix m = new Matrix();
        if (fh) m.postScale(-1, 1);
        if (fv) m.postScale(1, -1);
        if (deg != 0) m.postRotate(deg);
        try {
            Bitmap nb = Bitmap.createBitmap(bmp, 0, 0, bmp.getWidth(), bmp.getHeight(), m, true);
            push();
            bmp = nb;
            ev.setBitmap(bmp);
        } catch (Throwable e) { a.toast("Not enough memory"); }
    }

    void applyCrop() {
        if (bmp == null || !ev.cropping) return;
        RectF r = ev.crop;
        int x = Math.max(0, Math.round(r.left)), y = Math.max(0, Math.round(r.top));
        int w = Math.min(bmp.getWidth() - x, Math.round(r.width())), h = Math.min(bmp.getHeight() - y, Math.round(r.height()));
        if (w < 8 || h < 8) return;
        try {
            Bitmap nb = Bitmap.createBitmap(bmp, x, y, w, h);
            push();
            bmp = nb;
            ev.setBitmap(bmp);
            pickTool(T_NONE);
        } catch (Throwable e) { a.toast("Not enough memory"); }
    }

    void bakeFilter() {
        if (bmp == null) return;
        if (ev.filter == null || (tool == T_ADJUST && adjB == 0 && adjC == 0 && adjS == 0)) { pickTool(T_NONE); return; }
        try {
            Bitmap nb = Bitmap.createBitmap(bmp.getWidth(), bmp.getHeight(), Bitmap.Config.ARGB_8888);
            Paint p = new Paint(Paint.FILTER_BITMAP_FLAG);
            p.setColorFilter(ev.filter);
            new Canvas(nb).drawBitmap(bmp, 0, 0, p);
            push();
            bmp = nb;
            ev.setBitmap(bmp);
            pickTool(T_NONE);
        } catch (Throwable e) { a.toast("Not enough memory"); }
    }

    void askSave() {
        if (bmp == null) return;
        if (tool == T_CROP && ev.cropping) applyCrop();
        else if (tool == T_ADJUST || tool == T_FILTER) bakeFilter();
        else if (tool == T_TEXT || tool == T_DRAW) bakeOverlay();
        else if (tool == T_ROTATE) applyRotate();
        else if (tool == T_CHROMA && ev.chromaSet) applyChroma();
        final Bitmap out = bmp;
        final Photo p = photo;
        if (p.gif || p.svg || (usesAlpha && formatFor(p.name) == Bitmap.CompressFormat.JPEG)) {
            Ui.confirm(a, "Save as PNG", (p.gif || p.svg ? "GIF and SVG pictures are saved as a new PNG so nothing is lost. "
                    : "This picture now has transparent areas, so it is saved as a new PNG. ") + "The original stays as it is.",
                    "Save PNG", false, () -> saveAsNew(p, out));
            return;
        }
        Ui.choose(a, "Save picture", "Replace the original, or keep it and save the edited picture as a new one (Save new)?",
                "Replace", true, () -> replaceOriginal(p, out),
                "Save new", false, () -> saveAsNew(p, out));
    }

    static Bitmap.CompressFormat formatFor(String name) {
        String n = name == null ? "" : name.toLowerCase(Locale.US);
        if (n.endsWith(".png")) return Bitmap.CompressFormat.PNG;
        if (n.endsWith(".webp")) return Bitmap.CompressFormat.WEBP_LOSSY;
        return Bitmap.CompressFormat.JPEG;
    }

    static String mimeFor(Bitmap.CompressFormat f) {
        return f == Bitmap.CompressFormat.PNG ? "image/png" : f == Bitmap.CompressFormat.JPEG ? "image/jpeg" : "image/webp";
    }

    static String extFor(Bitmap.CompressFormat f) {
        return f == Bitmap.CompressFormat.PNG ? ".png" : f == Bitmap.CompressFormat.JPEG ? ".jpg" : ".webp";
    }

    void replaceOriginal(final Photo p, final Bitmap out) {
        Bitmap.CompressFormat f = formatFor(p.name);
        try (OutputStream os = a.getContentResolver().openOutputStream(p.uri, "wt")) {
            if (os == null) throw new Exception("no stream");
            out.compress(f, 95, os);
            replaced(p);
        } catch (SecurityException e) {
            try {
                PendingIntent pi = MediaStore.createWriteRequest(a.getContentResolver(), Collections.singletonList(p.uri));
                pendingBmp = out;
                pendingPhoto = p;
                a.startIntentSenderForResult(pi.getIntentSender(), REQ_WRITE, null, 0, 0, 0);
            } catch (Exception e2) { a.pops.saveFailed("the picture", () -> replaceOriginal(p, out)); }
        } catch (Exception e) { a.pops.saveFailed("the picture", () -> replaceOriginal(p, out)); }
    }

    void onWriteResult(int res) {
        Bitmap b = pendingBmp;
        Photo p = pendingPhoto;
        pendingBmp = null;
        pendingPhoto = null;
        if (res != Activity.RESULT_OK || b == null || p == null) return;
        Bitmap.CompressFormat f = formatFor(p.name);
        try (OutputStream os = a.getContentResolver().openOutputStream(p.uri, "wt")) {
            b.compress(f, 95, os);
            replaced(p);
        } catch (Exception e) { final Bitmap fb = b; a.pops.saveFailed("the picture", () -> replaceOriginal(p, fb)); }
    }

    void replaced(Photo p) {
        for (String k : new ArrayList<>(Ui.PCACHE.snapshot().keySet())) if (k.startsWith(p.key() + "@")) Ui.PCACHE.remove(k);
        PhotoViewer.FULL.remove(p.key());
        p.w = bmp != null ? bmp.getWidth() : p.w;
        p.h = bmp != null ? bmp.getHeight() : p.h;
        a.toast("Picture replaced");
        close();
        if (a.viewer != null) a.viewer.reload();
        a.refreshPhotos();
        a.loadMedia();
    }

    void saveAsNew(final Photo p, final Bitmap out) {
        final Bitmap.CompressFormat f = (p.gif || p.svg || (usesAlpha && formatFor(p.name) == Bitmap.CompressFormat.JPEG)) ? Bitmap.CompressFormat.PNG : formatFor(p.name);
        loading.setText("Saving...");
        loading.setVisibility(View.VISIBLE);
        PhotoViewer.FULLX.execute(() -> {
            boolean ok = false;
            try {
                java.io.ByteArrayOutputStream bo = new java.io.ByteArrayOutputStream();
                out.compress(f, 95, bo);
                String base = p.title == null || p.title.isEmpty() ? "Picture" : p.title;
                ok = a.pops.saveFile(bo.toByteArray(), base + "_edited" + extFor(f), mimeFor(f), p.path);
            } catch (Throwable e) { }
            final boolean res = ok;
            PhotoViewer.H.post(() -> {
                loading.setText("Loading...");
                loading.setVisibility(View.GONE);
                if (res) { a.toast("Saved as a new picture"); close(); a.loadMedia(); }
                else a.pops.saveFailed("the picture", () -> saveAsNew(p, out));
            });
        });
    }

    static class EditView extends View {
        Bitmap bmp;
        boolean cropping;
        float ratio;
        final RectF crop = new RectF();
        ColorMatrixColorFilter filter;

        String text;
        float tx, ty, tsize = 0.08f, trot;
        int tcolor = 0xFFFFFFFF, tbgColor = 0xFF000000;
        boolean tbg, tbold = true;
        Typeface tface;
        String fontId = "b:";

        float rot;
        boolean checker = true, pickMode, chromaSet, picking;
        int chromaX, chromaY;
        float pickX, pickY;
        Bitmap chromaPrev;
        int chromaKey;
        Runnable onPick;
        float bottomPx;
        Paint checkPaint;

        boolean drawing, erasing;
        int pen;
        int brush = 0xFFFF5C5C;
        float brushSize = 0.012f;
        final ArrayList<Stroke> strokes = new ArrayList<>();
        Stroke live;
        final Paint pic = new Paint(Paint.FILTER_BITMAP_FLAG), line = new Paint(Paint.ANTI_ALIAS_FLAG), dim = new Paint();
        final Matrix m = new Matrix();
        float sc = 1, ox, oy;

        int grab;
        float px, py;
        final RectF start = new RectF();

        static class Stroke {
            android.graphics.Path path = new android.graphics.Path();
            int color, type;
            float width;
            boolean erase;
        }

        static void stylePaint(Paint p, Stroke s) {
            p.reset();
            p.setAntiAlias(true);
            p.setStyle(Paint.Style.STROKE);
            p.setStrokeCap(Paint.Cap.ROUND);
            p.setStrokeJoin(Paint.Join.ROUND);
            p.setColor(s.color);
            p.setStrokeWidth(s.width);
            if (s.erase) {
                p.setColor(0xFF000000);
                p.setXfermode(new android.graphics.PorterDuffXfermode(android.graphics.PorterDuff.Mode.CLEAR));
                return;
            }
            switch (s.type) {
                case 1: p.setColor((s.color & 0x00FFFFFF) | 0xB0000000); p.setStrokeWidth(s.width * 1.5f); break;
                case 2:
                    p.setColor((s.color & 0x00FFFFFF) | 0x66000000);
                    p.setStrokeCap(Paint.Cap.SQUARE);
                    p.setStrokeWidth(s.width * 2.4f);
                    break;
                case 3: p.setPathEffect(new android.graphics.DashPathEffect(new float[]{0.1f, s.width * 2.2f}, 0)); break;
                default: break;
            }
        }

        void paintOverlays(Canvas c, float bw, float bh, boolean preview) {
            if (!strokes.isEmpty()) {
                // strokes live in their own layer so the eraser can only remove what is drawn here, never the picture
                int layer = c.saveLayer(0, 0, bw, bh, null);
                Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
                for (Stroke s : strokes) {
                    if (s.type == 4 && !s.erase) {
                        stylePaint(p, s);
                        p.setColor(s.color);
                        p.setStrokeWidth(s.width * 2.6f);
                        p.setAlpha(150);
                        p.setMaskFilter(new android.graphics.BlurMaskFilter(Math.max(1f, s.width * 1.2f), android.graphics.BlurMaskFilter.Blur.NORMAL));
                        c.drawPath(s.path, p);
                        stylePaint(p, s);
                        p.setColor(0xFFFFFFFF);
                        p.setStrokeWidth(Math.max(1f, s.width * 0.7f));
                        c.drawPath(s.path, p);
                    } else {
                        stylePaint(p, s);
                        c.drawPath(s.path, p);
                    }
                }
                c.restoreToCount(layer);
            }
            if (text != null) {
                Paint t = new Paint(Paint.ANTI_ALIAS_FLAG);
                t.setTextSize(tsize * Math.min(bw, bh));
                if (tface != null) t.setTypeface(tface);
                t.setFakeBoldText(tbold);
                t.setTextAlign(Paint.Align.CENTER);
                t.setColor(tcolor);
                Paint.FontMetrics fm = t.getFontMetrics();
                float w = t.measureText(text), pad = t.getTextSize() * 0.3f;
                float half = (fm.descent - fm.ascent) / 2f;
                c.save();
                c.rotate(trot, tx, ty);
                if (tbg) {
                    Paint b = new Paint(Paint.ANTI_ALIAS_FLAG);
                    b.setColor((tbgColor & 0x00FFFFFF) | 0xDD000000);
                    c.drawRoundRect(tx - w / 2 - pad, ty - half - pad / 2, tx + w / 2 + pad, ty + half + pad / 2, pad, pad, b);
                } else {
                    t.setShadowLayer(t.getTextSize() * 0.08f, 0, 0, tcolor == 0xFF000000 ? 0xAAFFFFFF : 0xAA000000);
                }
                c.drawText(text, tx, ty - (fm.ascent + fm.descent) / 2f, t);
                if (preview) {
                    Paint fr = new Paint(Paint.ANTI_ALIAS_FLAG);
                    fr.setStyle(Paint.Style.STROKE);
                    fr.setColor(0xCCFFFFFF);
                    fr.setStrokeWidth(Math.max(2f, Math.min(bw, bh) * 0.003f));
                    fr.setPathEffect(new android.graphics.DashPathEffect(new float[]{14, 10}, 0));
                    c.drawRect(tx - w / 2 - pad, ty - half - pad / 2, tx + w / 2 + pad, ty + half + pad / 2, fr);
                }
                c.restore();
            }
        }

        EditView(Context c) {
            super(c);
            dim.setColor(0xAA000000);
            line.setColor(0xFFFFFFFF);
            line.setStyle(Paint.Style.STROKE);
        }

        float botPx() { return bottomPx > 0 ? bottomPx : 130 * getResources().getDisplayMetrics().density; }

        void setBottomPx(int h) {
            if (Math.abs(h - bottomPx) < 2) return;
            bottomPx = h;
            camSet = false;
            invalidate();
            if (cropping) post(this::zoomToCrop);
        }

        void setBitmap(Bitmap b) {
            bmp = b;
            cropping = false;
            invalidate();
        }

        float csc, cox, coy;
        boolean camSet;
        android.animation.ValueAnimator camAnim;

        float[] areaFit(RectF r) {
            float d = getResources().getDisplayMetrics().density, pad = 24 * d, top = 56 * d, bottom = botPx();
            float W = getWidth() - 2 * pad, H = getHeight() - top - bottom;
            float s = Math.min(W / r.width(), H / r.height());
            return new float[]{s, getWidth() / 2f - r.centerX() * s, top + (getHeight() - top - bottom) / 2f - r.centerY() * s};
        }

        void zoomToCrop() {
            if (bmp == null || !cropping || getWidth() == 0) return;
            if (!camSet) { layoutMap(); }
            final float[] to = areaFit(crop);
            final float s0 = csc, x0 = cox, y0 = coy;
            if (camAnim != null) camAnim.cancel();
            camAnim = android.animation.ValueAnimator.ofFloat(0f, 1f);
            camAnim.setDuration(220);
            camAnim.addUpdateListener(an -> {
                float f = (float) an.getAnimatedValue();
                csc = s0 + (to[0] - s0) * f;
                cox = x0 + (to[1] - x0) * f;
                coy = y0 + (to[2] - y0) * f;
                invalidate();
            });
            camAnim.start();
        }

        void startCrop(float r) {
            if (bmp == null) return;
            ratio = r;
            cropping = true;
            float bw = bmp.getWidth(), bh = bmp.getHeight();
            float w = bw, h = bh;
            if (r > 0) { if (bw / bh > r) w = bh * r; else h = bw / r; }
            w *= 0.9f;
            h *= 0.9f;
            crop.set((bw - w) / 2, (bh - h) / 2, (bw + w) / 2, (bh + h) / 2);
            invalidate();
            zoomToCrop();
        }

        void layoutMap() {
            if (bmp == null) return;
            float pad = getResources().getDisplayMetrics().density * 16;
            float W = getWidth() - 2 * pad, H = getHeight() - 2 * pad;

            float d = getResources().getDisplayMetrics().density;
            float top = 56 * d, bottom = botPx();
            H = getHeight() - top - bottom;
            sc = Math.min(W / bmp.getWidth(), H / bmp.getHeight());
            ox = (getWidth() - bmp.getWidth() * sc) / 2f;
            oy = top + (H - bmp.getHeight() * sc) / 2f;
            if (cropping) {
                if (!camSet) { csc = sc; cox = ox; coy = oy; camSet = true; post(this::zoomToCrop); }
                sc = csc; ox = cox; oy = coy;
            } else {
                camSet = false;
                if (camAnim != null) { camAnim.cancel(); camAnim = null; }
            }
            if (rot != 0f && !cropping) {
                double r = Math.toRadians(rot), cs = Math.abs(Math.cos(r)), sn = Math.abs(Math.sin(r));
                float rw = (float) (bmp.getWidth() * cs + bmp.getHeight() * sn), rh = (float) (bmp.getWidth() * sn + bmp.getHeight() * cs);
                sc = Math.min(W / rw, H / rh);
                float cx = getWidth() / 2f, cy = top + H / 2f;
                m.setTranslate(-bmp.getWidth() / 2f, -bmp.getHeight() / 2f);
                m.postRotate(rot);
                m.postScale(sc, sc);
                m.postTranslate(cx, cy);
                rbw = rw; rbh = rh; rcx = cx; rcy = cy;
                return;
            }
            m.setScale(sc, sc);
            m.postTranslate(ox, oy);
        }

        float rbw, rbh, rcx, rcy;

        void drawChecker(Canvas c, float l, float t, float r, float b) {
            if (checkPaint == null) {
                int sq = Math.max(6, (int) (8 * getResources().getDisplayMetrics().density));
                Bitmap tile = Bitmap.createBitmap(sq * 2, sq * 2, Bitmap.Config.ARGB_8888);
                Canvas tc = new Canvas(tile);
                tc.drawColor(0xFF4A4A4A);
                Paint q = new Paint();
                q.setColor(0xFF6A6A6A);
                tc.drawRect(0, 0, sq, sq, q);
                tc.drawRect(sq, sq, sq * 2, sq * 2, q);
                checkPaint = new Paint();
                checkPaint.setShader(new android.graphics.BitmapShader(tile, android.graphics.Shader.TileMode.REPEAT, android.graphics.Shader.TileMode.REPEAT));
            }
            c.drawRect(l, t, r, b, checkPaint);
        }

        float vx(float x) { return ox + x * sc; }
        float vy(float y) { return oy + y * sc; }

        @Override protected void onDraw(Canvas c) {
            if (bmp == null) return;
            layoutMap();
            if (cropping) {
                float d0 = getResources().getDisplayMetrics().density;
                c.clipRect(0, 56 * d0, getWidth(), getHeight() - botPx());
            }
            if (checker) {
                if (rot != 0f && !cropping) drawChecker(c, rcx - rbw * sc / 2f, rcy - rbh * sc / 2f, rcx + rbw * sc / 2f, rcy + rbh * sc / 2f);
                else drawChecker(c, vx(0), vy(0), vx(bmp.getWidth()), vy(bmp.getHeight()));
            }
            pic.setColorFilter(filter);
            if (chromaPrev != null) {
                c.save();
                c.concat(m);
                c.drawBitmap(chromaPrev, null, new RectF(0, 0, bmp.getWidth(), bmp.getHeight()), pic);
                c.restore();
            } else c.drawBitmap(bmp, m, pic);
            if (text != null || !strokes.isEmpty()) {
                c.save();
                c.concat(m);
                paintOverlays(c, bmp.getWidth(), bmp.getHeight(), true);
                c.restore();
            }
            if (pickMode) drawLoupe(c);
            if (!cropping) return;
            float d = getResources().getDisplayMetrics().density;
            float l = vx(crop.left), t = vy(crop.top), r = vx(crop.right), b = vy(crop.bottom);
            float il = vx(0), it = vy(0), ir = vx(bmp.getWidth()), ib = vy(bmp.getHeight());
            c.drawRect(il, it, ir, t, dim);
            c.drawRect(il, b, ir, ib, dim);
            c.drawRect(il, t, l, b, dim);
            c.drawRect(r, t, ir, b, dim);
            line.setStrokeWidth(1.5f * d);
            line.setAlpha(255);
            c.drawRect(l, t, r, b, line);
            line.setAlpha(110);
            line.setStrokeWidth(d);
            for (int i = 1; i < 3; i++) {
                c.drawLine(l + (r - l) * i / 3f, t, l + (r - l) * i / 3f, b, line);
                c.drawLine(l, t + (b - t) * i / 3f, r, t + (b - t) * i / 3f, line);
            }
            line.setAlpha(255);
            line.setStrokeWidth(4 * d);
            float s = 18 * d;
            c.drawLine(l, t, l + s, t, line); c.drawLine(l, t, l, t + s, line);
            c.drawLine(r, t, r - s, t, line); c.drawLine(r, t, r, t + s, line);
            c.drawLine(l, b, l + s, b, line); c.drawLine(l, b, l, b - s, line);
            c.drawLine(r, b, r - s, b, line); c.drawLine(r, b, r, b - s, line);
        }

        /** Magnifier + crosshair that follows the finger while picking a colour, and a ring left on the picked spot. */
        void drawLoupe(Canvas c) {
            if (!chromaSet || bmp == null) return;
            float d = getResources().getDisplayMetrics().density;
            Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
            p.setStyle(Paint.Style.STROKE);
            float fx = vx(chromaX + 0.5f), fy = vy(chromaY + 0.5f);
            p.setStrokeWidth(3 * d);
            p.setColor(0xFF000000);
            c.drawCircle(fx, fy, 9 * d, p);
            p.setStrokeWidth(1.5f * d);
            p.setColor(0xFFFFFFFF);
            c.drawCircle(fx, fy, 9 * d, p);
            if (!picking) return;
            float R = 46 * d, cx = Math.max(R + 8 * d, Math.min(getWidth() - R - 8 * d, pickX)), cy = pickY - 84 * d;
            if (cy < R + 70 * d) cy = pickY + 84 * d + R * 0.4f;
            int half = 7;
            android.graphics.Path clip = new android.graphics.Path();
            clip.addCircle(cx, cy, R, android.graphics.Path.Direction.CW);
            c.save();
            c.clipPath(clip);
            Paint bg = new Paint();
            bg.setColor(0xFF333333);
            c.drawRect(cx - R, cy - R, cx + R, cy + R, bg);
            Paint np = new Paint();
            np.setFilterBitmap(false);
            c.drawBitmap(bmp, new android.graphics.Rect(chromaX - half, chromaY - half, chromaX + half + 1, chromaY + half + 1),
                    new RectF(cx - R, cy - R, cx + R, cy + R), np);
            c.restore();
            p.setStyle(Paint.Style.STROKE);
            p.setStrokeWidth(5 * d);
            p.setColor(chromaKey | 0xFF000000);
            c.drawCircle(cx, cy, R, p);
            p.setStrokeWidth(1.5f * d);
            p.setColor(0xFFFFFFFF);
            c.drawCircle(cx, cy, R + 2.5f * d, p);
            p.setColor(0xFF000000);
            c.drawCircle(cx, cy, R - 2.5f * d, p);
            float cell = R / half;
            p.setStrokeWidth(2 * d);
            p.setColor(0xFFFFFFFF);
            c.drawRect(cx - cell / 2, cy - cell / 2, cx + cell / 2, cy + cell / 2, p);
            p.setColor(0xFF000000);
            p.setStrokeWidth(d);
            c.drawRect(cx - cell / 2 - d, cy - cell / 2 - d, cx + cell / 2 + d, cy + cell / 2 + d, p);
        }

        int hit(float x, float y) {
            float d = getResources().getDisplayMetrics().density, k = 30 * d;
            float l = vx(crop.left), t = vy(crop.top), r = vx(crop.right), b = vy(crop.bottom);
            boolean nl = Math.abs(x - l) < k, nr = Math.abs(x - r) < k, nt = Math.abs(y - t) < k, nb = Math.abs(y - b) < k;
            if (nl && nt) return 2;
            if (nr && nt) return 3;
            if (nl && nb) return 4;
            if (nr && nb) return 5;
            if (ratio == 0) {
                if (nl && y > t && y < b) return 6;
                if (nr && y > t && y < b) return 7;
                if (nt && x > l && x < r) return 8;
                if (nb && x > l && x < r) return 9;
            }
            if (x > l && x < r && y > t && y < b) return 1;
            return 0;
        }

        @Override public boolean onTouchEvent(MotionEvent e) {
            if (bmp == null) return false;
            if (pickMode) {
                int act = e.getActionMasked();
                if (act == MotionEvent.ACTION_DOWN || act == MotionEvent.ACTION_MOVE) {
                    int ix = Math.round((e.getX() - ox) / sc - 0.5f), iy = Math.round((e.getY() - oy) / sc - 0.5f);
                    picking = true;
                    pickX = e.getX();
                    pickY = e.getY();
                    if (ix >= 0 && iy >= 0 && ix < bmp.getWidth() && iy < bmp.getHeight()) {
                        chromaKey = bmp.getPixel(ix, iy);
                        chromaX = ix;
                        chromaY = iy;
                        chromaSet = true;
                    }
                    invalidate();
                } else if (act == MotionEvent.ACTION_UP || act == MotionEvent.ACTION_CANCEL) {
                    picking = false;
                    invalidate();
                    if (act == MotionEvent.ACTION_UP && chromaSet && onPick != null) onPick.run();
                }
                return true;
            }
            if (drawing) {
                float x = (e.getX() - ox) / sc, y = (e.getY() - oy) / sc;
                switch (e.getActionMasked()) {
                    case MotionEvent.ACTION_DOWN:
                        live = new Stroke();
                        live.color = brush;
                        live.type = pen;
                        live.erase = erasing;
                        live.width = Math.max(1f, brushSize * Math.min(bmp.getWidth(), bmp.getHeight()));
                        live.path.moveTo(x, y);
                        live.path.lineTo(x + 0.01f, y);
                        strokes.add(live);
                        break;
                    case MotionEvent.ACTION_MOVE:
                        if (live != null) live.path.lineTo(x, y);
                        break;
                    default:
                        live = null;
                        break;
                }
                invalidate();
                return true;
            }
            if (text != null && !cropping) {
                switch (e.getActionMasked()) {
                    case MotionEvent.ACTION_DOWN: px = e.getX(); py = e.getY(); return true;
                    case MotionEvent.ACTION_MOVE:
                        tx += (e.getX() - px) / sc;
                        ty += (e.getY() - py) / sc;
                        px = e.getX();
                        py = e.getY();
                        invalidate();
                        return true;
                    default: return true;
                }
            }
            if (!cropping) return false;
            float bw = bmp.getWidth(), bh = bmp.getHeight(), min = 32;
            switch (e.getActionMasked()) {
                case MotionEvent.ACTION_DOWN:
                    grab = hit(e.getX(), e.getY());
                    px = e.getX();
                    py = e.getY();
                    start.set(crop);
                    return grab != 0;
                case MotionEvent.ACTION_MOVE: {
                    if (grab == 0) return false;
                    float dx = (e.getX() - px) / sc, dy = (e.getY() - py) / sc;
                    RectF s = start;
                    if (grab == 1) {
                        float nx = Math.max(0, Math.min(bw - s.width(), s.left + dx)), ny = Math.max(0, Math.min(bh - s.height(), s.top + dy));
                        crop.set(nx, ny, nx + s.width(), ny + s.height());
                    } else if (grab >= 6) {
                        float l = s.left, t = s.top, r = s.right, b = s.bottom;
                        if (grab == 6) l = Math.max(0, Math.min(r - min, l + dx));
                        if (grab == 7) r = Math.min(bw, Math.max(l + min, r + dx));
                        if (grab == 8) t = Math.max(0, Math.min(b - min, t + dy));
                        if (grab == 9) b = Math.min(bh, Math.max(t + min, b + dy));
                        crop.set(l, t, r, b);
                    } else {

                        boolean left = grab == 2 || grab == 4, topc = grab == 2 || grab == 3;
                        float fx = left ? s.right : s.left, fy = topc ? s.bottom : s.top;
                        float cx = (left ? s.left : s.right) + dx, cy = (topc ? s.top : s.bottom) + dy;
                        float maxW = left ? fx : bw - fx, maxH = topc ? fy : bh - fy;
                        float w = Math.max(min, Math.min(maxW, Math.abs(cx - fx))), h = Math.max(min, Math.min(maxH, Math.abs(cy - fy)));
                        if (ratio > 0) {
                            w = Math.max(w, h * ratio);
                            w = Math.min(w, Math.min(maxW, maxH * ratio));
                            h = w / ratio;
                        }
                        crop.set(left ? fx - w : fx, topc ? fy - h : fy, left ? fx : fx + w, topc ? fy : fy + h);
                    }
                    invalidate();
                    return true;
                }
                default:
                    if (grab != 0) zoomToCrop();
                    grab = 0;
                    return true;
            }
        }
    }
}
