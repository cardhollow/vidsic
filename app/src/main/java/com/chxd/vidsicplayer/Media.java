package com.chxd.vidsicplayer;

import android.net.Uri;
import org.json.JSONObject;

class Media {
    long id;
    Uri uri;
    String title, artist, album, folder, path, name;
    long duration, added, size;
    boolean video, hidden, folderHidden;
    String filePath; // set only for files found by the hidden-file scan

    String key() { return (video ? "v" : "a") + id; }

    /** Everything needed to rebuild this item without scanning the library (saved play queue). */
    JSONObject toJson() throws Exception {
        JSONObject o = new JSONObject();
        o.put("id", id);
        o.put("u", uri == null ? "" : uri.toString());
        o.put("t", title);
        o.put("a", artist);
        o.put("al", album);
        o.put("f", folder);
        o.put("p", path);
        o.put("n", name);
        o.put("d", duration);
        o.put("ad", added);
        o.put("s", size);
        o.put("v", video);
        o.put("h", hidden);
        o.put("fh", folderHidden);
        o.put("fp", filePath);
        return o;
    }

    static Media fromJson(JSONObject o) {
        String u = o.optString("u", "");
        if (u.isEmpty()) return null;
        Media m = new Media();
        m.id = o.optLong("id");
        m.uri = Uri.parse(u);
        m.title = o.optString("t", "");
        m.artist = o.optString("a", "");
        m.album = o.isNull("al") ? null : o.optString("al", null);
        m.folder = o.isNull("f") ? null : o.optString("f", null);
        m.path = o.isNull("p") ? null : o.optString("p", null);
        m.name = o.isNull("n") ? null : o.optString("n", null);
        m.duration = o.optLong("d");
        m.added = o.optLong("ad");
        m.size = o.optLong("s");
        m.video = o.optBoolean("v");
        m.hidden = o.optBoolean("h");
        m.folderHidden = o.optBoolean("fh");
        m.filePath = o.isNull("fp") ? null : o.optString("fp", null);
        return m;
    }
}
