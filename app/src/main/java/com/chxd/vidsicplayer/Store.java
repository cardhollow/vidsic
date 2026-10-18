package com.chxd.vidsicplayer;

import android.content.Context;
import android.content.SharedPreferences;
import org.json.JSONArray;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

class Store {
    final SharedPreferences p;

    Store(Context c) { p = c.getSharedPreferences("vidsic", 0); }

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

    // history & searches
    String hk(boolean v) { return "hist_" + (v ? "v" : "a"); }
    String sk(boolean v) { return "srch_" + (v ? "v" : "a"); }
    void addHistory(Media m) {
        pushFront(hk(m.video), m.key(), 100);
        p.edit().putInt("pc_" + m.key(), plays(m) + 1).apply();
    }
    int plays(Media m) { return p.getInt("pc_" + m.key(), 0); }

    // sort / view mode, one set per page (audio / video)
    // sort modes: 0 name, 1 date added, 2 duration, 3 size, 4 most played
    int sortMode(boolean v) { return p.getInt("sort_" + (v ? "v" : "a"), 1); }
    boolean sortAsc(boolean v) { return p.getBoolean("asc_" + (v ? "v" : "a"), false); }
    void setSort(boolean v, int mode, boolean asc) {
        p.edit().putInt("sort_" + (v ? "v" : "a"), mode).putBoolean("asc_" + (v ? "v" : "a"), asc).apply();
    }
    boolean grid(boolean v) { return p.getBoolean("grid_" + (v ? "v" : "a"), false); }
    void setGrid(boolean v, boolean g) { p.edit().putBoolean("grid_" + (v ? "v" : "a"), g).apply(); }

    // custom names (rename)
    String renamed(Media m) { return p.getString("ren_" + m.key(), null); }
    void setRenamed(Media m, String t) { p.edit().putString("ren_" + m.key(), t).apply(); }

    // favorites
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

    // playlists (separate for audio / video)
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
    void addToPlaylist(Media m, String n) {
        List<String> l = items(m.video, n);
        if (!l.contains(m.key())) { l.add(m.key()); save(pi(m.video, n), l); }
    }
    void removeFromPlaylist(Media m, String n) {
        List<String> l = items(m.video, n);
        l.remove(m.key());
        save(pi(m.video, n), l);
    }

    void purge(Media m) {
        for (String k : new String[]{"fav", hk(m.video)}) {
            List<String> l = list(k);
            if (l.remove(m.key())) save(k, l);
        }
        for (String n : playlists(m.video)) removeFromPlaylist(m, n);
        p.edit().remove("pc_" + m.key()).remove("ren_" + m.key()).apply();
    }

    /** A hidden file got a new path (and so a new key): carry favorites, playlists, history and counters over. */
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
        e.apply();
    }
}
