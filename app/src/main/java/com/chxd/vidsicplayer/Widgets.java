package com.chxd.vidsicplayer;

import android.app.PendingIntent;
import android.appwidget.AppWidgetManager;
import android.appwidget.AppWidgetProvider;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.graphics.Bitmap;
import android.graphics.ImageDecoder;
import android.net.Uri;
import android.util.Size;
import android.view.View;
import android.widget.RemoteViews;
import android.widget.RemoteViewsService;
import org.json.JSONArray;
import org.json.JSONObject;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.util.ArrayList;
import java.util.List;

public class Widgets {
    static final String PREFS = "vidsic_widgets";

    static SharedPreferences wp(Context c) { return c.getSharedPreferences(PREFS, Context.MODE_PRIVATE); }

    static void theme(Context c) { Ui.apply(c, Engine.get(c.getApplicationContext()).store); }

    static int[] ids(Context c, Class<?> k) {
        return AppWidgetManager.getInstance(c).getAppWidgetIds(new ComponentName(c, k));
    }

    static void setPending(Context c, String type, Group g) {
        wp(c).edit().putString("pend_" + type, g.ser()).putLong("pend_t_" + type, System.currentTimeMillis()).apply();
    }

    static Group takePending(Context c, String type) {
        SharedPreferences p = wp(c);
        String s = p.getString("pend_" + type, null);
        long t = p.getLong("pend_t_" + type, 0);
        p.edit().remove("pend_" + type).remove("pend_t_" + type).apply();
        if (s == null || System.currentTimeMillis() - t > 120000) return null;
        return Group.parse(s);
    }

    static void applyPending(Context c, int id, String type) {
        SharedPreferences p = wp(c);
        if (p.contains("w" + id)) return;
        Group g = takePending(c, type);
        if (g == null) return;
        p.edit().putString("w" + id, g.ser()).putBoolean("wauto" + id, true).apply();
    }

    static void setPendingPicture(Context c, Photo p) {
        wp(c).edit().putString("pend_picture", p.uri.toString()).putString("pend_key", p.key())
                .putLong("pend_t_picture", System.currentTimeMillis()).apply();
    }

    static void applyPendingPicture(Context c, int id) {
        SharedPreferences p = wp(c);
        if (p.contains("wpic" + id)) return;
        String u = p.getString("pend_picture", null), k = p.getString("pend_key", null);
        long t = p.getLong("pend_t_picture", 0);
        p.edit().remove("pend_picture").remove("pend_key").remove("pend_t_picture").apply();
        if (u == null || System.currentTimeMillis() - t > 120000) return;
        p.edit().putString("wpic" + id, u).putString("wpk" + id, k == null ? "" : k).apply();
    }

    static Bitmap decode(Context c, Uri u, final int max) {
        try {
            return ImageDecoder.decodeBitmap(ImageDecoder.createSource(c.getContentResolver(), u), (d, info, s) -> {
                d.setAllocator(ImageDecoder.ALLOCATOR_SOFTWARE);
                int w = info.getSize().getWidth(), h = info.getSize().getHeight();
                float f = Math.min(1f, (float) max / Math.max(w, h));
                if (f < 1f) d.setTargetSize(Math.max(1, Math.round(w * f)), Math.max(1, Math.round(h * f)));
            });
        } catch (Throwable e) { return null; }
    }

    static RemoteViews pictureViews(Context c, int id) {
        theme(c);
        SharedPreferences p = wp(c);
        String u = p.getString("wpic" + id, null), key = p.getString("wpk" + id, "");
        RemoteViews r = new RemoteViews(c.getPackageName(), R.layout.widget_picture);
        r.setInt(R.id.w_bg, "setColorFilter", Ui.SURF);
        r.setTextColor(R.id.w_hint, Ui.SUB);
        Bitmap b = u == null ? null : decode(c, Uri.parse(u), 720);
        if (b != null) {
            r.setImageViewBitmap(R.id.w_pic, b);
            r.setViewVisibility(R.id.w_hint, View.GONE);
            if (u != null && !u.isEmpty()) {
                Uri imageUri = Uri.parse(u);
                String mime = null;
                try { mime = c.getContentResolver().getType(imageUri); } catch (Throwable ignored) { }
                if (mime == null || !mime.startsWith("image/")) mime = "image/*";
                Intent in = new Intent(c, MainActivity.class).setAction(Intent.ACTION_VIEW)
                        .setDataAndType(imageUri, mime)
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_GRANT_READ_URI_PERMISSION);
                try { in.setClipData(android.content.ClipData.newUri(c.getContentResolver(), "picture", imageUri)); }
                catch (Throwable ignored) { }
                r.setOnClickPendingIntent(R.id.w_pic, PendingIntent.getActivity(c, 6000 + id, in,
                        PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT));
            } else r.setOnClickPendingIntent(R.id.w_pic, openApp(c));
        } else {
            r.setImageViewBitmap(R.id.w_pic, null);
            r.setViewVisibility(R.id.w_hint, View.VISIBLE);
            r.setOnClickPendingIntent(R.id.w_hint, configure(c, id));
        }
        return r;
    }

    public static class PictureProvider extends AppWidgetProvider {
        @Override public void onUpdate(Context c, AppWidgetManager wm, int[] ids) {
            for (int id : ids) { applyPendingPicture(c, id); wm.updateAppWidget(id, pictureViews(c, id)); }
        }
        @Override public void onDeleted(Context c, int[] ids) { forget(c, ids); }
    }

    static PendingIntent configure(Context c, int id) {
        Intent in = new Intent(c, WidgetConfig.class).putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, id)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        return PendingIntent.getActivity(c, 5000 + id, in, PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);
    }

    static String lastKey = "";

    static void updatePlayers(Context c) {
        int[] pi = ids(c, PlayerProvider.class), gi = ids(c, GroupProvider.class);
        if (pi.length == 0 && gi.length == 0) { Engine.get(c.getApplicationContext()).store.setBool("w_player_on", false); return; }
        AppWidgetManager wm = AppWidgetManager.getInstance(c);
        for (int id : pi) wm.updateAppWidget(id, playerViews(c, id));
        if (gi.length > 0) {
            Media m = Engine.get(c.getApplicationContext()).cur();
            String k = m == null ? "" : m.key();
            if (!k.equals(lastKey)) { lastKey = k; wm.notifyAppWidgetViewDataChanged(gi, R.id.w_list); }
        }
    }

    static void gate(Context c) {
        boolean on = ids(c, PlayerProvider.class).length + ids(c, GroupProvider.class).length > 0;
        Engine.get(c.getApplicationContext()).store.setBool("w_player_on", on);
    }

    static void dataChanged(Context c) {
        try {
            AppWidgetManager wm = AppWidgetManager.getInstance(c);
            int[] gi = ids(c, GroupProvider.class);
            if (gi.length == 0) return;
            Group.invalidate();
            for (int id : gi) wm.updateAppWidget(id, groupViews(c, id));
            wm.notifyAppWidgetViewDataChanged(gi, R.id.w_list);
        } catch (Exception e) { }
    }

    static PendingIntent openApp(Context c) {
        Intent in = new Intent(c, MainActivity.class).setAction(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        return PendingIntent.getActivity(c, 100, in, PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);
    }

    static PendingIntent openPlayer(Context c) {
        Intent in = new Intent(c, MainActivity.class).setAction(MainActivity.ACT_PLAYER)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        return PendingIntent.getActivity(c, 101, in, PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);
    }

    static PendingIntent openGroup(Context c, int rc, Group g) {
        Intent in = new Intent(c, MainActivity.class).setAction(MainActivity.ACT_GROUP)
                .putExtra("group", g == null ? "" : g.ser()).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        return PendingIntent.getActivity(c, rc, in, PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);
    }

    static PendingIntent playTemplate(Context c, int rc, Group g) {
        Intent in = new Intent(c, WidgetPlay.class).putExtra("group", g == null ? "" : g.ser());
        return PendingIntent.getActivity(c, rc, in, PendingIntent.FLAG_MUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);
    }

    static PendingIntent playerAction(Context c, String action, int rc) {
        return PendingIntent.getForegroundService(c, rc, new Intent(c, PlaybackService.class).setAction(action),
                PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);
    }

    /** Widget size in dp as the launcher reports it: {width, height}. */
    static int[] sizeDp(Context c, int id) {
        try {
            android.os.Bundle o = AppWidgetManager.getInstance(c).getAppWidgetOptions(id);
            boolean land = c.getResources().getConfiguration().orientation == android.content.res.Configuration.ORIENTATION_LANDSCAPE;
            int w = o.getInt(land ? AppWidgetManager.OPTION_APPWIDGET_MAX_WIDTH : AppWidgetManager.OPTION_APPWIDGET_MIN_WIDTH, 0);
            int h = o.getInt(land ? AppWidgetManager.OPTION_APPWIDGET_MIN_HEIGHT : AppWidgetManager.OPTION_APPWIDGET_MAX_HEIGHT, 0);
            return new int[]{w, h};
        } catch (Throwable e) { return new int[]{0, 0}; }
    }

    static RemoteViews playerViews(Context c, int id) {
        Engine e = Engine.get(c.getApplicationContext());
        theme(c);
        int[] sz = sizeDp(c, id);
        int w = sz[0], h = sz[1];
        // 0 = compact row, 1 = art + text with a control row, 2 = big art on top
        int tier = h >= 190 ? 2 : h >= 90 ? 1 : 0;
        boolean tall = tier > 0;
        int squareArtDp = tier == 0 ? 56 : 64;
        RemoteViews r = new RemoteViews(c.getPackageName(),
                tier == 2 ? R.layout.widget_player_big : tier == 1 ? R.layout.widget_player_tall : R.layout.widget_player);
        if (tier == 1 && android.os.Build.VERSION.SDK_INT >= 31) {
            int art = Math.max(48, Math.min(h - 20 - 46, Math.max(48, w / 2)));
            squareArtDp = art;
            r.setViewLayoutWidth(R.id.w_art, art, android.util.TypedValue.COMPLEX_UNIT_DIP);
            r.setViewLayoutHeight(R.id.w_art, art, android.util.TypedValue.COMPLEX_UNIT_DIP);
            float ts = Math.min(17f, 14f + art / 40f);
            r.setTextViewTextSize(R.id.w_title, android.util.TypedValue.COMPLEX_UNIT_SP, ts);
            r.setTextViewTextSize(R.id.w_sub, android.util.TypedValue.COMPLEX_UNIT_SP, Math.max(12f, ts - 4f));
        } else if (tier == 2) {
            float ts = Math.min(19f, 16f + Math.max(0, h - 190) / 80f);
            r.setTextViewTextSize(R.id.w_title, android.util.TypedValue.COMPLEX_UNIT_SP, ts);
            r.setTextViewTextSize(R.id.w_sub, android.util.TypedValue.COMPLEX_UNIT_SP, Math.max(12f, ts - 5f));
        }
        r.setInt(R.id.w_bg, "setColorFilter", Ui.BG);
        Media m = e.cur();
        r.setTextViewText(R.id.w_title, m == null ? "Nothing playing" : m.title);
        r.setTextColor(R.id.w_title, Ui.TXT);
        r.setTextViewText(R.id.w_sub, m == null ? "Tap to open Vidsic Player" : (m.artist == null ? "" : m.artist));
        r.setTextColor(R.id.w_sub, Ui.SUB);
        Bitmap art;
        if (m != null && e.art != null && m.key().equals(e.artKey)) {
            art = e.art;
        } else {
            int placeholder = m != null && m.video ? R.drawable.ic_movie : R.drawable.ic_music;
            art = WidgetCovers.glyph(c, placeholder, Ui.SUB, tier == 2 ? 400 : 160, true);
        }
        // Round after cropping to the actual square widget thumbnail. This keeps the corners visible even
        // when the source is a landscape video thumbnail or a small bitmap.
        Bitmap displayedArt = tier == 2
                ? WidgetCovers.rounded(art, 0.16f)
                : WidgetCovers.roundedCrop(art, Ui.dp(c, squareArtDp), Ui.dp(c, squareArtDp), Ui.dp(c, 8));
        r.setImageViewBitmap(R.id.w_art, displayedArt);
        int playIcon = e.isPlaying() ? R.drawable.ic_pause : R.drawable.ic_play;
        r.setImageViewBitmap(R.id.w_play, WidgetCovers.glyph(c, playIcon, Ui.ACC, 96, false));
        r.setImageViewBitmap(R.id.w_prev, WidgetCovers.glyph(c, R.drawable.ic_prev, Ui.TXT, 96, false));
        r.setImageViewBitmap(R.id.w_next, WidgetCovers.glyph(c, R.drawable.ic_next, Ui.TXT, 96, false));
        r.setOnClickPendingIntent(R.id.w_prev, playerAction(c, PlaybackService.A_PREV, 11));
        r.setOnClickPendingIntent(R.id.w_play, playerAction(c, PlaybackService.A_TOGGLE, 12));
        r.setOnClickPendingIntent(R.id.w_next, playerAction(c, PlaybackService.A_NEXT, 13));
        if (tall) {
            boolean fav = m != null && e.store.isFav(m);
            r.setImageViewBitmap(R.id.w_fav, WidgetCovers.glyph(c, fav ? R.drawable.ic_heart_fill : R.drawable.ic_heart, fav ? Ui.ACC : Ui.TXT, 96, false));
            r.setImageViewBitmap(R.id.w_shuf, WidgetCovers.glyph(c, R.drawable.ic_shuffle, e.shuffle ? Ui.ACC : Ui.SUB, 96, false));
            r.setImageViewBitmap(R.id.w_rep, WidgetCovers.glyph(c, e.repeat == 2 ? R.drawable.ic_repeat_one : R.drawable.ic_repeat,
                    e.repeat == 0 ? Ui.SUB : Ui.ACC, 96, false));
            r.setOnClickPendingIntent(R.id.w_fav, playerAction(c, PlaybackService.A_FAV, 14));
            r.setOnClickPendingIntent(R.id.w_shuf, playerAction(c, PlaybackService.A_SHUFFLE, 15));
            r.setOnClickPendingIntent(R.id.w_rep, playerAction(c, PlaybackService.A_REPEAT, 16));
        }
        PendingIntent open = openPlayer(c);
        r.setOnClickPendingIntent(R.id.w_root, open);
        r.setOnClickPendingIntent(R.id.w_art, open);
        r.setOnClickPendingIntent(R.id.w_text, open);
        return r;
    }

    public static class PlayerProvider extends AppWidgetProvider {
        @Override public void onEnabled(Context c) { gate(c); }
        @Override public void onDisabled(Context c) { gate(c); }
        @Override public void onUpdate(Context c, AppWidgetManager wm, int[] ids) {
            gate(c);
            for (int id : ids) wm.updateAppWidget(id, playerViews(c, id));
        }
        @Override public void onAppWidgetOptionsChanged(Context c, AppWidgetManager wm, int id, android.os.Bundle options) {
            wm.updateAppWidget(id, playerViews(c, id));
        }
    }

    private static File groupSnapshotFile(Context c, int id) {
        File dir = new File(c.getFilesDir(), "widget_group_snapshots");
        if (!dir.isDirectory()) dir.mkdirs();
        return new File(dir, "group_" + id + ".json");
    }

    private static final class GroupSnapshot {
        final List<Media> media = new ArrayList<>();
        final List<Photo> pictures = new ArrayList<>();
        boolean matches(Group g) { return g != null && groupSer != null && groupSer.equals(g.ser()); }
        String groupSer;
    }

    private static GroupSnapshot readGroupSnapshot(Context c, int id, Group g) {
        File f = groupSnapshotFile(c, id);
        if (!f.isFile() || g == null) return null;
        try (FileInputStream in = new FileInputStream(f); ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            byte[] buf = new byte[8192];
            int n;
            while ((n = in.read(buf)) > 0) out.write(buf, 0, n);
            JSONObject root = new JSONObject(out.toString("UTF-8"));
            GroupSnapshot snap = new GroupSnapshot();
            snap.groupSer = root.optString("group", null);
            if (!snap.matches(g)) return null;
            JSONArray ma = root.optJSONArray("media");
            if (ma != null) for (int i = 0; i < ma.length(); i++) {
                JSONObject o = ma.optJSONObject(i);
                if (o == null) continue;
                Media m = Media.fromJson(o);
                if (m != null) snap.media.add(m);
            }
            JSONArray pa = root.optJSONArray("pictures");
            if (pa != null) for (int i = 0; i < pa.length(); i++) {
                JSONObject o = pa.optJSONObject(i);
                if (o == null) continue;
                Photo ph = new Photo();
                ph.id = o.optLong("id");
                String u = o.optString("uri", "");
                if (u.isEmpty()) continue;
                ph.uri = Uri.parse(u);
                ph.title = o.optString("title", "");
                ph.name = o.optString("name", "");
                ph.folder = o.isNull("folder") ? null : o.optString("folder", null);
                ph.path = o.isNull("path") ? null : o.optString("path", null);
                ph.added = o.optLong("added");
                ph.size = o.optLong("size");
                ph.w = o.optInt("w");
                ph.h = o.optInt("h");
                ph.gif = o.optBoolean("gif");
                ph.svg = o.optBoolean("svg");
                snap.pictures.add(ph);
            }
            return snap;
        } catch (Throwable e) { return null; }
    }

    private static void writeGroupSnapshot(Context c, int id, Group g, List<Media> media, List<Photo> pictures) {
        if (g == null) return;
        File f = groupSnapshotFile(c, id);
        File tmp = new File(f.getPath() + ".tmp");
        try {
            JSONObject root = new JSONObject();
            root.put("group", g.ser());
            JSONArray ma = new JSONArray();
            for (Media m : media) try {
                // Only persist the fields the widget needs; this is a small row cache, not a media copy.
                JSONObject o = new JSONObject();
                o.put("id", m.id);
                o.put("u", m.uri == null ? "" : m.uri.toString());
                o.put("t", m.title == null ? "" : m.title);
                o.put("a", m.artist == null ? "" : m.artist);
                o.put("d", m.duration);
                o.put("v", m.video);
                ma.put(o);
            } catch (Throwable ignored) { }
            root.put("media", ma);
            JSONArray pa = new JSONArray();
            for (Photo ph : pictures) try {
                JSONObject o = new JSONObject();
                o.put("id", ph.id);
                o.put("uri", ph.uri == null ? "" : ph.uri.toString());
                o.put("title", ph.title == null ? "" : ph.title);
                o.put("name", ph.name == null ? "" : ph.name);
                o.put("folder", ph.folder);
                o.put("path", ph.path);
                o.put("added", ph.added);
                o.put("size", ph.size);
                o.put("w", ph.w);
                o.put("h", ph.h);
                o.put("gif", ph.gif);
                o.put("svg", ph.svg);
                pa.put(o);
            } catch (Throwable ignored) { }
            root.put("pictures", pa);
            try (FileOutputStream out = new FileOutputStream(tmp)) {
                out.write(root.toString().getBytes("UTF-8"));
                out.flush();
                out.getFD().sync();
            }
            if (!tmp.renameTo(f)) {
                f.delete();
                if (!tmp.renameTo(f)) tmp.delete();
            }
        } catch (Throwable e) { tmp.delete(); }
    }

    private static boolean groupIsExplicitlyEmpty(Context c, Group g) {
        try {
            Store s = Engine.get(c.getApplicationContext()).store;
            if (Group.PLAYLIST.equals(g.kind)) return s.items(g.video, g.name).isEmpty();
            if (Group.FAV.equals(g.kind)) return s.list("fav").isEmpty();
            if (Group.P_COL.equals(g.kind)) return s.collectionItems(g.name).isEmpty();
            if (Group.P_FAV.equals(g.kind)) return s.list("pfav").isEmpty();
        } catch (Throwable ignored) { }
        return false;
    }

    static RemoteViews groupViews(Context c, int id) {
        theme(c);
        Group g = Group.parse(wp(c).getString("w" + id, null));
        RemoteViews r = new RemoteViews(c.getPackageName(), R.layout.widget_group);
        r.setInt(R.id.w_bg, "setColorFilter", Ui.BG);
        r.setTextViewText(R.id.w_title, g == null ? "Choose a group" : g.title(c));
        r.setTextColor(R.id.w_title, Ui.TXT);
        int count = wp(c).getInt("wc" + id, -1);
        r.setTextViewText(R.id.w_count, count < 0 ? "" : String.valueOf(count));
        r.setTextColor(R.id.w_count, Ui.SUB);
        r.setTextColor(R.id.w_empty, Ui.SUB);
        int icon = g == null ? R.drawable.ic_folder : g.icon();
        r.setImageViewBitmap(R.id.w_icon, WidgetCovers.glyph(c, icon, Ui.ACC, 64, false));
        Intent svc = new Intent(c, GroupService.class).putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, id);
        svc.setData(Uri.parse(svc.toUri(Intent.URI_INTENT_SCHEME)));
        r.setRemoteAdapter(R.id.w_list, svc);
        r.setEmptyView(R.id.w_list, R.id.w_empty);
        r.setOnClickPendingIntent(R.id.w_head, g == null ? configure(c, id) : openGroup(c, 3000 + id, g));
        r.setPendingIntentTemplate(R.id.w_list, playTemplate(c, 4000 + id, g));
        r.setOnClickPendingIntent(R.id.w_empty, configure(c, id));
        return r;
    }

    public static class GroupProvider extends AppWidgetProvider {
        @Override public void onEnabled(Context c) { gate(c); }
        @Override public void onDisabled(Context c) { gate(c); }
        @Override public void onUpdate(Context c, AppWidgetManager wm, int[] ids) {
            gate(c);
            for (int id : ids) {
                applyPending(c, id, "group");
                wm.updateAppWidget(id, groupViews(c, id));
            }
        }
        @Override public void onDeleted(Context c, int[] ids) { forget(c, ids); }
    }

    public static class GroupService extends RemoteViewsService {
        @Override public RemoteViewsFactory onGetViewFactory(Intent in) {
            return new GroupFactory(getApplicationContext(), in.getIntExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, 0));
        }
    }

    static class GroupFactory implements RemoteViewsService.RemoteViewsFactory {
        private static final int COVER_PX = 96;
        private final Context c;
        private final int id;
        private List<Media> media = new ArrayList<>();
        private List<Photo> pictures = new ArrayList<>();
        private Media playing;

        GroupFactory(Context c, int id) {
            this.c = c;
            this.id = id;
        }

        @Override public void onCreate() { }

        @Override public void onDataSetChanged() {
            List<Media> loadedMedia = new ArrayList<>();
            List<Photo> loadedPictures = new ArrayList<>();
            Group g = Group.parse(wp(c).getString("w" + id, null));
            try { theme(c); } catch (Throwable ignored) { }
            try {
                if (g != null) {
                    if (g.photo()) loadedPictures = g.pictureItems(c);
                    else loadedMedia = g.media(c);
                }
            } catch (Throwable ignored) { }
            try { playing = Engine.get(c.getApplicationContext()).cur(); }
            catch (Throwable ignored) { playing = null; }

            int freshCount = loadedMedia.size() + loadedPictures.size();
            GroupSnapshot cached = freshCount == 0 ? readGroupSnapshot(c, id, g) : null;
            if (freshCount > 0) {
                // Keep a compact, persistent list snapshot so the widget survives process death.
                writeGroupSnapshot(c, id, g, loadedMedia, loadedPictures);
            } else if (groupIsExplicitlyEmpty(c, g)) {
                // A deliberately emptied playlist/favorite/collection should not resurrect old rows.
                writeGroupSnapshot(c, id, g, loadedMedia, loadedPictures);
            } else if (cached != null && (!cached.media.isEmpty() || !cached.pictures.isEmpty())) {
                loadedMedia = cached.media;
                loadedPictures = cached.pictures;
            }
            media = loadedMedia;
            pictures = loadedPictures;
            publishCount(media.size() + pictures.size());
        }

        private void publishCount(int n) {
            try {
                SharedPreferences p = wp(c);
                if (p.getInt("wc" + id, -1) == n) return;
                p.edit().putInt("wc" + id, n).apply();
                RemoteViews r = new RemoteViews(c.getPackageName(), R.layout.widget_group);
                r.setTextViewText(R.id.w_count, String.valueOf(n));
                AppWidgetManager.getInstance(c).partiallyUpdateAppWidget(id, r);
            } catch (Throwable ignored) { }
        }

        @Override public void onDestroy() {
            media = new ArrayList<>();
            pictures = new ArrayList<>();
        }

        @Override public int getCount() { return media.size() + pictures.size(); }

        private static String duration(long ms) {
            long s = ms / 1000;
            return (s / 60) + ":" + (s % 60 < 10 ? "0" : "") + (s % 60);
        }

        @Override public RemoteViews getViewAt(int i) {
            RemoteViews v = new RemoteViews(c.getPackageName(), R.layout.widget_group_item);
            v.setTextColor(R.id.w_t, Ui.TXT);
            v.setTextColor(R.id.w_s, Ui.SUB);
            v.setViewVisibility(R.id.w_eq, View.INVISIBLE);
            String key = null;
            try {
                int placeholder;
                if (i < media.size()) {
                    final Media m = media.get(i);
                    key = m.key();
                    placeholder = m.video ? R.drawable.ic_movie : R.drawable.ic_music;
                    v.setTextViewText(R.id.w_t, m.title);
                    v.setTextViewText(R.id.w_s, (m.artist == null ? "" : m.artist) + (m.duration > 0 ? "  \u2022  " + duration(m.duration) : ""));
                    if (playing != null && playing.key().equals(m.key())) {
                        v.setTextColor(R.id.w_t, Ui.ACC);
                        v.setImageViewBitmap(R.id.w_eq, WidgetCovers.glyph(c, R.drawable.ic_eq, Ui.ACC, 64, false));
                        v.setViewVisibility(R.id.w_eq, View.VISIBLE);
                    }
                    WidgetCovers.request(c, id, key, () -> Ui.loadBitmap(c, m, COVER_PX));
                } else {
                    final Photo p = pictures.get(i - media.size());
                    key = p.key();
                    placeholder = R.drawable.ic_image;
                    v.setTextViewText(R.id.w_t, p.title);
                    v.setTextViewText(R.id.w_s, p.folder == null ? "" : p.folder);
                    WidgetCovers.request(c, id, key, () -> c.getContentResolver().loadThumbnail(p.uri, new Size(128, 128), null));
                }
                Bitmap cover = WidgetCovers.cached(c, key);
                Bitmap thumb = cover != null ? cover : WidgetCovers.glyph(c, placeholder, Ui.SUB, COVER_PX, true);
                // Group rows use an 8dp radius like Vidsic's in-app thumbnail boxes. Crop first, then
                // round the final square bitmap so RemoteViews scaling cannot remove the rounded corners.
                int thumbPx = Ui.dp(c, 44);
                v.setImageViewBitmap(R.id.w_thumb,
                        WidgetCovers.roundedCrop(thumb, thumbPx, thumbPx, Ui.dp(c, 8)));
            } catch (Throwable ignored) { }
            if (key != null) v.setOnClickFillInIntent(R.id.w_row, new Intent().putExtra("item", key));
            return v;
        }

        @Override public RemoteViews getLoadingView() {
            RemoteViews v = new RemoteViews(c.getPackageName(), R.layout.widget_group_item);
            v.setViewVisibility(R.id.w_eq, View.INVISIBLE);
            return v;
        }

        @Override public int getViewTypeCount() { return 1; }

        @Override public long getItemId(int i) { return i; }

        @Override public boolean hasStableIds() { return false; }
    }

    static void forget(Context c, int[] ids) {
        SharedPreferences.Editor e = wp(c).edit();
        for (int id : ids) {
            e.remove("w" + id).remove("wauto" + id).remove("wc" + id).remove("wpic" + id).remove("wpk" + id);
            try {
                File f = groupSnapshotFile(c, id);
                f.delete();
                new File(f.getPath() + ".tmp").delete();
            } catch (Throwable ignored) { }
        }
        e.apply();
    }
}
