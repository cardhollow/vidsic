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
import android.media.session.MediaSession;
import android.media.session.PlaybackState;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.os.PowerManager;
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
    }

    // ---------- listeners ----------
    void listen(L l) { if (!ls.contains(l)) ls.add(l); }
    void listenSoft(L l) { if (!soft.contains(l)) soft.add(l); }
    void unlistenSoft(L l) { soft.remove(l); }

    void fire() {
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
                    .setState(st, pos(), isPlaying() ? 1f : 0f)
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

    void vol(float v) { try { if (mp != null) mp.setVolume(v, v); } catch (Exception e) { } }

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
        playAt(i);
    }

    void playAt(int i) {
        if (i < 0 || i >= queue.size()) return;
        idx = i;
        Media m = queue.get(i);
        free();
        vw = vh = 0;
        resumeOnFocus = false;
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
                if (x == mp) { prepared = true; focus(); x.start(); ensureService(); fire(); }
            });
            p.setOnCompletionListener(x -> { if (x == mp) done(); });
            p.setOnErrorListener((x, a, b) -> { if (x == mp) { prepared = false; fire(); } return true; });
            p.prepareAsync();
        } catch (Exception e) { prepared = false; }
        store.addHistory(m);
        fire();
    }

    void free() {
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
        try { mp.pause(); } catch (Exception e) { }
        fire();
    }

    void resume() {
        if (mp == null) {
            if (cur() != null) playAt(idx);
            else if (!queue.isEmpty()) playAt(0);
            return;
        }
        if (!prepared || isPlaying()) return;
        focus();
        try { mp.start(); } catch (Exception e) { }
        ensureService();
        fire();
    }

    boolean isPlaying() { try { return prepared && mp != null && mp.isPlaying(); } catch (Exception e) { return false; } }
    int pos() { try { return prepared && mp != null ? mp.getCurrentPosition() : 0; } catch (Exception e) { return 0; } }
    int dur() {
        try { if (prepared && mp != null) return mp.getDuration(); } catch (Exception e) { }
        return cur() != null ? (int) cur().duration : 0;
    }
    void seek(int ms) {
        if (prepared && mp != null) {
            try { mp.seekTo(ms); } catch (Exception e) { }
            syncSession();
        }
    }

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
        if (repeat == 2) { seek(0); try { mp.start(); } catch (Exception e) { } return; }
        if (!store.bool("autonext", true)) { stopAtEnd(); return; }
        next(true);
    }

    void stopAtEnd() {
        try { if (mp != null && prepared) { mp.pause(); mp.seekTo(0); } } catch (Exception e) { }
        fire();
    }

    void addToQueue(Media m) {
        if (queue.isEmpty()) { queue.add(m); playAt(0); return; }
        queue.add(m);
        fire();
    }

    void addAllToQueue(List<Media> l) {
        if (l.isEmpty()) return;
        boolean empty = queue.isEmpty();
        queue.addAll(l);
        if (empty) playAt(0); else fire();
    }

    void playNext(Media m) {
        if (queue.isEmpty()) { queue.add(m); playAt(0); return; }
        queue.add(idx + 1, m);
        fire();
    }

    /** Drag & drop in the play queue. Does not restart anything; keeps idx pointing at the playing item. */
    void moveQueue(int from, int to) {
        if (from == to || from < 0 || to < 0 || from >= queue.size() || to >= queue.size()) return;
        Media m = queue.remove(from);
        queue.add(to, m);
        if (idx == from) idx = to;
        else if (from < idx && to >= idx) idx--;
        else if (from > idx && to <= idx) idx++;
    }

    /** Removes one queue entry. Removing the playing one moves on to the entry that took its place. */
    void removeAt(int i) {
        if (i < 0 || i >= queue.size()) return;
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

    void remove(String key) {
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
