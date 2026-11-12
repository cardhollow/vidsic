package com.chxd.vidsicplayer;

import android.app.Dialog;
import android.content.ContentValues;
import android.content.Intent;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.provider.MediaStore;
import android.text.InputType;
import android.view.Gravity;
import android.view.Window;
import android.view.WindowManager;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.graphics.drawable.ColorDrawable;
import android.net.Uri;
import java.io.InputStream;
import java.text.DateFormat;
import java.util.Date;

final class MetaEditor {
    static final int REQ_COVER = 85;
    static final String UNWRITABLE = "<Unwritable>", EDIT = "<Edit>";

    final MainActivity a;
    java.util.List<Media> coverFor;
    ImageView preview;
    java.util.List<Media> editing;

    MetaEditor(MainActivity act) { a = act; }

    /** One input of the editor. In multi mode it can be unwritable, locked behind <Edit> (values differ) or free. */
    private final class Field {
        EditText et;
        String original = "";
        boolean mixed, unlocked, disabled, dirty;

        String text() { return et.getText().toString().trim(); }

        /** The value to write, or null when this field was not changed by the user. */
        String result(boolean multi) {
            if (disabled) return null;
            if (multi && mixed) return unlocked ? text() : null;
            return dirty ? text() : null;
        }
    }

    private Field field(LinearLayout col, String label, String value, boolean multi, boolean mixed, boolean disabled, int count) {
        TextView l = Ui.text(a, label, 12, Ui.SUB, false);
        l.setPadding(0, Ui.dp(a, 10), 0, Ui.dp(a, 4));
        col.addView(l);
        final Field f = new Field();
        f.mixed = mixed;
        f.disabled = disabled;
        f.original = value == null ? "" : value.trim();
        final EditText et = new EditText(a);
        f.et = et;
        et.setTextColor(Ui.TXT);
        et.setHintTextColor(Ui.SUB);
        et.setTextSize(16);
        et.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_CAP_SENTENCES
                | (multi ? InputType.TYPE_TEXT_FLAG_MULTI_LINE : 0));
        if (multi) { et.setMinLines(3); et.setMaxLines(6); et.setGravity(Gravity.TOP | Gravity.START); }
        else et.setSingleLine(true);
        Ui.tintEdit(a, et);
        et.setBackground(Ui.shape(Ui.SURF2, 12, a));
        et.setPadding(Ui.dp(a, 14), Ui.dp(a, 10), Ui.dp(a, 14), Ui.dp(a, 10));
        if (disabled) {
            et.setText(UNWRITABLE);
            et.setTextColor(Ui.SUB);
            et.setEnabled(false);
            et.setAlpha(0.6f);
        } else if (mixed) {
            et.setText(EDIT);
            et.setTextColor(Ui.SUB);
            et.setHint("Applies to all " + count + " items");
            et.setFocusable(false);
            et.setFocusableInTouchMode(false);
            et.setCursorVisible(false);
            et.setOnClickListener(v -> {
                f.unlocked = true;
                et.setOnClickListener(null);
                et.setText("");
                et.setTextColor(Ui.TXT);
                et.setFocusable(true);
                et.setFocusableInTouchMode(true);
                et.setCursorVisible(true);
                et.requestFocus();
                et.post(() -> {
                    android.view.inputmethod.InputMethodManager imm =
                            (android.view.inputmethod.InputMethodManager) a.getSystemService(android.content.Context.INPUT_METHOD_SERVICE);
                    if (imm != null) imm.showSoftInput(et, 0);
                });
            });
        } else {
            et.setText(f.original);
        }
        et.addTextChangedListener(new android.text.TextWatcher() {
            public void beforeTextChanged(CharSequence s, int start, int count, int after) { }
            public void onTextChanged(CharSequence s, int start, int before, int count) { }
            public void afterTextChanged(android.text.Editable e) { f.dirty = true; }
        });
        col.addView(et, new LinearLayout.LayoutParams(Ui.MATCH, Ui.WRAP));
        return f;
    }

    private interface Get { String of(Media m); }

    /** Shared value of all items, or null when they differ. */
    private static String common(java.util.List<Media> ms, Get g) {
        String v = null;
        for (int i = 0; i < ms.size(); i++) {
            String s = g.of(ms.get(i));
            if (s == null) s = "";
            if (i == 0) v = s; else if (!v.equals(s)) return null;
        }
        return v;
    }

    private TextView pill(String label, int fg, int bg) {
        TextView t = Ui.text(a, label, 15, fg, true);
        t.setGravity(Gravity.CENTER);
        t.setPadding(Ui.dp(a, 18), Ui.dp(a, 10), Ui.dp(a, 18), Ui.dp(a, 10));
        t.setBackground(Ui.rip(bg, 20, a));
        return t;
    }

    void open(final Media m) { open(java.util.Collections.singletonList(m), null); }

    /** Advanced editor for one item, or for several at once. {@code after} runs once the changes are saved. */
    void open(final java.util.List<Media> items, final Runnable after) {
        if (items == null || items.isEmpty()) return;
        final java.util.List<Media> ms = new java.util.ArrayList<>(items);
        final boolean multi = ms.size() > 1;
        final Media m = ms.get(0);
        int vids = 0;
        for (Media x : ms) if (x.video) vids++;
        final boolean allVideo = vids == ms.size(), allAudio = vids == 0;
        editing = ms;
        final Dialog d = new Dialog(a);
        d.requestWindowFeature(Window.FEATURE_NO_TITLE);
        LinearLayout box = new LinearLayout(a);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setBackground(Ui.shape(Ui.SURF, 20, a));
        box.setPadding(0, Ui.dp(a, 20), 0, Ui.dp(a, 14));
        TextView head = Ui.text(a, "Advanced Editor", 19, Ui.TXT, true);
        head.setPadding(Ui.dp(a, 24), 0, Ui.dp(a, 24), Ui.dp(a, 4));
        box.addView(head);

        LinearLayout col = new LinearLayout(a);
        col.setOrientation(LinearLayout.VERTICAL);
        col.setPadding(Ui.dp(a, 24), 0, Ui.dp(a, 24), Ui.dp(a, 6));

        if (multi) {
            TextView note = Ui.text(a, ms.size() + " items selected. Fields with different values show " + EDIT
                    + ". Press one to write a value for all of them.", 12, Ui.SUB, false);
            note.setSingleLine(false);
            note.setEllipsize(null);
            note.setPadding(0, Ui.dp(a, 2), 0, Ui.dp(a, 4));
            col.addView(note);
        }

        LinearLayout cr = new LinearLayout(a);
        cr.setGravity(Gravity.CENTER_VERTICAL);
        cr.setPadding(0, Ui.dp(a, 8), 0, 0);
        FrameLayout pf = new FrameLayout(a);
        pf.setBackground(Ui.shape(Ui.SURF2, 10, a));
        pf.setClipToOutline(true);
        preview = new ImageView(a);
        preview.setScaleType(ImageView.ScaleType.CENTER_CROP);
        pf.addView(preview, new FrameLayout.LayoutParams(Ui.MATCH, Ui.MATCH));
        cr.addView(pf, new LinearLayout.LayoutParams(Ui.dp(a, 76), Ui.dp(a, 76)));
        LinearLayout cb = new LinearLayout(a);
        cb.setOrientation(LinearLayout.VERTICAL);
        cb.setPadding(Ui.dp(a, 14), 0, 0, 0);
        String chg = allVideo ? "Change thumbnail" : allAudio ? "Change cover art" : "Change picture";
        TextView change = Ui.text(a, multi ? chg + " (all)" : chg, 15, Ui.ACC, true);
        change.setPadding(0, Ui.dp(a, 6), 0, Ui.dp(a, 6));
        change.setOnClickListener(v -> pickCover(ms));
        cb.addView(change);
        TextView reset = Ui.text(a, multi ? "Use original (all)" : "Use original", 14, Ui.SUB, false);
        reset.setPadding(0, Ui.dp(a, 6), 0, Ui.dp(a, 6));
        reset.setOnClickListener(v -> { for (Media x : ms) a.store.deleteCover(x.key()); coverChanged(ms); });
        cb.addView(reset);
        cr.addView(cb);
        col.addView(cr);
        showCover(m);

        final Field title = multi ? field(col, "Title", "", false, false, true, ms.size())
                : field(col, "Title", m.title, false, false, false, 1);
        String artistLabel = allVideo ? "Author" : allAudio ? "Artist" : "Artist / Author";
        final Field artist = field(col, artistLabel, multi ? common(ms, x -> x.artist) : m.artist, false,
                multi && common(ms, x -> x.artist) == null, false, ms.size());
        final Field album = allAudio ? field(col, "Album", multi ? common(ms, x -> x.album) : m.album, false,
                multi && common(ms, x -> x.album) == null, false, ms.size()) : null;
        final Field genre = allAudio ? field(col, "Genre", multi ? common(ms, x -> x.genre) : m.genre, false,
                multi && common(ms, x -> x.genre) == null, false, ms.size()) : null;
        final Field desc = allVideo ? field(col, "Description", multi ? common(ms, x -> x.desc) : m.desc, true,
                multi && common(ms, x -> x.desc) == null, false, ms.size()) : null;

        ScrollView sv = new ScrollView(a);
        sv.addView(col);
        box.addView(sv, new LinearLayout.LayoutParams(Ui.MATCH, 0, 1f));

        LinearLayout b = new LinearLayout(a);
        b.setGravity(Gravity.END | Gravity.CENTER_VERTICAL);
        b.setPadding(Ui.dp(a, 12), Ui.dp(a, 12), Ui.dp(a, 16), 0);
        TextView no = pill("Cancel", Ui.ACC, 0);
        no.setOnClickListener(v -> d.dismiss());
        TextView yes = pill("Save", Ui.ONACC, Ui.ACC);
        yes.setOnClickListener(v -> {
            String t = null;
            if (!multi) {
                String enteredTitle = title.text();
                if (enteredTitle.isEmpty()) { title.et.setHintTextColor(Ui.RED); title.et.setHint("Title can't be empty"); return; }
                t = title.dirty ? enteredTitle : null;
            }
            save(ms, t, artist.result(multi), album == null ? null : album.result(multi),
                    genre == null ? null : genre.result(multi), desc == null ? null : desc.result(multi));
            d.dismiss();
            if (after != null) after.run();
        });
        b.addView(no);
        b.addView(yes);
        box.addView(b, new LinearLayout.LayoutParams(Ui.MATCH, Ui.WRAP));

        d.setContentView(box);
        d.setOnDismissListener(x -> { preview = null; editing = null; });
        d.show();
        Window w = d.getWindow();
        w.setBackgroundDrawable(new ColorDrawable(0));
        w.setDimAmount(0.6f);
        int sw = a.getResources().getDisplayMetrics().widthPixels, sh = a.getResources().getDisplayMetrics().heightPixels;
        w.setLayout(Math.min(Ui.dp(a, 360), sw * 9 / 10), Math.min(Ui.dp(a, 620), sh * 85 / 100));
        w.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE);
    }

    private void showCover(Media m) {
        if (preview == null) return;
        preview.setImageDrawable(null);
        Ui.thumb(a, m, preview, Ui.dp(a, 76));
    }

    void pickCover(java.util.List<Media> ms) {
        coverFor = ms;
        try {
            Intent i = new Intent(Intent.ACTION_GET_CONTENT);
            i.setType("image/*");
            i.addCategory(Intent.CATEGORY_OPENABLE);
            a.startActivityForResult(i, REQ_COVER);
        } catch (Exception e) { a.toast("No app available to pick a picture"); }
    }

    void onCoverResult(int res, Intent data) {
        java.util.List<Media> ms = coverFor;
        coverFor = null;
        if (res != android.app.Activity.RESULT_OK || data == null || data.getData() == null || ms == null || ms.isEmpty()) return;
        try {
            BitmapFactory.Options o = new BitmapFactory.Options();
            o.inJustDecodeBounds = true;
            try (InputStream in = a.getContentResolver().openInputStream(data.getData())) { BitmapFactory.decodeStream(in, null, o); }
            int ss = 1;
            while (o.outWidth / (ss * 2) >= 1024 && o.outHeight / (ss * 2) >= 1024) ss *= 2;
            o = new BitmapFactory.Options();
            o.inSampleSize = ss;
            Bitmap b;
            try (InputStream in = a.getContentResolver().openInputStream(data.getData())) { b = BitmapFactory.decodeStream(in, null, o); }
            if (b == null) { a.toast("Could not use that picture"); return; }
            for (Media m : ms) if (!a.store.saveCover(m.key(), b)) { a.toast("Could not use that picture"); return; }
            coverChanged(ms);
            writeCoverIntoFiles(ms, b);
        } catch (Exception e) { a.toast("Could not use that picture"); }
    }

    /** Embeds the picture into the real media files (MP3 APIC, FLAC PICTURE, MP4 covr). */
    private void writeCoverIntoFiles(java.util.List<Media> ms, Bitmap b) {
        java.io.ByteArrayOutputStream bo = new java.io.ByteArrayOutputStream();
        boolean png = b.hasAlpha();
        if (!b.compress(png ? Bitmap.CompressFormat.PNG : Bitmap.CompressFormat.JPEG, 92, bo)) {
            a.toast("Could not encode the picture for the file");
            return;
        }
        final byte[] bytes = bo.toByteArray();
        Job j = new Job();
        j.ms = new java.util.ArrayList<>();
        j.perFile = new java.util.HashMap<>();
        for (Media m : ms) {
            if (!mayWriteFormat(m)) continue;
            TagWriter.Fields f = new TagWriter.Fields();
            f.cover = bytes;
            f.coverMime = png ? "image/png" : "image/jpeg";
            f.coverW = b.getWidth();
            f.coverH = b.getHeight();
            j.ms.add(m);
            j.perFile.put(m.key(), f);
        }
        if (j.ms.isEmpty()) {
            a.toast("This file format can't hold embedded art; the picture is only used inside Vidsic");
            return;
        }
        if (j.ms.size() < ms.size()) a.toast("Some files can't hold embedded art and keep the picture only inside Vidsic");
        beginJob(j);
    }

    private void coverChanged(java.util.List<Media> ms) {
        for (Media m : ms) Ui.dropThumbs(m.key());
        showCover(ms.get(0));
        a.refreshAll();
        Media cur = a.engine.cur();
        if (cur != null) for (Media m : ms) if (cur.key().equals(m.key())) { a.engine.loadArt(cur); break; }
    }

    static final int REQ_TAGS = 88;

    private static final class Job {
        java.util.List<Media> ms;
        String title, artist, album, genre, desc;
        java.util.Map<String, TagWriter.Fields> perFile;
        boolean fromBackup;
    }

    private Job pendingJob;

    /** Only metadata written successfully to the media file is committed to the UI/store. */
    void save(java.util.List<Media> ms, String title, String artist, String album, String genre, String desc) {
        if (ms == null || ms.isEmpty()) return;
        Job j = new Job();
        j.ms = new java.util.ArrayList<>(ms);
        j.title = title; j.artist = artist; j.album = album; j.genre = genre; j.desc = desc;
        // Start from legacy Vidsic-only overrides so they can be migrated into the file even
        // when the user leaves that field alone. Only fields actually changed in this editor are
        // added otherwise; explicit user edits take precedence over old overrides.
        j.perFile = new java.util.HashMap<>();
        for (Media m : j.ms) {
            TagWriter.Fields f = legacyOverridesFor(m);
            if (title != null) f.title = title;
            if (artist != null) f.artist = artist;
            if (album != null && !m.video) f.album = album;
            if (genre != null && !m.video) f.genre = genre;
            if (desc != null && m.video) f.desc = desc;
            if (f.any()) j.perFile.put(m.key(), f);
        }
        boolean any = false;
        for (Media m : j.ms) if (fieldsFor(m, j).any()) { any = true; break; }
        if (!any) { a.toast("No metadata changes selected"); return; }
        beginJob(j);
    }

    /** Values left behind by pre-file-writing versions; never trust them as the file's current tags. */
    private TagWriter.Fields legacyOverridesFor(Media m) {
        TagWriter.Fields f = new TagWriter.Fields();
        String title = a.store.renamed(m);
        if (title != null) f.title = title;
        org.json.JSONObject meta = a.store.metaOf(m.key());
        if (meta != null) {
            if (meta.has("artist")) f.artist = meta.optString("artist", "");
            if (meta.has("album")) f.album = meta.optString("album", "");
            if (meta.has("genre")) f.genre = meta.optString("genre", "");
            if (meta.has("desc")) f.desc = meta.optString("desc", "");
        }
        return f;
    }

    /** Migrate metadata overrides found in older backups into the original media files. */
    void applyRestoredEdits(java.util.List<Backup.LegacyEdit> edits) {
        if (edits == null || edits.isEmpty()) return;
        Job j = new Job();
        j.ms = new java.util.ArrayList<>();
        j.perFile = new java.util.HashMap<>();
        j.fromBackup = true;
        java.util.HashSet<String> seen = new java.util.HashSet<>();
        for (Backup.LegacyEdit e : edits) {
            if (e == null || e.media == null || e.fields == null || !e.fields.any()) continue;
            String key = e.media.key();
            TagWriter.Fields prior = j.perFile.get(key);
            if (prior == null) {
                prior = new TagWriter.Fields();
                j.perFile.put(key, prior);
            }
            mergeFields(prior, e.fields);
            if (seen.add(key)) j.ms.add(e.media);
        }
        if (j.ms.isEmpty()) return;
        beginJob(j);
    }

    private static void mergeFields(TagWriter.Fields into, TagWriter.Fields from) {
        if (from.title != null) into.title = from.title;
        if (from.artist != null) into.artist = from.artist;
        if (from.album != null) into.album = from.album;
        if (from.genre != null) into.genre = from.genre;
        if (from.desc != null) into.desc = from.desc;
    }

    private void beginJob(Job j) {
        a.toast(j.fromBackup ? "Checking tags from the older backup... Don't close the app yet."
                : "Checking the original file tags... Don't close the app yet.");
        new Thread(() -> {
            java.util.List<Uri> uris = new java.util.ArrayList<>();
            java.util.HashSet<String> seenUris = new java.util.HashSet<>();
            if (!android.os.Environment.isExternalStorageManager()) {
                for (Media m : j.ms) {
                    TagWriter.Fields f = fieldsFor(m, j);
                    if (!f.any() || !mayWriteFormat(m) || TagWriter.alreadyMatches(a, m, f)) continue;
                    if (m.filePath == null && m.id > 0 && m.uri != null
                            && "content".equals(m.uri.getScheme()) && seenUris.add(m.uri.toString())) uris.add(m.uri);
                }
            }
            a.h.post(() -> {
                if (!uris.isEmpty()) {
                    try {
                        android.app.PendingIntent pi = MediaStore.createWriteRequest(a.getContentResolver(), uris);
                        pendingJob = j;
                        a.startIntentSenderForResult(pi.getIntentSender(), REQ_TAGS, null, 0, 0, 0);
                        return;
                    } catch (Exception e) { pendingJob = null; }
                }
                runJob(j);
            });
        }).start();
    }

    private static boolean mayWriteFormat(Media m) {
        String ext = MainActivity.ext(m == null ? null : m.name).toLowerCase(java.util.Locale.US);
        return ext.equals(".mp3") || ext.equals(".aac") || ext.equals(".flac")
                || ext.equals(".m4a") || ext.equals(".mp4") || ext.equals(".m4v")
                || ext.equals(".3gp") || ext.equals(".mov");
    }

    void onTagPermission(int res) {
        Job j = pendingJob;
        pendingJob = null;
        if (j == null) return;
        if (res != android.app.Activity.RESULT_OK) {
            for (Media m : j.ms) clearOverrideValues(m, fieldsFor(m, j));
            a.toast(j.fromBackup
                    ? "No original-file metadata was changed: Android modification permission was not granted."
                    : "No file changes made: modification permission was not granted");
            a.loadMedia();
            return;
        }
        runJob(j);
    }

    private void runJob(final Job j) {
        a.toast(j.fromBackup
                ? "Applying older backup metadata to original files... Don't close the app yet."
                : (j.ms.size() > 1
                    ? "Writing tags into the original files... Don't close the app yet."
                    : "Writing tags into the original file... Don't close the app yet."));
        new Thread(() -> {
            final int n = j.ms.size();
            final int[] res = new int[n];
            final boolean[] db = new boolean[n];
            final boolean[] dbChecked = new boolean[n];
            final boolean[] timeAttempted = new boolean[n];
            final boolean[] timeRestored = new boolean[n];
            final boolean[] apply = new boolean[n];
            final long[] oldModifiedTime = new long[n];
            final String[] paths = new String[n];
            for (int i = 0; i < n; i++) {
                Media m = j.ms.get(i);
                TagWriter.Fields f = fieldsFor(m, j);
                if (!f.any()) { res[i] = TagWriter.SKIPPED; continue; }
                if (!mayWriteFormat(m)) { res[i] = TagWriter.UNSUPPORTED; continue; }
                paths[i] = TagWriter.pathOf(a, m);
                if (paths[i] != null) {
                    java.io.File original = new java.io.File(paths[i]);
                    if (original.isFile()) {
                        timeAttempted[i] = true;
                        oldModifiedTime[i] = original.lastModified();
                    }
                }

                // Compare the embedded tags, never Media/Store display values. This avoids
                // treating an old Vidsic override as proof that the original file is already edited.
                // Compare against the original file, not Media/Store values. If only one field
                // differs, send only that field to the writer so unchanged embedded tags are untouched.
                TagWriter.Fields toWrite = TagWriter.differingFields(a, m, f);
                if (!toWrite.any()) {
                    res[i] = TagWriter.SKIPPED;
                    db[i] = refreshDatabase(m, f);
                    dbChecked[i] = true;
                    apply[i] = true;
                    continue;
                }

                res[i] = TagWriter.write(a, m, toWrite);
                if (res[i] == TagWriter.OK && toWrite.cover != null && paths[i] != null) {
                    try { android.media.MediaScannerConnection.scanFile(a, new String[]{paths[i]}, null, null); } catch (Throwable ignored) { }
                }
                if (res[i] == TagWriter.OK) {
                    if (timeAttempted[i]) timeRestored[i] = restoreModifiedTime(paths[i], oldModifiedTime[i]);
                    db[i] = refreshDatabase(m, f);
                    dbChecked[i] = true;
                    apply[i] = true;
                } else if (res[i] == TagWriter.FAILED && timeAttempted[i]) {
                    // A failed in-place attempt may have rolled back the tag bytes; restore its timestamp too.
                    timeRestored[i] = restoreModifiedTime(paths[i], oldModifiedTime[i]);
                }
            }
            a.h.post(() -> {
                int changed = 0, skipped = 0, unsupported = 0, denied = 0, failed = 0,
                        noSpace = 0, dbFailed = 0, timeFailed = 0;
                for (int i = 0; i < n; i++) {
                    Media m = j.ms.get(i);
                    if (res[i] == TagWriter.OK) changed++;
                    else if (res[i] == TagWriter.SKIPPED) skipped++;
                    else if (res[i] == TagWriter.UNSUPPORTED) unsupported++;
                    else if (res[i] == TagWriter.DENIED) denied++;
                    else if (res[i] == TagWriter.NO_SAFE_SPACE) noSpace++;
                    else failed++;
                    if (dbChecked[i] && !db[i]) dbFailed++;
                    if (timeAttempted[i] && !timeRestored[i] && (res[i] == TagWriter.OK || res[i] == TagWriter.FAILED)) timeFailed++;
                    if (apply[i]) applyWrittenValues(m, fieldsFor(m, j));
                    else clearOverrideValues(m, fieldsFor(m, j));
                }
                a.engine.fire();
                a.loadMedia();

                StringBuilder msg = new StringBuilder(j.fromBackup ? "Older backup metadata: " : "Metadata edit: ");
                boolean added = false;
                if (changed > 0) { msg.append("changed ").append(changed).append(changed == 1 ? " file" : " files"); added = true; }
                if (skipped > 0) { if (added) msg.append(", "); msg.append("skipped ").append(skipped); added = true; }
                int failCount = unsupported + denied + failed + noSpace;
                if (failCount > 0) { if (added) msg.append(", "); msg.append("failed ").append(failCount); added = true; }
                if (!added) msg.append("no files changed");
                java.util.List<String> reasons = new java.util.ArrayList<>();
                if (unsupported > 0) reasons.add(unsupported + " unsupported format(s)");
                if (denied > 0) reasons.add(denied + " permission denied");
                if (failed > 0) reasons.add(failed + " write failure(s)");
                if (noSpace > 0) reasons.add(noSpace + " file(s) could not be safely resized; original files were left unchanged (check free storage if tags needed to grow)");
                if (!reasons.isEmpty()) msg.append(" (" + android.text.TextUtils.join(", ", reasons) + ")");
                if (dbFailed > 0) msg.append(" Android's media index could not refresh ").append(dbFailed)
                        .append(" item(s); another app may show cached metadata.");
                if (timeFailed > 0) msg.append(" Could not preserve Last Modified for ").append(timeFailed).append(" file(s).");
                a.toast(msg.toString());
            });
        }).start();
    }

    private TagWriter.Fields fieldsFor(Media m, Job j) {
        if (j.perFile != null) {
            TagWriter.Fields custom = j.perFile.get(m.key());
            return custom == null ? new TagWriter.Fields() : custom;
        }
        TagWriter.Fields f = new TagWriter.Fields();
        f.title = j.title;
        f.artist = j.artist;
        f.album = m.video ? null : j.album;
        f.genre = m.video ? null : j.genre;
        f.desc = m.video ? j.desc : null;
        return f;
    }

    private boolean refreshDatabase(Media m, TagWriter.Fields f) {
        boolean ok = updateDatabase(m, f);
        if (!ok && m.filePath == null) ok = rescanThenUpdate(m, f);
        return ok;
    }

    private void applyWrittenValues(Media target, TagWriter.Fields f) {
        String key = target.key();
        applyWrittenValuesTo(target, f);
        for (Media q : new java.util.ArrayList<>(a.engine.queue)) {
            if (key.equals(q.key())) applyWrittenValuesTo(q, f);
        }
        Media lib = a.byKey.get(key);
        if (lib != null && lib != target) applyWrittenValuesTo(lib, f);
        // A successful write or a confirmed embedded-tag match is the source of truth.
        clearOverrideValues(target, f);
    }

    private void clearOverrideValues(Media target, TagWriter.Fields f) {
        String key = target.key();
        if (f.title != null) a.store.setRenamed(target, null);
        if (f.artist != null) a.store.setMeta(key, "artist", null);
        if (f.album != null) a.store.setMeta(key, "album", null);
        if (f.genre != null) a.store.setMeta(key, "genre", null);
        if (f.desc != null) a.store.setMeta(key, "desc", null);
    }

    private void applyWrittenValuesTo(Media m, TagWriter.Fields f) {
        if (f.title != null) m.title = f.title;
        if (f.artist != null) m.artist = f.artist;
        if (f.album != null && !m.video) m.album = f.album;
        if (f.genre != null && !m.video) m.genre = f.genre;
        if (f.desc != null && m.video) m.desc = f.desc;
    }

    private boolean restoreModifiedTime(String path, long modified) {
        if (path == null) return false;
        try {
            java.io.File file = new java.io.File(path);
            if (!file.isFile()) return false;
            if (file.lastModified() != modified && !file.setLastModified(modified)) return false;
            return file.lastModified() == modified;
        } catch (Throwable e) { return false; }
    }

    /** Updates or verifies Android's persistent media index after an on-disk tag write. */
    private boolean updateDatabase(Media m, TagWriter.Fields f) {
        return updateDatabaseAt(m, m == null ? null : m.uri, f);
    }

    private boolean updateDatabaseAt(Media m, Uri uri, TagWriter.Fields f) {
        if (m != null && m.filePath != null) return true; // Hidden files aren't in MediaStore.
        if (uri == null || !"content".equals(uri.getScheme())) return false;
        boolean attempted = false, ok = true;
        if (f.title != null) { attempted = true; ok &= updateColumn(uri, MediaStore.MediaColumns.TITLE, f.title); }
        if (f.artist != null) { attempted = true; ok &= updateColumn(uri, "artist", f.artist); }
        if (f.album != null) { attempted = true; ok &= updateColumn(uri, "album", f.album); }
        if (f.desc != null) { attempted = true; ok &= updateColumn(uri, "description", f.desc); }
        if (f.genre != null) { attempted = true; ok &= updateColumn(uri, "genre", f.genre); }
        return !attempted || ok || indexedValuesMatch(uri, f);
    }

    private boolean updateColumn(Uri uri, String column, String value) {
        // Some providers return 0 when a value is already correct. Check first instead of
        // treating that as a failed update.
        if (columnMatches(uri, column, value)) return true;
        try {
            ContentValues v = new ContentValues();
            v.put(column, value);
            return a.getContentResolver().update(uri, v, null, null) > 0
                    || columnMatches(uri, column, value);
        } catch (Throwable e) { return columnMatches(uri, column, value); }
    }

    private boolean indexedValuesMatch(Uri uri, TagWriter.Fields f) {
        boolean checked = false;
        if (f.title != null) { checked = true; if (!columnMatches(uri, MediaStore.MediaColumns.TITLE, f.title)) return false; }
        if (f.artist != null) { checked = true; if (!columnMatches(uri, "artist", f.artist)) return false; }
        if (f.album != null) { checked = true; if (!columnMatches(uri, "album", f.album)) return false; }
        if (f.desc != null) { checked = true; if (!columnMatches(uri, "description", f.desc)) return false; }
        if (f.genre != null) { checked = true; if (!columnMatches(uri, "genre", f.genre)) return false; }
        return checked;
    }

    private boolean columnMatches(Uri uri, String column, String expected) {
        try (android.database.Cursor c = a.getContentResolver().query(uri, new String[]{column}, null, null, null)) {
            if (c == null || !c.moveToFirst()) return false;
            int at = c.getColumnIndex(column);
            if (at < 0) return false;
            String actual = c.isNull(at) ? null : c.getString(at);
            if (actual == null || actual.isEmpty() || "<unknown>".equalsIgnoreCase(actual))
                return expected == null || expected.isEmpty() || "<unknown>".equalsIgnoreCase(expected);
            return actual.equals(expected);
        } catch (Throwable e) { return false; }
    }

    private boolean rescanThenUpdate(Media m, TagWriter.Fields f) {
        String path = TagWriter.pathOf(a, m);
        if (path == null || path.isEmpty()) return false;
        final java.util.concurrent.CountDownLatch latch = new java.util.concurrent.CountDownLatch(1);
        final java.util.concurrent.atomic.AtomicBoolean result = new java.util.concurrent.atomic.AtomicBoolean(false);
        try {
            android.media.MediaScannerConnection.scanFile(a, new String[]{path}, null, (p, scannedUri) -> {
                // Use the URI returned by the scanner: an indexed item can have a refreshed URI,
                // and the scanner itself may already have imported every tag from the file.
                Uri target = scannedUri != null ? scannedUri : m.uri;
                boolean matches = target != null && indexedValuesMatch(target, f);
                if (!matches) {
                    boolean updated = updateDatabaseAt(m, target, f);
                    matches = updated || (target != null && indexedValuesMatch(target, f));
                }
                result.set(matches);
                latch.countDown();
            });
            latch.await(8, java.util.concurrent.TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        } catch (Throwable e) { }
        return result.get();
    }

    void info(Media m) {
        String when = m.added > 0 ? DateFormat.getDateTimeInstance().format(new Date(m.added * 1000)) : "";
        int plays = a.store.plays(m);
        String[][] rows = m.video
                ? new String[][]{
                {"File name", m.name}, {"Title", m.title}, {"Author", m.artist},
                {"Folder", m.path == null ? m.folder : m.path},
                {"Size", m.size > 0 ? PhotoOps.fmtSize(m.size) : ""},
                {"Length", m.duration > 0 ? Ui.fmt(m.duration) : ""},
                {"Date added", when}, {"Format", fmtName(m.name)},
                {"Times played", plays > 0 ? String.valueOf(plays) : ""},
                {"Description", m.desc}}
                : new String[][]{
                {"File name", m.name}, {"Title", m.title}, {"Artist", m.artist}, {"Album", m.album},
                {"Genre", m.genre},
                {"Folder", m.path == null ? m.folder : m.path},
                {"Size", m.size > 0 ? PhotoOps.fmtSize(m.size) : ""},
                {"Length", m.duration > 0 ? Ui.fmt(m.duration) : ""},
                {"Date added", when}, {"Format", fmtName(m.name)},
                {"Times played", plays > 0 ? String.valueOf(plays) : ""}};
        Ui.details(a, m.video ? "Video info" : "Audio info", rows);
    }

    private static String fmtName(String n) {
        String e = MainActivity.ext(n);
        return e.isEmpty() ? "" : e.substring(1).toUpperCase(java.util.Locale.US);
    }
}
