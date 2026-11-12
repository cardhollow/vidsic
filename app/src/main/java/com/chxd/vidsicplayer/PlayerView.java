package com.chxd.vidsicplayer;

import android.content.res.ColorStateList;
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

class PlayerView extends FrameLayout implements Engine.L {
    final MainActivity a;
    final Engine en;
    final ImageView play, shuf, rep, fav, prev, next, queueBtn;
    final LinearLayout info;
    TextView nowPlaying;
    boolean land, rotated;
    final TextView title, sub, cur, max;
    final SeekBar sb;
    final TextureView sv;
    final FrameLayout area, artBox;
    final LinearLayout top, bottom;
    final ImageView unlock;
    final TextView speedChip, ccChip, syncChip, capView, holdTag, fbL, fbR, sleepHint;
    final FrameLayout sleepLayer;
    final GestureDetector gd, sleepGd;
    boolean pip, drag, controls = true, locked, lockIconShown, holding, prevControls, sleeping, capRun;
    long skipAt;
    int skipTo, skipSum;
    Captions caps;
    String capKey = "", capShown;
    String artKey = "";
    View spectrum;

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

        area = new FrameLayout(a);
        artBox = new FrameLayout(a);
        artBox.setBackground(Ui.shape(Ui.SURF2, 24, a));
        artBox.setClipToOutline(true);
        area.addView(artBox, new FrameLayout.LayoutParams(Ui.dp(a, 240), Ui.dp(a, 240), Gravity.CENTER));
        sv = new TextureView(a);
        sv.setSurfaceTexture(en.videoTexture());
        sv.setSurfaceTextureListener(new TextureView.SurfaceTextureListener() {
            public void onSurfaceTextureAvailable(SurfaceTexture t, int w, int h) { }
            public void onSurfaceTextureSizeChanged(SurfaceTexture t, int w, int h) { }
            public boolean onSurfaceTextureDestroyed(SurfaceTexture t) { return false; }
            public void onSurfaceTextureUpdated(SurfaceTexture t) { }
        });
        area.addView(sv, new FrameLayout.LayoutParams(Ui.MATCH, Ui.dp(a, 200), Gravity.CENTER));
        sv.setVisibility(View.GONE);
        area.addOnLayoutChangeListener((v, l, t, r, b, ol, ot, orr, ob) -> fit());
        addView(area, new FrameLayout.LayoutParams(Ui.MATCH, Ui.MATCH));

        capView = new CapSettings.CapText(a);
        CapSettings.apply(capView, a.store.p, a);
        capView.setVisibility(View.GONE);
        FrameLayout.LayoutParams cvp = new FrameLayout.LayoutParams(Ui.WRAP, Ui.WRAP, Gravity.BOTTOM | Gravity.CENTER_HORIZONTAL);
        cvp.setMargins(Ui.dp(a, 24), 0, Ui.dp(a, 24), Ui.dp(a, 40));
        addView(capView, cvp);

        top = new LinearLayout(a);
        top.setGravity(Gravity.CENTER_VERTICAL);
        top.setPadding(Ui.dp(a, 8), Ui.dp(a, 8), Ui.dp(a, 8), Ui.dp(a, 16));
        top.setBackground(scrim(GradientDrawable.Orientation.TOP_BOTTOM));
        ImageView x = Ui.btn(a, R.drawable.ic_close, Ui.TXT, 24, 10);
        x.setOnClickListener(v -> a.closePlayer());
        top.addView(x);
        TextView np = Ui.text(a, "Now playing", 15, Ui.SUB, true);
        nowPlaying = np;
        np.setGravity(Gravity.CENTER);
        top.addView(np, new LinearLayout.LayoutParams(0, Ui.WRAP, 1));
        ImageView rot = Ui.btn(a, R.drawable.ic_rotate, Ui.TXT, 24, 10);
        rot.setOnClickListener(v -> rotate());
        top.addView(rot);
        ImageView sleep = Ui.btn(a, R.drawable.ic_sleep, Ui.TXT, 24, 10);
        sleep.setOnClickListener(v -> enterSleep());
        top.addView(sleep);
        ImageView pipBtn = Ui.btn(a, R.drawable.ic_pip, Ui.TXT, 24, 10);
        pipBtn.setOnClickListener(v -> a.enterPip());
        top.addView(pipBtn);
        ImageView lock = Ui.btn(a, R.drawable.ic_lock, Ui.TXT, 24, 10);
        lock.setOnClickListener(v -> lock());
        top.addView(lock);
        ImageView dots = Ui.btn(a, R.drawable.ic_more, Ui.TXT, 24, 10);
        dots.setOnClickListener(v -> { if (en.cur() != null) a.showOptions(en.cur(), null); });
        top.addView(dots);
        addView(top, new FrameLayout.LayoutParams(Ui.MATCH, Ui.WRAP, Gravity.TOP));

        bottom = new LinearLayout(a);
        bottom.setOrientation(LinearLayout.VERTICAL);
        bottom.setBackground(scrim(GradientDrawable.Orientation.BOTTOM_TOP));
        addView(bottom, new FrameLayout.LayoutParams(Ui.MATCH, Ui.WRAP, Gravity.BOTTOM));

        info = new LinearLayout(a);
        info.setOrientation(LinearLayout.VERTICAL);
        info.setPadding(Ui.dp(a, 16), 0, Ui.dp(a, 16), 0);
        title = Ui.text(a, "", 22, Ui.TXT, true);
        sub = Ui.text(a, "", 15, Ui.SUB, false);
        info.addView(title);
        info.addView(sub);

        sb = new SeekBar(a);
        sb.setProgressTintList(ColorStateList.valueOf(Ui.ACC));
        sb.setThumbTintList(ColorStateList.valueOf(Ui.ACC));
        sb.setSecondaryProgressTintList(ColorStateList.valueOf(Ui.SURF2));
        sb.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            public void onProgressChanged(SeekBar s, int p, boolean user) { if (user) cur.setText(Ui.fmt(p)); }
            public void onStartTrackingTouch(SeekBar s) { drag = true; }
            public void onStopTrackingTouch(SeekBar s) { drag = false; en.seek(s.getProgress()); }
        });
        cur = Ui.text(a, "0:00", 12, Ui.SUB, false);
        max = Ui.text(a, "0:00", 12, Ui.SUB, false);

        shuf = Ui.btn(a, R.drawable.ic_shuffle, Ui.SUB, 24, 10);
        shuf.setOnClickListener(v -> { en.shuffle = !en.shuffle; en.fire(); });
        prev = Ui.btn(a, R.drawable.ic_prev, Ui.TXT, 32, 10);
        prev.setOnClickListener(v -> en.prev());
        play = Ui.btn(a, R.drawable.ic_play, Ui.BG, 36, 18);
        play.setBackground(Ui.rip(Ui.TXT, 100, a));
        play.setOnClickListener(v -> en.toggle());
        next = Ui.btn(a, R.drawable.ic_next, Ui.TXT, 32, 10);
        next.setOnClickListener(v -> en.next(false));
        rep = Ui.btn(a, R.drawable.ic_repeat, Ui.SUB, 24, 10);
        rep.setOnClickListener(v -> { en.repeat = (en.repeat + 1) % 3; en.fire(); });

        fav = Ui.btn(a, R.drawable.ic_heart, Ui.TXT, 24, 10);
        fav.setOnClickListener(v -> { if (en.cur() != null) { a.store.toggleFav(en.cur()); a.refreshAll(); changed(); } });
        speedChip = chip("1x");
        speedChip.setOnClickListener(v -> showSpeed());
        ccChip = chip("CC");
        ccChip.setOnClickListener(v -> onCC());
        syncChip = chip("A/V");
        syncChip.setOnClickListener(v -> showSync());
        queueBtn = Ui.btn(a, R.drawable.ic_playlist, Ui.TXT, 24, 10);
        queueBtn.setOnClickListener(v -> a.showQueue());
        buildBottom(false);

        unlock = Ui.btn(a, R.drawable.ic_lock, Ui.TXT, 26, 13);
        unlock.setBackground(Ui.rip(Ui.SURF2, 100, a));
        unlock.setOnClickListener(v -> unlock());
        unlock.setVisibility(View.INVISIBLE);
        unlock.setAlpha(0f);
        FrameLayout.LayoutParams up = new FrameLayout.LayoutParams(unlock.getLayoutParams());
        up.gravity = Gravity.CENTER_VERTICAL | Gravity.LEFT;
        up.leftMargin = Ui.dp(a, 20);
        addView(unlock, up);

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

    /** Lays the bottom controls out for portrait (tall stack) or landscape (two slim rows). */
    void buildBottom(boolean l) {
        land = l;
        for (View v : new View[]{info, sb, cur, max, shuf, prev, play, next, rep, fav, speedChip, ccChip, syncChip, queueBtn}) {
            if (v.getParent() instanceof android.view.ViewGroup) ((android.view.ViewGroup) v.getParent()).removeView(v);
        }
        bottom.removeAllViews();
        int m4 = Ui.dp(a, 4);
        if (!l) {
            bottom.setPadding(Ui.dp(a, 8), Ui.dp(a, 28), Ui.dp(a, 8), Ui.dp(a, 16));
            bottom.addView(info, new LinearLayout.LayoutParams(Ui.MATCH, Ui.WRAP));
            info.setVisibility(View.VISIBLE);
            bottom.addView(sb, new LinearLayout.LayoutParams(Ui.MATCH, Ui.WRAP));
            LinearLayout times = new LinearLayout(a);
            times.setPadding(Ui.dp(a, 16), 0, Ui.dp(a, 16), 0);
            times.addView(cur, new LinearLayout.LayoutParams(0, Ui.WRAP, 1));
            times.addView(max);
            bottom.addView(times, new LinearLayout.LayoutParams(Ui.MATCH, Ui.WRAP));

            sizePlay(36, 18);
            LinearLayout ctl = new LinearLayout(a);
            ctl.setGravity(Gravity.CENTER);
            for (View b : new View[]{shuf, prev, play, next, rep}) {
                LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(b.getLayoutParams());
                p.setMargins(m4, 0, m4, 0);
                ctl.addView(b, p);
            }
            LinearLayout.LayoutParams cp = new LinearLayout.LayoutParams(Ui.MATCH, Ui.WRAP);
            cp.setMargins(0, Ui.dp(a, 8), 0, Ui.dp(a, 8));
            bottom.addView(ctl, cp);

            LinearLayout bot = new LinearLayout(a);
            bot.setGravity(Gravity.CENTER_VERTICAL);
            bot.setPadding(Ui.dp(a, 8), 0, Ui.dp(a, 8), 0);
            bot.addView(fav);
            for (TextView c : new TextView[]{speedChip, ccChip, syncChip}) {
                LinearLayout.LayoutParams cp2 = new LinearLayout.LayoutParams(Ui.WRAP, Ui.WRAP);
                cp2.setMargins(m4, 0, m4, 0);
                bot.addView(c, cp2);
            }
            bot.addView(new View(a), new LinearLayout.LayoutParams(0, 1, 1));
            bot.addView(queueBtn);
            bottom.addView(bot, new LinearLayout.LayoutParams(Ui.MATCH, Ui.WRAP));
        } else {
            bottom.setPadding(Ui.dp(a, 8), Ui.dp(a, 20), Ui.dp(a, 8), Ui.dp(a, 6));
            LinearLayout seek = new LinearLayout(a);
            seek.setGravity(Gravity.CENTER_VERTICAL);
            seek.setPadding(Ui.dp(a, 12), 0, Ui.dp(a, 12), 0);
            seek.addView(cur, new LinearLayout.LayoutParams(Ui.WRAP, Ui.WRAP));
            seek.addView(sb, new LinearLayout.LayoutParams(0, Ui.WRAP, 1));
            seek.addView(max, new LinearLayout.LayoutParams(Ui.WRAP, Ui.WRAP));
            bottom.addView(seek, new LinearLayout.LayoutParams(Ui.MATCH, Ui.WRAP));

            sizePlay(26, 11);
            LinearLayout row = new LinearLayout(a);
            row.setGravity(Gravity.CENTER_VERTICAL);
            row.setPadding(Ui.dp(a, 4), 0, Ui.dp(a, 4), 0);
            row.addView(fav);
            for (TextView c : new TextView[]{speedChip, ccChip, syncChip}) {
                LinearLayout.LayoutParams cp2 = new LinearLayout.LayoutParams(Ui.WRAP, Ui.WRAP);
                cp2.setMargins(m4, 0, m4, 0);
                row.addView(c, cp2);
            }
            row.addView(new View(a), new LinearLayout.LayoutParams(0, 1, 1));
            for (View b : new View[]{shuf, prev, play, next, rep}) {
                LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(b.getLayoutParams());
                p.setMargins(m4, 0, m4, 0);
                row.addView(b, p);
            }
            row.addView(new View(a), new LinearLayout.LayoutParams(0, 1, 1));
            row.addView(queueBtn);
            bottom.addView(row, new LinearLayout.LayoutParams(Ui.MATCH, Ui.WRAP));
        }
        updateNowPlaying();
    }

    boolean modePosted;

    /** Rebuild the bottom controls after the current layout pass, never from inside it. */
    void requestMode(final boolean l) {
        if (modePosted) return;
        modePosted = true;
        post(() -> {
            modePosted = false;
            if (area.getWidth() == 0 || (area.getWidth() > area.getHeight()) == land) return;
            try { buildBottom(area.getWidth() > area.getHeight()); } catch (Throwable t) { }
            requestLayout();
        });
    }

    void sizePlay(int icon, int pad) {
        int p = Ui.dp(a, pad), s = Ui.dp(a, icon + 2 * pad);
        play.setPadding(p, p, p, p);
        play.setLayoutParams(new LinearLayout.LayoutParams(s, s));
    }

    void updateNowPlaying() {
        if (nowPlaying == null) return;
        Media m = en.cur();
        nowPlaying.setText(land && m != null ? m.title : "Now playing");
        nowPlaying.setTextColor(land && m != null ? Ui.TXT : Ui.SUB);
    }

    /** Flip the player between landscape and portrait. */
    void rotate() {
        final boolean isLand = getWidth() > getHeight();
        rotated = true;
        post(() -> {
            try {
                a.setRequestedOrientation(isLand ? android.content.pm.ActivityInfo.SCREEN_ORIENTATION_PORTRAIT
                        : android.content.pm.ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE);
            } catch (Throwable t) { a.toast("Can't rotate right now"); }
        });
    }

    void resetOrientation() {
        if (!rotated) return;
        rotated = false;
        try { a.setRequestedOrientation(android.content.pm.ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED); } catch (Throwable t) { }
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
        systemBars(false);
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
            lp.screenBrightness = WindowManager.LayoutParams.BRIGHTNESS_OVERRIDE_OFF;
            a.getWindow().setAttributes(lp);
            WindowInsetsController c = a.getWindow().getInsetsController();
            if (c != null) {
                c.setSystemBarsBehavior(WindowInsetsController.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE);
                c.hide(WindowInsets.Type.systemBars());
            }
        } catch (Exception e) { }
        updateKeepOn();
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
            if (c != null && ((controls && !locked) || pip)) c.show(WindowInsets.Type.systemBars());
        } catch (Exception e) { }
        updateKeepOn();
        layoutCaps();
    }

    void updateKeepOn() {
        Media m = en.cur();
        boolean videoOn = getVisibility() == View.VISIBLE && m != null && m.video && !en.audioMode && en.isPlaying();

        boolean on = !sleeping && ((a.store.bool("wake_lock", true) && !Engine.SAVER) || videoOn || (a.viewer != null && a.viewer.keepAwake()));
        try {
            if (on) a.getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
            else a.getWindow().clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        } catch (Exception e) { }
    }

    @Override
    protected void onVisibilityChanged(View v, int vis) {
        super.onVisibilityChanged(v, vis);
        if (v != this || en == null) return;
        if (vis != View.VISIBLE) { endHold(); exitSleep(); resetOrientation(); if (a.viewer == null || a.viewer.getVisibility() != View.VISIBLE) systemBars(true); } else startCapLoop();
        updateKeepOn();
    }

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
        bar.setMax(96);
        bar.setProgress(Math.round((en.speed - Engine.MIN_SPEED) / 0.05f));
        Ui.tintSeek(bar);
        body.addView(bar, new LinearLayout.LayoutParams(Ui.MATCH, Ui.WRAP));
        LinearLayout ends = new LinearLayout(a);
        TextView lo = Ui.text(a, "0.2x", 12, Ui.SUB, false), hi = Ui.text(a, "5x", 12, Ui.SUB, false);
        ends.addView(lo, new LinearLayout.LayoutParams(0, Ui.WRAP, 1));
        ends.addView(hi);
        body.addView(ends, new LinearLayout.LayoutParams(Ui.MATCH, Ui.WRAP));
        final float[] want = {en.speed};
        final long[] lastSet = {0};

        final Runnable apply = () -> {
            lastSet[0] = SystemClock.uptimeMillis();
            en.setSpeed(want[0]);
            updateChips();
        };
        final Runnable[] set = new Runnable[1];
        set[0] = () -> {
            apply.run();
            val.setText(fmtSpeed(want[0]));
            bar.setProgress(Math.round((want[0] - Engine.MIN_SPEED) / 0.05f));
        };
        bar.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            public void onProgressChanged(SeekBar s, int p, boolean user) {
                if (!user) return;
                want[0] = Math.round((Engine.MIN_SPEED + p * 0.05f) * 100f) / 100f;
                val.setText(fmtSpeed(want[0]));

                s.removeCallbacks(apply);
                long wait = 100 - (SystemClock.uptimeMillis() - lastSet[0]);
                if (wait <= 0) apply.run(); else s.postDelayed(apply, wait);
            }
            public void onStartTrackingTouch(SeekBar s) { }
            public void onStopTrackingTouch(SeekBar s) { s.removeCallbacks(apply); apply.run(); }
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
        bar.setMax(200);
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
            Ui.Sheet sn = new Ui.Sheet(a, "Captions", m.title);
            sn.item(R.drawable.ic_add, "Import captions (.srt, .vtt, .ass ...)", Ui.TXT, () -> a.pickCaptions(m));
            sn.item(R.drawable.ic_settings, "Caption settings", Ui.TXT, this::showCapSettings);
            sn.show();
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
        sh.item(R.drawable.ic_settings, "Caption settings", Ui.TXT, this::showCapSettings);
        sh.item(R.drawable.ic_delete, "Remove captions", Ui.RED, () -> {
            a.store.deleteCaps(m.key());
            capsChanged();
            a.toast("Captions removed");
        });
        sh.show();
    }

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

            if (getVisibility() != View.VISIBLE || getWindowVisibility() != View.VISIBLE || caps == null || !a.store.bool("caps_on", true)) {
                if (getWindowVisibility() == View.VISIBLE) capView.setVisibility(View.GONE);
                return;
            }
            String t = caps.at(en.pos() - a.store.p.getInt("cc_delay", 0));
            if (t == null ? capShown != null : !t.equals(capShown)) {
                capShown = t;
                if (t == null) capView.setVisibility(View.GONE);
                else { capView.setText(t); capView.setVisibility(View.VISIBLE); }
            }
            capRun = true;
            postDelayed(this, Engine.SAVER ? 250 : 100);
        }
    };

    void layoutCaps() {
        android.content.SharedPreferences sp = a.store.p;
        FrameLayout.LayoutParams lp = (FrameLayout.LayoutParams) capView.getLayoutParams();
        int pos = sp.getInt("cc_pos", 2), off = Ui.dp(a, sp.getInt("cc_off", 0));
        boolean chrome = controls && !locked && !holding && !sleeping && !pip;
        int g, mt = 0, mb = 0;
        if (pos == 0) { g = Gravity.TOP | Gravity.CENTER_HORIZONTAL; mt = (chrome ? top.getHeight() + Ui.dp(a, 8) : Ui.dp(a, 40)) + off; }
        else if (pos == 1) { g = Gravity.CENTER; mt = off; }
        else { g = Gravity.BOTTOM | Gravity.CENTER_HORIZONTAL; mb = (chrome ? bottom.getHeight() + Ui.dp(a, 8) : Ui.dp(a, 40)) + off; }
        if (pip) { mt = pos == 0 ? Ui.dp(a, 8) : 0; mb = pos == 2 ? Ui.dp(a, 8) : 0; }
        int side = Ui.dp(a, pip ? 6 : 24);
        if (lp.gravity != g || lp.topMargin != mt || lp.bottomMargin != mb || lp.leftMargin != side) {
            lp.gravity = g;
            lp.setMargins(side, mt, side, mb);
            capView.setLayoutParams(lp);
        }
        if (getWidth() > 0) {
            int maxW = Math.max(Ui.dp(a, 80), (getWidth() - 2 * side) * sp.getInt("cc_wid", 100) / 100);
            if (capView.getMaxWidth() != maxW) capView.setMaxWidth(maxW);
        }
    }

    void applyCapStyle() {
        CapSettings.apply(capView, a.store.p, a);
        layoutCaps();
    }

    void showCapSettings() {
        CapSettings.show(a, () -> {
            applyCapStyle();
            capShown = "";
            updateChips();
            startCapLoop();
            if (!a.store.bool("caps_on", true)) capView.setVisibility(View.GONE);
        });
    }

    GradientDrawable scrim(GradientDrawable.Orientation o) {
        int solid = (Ui.BG & 0x00FFFFFF) | 0xF2000000;
        return new GradientDrawable(o, new int[]{solid, Ui.BG & 0x00FFFFFF});
    }

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

    void setPip(boolean on) {
        pip = on;
        if (on) {
            exitSleep();
            endHold();
            top.animate().cancel();
            bottom.animate().cancel();
            top.setVisibility(View.GONE);
            bottom.setVisibility(View.GONE);
        } else {
            applyChrome();
        }
        layoutCaps();
        fit();
    }

    void applyChrome() {
        boolean show = controls && !locked && !pip;
        fade(top, show);
        fade(bottom, show);
        fade(unlock, locked && lockIconShown);
        systemBars(show || pip || getVisibility() != View.VISIBLE || vibeOn());
        layoutCaps();
    }

    /** The vibe visual is laid out around the window size, so the bars stay put while it is showing. */
    boolean vibeOn() { return spectrum instanceof VibeView && spectrum.getVisibility() == View.VISIBLE; }

    /** The status and navigation bars follow the controls: hidden together, back with a swipe from the edge. */
    void systemBars(boolean show) {
        if (sleeping) return;
        try {
            WindowInsetsController c = a.getWindow().getInsetsController();
            if (c == null) return;
            if (show) c.show(WindowInsets.Type.systemBars());
            else {
                c.setSystemBarsBehavior(WindowInsetsController.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE);
                c.hide(WindowInsets.Type.systemBars());
            }
        } catch (Exception e) { }
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
        if ((aw > ah) != land) requestMode(aw > ah);
        layoutCaps();

        int pt = pip ? 0 : top.getHeight(), pb = pip ? 0 : bottom.getHeight();
        int availH = pip ? ah : Math.max(Ui.dp(a, 120), ah - pt - pb);
        int s = pip ? Math.min(aw, ah) : Math.max(Ui.dp(a, 120), Math.min(aw - Ui.dp(a, 48), availH - Ui.dp(a, 16)));
        int tm = pt + (availH - s) / 2;
        FrameLayout.LayoutParams ab = (FrameLayout.LayoutParams) artBox.getLayoutParams();
        int g = Gravity.TOP | Gravity.CENTER_HORIZONTAL;
        if (ab.width != s || ab.height != s || ab.topMargin != tm || ab.gravity != g) {
            ab.width = s; ab.height = s; ab.topMargin = tm; ab.gravity = g;
            artBox.setLayoutParams(ab);
        }
        if (spectrum instanceof VibeView) {
            VibeView vv = (VibeView) spectrum;
            vv.ref = s;
            vv.focusY = tm + s / 2f;
            vv.invalidate();
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
        updateNowPlaying();
        boolean showVideo = m != null && m.video && !en.audioMode;
        sv.setVisibility(showVideo ? View.VISIBLE : View.GONE);
        boolean specOn = a.store.bool("spec_player", false) && !Engine.SAVER;
        boolean vibe = "vibe".equals(a.store.p.getString("viz_mode", "bar"));
        boolean vibeFull = specOn && vibe && m != null;
        artBox.setVisibility(showVideo || vibeFull ? View.GONE : View.VISIBLE);
        String k = m == null ? "" : m.key() + (m.video ? "v" : "a") + (specOn ? (vibe ? "w" : "s") : "");
        if (!k.equals(artKey)) {
            artKey = k;
            artBox.removeAllViews();
            if (spectrum != null && spectrum.getParent() == area) area.removeView(spectrum);
            spectrum = null;
            if (vibeFull) {
                spectrum = new VibeView(a);
                area.addView(spectrum, 0, new FrameLayout.LayoutParams(Ui.MATCH, Ui.MATCH));
                fit();
            } else if (m != null && specOn) {
                spectrum = new Ui.SpectrumView(a);
                artBox.addView(spectrum, new FrameLayout.LayoutParams(Ui.MATCH, Ui.MATCH));
            } else if (m != null) {
                ImageView ic = new ImageView(a);
                Ic.set(ic, m.video ? R.drawable.ic_movie : R.drawable.ic_music);
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
        if (specOn && m != null && !en.spec.permitted()) a.h.post(a::checkSpecPermission);
        if (spectrum != null) {
            if (vibeFull) spectrum.setVisibility(showVideo ? View.GONE : View.VISIBLE);
            if (vibeOn()) systemBars(true);
            spectrum.invalidate();
        }
        Ic.set(play, en.isPlaying() ? R.drawable.ic_pause : R.drawable.ic_play);
        shuf.setColorFilter(en.shuffle ? Ui.ACC : Ui.SUB);
        Ic.set(rep, en.repeat == 2 ? R.drawable.ic_repeat_one : R.drawable.ic_repeat);
        rep.setColorFilter(en.repeat == 0 ? Ui.SUB : Ui.ACC);
        boolean f = m != null && a.store.isFav(m);
        Ic.set(fav, f ? R.drawable.ic_heart_fill : R.drawable.ic_heart);
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
