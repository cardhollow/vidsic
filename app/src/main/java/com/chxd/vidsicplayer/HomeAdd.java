package com.chxd.vidsicplayer;

import android.app.Activity;
import android.app.Dialog;
import android.appwidget.AppWidgetManager;
import android.content.ComponentName;
import android.content.Intent;
import android.content.pm.ShortcutInfo;
import android.content.pm.ShortcutManager;
import android.database.Cursor;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Rect;
import android.graphics.drawable.ColorDrawable;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.Icon;
import android.graphics.ImageDecoder;
import android.net.Uri;
import android.provider.OpenableColumns;
import android.text.InputType;
import android.view.Gravity;
import android.view.View;
import android.view.Window;
import android.widget.EditText;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

final class HomeAdd {
    static final int REQ_ICON = 90;
    static final long MAX_BYTES = 2L * 1024 * 1024;
    static final int ICON_PX = 432;
    static final int[] ICONS = {R.drawable.ic_folder, R.drawable.ic_playlist, R.drawable.ic_heart_fill, R.drawable.ic_album,
            R.drawable.ic_person, R.drawable.ic_music, R.drawable.ic_movie, R.drawable.ic_image, R.drawable.ic_collections,
            R.drawable.ic_headphones, R.drawable.ic_slideshow, R.drawable.ic_grid};
    static final int[] COLORS = {0xFF8B6BFF, 0xFFE91E63, 0xFFFF5722, 0xFFFFA000, 0xFF43A047, 0xFF00ACC1, 0xFF1E88E5, 0xFF546E7A};

    private static MainActivity act;
    private static Dialog dlg;
    private static ImageView preview;
    private static Group group;
    private static EditText label;
    private static Bitmap custom;
    private static int icon, color;

    private HomeAdd() { }

    static void show(final MainActivity a, final Group g, final String title) {
        Ui.Sheet sh = new Ui.Sheet(a, title, "Add to home screen");
        sh.item(R.drawable.ic_add, "Shortcut", Ui.TXT, () -> shortcutDialog(a, g, title));
        sh.item(R.drawable.ic_grid, "Widget: list", Ui.TXT, () -> pinWidget(a, g, title));
        sh.show();
    }

    static void pinPicture(MainActivity a, Photo p) {
        if (p == null || p.svg || p.uri == null) { a.toast("Drawings can't be used as a picture widget"); return; }
        AppWidgetManager wm = AppWidgetManager.getInstance(a);
        if (!wm.isRequestPinAppWidgetSupported()) {
            Ui.notice(a, "Add a widget", "Your launcher can't add widgets from here. Press and hold the home screen, tap Widgets, "
                    + "find Vidsic Player and choose \"Vidsic Picture\". Then pick a picture.");
            return;
        }
        Widgets.setPendingPicture(a, p);
        try { wm.requestPinAppWidget(new ComponentName(a, Widgets.PictureProvider.class), null, null); }
        catch (Exception e) { a.toast("Could not add the widget"); }
    }

    static void pinWidget(MainActivity a, Group g, String title) {
        AppWidgetManager wm = AppWidgetManager.getInstance(a);
        if (!wm.isRequestPinAppWidgetSupported()) {
            Ui.notice(a, "Add a widget", "Your launcher can't add widgets from here. Press and hold the home screen, tap Widgets, "
                    + "find Vidsic Player and choose \"Vidsic Group\". Then pick \"" + title + "\".");
            return;
        }
        Widgets.setPending(a, "group", g);
        try { wm.requestPinAppWidget(new ComponentName(a, Widgets.GroupProvider.class), null, null); }
        catch (Exception e) { a.toast("Could not add the widget"); }
    }

    static void shortcutDialog(final MainActivity a, final Group g, String title) {
        act = a;
        group = g;
        custom = null;
        icon = g.icon();
        color = COLORS[0];
        final Dialog d = new Dialog(a);
        dlg = d;
        d.requestWindowFeature(Window.FEATURE_NO_TITLE);
        LinearLayout box = new LinearLayout(a);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setPadding(Ui.dp(a, 20), Ui.dp(a, 18), Ui.dp(a, 20), Ui.dp(a, 14));
        box.setBackground(Ui.shape(Ui.SURF, 20, a));
        box.addView(Ui.text(a, "Add shortcut", 20, Ui.TXT, true));

        LinearLayout top = new LinearLayout(a);
        top.setGravity(Gravity.CENTER_VERTICAL);
        top.setPadding(0, Ui.dp(a, 14), 0, Ui.dp(a, 6));
        preview = new ImageView(a);
        preview.setScaleType(ImageView.ScaleType.CENTER_CROP);
        preview.setBackground(Ui.shape(0xFF000000, 16, a));
        preview.setClipToOutline(true);
        top.addView(preview, new LinearLayout.LayoutParams(Ui.dp(a, 64), Ui.dp(a, 64)));
        label = new EditText(a);
        label.setText(title);
        label.setTextColor(Ui.TXT);
        label.setTextSize(16);
        label.setSingleLine(true);
        label.setInputType(InputType.TYPE_CLASS_TEXT);
        Ui.tintEdit(a, label);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0, Ui.WRAP, 1);
        lp.setMargins(Ui.dp(a, 14), 0, 0, 0);
        top.addView(label, lp);
        box.addView(top);

        box.addView(sectionLabel(a, "Icon"));
        LinearLayout grid = new LinearLayout(a);
        grid.setOrientation(LinearLayout.VERTICAL);
        LinearLayout row = null;
        for (int i = 0; i < ICONS.length; i++) {
            if (i % 6 == 0) { row = new LinearLayout(a); grid.addView(row); }
            final int res = ICONS[i];
            ImageView iv = Ui.icon(a, res, Ui.TXT, 22);
            iv.setPadding(Ui.dp(a, 9), Ui.dp(a, 9), Ui.dp(a, 9), Ui.dp(a, 9));
            iv.setBackground(Ui.rip(0, 20, a));
            iv.setOnClickListener(v -> { icon = res; custom = null; render(); });
            row.addView(iv, new LinearLayout.LayoutParams(Ui.dp(a, 44), Ui.dp(a, 44)));
        }
        box.addView(grid);

        box.addView(sectionLabel(a, "Colour"));
        LinearLayout cr = new LinearLayout(a);
        for (final int col : COLORS) {
            View sw = new View(a);
            sw.setBackground(Ui.shape(col, 100, a));
            sw.setOnClickListener(v -> { color = col; custom = null; render(); });
            LinearLayout.LayoutParams sp = new LinearLayout.LayoutParams(Ui.dp(a, 28), Ui.dp(a, 28));
            sp.setMargins(0, 0, Ui.dp(a, 10), 0);
            cr.addView(sw, sp);
        }
        box.addView(cr);

        TextView up = Ui.text(a, "Use my own picture", 14, Ui.ACC, true);
        up.setPadding(0, Ui.dp(a, 16), 0, Ui.dp(a, 2));
        up.setOnClickListener(v -> {
            Intent in = new Intent(Intent.ACTION_OPEN_DOCUMENT).addCategory(Intent.CATEGORY_OPENABLE).setType("image/*");
            try { a.startActivityForResult(in, REQ_ICON); } catch (Exception e) { a.toast("No picture picker found"); }
        });
        box.addView(up);
        TextView lim = Ui.text(a, "A picture up to 2 MB, a square one looks best", 12, Ui.SUB, false);
        box.addView(lim);

        LinearLayout btns = new LinearLayout(a);
        btns.setGravity(Gravity.END);
        btns.setPadding(0, Ui.dp(a, 16), 0, 0);
        TextView no = Ui.text(a, "Cancel", 15, Ui.SUB, true);
        no.setPadding(Ui.dp(a, 16), Ui.dp(a, 10), Ui.dp(a, 16), Ui.dp(a, 10));
        no.setOnClickListener(v -> d.dismiss());
        TextView ok = Ui.text(a, "Add", 15, Ui.ACC, true);
        ok.setPadding(Ui.dp(a, 16), Ui.dp(a, 10), Ui.dp(a, 16), Ui.dp(a, 10));
        ok.setOnClickListener(v -> {
            String l = label.getText().toString().trim();
            if (l.isEmpty()) l = title;
            pin(a, g, l);
            d.dismiss();
        });
        btns.addView(no);
        btns.addView(ok);
        box.addView(btns);

        ScrollView sv = new ScrollView(a);
        sv.addView(box);
        d.setContentView(sv);
        d.setOnDismissListener(x -> { dlg = null; preview = null; label = null; act = null; });
        Window w = d.getWindow();
        if (w != null) {
            w.setBackgroundDrawable(new ColorDrawable(0));
            w.setLayout(Math.min(Ui.dp(a, 360), (int) (a.getResources().getDisplayMetrics().widthPixels * 0.92f)), Ui.WRAP);
            w.setSoftInputMode(android.view.WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE);
        }
        render();
        d.show();
    }

    private static TextView sectionLabel(MainActivity a, String s) {
        TextView t = Ui.text(a, s, 12, Ui.SUB, true);
        t.setPadding(0, Ui.dp(a, 12), 0, Ui.dp(a, 6));
        return t;
    }

    private static Bitmap draw(MainActivity a) {
        if (custom != null) return custom;
        Bitmap b = Bitmap.createBitmap(ICON_PX, ICON_PX, Bitmap.Config.ARGB_8888);
        Canvas c = new Canvas(b);
        c.drawColor(color);
        Drawable d = Ic.get(a, icon);
        if (d != null) {
            d = d.mutate();
            d.setTint(0xFFFFFFFF);
            int s = ICON_PX * 40 / 100, o = (ICON_PX - s) / 2;
            d.setBounds(o, o, o + s, o + s);
            d.draw(c);
        }
        return b;
    }

    private static void render() {
        if (act == null || preview == null) return;
        preview.setImageBitmap(draw(act));
    }

    private static void pin(MainActivity a, Group g, String label) {
        ShortcutManager sm = a.getSystemService(ShortcutManager.class);
        if (sm == null || !sm.isRequestPinShortcutSupported()) {
            Ui.notice(a, "Add shortcut", "Your launcher can't add shortcuts from apps. Try a different launcher, or use a widget instead.");
            return;
        }
        Intent in = new Intent(a, MainActivity.class).setAction(MainActivity.ACT_GROUP).putExtra("group", g.ser());
        String id = "g" + Integer.toHexString(g.ser().hashCode());
        try {
            ShortcutInfo info = new ShortcutInfo.Builder(a, id)
                    .setShortLabel(label).setLongLabel(label)
                    .setIcon(Icon.createWithAdaptiveBitmap(draw(a)))
                    .setIntent(in).build();
            sm.requestPinShortcut(info, null);
        } catch (Exception e) { a.toast("Could not add the shortcut"); }
    }

    static void onPicked(MainActivity a, int res, Intent data) {
        if (res != Activity.RESULT_OK || data == null || data.getData() == null) return;
        Uri u = data.getData();
        try (Cursor cu = a.getContentResolver().query(u, new String[]{OpenableColumns.SIZE}, null, null, null)) {
            if (cu != null && cu.moveToFirst() && !cu.isNull(0) && cu.getLong(0) > MAX_BYTES) {
                a.toast("That picture is too big for an icon (2 MB at most)");
                return;
            }
        } catch (Exception e) { }
        try {
            Bitmap src = ImageDecoder.decodeBitmap(ImageDecoder.createSource(a.getContentResolver(), u), (dec, info, s) -> {
                dec.setAllocator(ImageDecoder.ALLOCATOR_SOFTWARE);
                int m = Math.min(info.getSize().getWidth(), info.getSize().getHeight());
                if (m > ICON_PX * 2) {
                    float f = (float) (ICON_PX * 2) / m;
                    dec.setTargetSize(Math.round(info.getSize().getWidth() * f), Math.round(info.getSize().getHeight() * f));
                }
            });
            int w = src.getWidth(), h = src.getHeight(), m = Math.min(w, h);
            if (m < 48) { a.toast("That picture is too small for an icon"); return; }
            Bitmap out = Bitmap.createBitmap(ICON_PX, ICON_PX, Bitmap.Config.ARGB_8888);
            new Canvas(out).drawBitmap(src, new Rect((w - m) / 2, (h - m) / 2, (w + m) / 2, (h + m) / 2),
                    new Rect(0, 0, ICON_PX, ICON_PX), null);
            custom = out;
            render();
        } catch (Exception e) { a.toast("Could not read that picture"); }
    }
}
