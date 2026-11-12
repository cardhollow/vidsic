package com.chxd.vidsicplayer;

import android.app.Activity;
import android.app.PendingIntent;
import android.content.ContentValues;
import android.provider.MediaStore;
import java.text.DateFormat;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Date;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

final class PhotoOps {
    static final int REQ_DEL = 83, REQ_REN = 84;

    final MainActivity a;
    List<Photo> pendingDel = new ArrayList<>();
    Runnable pendingDelAfter, pendingRenAfter;
    Photo pendingRen;
    String pendingRenName;

    PhotoOps(MainActivity act) { a = act; }

    static String fmtSize(long b) {
        if (b < 1024) return b + " B";
        if (b < 1024 * 1024) return String.format(Locale.US, "%.0f KB", b / 1024.0);
        if (b < 1024L * 1024 * 1024) return String.format(Locale.US, "%.1f MB", b / 1048576.0);
        return String.format(Locale.US, "%.2f GB", b / 1073741824.0);
    }

    void saveFailed(final String what, final Runnable retry) {
        if (android.os.Environment.isExternalStorageManager()) {
            a.toast("Could not save " + what + ". Check that the device has free space and try again");
            return;
        }
        Ui.choose(a, "Could not save", "Android did not let the app save " + what + ". "
                        + "Allowing All files access for this app usually fixes this.",
                "Open settings", false, a::openAllFiles, "Try again", false, retry);
    }

    static String safeFolder(String rel) {
        if (rel != null && (rel.startsWith("DCIM/") || rel.startsWith("Pictures/"))) return rel.endsWith("/") ? rel : rel + "/";
        return "Pictures/Vidsic/";
    }

    boolean saveFile(byte[] data, String name, String mime, String rel) {
        rel = safeFolder(rel);
        android.net.Uri u = null;
        try {
            ContentValues v = new ContentValues();
            v.put(MediaStore.MediaColumns.DISPLAY_NAME, name);
            v.put(MediaStore.MediaColumns.MIME_TYPE, mime);
            v.put(MediaStore.MediaColumns.RELATIVE_PATH, rel);
            v.put(MediaStore.MediaColumns.IS_PENDING, 1);
            u = a.getContentResolver().insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, v);
            if (u == null) throw new Exception("insert");
            try (java.io.OutputStream os = a.getContentResolver().openOutputStream(u)) { os.write(data); }
            ContentValues done = new ContentValues();
            done.put(MediaStore.MediaColumns.IS_PENDING, 0);
            a.getContentResolver().update(u, done, null, null);
            return true;
        } catch (Throwable e) {
            if (u != null) { try { a.getContentResolver().delete(u, null, null); } catch (Throwable e2) { } }
        }
        if (android.os.Environment.isExternalStorageManager()) {
            try {
                java.io.File dir = new java.io.File(android.os.Environment.getExternalStorageDirectory(), rel);
                dir.mkdirs();
                java.io.File f = new java.io.File(dir, name);
                int dot = name.lastIndexOf('.'), n = 1;
                while (f.exists()) f = new java.io.File(dir, (dot > 0 ? name.substring(0, dot) : name) + " (" + (n++) + ")" + (dot > 0 ? name.substring(dot) : ""));
                try (java.io.FileOutputStream os = new java.io.FileOutputStream(f)) { os.write(data); }
                android.media.MediaScannerConnection.scanFile(a, new String[]{f.getAbsolutePath()}, new String[]{mime}, null);
                return true;
            } catch (Throwable e) { }
        }
        return false;
    }

    void confirmDelete(final List<Photo> ps, final Runnable after) {
        if (ps.isEmpty()) return;
        boolean one = ps.size() == 1;
        a.confirmDanger(one ? "Delete picture?" : "Delete " + ps.size() + " pictures?",
                one ? "\"" + ps.get(0).title + "\" will be permanently removed from your device."
                        : "These " + ps.size() + " pictures will be permanently removed from your device.",
                "Delete", () -> deleteNow(new ArrayList<>(ps), after));
    }

    void deleteNow(List<Photo> ps, Runnable after) {
        try {
            List<android.net.Uri> uris = new ArrayList<>(ps.size());
            for (Photo p : ps) uris.add(p.uri);
            PendingIntent pi = MediaStore.createDeleteRequest(a.getContentResolver(), uris);
            pendingDel = ps;
            pendingDelAfter = after;
            a.startIntentSenderForResult(pi.getIntentSender(), REQ_DEL, null, 0, 0, 0);
        } catch (Exception e) { a.toast("Could not delete"); }
    }

    void onDeleteResult(int res) {
        if (res == Activity.RESULT_OK) {
            List<Photo> gone = pendingDel;
            Runnable after = pendingDelAfter;
            forget(gone);
            if (after != null) after.run();
            a.toast(gone.size() > 1 ? "Deleted " + gone.size() + " pictures" : "Deleted");
        }
        pendingDel = new ArrayList<>();
        pendingDelAfter = null;
    }

    void forget(List<Photo> ps) {
        Set<Photo> gone = new HashSet<>(ps);
        List<Photo> keep = new ArrayList<>(a.photos.size());
        for (Photo p : a.photos) if (!gone.contains(p)) keep.add(p);
        a.photos = keep;
        for (Photo p : ps) a.photoByKey.remove(p.key());
        a.store.purgePhotos(ps);
        a.refreshPhotos();
    }

    void rename(Photo p, String raw, Runnable after) {
        String n = raw.replaceAll("[\\\\/:*?\"<>|]", "").trim();
        if (n.isEmpty()) { a.toast("That name can't be used"); return; }
        applyRename(p, n, false, after);
    }

    void applyRename(Photo p, String n, boolean granted, Runnable after) {
        ContentValues v = new ContentValues();
        String full = n + MainActivity.ext(p.name);
        v.put(MediaStore.MediaColumns.DISPLAY_NAME, full);
        try {
            int c = a.getContentResolver().update(p.uri, v, null, null);
            if (c > 0) {
                p.name = full;
                p.title = n;
                a.refreshPhotos();
                if (after != null) after.run();
                a.toast("Renamed");
            } else a.toast("Could not rename");
        } catch (SecurityException e) {
            if (granted) { a.toast("Could not rename"); return; }
            try {
                PendingIntent pi = MediaStore.createWriteRequest(a.getContentResolver(), Collections.singletonList(p.uri));
                pendingRen = p;
                pendingRenName = n;
                pendingRenAfter = after;
                a.startIntentSenderForResult(pi.getIntentSender(), REQ_REN, null, 0, 0, 0);
            } catch (Exception e2) { a.toast("Could not rename"); }
        } catch (Exception e) { a.toast("Could not rename. Is there already a picture with that name?"); }
    }

    void onRenameResult(int res) {
        if (res == Activity.RESULT_OK && pendingRen != null) applyRename(pendingRen, pendingRenName, true, pendingRenAfter);
        pendingRen = null;
        pendingRenName = null;
        pendingRenAfter = null;
    }

    void pickCollection(final List<Photo> ps, final Runnable after) {
        if (ps.isEmpty()) return;
        Ui.Sheet sh = new Ui.Sheet(a, "Add to collection", ps.size() > 1 ? ps.size() + " pictures" : null);
        sh.item(R.drawable.ic_add, "New collection", Ui.ACC, () -> a.promptName("New collection", n -> {
            a.store.createCollection(n);
            addTo(ps, n, after);
        }));
        for (final String n : a.store.collections())
            sh.item(R.drawable.ic_collections, n, Ui.TXT, () -> addTo(ps, n, after));
        sh.show();
    }

    void addTo(List<Photo> ps, String name, Runnable after) {
        int added = a.store.addToCollection(ps, name);
        a.refreshPhotos();
        a.toast(added == 0 ? "Already in " + name
                : added < ps.size() ? added + " added to " + name + " (" + (ps.size() - added) + " already in it)"
                : "Added to " + name);
        if (after != null) after.run();
    }

    void toggleFav(List<Photo> ps) {
        boolean allFav = true;
        for (Photo p : ps) if (!a.store.isPhotoFav(p)) { allFav = false; break; }
        a.store.setPhotoFavs(ps, !allFav);
        a.toast(allFav ? "Removed from favorites" : "Added to favorites");
        a.refreshPhotos();
    }

    void info(Photo p) {
        String when = p.added > 0 ? DateFormat.getDateTimeInstance().format(new Date(p.added * 1000)) : "";
        StringBuilder sb = new StringBuilder();
        sb.append(p.name).append("\n\n");
        sb.append("Folder: ").append(p.path == null ? "" : p.path).append('\n');
        if (p.w > 0 && p.h > 0) sb.append("Size: ").append(p.w).append(" x ").append(p.h).append("  (").append(fmtSize(p.size)).append(")\n");
        else sb.append("Size: ").append(fmtSize(p.size)).append('\n');
        if (!when.isEmpty()) sb.append("Date: ").append(when);
        Ui.notice(a, "Picture info", sb.toString().trim());
    }
}
