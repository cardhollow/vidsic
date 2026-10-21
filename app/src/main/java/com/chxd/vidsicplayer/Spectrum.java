package com.chxd.vidsicplayer;

import android.content.pm.PackageManager;
import android.media.audiofx.Visualizer;
import android.os.SystemClock;

/**
 * Real audio spectrum of whatever the engine is playing (android.media.audiofx.Visualizer).
 * Android requires the RECORD_AUDIO permission for this; nothing is recorded, only the FFT of the
 * output is read. When it is not available the UI falls back to a simulated animation.
 */
class Spectrum {
    static final int BANDS = 32;

    final Engine en;
    Visualizer viz;
    int session = -1;
    boolean failed;
    boolean uiVisible = true, suspended, enabled;
    // Creating / releasing a Visualizer makes the audio glitch on many devices, so it is NOT done on every
    // screen-off / app-exit: it is only let go after the app has been out of sight for a minute.
    final Runnable letGo = () -> { if (!uiVisible) { suspended = true; release(); } };

    void setUiVisible(boolean v) {
        uiVisible = v;
        en.handler.removeCallbacks(letGo);
        if (v) { if (suspended) { suspended = false; update(); } }
        else en.handler.postDelayed(letGo, 60000);
    }
    final float[] bands = new float[BANDS];
    byte[] fft, wave; // wave: raw waveform (unsigned bytes centered on 128), used by the Vibe visual
    long last;

    Spectrum(Engine e) { en = e; }

    boolean wanted() {
        return en.store.bool("spec_sync", false) || en.store.bool("spec_player", false);
    }

    boolean permitted() {
        return en.ctx.checkSelfPermission("android.permission.RECORD_AUDIO") == PackageManager.PERMISSION_GRANTED;
    }

    /** True while real FFT data is being delivered. */
    boolean live() { return viz != null; }

    /** Called whenever the engine state changes: (re)attaches to the current player, or lets go. */
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
            boolean on = en.isPlaying();
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

    /** Pulls fresh FFT data (at most ~40 times a second, however many views ask). */
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

    /** FFT bytes -> BANDS log-spaced, smoothed levels in 0..1. */
    void fold() {
        int bins = fft.length / 2; // bin k: real = fft[2k], imag = fft[2k+1] (k=0 packs DC / Nyquist)
        double lo = 1, hi = bins * 0.62; // up to roughly 13 kHz at 44.1 kHz, where music stops mattering
        for (int b = 0; b < BANDS; b++) {
            int from = (int) Math.round(lo * Math.pow(hi / lo, b / (double) BANDS));
            int to = (int) Math.round(lo * Math.pow(hi / lo, (b + 1) / (double) BANDS));
            if (to <= from) to = from + 1;
            double peak = 0;
            for (int k = from; k < to && k < bins; k++) {
                double re = fft[2 * k], im = fft[2 * k + 1];
                peak = Math.max(peak, Math.sqrt(re * re + im * im));
            }
            // log scale + a little tilt so the (naturally weaker) highs show up
            double v = Math.log10(1 + peak) / 2.1 * (0.85 + 0.45 * b / BANDS);
            float t = (float) Math.max(0, Math.min(1, v));
            bands[b] = t > bands[b] ? bands[b] + (t - bands[b]) * 0.75f : bands[b] * 0.86f + t * 0.14f;
        }
    }

    /** Level 0..1 of bar i out of n (n <= BANDS). */
    float level(int i, int n) {
        int from = i * BANDS / n, to = Math.max(from + 1, (i + 1) * BANDS / n);
        float m = 0;
        for (int k = from; k < to; k++) m = Math.max(m, bands[k]);
        return m;
    }
}
