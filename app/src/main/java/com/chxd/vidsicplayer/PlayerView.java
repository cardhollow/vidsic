package com.chxd.vidsicplayer;

import android.content.Context;
import android.content.res.ColorStateList;
import android.graphics.Color;
import android.graphics.drawable.GradientDrawable;
import android.os.SystemClock;
import android.view.GestureDetector;
import android.view.Gravity;
import android.view.HapticFeedbackConstants;
import android.graphics.SurfaceTexture;
import android.view.MotionEvent;
import android.view.TextureView;
import android.view.View;
import android.view.WindowInsets;
import android.view.WindowInsetsController;
import android.view.WindowManager;
import java.util.Locale;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.SeekBar;
import android.widget.TextView;

/**
 * Full-screen player overlay.
 * Tap the screen to hide / show the controls. The lock button hides them and ignores taps
 * until the lock icon is tapped (tap the screen to show / hide that icon).
 * Double-tap the left / right half: back / forward 5 s. Press and hold: 2x speed while held (controls hidden).
 * Moon button: sleep screen (black, lowest brightness, touches ignored until a double-tap).
 * Bottom row: speed, captions (CC) and audio / video sync (A/V).
 */
class PlayerView extends FrameLayout implements Engine.L {
    final MainActivity a;
    final Engine en;
    final ImageView play, shuf, rep, fav;
    final TextView title, sub, cur, max;
    final SeekBar sb;
    final TextureView sv;
    final FrameLayout area, artBox;
    final LinearLayout top, bottom;
    final ImageView unlock;
    final TextView speedChip, ccChip, syncChip, capView, holdTag, fbL, fbR, sleepHint;
    final FrameLayout sleepLayer;
    final GestureDetector gd, sleepGd;
    boolean drag, controls = true, locked, lockIconShown, holding, prevControls, sleeping, capRun;
    long skipAt;
    int skipTo, skipSum;
    Captions caps;
    String capKey = "", capShown;
    String artKey = "";
    View spectrum; // "Audio Player Spectrum": replaces the thumbnail / note

    PlayerView(MainActivity act) {
        super(act);
        a = act;
        en = act.engine;
        setBackgroundColor(Ui.BG);
        setClickable(true);
        en.listen(this);

        gd = new GestureDetector(a, new GestureDetector.SimpleOnGestureListener() {
            @Override public boolean onDown(MotionEvent e) { return true; }
            @Override public boolean onSingleTapConfirmed(MotionEvent e) { onTap(); return true; }
            @Override public boolean onDoubleTap(MotionEvent e) {
                if (locked) { onTap(); return true; }
                skip(e.getX() >= getWidth() / 2f);
                return true;
            }
            @Override public void onLongPress(MotionEvent e) { startHold(); }
        });
        sleepGd = new GestureDetector(a, new GestureDetector.SimpleOnGestureListener() {
            @Override public boolean onDown(MotionEvent e) { return true; }
            @Override public boolean onDoubleTap(MotionEvent e) { exitSleep(); return true; }
        });

        // media area fills the whole screen; the controls float above it
        area = new FrameLayout(a);
        artBox = new FrameLayout(a);
        artBox.setBackground(Ui.shape(Ui.SURF2, 24, a));
        artBox.setClipToOutline(true);
        area.addView(artBox, new FrameLayout.LayoutParams(Ui.dp(a, 240), Ui.dp(a, 240), Gravity.CENTER));
        sv = new TextureView(a);
        sv.setSurfaceTexture(en.videoTexture()); // the engine's surface: playback never notices this view coming and going
        sv.setSurfaceTextureListener(new TextureView.SurfaceTextureListener() {
            public void onSurfaceTextureAvailable(SurfaceTexture t, int w, int h) { }
            public void onSurfaceTextureSizeChanged(SurfaceTexture t, int w, int h) { }
            public boolean onSurfaceTextureDestroyed(SurfaceTexture t) { return false; } // keep it, the engine owns it
            public void onSurfaceTextureUpdated(SurfaceTexture t) { }
        });
        area.addView(sv, new FrameLayout.LayoutParams(Ui.MATCH, Ui.dp(a, 200), Gravity.CENTER));
        sv.setVisibility(View.GONE);
        area.addOnLayoutChangeListener((v, l, t, r, b, ol, ot, orr, ob) -> fit());
        addView(area, new FrameLayout.LayoutParams(Ui.MATCH, Ui.MATCH));

        // captions: above the picture, below the controls
        capView = new TextView(a);
        capView.setTextColor(0xFFFFFFFF);
        capView.setTextSize(17);
        capView.setGravity(Gravity.CENTER);
        capView.setLineSpacing(0, 1.1f);
        capView.setMaxLines(4);
        capView.setPadding(Ui.dp(a, 12), Ui.dp(a, 5), Ui.dp(a, 12), Ui.dp(a, 6));
        capView.setBackground(Ui.shape(0xB3000000, 8, a));
        capView.setVisibility(View.GONE);
        FrameLayout.LayoutParams cvp = new FrameLayout.LayoutParams(Ui.WRAP, Ui.WRAP, Gravity.BOTTOM | Gravity.CENTER_HORIZONTAL);
        cvp.setMargins(Ui.dp(a, 24), 0, Ui.dp(a, 24), Ui.dp(a, 40));
        addView(capView, cvp);

        // ---------- top bar: X ... Now playing ... sleep ... lock ... 3 dots ----------
        top = new LinearLayout(a);
        top.setGravity(Gravity.CENTER_VERTICAL);
        top.setPadding(Ui.dp(a, 8), Ui.dp(a, 8), Ui.dp(a, 8), Ui.dp(a, 16));
        top.setBackground(scrim(GradientDrawable.Orientation.TOP_BOTTOM));
        ImageView x = Ui.btn(a, R.drawable.ic_close, Ui.TXT, 24, 10);
        x.setOnClickListener(v -> a.closePlayer());
        top.addView(x);
        TextView np = Ui.text(a, "Now playing", 15, Ui.SUB, true);
        np.setGravity(Gravity.CENTER);
        top.addView(np, new LinearLayout.LayoutParams(0, Ui.WRAP, 1));
        ImageView sleep = Ui.btn(a, R.drawable.ic_sleep, Ui.TXT, 24, 10);
        sleep.setOnClickListener(v -> enterSleep());
        top.addView(sleep);
        ImageView lock = Ui.btn(a, R.drawable.ic_lock, Ui.TXT, 24, 10);
        lock.setOnClickListener(v -> lock());
        top.addView(lock);
        ImageView dots = Ui.btn(a, R.drawable.ic_more, Ui.TXT, 24, 10);
        dots.setOnClickListener(v -> { if (en.cur() != null) a.showOptions(en.cur(), null); });
        top.addView(dots);
        addView(top, new FrameLayout.LayoutParams(Ui.MATCH, Ui.WRAP, Gravity.TOP));

        // ---------- bottom panel ----------
        bottom = new LinearLayout(a);
        bottom.setOrientation(LinearLayout.VERTICAL);
        bottom.setPadding(Ui.dp(a, 8), Ui.dp(a, 28), Ui.dp(a, 8), Ui.dp(a, 16));
        bottom.setBackground(scrim(GradientDrawable.Orientation.BOTTOM_TOP));
        addView(bottom, new FrameLayout.LayoutParams(Ui.MATCH, Ui.WRAP, Gravity.BOTTOM));

        // title / author
        LinearLayout info = new LinearLayout(a);
        info.setOrientation(LinearLayout.VERTICAL);
        info.setPadding(Ui.dp(a, 16), 0, Ui.dp(a, 16), 0);
        title = Ui.text(a, "", 22, Ui.TXT, true);
        sub = Ui.text(a, "", 15, Ui.SUB, false);
        info.addView(title);
        info.addView(sub);
        bottom.addView(info, new LinearLayout.LayoutParams(Ui.MATCH, Ui.WRAP));

        // seek
        sb = new SeekBar(a);
        sb.setProgressTintList(ColorStateList.valueOf(Ui.ACC));
        sb.setThumbTintList(ColorStateList.valueOf(Ui.ACC));
        sb.setSecondaryProgressTintList(ColorStateList.valueOf(Ui.SURF2));
        sb.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            public void onProgressChanged(SeekBar s, int p, boolean user) { if (user) cur.setText(Ui.fmt(p)); }
            public void onStartTrackingTouch(SeekBar s) { drag = true; }
            public void onStopTrackingTouch(SeekBar s) { drag = false; en.seek(s.getProgress()); }
        });
        bottom.addView(sb, new LinearLayout.LayoutParams(Ui.MATCH, Ui.WRAP));
        LinearLayout times = new LinearLayout(a);
        times.setPadding(Ui.dp(a, 16), 0, Ui.dp(a, 16), 0);
        cur = Ui.text(a, "0:00", 12, Ui.SUB, false);
        max = Ui.text(a, "0:00", 12, Ui.SUB, false);
        times.addView(cur, new LinearLayout.LayoutParams(0, Ui.WRAP, 1));
        times.addView(max);
        bottom.addView(times, new LinearLayout.LayoutParams(Ui.MATCH, Ui.WRAP));

        // controls
        LinearLayout ctl = new LinearLayout(a);
        ctl.setGravity(Gravity.CENTER);
        shuf = Ui.btn(a, R.drawable.ic_shuffle, Ui.SUB, 24, 10);
        shuf.setOnClickListener(v -> { en.shuffle = !en.shuffle; en.fire(); });
        ImageView prev = Ui.btn(a, R.drawable.ic_prev, Ui.TXT, 32, 10);
        prev.setOnClickListener(v -> en.prev());
        play = Ui.btn(a, R.drawable.ic_play, Ui.BG, 36, 18);
        play.setBackground(Ui.rip(Ui.TXT, 100, a));
        play.setOnClickListener(v -> en.toggle());
        ImageView next = Ui.btn(a, R.drawable.ic_next, Ui.TXT, 32, 10);
        next.setOnClickListener(v -> en.next(false));
        rep = Ui.btn(a, R.drawable.ic_repeat, Ui.SUB, 24, 10);
        rep.setOnClickListener(v -> { en.repeat = (en.repeat + 1) % 3; en.fire(); });
        for (View b : new View[]{shuf, prev, play, next, rep}) {
            LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(b.getLayoutParams());
            p.setMargins(Ui.dp(a, 4), 0, Ui.dp(a, 4), 0);
            ctl.addView(b, p);
        }
        LinearLayout.LayoutParams cp = new LinearLayout.LayoutParams(Ui.MATCH, Ui.WRAP);
        cp.setMargins(0, Ui.dp(a, 8), 0, Ui.dp(a, 8));
        bottom.addView(ctl, cp);

        // bottom row: favorite ... queue
        LinearLayout bot = new LinearLayout(a);
        bot.setGravity(Gravity.CENTER_VERTICAL);
        bot.setPadding(Ui.dp(a, 8), 0, Ui.dp(a, 8), 0);
        fav = Ui.btn(a, R.drawable.ic_heart, Ui.TXT, 24, 10);
        fav.setOnClickListener(v -> { if (en.cur() != null) { a.store.toggleFav(en.cur()); a.refreshAll(); changed(); } });
        bot.addView(fav);
        speedChip = chip("1x");
        speedChip.setOnClickListener(v -> showSpeed());
        ccChip = chip("CC");
        ccChip.setOnClickListener(v -> onCC());
        syncChip = chip("A/V");
        syncChip.setOnClickListener(v -> showSync());
        for (TextView c : new TextView[]{speedChip, ccChip, syncChip}) {
            LinearLayout.LayoutParams cp2 = new LinearLayout.LayoutParams(Ui.WRAP, Ui.WRAP);
            cp2.setMargins(Ui.dp(a, 4), 0, Ui.dp(a, 4), 0);
            bot.addView(c, cp2);
        }
        View sp = new View(a);
        bot.addView(sp, new LinearLayout.LayoutParams(0, 1, 1));
        ImageView q = Ui.btn(a, R.drawable.ic_playlist, Ui.TXT, 24, 10);
        q.setOnClickListener(v -> a.showQueue());
        bot.addView(q);
        bottom.addView(bot, new LinearLayout.LayoutParams(Ui.MATCH, Ui.WRAP));

        // lock icon, only visible while locked
        unlock = Ui.btn(a, R.drawable.ic_lock, Ui.TXT, 26, 13);
        unlock.setBackground(Ui.rip(Ui.SURF2, 100, a));
        unlock.setOnClickListener(v -> unlock());
        unlock.setVisibility(View.INVISIBLE);
        unlock.setAlpha(0f);
        FrameLayout.LayoutParams up = new FrameLayout.LayoutParams(unlock.getLayoutParams());
        up.gravity = Gravity.CENTER_VERTICAL | Gravity.LEFT;
        up.leftMargin = Ui.dp(a, 20);
        addView(unlock, up);

        // "+5s" / "-5s" feedback on the side that was double-tapped, and the "2x" tag while holding
        fbL = pill("");
        fbR = pill("");
        FrameLayout.LayoutParams lp1 = new FrameLayout.LayoutParams(Ui.WRAP, Ui.WRAP, Gravity.CENTER_VERTICAL | Gravity.LEFT);
        lp1.leftMargin = Ui.dp(a, 48);
        addView(fbL, lp1);
        FrameLayout.LayoutParams lp2 = new FrameLayout.LayoutParams(Ui.WRAP, Ui.WRAP, Gravity.CENTER_VERTICAL | Gravity.RIGHT);
        lp2.rightMargin = Ui.dp(a, 48);
        addView(fbR, lp2);
        holdTag = pill("");
        FrameLayout.LayoutParams lp3 = new FrameLayout.LayoutParams(Ui.WRAP, Ui.WRAP, Gravity.TOP | Gravity.CENTER_HORIZONTAL);
        lp3.topMargin = Ui.dp(a, 36);
        addView(holdTag, lp3);

        // sleep screen: black, ignores everything except a double-tap
        sleepLayer = new FrameLayout(a);
        sleepLayer.setBackgroundColor(0xFF000000);
        sleepLayer.setVisibility(View.GONE);
        sleepLayer.setOnTouchListener((v, e) -> { sleepGd.onTouchEvent(e); return true; });
        sleepHint = Ui.text(a, "Screen asleep. Double-tap to wake", 14, 0xFF555555, false);
        sleepLayer.addView(sleepHint, new FrameLayout.LayoutParams(Ui.WRAP, Ui.WRAP, Gravity.CENTER));
        addView(sleepLayer, new FrameLayout.LayoutParams(Ui.MATCH, Ui.MATCH));

        top.addOnLayoutChangeListener((v, l, t, r, b, ol, ot, orr, ob) -> fit());
        bottom.addOnLayoutChangeListener((v, l, t, r, b, ol, ot, orr, ob) -> fit());
        changed();
    }

    TextView chip(String t) {
        TextView v = Ui.text(a, t, 12, Ui.TXT, true);
        v.setGravity(Gravity.CENTER);
        v.setMinWidth(Ui.dp(a, 44));
        v.setPadding(Ui.dp(a, 10), Ui.dp(a, 7), Ui.dp(a, 10), Ui.dp(a, 7));
        v.setBackground(Ui.rip(Ui.SURF2, 14, a));
        return v;
    }

    void style(TextView v, boolean on) {
        v.setTextColor(on ? Ui.ONACC : Ui.TXT);
        v.setBackground(Ui.rip(on ? Ui.ACC : Ui.SURF2, 14, a));
    }

    TextView pill(String t) {
        TextView v = Ui.text(a, t, 16, 0xFFFFFFFF, true);
        v.setGravity(Gravity.CENTER);
        v.setPadding(Ui.dp(a, 16), Ui.dp(a, 8), Ui.dp(a, 16), Ui.dp(a, 8));
        v.setBackground(Ui.shape(0xCC000000, 20, a));
        v.setVisibility(View.INVISIBLE);
        v.setAlpha(0f);
        return v;
    }

    static String fmtSpeed(float s) {
        String t = String.format(Locale.US, "%.2f", s);
        if (t.contains(".")) t = t.replaceAll("0+$", "").replaceAll("\\.$", "");
        return t + "x";
    }

    // ---------- gestures: double-tap skip, press and hold = 2x ----------
    @Override
    public boolean onTouchEvent(MotionEvent e) {
        if (sleeping) return true;
        gd.onTouchEvent(e);
        int act = e.getActionMasked();
        if ((act == MotionEvent.ACTION_UP || act == MotionEvent.ACTION_CANCEL) && holding) endHold();
        return true;
    }

    void flash(final TextView v, String t) {
        v.setText(t);
        v.animate().cancel();
        v.setVisibility(View.VISIBLE);
        v.setAlpha(1f);
        v.animate().alpha(0f).setStartDelay(450).setDuration(300).withEndAction(() -> v.setVisibility(View.INVISIBLE)).start();
    }

    void skip(boolean fwd) {
        int dur = en.dur();
        if (dur <= 0 || en.cur() == null) return;
        long now = SystemClock.uptimeMillis();
        boolean chain = now - skipAt < 900;
        int base = chain ? skipTo : en.pos();
        int to = Math.max(0, Math.min(dur, base + (fwd ? 5000 : -5000)));
        skipSum = (chain && (skipSum > 0) == fwd ? skipSum : 0) + (fwd ? 5 : -5);
        skipAt = now;
        skipTo = to;
        en.seek(to);
        if (!drag) { sb.setProgress(to); cur.setText(Ui.fmt(to)); }
        flash(fwd ? fbR : fbL, (skipSum > 0 ? "+" : "\u2212") + Math.abs(skipSum) + "s");
        (fwd ? fbL : fbR).animate().cancel();
        (fwd ? fbL : fbR).setVisibility(View.INVISIBLE);
    }

    void startHold() {
        if (locked || sleeping || holding || !en.isPlaying()) return;
        holding = true;
        prevControls = controls;
        en.setBoost(2f);
        holdTag.setText("\u25B6\u25B6  " + fmtSpeed(en.rate()));
        fade(holdTag, true);
        fade(top, false);
        fade(bottom, false);
        layoutCaps();
        performHapticFeedback(HapticFeedbackConstants.LONG_PRESS);
    }

    void endHold() {
        if (!holding) return;
        holding = false;
        en.setBoost(1f);
        fade(holdTag, false);
        controls = prevControls;
        applyChrome();
        layoutCaps();
    }

    // ---------- sleep screen ----------
    void enterSleep() {
        if (sleeping) return;
        endHold();
        sleeping = true;
        sleepLayer.animate().cancel();
        sleepLayer.setAlpha(0f);
        sleepLayer.setVisibility(View.VISIBLE);
        sleepLayer.animate().alpha(1f).setStartDelay(0).setDuration(250).start();
        sleepHint.animate().cancel();
        sleepHint.setAlpha(1f);
        sleepHint.animate().alpha(0f).setStartDelay(2500).setDuration(600).start();
        try {
            WindowManager.LayoutParams lp = a.getWindow().getAttributes();
            lp.screenBrightness = WindowManager.LayoutParams.BRIGHTNESS_OVERRIDE_OFF; // as dark as the device allows
            a.getWindow().setAttributes(lp);
            WindowInsetsController c = a.getWindow().getInsetsController();
            if (c != null) {
                c.setSystemBarsBehavior(WindowInsetsController.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE);
                c.hide(WindowInsets.Type.systemBars());
            }
        } catch (Exception e) { }
        updateKeepOn(); // the system may now turn the screen off by itself; the sound keeps playing
    }

    void exitSleep() {
        if (!sleeping) return;
        sleeping = false;
        sleepLayer.animate().cancel();
        sleepHint.animate().cancel();
        sleepLayer.setVisibility(View.GONE);
        try {
            WindowManager.LayoutParams lp = a.getWindow().getAttributes();
            lp.screenBrightness = WindowManager.LayoutParams.BRIGHTNESS_OVERRIDE_NONE;
            a.getWindow().setAttributes(lp);
            WindowInsetsController c = a.getWindow().getInsetsController();
            if (c != null) c.show(WindowInsets.Type.systemBars());
        } catch (Exception e) { }
        updateKeepOn();
        layoutCaps();
    }

    /** Keeps the screen on: always with the Screen Wake Lock setting, otherwise only while a video is playing. Never while the screen is asleep. */
    void updateKeepOn() {
        Media m = en.cur();
        boolean videoOn = getVisibility() == View.VISIBLE && m != null && m.video && !en.audioMode && en.isPlaying();
        // Settings > Screen Wake Lock (default on): the screen never turns off by itself while the app is open
        boolean on = !sleeping && (a.store.bool("wake_lock", true) || videoOn);
        try {
            if (on) a.getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
            else a.getWindow().clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        } catch (Exception e) { }
    }

    @Override
    protected void onVisibilityChanged(View v, int vis) {
        super.onVisibilityChanged(v, vis);
        if (v != this || en == null) return;
        if (vis != View.VISIBLE) { endHold(); exitSleep(); } else startCapLoop();
        updateKeepOn();
    }

    // ---------- playback speed / audio-video sync / captions ----------
    void updateChips() {
        Media m = en.cur();
        speedChip.setText(fmtSpeed(en.speed));
        style(speedChip, Math.abs(en.speed - 1f) > 0.001f);
        boolean vid = m != null && m.video && !en.audioMode;
        syncChip.setVisibility(vid ? View.VISIBLE : View.GONE);
        syncChip.setText(en.syncMs == 0 ? "A/V" : (en.syncMs > 0 ? "+" : "") + en.syncMs + "ms");
        style(syncChip, en.syncMs != 0);
        style(ccChip, caps != null && a.store.bool("caps_on", true));
    }

    LinearLayout sheetBody(Ui.Sheet sh) {
        LinearLayout b = new LinearLayout(a);
        b.setOrientation(LinearLayout.VERTICAL);
        b.setPadding(Ui.dp(a, 20), Ui.dp(a, 4), Ui.dp(a, 20), Ui.dp(a, 8));
        sh.box.addView(b, new LinearLayout.LayoutParams(Ui.MATCH, Ui.WRAP));
        return b;
    }

    TextView sheetChip(String t) {
        TextView v = Ui.text(a, t, 14, Ui.TXT, true);
        v.setGravity(Gravity.CENTER);
        v.setPadding(Ui.dp(a, 4), Ui.dp(a, 10), Ui.dp(a, 4), Ui.dp(a, 10));
        v.setBackground(Ui.rip(Ui.SURF2, 20, a));
        return v;
    }

    LinearLayout chipRow(LinearLayout parent) {
        LinearLayout r = new LinearLayout(a);
        LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(Ui.MATCH, Ui.WRAP);
        p.topMargin = Ui.dp(a, 14);
        parent.addView(r, p);
        return r;
    }

    void addChip(LinearLayout row, TextView c, final Runnable r) {
        LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(0, Ui.WRAP, 1);
        p.setMargins(Ui.dp(a, 3), 0, Ui.dp(a, 3), 0);
        c.setOnClickListener(v -> r.run());
        row.addView(c, p);
    }

    void showSpeed() {
        final Ui.Sheet sh = new Ui.Sheet(a, "Playback speed", "Press and hold the player screen for a quick 2x.");
        LinearLayout body = sheetBody(sh);
        final TextView val = Ui.text(a, fmtSpeed(en.speed), 34, Ui.ACC, true);
        val.setGravity(Gravity.CENTER);
        body.addView(val, new LinearLayout.LayoutParams(Ui.MATCH, Ui.WRAP));
        final SeekBar bar = new SeekBar(a);
        bar.setMax(96); // 0.2x .. 5x in steps of 0.05
        bar.setProgress(Math.round((en.speed - Engine.MIN_SPEED) / 0.05f));
        Ui.tintSeek(bar);
        body.addView(bar, new LinearLayout.LayoutParams(Ui.MATCH, Ui.WRAP));
        LinearLayout ends = new LinearLayout(a);
        TextView lo = Ui.text(a, "0.2x", 12, Ui.SUB, false), hi = Ui.text(a, "5x", 12, Ui.SUB, false);
        ends.addView(lo, new LinearLayout.LayoutParams(0, Ui.WRAP, 1));
        ends.addView(hi);
        body.addView(ends, new LinearLayout.LayoutParams(Ui.MATCH, Ui.WRAP));
        final Runnable[] set = new Runnable[1];
        final float[] want = {en.speed};
        set[0] = () -> {
            if (!en.setSpeed(want[0])) a.toast("This device can't play at " + fmtSpeed(want[0]));
            val.setText(fmtSpeed(en.speed));
            bar.setProgress(Math.round((en.speed - Engine.MIN_SPEED) / 0.05f));
            updateChips();
        };
        bar.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            public void onProgressChanged(SeekBar s, int p, boolean user) {
                if (!user) return;
                want[0] = Engine.MIN_SPEED + p * 0.05f;
                set[0].run();
            }
            public void onStartTrackingTouch(SeekBar s) { }
            public void onStopTrackingTouch(SeekBar s) { }
        });
        LinearLayout row = chipRow(body);
        float[] presets = {0.5f, 1f, 1.5f, 2f, 3f, 5f};
        for (final float f : presets) addChip(row, sheetChip(fmtSpeed(f)), () -> { want[0] = f; set[0].run(); });
        sh.show();
    }

    void showSync() {
        Media m = en.cur();
        if (m == null || !m.video || en.audioMode) { a.toast("Audio / video sync is for videos"); return; }
        final Ui.Sheet sh = new Ui.Sheet(a, "Audio / video sync", "Move the sound against the picture, 10 ms at a time.");
        LinearLayout body = sheetBody(sh);
        final TextView val = Ui.text(a, "", 30, Ui.ACC, true);
        val.setGravity(Gravity.CENTER);
        body.addView(val, new LinearLayout.LayoutParams(Ui.MATCH, Ui.WRAP));
        final TextView what = Ui.text(a, "", 13, Ui.SUB, false);
        what.setGravity(Gravity.CENTER);
        body.addView(what, new LinearLayout.LayoutParams(Ui.MATCH, Ui.WRAP));
        final SeekBar bar = new SeekBar(a);
        bar.setMax(200); // -1000 .. +1000 ms
        Ui.tintSeek(bar);
        LinearLayout.LayoutParams bp = new LinearLayout.LayoutParams(Ui.MATCH, Ui.WRAP);
        bp.topMargin = Ui.dp(a, 6);
        body.addView(bar, bp);
        LinearLayout ends = new LinearLayout(a);
        ends.addView(Ui.text(a, "Sound later", 12, Ui.SUB, false), new LinearLayout.LayoutParams(0, Ui.WRAP, 1));
        ends.addView(Ui.text(a, "Sound earlier", 12, Ui.SUB, false));
        body.addView(ends, new LinearLayout.LayoutParams(Ui.MATCH, Ui.WRAP));
        final Runnable[] show = new Runnable[1];
        show[0] = () -> {
            int ms = en.syncMs;
            val.setText((ms > 0 ? "+" : "") + ms + " ms");
            what.setText(ms == 0 ? "In sync (as recorded)"
                    : ms > 0 ? "Sound plays " + ms + " ms before the picture" : "Sound plays " + (-ms) + " ms after the picture");
            bar.setProgress(ms / 10 + 100);
            updateChips();
        };
        show[0].run();
        bar.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            public void onProgressChanged(SeekBar s, int p, boolean user) {
                if (!user) return;
                en.setSync((p - 100) * 10);
                show[0].run();
            }
            public void onStartTrackingTouch(SeekBar s) { }
            public void onStopTrackingTouch(SeekBar s) { }
        });
        LinearLayout row = chipRow(body);
        final int[] steps = {-100, -10, 10, 100};
        for (final int d : steps)
            addChip(row, sheetChip((d > 0 ? "+" : "\u2212") + Math.abs(d)), () -> { en.setSync(en.syncMs + d); show[0].run(); });
        addChip(row, sheetChip("Reset"), () -> { en.setSync(0); show[0].run(); });
        sh.show();
    }

    void onCC() {
        final Media m = en.cur();
        if (m == null) return;
        if (caps == null) {
            Ui.confirm(a, "Add captions", "Import a subtitle file (.srt, .vtt, .ttml, .ass, .sbv, .lrc ...) for \"" + m.title
                    + "\". It is saved inside the app and included in your backups.", "Import", false, () -> a.pickCaptions(m));
            return;
        }
        final boolean on = a.store.bool("caps_on", true);
        Ui.Sheet sh = new Ui.Sheet(a, "Captions", m.title);
        sh.item(on ? R.drawable.ic_close : R.drawable.ic_check, on ? "Hide captions" : "Show captions", Ui.TXT, () -> {
            a.store.setBool("caps_on", !on);
            capShown = "";
            updateChips();
            startCapLoop();
            if (on) capView.setVisibility(View.GONE);
        });
        sh.item(R.drawable.ic_add, "Import other captions", Ui.TXT, () -> a.pickCaptions(m));
        sh.item(R.drawable.ic_delete, "Remove captions", Ui.RED, () -> {
            a.store.deleteCaps(m.key());
            capsChanged();
            a.toast("Captions removed");
        });
        sh.show();
    }

    /** Captions of the current item were added / replaced / removed. */
    void capsChanged() {
        capKey = "";
        changed();
    }

    void loadCaps() {
        Media m = en.cur();
        caps = m != null && a.store.hasCaps(m) ? Captions.parse(a.store.readCaps(m.key())) : null;
        capShown = "";
        capView.setVisibility(View.GONE);
        startCapLoop();
    }

    void startCapLoop() {
        if (capRun) return;
        capRun = true;
        post(capTick);
    }

    final Runnable capTick = new Runnable() {
        @Override public void run() {
            capRun = false;
            if (getVisibility() != View.VISIBLE || caps == null || !a.store.bool("caps_on", true)) {
                capView.setVisibility(View.GONE);
                return;
            }
            String t = caps.at(en.pos());
            if (t == null ? capShown != null : !t.equals(capShown)) {
                capShown = t;
                if (t == null) capView.setVisibility(View.GONE);
                else { capView.setText(t); capView.setVisibility(View.VISIBLE); }
            }
            capRun = true;
            postDelayed(this, 100);
        }
    };

    /** Captions sit just above the controls while those are shown, near the bottom edge otherwise. */
    void layoutCaps() {
        FrameLayout.LayoutParams lp = (FrameLayout.LayoutParams) capView.getLayoutParams();
        int mb = controls && !locked && !holding && !sleeping ? bottom.getHeight() + Ui.dp(a, 8) : Ui.dp(a, 40);
        if (lp.bottomMargin != mb) { lp.bottomMargin = mb; capView.setLayoutParams(lp); }
    }

    GradientDrawable scrim(GradientDrawable.Orientation o) {
        int solid = (Ui.BG & 0x00FFFFFF) | 0xF2000000;
        return new GradientDrawable(o, new int[]{solid, Ui.BG & 0x00FFFFFF});
    }

    // ---------- controls: tap to hide / show, lock ----------
    void onTap() {
        if (locked) lockIconShown = !lockIconShown;
        else controls = !controls;
        applyChrome();
    }

    void lock() {
        locked = true;
        lockIconShown = true;
        applyChrome();
    }

    void unlock() {
        locked = false;
        lockIconShown = false;
        controls = true;
        applyChrome();
    }

    /** Back button: returns true when it was swallowed because the player is locked. */
    boolean onBack() {
        if (sleeping) { exitSleep(); return true; }
        if (!locked) return false;
        lockIconShown = true;
        applyChrome();
        a.toast("Locked. Tap the lock icon to unlock");
        return true;
    }

    void onOpen() {
        if (!locked) { controls = true; applyChrome(); }
    }

    void applyChrome() {
        boolean show = controls && !locked;
        fade(top, show);
        fade(bottom, show);
        fade(unlock, locked && lockIconShown);
        layoutCaps();
    }

    void fade(final View v, boolean show) {
        v.animate().cancel();
        if (show) {
            v.setVisibility(View.VISIBLE);
            v.animate().alpha(1f).setDuration(180).start();
        } else {
            v.animate().alpha(0f).setDuration(180).withEndAction(() -> v.setVisibility(View.INVISIBLE)).start();
        }
    }

    void fit() {
        int aw = area.getWidth(), ah = area.getHeight();
        if (aw == 0 || ah == 0) return;
        layoutCaps();
        // artwork sits centred between the (invisible-but-laid-out) top bar and bottom panel
        int pt = top.getHeight(), pb = bottom.getHeight();
        int availH = Math.max(Ui.dp(a, 120), ah - pt - pb);
        int s = Math.max(Ui.dp(a, 120), Math.min(aw - Ui.dp(a, 48), availH - Ui.dp(a, 16)));
        int tm = pt + (availH - s) / 2;
        FrameLayout.LayoutParams ab = (FrameLayout.LayoutParams) artBox.getLayoutParams();
        int g = Gravity.TOP | Gravity.CENTER_HORIZONTAL;
        if (ab.width != s || ab.height != s || ab.topMargin != tm || ab.gravity != g) {
            ab.width = s; ab.height = s; ab.topMargin = tm; ab.gravity = g;
            artBox.setLayoutParams(ab);
        }
        if (spectrum instanceof VibeView) { // keep the visual centred where the artwork would be, sized like it
            VibeView vv = (VibeView) spectrum;
            vv.ref = s;
            vv.focusY = tm + s / 2f;
        }
        int w, h;
        if (en.vw > 0 && en.vh > 0) {
            float sc = Math.min((float) aw / en.vw, (float) ah / en.vh);
            w = (int) (en.vw * sc);
            h = (int) (en.vh * sc);
        } else { w = aw; h = aw * 9 / 16; }
        FrameLayout.LayoutParams vp = (FrameLayout.LayoutParams) sv.getLayoutParams();
        if (vp.width != w || vp.height != h) { vp.width = w; vp.height = h; sv.setLayoutParams(vp); }
    }

    @Override
    public void changed() {
        Media m = en.cur();
        String ck = m == null ? "" : m.key();
        if (!ck.equals(capKey)) { capKey = ck; loadCaps(); }
        title.setText(m == null ? "" : m.title);
        sub.setText(m == null ? "" : m.artist);
        boolean showVideo = m != null && m.video && !en.audioMode;
        sv.setVisibility(showVideo ? View.VISIBLE : View.GONE);
        boolean specOn = a.store.bool("spec_player", false);
        boolean vibe = "vibe".equals(a.store.p.getString("viz_mode", "bar"));
        boolean vibeFull = specOn && vibe && m != null; // Vibe fills the whole player instead of sitting in the artwork box
        artBox.setVisibility(showVideo || vibeFull ? View.GONE : View.VISIBLE);
        String k = m == null ? "" : m.key() + (m.video ? "v" : "a") + (specOn ? (vibe ? "w" : "s") : "");
        if (!k.equals(artKey)) {
            artKey = k;
            artBox.removeAllViews();
            if (spectrum != null && spectrum.getParent() == area) area.removeView(spectrum);
            spectrum = null;
            if (vibeFull) {
                spectrum = new VibeView(a);
                area.addView(spectrum, 0, new FrameLayout.LayoutParams(Ui.MATCH, Ui.MATCH)); // behind everything, controls float above it
                fit();
            } else if (m != null && specOn) {
                spectrum = new Ui.SpectrumView(a);
                artBox.addView(spectrum, new FrameLayout.LayoutParams(Ui.MATCH, Ui.MATCH));
            } else if (m != null) {
                ImageView ic = new ImageView(a);
                ic.setImageResource(m.video ? R.drawable.ic_movie : R.drawable.ic_music);
                ic.setColorFilter(Ui.SUB);
                int p = Ui.dp(a, 64);
                ic.setPadding(p, p, p, p);
                artBox.addView(ic, new FrameLayout.LayoutParams(Ui.MATCH, Ui.MATCH));
                ImageView t = new ImageView(a);
                t.setScaleType(ImageView.ScaleType.CENTER_CROP);
                artBox.addView(t, new FrameLayout.LayoutParams(Ui.MATCH, Ui.MATCH));
                Ui.thumb(a, m, t, 600);
            }
        }
        if (spectrum != null) {
            if (vibeFull) spectrum.setVisibility(showVideo ? View.GONE : View.VISIBLE);
            spectrum.invalidate();
        }
        play.setImageResource(en.isPlaying() ? R.drawable.ic_pause : R.drawable.ic_play);
        shuf.setColorFilter(en.shuffle ? Ui.ACC : Ui.SUB);
        rep.setImageResource(en.repeat == 2 ? R.drawable.ic_repeat_one : R.drawable.ic_repeat);
        rep.setColorFilter(en.repeat == 0 ? Ui.SUB : Ui.ACC);
        boolean f = m != null && a.store.isFav(m);
        fav.setImageResource(f ? R.drawable.ic_heart_fill : R.drawable.ic_heart);
        fav.setColorFilter(f ? Ui.ACC : Ui.TXT);
        sb.setMax(Math.max(1, en.dur()));
        max.setText(Ui.fmt(en.dur()));
        updateChips();
        updateKeepOn();
        fit();
        tick();
    }

    void tick() {
        if (drag) return;
        sb.setProgress(en.pos());
        cur.setText(Ui.fmt(en.pos()));
    }
}
