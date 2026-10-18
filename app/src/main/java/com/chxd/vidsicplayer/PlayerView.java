package com.chxd.vidsicplayer;

import android.content.Context;
import android.content.res.ColorStateList;
import android.graphics.Color;
import android.graphics.drawable.GradientDrawable;
import android.view.Gravity;
import android.view.SurfaceHolder;
import android.view.SurfaceView;
import android.view.View;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.SeekBar;
import android.widget.TextView;

/**
 * Full-screen player overlay.
 * Tap the screen to hide / show the controls. The lock button hides them and ignores taps
 * until the lock icon is tapped (tap the screen to show / hide that icon).
 */
class PlayerView extends FrameLayout implements Engine.L {
    final MainActivity a;
    final Engine en;
    final ImageView play, shuf, rep, fav;
    final TextView title, sub, cur, max;
    final SeekBar sb;
    final SurfaceView sv;
    final FrameLayout area, artBox;
    final LinearLayout top, bottom;
    final ImageView unlock;
    boolean drag, controls = true, locked, lockIconShown;
    String artKey = "";
    Ui.SpectrumView spectrum; // "Audio Player Spectrum": replaces the thumbnail / note

    PlayerView(MainActivity act) {
        super(act);
        a = act;
        en = act.engine;
        setBackgroundColor(Ui.BG);
        setClickable(true);
        setOnClickListener(v -> onTap());
        en.listen(this);

        // media area fills the whole screen; the controls float above it
        area = new FrameLayout(a);
        artBox = new FrameLayout(a);
        artBox.setBackground(Ui.shape(Ui.SURF2, 24, a));
        artBox.setClipToOutline(true);
        area.addView(artBox, new FrameLayout.LayoutParams(Ui.dp(a, 240), Ui.dp(a, 240), Gravity.CENTER));
        sv = new SurfaceView(a);
        sv.getHolder().addCallback(new SurfaceHolder.Callback() {
            public void surfaceCreated(SurfaceHolder h) { en.setHolder(h); }
            public void surfaceChanged(SurfaceHolder h, int f, int w, int hh) { en.setHolder(h); }
            public void surfaceDestroyed(SurfaceHolder h) { en.setHolder(null); }
        });
        area.addView(sv, new FrameLayout.LayoutParams(Ui.MATCH, Ui.dp(a, 200), Gravity.CENTER));
        sv.setVisibility(View.GONE);
        area.addOnLayoutChangeListener((v, l, t, r, b, ol, ot, orr, ob) -> fit());
        addView(area, new FrameLayout.LayoutParams(Ui.MATCH, Ui.MATCH));

        // ---------- top bar: X ... Now playing ... lock ... 3 dots ----------
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

        top.addOnLayoutChangeListener((v, l, t, r, b, ol, ot, orr, ob) -> fit());
        bottom.addOnLayoutChangeListener((v, l, t, r, b, ol, ot, orr, ob) -> fit());
        changed();
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
        title.setText(m == null ? "" : m.title);
        sub.setText(m == null ? "" : m.artist);
        boolean showVideo = m != null && m.video && !en.audioMode;
        sv.setVisibility(showVideo ? View.VISIBLE : View.GONE);
        artBox.setVisibility(showVideo ? View.GONE : View.VISIBLE);
        boolean specOn = a.store.bool("spec_player", false);
        String k = m == null ? "" : m.key() + (m.video ? "v" : "a") + (specOn ? "s" : "");
        if (!k.equals(artKey)) {
            artKey = k;
            artBox.removeAllViews();
            spectrum = null;
            if (m != null && specOn) {
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
        if (spectrum != null) spectrum.invalidate();
        play.setImageResource(en.isPlaying() ? R.drawable.ic_pause : R.drawable.ic_play);
        shuf.setColorFilter(en.shuffle ? Ui.ACC : Ui.SUB);
        rep.setImageResource(en.repeat == 2 ? R.drawable.ic_repeat_one : R.drawable.ic_repeat);
        rep.setColorFilter(en.repeat == 0 ? Ui.SUB : Ui.ACC);
        boolean f = m != null && a.store.isFav(m);
        fav.setImageResource(f ? R.drawable.ic_heart_fill : R.drawable.ic_heart);
        fav.setColorFilter(f ? Ui.ACC : Ui.TXT);
        sb.setMax(Math.max(1, en.dur()));
        max.setText(Ui.fmt(en.dur()));
        fit();
        tick();
    }

    void tick() {
        if (drag) return;
        sb.setProgress(en.pos());
        cur.setText(Ui.fmt(en.pos()));
    }
}
