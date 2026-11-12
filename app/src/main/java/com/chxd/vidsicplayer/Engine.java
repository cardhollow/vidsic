package com.chxd.vidsicplayer;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.graphics.Bitmap;
import android.media.AudioAttributes;
import android.media.AudioDeviceCallback;
import android.media.AudioDeviceInfo;
import android.media.AudioFocusRequest;
import android.media.AudioManager;
import android.media.MediaMetadata;
import android.media.MediaPlayer;
import android.media.PlaybackParams;
import android.media.session.MediaSession;
import android.media.session.PlaybackState;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.os.PowerManager;
import android.os.SystemClock;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import org.json.JSONArray;
import android.graphics.SurfaceTexture;
import android.view.Surface;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Random;
import java.util.Set;
import java.util.function.Consumer;

class Engine {
    interface L { void changed(); }

    private static Engine inst;

    static synchronized Engine get(Context c) {
        if (inst == null) {
            Context app = c.getApplicationContext();
            inst = new Engine(app, new Store(app));
        }
        return inst;
    }

    final Context ctx;
    final Store store;
    final AudioManager am;
    final MediaSession session;
    final Handler handler = new Handler(Looper.getMainLooper());
    MediaPlayer mp;
    final ArrayList<Media> queue = new ArrayList<>();
    int idx = -1, repeat = 0, vw, vh;
    boolean audioMode, shuffle, prepared;
    boolean surfaceOn;
    final ArrayList<L> ls = new ArrayList<>();
    final ArrayList<L> soft = new ArrayList<>();
    final Random rnd = new Random();
    final Spectrum spec = new Spectrum(this);

    static final float MIN_SPEED = 0.2f, MAX_SPEED = 5f;
    float speed = 1f, boost = 1f;

    MediaPlayer ap;
    boolean apReady;
    int syncMs;
    float apTrim = 1f, duck = 1f;
    long lastAlign;

    final File qFile;
    boolean qDirty;
    long qSeq, qWritten;
    int restPos;
    boolean posTickOn;

    Bitmap art;
    String artKey = "";

    AudioFocusRequest focusReq;
    boolean resumeOnFocus;
    final Set<Integer> btIds = new HashSet<>();

    Engine(Context c, Store s) {
        ctx = c;
        store = s;
        am = (AudioManager) c.getSystemService(Context.AUDIO_SERVICE);

        speed = Math.max(MIN_SPEED, Math.min(MAX_SPEED, s.p.getFloat("speed", 1f)));
        qFile = new File(c.getFilesDir(), "queue.json");

        session = new MediaSession(c, "VidsicPlayer");
        session.setCallback(new MediaSession.Callback() {
            @Override public void onPlay() { resume(); }
            @Override public void onPause() { pause(); }
            @Override public void onSkipToNext() { next(false); }
            @Override public void onSkipToPrevious() { prev(); }
            @Override public void onSeekTo(long p) { seek((int) p); }
            @Override public void onStop() { pause(); }
            @Override public void onCustomAction(String action, android.os.Bundle extras) {
                if (CA_SHUFFLE.equals(action)) { shuffle = !shuffle; fire(); }
                else if (CA_REPEAT.equals(action)) { repeat = (repeat + 1) % 3; fire(); }
            }
        });

        try {
            c.registerReceiver(new BroadcastReceiver() {
                @Override public void onReceive(Context x, Intent i) {
                    if (store.bool("pause_detach", true)) pause();
                }
            }, new IntentFilter(AudioManager.ACTION_AUDIO_BECOMING_NOISY));
        } catch (Exception e) { }

        watchVolume(c);
        refreshSaver();
        try {
            c.registerReceiver(new BroadcastReceiver() {
                @Override public void onReceive(Context x, Intent i) { powerChanged(); }
            }, new IntentFilter(PowerManager.ACTION_POWER_SAVE_MODE_CHANGED));
        } catch (Exception e) { }

        watchBluetooth();
        restoreQueue();
    }

    static volatile boolean SAVER;

    void refreshSaver() {
        boolean on = store.bool("batt_saver", false);
        if (!on && store.bool("batt_auto", false)) {
            try { on = ((PowerManager) ctx.getSystemService(Context.POWER_SERVICE)).isPowerSaveMode(); } catch (Exception e) { }
        }
        SAVER = on;
    }

    void powerChanged() {
        refreshSaver();
        spec.retry();
        spec.update();
        fire();
    }

    boolean lowPower() { return SAVER || !spec.uiVisible; }

    final Runnable saver = this::saveNow;

    void scheduleSave() {
        handler.removeCallbacks(saver);
        handler.postDelayed(saver, 800);
    }

    int savedPos() { return mp == null ? restPos : pos(); }

    void saveNow() {
        handler.removeCallbacks(saver);
        try {
            store.p.edit().putInt("q_idx", idx).putInt("q_pos", cur() == null ? 0 : savedPos())
                    .putBoolean("q_shuf", shuffle).putInt("q_rep", repeat).apply();
            if (!qDirty) return;
            qDirty = false;

            final ArrayList<Media> snap = new ArrayList<>(queue);
            final long seq = ++qSeq;
            Ui.EX.execute(() -> {
                final String out;
                try {
                    JSONArray a = new JSONArray();
                    for (Media m : snap) a.put(m.toJson());
                    out = a.toString();
                } catch (Exception e) { return; }
                synchronized (qFile) {
                    if (seq < qWritten) return;
                    qWritten = seq;
                    try {
                        File tmp = new File(qFile.getPath() + ".tmp");
                        try (FileOutputStream o = new FileOutputStream(tmp)) { o.write(out.getBytes("UTF-8")); }
                        if (!tmp.renameTo(qFile)) { qFile.delete(); tmp.renameTo(qFile); }
                    } catch (Exception e) { }
                }
            });
        } catch (Exception e) { }
    }

    void restoreQueue() {
        try {
            if (!qFile.isFile()) return;
            byte[] b = new byte[(int) qFile.length()];
            try (FileInputStream in = new FileInputStream(qFile)) {
                int n = 0, r;
                while (n < b.length && (r = in.read(b, n, b.length - n)) > 0) n += r;
            }
            JSONArray a = new JSONArray(new String(b, "UTF-8"));
            for (int i = 0; i < a.length(); i++) {
                Media m = Media.fromJson(a.getJSONObject(i));
                if (m == null) continue;
                store.applyMeta(m);
                queue.add(m);
            }
            if (queue.isEmpty()) return;
            idx = store.p.getInt("q_idx", 0);
            if (idx < 0 || idx >= queue.size()) idx = 0;
            restPos = Math.max(0, store.p.getInt("q_pos", 0));
            shuffle = store.p.getBoolean("q_shuf", false);
            repeat = store.p.getInt("q_rep", 0);
            if (repeat < 0 || repeat > 2) repeat = 0;
            syncMs = store.p.getInt("sync_" + cur().key(), 0);

            handler.post(this::fire);
        } catch (Exception e) { }
    }

    final Runnable posTick = new Runnable() {
        @Override public void run() {
            if (isPlaying()) { saveNow(); handler.postDelayed(this, lowPower() ? 30000 : 10000); } else posTickOn = false;
        }
    };

    void startPosTick() {
        if (posTickOn) return;
        posTickOn = true;
        handler.postDelayed(posTick, 10000);
    }

    void listen(L l) { if (!ls.contains(l)) ls.add(l); }
    void listenSoft(L l) { if (!soft.contains(l)) soft.add(l); }
    void unlistenSoft(L l) { soft.remove(l); }

    void fire() {
        scheduleSave();
        syncSession();
        spec.update();
        for (L l : new ArrayList<>(ls)) l.changed();
        for (L l : new ArrayList<>(soft)) l.changed();
        scheduleWidget();
    }

    final Runnable widgetUpd = this::updateWidgets;

    void updateWidgets() { Widgets.updatePlayers(ctx); }

    void scheduleWidget() {
        if (!store.bool("w_player_on", false)) return;
        handler.removeCallbacks(widgetUpd);
        handler.postDelayed(widgetUpd, 600);
    }

    static final String CA_SHUFFLE = "vidsic.shuffle", CA_REPEAT = "vidsic.repeat";

    Media cur() { return idx >= 0 && idx < queue.size() ? queue.get(idx) : null; }

    void syncSession() {
        try {
            Media m = cur();
            if (m == null) { if (session.isActive()) session.setActive(false); return; }
            if (!session.isActive()) session.setActive(true);
            if (!m.key().equals(artKey)) loadArt(m);
            MediaMetadata.Builder mb = new MediaMetadata.Builder()
                    .putString(MediaMetadata.METADATA_KEY_TITLE, m.title)
                    .putString(MediaMetadata.METADATA_KEY_ARTIST, m.artist)
                    .putString(MediaMetadata.METADATA_KEY_ALBUM, m.album)
                    .putLong(MediaMetadata.METADATA_KEY_DURATION, dur());
            if (art != null && m.key().equals(artKey)) mb.putBitmap(MediaMetadata.METADATA_KEY_ALBUM_ART, art);
            session.setMetadata(mb.build());
            int st = !prepared ? (mp == null ? PlaybackState.STATE_STOPPED : PlaybackState.STATE_BUFFERING)
                    : (isPlaying() ? PlaybackState.STATE_PLAYING : PlaybackState.STATE_PAUSED);
            session.setPlaybackState(new PlaybackState.Builder()
                    .addCustomAction(new PlaybackState.CustomAction.Builder(CA_SHUFFLE, shuffle ? "Shuffle on" : "Shuffle off",
                            shuffle ? R.drawable.st_shuffle_on : R.drawable.st_shuffle_off).build())
                    .addCustomAction(new PlaybackState.CustomAction.Builder(CA_REPEAT,
                            repeat == 0 ? "Loop off" : repeat == 1 ? "Loop all" : "Loop one",
                            repeat == 0 ? R.drawable.st_repeat_off : repeat == 1 ? R.drawable.st_repeat_on : R.drawable.st_repeat_one_on).build())
                    .setActions(PlaybackState.ACTION_PLAY | PlaybackState.ACTION_PAUSE | PlaybackState.ACTION_PLAY_PAUSE
                            | PlaybackState.ACTION_SKIP_TO_NEXT | PlaybackState.ACTION_SKIP_TO_PREVIOUS
                            | PlaybackState.ACTION_SEEK_TO | PlaybackState.ACTION_STOP)
                    .setState(st, pos(), isPlaying() ? rate() : 0f)
                    .build());
        } catch (Exception e) { }
    }

    void loadArt(final Media m) {
        final String k = m.key();
        artKey = k;
        art = null;
        Ui.EX.execute(() -> {
            Bitmap b = null;
            try { b = Ui.loadBitmap(ctx, m, 384); } catch (Exception e) { }
            final Bitmap bm = b;
            handler.post(() -> {
                if (bm == null || !k.equals(artKey)) return;
                art = bm;
                syncSession();
                for (L l : new ArrayList<>(soft)) l.changed();
                scheduleWidget();
            });
        });
    }

    void ensureService() {
        if (PlaybackService.running) return;
        try { ctx.startForegroundService(new Intent(ctx, PlaybackService.class)); } catch (Exception e) { }
    }

    final AudioManager.OnAudioFocusChangeListener focusL = ch -> {
        switch (ch) {
            case AudioManager.AUDIOFOCUS_LOSS:
                resumeOnFocus = false;
                doPause();
                break;
            case AudioManager.AUDIOFOCUS_LOSS_TRANSIENT:
                if (isPlaying()) { resumeOnFocus = true; doPause(); }
                break;
            case AudioManager.AUDIOFOCUS_LOSS_TRANSIENT_CAN_DUCK:
                vol(0.25f);
                break;
            case AudioManager.AUDIOFOCUS_GAIN:
                vol(1f);
                if (resumeOnFocus) { resumeOnFocus = false; resume(); }
                break;
            default:
                break;
        }
    };

    void vol(float v) { duck = v; applyVol(); }

    void applyVol() {
        try {
            if (ap != null) { if (mp != null) mp.setVolume(0f, 0f); ap.setVolume(duck, duck); }
            else if (mp != null) mp.setVolume(duck, duck);
        } catch (Exception e) { }
    }

    MediaPlayer sound() { return ap != null && apReady ? ap : mp; }

    void focus() {
        try {
            if (focusReq == null) {
                focusReq = new AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN)
                        .setAudioAttributes(new AudioAttributes.Builder()
                                .setUsage(AudioAttributes.USAGE_MEDIA)
                                .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC).build())
                        .setOnAudioFocusChangeListener(focusL, handler)
                        .build();
            }
            am.requestAudioFocus(focusReq);
        } catch (Exception e) { }
    }

    static boolean isBt(AudioDeviceInfo d) {
        int t = d.getType();
        return t == AudioDeviceInfo.TYPE_BLUETOOTH_A2DP
                || (Build.VERSION.SDK_INT >= 31 && t == AudioDeviceInfo.TYPE_BLE_HEADSET);
    }

    void watchBluetooth() {
        try {
            for (AudioDeviceInfo d : am.getDevices(AudioManager.GET_DEVICES_OUTPUTS)) if (isBt(d)) btIds.add(d.getId());
            am.registerAudioDeviceCallback(new AudioDeviceCallback() {
                @Override public void onAudioDevicesAdded(AudioDeviceInfo[] added) {
                    recheckRate();
                    boolean fresh = false;
                    for (AudioDeviceInfo d : added) if (isBt(d) && btIds.add(d.getId())) fresh = true;
                    if (fresh && store.bool("bt_play", false) && !SAVER) {

                        handler.postDelayed(() -> { if (!isPlaying() && !queue.isEmpty()) resume(); }, 1500);
                    }
                }
                @Override public void onAudioDevicesRemoved(AudioDeviceInfo[] removed) {
                    recheckRate();
                    for (AudioDeviceInfo d : removed) btIds.remove(d.getId());
                }
            }, handler);
        } catch (Exception e) { }
    }

    void play(List<Media> list, int i) {
        queue.clear();
        queue.addAll(list);
        qDirty = true;
        playAt(i);
    }

    void playAt(int i) { playAt(i, 0, true); }

    void playAt(int i, int startPos) { playAt(i, startPos, startPos <= 0); }

    void playAt(int i, final int startPos, boolean history) {
        if (i < 0 || i >= queue.size()) return;
        idx = i;
        restPos = 0;
        Media m = queue.get(i);
        free();
        vw = vh = 0;
        resumeOnFocus = false;
        if (history) rateRebuilt = false;
        syncMs = store.p.getInt("sync_" + m.key(), 0);
        final MediaPlayer p = new MediaPlayer();
        mp = p;
        try {
            p.setAudioAttributes(new AudioAttributes.Builder()
                    .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                    .setUsage(AudioAttributes.USAGE_MEDIA).build());
            p.setWakeMode(ctx, PowerManager.PARTIAL_WAKE_LOCK);
            p.setDataSource(ctx, m.uri);
            if (m.video && !audioMode) { p.setSurface(videoSurface()); surfaceOn = true; }
            p.setOnVideoSizeChangedListener((x, w, h) -> { if (x == mp) { vw = w; vh = h; fire(); } });
            p.setOnPreparedListener(x -> {
                if (x != mp) return;
                prepared = true;
                focus();
                if (wantAp()) { try { x.setVolume(0f, 0f); } catch (Exception e) { } }
                else applyVol();

                if (startPos > 0) seekThen(startPos, () -> { if (x == mp) begin(x); });
                else begin(x);
            });
            p.setOnSeekCompleteListener(x -> { if (x == mp) seeked(); });
            p.setOnCompletionListener(x -> { if (x == mp) done(); });
            p.setOnErrorListener((x, a, b) -> { if (x == mp) { prepared = false; fire(); } return true; });
            p.prepareAsync();
        } catch (Exception e) { prepared = false; }
        if (history) store.addHistory(m);
        fire();
    }

    void begin(MediaPlayer x) {
        startP(x, rate());
        ensureService();
        startPosTick();
        applySync();
        fire();
    }

    void free() {
        dropAp();
        spec.release();
        surfaceOn = false;
        handler.removeCallbacks(seekGuard);
        handler.removeCallbacks(rateFix);
        afterSeek = null;
        rateTry = 0;
        if (mp != null) { try { mp.release(); } catch (Exception e) { } mp = null; }
        prepared = false;
    }

    Runnable afterSeek;
    final Runnable seekGuard = this::runAfterSeek;

    void seekThen(int ms, Runnable then) {
        handler.removeCallbacks(seekGuard);
        afterSeek = then;
        try { mp.seekTo(ms, MediaPlayer.SEEK_CLOSEST); } catch (Exception e) { runAfterSeek(); return; }
        handler.postDelayed(seekGuard, 1500);
    }

    void runAfterSeek() {
        handler.removeCallbacks(seekGuard);
        Runnable r = afterSeek;
        afterSeek = null;
        if (r != null) r.run();
    }

    void seeked() {
        if (afterSeek != null) { runAfterSeek(); return; }

        if (isPlaying() && rate() != 1f && !applyRate()) keepRate();
    }

    void toggle() {
        if (isPlaying()) pause(); else resume();
    }

    /** True only while playback was paused by the "Volume auto pause" setting. */
    private boolean volPaused;

    private void watchVolume(Context c) {
        try {
            c.registerReceiver(new BroadcastReceiver() {
                @Override public void onReceive(Context x, Intent i) {
                    if (i.getIntExtra("android.media.EXTRA_VOLUME_STREAM_TYPE", 3) == AudioManager.STREAM_MUSIC) volumeChanged();
                }
            }, new IntentFilter("android.media.VOLUME_CHANGED_ACTION"));
        } catch (Exception e) { }
        try {
            c.getContentResolver().registerContentObserver(android.provider.Settings.System.CONTENT_URI, true,
                    new android.database.ContentObserver(handler) {
                        @Override public void onChange(boolean selfChange) { volumeChanged(); }
                    });
        } catch (Exception e) { }
    }

    void volumeChanged() {
        if (!store.bool("vol_pause", false)) { volPaused = false; return; }
        int v;
        try { v = am.getStreamVolume(AudioManager.STREAM_MUSIC); } catch (Exception e) { return; }
        if (v <= 0) {
            if (isPlaying()) { pause(); volPaused = true; }
        } else if (volPaused) {
            volPaused = false;
            resume();
        }
    }

    void pause() {
        volPaused = false;
        resumeOnFocus = false;
        doPause();
    }

    void doPause() {
        if (!isPlaying()) return;
        handler.removeCallbacks(apGo);
        try { mp.pause(); } catch (Exception e) { }
        try { if (ap != null) ap.pause(); } catch (Exception e) { }
        fire();
        saveNow();
    }

    void resume() {
        volPaused = false;
        if (mp == null) {
            if (cur() != null) playAt(idx, restPos);
            else if (!queue.isEmpty()) playAt(0);
            return;
        }
        if (!prepared || isPlaying()) return;
        focus();
        startP(mp, rate());
        if (ap != null) alignAp(pos());
        ensureService();
        startPosTick();
        fire();
    }

    boolean isPlaying() { try { return prepared && mp != null && mp.isPlaying(); } catch (Exception e) { return false; } }
    int pos() {
        try { if (prepared && mp != null) return mp.getCurrentPosition(); } catch (Exception e) { }
        return mp == null ? restPos : 0;
    }
    int dur() {
        try { if (prepared && mp != null) return mp.getDuration(); } catch (Exception e) { }
        return cur() != null ? (int) cur().duration : 0;
    }
    void seek(int ms) {
        if (prepared && mp != null) {
            try { mp.seekTo(Math.max(0, ms), MediaPlayer.SEEK_CLOSEST); } catch (Exception e) { }
            if (ap != null) alignAp(ms);
            syncSession();
        } else if (mp == null && cur() != null) {
            restPos = Math.max(0, ms);
            fire();
        }
    }

    float rate() { return Math.max(MIN_SPEED, Math.min(MAX_SPEED, speed * boost)); }

    static boolean setParams(MediaPlayer p, float r) {
        try {
            p.setPlaybackParams(new PlaybackParams().setSpeed(r).setPitch(1f));
            return true;
        } catch (Exception e) { return false; }
    }

    void startP(MediaPlayer p, float r) {
        boolean ok = setParams(p, r);
        try { if (!ok || !p.isPlaying()) p.start(); } catch (Exception e) { }
        if (ok) { if (p == mp) lastGoodSpeed = speed; }
        else if (r != 1f) keepRate();
    }

    boolean applyRate() {
        if (!isPlaying()) return true;
        float r = rate();
        boolean ok = setParams(mp, r);
        if (ap != null && apReady && !setParams(ap, r * apTrim)) ok = false;
        return ok;
    }

    float lastGoodSpeed = 1f;
    int rateTry;
    boolean rateRebuilt;
    Consumer<Float> onRateRefused;

    final Runnable rateFix = new Runnable() {
        @Override public void run() {
            if (!isPlaying()) { rateTry = 0; return; }
            if (applyRate()) { rateTry = 0; lastGoodSpeed = speed; return; }
            if (++rateTry <= 4) { handler.postDelayed(this, 120L << rateTry); return; }
            rateTry = 0;
            if (rate() == 1f) return;
            if (!rateRebuilt && cur() != null) {
                rateRebuilt = true;
                playAt(idx, pos(), false);
                return;
            }
            float bad = rate();
            if (boost != 1f) boost = 1f; else speed = lastGoodSpeed;
            store.p.edit().putFloat("speed", speed).apply();
            applyRate();
            fire();
            if (onRateRefused != null) onRateRefused.accept(bad);
        }
    };

    void reloadSettings() {
        speed = Math.max(MIN_SPEED, Math.min(MAX_SPEED, store.p.getFloat("speed", 1f)));
        lastGoodSpeed = speed;
        if (isPlaying() && !applyRate()) keepRate();
        spec.update();
        fire();
    }

    void recheckRate() {
        if (rate() != 1f && isPlaying()) { handler.removeCallbacks(rateFix); handler.postDelayed(rateFix, 500); rateTry = 0; }
    }

    void keepRate() {
        rateTry = 0;
        handler.removeCallbacks(rateFix);
        handler.postDelayed(rateFix, 150);
    }

    void setSpeed(float v) {
        speed = Math.max(MIN_SPEED, Math.min(MAX_SPEED, Math.round(v * 100f) / 100f));
        rateRebuilt = false;
        store.p.edit().putFloat("speed", speed).apply();
        if (applyRate()) { handler.removeCallbacks(rateFix); rateTry = 0; if (isPlaying()) lastGoodSpeed = speed; }
        else keepRate();
        syncSession();
    }

    void setBoost(float b) {
        boost = b;
        rateRebuilt = false;
        if (applyRate()) { handler.removeCallbacks(rateFix); rateTry = 0; }
        else keepRate();
        syncSession();
    }

    boolean wantAp() {
        Media m = cur();
        return m != null && m.video && !audioMode && syncMs != 0 && mp != null;
    }

    void setSync(int ms) {
        syncMs = Math.max(-1000, Math.min(1000, ms));
        Media m = cur();
        if (m != null) {
            if (syncMs == 0) store.p.edit().remove("sync_" + m.key()).apply();
            else store.p.edit().putInt("sync_" + m.key(), syncMs).apply();
        }
        applySync();
    }

    void applySync() {
        if (!prepared || mp == null) return;
        if (wantAp()) { if (ap == null) makeAp(); }
        else if (ap != null) dropAp();
    }

    final Runnable apGo = () -> {
        if (ap != null && apReady && isPlaying()) startP(ap, rate() * apTrim);
    };

    void makeAp() {
        Media m = cur();
        if (m == null) return;
        final MediaPlayer p = new MediaPlayer();
        ap = p;
        apReady = false;
        apTrim = 1f;
        try {
            p.setAudioAttributes(new AudioAttributes.Builder()
                    .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                    .setUsage(AudioAttributes.USAGE_MEDIA).build());
            p.setWakeMode(ctx, PowerManager.PARTIAL_WAKE_LOCK);
            p.setDataSource(ctx, m.uri);
            p.setOnPreparedListener(x -> {
                if (x != ap) return;
                apReady = true;
                applyVol();
                alignAp(pos());
                fire();
            });
            p.setOnSeekCompleteListener(x -> {
                if (x == ap && apReady && isPlaying() && rate() * apTrim != 1f && !setParams(x, rate() * apTrim)) keepRate();
            });
            p.setOnCompletionListener(x -> { });
            p.setOnErrorListener((x, a, b) -> { if (x == ap) { dropAp(); fire(); } return true; });
            p.prepareAsync();
        } catch (Exception e) { dropAp(); return; }
        applyVol();
        handler.removeCallbacks(syncLoop);
        handler.postDelayed(syncLoop, 250);
    }

    void dropAp() {
        handler.removeCallbacks(apGo);
        handler.removeCallbacks(syncLoop);
        MediaPlayer p = ap;
        ap = null;
        apReady = false;
        apTrim = 1f;
        if (p != null) { try { p.release(); } catch (Exception e) { } }
        applyVol();
    }

    void alignAp(int base) {
        if (ap == null || !apReady || mp == null || !prepared) return;
        handler.removeCallbacks(apGo);
        lastAlign = SystemClock.uptimeMillis();
        apTrim = 1f;
        int t = base + syncMs;
        try {
            if (t < 0) {
                ap.seekTo(0);
                if (ap.isPlaying()) ap.pause();
                if (isPlaying()) handler.postDelayed(apGo, (long) (-t / rate()));
            } else {
                int d = ap.getDuration();
                ap.seekTo(d > 0 ? Math.min(t, d - 1) : t, MediaPlayer.SEEK_CLOSEST);
                if (isPlaying()) startP(ap, rate());
            }
        } catch (Exception e) { }
    }

    final Runnable syncLoop = new Runnable() {
        @Override public void run() {
            if (ap == null) return;
            try {
                if (apReady && isPlaying() && ap.isPlaying()) {
                    int err = ap.getCurrentPosition() - (mp.getCurrentPosition() + syncMs);
                    float trim = apTrim;
                    if (Math.abs(err) > 300) {
                        if (SystemClock.uptimeMillis() - lastAlign > 1500) { alignAp(pos()); trim = 1f; }
                    } else if (err > 12) trim = err > 60 ? 0.95f : 0.985f;
                    else if (err < -12) trim = err < -60 ? 1.05f : 1.015f;
                    else if (Math.abs(err) <= 5) trim = 1f;
                    if (trim != apTrim) { apTrim = trim; setParams(ap, rate() * trim); }
                }
            } catch (Exception e) { }
            handler.postDelayed(this, lowPower() ? 1000 : 250);
        }
    };

    final java.util.ArrayDeque<Integer> backStack = new java.util.ArrayDeque<>();

    void next(boolean auto) {
        if (queue.isEmpty()) return;
        int n = queue.size(), i;
        if (shuffle && n > 1) {
            do { i = rnd.nextInt(n); } while (i == idx);
        } else {
            i = idx + 1;
            if (i >= n) {
                if (repeat == 1 || !auto) i = 0; else { stopAtEnd(); return; }
            }
        }
        if (idx >= 0) { backStack.push(idx); while (backStack.size() > 100) backStack.removeLast(); }
        playAt(i);
    }

    void prev() {
        if (queue.isEmpty()) return;
        int i = -1;
        while (!backStack.isEmpty()) {
            int b = backStack.pop();
            if (b >= 0 && b < queue.size() && b != idx) { i = b; break; }
        }
        if (i < 0) {
            if (shuffle && queue.size() > 1) {
                do { i = rnd.nextInt(queue.size()); } while (i == idx);
            } else {
                i = idx - 1;
                if (i < 0) i = queue.size() - 1;
            }
        }
        playAt(i);
    }

    void done() {
        if (repeat == 2) {
            seekThen(0, () -> { if (mp != null && prepared) { startP(mp, rate()); if (ap != null) alignAp(0); } });
            return;
        }
        if (!store.bool("autonext", true)) { stopAtEnd(); return; }
        next(true);
    }

    void stopAtEnd() {
        handler.removeCallbacks(apGo);
        try { if (mp != null && prepared) { mp.pause(); mp.seekTo(0); } } catch (Exception e) { }
        try { if (ap != null) { ap.pause(); ap.seekTo(Math.max(0, syncMs)); } } catch (Exception e) { }
        fire();
    }

    boolean dupOk() { return store.bool("q_dup", false); }

    boolean inQueue(Media m) {
        String k = m.key();
        for (Media q : queue) if (q.key().equals(k)) return true;
        return false;
    }

    boolean addToQueue(Media m) {
        if (!dupOk() && inQueue(m)) return false;
        qDirty = true;
        if (queue.isEmpty()) { queue.add(m); playAt(0); return true; }
        queue.add(m);
        fire();
        return true;
    }

    int addAllToQueue(List<Media> l) {
        List<Media> add = l;
        if (!dupOk()) {
            Set<String> have = new HashSet<>();
            for (Media q : queue) have.add(q.key());
            add = new ArrayList<>();
            for (Media m : l) if (have.add(m.key())) add.add(m);
        }
        if (add.isEmpty()) return 0;
        qDirty = true;
        boolean empty = queue.isEmpty();
        queue.addAll(add);
        if (empty) playAt(0); else fire();
        return add.size();
    }

    static final int NEXT_ADDED = 0, NEXT_MOVED = 1, NEXT_SAME = 2;

    int playNext(Media m) {
        if (queue.isEmpty()) { qDirty = true; queue.add(m); playAt(0); return NEXT_ADDED; }
        if (!dupOk()) {
            String k = m.key();
            if (cur() != null && cur().key().equals(k)) return NEXT_SAME;
            int j = -1;
            for (int i = 0; i < queue.size(); i++) if (queue.get(i).key().equals(k)) { j = i; break; }
            if (j >= 0) {
                if (j != idx + 1) {
                    qDirty = true;
                    Media q = queue.remove(j);
                    if (j < idx) idx--;
                    queue.add(idx + 1, q);
                    fire();
                }
                return NEXT_MOVED;
            }
        }
        qDirty = true;
        queue.add(Math.min(queue.size(), idx + 1), m);
        fire();
        return NEXT_ADDED;
    }

    void moveQueue(int from, int to) {
        if (from == to || from < 0 || to < 0 || from >= queue.size() || to >= queue.size()) return;
        qDirty = true;
        scheduleSave();
        Media m = queue.remove(from);
        queue.add(to, m);
        if (idx == from) idx = to;
        else if (from < idx && to >= idx) idx--;
        else if (from > idx && to <= idx) idx++;
    }

    void removeAt(int i) {
        if (i < 0 || i >= queue.size()) return;
        qDirty = true;
        if (i != idx) {
            queue.remove(i);
            if (i < idx) idx--;
            fire();
            return;
        }
        queue.remove(i);
        if (queue.isEmpty()) { free(); idx = -1; fire(); return; }
        playAt(i < queue.size() ? i : 0);
    }

    void clearQueue() {
        qDirty = true;
        free();
        queue.clear();
        idx = -1;
        fire();
    }

    void removeMany(java.util.Collection<Integer> which) {
        java.util.BitSet gone = new java.util.BitSet();
        for (int i : which) if (i >= 0 && i < queue.size()) gone.set(i);
        if (gone.isEmpty()) return;
        qDirty = true;
        boolean curGone = idx >= 0 && gone.get(idx);
        ArrayList<Media> keep = new ArrayList<>(Math.max(0, queue.size() - gone.cardinality()));
        int newIdx = -1, firstAfter = -1;
        for (int i = 0; i < queue.size(); i++) {
            if (gone.get(i)) continue;
            if (i == idx) newIdx = keep.size();
            else if (i > idx && firstAfter < 0) firstAfter = keep.size();
            keep.add(queue.get(i));
        }
        queue.clear();
        queue.addAll(keep);
        if (queue.isEmpty()) { free(); idx = -1; fire(); return; }
        if (!curGone) { idx = newIdx; fire(); return; }
        playAt(firstAfter >= 0 ? firstAfter : 0);
    }

    void remove(String key) {
        qDirty = true;
        for (int i = queue.size() - 1; i >= 0; i--) {
            if (!queue.get(i).key().equals(key)) continue;
            if (i == idx) { free(); queue.remove(i); idx = -1; }
            else { queue.remove(i); if (i < idx) idx--; }
        }
        fire();
    }

    void setAudioMode(boolean b) {
        audioMode = b;
        try {
            if (mp != null) {
                Media m = cur();
                boolean show = m != null && m.video && !b;
                if (show != surfaceOn) { mp.setSurface(show ? videoSurface() : null); surfaceOn = show; }
            }
        } catch (Exception e) { }
        applySync();
        fire();
    }

    SurfaceTexture vtex;
    Surface vsurf;

    Surface videoSurface() {
        if (vsurf == null) {
            vtex = new SurfaceTexture(false);
            vsurf = new Surface(vtex);
        }
        return vsurf;
    }

    SurfaceTexture videoTexture() {
        videoSurface();
        return vtex;
    }
}
