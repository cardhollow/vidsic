package com.chxd.vidsicplayer;

import android.app.Activity;
import android.appwidget.AppWidgetManager;
import android.appwidget.AppWidgetProviderInfo;
import android.content.Intent;
import android.content.SharedPreferences;
import android.net.Uri;
import android.os.Bundle;
import android.view.Gravity;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import java.util.List;

public class WidgetConfig extends Activity {
    int id;
    LinearLayout list;

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        id = getIntent().getIntExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, AppWidgetManager.INVALID_APPWIDGET_ID);
        Intent res = new Intent().putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, id);
        setResult(RESULT_CANCELED, res);
        if (id == AppWidgetManager.INVALID_APPWIDGET_ID) { finish(); return; }
        Widgets.theme(this);
        AppWidgetProviderInfo info = AppWidgetManager.getInstance(this).getAppWidgetInfo(id);
        String cls = info == null ? "" : info.provider.getClassName();
        SharedPreferences wp = Widgets.wp(this);

        if (cls.endsWith("PictureProvider")) {
            boolean had = wp.contains("wpic" + id);
            Widgets.applyPendingPicture(this, id);
            if (!had && wp.contains("wpic" + id)) { finishPicture(); return; }
            Intent pick = new Intent(Intent.ACTION_OPEN_DOCUMENT).addCategory(Intent.CATEGORY_OPENABLE).setType("image/*");
            try { startActivityForResult(pick, 7); } catch (Exception e) { finish(); }
            return;
        }

        if (wp.getBoolean("wauto" + id, false) && wp.contains("w" + id)) {
            wp.edit().remove("wauto" + id).apply();
            finishWith(Group.parse(wp.getString("w" + id, null)));
            return;
        }

        Group pre = Widgets.takePending(this, "group");
        if (pre != null) { finishWith(pre); return; }

        getWindow().setStatusBarColor(Ui.BG);
        getWindow().setNavigationBarColor(Ui.BG);
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(Ui.BG);
        root.setPadding(Ui.dp(this, 16), Ui.dp(this, 36), Ui.dp(this, 16), Ui.dp(this, 8));
        root.addView(Ui.text(this, "Group widget", 22, Ui.TXT, true));
        TextView s = Ui.text(this, "Choose what the widget shows", 14, Ui.SUB, false);
        s.setPadding(0, Ui.dp(this, 2), 0, Ui.dp(this, 10));
        root.addView(s);
        ScrollView sv = new ScrollView(this);
        list = new LinearLayout(this);
        list.setOrientation(LinearLayout.VERTICAL);
        sv.addView(list);
        root.addView(sv, new LinearLayout.LayoutParams(Ui.MATCH, 0, 1));
        TextView loading = Ui.text(this, "Loading...", 14, Ui.SUB, false);
        loading.setPadding(0, Ui.dp(this, 16), 0, 0);
        list.addView(loading);
        setContentView(root);

        new Thread(() -> {
            final List<Group> gs = Group.all(this, true, true);
            runOnUiThread(() -> fill(gs));
        }).start();
    }

    @Override
    protected void onActivityResult(int req, int res, Intent data) {
        super.onActivityResult(req, res, data);
        if (req != 7) return;
        if (res != RESULT_OK || data == null || data.getData() == null) { finish(); return; }
        Uri u = data.getData();
        try { getContentResolver().takePersistableUriPermission(u, Intent.FLAG_GRANT_READ_URI_PERMISSION); } catch (Exception e) { }
        Widgets.wp(this).edit().putString("wpic" + id, u.toString()).putString("wpk" + id, "").apply();
        finishPicture();
    }

    void finishPicture() {
        AppWidgetManager.getInstance(this).updateAppWidget(id, Widgets.pictureViews(this, id));
        setResult(RESULT_OK, new Intent().putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, id));
        finish();
    }

    void fill(List<Group> gs) {
        list.removeAllViews();
        String last = "";
        int n = 0;
        for (final Group g : gs) {
            String sec = g.photo() ? "Pictures" : g.video ? "Video" : "Audio";
            if (!sec.equals(last)) {
                last = sec;
                TextView h = Ui.text(this, sec, 13, Ui.ACC, true);
                h.setPadding(0, Ui.dp(this, 16), 0, Ui.dp(this, 4));
                list.addView(h);
            }
            LinearLayout row = new LinearLayout(this);
            row.setGravity(Gravity.CENTER_VERTICAL);
            row.setPadding(Ui.dp(this, 10), Ui.dp(this, 10), Ui.dp(this, 10), Ui.dp(this, 10));
            row.setBackground(Ui.rip(0, 12, this));
            row.addView(Ui.icon(this, g.icon(), Ui.ACC, 22));
            LinearLayout col = new LinearLayout(this);
            col.setOrientation(LinearLayout.VERTICAL);
            col.setPadding(Ui.dp(this, 14), 0, 0, 0);
            col.addView(Ui.text(this, g.title(this), 15, Ui.TXT, false));
            col.addView(Ui.text(this, g.kindLabel(), 12, Ui.SUB, false));
            row.addView(col);
            row.setOnClickListener(v -> finishWith(g));
            list.addView(row);
            n++;
        }
        if (n == 0) {
            TextView e = Ui.text(this, "Nothing to choose from yet. Check that the app can read your media.", 14, Ui.SUB, false);
            e.setPadding(0, Ui.dp(this, 16), 0, 0);
            list.addView(e);
        }
    }

    void finishWith(Group g) {
        if (g == null) { finish(); return; }
        SharedPreferences.Editor e = Widgets.wp(this).edit();
        e.putString("w" + id, g.ser()).apply();
        AppWidgetManager wm = AppWidgetManager.getInstance(this);
        wm.updateAppWidget(id, Widgets.groupViews(this, id));
        wm.notifyAppWidgetViewDataChanged(id, R.id.w_list);
        setResult(RESULT_OK, new Intent().putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, id));
        finish();
    }
}
