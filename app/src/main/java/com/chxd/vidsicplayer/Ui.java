package com.chxd.vidsicplayer;

import android.app.Dialog;
import android.content.Context;
import android.content.res.ColorStateList;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.os.SystemClock;
import android.view.View;
import android.graphics.Typeface;
import android.graphics.drawable.ColorDrawable;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.RippleDrawable;
import android.os.Handler;
import android.os.Looper;
import android.text.TextUtils;
import android.util.LruCache;
import android.util.Size;
import android.view.Gravity;
import android.view.Window;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.content.res.Configuration;
import android.graphics.Color;
import android.media.ThumbnailUtils;
import android.os.Build;
import java.io.File;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

final class Ui {
    static int BG = 0xFF0E0E13, SURF = 0xFF1A1A22, SURF2 = 0xFF2A2A36, ACC = 0xFF8B6BFF, ONACC = 0xFFFFFFFF,
            TXT = 0xFFFFFFFF, SUB = 0xFF9A9AAD, RIP = 0x33FFFFFF;
    static boolean LIGHT = false;
    static final int RED = 0xFFFF5C5C;
    static final int MATCH = ViewGroup.LayoutParams.MATCH_PARENT, WRAP = ViewGroup.LayoutParams.WRAP_CONTENT;
    static final LruCache<String, Bitmap> CACHE = new LruCache<>(80);
    static final ExecutorService EX = Executors.newFixedThreadPool(2);
    static final Handler H = new Handler(Looper.getMainLooper());

    static double lum(int c) { return (0.299 * Color.red(c) + 0.587 * Color.green(c) + 0.114 * Color.blue(c)) / 255.0; }

    static int dialogTheme() {
        return LIGHT ? android.R.style.Theme_Material_Light_Dialog_Alert : android.R.style.Theme_Material_Dialog_Alert;
    }

    /** theme: app (default purple/dark) | device (Material You, Android 12+) | custom (base + accent). */
    static void apply(Context c, Store s) {
        String t = s.p.getString("theme", "app");
        boolean night = (c.getResources().getConfiguration().uiMode & Configuration.UI_MODE_NIGHT_MASK) == Configuration.UI_MODE_NIGHT_YES;
        int acc = 0xFF8B6BFF;
        String base = "dark";
        if (t.equals("custom")) {
            acc = s.p.getInt("c_acc", acc);
            base = s.p.getString("c_base", "dark");
        } else if (t.equals("device") && Build.VERSION.SDK_INT >= 31) {
            LIGHT = !night;
            if (night) {
                ACC = c.getColor(android.R.color.system_accent1_200);
                ONACC = c.getColor(android.R.color.system_accent1_800);
                BG = c.getColor(android.R.color.system_neutral1_900);
                SURF = c.getColor(android.R.color.system_neutral1_800);
                SURF2 = c.getColor(android.R.color.system_neutral2_700);
                TXT = c.getColor(android.R.color.system_neutral1_50);
                SUB = c.getColor(android.R.color.system_neutral2_300);
                RIP = 0x33FFFFFF;
            } else {
                ACC = c.getColor(android.R.color.system_accent1_600);
                ONACC = c.getColor(android.R.color.system_accent1_0);
                BG = c.getColor(android.R.color.system_neutral1_10);
                SURF = c.getColor(android.R.color.system_neutral1_0);
                SURF2 = c.getColor(android.R.color.system_neutral2_100);
                TXT = c.getColor(android.R.color.system_neutral1_900);
                SUB = c.getColor(android.R.color.system_neutral2_600);
                RIP = 0x22000000;
            }
            return;
        }
        LIGHT = base.equals("light");
        boolean black = base.equals("black");
        if (LIGHT) {
            BG = 0xFFF6F5FA; SURF = 0xFFFFFFFF; SURF2 = 0xFFE6E4EE; TXT = 0xFF15141A; SUB = 0xFF6B6A78; RIP = 0x22000000;
        } else {
            BG = black ? 0xFF000000 : 0xFF0E0E13;
            SURF = black ? 0xFF0C0C0C : 0xFF1A1A22;
            SURF2 = black ? 0xFF1E1E1E : 0xFF2A2A36;
            TXT = 0xFFFFFFFF; SUB = 0xFF9A9AAD; RIP = 0x33FFFFFF;
        }
        ACC = acc;
        ONACC = lum(acc) > 0.6 ? 0xFF111111 : 0xFFFFFFFF;
    }

    static int dp(Context c, float v) { return Math.round(v * c.getResources().getDisplayMetrics().density); }

    static GradientDrawable shape(int color, float r, Context c) {
        GradientDrawable g = new GradientDrawable();
        g.setColor(color);
        g.setCornerRadius(dp(c, r));
        return g;
    }

    static Drawable rip(int color, float r, Context c) {
        GradientDrawable m = new GradientDrawable();
        m.setColor(0xFFFFFFFF);
        m.setCornerRadius(dp(c, r));
        return new RippleDrawable(ColorStateList.valueOf(RIP), shape(color, r, c), m);
    }

    static ImageView icon(Context c, int res, int tint, int size) {
        ImageView v = new ImageView(c);
        v.setImageResource(res);
        v.setColorFilter(tint);
        v.setLayoutParams(new LinearLayout.LayoutParams(dp(c, size), dp(c, size)));
        return v;
    }

    static ImageView btn(Context c, int res, int tint, int size, int pad) {
        ImageView v = new ImageView(c);
        v.setImageResource(res);
        v.setColorFilter(tint);
        int p = dp(c, pad);
        v.setPadding(p, p, p, p);
        v.setBackground(rip(0, 100, c));
        int s = dp(c, size + 2 * pad);
        v.setLayoutParams(new LinearLayout.LayoutParams(s, s));
        return v;
    }

    static TextView text(Context c, String s, float sp, int col, boolean bold) {
        TextView t = new TextView(c);
        t.setText(s);
        t.setTextSize(sp);
        t.setTextColor(col);
        if (bold) t.setTypeface(Typeface.DEFAULT_BOLD);
        t.setSingleLine(true);
        t.setEllipsize(TextUtils.TruncateAt.END);
        return t;
    }

    static String fmt(long ms) {
        long s = Math.max(0, ms) / 1000, h = s / 3600, m = (s % 3600) / 60;
        return h > 0 ? String.format(Locale.US, "%d:%02d:%02d", h, m, s % 60)
                : String.format(Locale.US, "%d:%02d", m, s % 60);
    }

    static FrameLayout thumbBox(Context c, Media m, int size) {
        FrameLayout f = new FrameLayout(c);
        f.setBackground(shape(SURF2, 8, c));
        f.setClipToOutline(true);
        ImageView ic = new ImageView(c);
        ic.setImageResource(m.video ? R.drawable.ic_movie : R.drawable.ic_music);
        ic.setColorFilter(SUB);
        int p = dp(c, size / 4f);
        ic.setPadding(p, p, p, p);
        f.addView(ic, new FrameLayout.LayoutParams(MATCH, MATCH));
        ImageView t = new ImageView(c);
        t.setScaleType(ImageView.ScaleType.CENTER_CROP);
        f.addView(t, new FrameLayout.LayoutParams(MATCH, MATCH));
        thumb(c, m, t, dp(c, size));
        return f;
    }

    static Bitmap loadBitmap(Context c, Media m, int px) throws Exception {
        if (m.filePath != null) {
            File f = new File(m.filePath);
            return m.video ? ThumbnailUtils.createVideoThumbnail(f, new Size(px, px), null)
                    : ThumbnailUtils.createAudioThumbnail(f, new Size(px, px), null);
        }
        return c.getContentResolver().loadThumbnail(m.uri, new Size(px, px), null);
    }

    static void thumb(Context c, Media m, ImageView iv, int px) {
        final String key = m.key();
        final String ck = key + "@" + px;
        iv.setTag(key);
        Bitmap b = CACHE.get(ck);
        if (b != null) { iv.setImageBitmap(b); return; }
        EX.execute(() -> {
            try {
                final Bitmap bm = loadBitmap(c, m, px);
                if (bm == null) return;
                CACHE.put(ck, bm);
                H.post(() -> { if (key.equals(iv.getTag())) iv.setImageBitmap(bm); });
            } catch (Exception e) { }
        });
    }

    /** Equalizer-style bars: animated while "live", resting bars otherwise. */
    static class Eq extends View {
        final Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
        final boolean live;

        Eq(Context c, int color, boolean live) {
            super(c);
            p.setColor(color);
            this.live = live;
        }

        @Override
        protected void onDraw(Canvas cv) {
            int w = getWidth(), h = getHeight(), n = 4;
            float bw = w / (n * 2f - 1f);
            double t = SystemClock.uptimeMillis() / 170.0;
            for (int i = 0; i < n; i++) {
                float f = live ? (float) (0.22 + 0.78 * (0.5 + 0.5 * Math.sin(t * (1 + 0.37 * i) + i * 1.9))) : 0.3f + 0.1f * (i % 2);
                float bh = h * f, x = i * 2 * bw;
                cv.drawRoundRect(x, h - bh, x + bw, h, bw / 2, bw / 2, p);
            }
            if (live) postInvalidateOnAnimation();
        }
    }

    /** Filled / empty selection circle with a tick. */
    static View check(Context c, boolean on) {
        FrameLayout f = new FrameLayout(c);
        GradientDrawable g = new GradientDrawable();
        g.setShape(GradientDrawable.OVAL);
        if (on) g.setColor(ACC); else { g.setColor(0); g.setStroke(dp(c, 2), SUB); }
        f.setBackground(g);
        if (on) {
            ImageView i = new ImageView(c);
            i.setImageResource(R.drawable.ic_check);
            i.setColorFilter(ONACC);
            int p = dp(c, 3);
            i.setPadding(p, p, p, p);
            f.addView(i, new FrameLayout.LayoutParams(MATCH, MATCH));
        }
        f.setLayoutParams(new LinearLayout.LayoutParams(dp(c, 22), dp(c, 22)));
        return f;
    }

    /** Radio button. */
    static class Radio extends FrameLayout {
        final View dot;
        final GradientDrawable ring = new GradientDrawable();

        Radio(Context c, boolean on) {
            super(c);
            ring.setShape(GradientDrawable.OVAL);
            ring.setColor(0);
            setBackground(ring);
            dot = new View(c);
            dot.setBackground(shape(ACC, 100, c));
            addView(dot, new FrameLayout.LayoutParams(dp(c, 10), dp(c, 10), Gravity.CENTER));
            setLayoutParams(new LinearLayout.LayoutParams(dp(c, 20), dp(c, 20)));
            set(on);
        }

        void set(boolean on) {
            ring.setStroke(dp(getContext(), 2), on ? ACC : SUB);
            dot.setVisibility(on ? View.VISIBLE : View.INVISIBLE);
        }
    }

    /** Bottom-sheet modal. */
    static class Sheet {
        final Dialog d;
        final LinearLayout box;
        final Context c;

        Sheet(Context c, String title, String sub) {
            this.c = c;
            d = new Dialog(c);
            d.requestWindowFeature(Window.FEATURE_NO_TITLE);
            box = new LinearLayout(c);
            box.setOrientation(LinearLayout.VERTICAL);
            box.setPadding(0, dp(c, 12), 0, dp(c, 16));
            GradientDrawable g = new GradientDrawable();
            g.setColor(SURF);
            float r = dp(c, 20);
            g.setCornerRadii(new float[]{r, r, r, r, 0, 0, 0, 0});
            box.setBackground(g);
            if (title != null) {
                LinearLayout h = new LinearLayout(c);
                h.setOrientation(LinearLayout.VERTICAL);
                h.setPadding(dp(c, 20), dp(c, 4), dp(c, 20), dp(c, 12));
                h.addView(text(c, title, 17, TXT, true));
                if (sub != null) h.addView(text(c, sub, 13, SUB, false));
                box.addView(h, new LinearLayout.LayoutParams(MATCH, WRAP));
            }
            ScrollView sv = new ScrollView(c);
            sv.addView(box);
            d.setContentView(sv);
        }

        Sheet item(int icon, String label, int color, Runnable r) {
            LinearLayout row = new LinearLayout(c);
            row.setGravity(Gravity.CENTER_VERTICAL);
            row.setPadding(dp(c, 20), 0, dp(c, 20), 0);
            row.setMinimumHeight(dp(c, 52));
            row.setBackground(rip(0, 0, c));
            row.addView(icon(c, icon, color, 22));
            TextView t = text(c, label, 16, color, false);
            t.setPadding(dp(c, 16), 0, 0, 0);
            row.addView(t);
            row.setOnClickListener(v -> { d.dismiss(); if (r != null) r.run(); });
            box.addView(row, new LinearLayout.LayoutParams(MATCH, WRAP));
            return this;
        }

        void show() {
            d.show();
            Window w = d.getWindow();
            w.setBackgroundDrawable(new ColorDrawable(0));
            w.setGravity(Gravity.BOTTOM);
            w.setLayout(MATCH, WRAP);
        }
    }
}
