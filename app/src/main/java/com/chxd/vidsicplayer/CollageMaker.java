package com.chxd.vidsicplayer;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.Rect;
import android.graphics.RectF;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.widget.FrameLayout;
import android.widget.HorizontalScrollView;
import android.widget.LinearLayout;
import android.widget.SeekBar;
import android.widget.TextView;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Date;
import java.util.List;
import java.util.Locale;

final class CollageMaker extends FrameLayout {
    static final String[] LAYOUTS = {"Grid", "Rows", "Columns", "Feature"};
    static final String[] SHAPES = {"1:1", "4:5", "9:16", "3:2", "16:9"};
    static final float[] SHAPE_R = {1f, 4f / 5f, 9f / 16f, 3f / 2f, 16f / 9f};
    static final int[] BGS = {0xFFFFFFFF, 0xFF000000, 0xFF2A2A36, 0xFF8B6BFF, 0xFFF4D35E, 0xFFEE6C4D};
    static final int MAX = 9;

    final MainActivity a;
    final CView cv;
    final LinearLayout opts;
    final TextView loading;
    final List<Photo> items = new ArrayList<>();
    final List<Bitmap> bmps = new ArrayList<>();
    int layout = 0, shape = 0, bg = 0, gap = 8;
    boolean round;
    int selected = -1;
    boolean saving;

    CollageMaker(MainActivity act) {
        super(act);
        a = act;
        setBackgroundColor(0xFF000000);
        setClickable(true);
        cv = new CView(a);
        addView(cv, new LayoutParams(Ui.MATCH, Ui.MATCH));

        LinearLayout top = new LinearLayout(a);
        top.setGravity(Gravity.CENTER_VERTICAL);
        top.setBackgroundColor(0xE6000000);
        top.setPadding(Ui.dp(a, 4), Ui.dp(a, 6), Ui.dp(a, 8), Ui.dp(a, 6));
        android.widget.ImageView x = Ui.btn(a, R.drawable.ic_close, 0xFFFFFFFF, 24, 10);
        x.setOnClickListener(v -> onBack());
        top.addView(x);
        TextView t = Ui.text(a, "Collage", 17, 0xFFFFFFFF, true);
        t.setPadding(Ui.dp(a, 6), 0, 0, 0);
        top.addView(t, new LinearLayout.LayoutParams(0, Ui.WRAP, 1));
        android.widget.ImageView sh = Ui.btn(a, R.drawable.ic_shuffle, 0xFFFFFFFF, 22, 10);
        sh.setOnClickListener(v -> {
            if (bmps.size() < 2) return;
            long seed = System.nanoTime();
            Collections.shuffle(bmps, new java.util.Random(seed));
            Collections.shuffle(items, new java.util.Random(seed));
            selected = -1;
            cv.invalidate();
        });
        top.addView(sh);
        TextView save = Ui.text(a, "Save", 14, Ui.ONACC, true);
        save.setGravity(Gravity.CENTER);
        save.setPadding(Ui.dp(a, 16), Ui.dp(a, 8), Ui.dp(a, 16), Ui.dp(a, 8));
        save.setBackground(Ui.rip(Ui.ACC, 20, a));
        save.setOnClickListener(v -> save());
        LinearLayout.LayoutParams sp = new LinearLayout.LayoutParams(Ui.WRAP, Ui.WRAP);
        sp.leftMargin = Ui.dp(a, 6);
        top.addView(save, sp);
        addView(top, new LayoutParams(Ui.MATCH, Ui.WRAP, Gravity.TOP));

        opts = new LinearLayout(a);
        opts.setOrientation(LinearLayout.VERTICAL);
        opts.setBackgroundColor(0xE6000000);
        opts.setPadding(0, Ui.dp(a, 4), 0, Ui.dp(a, 8));
        addView(opts, new LayoutParams(Ui.MATCH, Ui.WRAP, Gravity.BOTTOM));

        loading = Ui.text(a, "Loading...", 16, 0xFFFFFFFF, true);
        loading.setGravity(Gravity.CENTER);
        addView(loading, new LayoutParams(Ui.MATCH, Ui.MATCH));
        setVisibility(View.GONE);
    }

    boolean showing() { return getVisibility() == View.VISIBLE; }

    void open(List<Photo> ps) {
        List<Photo> use = new ArrayList<>();
        for (Photo p : ps) if (!p.svg) use.add(p);
        if (use.size() < 2) { a.toast("Pick at least 2 pictures"); return; }
        if (use.size() > MAX) { use = new ArrayList<>(use.subList(0, MAX)); a.toast("Using the first " + MAX + " pictures"); }
        items.clear();
        items.addAll(use);
        bmps.clear();
        selected = -1;
        layout = 0;
        shape = 0;
        bg = 0;
        gap = 8;
        round = false;
        saving = false;
        buildOptions();
        loading.setVisibility(View.VISIBLE);
        setVisibility(View.VISIBLE);
        final List<Photo> src = new ArrayList<>(use);
        PhotoViewer.FULLX.execute(() -> {
            final List<Photo> ok = new ArrayList<>();
            final List<Bitmap> bm = new ArrayList<>();
            for (Photo p : src) {
                try {
                    bm.add(PhotoEditor.decodeSoft(a, p.uri, 1200));
                    ok.add(p);
                } catch (Throwable e) { }
            }
            PhotoViewer.H.post(() -> {
                if (!showing() || items.size() != src.size() || !items.containsAll(src)) return;
                if (ok.size() < 2) { a.toast("Could not open these pictures"); close(); return; }
                items.clear();
                items.addAll(ok);
                bmps.clear();
                bmps.addAll(bm);
                loading.setVisibility(View.GONE);
                cv.invalidate();
            });
        });
    }

    void close() {
        setVisibility(View.GONE);
        bmps.clear();
        items.clear();
    }

    void onBack() {
        if (saving) return;
        close();
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

    LinearLayout chipRow() {
        HorizontalScrollView hs = new HorizontalScrollView(a);
        hs.setHorizontalScrollBarEnabled(false);
        LinearLayout r = new LinearLayout(a);
        r.setGravity(Gravity.CENTER_VERTICAL);
        r.setPadding(Ui.dp(a, 8), Ui.dp(a, 4), Ui.dp(a, 8), Ui.dp(a, 4));
        hs.addView(r);
        opts.addView(hs, new LinearLayout.LayoutParams(Ui.MATCH, Ui.WRAP));
        return r;
    }

    void buildOptions() {
        opts.removeAllViews();
        LinearLayout r = chipRow();
        for (int i = 0; i < LAYOUTS.length; i++) {
            final int k = i;
            r.addView(chip(LAYOUTS[i], layout == i, v -> { layout = k; selected = -1; buildOptions(); cv.invalidate(); }));
        }
        r = chipRow();
        for (int i = 0; i < SHAPES.length; i++) {
            final int k = i;
            r.addView(chip(SHAPES[i], shape == i, v -> { shape = k; buildOptions(); cv.invalidate(); }));
        }
        r = chipRow();
        for (int i = 0; i < BGS.length; i++) {
            final int k = i;
            TextView sw = new TextView(a);
            android.graphics.drawable.GradientDrawable g = new android.graphics.drawable.GradientDrawable();
            g.setShape(android.graphics.drawable.GradientDrawable.OVAL);
            g.setColor(BGS[i]);
            g.setStroke(Ui.dp(a, bg == i ? 3 : 1), bg == i ? 0xFFFFFFFF : 0x66FFFFFF);
            sw.setBackground(g);
            sw.setOnClickListener(v -> { bg = k; buildOptions(); cv.invalidate(); });
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(Ui.dp(a, 32), Ui.dp(a, 32));
            lp.setMargins(Ui.dp(a, 6), 0, Ui.dp(a, 6), 0);
            r.addView(sw, lp);
        }
        r.addView(chip(round ? "Rounded corners: on" : "Rounded corners: off", round, v -> { round = !round; buildOptions(); cv.invalidate(); }));
        LinearLayout g = new LinearLayout(a);
        g.setGravity(Gravity.CENTER_VERTICAL);
        g.setPadding(Ui.dp(a, 16), Ui.dp(a, 2), Ui.dp(a, 16), 0);
        g.addView(Ui.text(a, "Spacing", 13, 0xFFFFFFFF, false), new LinearLayout.LayoutParams(Ui.dp(a, 70), Ui.WRAP));
        SeekBar sb = new SeekBar(a);
        sb.setMax(40);
        sb.setProgress(gap);
        Ui.tintSeek(sb);
        sb.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override public void onProgressChanged(SeekBar s, int p, boolean user) { gap = p; cv.invalidate(); }
            @Override public void onStartTrackingTouch(SeekBar s) { }
            @Override public void onStopTrackingTouch(SeekBar s) { }
        });
        g.addView(sb, new LinearLayout.LayoutParams(0, Ui.WRAP, 1));
        opts.addView(g, new LinearLayout.LayoutParams(Ui.MATCH, Ui.WRAP));
    }

    static List<RectF> cells(int n, int kind) {
        List<RectF> out = new ArrayList<>();
        if (kind == 1) {
            for (int i = 0; i < n; i++) out.add(new RectF(0, i / (float) n, 1, (i + 1) / (float) n));
        } else if (kind == 2) {
            for (int i = 0; i < n; i++) out.add(new RectF(i / (float) n, 0, (i + 1) / (float) n, 1));
        } else if (kind == 3 && n > 1) {
            out.add(new RectF(0, 0, 0.6f, 1));
            for (int i = 0; i < n - 1; i++) out.add(new RectF(0.6f, i / (float) (n - 1), 1, (i + 1) / (float) (n - 1)));
        } else {
            int cols = (int) Math.ceil(Math.sqrt(n)), rows = (int) Math.ceil(n / (double) cols), k = 0;
            for (int r = 0; r < rows; r++) {
                int cnt = r < rows - 1 ? cols : n - cols * (rows - 1);
                for (int c = 0; c < cnt; c++) out.add(new RectF(c / (float) cnt, r / (float) rows, (c + 1) / (float) cnt, (r + 1) / (float) rows));
            }
        }
        return out;
    }

    List<RectF> cellRects(float w, float h) {
        float g = w * gap / 400f;
        List<RectF> cs = cells(bmps.size(), layout);
        List<RectF> out = new ArrayList<>();
        float iw = w - 2 * g, ih = h - 2 * g;
        for (RectF c : cs) {
            out.add(new RectF(g + c.left * iw + g / 2f * (c.left > 0 ? 1 : 0) , g + c.top * ih + g / 2f * (c.top > 0 ? 1 : 0),
                    g + c.right * iw - g / 2f * (c.right < 1 ? 1 : 0), g + c.bottom * ih - g / 2f * (c.bottom < 1 ? 1 : 0)));
        }
        return out;
    }

    void render(Canvas c, float w, float h, int sel) {
        Paint p = new Paint(Paint.FILTER_BITMAP_FLAG | Paint.ANTI_ALIAS_FLAG);
        c.drawColor(BGS[bg]);
        List<RectF> rs = cellRects(w, h);
        float rad = round ? Math.min(w, h) * 0.03f : 0;
        for (int i = 0; i < rs.size() && i < bmps.size(); i++) {
            RectF d = rs.get(i);
            Bitmap b = bmps.get(i);

            float bw = b.getWidth(), bh = b.getHeight();
            float cr = d.width() / d.height();
            Rect s;
            if (bw / bh > cr) { float nw = bh * cr; s = new Rect(Math.round((bw - nw) / 2), 0, Math.round((bw + nw) / 2), Math.round(bh)); }
            else { float nh = bw / cr; s = new Rect(0, Math.round((bh - nh) / 2), Math.round(bw), Math.round((bh + nh) / 2)); }
            c.save();
            if (rad > 0) { Path path = new Path(); path.addRoundRect(d, rad, rad, Path.Direction.CW); c.clipPath(path); }
            else c.clipRect(d);
            c.drawBitmap(b, s, d, p);
            c.restore();
            if (i == sel) {
                Paint hp = new Paint(Paint.ANTI_ALIAS_FLAG);
                hp.setStyle(Paint.Style.STROKE);
                hp.setColor(Ui.ACC);
                hp.setStrokeWidth(Math.max(3, w * 0.008f));
                c.drawRect(d, hp);
            }
        }
    }

    void save() {
        if (saving || bmps.size() < 2) return;
        doSave();
    }

    void doSave() {
        if (saving || bmps.size() < 2) return;
        saving = true;
        loading.setText("Saving...");
        loading.setVisibility(View.VISIBLE);
        final int W = 2048, H = Math.round(W / SHAPE_R[shape]);
        PhotoViewer.FULLX.execute(() -> {
            boolean ok = false;
            try {
                Bitmap out = Bitmap.createBitmap(W, H, Bitmap.Config.ARGB_8888);
                render(new Canvas(out), W, H, -1);
                java.io.ByteArrayOutputStream bo = new java.io.ByteArrayOutputStream();
                out.compress(Bitmap.CompressFormat.JPEG, 95, bo);
                out.recycle();
                String name = "Collage_" + new SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(new Date()) + ".jpg";
                ok = a.pops.saveFile(bo.toByteArray(), name, "image/jpeg", "Pictures/Vidsic/");
            } catch (Throwable e) { }
            final boolean res = ok;
            PhotoViewer.H.post(() -> {
                saving = false;
                loading.setText("Loading...");
                loading.setVisibility(View.GONE);
                if (res) {
                    a.toast("Collage saved in Pictures/Vidsic");
                    close();
                    a.loadMedia();
                } else a.pops.saveFailed("the collage", this::doSave);
            });
        });
    }

    class CView extends View {
        final RectF area = new RectF();

        CView(Context c) { super(c); }

        void place() {
            float d = getResources().getDisplayMetrics().density;
            float top = 64 * d, bottom = 215 * d, pad = 12 * d;
            float aw = getWidth() - 2 * pad, ah = getHeight() - top - bottom;
            float r = SHAPE_R[shape], w = aw, h = aw / r;
            if (h > ah) { h = ah; w = ah * r; }
            float x = (getWidth() - w) / 2f, y = top + (ah - h) / 2f;
            area.set(x, y, x + w, y + h);
        }

        @Override protected void onDraw(Canvas c) {
            if (bmps.size() < 2) return;
            place();
            c.save();
            c.translate(area.left, area.top);
            render(c, area.width(), area.height(), selected);
            c.restore();
        }

        @Override public boolean onTouchEvent(MotionEvent e) {
            if (e.getActionMasked() != MotionEvent.ACTION_UP || bmps.size() < 2) return true;
            place();
            float x = e.getX() - area.left, y = e.getY() - area.top;
            List<RectF> rs = cellRects(area.width(), area.height());
            for (int i = 0; i < rs.size(); i++) {
                if (!rs.get(i).contains(x, y)) continue;
                if (selected < 0) selected = i;
                else if (selected == i) selected = -1;
                else {
                    Collections.swap(bmps, selected, i);
                    Collections.swap(items, selected, i);
                    selected = -1;
                }
                invalidate();
                break;
            }
            return true;
        }
    }
}
