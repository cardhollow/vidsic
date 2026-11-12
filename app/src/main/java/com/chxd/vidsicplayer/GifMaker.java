package com.chxd.vidsicplayer;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.RectF;
import android.view.Gravity;
import android.view.View;
import android.widget.FrameLayout;
import android.widget.HorizontalScrollView;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.SeekBar;
import android.widget.TextView;
import java.io.ByteArrayOutputStream;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Date;
import java.util.List;
import java.util.Locale;

final class GifMaker extends FrameLayout {
    static final String[] TRANS = {"None", "Fade", "Slide", "Slide up", "Zoom", "Wipe"};
    static final String[] SIZES = {"Small", "Medium", "Large"};
    static final int[] SIZE_PX = {320, 480, 640};
    static final String[] SHAPES = {"1:1", "4:3", "16:9", "9:16"};
    static final float[] SHAPE_R = {1f, 4f / 3f, 16f / 9f, 9f / 16f};
    static final int MAX = 30;

    static class Frame {
        Photo p;
        Bitmap src, scaled;
        float dur = 1.5f;
        int trans = 1;
    }

    final MainActivity a;
    final GView gv;
    final LinearLayout strip, panel;
    final TextView loading, playBtn, summary;
    final LinearLayout tabBar;
    int ptab;
    final List<Frame> frames = new ArrayList<>();
    int sel = 0, size = 1, shape = 0, fps = 10;
    float trLen = 0.6f;
    boolean fit, playing, busy;
    int ow = 480, oh = 480;
    long startNs;
    float tNow;

    GifMaker(MainActivity act) {
        super(act);
        a = act;
        setBackgroundColor(0xFF000000);
        setClickable(true);
        gv = new GView(a);
        addView(gv, new LayoutParams(Ui.MATCH, Ui.MATCH));

        LinearLayout top = new LinearLayout(a);
        top.setGravity(Gravity.CENTER_VERTICAL);
        top.setBackgroundColor(0xE6000000);
        top.setPadding(Ui.dp(a, 4), Ui.dp(a, 6), Ui.dp(a, 8), Ui.dp(a, 6));
        ImageView x = Ui.btn(a, R.drawable.ic_close, 0xFFFFFFFF, 24, 10);
        x.setOnClickListener(v -> onBack());
        top.addView(x);
        TextView t = Ui.text(a, "Create GIF", 17, 0xFFFFFFFF, true);
        t.setPadding(Ui.dp(a, 6), 0, 0, 0);
        top.addView(t, new LinearLayout.LayoutParams(0, Ui.WRAP, 1));
        TextView save = Ui.text(a, "Save", 14, Ui.ONACC, true);
        save.setGravity(Gravity.CENTER);
        save.setPadding(Ui.dp(a, 16), Ui.dp(a, 8), Ui.dp(a, 16), Ui.dp(a, 8));
        save.setBackground(Ui.rip(Ui.ACC, 20, a));
        save.setOnClickListener(v -> export());
        top.addView(save);
        addView(top, new LayoutParams(Ui.MATCH, Ui.WRAP, Gravity.TOP));

        LinearLayout bottom = new LinearLayout(a);
        bottom.setOrientation(LinearLayout.VERTICAL);
        bottom.setBackgroundColor(0xE6000000);
        LinearLayout pr = new LinearLayout(a);
        pr.setGravity(Gravity.CENTER_VERTICAL);
        pr.setPadding(Ui.dp(a, 12), Ui.dp(a, 4), Ui.dp(a, 12), Ui.dp(a, 2));
        playBtn = Ui.text(a, "Play", 14, Ui.ONACC, true);
        playBtn.setGravity(Gravity.CENTER);
        playBtn.setPadding(Ui.dp(a, 18), Ui.dp(a, 6), Ui.dp(a, 18), Ui.dp(a, 6));
        playBtn.setBackground(Ui.rip(Ui.ACC, 18, a));
        playBtn.setOnClickListener(v -> setPlaying(!playing));
        pr.addView(playBtn);
        summary = Ui.text(a, "", 12, 0xFFBBBBBB, false);
        summary.setPadding(Ui.dp(a, 12), 0, 0, 0);
        pr.addView(summary, new LinearLayout.LayoutParams(0, Ui.WRAP, 1));
        bottom.addView(pr);
        HorizontalScrollView hs = new HorizontalScrollView(a);
        hs.setHorizontalScrollBarEnabled(false);
        strip = new LinearLayout(a);
        strip.setPadding(Ui.dp(a, 8), Ui.dp(a, 4), Ui.dp(a, 8), Ui.dp(a, 4));
        hs.addView(strip);
        bottom.addView(hs, new LinearLayout.LayoutParams(Ui.MATCH, Ui.WRAP));
        tabBar = new LinearLayout(a);
        bottom.addView(tabBar, new LinearLayout.LayoutParams(Ui.MATCH, Ui.WRAP));
        ScrollView ps = new ScrollView(a);
        panel = new LinearLayout(a);
        panel.setOrientation(LinearLayout.VERTICAL);
        panel.setPadding(0, 0, 0, Ui.dp(a, 8));
        ps.addView(panel);
        bottom.addView(ps, new LinearLayout.LayoutParams(Ui.MATCH, Ui.dp(a, 170)));
        addView(bottom, new LayoutParams(Ui.MATCH, Ui.WRAP, Gravity.BOTTOM));

        loading = Ui.text(a, "Loading...", 16, 0xFFFFFFFF, true);
        loading.setGravity(Gravity.CENTER);
        loading.setBackgroundColor(0xCC000000);
        addView(loading, new LayoutParams(Ui.MATCH, Ui.MATCH));
        setVisibility(View.GONE);
    }

    boolean showing() { return getVisibility() == View.VISIBLE; }

    void open(List<Photo> ps) {
        List<Photo> use = new ArrayList<>();
        for (Photo p : ps) if (!p.svg) use.add(p);
        if (use.size() < 2) { a.toast("Pick at least 2 pictures"); return; }
        if (use.size() > MAX) { use = new ArrayList<>(use.subList(0, MAX)); a.toast("Using the first " + MAX + " pictures"); }
        frames.clear();
        sel = 0;
        busy = false;
        playing = false;
        tNow = 0;
        loading.setText("Loading...");
        loading.setVisibility(View.VISIBLE);
        setVisibility(View.VISIBLE);
        final List<Photo> src = new ArrayList<>(use);
        PhotoViewer.FULLX.execute(() -> {
            final List<Frame> fs = new ArrayList<>();
            for (Photo p : src) {
                try {
                    Frame f = new Frame();
                    f.p = p;
                    f.src = PhotoEditor.decodeSoft(a, p.uri, 1000);
                    fs.add(f);
                } catch (Throwable e) { }
            }
            PhotoViewer.H.post(() -> {
                if (!showing()) return;
                if (fs.size() < 2) { a.toast("Could not open these pictures"); close(); return; }
                frames.addAll(fs);
                frames.get(0).trans = 0;
                rescale();
                loading.setVisibility(View.GONE);
                rebuild();
                setPlaying(true);
            });
        });
    }

    void close() {
        playing = false;
        setVisibility(View.GONE);
        frames.clear();
    }

    void onBack() {
        if (busy) return;
        close();
    }

    void rescale() {
        ow = SIZE_PX[size];
        oh = Math.round(ow / SHAPE_R[shape]);
        if (oh % 2 == 1) oh++;
        for (Frame f : frames) {
            Bitmap b = Bitmap.createBitmap(ow, oh, Bitmap.Config.ARGB_8888);
            Canvas c = new Canvas(b);
            c.drawColor(0xFF000000);
            Paint p = new Paint(Paint.FILTER_BITMAP_FLAG);
            float sw = f.src.getWidth(), sh = f.src.getHeight();
            float k = fit ? Math.min(ow / sw, oh / sh) : Math.max(ow / sw, oh / sh);
            float dw = sw * k, dh = sh * k;
            c.drawBitmap(f.src, null, new RectF((ow - dw) / 2f, (oh - dh) / 2f, (ow + dw) / 2f, (oh + dh) / 2f), p);
            f.scaled = b;
        }
    }

    float transTime(int i) { return i > 0 && frames.get(i).trans != 0 ? trLen : 0f; }

    float total() {
        float t = 0;
        for (int i = 0; i < frames.size(); i++) t += frames.get(i).dur + (i + 1 < frames.size() ? transTime(i + 1) : 0);
        return t;
    }

    float startOf(int idx) {
        float t = 0;
        for (int i = 0; i < idx; i++) t += frames.get(i).dur + transTime(i + 1);
        return t;
    }

    void drawAt(Canvas c, float t) {
        float acc = 0;
        int n = frames.size();
        for (int i = 0; i < n; i++) {
            Frame f = frames.get(i);
            if (t < acc + f.dur || i == n - 1) { c.drawBitmap(f.scaled, 0, 0, null); return; }
            acc += f.dur;
            float tt = transTime(i + 1);
            if (t < acc + tt) {
                compose(c, f.scaled, frames.get(i + 1).scaled, (t - acc) / tt, frames.get(i + 1).trans);
                return;
            }
            acc += tt;
        }
    }

    void compose(Canvas c, Bitmap A, Bitmap B, float p, int type) {
        Paint ap = new Paint(Paint.FILTER_BITMAP_FLAG);
        float W = A.getWidth(), H = A.getHeight();
        switch (type) {
            case 1:
                c.drawBitmap(A, 0, 0, null);
                ap.setAlpha(Math.round(255 * p));
                c.drawBitmap(B, 0, 0, ap);
                break;
            case 2:
                c.drawBitmap(A, -p * W, 0, null);
                c.drawBitmap(B, W - p * W, 0, null);
                break;
            case 3:
                c.drawBitmap(A, 0, -p * H, null);
                c.drawBitmap(B, 0, H - p * H, null);
                break;
            case 4: {
                c.drawBitmap(A, 0, 0, null);
                float s = 1.4f - 0.4f * p;
                ap.setAlpha(Math.round(255 * p));
                c.save();
                c.scale(s, s, W / 2f, H / 2f);
                c.drawBitmap(B, 0, 0, ap);
                c.restore();
                break;
            }
            case 5:
                c.drawBitmap(A, 0, 0, null);
                c.save();
                c.clipRect(0, 0, p * W, H);
                c.drawBitmap(B, 0, 0, null);
                c.restore();
                break;
            default:
                c.drawBitmap(B, 0, 0, null);
                break;
        }
    }

    final Runnable tick = new Runnable() {
        @Override public void run() {
            if (!playing || !showing()) return;
            float tot = Math.max(0.1f, total());
            tNow = ((System.nanoTime() - startNs) / 1e9f) % tot;
            gv.invalidate();
            gv.postDelayed(this, Engine.SAVER ? 66 : 33);
        }
    };

    void setPlaying(boolean on) {
        playing = on;
        playBtn.setText(on ? "Pause" : "Play");
        if (on) {
            startNs = System.nanoTime() - (long) (tNow * 1e9f);
            gv.post(tick);
        }
    }

    TextView chip(String t, boolean on, View.OnClickListener l) {
        TextView v = Ui.text(a, t, 13, on ? Ui.ONACC : 0xFFFFFFFF, true);
        v.setGravity(Gravity.CENTER);
        v.setPadding(Ui.dp(a, 13), Ui.dp(a, 7), Ui.dp(a, 13), Ui.dp(a, 7));
        v.setBackground(Ui.rip(on ? Ui.ACC : 0x33FFFFFF, 18, a));
        v.setOnClickListener(l);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(Ui.WRAP, Ui.WRAP);
        lp.setMargins(Ui.dp(a, 4), 0, Ui.dp(a, 4), 0);
        v.setLayoutParams(lp);
        return v;
    }

    LinearLayout chipRow(String label) {
        LinearLayout wrap = new LinearLayout(a);
        wrap.setOrientation(LinearLayout.VERTICAL);
        if (label != null) {
            TextView l = Ui.text(a, label, 12, 0xFFBBBBBB, false);
            l.setPadding(Ui.dp(a, 16), Ui.dp(a, 6), 0, 0);
            wrap.addView(l);
        }
        HorizontalScrollView hs = new HorizontalScrollView(a);
        hs.setHorizontalScrollBarEnabled(false);
        LinearLayout r = new LinearLayout(a);
        r.setPadding(Ui.dp(a, 8), Ui.dp(a, 4), Ui.dp(a, 8), Ui.dp(a, 2));
        hs.addView(r);
        wrap.addView(hs);
        panel.addView(wrap);
        return r;
    }

    SeekBar slider(String label, final TextView[] val, int max, int cur, final SeekBar.OnSeekBarChangeListener l) {
        LinearLayout r = new LinearLayout(a);
        r.setGravity(Gravity.CENTER_VERTICAL);
        r.setPadding(Ui.dp(a, 16), Ui.dp(a, 2), Ui.dp(a, 16), Ui.dp(a, 2));
        r.addView(Ui.text(a, label, 13, 0xFFFFFFFF, false), new LinearLayout.LayoutParams(Ui.dp(a, 96), Ui.WRAP));
        SeekBar sb = new SeekBar(a);
        sb.setMax(max);
        sb.setProgress(cur);
        Ui.tintSeek(sb);
        sb.setOnSeekBarChangeListener(l);
        r.addView(sb, new LinearLayout.LayoutParams(0, Ui.WRAP, 1));
        val[0] = Ui.text(a, "", 13, 0xFFFFFFFF, true);
        val[0].setGravity(Gravity.END);
        r.addView(val[0], new LinearLayout.LayoutParams(Ui.dp(a, 48), Ui.WRAP));
        panel.addView(r);
        return sb;
    }

    static String secs(float s) { return String.format(Locale.US, "%.1fs", s); }

    void rebuild() {
        if (frames.isEmpty()) return;
        if (sel >= frames.size()) sel = frames.size() - 1;
        buildStrip();
        buildTabs();
        buildPanel();
        updateSummary();
        gv.invalidate();
    }

    void updateSummary() {
        summary.setText("Picture " + (sel + 1) + " of " + frames.size() + "  •  " + secs(total()) + "  •  " + ow + " x " + oh);
    }

    void buildTabs() {
        tabBar.removeAllViews();
        String[] names = {"Picture", "Transition", "Output"};
        for (int i = 0; i < names.length; i++) {
            final int k = i;
            LinearLayout col = new LinearLayout(a);
            col.setOrientation(LinearLayout.VERTICAL);
            col.setBackground(Ui.rip(0, 0, a));
            TextView t = Ui.text(a, names[i], 14, i == ptab ? Ui.ACC : 0xFFBBBBBB, true);
            t.setGravity(Gravity.CENTER);
            t.setPadding(0, Ui.dp(a, 10), 0, Ui.dp(a, 8));
            col.addView(t, new LinearLayout.LayoutParams(Ui.MATCH, Ui.WRAP));
            View bar = new View(a);
            bar.setBackgroundColor(i == ptab ? Ui.ACC : 0x33FFFFFF);
            col.addView(bar, new LinearLayout.LayoutParams(Ui.MATCH, Ui.dp(a, i == ptab ? 3 : 1)));
            col.setOnClickListener(v -> { ptab = k; buildTabs(); buildPanel(); });
            tabBar.addView(col, new LinearLayout.LayoutParams(0, Ui.WRAP, 1));
        }
    }

    void hint(String s) {
        TextView t = Ui.text(a, s, 13, 0xFFBBBBBB, false);
        t.setSingleLine(false);
        t.setPadding(Ui.dp(a, 16), Ui.dp(a, 10), Ui.dp(a, 16), Ui.dp(a, 4));
        panel.addView(t);
    }

    void buildStrip() {
        strip.removeAllViews();
        for (int i = 0; i < frames.size(); i++) {
            final int k = i;
            final Frame f = frames.get(i);
            FrameLayout cell = new FrameLayout(a);
            android.graphics.drawable.GradientDrawable g = new android.graphics.drawable.GradientDrawable();
            g.setColor(0xFF1A1A22);
            g.setCornerRadius(Ui.dp(a, 8));
            g.setStroke(Ui.dp(a, i == sel ? 3 : 1), i == sel ? Ui.ACC : 0x44FFFFFF);
            cell.setBackground(g);
            ImageView iv = new ImageView(a);
            iv.setScaleType(ImageView.ScaleType.CENTER_CROP);
            iv.setPadding(Ui.dp(a, 3), Ui.dp(a, 3), Ui.dp(a, 3), Ui.dp(a, 3));
            Ui.photoThumb(a, f.p, iv, Ui.dp(a, 84));
            cell.addView(iv, new FrameLayout.LayoutParams(Ui.MATCH, Ui.MATCH));
            TextView d = Ui.text(a, secs(f.dur), 11, 0xFFFFFFFF, true);
            d.setBackground(Ui.shape(0xAA000000, 8, a));
            d.setPadding(Ui.dp(a, 5), Ui.dp(a, 1), Ui.dp(a, 5), Ui.dp(a, 1));
            FrameLayout.LayoutParams dp = new FrameLayout.LayoutParams(Ui.WRAP, Ui.WRAP, Gravity.BOTTOM | Gravity.START);
            dp.setMargins(Ui.dp(a, 5), 0, 0, Ui.dp(a, 5));
            cell.addView(d, dp);
            TextView nb = Ui.text(a, String.valueOf(i + 1), 11, i == sel ? Ui.ONACC : 0xFFFFFFFF, true);
            nb.setGravity(Gravity.CENTER);
            nb.setBackground(Ui.shape(i == sel ? Ui.ACC : 0xAA000000, 100, a));
            FrameLayout.LayoutParams np = new FrameLayout.LayoutParams(Ui.dp(a, 20), Ui.dp(a, 20), Gravity.BOTTOM | Gravity.END);
            np.setMargins(0, 0, Ui.dp(a, 5), Ui.dp(a, 5));
            cell.addView(nb, np);
            if (i > 0 && f.trans != 0) {
                TextView tr = Ui.text(a, TRANS[f.trans], 10, 0xFFFFFFFF, false);
                tr.setBackground(Ui.shape(0xAA8B6BFF, 8, a));
                tr.setPadding(Ui.dp(a, 5), Ui.dp(a, 1), Ui.dp(a, 5), Ui.dp(a, 1));
                FrameLayout.LayoutParams tp = new FrameLayout.LayoutParams(Ui.WRAP, Ui.WRAP, Gravity.TOP | Gravity.START);
                tp.setMargins(Ui.dp(a, 5), Ui.dp(a, 5), 0, 0);
                cell.addView(tr, tp);
            }
            cell.setOnClickListener(v -> {
                sel = k;
                setPlaying(false);
                tNow = startOf(k);
                rebuild();
            });
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(Ui.dp(a, 84), Ui.dp(a, 84));
            lp.setMargins(Ui.dp(a, 4), 0, Ui.dp(a, 4), 0);
            strip.addView(cell, lp);
        }
    }

    void buildPanel() {
        panel.removeAllViews();
        final Frame f = frames.get(sel);
        LinearLayout r;
        if (ptab == 0) {
            final TextView[] dv = new TextView[1];
            slider("Time on screen", dv, 78, Math.round((f.dur - 0.2f) * 10), new SeekBar.OnSeekBarChangeListener() {
                @Override public void onProgressChanged(SeekBar s, int p, boolean user) {
                    if (!user) return;
                    f.dur = 0.2f + p / 10f;
                    dv[0].setText(secs(f.dur));
                    refreshStripLabels();
                    updateSummary();
                }
                @Override public void onStartTrackingTouch(SeekBar s) { }
                @Override public void onStopTrackingTouch(SeekBar s) { }
            });
            dv[0].setText(secs(f.dur));
            r = chipRow("Order");
            r.addView(chip("Move left", false, v -> move(-1)));
            r.addView(chip("Move right", false, v -> move(1)));
            r = chipRow("More");
            r.addView(chip("Same time for all", false, v -> { for (Frame o : frames) o.dur = f.dur; rebuild(); }));
            if (frames.size() > 2) r.addView(chip("Remove picture", false, v -> {
                frames.remove(sel);
                if (frames.get(0).trans != 0) frames.get(0).trans = 0;
                rebuild();
            }));
        } else if (ptab == 1) {
            if (sel == 0) {
                hint("The first picture simply appears. Tap another picture above to choose how it comes in.");
            } else {
                r = chipRow("How picture " + (sel + 1) + " comes in");
                for (int i = 0; i < TRANS.length; i++) {
                    final int k = i;
                    r.addView(chip(TRANS[i], f.trans == i, v -> { f.trans = k; rebuild(); }));
                }
                r.addView(chip("Apply to all", false, v -> {
                    for (int i = 1; i < frames.size(); i++) frames.get(i).trans = f.trans;
                    rebuild();
                }));
            }
            final TextView[] tv = new TextView[1];
            slider("Transition time", tv, 14, Math.round((trLen - 0.1f) * 10), new SeekBar.OnSeekBarChangeListener() {
                @Override public void onProgressChanged(SeekBar s, int p, boolean user) {
                    if (!user) return;
                    trLen = 0.1f + p / 10f;
                    tv[0].setText(secs(trLen));
                    updateSummary();
                }
                @Override public void onStartTrackingTouch(SeekBar s) { }
                @Override public void onStopTrackingTouch(SeekBar s) { }
            });
            tv[0].setText(secs(trLen));
            hint("Transition time is the same for every picture.");
        } else {
            r = chipRow("Size");
            for (int i = 0; i < SIZES.length; i++) {
                final int k = i;
                r.addView(chip(SIZES[i] + " " + SIZE_PX[i], size == i, v -> { size = k; rescale(); rebuild(); }));
            }
            r = chipRow("Shape");
            for (int i = 0; i < SHAPES.length; i++) {
                final int k = i;
                r.addView(chip(SHAPES[i], shape == i, v -> { shape = k; rescale(); rebuild(); }));
            }
            r = chipRow("Picture in the shape");
            r.addView(chip("Fill (crop edges)", !fit, v -> { fit = false; rescale(); rebuild(); }));
            r.addView(chip("Fit (show all)", fit, v -> { fit = true; rescale(); rebuild(); }));
            r = chipRow("Smoothness (frames per second)");
            int[] fp = {8, 10, 15};
            for (final int v0 : fp) r.addView(chip(v0 + " fps", fps == v0, v -> { fps = v0; rebuild(); }));
        }
    }

    void refreshStripLabels() {
        for (int i = 0; i < strip.getChildCount() && i < frames.size(); i++) {
            FrameLayout cell = (FrameLayout) strip.getChildAt(i);
            for (int j = 0; j < cell.getChildCount(); j++) {
                View v = cell.getChildAt(j);
                if (v instanceof TextView && ((TextView) v).getText().toString().endsWith("s") && !(((TextView) v).getText().toString().contains(" ")))
                    ((TextView) v).setText(secs(frames.get(i).dur));
            }
        }
    }

    void move(int dir) {
        int to = sel + dir;
        if (to < 0 || to >= frames.size()) return;
        Collections.swap(frames, sel, to);
        sel = to;
        frames.get(0).trans = 0;
        if (frames.get(1).trans == 0) frames.get(1).trans = 1;
        rebuild();
    }

    void export() {
        if (busy || frames.size() < 2) return;
        busy = true;
        setPlaying(false);
        loading.setText("Creating GIF...");
        loading.setVisibility(View.VISIBLE);
        final List<Frame> fl = new ArrayList<>(frames);
        final int tfCount = Math.max(1, Math.round(trLen * fps)), tfDelay = Math.max(2, Math.round(100f / fps));
        final int W = ow, H = oh;
        final float tl = trLen;
        PhotoViewer.FULLX.execute(() -> {
            boolean ok = false;
            try {
                ByteArrayOutputStream bo = new ByteArrayOutputStream();
                GifEncoder enc = new GifEncoder(bo, W, H);
                Bitmap work = Bitmap.createBitmap(W, H, Bitmap.Config.ARGB_8888);
                Canvas c = new Canvas(work);
                int[] px = new int[W * H];
                for (int i = 0; i < fl.size(); i++) {
                    final int done = i + 1;
                    PhotoViewer.H.post(() -> loading.setText("Creating GIF " + done + " / " + fl.size()));
                    Frame f = fl.get(i);
                    f.scaled.getPixels(px, 0, W, 0, 0, W, H);
                    enc.addFrame(px, Math.round(f.dur * 100));
                    if (i + 1 < fl.size() && fl.get(i + 1).trans != 0) {
                        Frame nx = fl.get(i + 1);
                        for (int k = 1; k <= tfCount; k++) {
                            c.drawColor(0xFF000000);
                            compose(c, f.scaled, nx.scaled, k / (float) (tfCount + 1), nx.trans);
                            work.getPixels(px, 0, W, 0, 0, W, H);
                            enc.addFrame(px, tfDelay);
                        }
                    }
                }
                enc.finish();
                work.recycle();
                String name = "Animation_" + new SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(new Date()) + ".gif";
                ok = a.pops.saveFile(bo.toByteArray(), name, "image/gif", "Pictures/Vidsic/");
            } catch (Throwable e) { }
            final boolean res = ok;
            PhotoViewer.H.post(() -> {
                busy = false;
                loading.setText("Loading...");
                loading.setVisibility(View.GONE);
                if (res) { a.toast("GIF saved in Pictures/Vidsic"); close(); a.loadMedia(); }
                else a.pops.saveFailed("the GIF", this::export);
            });
        });
    }

    class GView extends View {
        GView(Context c) { super(c); }

        @Override protected void onDraw(Canvas c) {
            if (frames.isEmpty() || frames.get(0).scaled == null) return;
            float d = getResources().getDisplayMetrics().density;
            float top = 60 * d, bottom = 350 * d, pad = 12 * d;
            float aw = getWidth() - 2 * pad, ah = getHeight() - top - bottom;
            if (ah < 80 * d) ah = 80 * d;
            float r = (float) ow / oh, w = aw, h = aw / r;
            if (h > ah) { h = ah; w = ah * r; }
            float x = (getWidth() - w) / 2f, y = top + (ah - h) / 2f;
            c.save();
            c.translate(x, y);
            c.scale(w / ow, h / oh);
            c.clipRect(0, 0, ow, oh);
            drawAt(c, tNow);
            c.restore();
        }
    }
}
