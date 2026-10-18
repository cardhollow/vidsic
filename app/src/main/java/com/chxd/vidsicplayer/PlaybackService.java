package com.chxd.vidsicplayer;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Intent;
import android.content.pm.ServiceInfo;
import android.graphics.drawable.Icon;
import android.os.IBinder;

/**
 * Keeps playback alive in the background and shows the system media notification
 * (also what the lock screen and the quick-settings media player use).
 */
public class PlaybackService extends Service implements Engine.L {
    static final String CH = "vidsic_playback";
    static final String A_PREV = "com.chxd.vidsicplayer.PREV", A_TOGGLE = "com.chxd.vidsicplayer.TOGGLE",
            A_NEXT = "com.chxd.vidsicplayer.NEXT", A_DISMISS = "com.chxd.vidsicplayer.DISMISS";
    static final int ID = 4711;
    static boolean running;

    Engine en;
    NotificationManager nm;
    boolean fg;

    @Override public IBinder onBind(Intent i) { return null; }

    @Override
    public void onCreate() {
        super.onCreate();
        running = true;
        en = Engine.get(this);
        nm = (NotificationManager) getSystemService(NOTIFICATION_SERVICE);
        NotificationChannel c = new NotificationChannel(CH, "Playback", NotificationManager.IMPORTANCE_LOW);
        c.setShowBadge(false);
        c.setLockscreenVisibility(Notification.VISIBILITY_PUBLIC);
        nm.createNotificationChannel(c);
        en.listenSoft(this);
    }

    @Override
    public int onStartCommand(Intent in, int flags, int startId) {
        String a = in != null ? in.getAction() : null;
        if (A_PREV.equals(a)) en.prev();
        else if (A_NEXT.equals(a)) en.next(false);
        else if (A_TOGGLE.equals(a)) en.toggle();
        else if (A_DISMISS.equals(a) && !en.isPlaying()) { quit(); return START_NOT_STICKY; }
        update(true);
        return START_NOT_STICKY;
    }

    @Override
    public void onDestroy() {
        running = false;
        en.unlistenSoft(this);
        super.onDestroy();
    }

    @Override public void changed() { update(false); }

    void quit() {
        stopForeground(STOP_FOREGROUND_REMOVE);
        fg = false;
        try { nm.cancel(ID); } catch (Exception e) { }
        stopSelf();
    }

    void goForeground(Notification n) {
        try {
            startForeground(ID, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK);
            fg = true;
        } catch (Exception e) { // not allowed from the background right now: still show it
            try { nm.notify(ID, n); } catch (Exception e2) { }
        }
    }

    void update(boolean start) {
        Media m = en.cur();
        if (m == null) {
            if (start && !fg) goForeground(build(null, false)); // a started foreground service must always call startForeground
            quit();
            return;
        }
        boolean playing = en.isPlaying();
        Notification n = build(m, playing);
        if (playing || (start && !fg)) {
            if (!fg) goForeground(n); else nm.notify(ID, n);
        } else {
            if (fg) { stopForeground(STOP_FOREGROUND_DETACH); fg = false; }
            nm.notify(ID, n);
        }
    }

    PendingIntent act(String a, int rc) {
        return PendingIntent.getService(this, rc, new Intent(this, PlaybackService.class).setAction(a),
                PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);
    }

    Notification build(Media m, boolean playing) {
        Intent open = new Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
                .setClass(this, MainActivity.class)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED);
        Notification.Builder b = new Notification.Builder(this, CH)
                .setSmallIcon(R.drawable.ic_music)
                .setContentTitle(m == null ? "Vidsic Player" : m.title)
                .setContentText(m == null ? "" : m.artist)
                .setContentIntent(PendingIntent.getActivity(this, 0, open, PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT))
                .setDeleteIntent(act(A_DISMISS, 9))
                .setCategory(Notification.CATEGORY_TRANSPORT)
                .setVisibility(Notification.VISIBILITY_PUBLIC)
                .setOnlyAlertOnce(true)
                .setShowWhen(false)
                .setOngoing(playing);
        if (m != null && en.art != null && m.key().equals(en.artKey)) b.setLargeIcon(en.art);
        b.addAction(new Notification.Action.Builder(Icon.createWithResource(this, R.drawable.ic_prev), "Previous", act(A_PREV, 1)).build());
        b.addAction(new Notification.Action.Builder(Icon.createWithResource(this, playing ? R.drawable.ic_pause : R.drawable.ic_play),
                playing ? "Pause" : "Play", act(A_TOGGLE, 2)).build());
        b.addAction(new Notification.Action.Builder(Icon.createWithResource(this, R.drawable.ic_next), "Next", act(A_NEXT, 3)).build());
        b.setStyle(new Notification.MediaStyle()
                .setMediaSession(en.session.getSessionToken())
                .setShowActionsInCompactView(0, 1, 2));
        return b.build();
    }
}
