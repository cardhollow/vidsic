package com.chxd.vidsicplayer;

import android.content.Context;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.HashMap;
import java.util.HashSet;
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

final class Backup {
    static final String FAV = "favorites.json", PL_A = "playlists_audio.json", PL_V = "playlists_video.json",
            PHO = "photos.json", SET = "settings.json", EDT = "edits.json";

    static String fileName() {
        return "vidsic_backup_" + new SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(new Date()) + ".zip";
    }

    static class LegacyEdit {
        final Media media;
        final TagWriter.Fields fields;
        LegacyEdit(Media media, TagWriter.Fields fields) { this.media = media; this.fields = fields; }
    }

    static class Result {
        int favorites, audioPlaylists, videoPlaylists, captions, settings, photoFavorites, collections;
        final List<LegacyEdit> legacyEdits = new ArrayList<>();
    }

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

    static JSONArray photoItems(List<String> keys, Map<String, Photo> byKey) throws Exception {
        JSONArray a = new JSONArray();
        for (String k : keys) {
            JSONObject o = new JSONObject();
            o.put("key", k);
            Photo p = byKey.get(k);
            if (p != null) {
                if (p.name != null) o.put("name", p.name);
                o.put("size", p.size);
            }
            a.put(o);
        }
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

    static JSONObject photos(Store s, Map<String, Photo> byKey) throws Exception {
        JSONObject root = new JSONObject();
        root.put("version", 1);
        root.put("favorites", photoItems(s.list("pfav"), byKey));
        JSONArray cols = new JSONArray();
        for (String n : s.collections()) {
            JSONObject c = new JSONObject();
            c.put("name", n);
            c.put("items", photoItems(s.collectionItems(n), byKey));
            cols.put(c);
        }
        root.put("collections", cols);
        return root;
    }

    static void write(ZipOutputStream z, String name, JSONObject o) throws Exception {
        z.putNextEntry(new ZipEntry(name));
        z.write(o.toString(2).getBytes("UTF-8"));
        z.closeEntry();
    }

    private static JSONObject editsForBackup(Context context, Store s, Map<String, Media> byKey) throws Exception {
        JSONObject source = s.editsJson();
        JSONObject values = source.optJSONObject("values");
        JSONObject kept = new JSONObject();
        if (values != null) {
            java.util.Iterator<String> it = values.keys();
            while (it.hasNext()) {
                String k = it.next();
                Object raw = values.opt(k);
                if (k.startsWith("pv_")) { kept.put(k, raw); continue; }
                if (k.startsWith("ren_") && raw instanceof String) {
                    Media m = byKey.get(k.substring(4));
                    if (m == null) { kept.put(k, raw); continue; }
                    TagWriter.Fields f = new TagWriter.Fields(); f.title = (String) raw;
                    if (!TagWriter.alreadyMatches(context, m, f)) kept.put(k, raw);
                    continue;
                }
                if (k.startsWith("mt_") && raw instanceof String) {
                    Media m = byKey.get(k.substring(3));
                    if (m == null) { kept.put(k, raw); continue; }
                    JSONObject incoming;
                    try { incoming = new JSONObject((String) raw); }
                    catch (Exception e) { kept.put(k, raw); continue; }
                    JSONObject remaining = new JSONObject();
                    String[] fields = {"artist", "album", "genre", "desc"};
                    for (String field : fields) if (incoming.has(field)) {
                        String value = incoming.optString(field, "");
                        TagWriter.Fields f = new TagWriter.Fields();
                        if (field.equals("artist")) f.artist = value;
                        else if (field.equals("album")) f.album = value;
                        else if (field.equals("genre")) f.genre = value;
                        else f.desc = value;
                        if (!TagWriter.alreadyMatches(context, m, f)) remaining.put(field, value);
                    }
                    if (remaining.length() > 0) kept.put(k, remaining.toString());
                }
            }
        }
        JSONObject filtered = new JSONObject();
        filtered.put("version", source.optInt("version", 1));
        filtered.put("values", kept);
        return filtered;
    }

    static void create(Context context, Store s, Map<String, Media> byKey, Map<String, Photo> photoByKey, OutputStream os) throws Exception {
        ZipOutputStream z = new ZipOutputStream(os);
        JSONObject fav = new JSONObject();
        fav.put("version", 1);
        fav.put("items", items(s.list("fav"), byKey));
        write(z, FAV, fav);
        write(z, PL_A, playlists(s, false, byKey));
        write(z, PL_V, playlists(s, true, byKey));
        write(z, PHO, photos(s, photoByKey));
        JSONObject set = new JSONObject();
        set.put("version", 1);
        set.put("settings", s.settingsJson());
        write(z, SET, set);
        write(z, EDT, editsForBackup(context, s, byKey));
        for (java.io.File cf : s.coverFiles()) {
            z.putNextEntry(new ZipEntry("covers/" + cf.getName()));
            try (java.io.FileInputStream in = new java.io.FileInputStream(cf)) {
                byte[] buf = new byte[8192];
                int n;
                while ((n = in.read(buf)) > 0) z.write(buf, 0, n);
            }
            z.closeEntry();
        }
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
        return key;
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

    static List<String> photoKeys(JSONArray a, Map<String, Photo> byKey, Map<String, List<Photo>> byName) {
        Set<String> out = new LinkedHashSet<>();
        if (a != null)
            for (int i = 0; i < a.length(); i++) {
                JSONObject e = a.optJSONObject(i);
                if (e == null) continue;
                String key = e.optString("key", ""), name = e.optString("name", "");
                long size = e.optLong("size", 0);
                Photo p = byKey.get(key);
                String k = key;
                if (p != null && (name.isEmpty() || name.equals(p.name))) k = key;
                else if (!name.isEmpty() && byName.containsKey(name)) {
                    for (Photo c : byName.get(name)) if (size <= 0 || c.size == size) { k = c.key(); break; }
                }
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

    private static List<LegacyEdit> legacyMediaEdits(JSONObject document, Map<String, Media> byKey) {
        Map<String, LegacyEdit> grouped = new java.util.LinkedHashMap<>();
        if (document == null) return new ArrayList<>();
        JSONObject values = document.optJSONObject("values");
        if (values == null) return new ArrayList<>();
        java.util.Iterator<String> it = values.keys();
        while (it.hasNext()) {
            String k = it.next();
            String mediaKey = null;
            String type = null;
            if (k.startsWith("ren_")) { mediaKey = k.substring(4); type = "title"; }
            else if (k.startsWith("mt_")) { mediaKey = k.substring(3); type = "meta"; }
            if (mediaKey == null) continue;
            Media m = byKey.get(mediaKey);
            if (m == null) continue;
            LegacyEdit e = grouped.get(mediaKey);
            if (e == null) { e = new LegacyEdit(m, new TagWriter.Fields()); grouped.put(mediaKey, e); }
            Object raw = values.opt(k);
            if ("title".equals(type)) {
                if (raw instanceof String) e.fields.title = (String) raw;
            } else if (raw instanceof String) {
                try {
                    JSONObject meta = new JSONObject((String) raw);
                    if (meta.has("artist")) e.fields.artist = meta.optString("artist", "");
                    if (meta.has("album")) e.fields.album = meta.optString("album", "");
                    if (meta.has("genre")) e.fields.genre = meta.optString("genre", "");
                    if (meta.has("desc")) e.fields.desc = meta.optString("desc", "");
                } catch (Exception ignored) { }
            }
        }
        List<LegacyEdit> out = new ArrayList<>();
        for (LegacyEdit e : grouped.values()) if (e.fields.any()) out.add(e);
        return out;
    }

    private static JSONObject nonMetadataEdits(JSONObject document) throws Exception {
        JSONObject out = new JSONObject();
        if (document == null) return out;
        out.put("version", document.optInt("version", 1));
        JSONObject values = document.optJSONObject("values"), keep = new JSONObject();
        if (values != null) {
            java.util.Iterator<String> it = values.keys();
            while (it.hasNext()) {
                String k = it.next();
                if (k.startsWith("mt_") || k.startsWith("ren_")) continue;
                keep.put(k, values.opt(k));
            }
        }
        out.put("values", keep);
        return out;
    }

    static Result restore(Store s, Map<String, Media> byKey, Map<String, Photo> photoByKey, InputStream is, boolean replace) throws Exception {
        String fav = null, pa = null, pv = null, pho = null, set = null, edt = null;
        List<String> caps = new ArrayList<>();
        Map<String, byte[]> covers = new HashMap<>();
        ZipInputStream z = new ZipInputStream(is);
        ZipEntry e;
        while ((e = z.getNextEntry()) != null) {
            String n = e.getName();
            if (n.startsWith("captions/") && n.endsWith(".json")) { caps.add(read(z)); continue; }
            if (n.startsWith("covers/") && n.endsWith(".jpg")) {
                java.io.ByteArrayOutputStream bo = new java.io.ByteArrayOutputStream();
                byte[] buf = new byte[8192];
                int c;
                while ((c = z.read(buf)) > 0 && bo.size() < 8 * 1024 * 1024) bo.write(buf, 0, c);
                covers.put(n.substring(7), bo.toByteArray());
                continue;
            }
            int slash = n.lastIndexOf('/');
            if (slash >= 0) n = n.substring(slash + 1);
            if (n.equals(EDT)) edt = read(z);
            else if (n.equals(FAV)) fav = read(z);
            else if (n.equals(PL_A)) pa = read(z);
            else if (n.equals(PL_V)) pv = read(z);
            else if (n.equals(PHO)) pho = read(z);
            else if (n.equals(SET)) set = read(z);
        }
        if (fav == null && pa == null && pv == null && pho == null && set == null && edt == null && caps.isEmpty()) throw new Exception("not a backup");

        JSONObject legacyDocument = edt == null ? null : new JSONObject(edt);
        List<LegacyEdit> migratedMetadata = legacyMediaEdits(legacyDocument, byKey);
        JSONArray arr = fav == null ? null : new JSONObject(fav).optJSONArray("items");
        List<String[]> plA = pa == null ? null : parsePl(pa, false, byKey);
        List<String[]> plV = pv == null ? null : parsePl(pv, true, byKey);
        JSONArray settings = set == null ? null : new JSONObject(set).optJSONArray("settings");
        List<String> pFav = null;
        List<String[]> cols = null;
        if (pho != null) {
            JSONObject po = new JSONObject(pho);
            Map<String, List<Photo>> byName = new HashMap<>();
            for (Photo p : photoByKey.values()) {
                if (p.name == null) continue;
                List<Photo> l = byName.get(p.name);
                if (l == null) { l = new ArrayList<>(); byName.put(p.name, l); }
                l.add(p);
            }
            pFav = photoKeys(po.optJSONArray("favorites"), photoByKey, byName);
            cols = new ArrayList<>();
            JSONArray ca = po.optJSONArray("collections");
            if (ca != null)
                for (int i = 0; i < ca.length(); i++) {
                    JSONObject c = ca.optJSONObject(i);
                    if (c == null) continue;
                    String name = c.optString("name", "").trim();
                    if (name.isEmpty()) continue;
                    List<String> ks = photoKeys(c.optJSONArray("items"), photoByKey, byName);
                    String[] row = new String[ks.size() + 1];
                    row[0] = name;
                    for (int k = 0; k < ks.size(); k++) row[k + 1] = ks.get(k);
                    cols.add(row);
                }
        }
        List<String[]> cp = new ArrayList<>();
        for (String c : caps) {
            JSONObject o = new JSONObject(c);
            String text = o.optString("text", "");
            String k0 = o.optString("key", "");
            if (text.isEmpty() || k0.isEmpty()) continue;
            cp.add(new String[]{key(o, k0.startsWith("v"), byKey), text});
        }

        Result r = new Result();
        if (fav != null) {

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
            r.favorites = mergeList(s, "fav", fixed, replace);
        }
        if (plA != null) { applyPl(s, false, plA, replace); r.audioPlaylists = plA.size(); }
        if (plV != null) { applyPl(s, true, plV, replace); r.videoPlaylists = plV.size(); }
        if (pFav != null) r.photoFavorites = mergeList(s, "pfav", pFav, replace);
        if (cols != null) { applyCollections(s, cols, replace); r.collections = cols.size(); }
        for (String[] c : cp) {
            if (!replace && s.capFile(c[0]).isFile()) continue;
            if (s.saveCaps(c[0], c[1])) r.captions++;
        }
        r.settings = s.applySettings(settings, replace);
        if (replace) s.clearMetaOverrides();
        if (edt != null) s.applyEdits(nonMetadataEdits(new JSONObject(edt)), replace);
        r.legacyEdits.addAll(migratedMetadata);
        for (Map.Entry<String, byte[]> c : covers.entrySet()) s.putCoverFile(c.getKey(), c.getValue(), replace);
        return r;
    }

    static int mergeList(Store s, String key, List<String> incoming, boolean replace) {
        if (replace) { s.save(key, incoming); return incoming.size(); }
        List<String> cur = s.list(key);
        Set<String> have = new HashSet<>(cur);
        int added = 0;
        for (String k : incoming) if (have.add(k)) { cur.add(k); added++; }
        if (added > 0) s.save(key, cur);
        return added;
    }

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

    static void applyPl(Store s, boolean video, List<String[]> pls, boolean replace) {
        if (replace) for (String n : s.playlists(video)) s.deletePlaylist(video, n);
        for (String[] row : pls) {
            List<String> ks = replace ? new ArrayList<String>() : s.items(video, row[0]);
            Set<String> have = new HashSet<>(ks);
            for (int i = 1; i < row.length; i++) if (have.add(row[i])) ks.add(row[i]);
            s.createPlaylist(video, row[0]);
            s.save(s.pi(video, row[0]), ks);
        }
    }

    static void applyCollections(Store s, List<String[]> cols, boolean replace) {
        if (replace) for (String n : s.collections()) s.deleteCollection(n);
        for (String[] row : cols) {
            List<String> ks = replace ? new ArrayList<String>() : s.collectionItems(row[0]);
            Set<String> have = new HashSet<>(ks);
            for (int i = 1; i < row.length; i++) if (have.add(row[i])) ks.add(row[i]);
            s.createCollection(row[0]);
            s.save(s.ci(row[0]), ks);
        }
    }
}
