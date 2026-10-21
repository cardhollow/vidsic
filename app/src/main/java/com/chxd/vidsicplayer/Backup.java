package com.chxd.vidsicplayer;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;
import java.util.zip.ZipOutputStream;
import org.json.JSONArray;
import org.json.JSONObject;

/**
 * vidsic_backup_<date>_<time>.zip
 *   favorites.json        favorites (audio + video)
 *   playlists_audio.json  audio playlists
 *   playlists_video.json  video playlists
 *   captions/<key>.json   imported captions, one file per video / audio (restore adds them, nothing is deleted)
 * Every item is saved with its key plus file name and size, so it can still be matched after a
 * reinstall or a library rescan changed the MediaStore ids.
 */
final class Backup {
    static final String FAV = "favorites.json", PL_A = "playlists_audio.json", PL_V = "playlists_video.json";

    static String fileName() {
        return "vidsic_backup_" + new SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(new Date()) + ".zip";
    }

    static class Result {
        int favorites, audioPlaylists, videoPlaylists, captions;
    }

    // ---------- create ----------
    static JSONObject item(String key, Map<String, Media> byKey) throws Exception {
        JSONObject o = new JSONObject();
        o.put("key", key);
        Media m = byKey.get(key);
        if (m != null) {
            if (m.name != null) o.put("name", m.name);
            o.put("size", m.size);
            o.put("title", m.title);
        }
        return o;
    }

    static JSONArray items(List<String> keys, Map<String, Media> byKey) throws Exception {
        JSONArray a = new JSONArray();
        for (String k : keys) a.put(item(k, byKey));
        return a;
    }

    static JSONObject playlists(Store s, boolean video, Map<String, Media> byKey) throws Exception {
        JSONObject root = new JSONObject();
        root.put("version", 1);
        root.put("type", video ? "video" : "audio");
        JSONArray pls = new JSONArray();
        for (String n : s.playlists(video)) {
            JSONObject p = new JSONObject();
            p.put("name", n);
            p.put("items", items(s.items(video, n), byKey));
            pls.put(p);
        }
        root.put("playlists", pls);
        return root;
    }

    static void write(ZipOutputStream z, String name, JSONObject o) throws Exception {
        z.putNextEntry(new ZipEntry(name));
        z.write(o.toString(2).getBytes("UTF-8"));
        z.closeEntry();
    }

    static void create(Store s, Map<String, Media> byKey, OutputStream os) throws Exception {
        ZipOutputStream z = new ZipOutputStream(os);
        JSONObject fav = new JSONObject();
        fav.put("version", 1);
        fav.put("items", items(s.list("fav"), byKey));
        write(z, FAV, fav);
        write(z, PL_A, playlists(s, false, byKey));
        write(z, PL_V, playlists(s, true, byKey));
        for (String k : s.capKeys()) {
            String text = s.readCaps(k);
            if (text == null) continue;
            JSONObject o = item(k, byKey);
            o.put("version", 1);
            o.put("text", text);
            write(z, "captions/" + k + ".json", o);
        }
        z.finish();
        z.flush();
    }

    // ---------- restore ----------
    static String key(JSONObject e, boolean video, Map<String, Media> byKey) {
        String key = e.optString("key", "");
        String name = e.optString("name", "");
        long size = e.optLong("size", 0);
        Media m = byKey.get(key);
        if (m != null && (name.isEmpty() || name.equals(m.name))) return key;
        if (!name.isEmpty()) {
            for (Media c : byKey.values())
                if (c.video == video && name.equals(c.name) && (size <= 0 || c.size == size)) return c.key();
        }
        return key; // not in the library right now (yet): keep it as it was
    }

    static List<String> keys(JSONArray a, boolean video, Map<String, Media> byKey) {
        Set<String> out = new LinkedHashSet<>();
        if (a != null)
            for (int i = 0; i < a.length(); i++) {
                JSONObject e = a.optJSONObject(i);
                if (e == null) continue;
                String k = key(e, video, byKey);
                if (!k.isEmpty()) out.add(k);
            }
        return new ArrayList<>(out);
    }

    static String read(ZipInputStream z) throws Exception {
        ByteArrayOutputStream b = new ByteArrayOutputStream();
        byte[] buf = new byte[8192];
        int n;
        while ((n = z.read(buf)) > 0) {
            b.write(buf, 0, n);
            if (b.size() > 20 * 1024 * 1024) throw new Exception("too big");
        }
        return b.toString("UTF-8");
    }

    /** Replaces favorites and playlists with the ones in the zip. Throws when it isn't a Vidsic backup. */
    static Result restore(Store s, Map<String, Media> byKey, InputStream is) throws Exception {
        String fav = null, pa = null, pv = null;
        List<String> caps = new ArrayList<>();
        ZipInputStream z = new ZipInputStream(is);
        ZipEntry e;
        while ((e = z.getNextEntry()) != null) {
            String n = e.getName();
            if (n.startsWith("captions/") && n.endsWith(".json")) { caps.add(read(z)); continue; }
            int slash = n.lastIndexOf('/');
            if (slash >= 0) n = n.substring(slash + 1);
            if (n.equals(FAV)) fav = read(z);
            else if (n.equals(PL_A)) pa = read(z);
            else if (n.equals(PL_V)) pv = read(z);
        }
        if (fav == null && pa == null && pv == null && caps.isEmpty()) throw new Exception("not a backup");

        // parse everything first so a broken file can't leave things half restored
        JSONArray arr = fav == null ? null : new JSONObject(fav).optJSONArray("items");
        List<String[]> plA = pa == null ? null : parsePl(pa, false, byKey);
        List<String[]> plV = pv == null ? null : parsePl(pv, true, byKey);
        List<String[]> cp = new ArrayList<>(); // [key, text]
        for (String c : caps) {
            JSONObject o = new JSONObject(c);
            String text = o.optString("text", "");
            String k0 = o.optString("key", "");
            if (text.isEmpty() || k0.isEmpty()) continue;
            cp.add(new String[]{key(o, k0.startsWith("v"), byKey), text});
        }

        Result r = new Result();
        if (fav != null) {
            // favorites hold both audio and video keys; "v" keys are matched by the video flag
            if (arr == null) arr = new JSONArray();
            List<String> fixed = new ArrayList<>();
            Set<String> seen = new LinkedHashSet<>();
            for (int i = 0; i < arr.length(); i++) {
                JSONObject o = arr.optJSONObject(i);
                if (o == null) continue;
                String k = o.optString("key", "");
                boolean video = k.startsWith("v");
                String nk = key(o, video, byKey);
                if (!nk.isEmpty() && seen.add(nk)) fixed.add(nk);
            }
            s.save("fav", fixed);
            r.favorites = fixed.size();
        }
        if (plA != null) { applyPl(s, false, plA); r.audioPlaylists = plA.size(); }
        if (plV != null) { applyPl(s, true, plV); r.videoPlaylists = plV.size(); }
        for (String[] c : cp) if (s.saveCaps(c[0], c[1])) r.captions++;
        return r;
    }

    /** each entry: [name, key1, key2, ...] */
    static List<String[]> parsePl(String json, boolean video, Map<String, Media> byKey) throws Exception {
        List<String[]> out = new ArrayList<>();
        JSONArray pls = new JSONObject(json).optJSONArray("playlists");
        if (pls == null) return out;
        for (int i = 0; i < pls.length(); i++) {
            JSONObject p = pls.optJSONObject(i);
            if (p == null) continue;
            String name = p.optString("name", "").trim();
            if (name.isEmpty()) continue;
            List<String> ks = keys(p.optJSONArray("items"), video, byKey);
            String[] row = new String[ks.size() + 1];
            row[0] = name;
            for (int k = 0; k < ks.size(); k++) row[k + 1] = ks.get(k);
            out.add(row);
        }
        return out;
    }

    static void applyPl(Store s, boolean video, List<String[]> pls) {
        for (String n : s.playlists(video)) s.deletePlaylist(video, n);
        for (String[] row : pls) {
            s.createPlaylist(video, row[0]);
            List<String> ks = new ArrayList<>();
            for (int i = 1; i < row.length; i++) ks.add(row[i]);
            s.save(s.pi(video, row[0]), ks);
        }
    }
}
