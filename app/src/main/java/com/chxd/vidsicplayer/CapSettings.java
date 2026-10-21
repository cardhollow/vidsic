package com.chxd.vidsicplayer;

import android.app.Dialog;
import android.content.Context;
import android.content.SharedPreferences;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Typeface;
import android.graphics.drawable.ColorDrawable;
import android.graphics.drawable.GradientDrawable;
import android.text.TextPaint;
import android.view.Gravity;
import android.view.View;
import android.view.Window;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.SeekBar;
import android.widget.Switch;
import android.widget.TextView;
import java.util.ArrayList;
import java.util.Locale;
import java.util.function.IntFunction;

/**
 * Caption style (all keys start with "cc_" in the app preferences; the defaults are the look the player always had)
 * and the "Caption settings" screen that edits it with a live preview.
 */
class CapSettings {
    static final int[] TEXT_COLORS = {0xFFFFFFFF, 0xFFFFEB3B, 0xFF00E5FF, 0xFF69F0AE, 0xFFFF80AB, 0xFFFF5252, 0xFF000000};
    static final int[] EDGE_COLORS = {0xFF000000, 0xFFFFFFFF, 0xFF1A237E, 0xFFB71C1C, 0xFF1B5E20, 0xFFFFEB3B};
    static final int[] BG_COLORS = {0xFF000000, 0xFF202028, 0xFF1B2A4A, 0xFF3A1B1B, 0xFF1B3A2A, 0xFFFFFFFF};

    /** TextView that can draw an outline around its letters. */
    static class CapText extends TextView {
        float outW;
        int outCol = 0xFF000000;
        boolean drawing;

        CapText(Context c) { super(c); }

        @Override public void invalidate() { if (!drawing) super.invalidate(); } // setTextColor() inside onDraw must not loop

        @Override
        protected void onDraw(Canvas cv) {
            if (outW <= 0) { super.onDraw(cv); return; }
            drawing = true;
            int fg = getCurrentTextColor();
            TextPaint pt = getPaint();
            pt.setStyle(Paint.Style.STROKE);
            pt.setStrokeJoin(Paint.Join.ROUND);
            pt.setStrokeWidth(outW);
            setTextColor(outCol);
            super.onDraw(cv);
            pt.setStyle(Paint.Style.FILL);
            setTextColor(fg);
            super.onDraw(cv);
            drawing = false;
        }
    }

    /** Puts the saved style on a caption TextView (the real one in the player, or the preview). */
    static void apply(TextView tv, SharedPreferences p, Context c) {
        float d = c.getResources().getDisplayMetrics().density;
        tv.setTextSize(p.getInt("cc_size", 17));
        int font = p.getInt("cc_font", 0);
        boolean bold = p.getBoolean("cc_bold", false), it = p.getBoolean("cc_italic", false);
        Typeface base = font == 1 ? Typeface.SANS_SERIF : font == 2 ? Typeface.SERIF : font == 3 ? Typeface.MONOSPACE : Typeface.DEFAULT;
        tv.setTypeface(Typeface.create(base, bold && it ? Typeface.BOLD_ITALIC : bold ? Typeface.BOLD : it ? Typeface.ITALIC : Typeface.NORMAL));
        int col = p.getInt("cc_color", 0xFFFFFFFF), op = p.getInt("cc_opac", 100);
        tv.setTextColor((col & 0x00FFFFFF) | ((op * 255 / 100) << 24));
        int al = p.getInt("cc_align", 1);
        tv.setGravity(al == 0 ? Gravity.START : al == 2 ? Gravity.END : Gravity.CENTER_HORIZONTAL);
        tv.setLetterSpacing(p.getInt("cc_ltr", 0) / 100f);
        tv.setLineSpacing(0, p.getInt("cc_lsp", 110) / 100f);
        tv.setMaxLines(p.getInt("cc_lines", 4));

        int edge = p.getInt("cc_edge", 0), ecol = p.getInt("cc_ecol", 0xFF000000), esz = p.getInt("cc_esz", 2);
        if (tv instanceof CapText) { ((CapText) tv).outW = edge == 1 ? esz * 2 * d : 0; ((CapText) tv).outCol = ecol; }
        if (edge == 2) tv.setShadowLayer(esz * d * 1.5f, esz * d, esz * d, ecol); else tv.setShadowLayer(0, 0, 0, 0);

        GradientDrawable g = new GradientDrawable();
        g.setColor((p.getInt("cc_bgcol", 0xFF000000) & 0x00FFFFFF) | ((p.getInt("cc_bgop", 70) * 255 / 100) << 24));
        g.setCornerRadius(p.getInt("cc_rad", 8) * d);
        tv.setBackground(g);
        tv.setPadding(Ui.dp(c, 12), Ui.dp(c, 5), Ui.dp(c, 12), Ui.dp(c, 6));
        tv.invalidate();
    }

    static void show(MainActivity a, Runnable onChange) { new CapSettings(a, onChange).open(); }

    // ---------- the screen ----------
    final MainActivity a;
    final SharedPreferences p;
    final Runnable onChange;
    Dialog d;
    CapText preview;

    CapSettings(MainActivity act, Runnable change) {
        a = act;
        p = act.store.p;
        onChange = change;
    }

    void changed() {
        if (preview != null) apply(preview, p, a);
        if (onChange != null) onChange.run();
    }

    void put(String key, int v) { p.edit().putInt(key, v).apply(); changed(); }

    void open() {
        d = new Dialog(a);
        d.requestWindowFeature(Window.FEATURE_NO_TITLE);
        LinearLayout root = new LinearLayout(a);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(Ui.BG);
        root.setFitsSystemWindows(true);

        LinearLayout head = new LinearLayout(a);
        head.setGravity(Gravity.CENTER_VERTICAL);
        head.setPadding(Ui.dp(a, 8), Ui.dp(a, 4), Ui.dp(a, 8), Ui.dp(a, 4));
        ImageView x = Ui.btn(a, R.drawable.ic_close, Ui.TXT, 24, 10);
        x.setOnClickListener(v -> d.dismiss());
        head.addView(x);
        TextView t = Ui.text(a, "Caption settings", 18, Ui.TXT, true);
        t.setPadding(Ui.dp(a, 8), 0, 0, 0);
        head.addView(t, new LinearLayout.LayoutParams(0, Ui.WRAP, 1));
        root.addView(head, new LinearLayout.LayoutParams(Ui.MATCH, Ui.WRAP));

        FrameLayout pv = new FrameLayout(a);
        pv.setBackgroundColor(0xFF2B2F3A);
        preview = new CapText(a);
        preview.setText("This is how captions look.\nSecond line of the sample.");
        apply(preview, p, a);
        pv.addView(preview, new FrameLayout.LayoutParams(Ui.WRAP, Ui.WRAP, Gravity.CENTER));
        root.addView(pv, new LinearLayout.LayoutParams(Ui.MATCH, Ui.dp(a, 120)));

        ScrollView sv = new ScrollView(a);
        LinearLayout col = new LinearLayout(a);
        col.setOrientation(LinearLayout.VERTICAL);
        col.setPadding(0, 0, 0, Ui.dp(a, 28));
        sv.addView(col);
        root.addView(sv, new LinearLayout.LayoutParams(Ui.MATCH, 0, 1));

        LinearLayout c = section(col, "GENERAL");
        toggle(c, "Show captions", "caps_on", true);
        button(c, R.drawable.ic_add, "Load external subtitle file", () -> { d.dismiss(); if (a.engine.cur() != null) a.pickCaptions(a.engine.cur()); });
        button(c, R.drawable.ic_restore, "Reset caption settings", () ->
                a.confirmDanger("Reset caption settings?", "Text, effects, background, position and delay go back to the defaults.", "Reset", () -> {
                    SharedPreferences.Editor e = p.edit();
                    for (String k : new ArrayList<>(p.getAll().keySet())) if (k.startsWith("cc_")) e.remove(k);
                    e.apply();
                    changed();
                    d.dismiss();
                    open();
                }));

        c = section(col, "TEXT");
        choice(c, "Font", "cc_font", 0, "Default", "Sans", "Serif", "Mono");
        slider(c, "Size", "cc_size", 17, 12, 40, 1, v -> v + " sp");
        toggle(c, "Bold", "cc_bold", false);
        toggle(c, "Italic", "cc_italic", false);
        color(c, "Text color", "cc_color", 0xFFFFFFFF, TEXT_COLORS);
        slider(c, "Text opacity", "cc_opac", 100, 20, 100, 5, v -> v + " %");
        choice(c, "Alignment", "cc_align", 1, "Left", "Center", "Right");
        slider(c, "Line spacing", "cc_lsp", 110, 100, 200, 5, v -> String.format(Locale.US, "%.2fx", v / 100f));
        slider(c, "Letter spacing", "cc_ltr", 0, 0, 20, 1, v -> String.format(Locale.US, "%.2f em", v / 100f));

        c = section(col, "EFFECTS");
        choice(c, "Edge style", "cc_edge", 0, "None", "Outline", "Shadow");
        color(c, "Edge color", "cc_ecol", 0xFF000000, EDGE_COLORS);
        slider(c, "Edge thickness", "cc_esz", 2, 1, 6, 1, v -> String.valueOf(v));

        c = section(col, "BACKGROUND");
        color(c, "Background color", "cc_bgcol", 0xFF000000, BG_COLORS);
        slider(c, "Background opacity", "cc_bgop", 70, 0, 100, 5, v -> v + " %");
        slider(c, "Corner radius", "cc_rad", 8, 0, 24, 1, v -> v + " dp");

        c = section(col, "POSITION");
        choice(c, "Caption position", "cc_pos", 2, "Top", "Middle", "Bottom");
        slider(c, "Vertical offset", "cc_off", 0, 0, 200, 5, v -> v + " dp");
        slider(c, "Maximum width", "cc_wid", 100, 40, 100, 5, v -> v + " %");
        slider(c, "Maximum lines", "cc_lines", 4, 1, 6, 1, v -> String.valueOf(v));

        c = section(col, "SYNCHRONIZATION");
        slider(c, "Subtitle delay", "cc_delay", 0, -5000, 5000, 100,
                v -> v == 0 ? "In sync" : (v > 0 ? "+" : "\u2212") + Math.abs(v) + " ms");
        button(c, R.drawable.ic_restore, "Reset synchronization", () -> { put("cc_delay", 0); d.dismiss(); open(); });
        TextView n = Ui.text(a, "Positive delay shows captions later, negative shows them earlier.", 12, Ui.SUB, false);
        n.setSingleLine(false);
        n.setPadding(Ui.dp(a, 16), Ui.dp(a, 4), Ui.dp(a, 16), Ui.dp(a, 12));
        add(c, n);

        d.setContentView(root);
        d.show();
        Window w = d.getWindow();
        w.setBackgroundDrawable(new ColorDrawable(Ui.BG));
        w.setLayout(Ui.MATCH, Ui.MATCH);
    }

    // ---------- row builders ----------
    LinearLayout section(LinearLayout col, String title) {
        TextView v = Ui.text(a, title, 13, Ui.ACC, true);
        v.setPadding(Ui.dp(a, 20), Ui.dp(a, 20), Ui.dp(a, 16), Ui.dp(a, 8));
        col.addView(v);
        LinearLayout card = new LinearLayout(a);
        card.setOrientation(LinearLayout.VERTICAL);
        card.setBackground(Ui.shape(Ui.SURF, 16, a));
        card.setClipToOutline(true);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(Ui.MATCH, Ui.WRAP);
        lp.setMargins(Ui.dp(a, 16), 0, Ui.dp(a, 16), 0);
        col.addView(card, lp);
        return card;
    }

    void add(LinearLayout card, View v) {
        if (card.getChildCount() > 0) {
            View line = new View(a);
            line.setBackgroundColor(Ui.SURF2);
            card.addView(line, new LinearLayout.LayoutParams(Ui.MATCH, 1));
        }
        card.addView(v, new LinearLayout.LayoutParams(Ui.MATCH, Ui.WRAP));
    }

    LinearLayout box() {
        LinearLayout b = new LinearLayout(a);
        b.setOrientation(LinearLayout.VERTICAL);
        b.setPadding(Ui.dp(a, 16), Ui.dp(a, 12), Ui.dp(a, 16), Ui.dp(a, 12));
        return b;
    }

    TextView label(String s) { return Ui.text(a, s, 15, Ui.TXT, false); }

    void toggle(LinearLayout card, String title, final String key, boolean def) {
        LinearLayout row = new LinearLayout(a);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding(Ui.dp(a, 16), Ui.dp(a, 10), Ui.dp(a, 16), Ui.dp(a, 10));
        row.addView(label(title), new LinearLayout.LayoutParams(0, Ui.WRAP, 1));
        Switch sw = new Switch(a);
        Ui.tintSwitch(sw);
        sw.setChecked(p.getBoolean(key, def));
        sw.setOnCheckedChangeListener((b, on) -> { p.edit().putBoolean(key, on).apply(); changed(); });
        row.addView(sw);
        add(card, row);
    }

    void button(LinearLayout card, int icon, String text, Runnable r) {
        LinearLayout row = new LinearLayout(a);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding(Ui.dp(a, 16), Ui.dp(a, 14), Ui.dp(a, 16), Ui.dp(a, 14));
        row.setBackground(Ui.rip(0, 0, a));
        row.addView(Ui.icon(a, icon, Ui.ACC, 22));
        TextView t = Ui.text(a, text, 15, Ui.TXT, false);
        t.setPadding(Ui.dp(a, 16), 0, 0, 0);
        row.addView(t);
        row.setOnClickListener(v -> r.run());
        add(card, row);
    }

    void choice(LinearLayout card, String title, final String key, int def, String... labels) {
        LinearLayout b = box();
        b.addView(label(title));
        LinearLayout r = new LinearLayout(a);
        r.setPadding(0, Ui.dp(a, 8), 0, 0);
        final TextView[] tv = new TextView[labels.length];
        for (int i = 0; i < labels.length; i++) {
            final int v = i;
            TextView t = Ui.text(a, labels[i], 13, Ui.TXT, true);
            t.setGravity(Gravity.CENTER);
            t.setSingleLine(true);
            t.setPadding(Ui.dp(a, 6), Ui.dp(a, 9), Ui.dp(a, 6), Ui.dp(a, 9));
            tv[i] = t;
            t.setOnClickListener(x -> { put(key, v); paint(tv, v); });
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0, Ui.WRAP, 1);
            lp.setMargins(0, 0, Ui.dp(a, 6), 0);
            r.addView(t, lp);
        }
        paint(tv, p.getInt(key, def));
        b.addView(r);
        add(card, b);
    }

    void paint(TextView[] tv, int sel) {
        for (int i = 0; i < tv.length; i++) {
            tv[i].setBackground(Ui.rip(i == sel ? Ui.ACC : Ui.SURF2, 18, a));
            tv[i].setTextColor(i == sel ? Ui.ONACC : Ui.TXT);
        }
    }

    void slider(LinearLayout card, String title, final String key, int def, final int min, int max, final int step, final IntFunction<String> fmt) {
        LinearLayout b = box();
        LinearLayout top = new LinearLayout(a);
        top.addView(label(title), new LinearLayout.LayoutParams(0, Ui.WRAP, 1));
        final TextView val = Ui.text(a, "", 14, Ui.ACC, true);
        top.addView(val);
        b.addView(top);
        SeekBar sb = new SeekBar(a);
        Ui.tintSeek(sb);
        sb.setMax((max - min) / step);
        int cur = p.getInt(key, def);
        sb.setProgress((cur - min) / step);
        val.setText(fmt.apply(cur));
        sb.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            public void onProgressChanged(SeekBar s, int pr, boolean user) {
                int v = min + pr * step;
                val.setText(fmt.apply(v));
                if (user) put(key, v);
            }
            public void onStartTrackingTouch(SeekBar s) { }
            public void onStopTrackingTouch(SeekBar s) { }
        });
        b.addView(sb, new LinearLayout.LayoutParams(Ui.MATCH, Ui.WRAP));
        add(card, b);
    }

    void color(LinearLayout card, String title, final String key, int def, final int[] cols) {
        LinearLayout b = box();
        b.addView(label(title));
        LinearLayout r = new LinearLayout(a);
        final View[] sw = new View[cols.length];
        for (int i = 0; i < cols.length; i++) {
            final int c = cols[i];
            View v = new View(a);
            sw[i] = v;
            v.setOnClickListener(x -> { put(key, c); paintSw(sw, cols, c); });
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(Ui.dp(a, 32), Ui.dp(a, 32));
            lp.setMargins(0, Ui.dp(a, 8), Ui.dp(a, 8), 0);
            r.addView(v, lp);
        }
        paintSw(sw, cols, p.getInt(key, def));
        b.addView(r);
        add(card, b);
    }

    void paintSw(View[] sw, int[] cols, int cur) {
        for (int i = 0; i < sw.length; i++) {
            boolean on = cols[i] == cur;
            GradientDrawable g = new GradientDrawable();
            g.setShape(GradientDrawable.OVAL);
            g.setColor(cols[i]);
            g.setStroke(Ui.dp(a, on ? 3 : 1), on ? Ui.ACC : 0x66808080);
            sw[i].setBackground(g);
        }
    }
}
