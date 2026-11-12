package com.chxd.vidsicplayer;

import android.content.pm.PackageManager;
import android.media.audiofx.Visualizer;
import android.os.SystemClock;

class Spectrum {
    static final int BANDS = 32;

    final Engine en;
    Visualizer viz;
    int session = -1;
    boolean failed;
    boolean uiVisible = true, suspended, enabled;

    final Runnable letGo = () -> { if (!uiVisible) { suspended = true; release(); } };

    void setUiVisible(boolean v) {
        uiVisible = v;
        en.handler.removeCallbacks(letGo);
        if (v) { suspended = false; failed = false; update(); }
        else {

            try { if (viz != null && enabled) { viz.setEnabled(false); enabled = false; } } catch (Exception e) { }
            en.handler.postDelayed(letGo, 60000);
        }
    }

    void retry() { failed = false; if (viz == null) session = -1; }
    final float[] bands = new float[BANDS];
    byte[] fft, wave;
    long last;

    Spectrum(Engine e) { en = e; }

    boolean wanted() {
        return !Engine.SAVER && (en.store.bool("spec_sync", false) || en.store.bool("spec_player", false));
    }

    boolean permitted() {
        return en.ctx.checkSelfPermission("android.permission.RECORD_AUDIO") == PackageManager.PERMISSION_GRANTED;
    }

    boolean live() { return viz != null; }

    void update() {
        android.media.MediaPlayer sp = en.sound();
        if (suspended || !wanted() || !permitted() || sp == null || !en.prepared) { release(); return; }
        int id;
        try { id = sp.getAudioSessionId(); } catch (Exception e) { release(); return; }
        if (viz == null || id != session) {
            if (failed && id == session) return;
            release();
            try {
                Visualizer v = new Visualizer(id);
                v.setCaptureSize(Visualizer.getCaptureSizeRange()[1]);
                v.setEnabled(true);
                viz = v;
                enabled = true;
                session = id;
                failed = false;
                fft = new byte[v.getCaptureSize()];
                wave = new byte[v.getCaptureSize()];
            } catch (Exception e) {
                viz = null;
                session = id;
                failed = true;
            }
        }
        try {
            boolean on = en.isPlaying() && uiVisible;
            if (viz != null && on != enabled) { viz.setEnabled(on); enabled = on; }
        } catch (Exception e) { }
    }

    void release() {
        if (viz != null) {
            try { viz.setEnabled(false); viz.release(); } catch (Exception e) { }
            viz = null;
        }
        session = -1;
        for (int i = 0; i < BANDS; i++) bands[i] = 0;
    }

    void poll() {
        long now = SystemClock.uptimeMillis();
        if (now - last < 25) return;
        last = now;
        boolean got = false;
        if (viz != null && en.isPlaying()) {
            try {
                if (viz.getFft(fft) == Visualizer.SUCCESS) { fold(); got = true; }
                if (got && wave != null) viz.getWaveForm(wave);
            } catch (Exception e) { }
        }
        if (!got) for (int i = 0; i < BANDS; i++) bands[i] *= 0.82f;
    }

    void fold() {
        int bins = fft.length / 2;
        double lo = 1, hi = bins * 0.62;
        for (int b = 0; b < BANDS; b++) {
            int from = (int) Math.round(lo * Math.pow(hi / lo, b / (double) BANDS));
            int to = (int) Math.round(lo * Math.pow(hi / lo, (b + 1) / (double) BANDS));
            if (to <= from) to = from + 1;
            double peak = 0;
            for (int k = from; k < to && k < bins; k++) {
                double re = fft[2 * k], im = fft[2 * k + 1];
                peak = Math.max(peak, Math.sqrt(re * re + im * im));
            }

            double v = Math.log10(1 + peak) / 2.1 * (0.85 + 0.45 * b / BANDS);
            float t = (float) Math.max(0, Math.min(1, v));
            bands[b] = t > bands[b] ? bands[b] + (t - bands[b]) * 0.75f : bands[b] * 0.86f + t * 0.14f;
        }
    }

    float level(int i, int n) {
        int from = i * BANDS / n, to = Math.max(from + 1, (i + 1) * BANDS / n);
        float m = 0;
        for (int k = from; k < to; k++) m = Math.max(m, bands[k]);
        return m;
    }
}
