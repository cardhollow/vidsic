package com.chxd.vidsicplayer;

import android.content.ContentUris;
import android.content.Context;
import android.database.Cursor;
import android.net.Uri;
import android.provider.MediaStore;
import java.util.ArrayList;
import java.util.List;

class PhotoLoader {
    static List<Photo> load(Context c) {
        List<Photo> out = new ArrayList<>();
        Uri base = MediaStore.Images.Media.EXTERNAL_CONTENT_URI;
        String[] proj = new String[]{"_id", "_display_name", "date_added", "datetaken", "relative_path", "_size", "width", "height", "mime_type"};
        java.util.HashSet<Long> seen = new java.util.HashSet<>();
        try (Cursor cu = c.getContentResolver().query(base, proj, null, null, "date_added DESC")) {
            if (cu == null) return out;
            while (cu.moveToNext()) {
                Photo p = new Photo();
                p.id = cu.getLong(0);
                p.uri = ContentUris.withAppendedId(base, p.id);
                p.name = cu.getString(1);
                if (p.name == null || p.name.isEmpty()) p.name = "Picture " + p.id;
                int dot = p.name.lastIndexOf('.');
                p.title = dot > 0 ? p.name.substring(0, dot) : p.name;
                long taken = cu.isNull(3) ? 0 : cu.getLong(3);
                p.added = taken > 0 ? taken / 1000 : cu.getLong(2);
                String rel = cu.getString(4);
                p.path = rel == null ? "Other" : rel;
                String[] seg = p.path.split("/");
                p.folder = seg.length > 0 && !seg[seg.length - 1].isEmpty() ? seg[seg.length - 1] : "Other";
                p.size = cu.isNull(5) ? 0 : cu.getLong(5);
                p.w = cu.isNull(6) ? 0 : cu.getInt(6);
                p.h = cu.isNull(7) ? 0 : cu.getInt(7);
                String mime = cu.getString(8);
                p.svg = isSvg(p.name, mime);
                p.gif = isGif(p.name, mime);
                seen.add(p.id);
                out.add(p);
            }
        } catch (Exception e) { }

        Uri files = MediaStore.Files.getContentUri("external");
        try (Cursor cu = c.getContentResolver().query(files, new String[]{"_id", "_display_name", "date_added", "relative_path", "_size"},
                "_display_name LIKE ?", new String[]{"%.svg"}, "date_added DESC")) {
            if (cu != null) while (cu.moveToNext()) {
                long id = cu.getLong(0);
                if (seen.contains(id)) continue;
                Photo p = new Photo();
                p.id = id;
                p.uri = ContentUris.withAppendedId(files, id);
                p.name = cu.getString(1);
                if (p.name == null || p.name.isEmpty()) continue;
                int dot = p.name.lastIndexOf('.');
                p.title = dot > 0 ? p.name.substring(0, dot) : p.name;
                p.added = cu.getLong(2);
                String rel = cu.getString(3);
                p.path = rel == null ? "Other" : rel;
                String[] seg = p.path.split("/");
                p.folder = seg.length > 0 && !seg[seg.length - 1].isEmpty() ? seg[seg.length - 1] : "Other";
                p.size = cu.isNull(4) ? 0 : cu.getLong(4);
                p.svg = true;
                out.add(p);
            }
        } catch (Exception e) { }
        return out;
    }

    static boolean isGif(String name, String mime) {
        return (mime != null && mime.toLowerCase(java.util.Locale.US).contains("gif"))
                || (name != null && name.toLowerCase(java.util.Locale.US).endsWith(".gif"));
    }

    static boolean isSvg(String name, String mime) {
        return (mime != null && mime.toLowerCase(java.util.Locale.US).contains("svg"))
                || (name != null && name.toLowerCase(java.util.Locale.US).endsWith(".svg"));
    }
}
