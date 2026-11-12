package com.chxd.vidsicplayer;

import android.content.Context;
import android.content.SharedPreferences;
import org.json.JSONArray;
import org.json.JSONObject;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

class Store {
    final SharedPreferences p;
    final File capDir, coverDir;

    Store(Context c) {
        p = c.getSharedPreferences("vidsic", 0);
        capDir = new File(c.getFilesDir(), "captions");
        coverDir = new File(c.getFilesDir(), "covers");
    }

    JSONObject metaOf(String key) {
        String j = p.getString("mt_" + key, null);
        if (j == null) return null;
        try { return new JSONObject(j); } catch (Exception e) { return null; }
    }

    void setMeta(String key, String field, String value) {
        JSONObject o = metaOf(key);
        if (o == null) o = new JSONObject();
        try { if (value == null) o.remove(field); else o.put(field, value); } catch (Exception e) { }
        if (o.length() == 0) p.edit().remove("mt_" + key).apply();
        else p.edit().putString("mt_" + key, o.toString()).apply();
    }

    void applyMeta(Media m) {
        String t = renamed(m);
        if (t != null) m.title = t;
        JSONObject o = metaOf(m.key());
        if (o == null) return;
        if (o.has("artist")) m.artist = o.optString("artist");
        if (o.has("album")) m.album = o.optString("album");
        if (o.has("genre")) m.genre = o.optString("genre");
        if (o.has("desc")) m.desc = o.optString("desc");
    }

    JSONObject editsJson() throws Exception {
        JSONObject o = new JSONObject();
        o.put("version", 1);
        JSONObject all = new JSONObject();
        for (Map.Entry<String, ?> en : p.getAll().entrySet()) {
            String k = en.getKey();
            if ((k.startsWith("mt_") || k.startsWith("ren_") || k.startsWith("pv_")) && en.getValue() != null)
                all.put(k, en.getValue());
        }
        o.put("values", all);
        return o;
    }

    void applyEdits(JSONObject o, boolean replace) {
        JSONObject all = o.optJSONObject("values");
        if (all == null) return;
        SharedPreferences.Editor e = p.edit();
        java.util.Iterator<String> it = all.keys();
        while (it.hasNext()) {
            String k = it.next();
            if (!(k.startsWith("mt_") || k.startsWith("ren_") || k.startsWith("pv_"))) continue;
            if (!replace && p.contains(k)) continue;
            Object v = all.opt(k);
            if (v instanceof Number) e.putInt(k, ((Number) v).intValue());
            else if (v instanceof String) e.putString(k, (String) v);
        }
        e.apply();
    }

    File[] coverFiles() {
        File[] l = coverDir.listFiles();
        return l == null ? new File[0] : l;
    }

    void putCoverFile(String fileName, byte[] data, boolean replace) {
        if (fileName.contains("/") || fileName.contains("..")) return;
        File f = new File(coverDir, fileName);
        if (f.isFile() && !replace) return;
        try {
            coverDir.mkdirs();
            try (FileOutputStream o = new FileOutputStream(f)) { o.write(data); }
        } catch (Exception e) { }
    }

    File coverFile(String key) { return new File(coverDir, key.replaceAll("[^A-Za-z0-9_-]", "_") + ".jpg"); }
    boolean hasCover(String key) { return coverFile(key).isFile(); }
    void deleteCover(String key) { coverFile(key).delete(); }

    boolean saveCover(String key, android.graphics.Bitmap b) {
        try {
            coverDir.mkdirs();
            try (FileOutputStream o = new FileOutputStream(coverFile(key))) { b.compress(android.graphics.Bitmap.CompressFormat.JPEG, 90, o); }
            return true;
        } catch (Exception e) { return false; }
    }

    File capFile(String key) { return new File(capDir, key.replaceAll("[^A-Za-z0-9_-]", "_") + ".cap"); }
    boolean hasCaps(Media m) { return capFile(m.key()).isFile(); }

    String readCaps(String key) {
        File f = capFile(key);
        if (!f.isFile()) return null;
        try (FileInputStream in = new FileInputStream(f)) {
            ByteArrayOutputStream b = new ByteArrayOutputStream();
            byte[] buf = new byte[16384];
            int n;
            while ((n = in.read(buf)) > 0) b.write(buf, 0, n);
            return b.toString("UTF-8");
        } catch (Exception e) { return null; }
    }

    boolean saveCaps(String key, String text) {
        try {
            capDir.mkdirs();
            try (FileOutputStream o = new FileOutputStream(capFile(key))) { o.write(text.getBytes("UTF-8")); }
            return true;
        } catch (Exception e) { return false; }
    }

    void deleteCaps(String key) { capFile(key).delete(); }

    List<String> capKeys() {
        List<String> r = new ArrayList<>();
        File[] fs = capDir.listFiles();
        if (fs != null) for (File f : fs) {
            String n = f.getName();
            if (n.endsWith(".cap")) r.add(n.substring(0, n.length() - 4));
        }
        return r;
    }

    List<String> list(String k) {
        List<String> r = new ArrayList<>();
        try {
            JSONArray a = new JSONArray(p.getString(k, "[]"));
            for (int i = 0; i < a.length(); i++) r.add(a.getString(i));
        } catch (Exception e) { }
        return r;
    }

    void save(String k, List<String> l) { p.edit().putString(k, new JSONArray(l).toString()).apply(); }

    void pushFront(String k, String v, int max) {
        List<String> l = list(k);
        l.remove(v);
        l.add(0, v);
        while (l.size() > max) l.remove(l.size() - 1);
        save(k, l);
    }

    boolean bool(String k, boolean d) { return p.getBoolean(k, d); }
    void setBool(String k, boolean v) { p.edit().putBoolean(k, v).apply(); }

    String hk(boolean v) { return "hist_" + (v ? "v" : "a"); }
    String sk(boolean v) { return "srch_" + (v ? "v" : "a"); }
    void addHistory(Media m) {
        pushFront(hk(m.video), m.key(), 100);
        p.edit().putInt("pc_" + m.key(), plays(m) + 1).apply();
    }
    int plays(Media m) { return p.getInt("pc_" + m.key(), 0); }

    int sortMode(boolean v) { return p.getInt("sort_" + (v ? "v" : "a"), 1); }
    boolean sortAsc(boolean v) { return p.getBoolean("asc_" + (v ? "v" : "a"), false); }
    void setSort(boolean v, int mode, boolean asc) {
        p.edit().putInt("sort_" + (v ? "v" : "a"), mode).putBoolean("asc_" + (v ? "v" : "a"), asc).apply();
    }
    boolean grid(boolean v) { return p.getBoolean("grid_" + (v ? "v" : "a"), false); }
    void setGrid(boolean v, boolean g) { p.edit().putBoolean("grid_" + (v ? "v" : "a"), g).apply(); }

    String renamed(Media m) { return p.getString("ren_" + m.key(), null); }
    void setRenamed(Media m, String t) {
        if (t == null) p.edit().remove("ren_" + m.key()).apply();
        else p.edit().putString("ren_" + m.key(), t).apply();
    }

    void clearMetaOverrides() {
        SharedPreferences.Editor e = p.edit();
        for (String k : p.getAll().keySet())
            if (k.startsWith("mt_") || k.startsWith("ren_")) e.remove(k);
        e.apply();
    }

    boolean isFav(Media m) { return list("fav").contains(m.key()); }
    void toggleFav(Media m) {
        List<String> l = list("fav");
        if (!l.remove(m.key())) l.add(0, m.key());
        save("fav", l);
    }
    void setFav(Media m, boolean on) {
        List<String> l = list("fav");
        boolean has = l.contains(m.key());
        if (on && !has) { l.add(0, m.key()); save("fav", l); }
        else if (!on && has) { l.remove(m.key()); save("fav", l); }
    }

    String pn(boolean v) { return "pln_" + (v ? "v" : "a"); }
    String pi(boolean v, String n) { return "pl_" + (v ? "v" : "a") + "_" + n; }
    List<String> playlists(boolean v) { return list(pn(v)); }
    void createPlaylist(boolean v, String n) {
        List<String> l = playlists(v);
        if (!l.contains(n)) { l.add(n); save(pn(v), l); }
    }
    void deletePlaylist(boolean v, String n) {
        List<String> l = playlists(v);
        l.remove(n);
        save(pn(v), l);
        p.edit().remove(pi(v, n)).apply();
    }
    List<String> items(boolean v, String n) { return list(pi(v, n)); }

    int addAllToPlaylist(List<Media> ms, String n) {
        int added = 0;
        for (int pass = 0; pass < 2; pass++) {
            boolean v = pass == 1;
            List<String> l = null;
            Set<String> have = null;
            boolean changed = false;
            for (Media m : ms) {
                if (m.video != v) continue;
                if (l == null) { l = items(v, n); have = new HashSet<>(l); }
                if (have.add(m.key())) { l.add(m.key()); added++; changed = true; }
            }
            if (changed) save(pi(v, n), l);
        }
        return added;
    }

    boolean renamePlaylist(boolean v, String from, String to) {
        to = to.trim();
        if (to.isEmpty()) return false;
        if (from.equals(to)) return true;
        List<String> l = playlists(v);
        int i = l.indexOf(from);
        if (i < 0 || l.contains(to)) return false;
        List<String> its = items(v, from);
        l.set(i, to);
        p.edit().putString(pn(v), new JSONArray(l).toString())
                .putString(pi(v, to), new JSONArray(its).toString())
                .remove(pi(v, from)).apply();
        return true;
    }

    void addToPlaylist(Media m, String n) {
        List<String> l = items(m.video, n);
        if (!l.contains(m.key())) { l.add(m.key()); save(pi(m.video, n), l); }
    }
    void removeFromPlaylist(Media m, String n) {
        List<String> l = items(m.video, n);
        l.remove(m.key());
        save(pi(m.video, n), l);
    }

    boolean isPhotoFav(Photo ph) { return list("pfav").contains(ph.key()); }

    void setPhotoFavs(List<Photo> phs, boolean on) {
        List<String> l = list("pfav");
        Set<String> have = new HashSet<>(l);
        boolean changed = false;
        for (Photo ph : phs) {
            if (on && have.add(ph.key())) { l.add(0, ph.key()); changed = true; }
            else if (!on && have.remove(ph.key())) { l.remove(ph.key()); changed = true; }
        }
        if (changed) save("pfav", l);
    }

    void addPhotoHistory(Photo ph) {
        pushFront("hist_p", ph.key(), 100);
        p.edit().putInt("pv_" + ph.key(), photoViews(ph) + 1).apply();
    }
    int photoViews(Photo ph) { return p.getInt("pv_" + ph.key(), 0); }

    int psortMode() { return p.getInt("sort_p", 1); }
    boolean psortAsc() { return p.getBoolean("asc_p", false); }
    void setPsort(int mode, boolean asc) { p.edit().putInt("sort_p", mode).putBoolean("asc_p", asc).apply(); }
    boolean pgrid() { return p.getBoolean("grid_p", true); }
    void setPgrid(boolean g) { p.edit().putBoolean("grid_p", g).apply(); }

    List<String> collections() { return list("pln_p"); }
    String ci(String n) { return "pl_p_" + n; }
    List<String> collectionItems(String n) { return list(ci(n)); }

    boolean createCollection(String n) {
        List<String> l = collections();
        if (l.contains(n)) return false;
        l.add(n);
        save("pln_p", l);
        return true;
    }

    void deleteCollection(String n) {
        List<String> l = collections();
        l.remove(n);
        save("pln_p", l);
        p.edit().remove(ci(n)).apply();
    }

    boolean renameCollection(String from, String to) {
        to = to.trim();
        if (to.isEmpty()) return false;
        if (from.equals(to)) return true;
        List<String> l = collections();
        int i = l.indexOf(from);
        if (i < 0 || l.contains(to)) return false;
        List<String> its = collectionItems(from);
        l.set(i, to);
        p.edit().putString("pln_p", new JSONArray(l).toString()).putString(ci(to), new JSONArray(its).toString())
                .remove(ci(from)).apply();
        return true;
    }

    int addToCollection(List<Photo> phs, String n) {
        List<String> l = collectionItems(n);
        Set<String> have = new HashSet<>(l);
        int added = 0;
        for (Photo ph : phs) if (have.add(ph.key())) { l.add(ph.key()); added++; }
        if (added > 0) save(ci(n), l);
        return added;
    }

    void removeFromCollection(List<Photo> phs, String n) {
        List<String> l = collectionItems(n);
        Set<String> gone = new HashSet<>();
        for (Photo ph : phs) gone.add(ph.key());
        if (l.removeAll(gone)) save(ci(n), l);
    }

    void purgePhotos(List<Photo> phs) {
        Set<String> gone = new HashSet<>();
        for (Photo ph : phs) gone.add(ph.key());
        List<String> lists = new ArrayList<>(Arrays.asList("pfav", "hist_p"));
        for (String n : collections()) lists.add(ci(n));
        for (String k : lists) {
            List<String> l = list(k);
            if (l.removeAll(gone)) save(k, l);
        }
        SharedPreferences.Editor e = p.edit();
        for (Photo ph : phs) e.remove("pv_" + ph.key());
        e.apply();
    }

    static final Set<String> DATA_KEYS = new HashSet<>(Arrays.asList("fav", "pfav", "q_idx", "q_pos", "q_shuf", "q_rep"));

    static boolean isData(String k) {
        return DATA_KEYS.contains(k) || k.startsWith("hist_") || k.startsWith("srch_") || k.startsWith("pl_") || k.startsWith("pln_")
                || k.startsWith("pc_") || k.startsWith("ren_") || k.startsWith("sync_") || k.startsWith("mt_") || k.startsWith("pv_");
    }

    JSONArray settingsJson() throws Exception {
        JSONArray a = new JSONArray();
        for (Map.Entry<String, ?> en : p.getAll().entrySet()) {
            String k = en.getKey();
            Object v = en.getValue();
            if (v == null || isData(k)) continue;
            JSONObject o = new JSONObject();
            o.put("k", k);
            if (v instanceof Boolean) { o.put("t", "b"); o.put("v", ((Boolean) v).booleanValue()); }
            else if (v instanceof Integer) { o.put("t", "i"); o.put("v", ((Integer) v).intValue()); }
            else if (v instanceof Long) { o.put("t", "l"); o.put("v", ((Long) v).longValue()); }
            else if (v instanceof Float) { o.put("t", "f"); o.put("v", ((Float) v).doubleValue()); }
            else if (v instanceof String) { o.put("t", "s"); o.put("v", (String) v); }
            else continue;
            a.put(o);
        }
        return a;
    }

    int applySettings(JSONArray a, boolean replace) {
        if (a == null) return 0;
        SharedPreferences.Editor e = p.edit();
        int n = 0;
        for (int i = 0; i < a.length(); i++) {
            JSONObject o = a.optJSONObject(i);
            if (o == null) continue;
            String k = o.optString("k", ""), t = o.optString("t", "");
            if (k.isEmpty() || isData(k) || (!replace && p.contains(k))) continue;
            switch (t) {
                case "b": e.putBoolean(k, o.optBoolean("v")); break;
                case "i": e.putInt(k, o.optInt("v")); break;
                case "l": e.putLong(k, o.optLong("v")); break;
                case "f": e.putFloat(k, (float) o.optDouble("v")); break;
                case "s": e.putString(k, o.optString("v")); break;
                default: continue;
            }
            n++;
        }
        e.apply();
        return n;
    }

    void purge(Media m) {
        for (String k : new String[]{"fav", hk(m.video)}) {
            List<String> l = list(k);
            if (l.remove(m.key())) save(k, l);
        }
        for (String n : playlists(m.video)) removeFromPlaylist(m, n);
        p.edit().remove("pc_" + m.key()).remove("ren_" + m.key()).remove("sync_" + m.key()).remove("mt_" + m.key()).apply();
        deleteCaps(m.key());
        deleteCover(m.key());
    }

    void rekey(String o, String n) {
        List<String> keys = new ArrayList<>();
        for (Map.Entry<String, ?> en : p.getAll().entrySet()) {
            String k = en.getKey();
            if ((k.equals("fav") || k.startsWith("hist_") || k.startsWith("pl_")) && en.getValue() instanceof String) keys.add(k);
        }
        for (String k : keys) {
            List<String> l = list(k);
            int i = l.indexOf(o);
            if (i >= 0) { l.set(i, n); save(k, l); }
        }
        SharedPreferences.Editor e = p.edit();
        if (p.contains("pc_" + o)) e.putInt("pc_" + n, p.getInt("pc_" + o, 0)).remove("pc_" + o);
        if (p.contains("ren_" + o)) e.putString("ren_" + n, p.getString("ren_" + o, "")).remove("ren_" + o);
        if (p.contains("sync_" + o)) e.putInt("sync_" + n, p.getInt("sync_" + o, 0)).remove("sync_" + o);
        if (p.contains("mt_" + o)) e.putString("mt_" + n, p.getString("mt_" + o, "")).remove("mt_" + o);
        e.apply();
        File cov = coverFile(o);
        if (cov.isFile()) cov.renameTo(coverFile(n));
        File cf = capFile(o);
        if (cf.isFile()) cf.renameTo(capFile(n));
    }
}
