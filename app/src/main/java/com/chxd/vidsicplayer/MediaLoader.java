package com.chxd.vidsicplayer;

import android.content.ContentUris;
import android.content.Context;
import android.database.Cursor;
import android.media.MediaMetadataRetriever;
import android.net.Uri;
import android.os.Environment;
import android.provider.MediaStore;
import java.io.File;
import java.util.ArrayList;
import java.util.List;

class MediaLoader {
    static final String[] AEXT = {"mp3", "m4a", "aac", "wav", "flac", "ogg", "opus", "amr", "wma", "mka", "m4b"};
    static final String[] VEXT = {"mp4", "mkv", "webm", "3gp", "avi", "mov", "m4v", "ts", "flv", "wmv"};

    static boolean hiddenPath(String rel) {
        if (rel == null) return false;
        for (String seg : rel.split("/")) if (seg.startsWith(".") && seg.length() > 1) return true;
        return false;
    }

    static List<Media> load(Context c, boolean video) {
        List<Media> out = new ArrayList<>();
        Uri base = video ? MediaStore.Video.Media.EXTERNAL_CONTENT_URI : MediaStore.Audio.Media.EXTERNAL_CONTENT_URI;
        String[] proj = new String[]{"_id", "title", "duration", "date_added", "relative_path", "_display_name", "artist", "album", "_size"};
        try (Cursor cu = c.getContentResolver().query(base, proj, null, null, "date_added DESC")) {
            if (cu == null) return out;
            while (cu.moveToNext()) {
                Media m = new Media();
                m.video = video;
                m.id = cu.getLong(0);
                m.uri = ContentUris.withAppendedId(base, m.id);
                m.title = cu.getString(1);
                if (m.title == null || m.title.isEmpty()) m.title = cu.getString(5);
                if (m.title == null) m.title = "Unknown";
                m.duration = cu.isNull(2) ? 0 : cu.getLong(2);
                m.added = cu.getLong(3);
                String rel = cu.getString(4);
                if (!video && rel != null && (rel.startsWith("Ringtones") || rel.startsWith("Notifications") || rel.startsWith("Alarms")))
                    continue;
                m.path = rel == null ? "Other" : rel;
                String[] seg = m.path.split("/");
                m.folder = seg.length > 0 && !seg[seg.length - 1].isEmpty() ? seg[seg.length - 1] : "Other";
                m.name = cu.getString(5);
                m.size = cu.isNull(8) ? 0 : cu.getLong(8);
                String al = cu.getString(7);
                m.album = (al == null || al.isEmpty() || al.equals("<unknown>")) ? "Unknown album" : al;
                m.hidden = hiddenPath(rel);
                m.folderHidden = m.hidden;
                String ar = cu.getString(6);
                m.artist = (ar == null || ar.equals("<unknown>")) ? (video ? m.folder : "Unknown artist") : ar;
                out.add(m);
            }
        } catch (Exception e) { }
        return out;
    }

    /** Needs All-files access. Finds media inside .nomedia / dot-folders / dot-files that MediaStore skips. */
    static void scanHidden(List<Media> aOut, List<Media> vOut) {
        File root = Environment.getExternalStorageDirectory();
        walk(root, root, false, 0, aOut, vOut);
    }

    static void walk(File root, File dir, boolean hid, int depth, List<Media> aOut, List<Media> vOut) {
        File[] fs = dir.listFiles();
        if (fs == null || depth > 12) return;
        boolean h = hid || new File(dir, ".nomedia").exists();
        for (File f : fs) {
            String n = f.getName();
            if (f.isDirectory()) {
                if (depth == 0 && n.equals("Android")) continue;
                if (n.equals(".thumbnails") || n.equals(".cache") || n.startsWith(".trashed")) continue;
                walk(root, f, h || n.startsWith("."), depth + 1, aOut, vOut);
            } else {
                boolean fh = h || n.startsWith(".");
                if (!fh || n.startsWith(".trashed") || n.startsWith(".pending")) continue;
                int dot = n.lastIndexOf('.');
                if (dot < 0) continue;
                String ext = n.substring(dot + 1).toLowerCase();
                boolean isV = has(VEXT, ext), isA = has(AEXT, ext);
                if (!isV && !isA) continue;
                Media m = new Media();
                m.video = isV;
                m.filePath = f.getAbsolutePath();
                m.uri = Uri.fromFile(f);
                m.id = -(Math.abs((long) m.filePath.hashCode()) + 1);
                m.hidden = true;
                m.folderHidden = h;
                m.name = n;
                m.size = f.length();
                m.added = f.lastModified() / 1000;
                String rp = dir.getAbsolutePath().substring(Math.min(dir.getAbsolutePath().length(), root.getAbsolutePath().length()));
                m.path = rp.startsWith("/") ? rp.substring(1) + "/" : rp + "/";
                m.folder = dir.getName();
                String t = n.substring(0, dot), ar = null, al = null;
                MediaMetadataRetriever r = new MediaMetadataRetriever();
                try {
                    r.setDataSource(m.filePath);
                    String d = r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION);
                    if (d != null) m.duration = Long.parseLong(d);
                    String tt = r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_TITLE);
                    if (tt != null && !tt.isEmpty()) t = tt;
                    ar = r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_ARTIST);
                    al = r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_ALBUM);
                } catch (Exception e) {
                } finally { try { r.release(); } catch (Exception e) { } }
                m.title = t;
                m.album = (al == null || al.isEmpty()) ? "Unknown album" : al;
                m.artist = (ar == null || ar.isEmpty()) ? (isV ? m.folder : "Unknown artist") : ar;
                (isV ? vOut : aOut).add(m);
            }
        }
    }

    static boolean has(String[] a, String x) { for (String s : a) if (s.equals(x)) return true; return false; }
}
