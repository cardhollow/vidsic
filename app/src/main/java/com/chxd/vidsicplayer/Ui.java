package com.chxd.vidsicplayer;

import android.app.Dialog;
import android.content.Context;
import android.content.res.ColorStateList;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.LinearGradient;
import android.graphics.Paint;
import android.graphics.RectF;
import android.graphics.Shader;
import android.text.InputType;
import android.view.WindowManager;
import android.view.inputmethod.EditorInfo;
import android.widget.EditText;
import java.util.function.Consumer;
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

    /** Equalizer-style bars: animated while "live", resting bars otherwise. Follows the real audio when "Audio Spectrum Sync" is on. */
    static class Eq extends View {
        final Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
        final boolean live;
        final Spectrum sp;
        final boolean sync;

        Eq(Context c, int color, boolean live) {
            super(c);
            p.setColor(color);
            this.live = live;
            Engine en = Engine.get(c);
            sp = en.spec;
            sync = en.store.bool("spec_sync", false);
        }

        @Override
        protected void onDraw(Canvas cv) {
            int w = getWidth(), h = getHeight(), n = 4;
            float bw = w / (n * 2f - 1f);
            double t = SystemClock.uptimeMillis() / 170.0;
            boolean real = live && sync && sp.live();
            if (real) sp.poll();
            for (int i = 0; i < n; i++) {
                float f;
                if (real) f = 0.14f + 0.86f * Math.min(1f, sp.level(i, n) * 1.15f);
                else if (live) f = (float) (0.22 + 0.78 * (0.5 + 0.5 * Math.sin(t * (1 + 0.37 * i) + i * 1.9)));
                else f = 0.3f + 0.1f * (i % 2);
                float bh = h * f, x = i * 2 * bw;
                cv.drawRoundRect(x, h - bh, x + bw, h, bw / 2, bw / 2, p);
            }
            if (live) postInvalidateOnAnimation();
        }
    }

    /** Big mirrored spectrum for the full-screen player ("Audio Player Spectrum"). Real FFT if possible, simulated otherwise. */
    static class SpectrumView extends View {
        static final int N = Spectrum.BANDS;
        final Engine en;
        final Paint bar = new Paint(Paint.ANTI_ALIAS_FLAG), cap = new Paint(Paint.ANTI_ALIAS_FLAG), base = new Paint(Paint.ANTI_ALIAS_FLAG);
        final float[] val = new float[N], peak = new float[N];
        final RectF r = new RectF();

        SpectrumView(Context c) {
            super(c);
            en = Engine.get(c);
            cap.setColor(TXT);
            base.setColor(SUB);
            base.setAlpha(70);
        }

        @Override
        protected void onSizeChanged(int w, int h, int ow, int oh) {
            int light = Color.argb(255, (Color.red(ACC) + 255 * 2) / 3 , (Color.green(ACC) + 255 * 2) / 3, (Color.blue(ACC) + 255 * 2) / 3);
            bar.setShader(new LinearGradient(0, 0, 0, h, new int[]{light, ACC, ACC, light}, new float[]{0f, 0.38f, 0.62f, 1f}, Shader.TileMode.CLAMP));
        }

        @Override
        protected void onDraw(Canvas cv) {
            int w = getWidth(), h = getHeight();
            if (w == 0 || h == 0) return;
            boolean playing = en.isPlaying();
            boolean real = en.spec.live();
            if (real) en.spec.poll();
            double t = SystemClock.uptimeMillis() / 1000.0;
            boolean moving = false;
            float pad = dp(getContext(), 14), usable = w - 2 * pad;
            float slot = usable / N, bw = slot * 0.62f, mid = h / 2f, maxHalf = h / 2f - pad;
            float minHalf = dp(getContext(), 2);
            for (int i = 0; i < N; i++) {
                float target;
                if (!playing) target = 0;
                else if (real) target = Math.min(1f, en.spec.bands[i] * 1.1f);
                else {
                    float env = 1f - 0.55f * i / N;
                    double a = 0.5 + 0.5 * Math.sin(t * 2.3 + i * 0.7), b = 0.5 + 0.5 * Math.sin(t * 3.7 - i * 1.3 + 1.7);
                    target = (float) Math.max(0.05, env * (0.15 + 0.85 * a * b));
                }
                val[i] = target > val[i] ? val[i] + (target - val[i]) * 0.6f : val[i] * 0.88f + target * 0.12f;
                if (val[i] > 0.01f) moving = true;
                peak[i] = Math.max(val[i], peak[i] - 0.012f);
                if (peak[i] > 0.01f) moving = true;
                float half = minHalf + val[i] * (maxHalf - minHalf);
                float x = pad + i * slot + (slot - bw) / 2f;
                r.set(x, mid - half, x + bw, mid + half);
                cv.drawRoundRect(r, bw / 2f, bw / 2f, bar);
                if (peak[i] > val[i] + 0.04f) {
                    float ph = minHalf + peak[i] * (maxHalf - minHalf) + bw * 0.9f;
                    cap.setAlpha(190);
                    cv.drawCircle(x + bw / 2f, mid - ph, bw * 0.28f, cap);
                    cv.drawCircle(x + bw / 2f, mid + ph, bw * 0.28f, cap);
                }
            }
            if (playing || moving) postInvalidateOnAnimation();
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

    // ---------- centered modals (replace the native Android alert dialogs) ----------
    private static Dialog modal(Context c, LinearLayout box) {
        final Dialog d = new Dialog(c);
        d.requestWindowFeature(Window.FEATURE_NO_TITLE);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setBackground(shape(SURF, 20, c));
        box.setPadding(0, dp(c, 22), 0, dp(c, 14));
        d.setContentView(box);
        return d;
    }

    private static void showModal(Context c, Dialog d, boolean keyboard) {
        d.show();
        Window w = d.getWindow();
        w.setBackgroundDrawable(new ColorDrawable(0));
        w.setDimAmount(0.6f);
        int sw = c.getResources().getDisplayMetrics().widthPixels;
        w.setLayout(Math.min(dp(c, 340), sw * 9 / 10), ViewGroup.LayoutParams.WRAP_CONTENT);
        if (keyboard) w.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_STATE_VISIBLE | WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE);
    }

    private static TextView modalButton(Context c, String label, int fg, int bg) {
        TextView t = text(c, label, 15, fg, true);
        t.setGravity(Gravity.CENTER);
        t.setPadding(dp(c, 20), dp(c, 10), dp(c, 20), dp(c, 10));
        t.setBackground(rip(bg, 20, c));
        return t;
    }

    private static void modalHead(Context c, LinearLayout box, String title, String msg) {
        TextView t = text(c, title, 19, TXT, true);
        t.setSingleLine(false);
        t.setPadding(dp(c, 24), 0, dp(c, 24), dp(c, 10));
        box.addView(t);
        if (msg != null) {
            TextView m = text(c, msg, 15, SUB, false);
            m.setSingleLine(false);
            m.setEllipsize(null);
            m.setLineSpacing(0, 1.12f);
            m.setPadding(dp(c, 24), 0, dp(c, 24), dp(c, 6));
            box.addView(m);
        }
    }

    private static LinearLayout modalButtons(Context c, LinearLayout box) {
        LinearLayout b = new LinearLayout(c);
        b.setGravity(Gravity.END | Gravity.CENTER_VERTICAL);
        b.setPadding(dp(c, 12), dp(c, 14), dp(c, 16), 0);
        box.addView(b, new LinearLayout.LayoutParams(MATCH, WRAP));
        return b;
    }

    /** Yes / No style confirmation. danger = red confirm button (delete, clear...). */
    static void confirm(Context c, String title, String msg, String ok, boolean danger, final Runnable onOk) {
        confirm(c, title, msg, ok, danger, onOk, null);
    }

    static void confirm(Context c, String title, String msg, String ok, boolean danger, final Runnable onOk, final Runnable onCancel) {
        LinearLayout box = new LinearLayout(c);
        final Dialog d = modal(c, box);
        modalHead(c, box, title, msg);
        LinearLayout b = modalButtons(c, box);
        d.setOnCancelListener(x -> { if (onCancel != null) onCancel.run(); });
        TextView no = modalButton(c, "Cancel", ACC, 0);
        no.setOnClickListener(v -> d.cancel());
        TextView yes = modalButton(c, ok, danger ? 0xFFFFFFFF : ONACC, danger ? RED : ACC);
        yes.setOnClickListener(v -> { d.dismiss(); if (onOk != null) onOk.run(); });
        b.addView(no);
        b.addView(yes);
        showModal(c, d, false);
    }

    /** One text field (playlist name, rename...). */
    static void prompt(Context c, String title, String initial, String hint, String ok, final Consumer<String> done) {
        LinearLayout box = new LinearLayout(c);
        final Dialog d = modal(c, box);
        modalHead(c, box, title, null);
        final EditText et = new EditText(c);
        et.setSingleLine(true);
        et.setHint(hint);
        et.setHintTextColor(SUB);
        et.setTextColor(TXT);
        et.setTextSize(16);
        et.setText(initial);
        et.setSelectAllOnFocus(true);
        et.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_CAP_SENTENCES);
        et.setImeOptions(EditorInfo.IME_ACTION_DONE);
        et.setHighlightColor((ACC & 0x00FFFFFF) | 0x55000000);
        et.setBackground(shape(SURF2, 12, c));
        et.setPadding(dp(c, 14), dp(c, 12), dp(c, 14), dp(c, 12));
        LinearLayout.LayoutParams ep = new LinearLayout.LayoutParams(MATCH, WRAP);
        ep.setMargins(dp(c, 24), dp(c, 4), dp(c, 24), 0);
        box.addView(et, ep);
        LinearLayout b = modalButtons(c, box);
        final Runnable submit = () -> {
            String n = et.getText().toString().trim();
            if (n.isEmpty()) { et.setHintTextColor(RED); return; }
            d.dismiss();
            done.accept(n);
        };
        et.setOnEditorActionListener((v, id, ev) -> { submit.run(); return true; });
        TextView no = modalButton(c, "Cancel", ACC, 0);
        no.setOnClickListener(v -> d.dismiss());
        TextView yes = modalButton(c, ok, ONACC, ACC);
        yes.setOnClickListener(v -> submit.run());
        b.addView(no);
        b.addView(yes);
        showModal(c, d, true);
        et.requestFocus();
    }

    /** Just a message with one button. */
    static void notice(Context c, String title, String msg) {
        LinearLayout box = new LinearLayout(c);
        final Dialog d = modal(c, box);
        modalHead(c, box, title, msg);
        LinearLayout b = modalButtons(c, box);
        TextView ok = modalButton(c, "OK", ONACC, ACC);
        ok.setOnClickListener(v -> d.dismiss());
        b.addView(ok);
        showModal(c, d, false);
    }
}
