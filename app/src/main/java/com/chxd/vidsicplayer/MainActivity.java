package com.chxd.vidsicplayer;

import android.app.Activity;
import android.app.PendingIntent;
import android.content.ContentValues;
import android.database.ContentObserver;
import android.database.Cursor;
import android.os.SystemClock;
import android.provider.OpenableColumns;
import java.io.InputStream;
import java.io.OutputStream;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.os.Build;
import android.os.Environment;
import android.provider.Settings;
import android.graphics.drawable.GradientDrawable;
import android.widget.HorizontalScrollView;
import java.io.File;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.provider.MediaStore;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.BaseAdapter;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.ScrollView;
import android.widget.Switch;
import android.widget.TextView;
import android.widget.Toast;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;

public class MainActivity extends Activity implements Engine.L {
    Store store;
    Engine engine;
    FrameLayout root, content;
    MediaPage audioPage, videoPage;
    PhotoPage photoPage;
    PhotoViewer viewer;
    PhotoOps pops;
    MetaEditor meta;
    SvgThumbs svgThumbs;
    PhotoEditor editor;
    CollageMaker collage;
    GifMaker gif;
    List<Photo> photos = new ArrayList<>();
    final Map<String, Photo> photoByKey = new HashMap<>();
    static final int P_AUDIO = 0, P_VIDEO = 1, P_PHOTO = 2, P_SET = 3;
    int[] navPages = {P_AUDIO, P_VIDEO, P_SET};
    ScrollView settings;
    PlayerView player;
    LinearLayout nav, mini;
    ImageView miniPlay;
    TextView miniTitle, miniSub, miniTime;
    FrameLayout miniThumb;
    String miniKey = "";
    ProgressBar miniProg;
    ImageView[] navIcons;
    TextView[] navLabels;
    List<Media> audios = new ArrayList<>(), videos = new ArrayList<>();
    final Map<String, Media> byKey = new HashMap<>();
    int page = 0;
    List<Media> pendingDel = new ArrayList<>();
    Runnable pendingDelAfter;
    Media pendingRen;
    String pendingRenName;
    FrameLayout queueLayer;
    BaseAdapter queueAd;
    TextView queueHead, queueHint;
    QueueList queueList;
    LinearLayout queueSelBar;
    boolean qSelMode;
    final java.util.HashSet<Integer> qSel = new java.util.HashSet<>();
    String pendingSpecKey;
    boolean rescanPending;
    Media pendingCapMedia;
    boolean loading, reloadQueued;
    long lastLoad;
    ContentObserver mediaObs;
    final Runnable autoRescan = () -> loadMedia();
    final Handler h = new Handler(Looper.getMainLooper());
    final Runnable ticker = new Runnable() {
        public void run() {
            updateMini();
            if (player.getVisibility() == View.VISIBLE) player.tick();
            h.postDelayed(this, Engine.SAVER ? 1000 : 500);
        }
    };

    boolean scanned;
    TextView stats;

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        engine = Engine.get(this);
        store = engine.store;
        engine.listen(this);
        pops = new PhotoOps(this);
        meta = new MetaEditor(this);
        TagWriter.Recovery recovery = TagWriter.recoverInterruptedWrites(this);
        if (recovery.restored > 0) toast("Recovered " + recovery.restored + " interrupted metadata edit(s); original file data was restored.");
        if (recovery.timestampNotRestored > 0) toast("Recovered original metadata, but could not preserve the original Last Modified timestamp for " + recovery.timestampNotRestored + " file(s).");
        if (recovery.pending > 0) toast("Could not yet recover " + recovery.pending + " interrupted metadata edit(s). Keep the affected files in place and reopen Vidsic after restoring file access.");
        svgThumbs = new SvgThumbs(this);
        engine.onRateRefused = f -> toast("This device can't play at " + PlayerView.fmtSpeed(f));
        buildUi();
        loadMedia();
        if (needsPermissions()) {
            store.setBool("perm_asked", true);
            store.setBool("notif_asked", true);
            requestPermissions(perms(), 1);
        }

        mediaObs = new ContentObserver(h) {
            @Override public void onChange(boolean self) {
                if (!store.bool("auto_rescan", false)) return;

                if (Engine.SAVER || !engine.spec.uiVisible) { rescanPending = true; return; }
                h.removeCallbacks(autoRescan);
                h.postDelayed(autoRescan, 2000);
            }
        };
        try {
            getContentResolver().registerContentObserver(MediaStore.Audio.Media.EXTERNAL_CONTENT_URI, true, mediaObs);
            getContentResolver().registerContentObserver(MediaStore.Video.Media.EXTERNAL_CONTENT_URI, true, mediaObs);
            getContentResolver().registerContentObserver(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, true, mediaObs);
        } catch (Exception e) { }
        if (b == null) handleIntent(getIntent());
    }

    @Override protected void onNewIntent(Intent in) {
        super.onNewIntent(in);
        setIntent(in);
        handleIntent(in);
    }

    @SuppressWarnings("deprecation")
    static final String ACT_GROUP = "com.chxd.vidsicplayer.OPEN_GROUP";
    static final String ACT_PLAYER = "com.chxd.vidsicplayer.OPEN_PLAYER";
    Runnable pendingOpen;
    boolean loadedOnce;

    void openGroup(final Group g, final String item, final boolean play, final boolean exitAfter) {
        if (g == null) return;
        Runnable r = () -> {
            if (g.photo()) {
                if (photoPage == null) { toast("Turn on Image Viewer in Settings to open pictures"); return; }
                showPage(P_PHOTO);
                photoPage.openGroup(g);
                if (item != null) {
                    List<Photo> l = photoPage.shown.isEmpty() ? new ArrayList<Photo>() : new ArrayList<>(photoPage.shown);
                    for (int i = 0; i < l.size(); i++) if (l.get(i).key().equals(item)) { openViewer(l, i, false, -1); break; }
                }
            } else {
                showPage(g.video ? P_VIDEO : P_AUDIO);
                MediaPage mp = g.video ? videoPage : audioPage;
                mp.openGroup(g);
                if (item != null && play) {
                    List<Media> l = new ArrayList<>(mp.shown);
                    for (int i = 0; i < l.size(); i++) if (l.get(i).key().equals(item)) { playMedia(l, i); break; }
                    if (exitAfter) h.postDelayed(this::finish, 700);
                }
            }
        };
        if (loadedOnce) r.run(); else pendingOpen = r;
    }

    void handleIntent(Intent in) {
        if (in == null || in.getAction() == null) return;
        String act = in.getAction();
        if (ACT_PLAYER.equals(act)) {
            setIntent(new Intent(Intent.ACTION_MAIN));
            if (engine.cur() != null) {
                if (queueLayer != null) hideQueue();
                openPlayer();
            }
            return;
        }
        if (ACT_GROUP.equals(act)) {
            Group g = Group.parse(in.getStringExtra("group"));
            String item = in.getStringExtra("item");
            setIntent(new Intent(Intent.ACTION_MAIN));
            openGroup(g, item, in.getBooleanExtra("play", false), in.getBooleanExtra("exit", false));
            return;
        }
        List<Uri> uris = new ArrayList<>();
        try {
            if (Intent.ACTION_VIEW.equals(act) && in.getData() != null) uris.add(in.getData());
            else if (Intent.ACTION_SEND.equals(act)) {
                Uri u = in.getParcelableExtra(Intent.EXTRA_STREAM);
                if (u != null) uris.add(u);
            } else if (Intent.ACTION_SEND_MULTIPLE.equals(act)) {
                ArrayList<Uri> l = in.getParcelableArrayListExtra(Intent.EXTRA_STREAM);
                if (l != null) uris.addAll(l);
            }
        } catch (Exception e) { }
        setIntent(new Intent(Intent.ACTION_MAIN));
        if (uris.isEmpty()) return;
        List<Media> ms = new ArrayList<>();
        List<Photo> ps = new ArrayList<>();
        for (Uri u : uris) {
            String name = null;
            long size = 0;
            try (Cursor cu = getContentResolver().query(u, new String[]{OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE}, null, null, null)) {
                if (cu != null && cu.moveToFirst()) { name = cu.getString(0); size = cu.isNull(1) ? 0 : cu.getLong(1); }
            } catch (Exception e) { }
            if (name == null || name.isEmpty()) name = u.getLastPathSegment() == null ? "File" : u.getLastPathSegment();
            String type = null;
            try { type = getContentResolver().getType(u); } catch (Exception e) { }
            if (type == null || type.equals("application/octet-stream")) {
                String x = ext(name);
                if (!x.isEmpty()) type = android.webkit.MimeTypeMap.getSingleton().getMimeTypeFromExtension(x.substring(1).toLowerCase(java.util.Locale.US));
            }
            if (type == null) type = in.getType();
            if (type == null) continue;
            long id = -(Math.abs((long) u.toString().hashCode()) + 1);
            int dot = name.lastIndexOf('.');
            String title = dot > 0 ? name.substring(0, dot) : name;
            if (type.startsWith("audio/") || type.startsWith("video/") || type.equals("application/ogg")) {
                Media m = new Media();
                m.id = id;
                m.uri = u;
                m.name = name;
                m.title = title;
                m.video = type.startsWith("video/");
                m.artist = m.video ? "Opened files" : "Unknown artist";
                m.album = "Unknown album";
                m.folder = "Opened files";
                m.path = "Opened files/";
                m.added = System.currentTimeMillis() / 1000;
                m.size = size;
                store.applyMeta(m);
                ms.add(m);
            } else if (type.startsWith("image/")) {
                Photo p = new Photo();
                p.id = id;
                p.uri = u;
                p.name = name;
                p.title = title;
                p.folder = "Opened files";
                p.path = "Opened files/";
                p.added = System.currentTimeMillis() / 1000;
                p.size = size;
                p.svg = PhotoLoader.isSvg(name, type);
                p.gif = PhotoLoader.isGif(name, type);
                ps.add(p);
            }
        }
        if (!ms.isEmpty()) {
            for (Media m : ms) byKey.put(m.key(), m);
            playMedia(ms, 0);
        } else if (!ps.isEmpty()) {
            for (Photo p : ps) photoByKey.put(p.key(), p);
            openViewer(ps, 0, false, 0);
        } else toast("This file can't be opened");
    }

    void buildUi() {
        queueLayer = null;
        queueAd = null;
        if (player != null) engine.ls.remove(player);
        Ui.apply(this, store);
        getWindow().setStatusBarColor(Ui.BG);
        getWindow().setNavigationBarColor(Ui.SURF);
        getWindow().getDecorView().setSystemUiVisibility(Ui.LIGHT
                ? (View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR | View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR) : 0);

        root = new FrameLayout(this);
        root.setBackgroundColor(Ui.BG);
        LinearLayout main = new LinearLayout(this);
        main.setOrientation(LinearLayout.VERTICAL);
        root.addView(main, new FrameLayout.LayoutParams(Ui.MATCH, Ui.MATCH));

        content = new FrameLayout(this);
        main.addView(content, new LinearLayout.LayoutParams(Ui.MATCH, 0, 1));
        audioPage = new MediaPage(this, false);
        videoPage = new MediaPage(this, true);
        photoPage = store.bool("img_on", false) ? new PhotoPage(this) : null;
        if (photoPage == null && page == P_PHOTO) page = P_AUDIO;
        settings = buildSettings();
        content.addView(audioPage.root, new FrameLayout.LayoutParams(Ui.MATCH, Ui.MATCH));
        content.addView(videoPage.root, new FrameLayout.LayoutParams(Ui.MATCH, Ui.MATCH));
        if (photoPage != null) content.addView(photoPage.root, new FrameLayout.LayoutParams(Ui.MATCH, Ui.MATCH));
        content.addView(settings, new FrameLayout.LayoutParams(Ui.MATCH, Ui.MATCH));

        buildMini();
        main.addView(mini, new LinearLayout.LayoutParams(Ui.MATCH, Ui.WRAP));
        buildNav();
        main.addView(nav, new LinearLayout.LayoutParams(Ui.MATCH, Ui.dp(this, 60)));

        player = new PlayerView(this);
        player.setVisibility(View.GONE);
        root.addView(player, new FrameLayout.LayoutParams(Ui.MATCH, Ui.MATCH));
        viewer = null;
        viewer = new PhotoViewer(this);
        viewer.setVisibility(View.GONE);
        root.addView(viewer, new FrameLayout.LayoutParams(Ui.MATCH, Ui.MATCH));
        editor = new PhotoEditor(this);
        root.addView(editor, new FrameLayout.LayoutParams(Ui.MATCH, Ui.MATCH));
        collage = new CollageMaker(this);
        root.addView(collage, new FrameLayout.LayoutParams(Ui.MATCH, Ui.MATCH));
        gif = new GifMaker(this);
        root.addView(gif, new FrameLayout.LayoutParams(Ui.MATCH, Ui.MATCH));
        setContentView(root);
        showPage(page);
        updateMini();
    }

    void rebuildUi() {
        int sy = settings != null ? settings.getScrollY() : 0;
        buildUi();
        settings.post(() -> settings.scrollTo(0, sy));
    }

    @Override protected void onResume() {
        super.onResume();
        h.removeCallbacks(ticker);
        h.post(ticker);
        engine.refreshSaver();
        engine.spec.setUiVisible(true);
        h.postDelayed(this::checkSpecPermission, 700);
        if (player != null) { player.updateKeepOn(); player.startCapLoop(); }
        if (loading) { if (stats != null) refreshStats(); }
        else if (store.bool("auto_rescan", false) && !Engine.SAVER && (rescanPending || SystemClock.uptimeMillis() - lastLoad > 30000)) { rescanPending = false; loadMedia(); }
        else if (wantHidden() && !scanned && Environment.isExternalStorageManager()) loadMedia();
        else if (stats != null) refreshStats();
    }
    @Override protected void onStart() {
        super.onStart();
        engine.spec.setUiVisible(true);
        h.removeCallbacks(ticker);
        h.post(ticker);
    }

    @Override protected void onStop() {
        super.onStop();
        if (isInPictureInPictureMode()) return;
        h.removeCallbacks(ticker);
        engine.spec.setUiVisible(false);
        Widgets.dataChanged(this);
    }

    @Override protected void onPause() {
        super.onPause();
        engine.saveNow();
        if (viewer != null) viewer.pauseForBackground();
        if (player != null) player.exitSleep();
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();

        engine.ls.remove(this);
        engine.onRateRefused = null;
        if (player != null) engine.ls.remove(player);
        h.removeCallbacks(autoRescan);
        try { getContentResolver().unregisterContentObserver(mediaObs); } catch (Exception e) { }
    }

    boolean can(String p) { return checkSelfPermission(p) == PackageManager.PERMISSION_GRANTED; }
    boolean audioOk() {
        return can("android.permission.READ_EXTERNAL_STORAGE")
                || (Build.VERSION.SDK_INT >= 33 && can("android.permission.READ_MEDIA_AUDIO"));
    }
    boolean videoOk() {
        return can("android.permission.READ_EXTERNAL_STORAGE")
                || (Build.VERSION.SDK_INT >= 33 && can("android.permission.READ_MEDIA_VIDEO"));
    }
    boolean imageOk() {
        return can("android.permission.READ_EXTERNAL_STORAGE")
                || (Build.VERSION.SDK_INT >= 33 && can("android.permission.READ_MEDIA_IMAGES"));
    }
    boolean notifOk() { return Build.VERSION.SDK_INT < 33 || can("android.permission.POST_NOTIFICATIONS"); }
    boolean needsPermissions() {

        return !(audioOk() && videoOk()) || (store.bool("img_on", false) && !imageOk())
                || (!notifOk() && !store.bool("notif_asked", false));
    }

    boolean allAccessOk() { return audioOk() && videoOk() && notifOk() && (!store.bool("img_on", false) || imageOk()); }

    void openAppSettings() {
        try {
            startActivity(new Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:" + getPackageName())));
        } catch (Exception e) { }
    }

    void grantAccess() {
        if (allAccessOk()) { toast("Access is already allowed"); return; }
        boolean canAsk = !store.bool("perm_asked", false);
        if (!canAsk) for (String p : perms()) if (!can(p) && shouldShowRequestPermissionRationale(p)) { canAsk = true; break; }
        if (canAsk) {
            store.setBool("perm_asked", true);
            store.setBool("notif_asked", true);
            requestPermissions(perms(), 1);
        } else {
            toast("Allow Storage / Media and Notifications in the settings");
            openAppSettings();
        }
    }

    String[] perms() {
        if (Build.VERSION.SDK_INT < 33) return new String[]{"android.permission.READ_EXTERNAL_STORAGE"};
        boolean img = store.bool("img_on", false);
        return img ? new String[]{"android.permission.READ_EXTERNAL_STORAGE", "android.permission.READ_MEDIA_AUDIO",
                "android.permission.READ_MEDIA_VIDEO", "android.permission.READ_MEDIA_IMAGES", "android.permission.POST_NOTIFICATIONS"}
                : new String[]{"android.permission.READ_EXTERNAL_STORAGE", "android.permission.READ_MEDIA_AUDIO",
                "android.permission.READ_MEDIA_VIDEO", "android.permission.POST_NOTIFICATIONS"};
    }

    @Override
    public void onRequestPermissionsResult(int code, String[] p, int[] r) {
        if (code == 2) {
            boolean ok = r.length > 0 && r[0] == PackageManager.PERMISSION_GRANTED;
            if (ok) {
                engine.spec.retry();
                engine.spec.update();
                engine.fire();
                rebuildUi();
            } else {
                if (pendingSpecKey != null) store.setBool(pendingSpecKey, false);
                rebuildUi();
                if (!shouldShowRequestPermissionRationale("android.permission.RECORD_AUDIO")) micBlocked();
                else Ui.notice(this, "Permission needed", "Android only lets an app read the audio spectrum with the Microphone permission. "
                        + "Nothing is recorded. You can turn this on again and allow it.");
            }
            pendingSpecKey = null;
            return;
        }
        loadMedia();
    }

    boolean wantHidden() { return store.bool("hid_a", false) || store.bool("hid_v", false) || store.bool("hid_f", false); }

    void loadMedia() {
        if (loading) { reloadQueued = true; return; }
        loading = true;
        lastLoad = SystemClock.uptimeMillis();
        new Thread(() -> {

            android.os.Process.setThreadPriority(android.os.Process.THREAD_PRIORITY_BACKGROUND);
            try { loadNow(); } catch (Throwable t) { h.post(() -> loading = false); }
        }).start();
    }

    void loadNow() {
        final List<Media> la = MediaLoader.load(this, false), lv = MediaLoader.load(this, true);
        final List<Photo> lp = store.bool("img_on", false) && imageOk() ? PhotoLoader.load(this) : new ArrayList<Photo>();
        boolean sc = false;
        if (wantHidden() && Environment.isExternalStorageManager()) {
            try { MediaLoader.scanHidden(la, lv); sc = true; } catch (Exception e) { }
        }
        final boolean did = sc;
        for (Media m : la) store.applyMeta(m);
        for (Media m : lv) store.applyMeta(m);
        java.util.Comparator<Media> cmp = (x, y) -> Long.compare(y.added, x.added);
        Collections.sort(la, cmp);
        Collections.sort(lv, cmp);
        h.post(() -> {
            audios = la;
            videos = lv;
            scanned = did;
            byKey.clear();
            for (Media m : la) byKey.put(m.key(), m);
            for (Media m : lv) byKey.put(m.key(), m);
            photos = lp;
            photoByKey.clear();
            for (Photo ph : lp) photoByKey.put(ph.key(), ph);
            loading = false;
            refreshAll();
            refreshPhotos();
            loadedOnce = true;
            if (pendingOpen != null) { Runnable po = pendingOpen; pendingOpen = null; po.run(); }
            Group.invalidate();
            if (reloadQueued) { reloadQueued = false; loadMedia(); }
        });
    }

    boolean visible(Media m, boolean folders) {
        if (!m.hidden) return true;
        if (store.bool(m.video ? "hid_v" : "hid_a", false)) return true;
        return folders && m.folderHidden && store.bool("hid_f", false);
    }

    List<Media> media(boolean video) { return media(video, false); }

    List<Media> media(boolean video, boolean folders) {
        List<Media> out = new ArrayList<>();
        for (Media m : video ? videos : audios) if (visible(m, folders)) out.add(m);
        return out;
    }

    void refreshAll() {
        audioPage.refresh();
        videoPage.refresh();
        if (stats != null) refreshStats();
    }

    void refreshPhotos() {
        if (photoPage != null) photoPage.refresh();
        if (viewer != null && viewer.getVisibility() == View.VISIBLE) viewer.updateChrome();
    }

    void openViewer(List<Photo> list, int idx, boolean slideshow, int shuffle) {
        if (viewer == null || list.isEmpty()) return;
        viewer.open(list, idx, slideshow, shuffle);
    }

    void openEditor(Photo p) { editor.open(p); }

    void openCollage(List<Photo> ps) { collage.open(ps); }

    void openGif(List<Photo> ps) { gif.open(ps); }

    void onViewerClosed() {
        if (photoPage != null) photoPage.refresh();
        if (player != null) player.updateKeepOn();
    }

    void toast(String s) { Toast.makeText(this, s, Toast.LENGTH_SHORT).show(); }

    void buildNav() {
        nav = new LinearLayout(this);
        nav.setBackgroundColor(Ui.SURF);
        navPages = photoPage != null ? new int[]{P_AUDIO, P_VIDEO, P_PHOTO, P_SET} : new int[]{P_AUDIO, P_VIDEO, P_SET};
        int[] allIc = {R.drawable.ic_music, R.drawable.ic_movie, R.drawable.ic_image, R.drawable.ic_settings};
        String[] allLb = {"Audio", "Video", "Photos", "Settings"};
        navIcons = new ImageView[navPages.length];
        navLabels = new TextView[navPages.length];
        for (int i = 0; i < navPages.length; i++) {
            final int id = navPages[i];
            LinearLayout it = new LinearLayout(this);
            it.setOrientation(LinearLayout.VERTICAL);
            it.setGravity(Gravity.CENTER);
            it.setBackground(Ui.rip(0, 0, this));
            navIcons[i] = Ui.icon(this, allIc[id], Ui.SUB, 24);
            navLabels[i] = Ui.text(this, allLb[id], 11, Ui.SUB, true);
            navLabels[i].setGravity(Gravity.CENTER);
            it.addView(navIcons[i]);
            it.addView(navLabels[i], new LinearLayout.LayoutParams(Ui.WRAP, Ui.WRAP));
            it.setOnClickListener(v -> showPage(id));
            nav.addView(it, new LinearLayout.LayoutParams(0, Ui.MATCH, 1));
        }
    }

    void showPage(int id) {
        page = id;
        audioPage.root.setVisibility(id == P_AUDIO ? View.VISIBLE : View.GONE);
        videoPage.root.setVisibility(id == P_VIDEO ? View.VISIBLE : View.GONE);
        if (photoPage != null) photoPage.root.setVisibility(id == P_PHOTO ? View.VISIBLE : View.GONE);
        settings.setVisibility(id == P_SET ? View.VISIBLE : View.GONE);
        for (int k = 0; k < navPages.length; k++) {
            navIcons[k].setColorFilter(navPages[k] == id ? Ui.ACC : Ui.SUB);
            navLabels[k].setTextColor(navPages[k] == id ? Ui.ACC : Ui.SUB);
        }
    }

    @Override
    public void onBackPressed() {
        if (gif != null && gif.showing()) { gif.onBack(); return; }
        if (collage != null && collage.showing()) { collage.onBack(); return; }
        if (editor != null && editor.showing()) { editor.onBack(); return; }
        if (viewer != null && viewer.getVisibility() == View.VISIBLE) { viewer.onBack(); return; }
        if (queueLayer != null) { hideQueue(); return; }
        if (player.getVisibility() == View.VISIBLE) {
            if (player.onBack()) return;
            closePlayer();
            return;
        }
        if (page == P_AUDIO && audioPage.onBack()) return;
        if (page == P_VIDEO && videoPage.onBack()) return;
        if (page == P_PHOTO && photoPage != null && photoPage.onBack()) return;
        if (page == P_PHOTO || page == P_SET) { showPage(P_AUDIO); return; }
        super.onBackPressed();
    }

    void buildMini() {
        mini = new LinearLayout(this);
        mini.setOrientation(LinearLayout.VERTICAL);
        mini.setBackgroundColor(Ui.SURF2);
        mini.setVisibility(View.GONE);
        miniKey = "";
        miniProg = new ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal);
        miniProg.setProgressTintList(android.content.res.ColorStateList.valueOf(Ui.ACC));
        miniProg.setProgressBackgroundTintList(android.content.res.ColorStateList.valueOf(Ui.SURF));
        mini.addView(miniProg, new LinearLayout.LayoutParams(Ui.MATCH, Ui.dp(this, 3)));
        LinearLayout row = new LinearLayout(this);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding(Ui.dp(this, 12), Ui.dp(this, 6), Ui.dp(this, 4), Ui.dp(this, 6));

        miniThumb = new FrameLayout(this);
        row.addView(miniThumb, new LinearLayout.LayoutParams(Ui.dp(this, 44), Ui.dp(this, 44)));

        LinearLayout col = new LinearLayout(this);
        col.setOrientation(LinearLayout.VERTICAL);
        col.setPadding(Ui.dp(this, 10), 0, Ui.dp(this, 4), 0);
        miniTitle = Ui.text(this, "", 14, Ui.TXT, true);
        miniSub = Ui.text(this, "", 12, Ui.SUB, false);
        col.addView(miniTitle);
        col.addView(miniSub);
        row.addView(col, new LinearLayout.LayoutParams(0, Ui.WRAP, 1));

        ImageView prev = Ui.btn(this, R.drawable.ic_prev, Ui.TXT, 22, 7);
        prev.setOnClickListener(v -> engine.prev());
        row.addView(prev);
        miniPlay = Ui.btn(this, R.drawable.ic_play, Ui.BG, 20, 8);
        miniPlay.setBackground(Ui.rip(Ui.ACC, 100, this));
        miniPlay.setColorFilter(Ui.ONACC);
        miniPlay.setOnClickListener(v -> engine.toggle());
        row.addView(miniPlay);
        ImageView next = Ui.btn(this, R.drawable.ic_next, Ui.TXT, 22, 7);
        next.setOnClickListener(v -> engine.next(false));
        row.addView(next);

        LinearLayout qc = new LinearLayout(this);
        qc.setOrientation(LinearLayout.VERTICAL);
        qc.setGravity(Gravity.CENTER_HORIZONTAL);
        qc.setPadding(Ui.dp(this, 2), 0, Ui.dp(this, 4), 0);
        ImageView q = Ui.btn(this, R.drawable.ic_playlist, Ui.TXT, 22, 6);
        q.setOnClickListener(v -> showQueue());
        qc.addView(q);
        miniTime = Ui.text(this, "0:00 / 0:00", 10, Ui.SUB, false);
        miniTime.setGravity(Gravity.CENTER);
        qc.addView(miniTime, new LinearLayout.LayoutParams(Ui.WRAP, Ui.WRAP));
        row.addView(qc, new LinearLayout.LayoutParams(Ui.WRAP, Ui.WRAP));

        mini.addView(row, new LinearLayout.LayoutParams(Ui.MATCH, Ui.WRAP));
        mini.setOnClickListener(v -> openPlayer());
    }

    void updateMini() {
        Media m = engine.cur();
        boolean show = m != null && player.getVisibility() != View.VISIBLE;
        mini.setVisibility(show ? View.VISIBLE : View.GONE);
        if (!show) return;
        String k = m.key();
        if (!k.equals(miniKey)) {
            miniKey = k;
            miniThumb.removeAllViews();
            miniThumb.addView(Ui.thumbBox(this, m, 44), new FrameLayout.LayoutParams(Ui.MATCH, Ui.MATCH));
        }
        miniTitle.setText(m.title);
        miniSub.setText(m.artist);
        miniTime.setText(Ui.fmt(engine.pos()) + " / " + Ui.fmt(engine.dur()));
        Ic.set(miniPlay, engine.isPlaying() ? R.drawable.ic_pause : R.drawable.ic_play);
        miniProg.setMax(Math.max(1, engine.dur()));
        miniProg.setProgress(engine.pos());
    }

    @Override
    public void changed() {
        updateMini();
        refreshAll();
        if (queueAd != null && queueLayer != null) { queueAd.notifyDataSetChanged(); updateQueueHead(); }
    }

    void openPlayer() {
        player.setVisibility(View.VISIBLE);
        player.changed();
        player.onOpen();
        updateMini();
    }

    void closePlayer() {
        player.setVisibility(View.GONE);
        updateMini();
    }

    void playMedia(List<Media> ctx, int i) {
        if (i < 0 || i >= ctx.size()) return;
        if (ctx.get(i).video) engine.setAudioMode(false);
        engine.play(new ArrayList<>(ctx), i);
        openPlayer();
    }

    void enterPip() {
        try {
            Media m = engine.cur();
            float ar = 1f;
            if (m != null && m.video && !engine.audioMode && engine.vw > 0 && engine.vh > 0) ar = (float) engine.vw / engine.vh;
            ar = Math.max(0.45f, Math.min(2.3f, ar));
            android.app.PictureInPictureParams.Builder b = new android.app.PictureInPictureParams.Builder()
                    .setAspectRatio(new android.util.Rational(Math.round(ar * 1000), 1000));
            enterPictureInPictureMode(b.build());
        } catch (Exception e) {
            toast("Picture in picture is not available");
        }
    }

    @Override
    public void onPictureInPictureModeChanged(boolean isInPip, android.content.res.Configuration cfg) {
        super.onPictureInPictureModeChanged(isInPip, cfg);
        if (isInPip) {
            engine.spec.setUiVisible(true);
            h.removeCallbacks(ticker);
            h.post(ticker);
            if (player != null) player.startCapLoop();
        }
        if (player != null) player.setPip(isInPip);
        if (isInPip) hideQueue();
    }

    void hideQueue() {
        if (queueLayer != null && queueLayer.getParent() != null) root.removeView(queueLayer);
        queueLayer = null;
        queueAd = null;
        queueHead = null;
        queueHint = null;
        queueList = null;
        queueSelBar = null;
        qSelMode = false;
        qSel.clear();
    }

    void updateQueueHead() {
        if (queueHead != null) queueHead.setText(qSelMode ? qSel.size() + " selected" : "Play queue (" + engine.queue.size() + ")");
    }

    void showQueue() {
        hideQueue();
        final FrameLayout layer = new FrameLayout(this);
        layer.setOnClickListener(v -> hideQueue());
        queueLayer = layer;

        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setClickable(true);
        box.setBackground(Ui.shape(Ui.SURF, 16, this));
        box.setElevation(Ui.dp(this, 12));
        LinearLayout hrow = new LinearLayout(this);
        hrow.setGravity(Gravity.CENTER_VERTICAL);
        queueHead = Ui.text(this, "", 16, Ui.TXT, true);
        queueHead.setPadding(Ui.dp(this, 16), Ui.dp(this, 10), Ui.dp(this, 8), Ui.dp(this, 2));
        hrow.addView(queueHead, new LinearLayout.LayoutParams(0, Ui.WRAP, 1));
        ImageView qdots = Ui.btn(this, R.drawable.ic_more, Ui.TXT, 22, 8);
        qdots.setOnClickListener(v -> queueMenu());
        LinearLayout.LayoutParams qdp = new LinearLayout.LayoutParams(Ui.dp(this, 38), Ui.dp(this, 38));
        qdp.setMargins(0, Ui.dp(this, 4), Ui.dp(this, 6), 0);
        hrow.addView(qdots, qdp);
        box.addView(hrow, new LinearLayout.LayoutParams(Ui.MATCH, Ui.WRAP));
        updateQueueHead();
        TextView hint = Ui.text(this, "", 12, Ui.SUB, false);
        hint.setPadding(Ui.dp(this, 16), 0, Ui.dp(this, 16), Ui.dp(this, 8));
        queueHint = hint;
        box.addView(hint);
        queueSelBar = buildQueueSelBar();
        box.addView(queueSelBar, new LinearLayout.LayoutParams(Ui.MATCH, Ui.WRAP));
        refreshQueueMode();

        final QueueList lv = new QueueList(this);
        queueList = lv;
        lv.setDivider(null);
        lv.setSelector(new android.graphics.drawable.ColorDrawable(0));
        lv.setOverScrollMode(View.OVER_SCROLL_NEVER);
        final BaseAdapter ad = new BaseAdapter() {
            public int getCount() { return engine.queue.size(); }
            public Object getItem(int i) { return i; }
            public long getItemId(int i) { return i; }
            public View getView(int i, View cv, ViewGroup p) {
                Media m = engine.queue.get(i);
                boolean now = i == engine.idx;
                MainActivity c = MainActivity.this;
                LinearLayout r = new LinearLayout(c);
                r.setGravity(Gravity.CENTER_VERTICAL);
                r.setPadding(Ui.dp(c, 8), Ui.dp(c, 8), Ui.dp(c, 16), Ui.dp(c, 8));
                r.setBackground(Ui.rip(0, 0, c));
                if (qSelMode) {
                    LinearLayout.LayoutParams ckp = new LinearLayout.LayoutParams(Ui.dp(c, 22), Ui.dp(c, 22));
                    ckp.setMargins(Ui.dp(c, 8), 0, Ui.dp(c, 8), 0);
                    r.addView(Ui.check(c, qSel.contains(i)), ckp);
                } else {
                    ImageView grip = Ui.icon(c, R.drawable.ic_drag, Ui.SUB, 22);
                    grip.setPadding(Ui.dp(c, 8), Ui.dp(c, 8), Ui.dp(c, 8), Ui.dp(c, 8));
                    r.addView(grip, new LinearLayout.LayoutParams(Ui.dp(c, 38), Ui.dp(c, 38)));
                }
                LinearLayout.LayoutParams tp = new LinearLayout.LayoutParams(Ui.dp(c, 40), Ui.dp(c, 40));
                tp.setMargins(Ui.dp(c, 4), 0, 0, 0);
                r.addView(Ui.thumbBox(c, m, 40), tp);
                LinearLayout col = new LinearLayout(c);
                col.setOrientation(LinearLayout.VERTICAL);
                col.setPadding(Ui.dp(c, 12), 0, Ui.dp(c, 8), 0);
                col.addView(Ui.text(c, m.title, 14, now ? Ui.ACC : Ui.TXT, now));
                col.addView(Ui.text(c, m.artist, 12, Ui.SUB, false));
                r.addView(col, new LinearLayout.LayoutParams(0, Ui.WRAP, 1));
                if (now) {

                    LinearLayout.LayoutParams ep = new LinearLayout.LayoutParams(Ui.dp(c, 18), Ui.dp(c, 16));
                    ep.setMargins(0, 0, Ui.dp(c, 10), 0);
                    r.addView(new Ui.Eq(c, Ui.ACC, engine.isPlaying()), ep);
                }
                r.addView(Ui.text(c, Ui.fmt(m.duration), 12, Ui.SUB, false));
                if (i == lv.drag) r.setAlpha(0f);
                return r;
            }
        };
        queueAd = ad;
        lv.setAdapter(ad);
        lv.cb = new QueueList.Cb() {
            public void move(int from, int to) { engine.moveQueue(from, to); ad.notifyDataSetChanged(); }
            public void done() { ad.notifyDataSetChanged(); }
        };
        lv.setOnItemClickListener((p, v, i, id) -> {
            if (!qSelMode) { queueOptions(i); return; }
            if (!qSel.remove(i)) qSel.add(i);
            ad.notifyDataSetChanged();
            updateQueueHead();
        });
        int maxH = Math.min(Ui.dp(this, 56) * Math.max(1, engine.queue.size()), root.getHeight() * 50 / 100);
        box.addView(lv, new LinearLayout.LayoutParams(Ui.MATCH, maxH));

        lv.post(() -> {
            if (engine.idx >= 0 && engine.idx < engine.queue.size())
                lv.setSelectionFromTop(engine.idx, Math.max(0, (lv.getHeight() - Ui.dp(this, 56)) / 2));
        });

        boolean pl = player.getVisibility() == View.VISIBLE;
        FrameLayout.LayoutParams bp = new FrameLayout.LayoutParams(Ui.MATCH, Ui.WRAP, Gravity.BOTTOM);
        bp.setMargins(Ui.dp(this, 8), 0, Ui.dp(this, 8), (pl ? 0 : nav.getHeight() + mini.getHeight()) + Ui.dp(this, 8));
        layer.addView(box, bp);
        root.addView(layer, new FrameLayout.LayoutParams(Ui.MATCH, Ui.MATCH));
    }

    void queueMenu() {
        if (engine.queue.isEmpty()) { toast("The queue is empty"); return; }
        Ui.Sheet sh = new Ui.Sheet(this, "Play queue", engine.queue.size() + " items");
        sh.item(R.drawable.ic_playlist, "Add Queue Playlist", Ui.TXT, () -> addQueuePlaylist(new ArrayList<>(engine.queue)));
        sh.item(R.drawable.ic_select, "Select Item", Ui.TXT, () -> { qSelMode = true; qSel.clear(); refreshQueueMode(); });
        sh.item(R.drawable.ic_delete, "Clear Queue", Ui.RED, () ->
                confirmDanger("Clear queue?", "All " + engine.queue.size() + " items are removed from the play queue and playback stops.", "Clear", () -> {
                    engine.clearQueue();
                    hideQueue();
                }));
        sh.show();
    }

    void addQueuePlaylist(List<Media> src) {
        if (src.isEmpty()) { toast("Nothing to add"); return; }
        final List<Media> au = new ArrayList<>(), vi = new ArrayList<>();
        for (Media m : src) (m.video ? vi : au).add(m);
        if (vi.isEmpty()) pickPlaylist(au, null);
        else if (au.isEmpty()) pickPlaylist(vi, null);
        else {
            Ui.Sheet sh = new Ui.Sheet(this, "Add Queue Playlist", "Audios and videos have separate playlists");
            sh.item(R.drawable.ic_music, "Audios (" + au.size() + ")", Ui.TXT, () -> pickPlaylist(au, null));
            sh.item(R.drawable.ic_movie, "Videos (" + vi.size() + ")", Ui.TXT, () -> pickPlaylist(vi, null));
            sh.show();
        }
    }

    List<Media> selectedQueueItems() {
        List<Media> l = new ArrayList<>();
        for (int i = 0; i < engine.queue.size(); i++) if (qSel.contains(i)) l.add(engine.queue.get(i));
        return l;
    }

    LinearLayout buildQueueSelBar() {
        LinearLayout bar = new LinearLayout(this);
        bar.setPadding(Ui.dp(this, 12), 0, Ui.dp(this, 6), Ui.dp(this, 8));
        String[] labels = {"All", "Add Queue Playlist", "Clear Queue", "Done"};
        float[] weights = {.6f, 2.2f, 1.4f, .8f};
        for (int k = 0; k < labels.length; k++) {
            final int kk = k;
            TextView t = Ui.text(this, labels[k], 12, k == 2 ? Ui.RED : Ui.TXT, true);
            t.setGravity(Gravity.CENTER);
            t.setSingleLine(true);
            t.setPadding(Ui.dp(this, 4), Ui.dp(this, 9), Ui.dp(this, 4), Ui.dp(this, 9));
            t.setBackground(Ui.rip(Ui.SURF2, 16, this));
            t.setOnClickListener(v -> queueSelAction(kk));
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0, Ui.WRAP, weights[k]);
            lp.setMargins(0, 0, Ui.dp(this, 6), 0);
            bar.addView(t, lp);
        }
        return bar;
    }

    void queueSelAction(int k) {
        if (k == 0) {
            boolean all = qSel.size() == engine.queue.size();
            qSel.clear();
            if (!all) for (int i = 0; i < engine.queue.size(); i++) qSel.add(i);
        } else if (k == 1) {
            List<Media> l = selectedQueueItems();
            if (l.isEmpty()) { toast("Select items first"); return; }
            addQueuePlaylist(l);
            return;
        } else if (k == 2) {
            final java.util.ArrayList<Integer> which = new java.util.ArrayList<>(qSel);
            if (which.isEmpty()) { toast("Select items first"); return; }
            confirmDanger("Remove from queue?", which.size() + (which.size() == 1 ? " item is" : " items are") + " removed from the play queue.", "Remove", () -> {
                engine.removeMany(which);
                qSel.clear();
                if (engine.queue.isEmpty()) { hideQueue(); return; }
                qSelMode = false;
                refreshQueueMode();
            });
            return;
        } else {
            qSelMode = false;
            qSel.clear();
        }
        refreshQueueMode();
    }

    void refreshQueueMode() {
        if (queueSelBar != null) queueSelBar.setVisibility(qSelMode ? View.VISIBLE : View.GONE);
        if (queueHint != null) queueHint.setText(qSelMode ? "Tap items to select them."
                : "Hold \u2261 and drag to re-arrange. Tap an item for options.");
        if (queueList != null) queueList.noDrag = qSelMode;
        if (queueAd != null) queueAd.notifyDataSetChanged();
        updateQueueHead();
    }

    void queueOptions(final int i) {
        if (i < 0 || i >= engine.queue.size()) return;
        final Media m = engine.queue.get(i);
        Ui.Sheet sh = new Ui.Sheet(this, m.title, m.artist);
        sh.item(R.drawable.ic_play, "Play now", Ui.TXT, () -> engine.playAt(i));
        sh.item(R.drawable.ic_close, "Remove from queue", Ui.TXT, () -> {
            engine.removeAt(i);
            if (engine.queue.isEmpty()) hideQueue();
            else if (queueAd != null) { queueAd.notifyDataSetChanged(); updateQueueHead(); }
        });
        sh.item(R.drawable.ic_playlist, "Add in playlist", Ui.TXT, () -> pickPlaylist(Collections.singletonList(m), null));
        boolean f = store.isFav(m);
        sh.item(f ? R.drawable.ic_heart_fill : R.drawable.ic_heart, f ? "Remove from favorites" : "Add to favorites", Ui.TXT, () -> {
            store.toggleFav(m);
            refreshAll();
            player.changed();
            toast(store.isFav(m) ? "Added to favorites" : "Removed from favorites");
        });
        sh.show();
    }

    void showOptions(final Media m, final Runnable removeFn) {
        Ui.Sheet sh = new Ui.Sheet(this, m.title, m.artist);
        Media cur = engine.cur();
        boolean isCur = cur != null && cur.key().equals(m.key());
        if (m.video) {
            if (isCur && engine.audioMode) sh.item(R.drawable.ic_movie, "Play video", Ui.TXT, () -> engine.setAudioMode(false));
            else sh.item(R.drawable.ic_headphones, "Play as audio", Ui.TXT, () -> {
                engine.setAudioMode(true);
                if (!isCur) {
                    List<Media> l = media(true);
                    int i = l.indexOf(m);
                    if (i < 0) { l = Collections.singletonList(m); i = 0; }
                    engine.play(new ArrayList<>(l), i);
                }
                closePlayer();
            });
        }
        sh.item(R.drawable.ic_add_queue, "Add in play queue", Ui.TXT, () -> toast(engine.addToQueue(m) ? "Added to queue" : "Already in queue"));
        sh.item(R.drawable.ic_play_next, "Play next", Ui.TXT, () -> {
            int r = engine.playNext(m);
            toast(r == Engine.NEXT_SAME ? "Already playing" : r == Engine.NEXT_MOVED ? "Moved to play next" : "Playing next");
        });
        sh.item(R.drawable.ic_playlist, "Add in playlist", Ui.TXT, () -> pickPlaylist(Collections.singletonList(m), null));
        boolean f = store.isFav(m);
        sh.item(f ? R.drawable.ic_heart_fill : R.drawable.ic_heart, f ? "Remove from favorites" : "Add to favorites", Ui.TXT,
                () -> { store.toggleFav(m); refreshAll(); player.changed(); });
        if (removeFn != null) sh.item(R.drawable.ic_close, "Remove from playlist", Ui.TXT, removeFn);
        sh.item(R.drawable.ic_edit, "Rename", Ui.TXT, () -> promptName("Rename", m.title, n -> doRename(m, n)));
        sh.item(R.drawable.ic_edit, "Advanced Editor", Ui.TXT, () -> meta.open(m));
        sh.item(R.drawable.ic_info, "Info", Ui.TXT, () -> meta.info(m));
        sh.item(R.drawable.ic_delete, m.video ? "Delete Video" : "Delete Audio", Ui.RED, () -> confirmDeleteMany(Collections.singletonList(m), null));
        sh.show();
    }

    void pickPlaylist(final List<Media> ms, final Runnable after) {
        if (ms.isEmpty()) return;
        final boolean vid = ms.get(0).video;
        Ui.Sheet sh = new Ui.Sheet(this, "Add to playlist", ms.size() > 1 ? ms.size() + " items" : null);
        sh.item(R.drawable.ic_add, "New playlist", Ui.ACC, () -> promptName("New playlist", n -> {
            store.createPlaylist(vid, n);
            addAllTo(ms, n);
            if (after != null) after.run();
        }));
        for (final String n : store.playlists(vid))
            sh.item(R.drawable.ic_playlist, n, Ui.TXT, () -> { addAllTo(ms, n); if (after != null) after.run(); });
        sh.show();
    }

    void addAllTo(List<Media> ms, String n) {
        int added = store.addAllToPlaylist(ms, n);
        refreshAll();
        toast(added == 0 ? "Already in " + n
                : added < ms.size() ? added + " added to " + n + " (" + (ms.size() - added) + " already in it)"
                : "Added to " + n);
    }

    void promptName(String title, final Consumer<String> done) { promptName(title, "", done); }

    void promptName(String title, String initial, final Consumer<String> done) {
        Ui.prompt(this, title, initial, "Name", title.startsWith("New") ? "Create" : "OK", done);
    }

    void confirm(String title, String msg, String ok, final Runnable r) { Ui.confirm(this, title, msg, ok, false, r); }

    void confirmDanger(String title, String msg, String ok, final Runnable r) { Ui.confirm(this, title, msg, ok, true, r); }

    static String ext(String fileName) {
        if (fileName == null) return "";
        int dot = fileName.lastIndexOf('.');
        return dot > 0 ? fileName.substring(dot) : "";
    }

    void doRename(Media m, String raw) {
        String n = raw.replaceAll("[\\\\/:*?\"<>|]", "").trim();
        if (n.isEmpty()) { toast("That name can't be used"); return; }
        if (m.filePath != null) { renameHiddenFile(m, n); return; }
        applyRename(m, n, false);
    }

    void renameHiddenFile(Media m, String n) {
        File f = new File(m.filePath);
        String dn = f.getName();
        File nf = new File(f.getParentFile(), (dn.startsWith(".") ? "." : "") + n + ext(dn));
        if (nf.exists()) { toast("A file with that name already exists"); return; }
        if (!f.renameTo(nf)) { toast("Could not rename"); return; }
        String oldKey = m.key();
        m.filePath = nf.getAbsolutePath();
        m.uri = Uri.fromFile(nf);
        m.id = -(Math.abs((long) m.filePath.hashCode()) + 1);
        m.name = nf.getName();
        store.rekey(oldKey, m.key());
        byKey.remove(oldKey);
        byKey.put(m.key(), m);
        finishRename(m, n);
    }

    void applyRename(Media m, String n, boolean granted) {
        ContentValues v = new ContentValues();
        v.put(MediaStore.MediaColumns.DISPLAY_NAME, n + ext(m.name));
        v.put(MediaStore.MediaColumns.TITLE, n);
        try {
            int c = getContentResolver().update(m.uri, v, null, null);
            if (c > 0) { m.name = n + ext(m.name); finishRename(m, n); }
            else toast("Could not rename");
        } catch (SecurityException e) {
            if (granted) { toast("Could not rename"); return; }
            try {
                PendingIntent pi = MediaStore.createWriteRequest(getContentResolver(), Collections.singletonList(m.uri));
                pendingRen = m;
                pendingRenName = n;
                startIntentSenderForResult(pi.getIntentSender(), 78, null, 0, 0, 0);
            } catch (Exception e2) { toast("Could not rename"); }
        } catch (Exception e) { toast("Could not rename"); }
    }

    void finishRename(Media m, String n) {
        m.title = n;
        store.setRenamed(m, n);
        for (Media q : engine.queue) if (q.key().equals(m.key())) q.title = n;
        for (Media q : m.video ? videos : audios) if (q.key().equals(m.key())) q.title = n;
        engine.fire();
        toast("Renamed");
    }

    void confirmDeleteMany(final List<Media> ms, final Runnable after) {
        if (ms.isEmpty()) return;
        boolean one = ms.size() == 1;
        confirmDanger(one ? (ms.get(0).video ? "Delete video?" : "Delete audio?") : "Delete " + ms.size() + " items?",
                one ? "\"" + ms.get(0).title + "\" will be permanently removed from your device."
                        : "These " + ms.size() + " items will be permanently removed from your device.",
                "Delete", () -> deleteNow(ms, after));
    }

    void deleteNow(List<Media> ms, Runnable after) {
        List<Uri> uris = new ArrayList<>();
        List<Media> viaStore = new ArrayList<>();
        int done = 0, failed = 0;
        for (Media m : ms) {
            if (m.filePath != null) {
                if (new File(m.filePath).delete()) { removeDeleted(m); done++; } else failed++;
            } else { uris.add(m.uri); viaStore.add(m); }
        }
        if (!uris.isEmpty()) {
            try {
                PendingIntent pi = MediaStore.createDeleteRequest(getContentResolver(), uris);
                pendingDel = viaStore;
                pendingDelAfter = after;
                refreshAll();
                startIntentSenderForResult(pi.getIntentSender(), 77, null, 0, 0, 0);
                if (failed > 0) toast("Could not delete " + failed);
                return;
            } catch (Exception e) { failed += uris.size(); }
        }
        refreshAll();
        if (after != null) after.run();
        toast(failed > 0 ? "Could not delete " + failed : (done > 1 ? "Deleted " + done + " items" : "Deleted"));
    }

    void removeDeleted(Media m) {
        engine.remove(m.key());
        store.purge(m);
        (m.video ? videos : audios).remove(m);
        byKey.remove(m.key());
    }

    @Override
    protected void onActivityResult(int req, int res, Intent data) {
        super.onActivityResult(req, res, data);
        if (req == 77) {
            if (res == RESULT_OK) {
                int n = pendingDel.size();
                for (Media m : new ArrayList<>(pendingDel)) removeDeleted(m);
                refreshAll();
                if (pendingDelAfter != null) pendingDelAfter.run();
                toast(n > 1 ? "Deleted " + n + " items" : "Deleted");
            }
            pendingDel = new ArrayList<>();
            pendingDelAfter = null;
        } else if (req == IconOverride.REQ) {
            IconOverride.onPicked(this, res, data);
        } else if (req == IconOverride.REQ_SAVE) {
            IconOverride.onSaved(this, res, data);
        } else if (req == HomeAdd.REQ_ICON) {
            HomeAdd.onPicked(this, res, data);
        } else if (req == 80) {
            if (res == RESULT_OK && data != null && data.getData() != null) writeBackup(data.getData());
        } else if (req == 81) {
            if (res == RESULT_OK && data != null && data.getData() != null) askRestore(data.getData());
        } else if (req == 82) {
            if (res == RESULT_OK && data != null && data.getData() != null && pendingCapMedia != null)
                importCaptions(pendingCapMedia, data.getData());
            pendingCapMedia = null;
        } else if (req == MetaEditor.REQ_TAGS) {
            meta.onTagPermission(res);
        } else if (req == PhotoEditor.REQ_FONT) {
            editor.onFontResult(res, data);
        } else if (req == PhotoEditor.REQ_WRITE) {
            editor.onWriteResult(res);
        } else if (req == MetaEditor.REQ_COVER) {
            meta.onCoverResult(res, data);
        } else if (req == PhotoOps.REQ_DEL) {
            pops.onDeleteResult(res);
        } else if (req == PhotoOps.REQ_REN) {
            pops.onRenameResult(res);
        } else if (req == 78) {
            if (res == RESULT_OK && pendingRen != null) applyRename(pendingRen, pendingRenName, true);
            pendingRen = null;
            pendingRenName = null;
        }
    }

    void pickCaptions(Media m) {
        pendingCapMedia = m;
        Intent i = new Intent(Intent.ACTION_OPEN_DOCUMENT);
        i.addCategory(Intent.CATEGORY_OPENABLE);
        i.setType("*/*");
        i.putExtra(Intent.EXTRA_MIME_TYPES, new String[]{"application/x-subrip", "text/vtt", "text/plain", "application/octet-stream", "text/*",
                "application/ttml+xml", "application/xml", "application/x-subtitle"});
        try { startActivityForResult(i, 82); } catch (Exception e) { pendingCapMedia = null; toast("No file picker available"); }
    }

    void importCaptions(Media m, Uri u) {
        try (InputStream is = getContentResolver().openInputStream(u)) {
            java.io.ByteArrayOutputStream b = new java.io.ByteArrayOutputStream();
            byte[] buf = new byte[16384];
            int n;
            while ((n = is.read(buf)) > 0) {
                b.write(buf, 0, n);
                if (b.size() > 4 * 1024 * 1024) throw new Exception("too big");
            }
            String text = Captions.decode(b.toByteArray());
            if (Captions.parse(text) == null) {
                Ui.notice(this, "Can't read captions", "No subtitles were found in " + displayName(u) + ". Supported: .srt .vtt .ttml .dfxp .xml .ass .ssa .sbv .lrc .sub");
                return;
            }
            if (!store.saveCaps(m.key(), text)) { Ui.notice(this, "Captions not saved", "The file could not be stored."); return; }
            store.setBool("caps_on", true);
            player.capsChanged();
            toast("Captions added");
        } catch (Exception e) {
            Ui.notice(this, "Can't read captions", "That file could not be opened or is too large.");
        }
    }

    private boolean specChecked;

    /**
     * The spectrum / Vibe needs the Microphone permission to read the sound the app plays. If it was turned on by
     * a restored backup (or the permission was revoked later) nothing ever asked, so the visual just sat there
     * drawing silence. Ask again here; once per app run so it never nags.
     */
    void checkSpecPermission() {
        if (specChecked || Engine.SAVER) return;
        String key = store.bool("spec_player", false) ? "spec_player" : store.bool("spec_sync", false) ? "spec_sync" : null;
        if (key == null || engine.spec.permitted()) return;
        specChecked = true;
        specToggled(key);
    }

    void specToggled(final String key) {
        if (store.bool(key, false) && !engine.spec.permitted()) {

            if (store.bool("mic_asked", false) && !shouldShowRequestPermissionRationale("android.permission.RECORD_AUDIO")) {
                store.setBool(key, false);
                rebuildUi();
                micBlocked();
                return;
            }
            Ui.confirm(this, "Allow audio access?",
                    "Android only lets an app read the audio spectrum with the Microphone permission. Nothing is recorded, "
                            + "the app only looks at the sound it is playing itself.", "Continue", false,
                    () -> { pendingSpecKey = key; store.setBool("mic_asked", true); requestPermissions(new String[]{"android.permission.RECORD_AUDIO"}, 2); },
                    () -> { store.setBool(key, false); rebuildUi(); });
            return;
        }
        engine.spec.retry();
        engine.spec.update();
        engine.fire();
    }

    void micBlocked() {
        Ui.confirm(this, "Allow microphone in settings", "Android no longer shows the permission question. Open the app settings, "
                + "tap Permissions > Microphone > Allow, come back and turn the spectrum on again. Nothing is recorded.",
                "Open settings", false, this::openAppSettings);
    }

    Uri restoreUri;

    void createBackup() {
        Intent i = new Intent(Intent.ACTION_CREATE_DOCUMENT);
        i.addCategory(Intent.CATEGORY_OPENABLE);
        i.setType("application/zip");
        i.putExtra(Intent.EXTRA_TITLE, Backup.fileName());
        try { startActivityForResult(i, 80); } catch (Exception e) { toast("No file picker available"); }
    }

    void pickBackup() {
        Intent i = new Intent(Intent.ACTION_OPEN_DOCUMENT);
        i.addCategory(Intent.CATEGORY_OPENABLE);
        i.setType("*/*");
        i.putExtra(Intent.EXTRA_MIME_TYPES, new String[]{"application/zip", "application/x-zip-compressed", "application/octet-stream"});
        try { startActivityForResult(i, 81); } catch (Exception e) { toast("No file picker available"); }
    }

    String displayName(Uri u) {
        try (Cursor c = getContentResolver().query(u, new String[]{OpenableColumns.DISPLAY_NAME}, null, null, null)) {
            if (c != null && c.moveToFirst() && !c.isNull(0)) return c.getString(0);
        } catch (Exception e) { }
        return "this backup";
    }

    void writeBackup(Uri u) {
        try (OutputStream os = getContentResolver().openOutputStream(u, "wt")) {
            Backup.create(this, store, byKey, photoByKey, os);
            Ui.notice(this, "Backup created", "Saved as " + displayName(u) + "\n\n" + store.list("fav").size() + " favorites, "
                    + store.playlists(false).size() + " audio playlists, " + store.playlists(true).size() + " video playlists, "
                    + store.capKeys().size() + " captions, " + store.list("pfav").size() + " photo favorites, "
                    + store.collections().size() + " photo collections and your settings.");
        } catch (Exception e) {
            Ui.notice(this, "Backup failed", "The backup could not be written. Try another location.");
        }
    }

    void askRestore(final Uri u) {
        Ui.choose(this, "Restore backup?",
                "Merge keeps what you have now and only adds what is in " + displayName(u)
                        + " (new favorites, playlists and items, and settings you never changed).\n\n"
                        + "Replace swaps your favorites, playlists, collections and settings for the ones in the backup.",
                "Merge", false, () -> restore(u, false),
                "Replace", true, () -> restore(u, true));
    }

    void restore(Uri u, boolean replace) {
        try (InputStream is = getContentResolver().openInputStream(u)) {
            Backup.Result r = Backup.restore(store, byKey, photoByKey, is, replace);
            engine.reloadSettings();
            rebuildUi();
            specChecked = false;
            h.postDelayed(this::checkSpecPermission, 1500);
            if (store.bool("img_on", false) && !imageOk()) requestPermissions(perms(), 3);
            loadMedia();
            player.changed();
            String restoredSummary = r.favorites + (replace ? " favorites, " : " new favorites, ")
                    + r.audioPlaylists + " audio playlists, " + r.videoPlaylists + " video playlists, "
                    + r.photoFavorites + (replace ? " photo favorites, " : " new photo favorites, ")
                    + r.collections + " photo collections, " + r.captions + " captions, "
                    + r.settings + " settings.";
            if (!r.legacyEdits.isEmpty()) {
                restoredSummary += "\n\nThis older backup also contains legacy metadata edits. Vidsic will apply them to the original files next; Android may ask for permission.";
                Ui.notice(this, replace ? "Backup restored" : "Backup merged", restoredSummary,
                        () -> meta.applyRestoredEdits(r.legacyEdits));
            } else {
                Ui.notice(this, replace ? "Backup restored" : "Backup merged", restoredSummary);
            }
        } catch (Exception e) {
            Ui.notice(this, "Restore failed", "That file is not a Vidsic backup, or it is damaged. Nothing was changed.");
        }
    }

    LinearLayout group(LinearLayout col, String title) {
        TextView v = Ui.text(this, title, 13, Ui.ACC, true);
        v.setPadding(Ui.dp(this, 20), Ui.dp(this, 20), Ui.dp(this, 16), Ui.dp(this, 8));
        col.addView(v);
        LinearLayout card = new LinearLayout(this);
        card.setOrientation(LinearLayout.VERTICAL);
        card.setBackground(Ui.shape(Ui.SURF, 16, this));
        card.setClipToOutline(true);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(Ui.MATCH, Ui.WRAP);
        lp.setMargins(Ui.dp(this, 16), 0, Ui.dp(this, 16), 0);
        col.addView(card, lp);
        return card;
    }

    void add(LinearLayout card, View row) {
        if (card.getChildCount() > 0) {
            View d = new View(this);
            d.setBackgroundColor(Ui.SURF2);
            LinearLayout.LayoutParams dp = new LinearLayout.LayoutParams(Ui.MATCH, Math.max(1, Ui.dp(this, 1)));
            dp.setMargins(Ui.dp(this, 16), 0, 0, 0);
            card.addView(d, dp);
        }
        card.addView(row, new LinearLayout.LayoutParams(Ui.MATCH, Ui.WRAP));
    }

    void note(LinearLayout card, String t) {
        TextView v = Ui.text(this, t, 12, Ui.SUB, false);
        v.setSingleLine(false);
        v.setPadding(Ui.dp(this, 16), Ui.dp(this, 10), Ui.dp(this, 16), Ui.dp(this, 10));
        add(card, v);
    }

    void chips(LinearLayout card, String[] labels, String[] vals, String cur, Consumer<String> on) {
        LinearLayout r = new LinearLayout(this);
        r.setPadding(Ui.dp(this, 16), Ui.dp(this, 12), Ui.dp(this, 8), Ui.dp(this, 12));
        for (int i = 0; i < labels.length; i++) {
            final String v = vals[i];
            boolean sel = v.equals(cur);
            TextView t = Ui.text(this, labels[i], 14, sel ? Ui.ONACC : Ui.TXT, true);
            t.setGravity(Gravity.CENTER);
            t.setPadding(Ui.dp(this, 8), Ui.dp(this, 11), Ui.dp(this, 8), Ui.dp(this, 11));
            t.setBackground(Ui.rip(sel ? Ui.ACC : Ui.SURF2, 20, this));
            t.setOnClickListener(x -> on.accept(v));
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0, Ui.WRAP, 1);
            lp.setMargins(0, 0, Ui.dp(this, 8), 0);
            r.addView(t, lp);
        }
        add(card, r);
    }

    void swatches(LinearLayout card) {
        int[] cs = {0xFF8B6BFF, 0xFF4C8DFF, 0xFF1FC8B0, 0xFF4CD964, 0xFFFFC83D, 0xFFFF8A3D, 0xFFFF5C5C, 0xFFFF6BB5};
        int cur = store.p.getInt("c_acc", 0xFF8B6BFF);
        HorizontalScrollView hs = new HorizontalScrollView(this);
        hs.setHorizontalScrollBarEnabled(false);
        LinearLayout r = new LinearLayout(this);
        r.setPadding(Ui.dp(this, 10), Ui.dp(this, 10), Ui.dp(this, 10), Ui.dp(this, 12));
        for (final int c : cs) {
            View sw = new View(this);
            GradientDrawable g = new GradientDrawable();
            g.setShape(GradientDrawable.OVAL);
            g.setColor(c);
            if (c == cur) g.setStroke(Ui.dp(this, 3), Ui.TXT);
            sw.setBackground(g);
            sw.setOnClickListener(x -> { store.p.edit().putInt("c_acc", c).apply(); rebuildUi(); });
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(Ui.dp(this, 40), Ui.dp(this, 40));
            lp.setMargins(Ui.dp(this, 6), 0, Ui.dp(this, 6), 0);
            r.addView(sw, lp);
        }
        hs.addView(r);
        add(card, hs);
    }

    String versionName() {
        try { return getPackageManager().getPackageInfo(getPackageName(), 0).versionName; } catch (Exception e) { return "36.0"; }
    }

    ScrollView buildSettings() {
        ScrollView sv = new ScrollView(this);
        sv.setBackgroundColor(Ui.BG);
        LinearLayout col = new LinearLayout(this);
        col.setOrientation(LinearLayout.VERTICAL);
        col.setPadding(0, Ui.dp(this, 12), 0, Ui.dp(this, 28));
        sv.addView(col);
        TextView t = Ui.text(this, "Settings", 24, Ui.TXT, true);
        t.setPadding(Ui.dp(this, 20), Ui.dp(this, 8), Ui.dp(this, 16), Ui.dp(this, 0));
        col.addView(t);

        LinearLayout card = group(col, "THEME");
        String th = store.p.getString("theme", "app");
        chips(card, new String[]{"App", "Device", "Customize"}, new String[]{"app", "device", "custom"}, th,
                v -> { store.p.edit().putString("theme", v).apply(); rebuildUi(); });
        if (th.equals("device") && Build.VERSION.SDK_INT < 31) note(card, "Needs Android 12 or newer.");
        if (th.equals("custom")) {
            note(card, "Base");
            chips(card, new String[]{"Dark", "Light", "Black"}, new String[]{"dark", "light", "black"},
                    store.p.getString("c_base", "dark"), v -> { store.p.edit().putString("c_base", v).apply(); rebuildUi(); });
            note(card, "Accent color");
            swatches(card);
        }

        card = group(col, "DRAWABLES");
        toggle(card, "Override Drawable", "Replace the app's icons with your own", Ic.PREF_ON, false, () -> {
            Ic.invalidate();
            engine.fire();
            rebuildUi();
        });
        if (store.bool(Ic.PREF_ON, false)) {
            action(card, R.drawable.ic_edit, "Advance Drawable", () -> IconOverride.open(this));
            note(card, "Pick an icon, change it with your own .xml vector, then press Apply.");
        }

        card = group(col, "LIBRARY");
        toggle(card, "Show hidden audios", "Include audio other apps can't see", "hid_a", false, this::hiddenChanged);
        toggle(card, "Show hidden videos", "Include videos other apps can't see", "hid_v", false, this::hiddenChanged);
        toggle(card, "Show hidden folders", "Show them in the Folders tab", "hid_f", false, this::hiddenChanged);
        action(card, R.drawable.ic_folder, "All files access", () -> openAllFiles());
        note(card, "Needed to find hidden files.");
        action(card, R.drawable.ic_folder, "Rescan library", () -> { loadMedia(); toast("Scanning..."); });
        toggle(card, "Auto rescan library", "Keep the list up to date by itself",
                "auto_rescan", false, () -> { if (store.bool("auto_rescan", false)) loadMedia(); });
        action(card, R.drawable.ic_settings, "Grant media access", this::grantAccess);
        toggle(card, "Search Specified", "Search only inside the folder, album, playlist or other group you have open, not the whole list",
                "search_scope", false, () -> { refreshAll(); });

        card = group(col, "PLAYBACK");
        toggle(card, "Play next automatically", "Continue with the next item in the queue", "autonext", true, null);
        toggle(card, "Screen Wake Lock", "Keep the screen on while playing",
                "wake_lock", true, () -> { if (player != null) player.updateKeepOn(); });
        toggle(card, "Show history", "Show recently played at the top of Audios / Videos", "showhist", true, null);
        toggle(card, "Allow play queue duplication", "Let the same item be in the queue more than once", "q_dup", false, null);

        card = group(col, "BATTERY");
        toggle(card, "Battery Saving Mode", "Pauses visual effects and background extras", "batt_saver", false, () -> {
            engine.powerChanged();
            if (player != null) player.updateKeepOn();
            rebuildUi();
        });
        toggle(card, "Follow system Battery Saver", "Turn on together with Android's Battery Saver",
                "batt_auto", false, () -> {
            engine.powerChanged();
            if (player != null) player.updateKeepOn();
            rebuildUi();
        });
        note(card, Engine.SAVER ? "Battery Saving Mode is on." : "Off. Audio spectrum, Vibe, auto rescan and Bluetooth auto-play rest while it is on.");

        card = group(col, "HEADSET");
        toggle(card, "Auto-play via bluetooth", "Resume playing when a bluetooth headset or speaker connects", "bt_play", false, null);
        toggle(card, "Pause when headphones detach", "Pause music when the headphones are unplugged or disconnect", "pause_detach", true, null);
        toggle(card, "Volume auto pause", "Pause when the volume reaches 0 and play again when it is turned up", "vol_pause", false, null);

        card = group(col, "HISTORY");
        action(card, R.drawable.ic_history, "Clear play history", () ->
                confirm("Clear play history?", "Your recently played lists will be emptied.", "Clear", () -> {
                    store.p.edit().remove(store.hk(false)).remove(store.hk(true)).apply();
                    refreshAll();
                    toast("History cleared");
                }));
        action(card, R.drawable.ic_search, "Clear search history", () ->
                confirm("Clear search history?", "Your recent searches will be removed.", "Clear", () -> {
                    store.p.edit().remove(store.sk(false)).remove(store.sk(true)).apply();
                    refreshAll();
                    toast("Searches cleared");
                }));

        card = group(col, "BACKUP");
        action(card, R.drawable.ic_backup, "Create Backup", this::createBackup);
        action(card, R.drawable.ic_restore, "Restore Backup", this::pickBackup);
        note(card, "Saves your settings, favorites, playlists and edits to a file.");

        card = group(col, "VISUALIZER");
        toggle(card, "Audio Spectrum Sync", "Bars move with the actual sound",
                "spec_sync", false, () -> specToggled("spec_sync"));
        toggle(card, "Audio Player Spectrum", "Show the visualizer in the player",
                "spec_player", false, () -> specToggled("spec_player"));
        note(card, "Visual");
        chips(card, new String[]{"Bar", "Vibe"}, new String[]{"bar", "vibe"}, store.p.getString("viz_mode", "bar"),
                v -> { store.p.edit().putString("viz_mode", v).apply(); engine.fire(); rebuildUi(); });
        note(card, "Bar: classic bars. Vibe: a visual that reacts to the mood of the music.");

        card = group(col, "IMAGE VIEWER");
        toggle(card, "Image Viewer", "Adds a Photos tab for your pictures",
                "img_on", false, this::imageViewerChanged);
        if (store.bool("img_on", false)) {
            action(card, R.drawable.ic_slideshow, "Slideshow settings", () -> PhotoViewer.slideshowSettings(this, null));
        }

        card = group(col, "ABOUT");
        LinearLayout about = new LinearLayout(this);
        about.setGravity(Gravity.CENTER_VERTICAL);
        about.setPadding(Ui.dp(this, 16), Ui.dp(this, 14), Ui.dp(this, 16), Ui.dp(this, 14));
        ImageView logo = new ImageView(this);
        logo.setImageResource(R.mipmap.ic_launcher);
        about.addView(logo, new LinearLayout.LayoutParams(Ui.dp(this, 48), Ui.dp(this, 48)));
        LinearLayout tx = new LinearLayout(this);
        tx.setOrientation(LinearLayout.VERTICAL);
        tx.setPadding(Ui.dp(this, 14), 0, 0, 0);
        tx.addView(Ui.text(this, "Vidsic Player " + versionName(), 16, Ui.TXT, true));
        tx.addView(Ui.text(this, "By CHXD", 13, Ui.SUB, false));
        about.addView(tx, new LinearLayout.LayoutParams(0, Ui.WRAP, 1));
        add(card, about);
        return sv;
    }

    void refreshStats() {
        if (stats == null) return;
        stats.setText(audios.size() + " audios, " + videos.size() + " videos found\nAudio access: " + (audioOk() ? "yes" : "NO")
                + "  |  Video access: " + (videoOk() ? "yes" : "NO")
                + "  |  All files: " + (Environment.isExternalStorageManager() ? "yes" : "no"));
    }

    void openAllFiles() {
        try {
            startActivity(new Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION, Uri.parse("package:" + getPackageName())));
        } catch (Exception e) {
            try { startActivity(new Intent(Settings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION)); } catch (Exception e2) { }
        }
    }

    void imageViewerChanged() {
        boolean on = store.bool("img_on", false);
        if (on && !imageOk()) requestPermissions(perms(), 3);
        if (!on && viewer != null) viewer.close();
        photos = new ArrayList<>();
        photoByKey.clear();
        page = P_SET;
        rebuildUi();
        if (on) loadMedia();
    }

    void hiddenChanged() {
        boolean mgr = Environment.isExternalStorageManager();
        if (wantHidden() && !mgr) { toast("Allow \"All files access\" to find hidden files"); openAllFiles(); }
        if (wantHidden() && mgr && !scanned) loadMedia(); else refreshAll();
    }

    void toggle(LinearLayout card, String title, String sub, final String key, boolean def, final Runnable onChange) {
        LinearLayout row = new LinearLayout(this);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding(Ui.dp(this, 16), Ui.dp(this, 12), Ui.dp(this, 16), Ui.dp(this, 12));
        LinearLayout c = new LinearLayout(this);
        c.setOrientation(LinearLayout.VERTICAL);
        c.addView(Ui.text(this, title, 16, Ui.TXT, false));
        if (sub != null && !sub.isEmpty()) {
            TextView st = Ui.text(this, sub, 12, Ui.SUB, false);
            st.setSingleLine(false);
            c.addView(st);
        }
        row.addView(c, new LinearLayout.LayoutParams(0, Ui.WRAP, 1));
        Switch sw = new Switch(this);
        Ui.tintSwitch(sw);
        sw.setChecked(store.bool(key, def));
        sw.setOnCheckedChangeListener((b, on) -> {
            store.setBool(key, on);
            if (onChange != null) onChange.run();
            else if (!key.equals("bt_play") && !key.equals("pause_detach") && !key.equals("vol_pause")) refreshAll();
        });
        row.addView(sw);
        add(card, row);
    }

    void action(LinearLayout card, int icon, String label, Runnable r) {
        LinearLayout row = new LinearLayout(this);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding(Ui.dp(this, 16), Ui.dp(this, 14), Ui.dp(this, 16), Ui.dp(this, 14));
        row.setBackground(Ui.rip(0, 0, this));
        row.addView(Ui.icon(this, icon, Ui.ACC, 22));
        TextView t = Ui.text(this, label, 16, Ui.TXT, false);
        t.setPadding(Ui.dp(this, 16), 0, 0, 0);
        row.addView(t);
        row.setOnClickListener(v -> r.run());
        add(card, row);
    }
}
