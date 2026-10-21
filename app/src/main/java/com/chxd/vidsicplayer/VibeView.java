package com.chxd.vidsicplayer;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.LinearGradient;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.PorterDuff;
import android.graphics.PorterDuffXfermode;
import android.graphics.RadialGradient;
import android.graphics.RectF;
import android.graphics.Shader;
import android.os.SystemClock;
import android.view.View;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Random;

/**
 * "Vibe" visual for the player. It does not know the song: it listens to the live spectrum, measures energy, bass,
 * brightness, how fast the sound changes and the beat, scores six moods from that and restyles itself (colors, speed,
 * trails, shake, flashes, gravity of the particles) with smooth transitions.
 */
class VibeView extends View {
    static final int N = Spectrum.BANDS;
    // target feature vector per mood: energy, bass, brightness, flux
    static final float[][] T = {
            {.22f, .40f, .22f, .18f},   // sad
            {.38f, .62f, .32f, .30f},   // moody
            {.48f, .45f, .50f, .32f},   // chill
            {.68f, .62f, .66f, .62f},   // party
            {.90f, .92f, .62f, .82f},   // edm
            {.86f, .62f, .88f, .88f}};  // intense
    // look per mood: hue, spread, sat, light, trail, speed, len, gravity, shake, flash, hueSpeed, burst
    static final float[][] L = {
            {212, 40, 55, 58, .07f, .12f, .9f, .5f, 0, 0, 2, 6},
            {272, 70, 70, 55, .09f, .22f, 1.1f, .05f, .2f, 0, 4, 10},
            {168, 90, 70, 58, .10f, .30f, 1.1f, -.15f, 0, 0, 6, 10},
            {320, 220, 92, 60, .16f, .9f, 1.4f, 0, .5f, .08f, 40, 22},
            {190, 170, 100, 58, .20f, 1.3f, 1.7f, 0, 1, .22f, 70, 34},
            {8, 55, 100, 55, .22f, 1.1f, 1.6f, 0, 1.6f, .12f, 10, 30}};
    static final int MAX_PARTS = 260;

    static class Pt { float x, y, vx, vy, l, d, h, s; }
    static class Ring { float r, a, h; }

    final Engine en;
    final Random rnd = new Random();
    final Paint stroke = new Paint(Paint.ANTI_ALIAS_FLAG), fill = new Paint(Paint.ANTI_ALIAS_FLAG),
            blit = new Paint(Paint.FILTER_BITMAP_FLAG), orbP = new Paint(Paint.ANTI_ALIAS_FLAG);
    final PorterDuffXfermode ADD = new PorterDuffXfermode(PorterDuff.Mode.ADD);
    final RectF dst = new RectF();
    final Path path = new Path();
    final ArrayList<Pt> parts = new ArrayList<>();
    final ArrayList<Ring> rings = new ArrayList<>();
    Bitmap bmp;
    Canvas bc;
    static final float SCALE = 0.5f; // the picture is drawn at half size and scaled up: soft glow, much cheaper

    final float[] P = new float[12];
    final float[] score = new float[6];
    final float[] b = new float[N], prev = new float[N];
    final float[] ints = new float[14];
    int nInts;
    int mood = 1, moodHold, silence;
    float fe, fb, fc = .3f, ff, fluxPeak = .02f, bassAvg, bpm, beat, flash, rot, hueShift;
    long lastBeat;
    boolean beatNow;
    // left / right: how much each speaker drives each band (1 = full). Both 1 when the sound is centred or not analysed (yet).
    final float[] gl = new float[N], gr = new float[N];
    StereoMap map;
    String mapKey = "";
    float ref, focusY; // set by the player: size of the artwork square and the y of its centre (0 = use own size / middle)

    VibeView(Context c) {
        super(c);
        en = Engine.get(c);
        System.arraycopy(L[mood], 0, P, 0, 12);
        Arrays.fill(gl, 1f);
        Arrays.fill(gr, 1f);
        stroke.setStyle(Paint.Style.STROKE);
        stroke.setStrokeCap(Paint.Cap.ROUND);
        stroke.setXfermode(ADD);
        fill.setXfermode(ADD);
    }

    @Override
    protected void onSizeChanged(int w, int h, int ow, int oh) {
        if (w <= 0 || h <= 0) return;
        bmp = Bitmap.createBitmap(Math.max(1, (int) (w * SCALE)), Math.max(1, (int) (h * SCALE)), Bitmap.Config.ARGB_8888);
        bc = new Canvas(bmp);
        bc.scale(SCALE, SCALE);
        bc.drawColor(0xFF05060A);
        parts.clear();
        rings.clear();
    }

    static float clamp(float v, float lo, float hi) { return v < lo ? lo : (v > hi ? hi : v); }

    /** CSS-style hsl(h, s%, l%) with alpha 0..1 -> ARGB. */
    static int hsl(float h, float s, float l, float a) {
        h = ((h % 360f) + 360f) % 360f;
        s = clamp(s / 100f, 0, 1);
        l = clamp(l / 100f, 0, 1);
        float c = (1 - Math.abs(2 * l - 1)) * s, x = c * (1 - Math.abs((h / 60f) % 2 - 1)), m = l - c / 2, r, g, bl;
        switch ((int) (h / 60f)) {
            case 0: r = c; g = x; bl = 0; break;
            case 1: r = x; g = c; bl = 0; break;
            case 2: r = 0; g = c; bl = x; break;
            case 3: r = 0; g = x; bl = c; break;
            case 4: r = x; g = 0; bl = c; break;
            default: r = c; g = 0; bl = x;
        }
        return Color.argb((int) (clamp(a, 0, 1) * 255), (int) ((r + m) * 255), (int) ((g + m) * 255), (int) ((bl + m) * 255));
    }

    /** Starts / switches the background left-right analysis for the playing file, and eases gl / gr toward it. */
    void updatePan(boolean real) {
        Media m = en.cur();
        String key = m == null ? "" : m.key();
        if (!key.equals(mapKey)) {
            stopMap();
            mapKey = key;
            if (real && m != null && m.uri != null) { map = new StereoMap(getContext(), m.uri); map.start(); }
            else mapKey = "";
        }
        int pos = en.pos();
        for (int i = 0; i < N; i++) {
            float rs = map != null ? map.right(pos, i) : -1f, tl = 1f, tr = 1f;
            if (rs >= 0) { tl = Math.min(1f, 2f * (1f - rs)); tr = Math.min(1f, 2f * rs); }
            gl[i] += (tl - gl[i]) * .25f;
            gr[i] += (tr - gr[i]) * .25f;
        }
    }

    void stopMap() {
        if (map != null) { map.cancel(); map = null; }
        mapKey = "";
    }

    @Override
    protected void onDetachedFromWindow() {
        stopMap();
        super.onDetachedFromWindow();
    }

    /** Reads the engine's spectrum into b[] (real FFT when available, a simulated animation otherwise, silence when paused). */
    void fetch(boolean playing, boolean real, double t) {
        for (int i = 0; i < N; i++) {
            if (playing && real) { b[i] = en.spec.bands[i]; continue; }
            float target = 0;
            if (playing) {
                float env = 1f - 0.55f * i / N;
                double a = 0.5 + 0.5 * Math.sin(t * 2.3 + i * 0.7), c = 0.5 + 0.5 * Math.sin(t * 3.7 - i * 1.3 + 1.7);
                target = (float) Math.max(0.05, env * (0.15 + 0.85 * a * c));
            }
            b[i] = target > b[i] ? b[i] + (target - b[i]) * 0.6f : b[i] * 0.88f + target * 0.12f;
        }
    }

    /** Features -> beat -> mood scores. Only runs on real audio. */
    void analyse() {
        float sum = 0, wsum = 0, flux = 0, bass = 0;
        for (int i = 0; i < N; i++) {
            float v = b[i];
            sum += v;
            wsum += v * i;
            float d = v - prev[i];
            if (d > 0) flux += d;
            prev[i] = v;
        }
        for (int i = 0; i < 8; i++) bass += b[i];
        bass /= 8f;
        float mean = sum / N, cent = sum > 0 ? wsum / sum / (N - 1) : 0;
        float rawE = Math.min(1f, mean * 1.8f), rawB = Math.min(1f, bass * 1.1f), rawC = clamp((cent - .12f) / .38f, 0, 1);
        float fl = flux / N;
        fluxPeak = Math.max(Math.max(fluxPeak * .9993f, fl), .004f);
        float rawF = Math.min(1f, fl / fluxPeak);
        if (mean > .02f) {
            float a = .025f;
            fe += (rawE - fe) * a;
            fb += (rawB - fb) * a;
            fc += (rawC - fc) * a;
            ff += (rawF - ff) * a;
            silence = 0;
        } else silence++;

        // beat: bass jumping over its own running average
        bassAvg += (bass - bassAvg) * .04f;
        long now = SystemClock.uptimeMillis();
        if (bass > bassAvg * 1.2f && bass > .22f && now - lastBeat > 240) {
            long dt = now - lastBeat;
            lastBeat = now;
            beat = 1;
            flash = 1;
            beatNow = true;
            if (dt < 1500) {
                if (nInts == ints.length) { System.arraycopy(ints, 1, ints, 0, ints.length - 1); nInts--; }
                ints[nInts++] = dt;
                float[] s = Arrays.copyOf(ints, nInts);
                Arrays.sort(s);
                float bp = 60000f / s[nInts / 2];
                while (bp < 80) bp *= 2;
                while (bp > 180) bp /= 2;
                bpm = bpm == 0 ? bp : bpm + (bp - bpm) * .3f;
            }
        }

        // mood scores
        float[] v = {fe, fb, fc, ff};
        for (int k = 0; k < 6; k++) {
            float d = 0;
            for (int i = 0; i < 4; i++) d += Math.abs(v[i] - T[k][i]) * (i == 0 ? 1.4f : 1f);
            float s = Math.max(0, 1 - d / 1.6f);
            if ((k == 3 || k == 4) && bpm >= 118 && bpm <= 150) s += .08f;
            if ((k == 0 || k == 1) && bpm > 0 && bpm < 95) s += .06f;
            score[k] += (s - score[k]) * .02f;
        }
        moodHold++;
        int best = mood;
        for (int k = 0; k < 6; k++) if (score[k] > score[best] + .025f) best = k;
        if (best != mood && moodHold > 150) { mood = best; moodHold = 0; }
    }

    void spawn(float cx, float cy, float R, int n, float hue, float k) {
        for (int i = 0; i < n && parts.size() < MAX_PARTS; i++) {
            Pt p = new Pt();
            double a = rnd.nextDouble() * 6.2832;
            float sp = (1 + rnd.nextFloat() * 4) * (.4f + P[5]) * k;
            p.x = cx + (float) Math.cos(a) * R;
            p.y = cy + (float) Math.sin(a) * R;
            p.vx = (float) Math.cos(a) * sp;
            p.vy = (float) Math.sin(a) * sp;
            p.l = 1;
            p.d = .006f + rnd.nextFloat() * .014f;
            p.h = hue + rnd.nextFloat() * P[1];
            p.s = (1 + rnd.nextFloat() * 2.5f) * k;
            parts.add(p);
        }
    }

    @Override
    protected void onDraw(Canvas cv) {
        int w = getWidth(), h = getHeight();
        if (w == 0 || h == 0 || bmp == null) return;
        boolean playing = en.isPlaying(), real = en.spec.live() && playing;
        if (en.spec.live()) en.spec.poll();
        double t = SystemClock.uptimeMillis() / 1000.0;
        fetch(playing, real, t);
        if (real) analyse();
        updatePan(real);

        // ease the look toward the current mood
        float[] M = L[mood];
        for (int i = 0; i < 12; i++) P[i] += (M[i] - P[i]) * .025f;
        hueShift += P[10] * .016f;
        beat *= .88f;
        flash *= .9f;

        float base = ref > 0 ? ref : Math.min(w, h);
        float k = base / 600f;
        float cx = w / 2f, cy = focusY > 0 ? focusY : h / 2f, R = base * .14f;
        float bassV = (b[0] + b[1] + b[2] + b[3]) / 4f;
        float hue = (P[0] + hueShift) % 360f;
        float maxB = 0;
        for (int i = 0; i < N; i++) maxB = Math.max(maxB, b[i]);

        // trails: paint the old frame over with a translucent dark tint
        bc.drawColor(hsl(hue, 45, 3 + flash * P[9] * 40, P[4]));

        bc.save();
        if (P[8] > .05f && beat > .2f)
            bc.translate((rnd.nextFloat() - .5f) * beat * P[8] * 10 * k, (rnd.nextFloat() - .5f) * beat * P[8] * 10 * k);
        rot += P[5] * .004f * (1 + bassV * 2);

        // orb (normal blending, under the bars, so it keeps its color instead of piling up to white)
        float orb = R * (.62f + bassV * .28f + beat * .1f);
        orbP.setShader(new RadialGradient(cx, cy, Math.max(1f, orb), new int[]{
                hsl(hue, P[2], P[3] + 8, .45f + beat * .25f), hsl(hue + P[1] * .4f, P[2], P[3] - 6, .18f), 0},
                new float[]{0, .6f, 1}, Shader.TileMode.CLAMP));
        bc.drawCircle(cx, cy, orb, orbP);
        orbP.setShader(null);


        // radial spectrum, mirrored left / right
        final int BN = 128;
        stroke.setStrokeWidth(Math.max(2 * k, R * .035f));
        for (int i = 0; i < BN; i++) {
            int m = i < BN / 2 ? i : BN - i;
            float f = (float) Math.pow(m / (BN / 2f), 1.8) * (N - 1);
            int i0 = (int) f, i1 = Math.min(N - 1, i0 + 1);
            float fr = f - i0, v = b[i0] * (1 - fr) + b[i1] * fr;
            float a = i / (float) BN * 6.2832f + rot, c = (float) Math.cos(a), s = (float) Math.sin(a);
            // left side of the screen listens to the left speaker, right side to the right one (smooth blend near the middle)
            float tt = clamp((c + .3f) / .6f, 0, 1), wr = tt * tt * (3 - 2 * tt);
            int bi = Math.min(N - 1, Math.round(f));
            v *= gl[bi] * (1 - wr) + gr[bi] * wr;
            float len = (v * v * .4f + v * .9f) * R * P[6] * (1 + beat * .4f) + 2 * k;
            float r0 = R * (1 + bassV * .18f);
            float jag = (P[8] > 1 && i % 2 == 0) ? .7f : 1f;
            float hh = hue + m / (BN / 2f) * P[1];
            stroke.setColor(hsl(hh, P[2], P[3], .35f + v * .65f));
            bc.drawLine(cx + c * r0, cy + s * r0, cx + c * (r0 + len * jag), cy + s * (r0 + len * jag), stroke);
            stroke.setColor(hsl(hh, P[2], P[3], (.35f + v * .65f) * .25f));
            bc.drawLine(cx + c * r0, cy + s * r0, cx + c * (r0 - len * .3f), cy + s * (r0 - len * .22f), stroke);
        }

        // waveform ring: the real waveform wrapped around a circle (a smooth circle when calm, like the HTML)
        stroke.setXfermode(null);
        stroke.setStrokeWidth(1.5f * k + .5f);
        stroke.setColor(hsl(hue + P[1] * .5f, P[2], 75, .8f));
        byte[] wv = real ? en.spec.wave : null;
        int step = wv == null ? 0 : Math.max(1, wv.length / 256);
        path.reset();
        for (int i = 0; i <= 256; i++) {
            float wf = 0;
            if (wv != null && (i % 256) * step < wv.length) wf = ((wv[(i % 256) * step] & 0xFF) - 128) / 128f;
            float a = i / 256f * 6.2832f - rot * 2, rr = R * .8f + wf * R * .35f * (.5f + P[6] * .4f);
            float x = cx + (float) Math.cos(a) * rr, y = cy + (float) Math.sin(a) * rr;
            if (i == 0) path.moveTo(x, y); else path.lineTo(x, y);
        }
        bc.drawPath(path, stroke);
        stroke.setXfermode(ADD);

        // beat events: shock ring + particle burst
        if (beatNow) {
            beatNow = false;
            Ring q = new Ring();
            q.r = R; q.a = .8f; q.h = hue;
            rings.add(q);
            spawn(cx, cy, R, Math.round(P[11] * (.6f + fe)), hue, k);
        }
        if (playing && rnd.nextFloat() < .25f * P[5] + .05f && (b[18] > .3f || !real)) spawn(cx, cy, R * 1.4f, 1, hue, k);

        stroke.setStrokeWidth((2 + P[9] * 14) * k);
        for (int i = rings.size() - 1; i >= 0; i--) {
            Ring q = rings.get(i);
            q.r += (6 + P[5] * 8) * k;
            q.a *= .94f;
            if (q.a < .02f) { rings.remove(i); continue; }
            stroke.setColor(hsl(q.h, P[2], 65, q.a));
            bc.drawCircle(cx, cy, q.r, stroke);
        }

        for (int i = parts.size() - 1; i >= 0; i--) {
            Pt p = parts.get(i);
            p.x += p.vx;
            p.y += p.vy + P[7] * (2 - p.l) * 1.2f * k;
            p.vx *= .985f;
            p.vy *= .985f;
            p.l -= p.d;
            if (p.l <= 0) { parts.remove(i); continue; }
            fill.setColor(hsl(p.h, P[2], P[3] + 10, p.l * .9f));
            bc.drawCircle(p.x, p.y, p.s * p.l + .3f * k, fill);
        }
        bc.restore();

        // floor glow that breathes with the bass
        fill.setShader(new LinearGradient(0, h, 0, h * .6f, hsl(hue, P[2], 50, .06f + bassV * .28f), 0, Shader.TileMode.CLAMP));
        bc.drawRect(0, h * .6f, w, h, fill);
        fill.setShader(null);

        dst.set(0, 0, w, h);
        cv.drawBitmap(bmp, null, dst, blit);

        if (playing || !parts.isEmpty() || !rings.isEmpty() || maxB > .01f || beat > .02f) postInvalidateOnAnimation();
    }
}
