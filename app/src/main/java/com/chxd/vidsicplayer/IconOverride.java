package com.chxd.vidsicplayer;

import android.app.Activity;
import android.app.Dialog;
import android.content.Intent;
import android.graphics.drawable.ColorDrawable;
import android.graphics.drawable.Drawable;
import android.net.Uri;
import android.view.Gravity;
import android.view.View;
import android.view.Window;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * The "Advance Drawable" dialog. It lists every ic_*.xml the app has (found by Ic.list, so new icons
 * show up on their own), lets each one be replaced with an uploaded vector or restored, and applies
 * all pending changes at once.
 */
final class IconOverride {
    static final int REQ = 83, REQ_SAVE = 84;
    private static IconOverride cur;

    private final MainActivity a;
    private final List<Ic.Entry> entries;
    /** name -> new xml, or "" meaning "restore the built-in". Nothing is written until Apply. */
    private final Map<String, String> staged = new HashMap<>();
    private final Map<String, String> saved = new HashMap<>();
    private Dialog d;
    private LinearLayout list;
    private ScrollView sc;
    private String pendingName;
    private String exportText;

    private IconOverride(MainActivity a) {
        this.a = a;
        entries = Ic.list(a);
        for (Ic.Entry e : entries) {
            String t = Ic.readSaved(a, e.name);
            if (t != null) saved.put(e.name, t);
        }
    }

    static void open(MainActivity a) {
        cur = new IconOverride(a);
        cur.show();
    }

    static void onPicked(MainActivity a, int res, Intent data) {
        IconOverride o = cur;
        if (o == null || o.pendingName == null) return;
        String name = o.pendingName;
        o.pendingName = null;
        if (res != Activity.RESULT_OK || data == null || data.getData() == null) return;
        o.load(name, data.getData());
    }

    /** The xml an icon currently has, counting changes that are waiting for Apply. Null means built-in. */
    private String effective(String name) {
        if (staged.containsKey(name)) {
            String s = staged.get(name);
            return s.isEmpty() ? null : s;
        }
        return saved.get(name);
    }

    private void show() {
        LinearLayout box = new LinearLayout(a);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setBackground(Ui.shape(Ui.SURF, 20, a));
        box.setPadding(0, Ui.dp(a, 22), 0, Ui.dp(a, 14));

        TextView t = Ui.text(a, "Advance Drawable", 19, Ui.TXT, true);
        t.setPadding(Ui.dp(a, 24), 0, Ui.dp(a, 24), Ui.dp(a, 6));
        box.addView(t);
        TextView m = Ui.text(a, "Change any icon with your own Android vector (.xml). Changes are used after Apply.", 13, Ui.SUB, false);
        m.setSingleLine(false);
        m.setEllipsize(null);
        m.setPadding(Ui.dp(a, 24), 0, Ui.dp(a, 24), Ui.dp(a, 10));
        box.addView(m);

        sc = new ScrollView(a);
        list = new LinearLayout(a);
        list.setOrientation(LinearLayout.VERTICAL);
        sc.addView(list);
        int h = Math.min(Ui.dp(a, 420), a.getResources().getDisplayMetrics().heightPixels * 5 / 10);
        box.addView(sc, new LinearLayout.LayoutParams(Ui.MATCH, h));

        LinearLayout bar = new LinearLayout(a);
        bar.setGravity(Gravity.CENTER_VERTICAL);
        bar.setPadding(Ui.dp(a, 12), Ui.dp(a, 14), Ui.dp(a, 16), 0);
        TextView all = pill("Restore", Ui.ACC, 0);
        all.setOnClickListener(v -> restoreAll());
        bar.addView(all);
        View sp = new View(a);
        bar.addView(sp, new LinearLayout.LayoutParams(0, 1, 1));
        TextView cancel = pill("Cancel", Ui.ACC, 0);
        cancel.setOnClickListener(v -> d.dismiss());
        bar.addView(cancel);
        TextView apply = pill("Apply", Ui.ONACC, Ui.ACC);
        apply.setOnClickListener(v -> apply());
        bar.addView(apply);
        box.addView(bar, new LinearLayout.LayoutParams(Ui.MATCH, Ui.WRAP));

        d = new Dialog(a);
        d.requestWindowFeature(Window.FEATURE_NO_TITLE);
        d.setContentView(box);
        d.setOnDismissListener(x -> { if (cur == this) cur = null; });
        build();
        d.show();
        Window w = d.getWindow();
        w.setBackgroundDrawable(new ColorDrawable(0));
        w.setDimAmount(0.6f);
        int sw = a.getResources().getDisplayMetrics().widthPixels;
        w.setLayout(Math.min(Ui.dp(a, 380), sw * 92 / 100), android.view.ViewGroup.LayoutParams.WRAP_CONTENT);
    }

    private TextView pill(String label, int fg, int bg) {
        TextView t = Ui.text(a, label, 14, fg, true);
        t.setGravity(Gravity.CENTER);
        t.setPadding(Ui.dp(a, 16), Ui.dp(a, 9), Ui.dp(a, 16), Ui.dp(a, 9));
        t.setBackground(Ui.rip(bg, 20, a));
        return t;
    }

    private void build() {
        int y = sc.getScrollY();
        list.removeAllViews();
        for (Ic.Entry e : entries) list.addView(row(e), new LinearLayout.LayoutParams(Ui.MATCH, Ui.WRAP));
        sc.post(() -> sc.scrollTo(0, y));
    }

    private View row(Ic.Entry e) {
        String xml = effective(e.name);
        boolean custom = xml != null;
        LinearLayout row = new LinearLayout(a);
        row.setOrientation(LinearLayout.VERTICAL);
        row.setPadding(Ui.dp(a, 24), Ui.dp(a, 10), Ui.dp(a, 20), Ui.dp(a, 10));

        LinearLayout top = new LinearLayout(a);
        top.setGravity(Gravity.CENTER_VERTICAL);
        ImageView iv = new ImageView(a);
        Drawable p = Ic.preview(a, e, xml);
        iv.setImageDrawable(p);
        iv.setColorFilter(Ui.TXT);
        iv.setBackground(Ui.shape(Ui.SURF2, 10, a));
        int pad = Ui.dp(a, 8);
        iv.setPadding(pad, pad, pad, pad);
        top.addView(iv, new LinearLayout.LayoutParams(Ui.dp(a, 44), Ui.dp(a, 44)));
        LinearLayout names = new LinearLayout(a);
        names.setOrientation(LinearLayout.VERTICAL);
        names.setPadding(Ui.dp(a, 14), 0, 0, 0);
        names.addView(Ui.text(a, e.name + ".xml", 15, Ui.TXT, false));
        TextView st = Ui.text(a, status(e.name), 12, staged.containsKey(e.name) ? Ui.ACC : Ui.SUB, false);
        names.addView(st);
        top.addView(names, new LinearLayout.LayoutParams(0, Ui.WRAP, 1));
        row.addView(top, new LinearLayout.LayoutParams(Ui.MATCH, Ui.WRAP));

        LinearLayout btns = new LinearLayout(a);
        btns.setPadding(0, Ui.dp(a, 8), 0, 0);
        TextView restore = pill("Restore", custom ? Ui.ACC : Ui.SUB, Ui.SURF2);
        restore.setAlpha(custom ? 1f : 0.45f);
        restore.setOnClickListener(v -> {
            if (effective(e.name) == null) { a.toast("Already the default"); return; }
            staged.put(e.name, "");
            build();
        });
        TextView change = pill("Change", Ui.ACC, Ui.SURF2);
        change.setOnClickListener(v -> pick(e.name));
        btns.addView(restore);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(Ui.WRAP, Ui.WRAP);
        lp.leftMargin = Ui.dp(a, 10);
        btns.addView(change, lp);
        TextView code = pill("Code", Ui.ACC, Ui.SURF2);
        code.setOnClickListener(v -> exportCode(e));
        LinearLayout.LayoutParams lp2 = new LinearLayout.LayoutParams(Ui.WRAP, Ui.WRAP);
        lp2.leftMargin = Ui.dp(a, 10);
        btns.addView(code, lp2);
        row.addView(btns, new LinearLayout.LayoutParams(Ui.MATCH, Ui.WRAP));
        return row;
    }

    private String status(String name) {
        if (staged.containsKey(name)) return staged.get(name).isEmpty() ? "Will go back to default" : "New icon, not applied yet";
        return saved.containsKey(name) ? "Custom" : "Default";
    }

    private void restoreAll() {
        boolean any = false;
        for (Ic.Entry e : entries) {
            if (effective(e.name) != null) { staged.put(e.name, ""); any = true; }
        }
        if (!any) { a.toast("Everything is already default"); return; }
        build();
    }

    /** Saves the icon's XML (your custom one if set, otherwise the built-in) to a place the user picks. */
    private void exportCode(Ic.Entry e) {
        String xml = effective(e.name);
        if (xml == null) {
            try { xml = Ic.builtinXml(a, e.id); } catch (Exception x) { a.toast("Could not read this icon's code"); return; }
        }
        exportText = xml;
        Intent i = new Intent(Intent.ACTION_CREATE_DOCUMENT);
        i.addCategory(Intent.CATEGORY_OPENABLE);
        i.setType("text/xml");
        i.putExtra(Intent.EXTRA_TITLE, e.name + ".xml");
        try { a.startActivityForResult(i, REQ_SAVE); } catch (Exception x) { exportText = null; a.toast("No file picker available"); }
    }

    static void onSaved(MainActivity a, int res, Intent data) {
        IconOverride o = cur;
        if (o == null || o.exportText == null) return;
        String text = o.exportText;
        o.exportText = null;
        if (res != Activity.RESULT_OK || data == null || data.getData() == null) return;
        try (java.io.OutputStream os = a.getContentResolver().openOutputStream(data.getData(), "wt")) {
            os.write(text.getBytes(StandardCharsets.UTF_8));
            a.toast("Saved");
        } catch (Exception x) {
            a.toast("Could not save the file");
        }
    }

    private void pick(String name) {
        pendingName = name;
        Intent i = new Intent(Intent.ACTION_OPEN_DOCUMENT);
        i.addCategory(Intent.CATEGORY_OPENABLE);
        i.setType("*/*");
        i.putExtra(Intent.EXTRA_MIME_TYPES, new String[]{"text/xml", "application/xml", "text/plain", "application/octet-stream"});
        try { a.startActivityForResult(i, REQ); } catch (Exception e) { pendingName = null; a.toast("No file picker available"); }
    }

    private void load(String name, Uri u) {
        String text;
        try (InputStream is = a.getContentResolver().openInputStream(u)) {
            ByteArrayOutputStream b = new ByteArrayOutputStream();
            byte[] buf = new byte[8192];
            int n;
            while ((n = is.read(buf)) > 0) {
                b.write(buf, 0, n);
                if (b.size() > Ic.MAX_BYTES) { fail("The file is too large for an icon."); return; }
            }
            text = new String(b.toByteArray(), StandardCharsets.UTF_8);
        } catch (Exception e) {
            fail("The file could not be read.");
            return;
        }
        try {
            Ic.parse(text);
        } catch (Exception e) {
            fail(e.getMessage());
            return;
        }
        staged.put(name, text);
        build();
        a.toast("Ready. Press Apply to use it.");
    }

    private void fail(String why) {
        Ui.notice(a, "Can't use this file", why + "\n\nChoose an Android vector drawable: a <vector> with viewportWidth, viewportHeight and <path> items that use pathData.");
    }

    private void apply() {
        if (staged.isEmpty()) { d.dismiss(); return; }
        int failed = 0;
        for (Map.Entry<String, String> e : staged.entrySet()) {
            try {
                if (e.getValue().isEmpty()) Ic.delete(a, e.getKey()); else Ic.save(a, e.getKey(), e.getValue());
            } catch (Exception x) { failed++; }
        }
        d.dismiss();
        Ic.invalidate();
        a.engine.fire();
        a.rebuildUi();
        a.toast(failed == 0 ? "Icons updated" : "Some icons could not be saved");
    }
}
