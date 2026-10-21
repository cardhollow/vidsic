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
import org.json.JSONObject;
import android.graphics.SurfaceTexture;
import android.view.Surface;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Random;
import java.util.Set;

/** One engine for the whole process, so music keeps playing when the screen is closed. */
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
    int idx = -1, repeat = 0, vw, vh; // repeat: 0 off, 1 all, 2 one
    boolean audioMode, shuffle, prepared;
    boolean surfaceOn; // mp currently has the video surface
    final ArrayList<L> ls = new ArrayList<>();   // UI listeners
    final ArrayList<L> soft = new ArrayList<>(); // notification service
    final Random rnd = new Random();
    final Spectrum spec = new Spectrum(this);

    // playback speed: what the user picked (0.2x - 5x), times a temporary boost while the screen is held
    static final float MIN_SPEED = 0.2f, MAX_SPEED = 5f;
    float speed = 1f, boost = 1f;

    // Audio / video sync. MediaPlayer cannot shift sound against picture, so when an offset is set the picture
    // (plus its muted sound) keeps playing in `mp` and the sound comes from a second, audio-only player `ap`
    // that is kept `syncMs` ahead of (+) or behind (-) the picture.
    MediaPlayer ap;
    boolean apReady;
    int syncMs;
    float apTrim = 1f, duck = 1f;
    long lastAlign;

    // saved play queue: survives the app / process being closed
    final File qFile;
    boolean qDirty;
    int restPos; // position of the restored (not yet started) item
    boolean posTickOn;

    // notification / lock-screen artwork
    Bitmap art;
    String artKey = "";

    // audio focus + headset handling
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
        });

        // "Pause music when detach headphone" (wired unplug or bluetooth disconnect)
        try {
            c.registerReceiver(new BroadcastReceiver() {
                @Override public void onReceive(Context x, Intent i) {
                    if (store.bool("pause_detach", true)) pause();
                }
            }, new IntentFilter(AudioManager.ACTION_AUDIO_BECOMING_NOISY));
        } catch (Exception e) { }

        watchBluetooth();
        restoreQueue();
    }

    // ---------- saved queue ----------
    final Runnable saver = this::saveNow;

    void scheduleSave() {
        handler.removeCallbacks(saver);
        handler.postDelayed(saver, 800);
    }

    /** Position of the current item: live while a player exists, the restored one before it is started. */
    int savedPos() { return mp == null ? restPos : pos(); }

    void saveNow() {
        handler.removeCallbacks(saver);
        try {
            store.p.edit().putInt("q_idx", idx).putInt("q_pos", cur() == null ? 0 : savedPos())
                    .putBoolean("q_shuf", shuffle).putInt("q_rep", repeat).apply();
            if (!qDirty) return;
            qDirty = false;
            JSONArray a = new JSONArray();
            for (Media m : queue) a.put(m.toJson());
            final String out = a.toString();
            Ui.EX.execute(() -> {
                synchronized (qFile) {
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
                String t = store.renamed(m);
                if (t != null) m.title = t;
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
            // show it on the lock screen / mini player right away, paused, ready to resume
            handler.post(this::fire);
        } catch (Exception e) { }
    }

    final Runnable posTick = new Runnable() {
        @Override public void run() {
            if (isPlaying()) { saveNow(); handler.postDelayed(this, 10000); } else posTickOn = false;
        }
    };

    void startPosTick() {
        if (posTickOn) return;
        posTickOn = true;
        handler.postDelayed(posTick, 10000);
    }

    // ---------- listeners ----------
    void listen(L l) { if (!ls.contains(l)) ls.add(l); }
    void listenSoft(L l) { if (!soft.contains(l)) soft.add(l); }
    void unlistenSoft(L l) { soft.remove(l); }

    void fire() {
        scheduleSave();
        syncSession();
        spec.update();
        for (L l : new ArrayList<>(ls)) l.changed();
        for (L l : new ArrayList<>(soft)) l.changed();
    }

    Media cur() { return idx >= 0 && idx < queue.size() ? queue.get(idx) : null; }

    // ---------- media session (lock screen / notification / headset buttons) ----------
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
            });
        });
    }

    void ensureService() {
        if (PlaybackService.running) return;
        try { ctx.startForegroundService(new Intent(ctx, PlaybackService.class)); } catch (Exception e) { }
    }

    // ---------- audio focus ----------
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

    /** The sound comes from `ap` while there is one (and `mp`, the picture, is muted). */
    void applyVol() {
        try {
            if (ap != null) { if (mp != null) mp.setVolume(0f, 0f); ap.setVolume(duck, duck); }
            else if (mp != null) mp.setVolume(duck, duck);
        } catch (Exception e) { }
    }

    /** Player whose audio session carries the sound (what the spectrum listens to). */
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

    // ---------- "Auto-play via bluetooth" ----------
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
                    boolean fresh = false;
                    for (AudioDeviceInfo d : added) if (isBt(d) && btIds.add(d.getId())) fresh = true;
                    if (fresh && store.bool("bt_play", false)) {
                        // give the bluetooth route a moment to settle
                        handler.postDelayed(() -> { if (!isPlaying() && !queue.isEmpty()) resume(); }, 1500);
                    }
                }
                @Override public void onAudioDevicesRemoved(AudioDeviceInfo[] removed) {
                    for (AudioDeviceInfo d : removed) btIds.remove(d.getId());
                }
            }, handler);
        } catch (Exception e) { }
    }

    // ---------- playback ----------
    void play(List<Media> list, int i) {
        queue.clear();
        queue.addAll(list);
        qDirty = true;
        playAt(i);
    }

    void playAt(int i) { playAt(i, 0); }

    void playAt(int i, final int startPos) {
        if (i < 0 || i >= queue.size()) return;
        idx = i;
        restPos = 0;
        Media m = queue.get(i);
        free();
        vw = vh = 0;
        resumeOnFocus = false;
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
                if (startPos > 0) { try { x.seekTo(startPos); } catch (Exception e) { } }
                if (wantAp()) { try { x.setVolume(0f, 0f); } catch (Exception e) { } } // the sound will come from `ap`
                else applyVol();
                startP(x, rate());
                ensureService();
                startPosTick();
                applySync();
                fire();
            });
            p.setOnCompletionListener(x -> { if (x == mp) done(); });
            p.setOnErrorListener((x, a, b) -> { if (x == mp) { prepared = false; fire(); } return true; });
            p.prepareAsync();
        } catch (Exception e) { prepared = false; }
        if (startPos <= 0) store.addHistory(m);
        fire();
    }

    void free() {
        dropAp();
        spec.release();
        surfaceOn = false;
        if (mp != null) { try { mp.release(); } catch (Exception e) { } mp = null; }
        prepared = false;
    }

    void toggle() {
        if (isPlaying()) pause(); else resume();
    }

    void pause() {
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
        if (mp == null) {
            if (cur() != null) playAt(idx, restPos);
            else if (!queue.isEmpty()) playAt(0);
            return;
        }
        if (!prepared || isPlaying()) return;
        focus();
        startP(mp, rate());
        if (ap != null) alignAp(pos()); // both were paused at slightly different spots: line them up again
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
            try { mp.seekTo(ms); } catch (Exception e) { }
            if (ap != null) alignAp(ms);
            syncSession();
        } else if (mp == null && cur() != null) { // restored, not started yet: the next start begins here
            restPos = Math.max(0, ms);
            fire();
        }
    }

    // ---------- playback speed ----------
    /** Speed actually in effect (user's speed x temporary boost), clamped to what we offer. */
    float rate() { return Math.max(MIN_SPEED, Math.min(MAX_SPEED, speed * boost)); }

    static boolean setParams(MediaPlayer p, float r) {
        try {
            p.setPlaybackParams(new PlaybackParams().setSpeed(r).setPitch(1f));
            return true;
        } catch (Exception e) { return false; }
    }

    /**
     * Starts a prepared player at the current speed. For MediaPlayer, setPlaybackParams with a non-zero speed on a
     * prepared player IS start(), so this is also how the speed is carried over a pause / resume.
     */
    void startP(MediaPlayer p, float r) {
        boolean ok = setParams(p, r);
        try { if (!ok || !p.isPlaying()) p.start(); } catch (Exception e) { }
    }

    /** Applies speed changes to a player that is playing (on a paused one it would start it, so it waits for resume). */
    boolean applyRate() {
        if (!isPlaying()) return true;
        float r = rate();
        boolean ok = setParams(mp, r);
        if (ap != null && apReady) setParams(ap, r * apTrim);
        return ok;
    }

    /** Returns false when this device refused the speed (the old one is restored). */
    boolean setSpeed(float v) {
        float old = speed;
        speed = Math.max(MIN_SPEED, Math.min(MAX_SPEED, Math.round(v * 100f) / 100f));
        if (!applyRate()) {
            speed = old;
            applyRate();
            return false;
        }
        store.p.edit().putFloat("speed", speed).apply();
        syncSession(); // no fire(): that rebuilds every list, far too much while a slider is being dragged
        return true;
    }

    /** Temporary multiplier (press and hold on the player). Does not touch the saved speed. */
    void setBoost(float b) {
        boost = b;
        if (!applyRate()) { boost = 1f; applyRate(); }
        syncSession();
    }

    // ---------- audio / video sync ----------
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

    /** Makes the second player exist exactly when it is needed. */
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
            p.setDataSource(ctx, m.uri); // no surface: it only produces the sound
            p.setOnPreparedListener(x -> {
                if (x != ap) return;
                apReady = true;
                applyVol();
                alignAp(pos());
                fire(); // the spectrum now listens to this player
            });
            p.setOnCompletionListener(x -> { }); // the picture decides when the item is over
            p.setOnErrorListener((x, a, b) -> { if (x == ap) { dropAp(); fire(); } return true; });
            p.prepareAsync();
        } catch (Exception e) { dropAp(); return; }
        applyVol(); // picture muted from now on
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
        applyVol(); // the picture's own (in step) sound again
    }

    /** Puts the sound where it belongs for picture position `base`, and starts it if the picture is playing. */
    void alignAp(int base) {
        if (ap == null || !apReady || mp == null || !prepared) return;
        handler.removeCallbacks(apGo);
        lastAlign = SystemClock.uptimeMillis();
        apTrim = 1f;
        int t = base + syncMs;
        try {
            if (t < 0) { // sound has to wait for the picture
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

    /** Keeps the sound at exactly picture + syncMs: small drifts are eased out by running the sound a hair fast / slow. */
    final Runnable syncLoop = new Runnable() {
        @Override public void run() {
            if (ap == null) return;
            try {
                if (apReady && isPlaying() && ap.isPlaying()) {
                    int err = ap.getCurrentPosition() - (mp.getCurrentPosition() + syncMs); // + = sound is too far ahead
                    float trim = apTrim;
                    if (Math.abs(err) > 300) {
                        if (SystemClock.uptimeMillis() - lastAlign > 1500) { alignAp(pos()); trim = 1f; }
                    } else if (err > 12) trim = err > 60 ? 0.95f : 0.985f;
                    else if (err < -12) trim = err < -60 ? 1.05f : 1.015f;
                    else if (Math.abs(err) <= 5) trim = 1f;
                    if (trim != apTrim) { apTrim = trim; setParams(ap, rate() * trim); }
                }
            } catch (Exception e) { }
            handler.postDelayed(this, 250);
        }
    };

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
        playAt(i);
    }

    void prev() {
        if (queue.isEmpty()) return;
        if (pos() > 3000) { seek(0); return; }
        int i = idx - 1;
        if (i < 0) i = queue.size() - 1;
        playAt(i);
    }

    void done() {
        if (repeat == 2) { seek(0); startP(mp, rate()); if (ap != null) alignAp(0); return; }
        if (!store.bool("autonext", true)) { stopAtEnd(); return; }
        next(true);
    }

    void stopAtEnd() {
        handler.removeCallbacks(apGo);
        try { if (mp != null && prepared) { mp.pause(); mp.seekTo(0); } } catch (Exception e) { }
        try { if (ap != null) { ap.pause(); ap.seekTo(Math.max(0, syncMs)); } } catch (Exception e) { }
        fire();
    }

    void addToQueue(Media m) {
        qDirty = true;
        if (queue.isEmpty()) { queue.add(m); playAt(0); return; }
        queue.add(m);
        fire();
    }

    void addAllToQueue(List<Media> l) {
        if (l.isEmpty()) return;
        qDirty = true;
        boolean empty = queue.isEmpty();
        queue.addAll(l);
        if (empty) playAt(0); else fire();
    }

    void playNext(Media m) {
        qDirty = true;
        if (queue.isEmpty()) { queue.add(m); playAt(0); return; }
        queue.add(idx + 1, m);
        fire();
    }

    /** Drag & drop in the play queue. Does not restart anything; keeps idx pointing at the playing item. */
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

    /** Removes one queue entry. Removing the playing one moves on to the entry that took its place. */
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

    /** Empties the whole play queue and stops playback. */
    void clearQueue() {
        qDirty = true;
        free();
        queue.clear();
        idx = -1;
        fire();
    }

    /** Removes several queue entries (positions); the playing one last so playback only jumps once. */
    void removeMany(java.util.Collection<Integer> which) {
        java.util.ArrayList<Integer> l = new java.util.ArrayList<>(new java.util.TreeSet<>(which));
        boolean curSel = false;
        for (int k = l.size() - 1; k >= 0; k--) {
            int i = l.get(k);
            if (i == idx) { curSel = true; continue; }
            removeAt(i);
        }
        if (curSel) removeAt(idx);
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

    // ---------- video output ----------
    // The video is decoded into a Surface that belongs to the ENGINE and lives as long as the process does;
    // the player screen just shows it (TextureView). The MediaPlayer's display is therefore set once when a
    // video starts and never touched again when the screen closes / reopens. Changing the display of a
    // playing video makes Android flush the audio and re-seek the video, which is the pause / pulsing you
    // heard every time the app was left and entered again.
    SurfaceTexture vtex;
    Surface vsurf;

    Surface videoSurface() {
        if (vsurf == null) {
            vtex = new SurfaceTexture(false); // detached, so any TextureView can adopt it
            vsurf = new Surface(vtex);
        }
        return vsurf;
    }

    SurfaceTexture videoTexture() {
        videoSurface();
        return vtex;
    }
}
