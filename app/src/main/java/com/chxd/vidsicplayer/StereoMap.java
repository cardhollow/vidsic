package com.chxd.vidsicplayer;

import android.content.Context;
import android.media.MediaCodec;
import android.media.MediaExtractor;
import android.media.MediaFormat;
import android.net.Uri;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.ShortBuffer;

/**
 * Android's Visualizer only hands out a mono mix, so to know what the LEFT and the RIGHT speaker are doing this
 * decodes the playing file in the background (much faster than real time) and stores, 30 times a second and for each of
 * the {@link Spectrum#BANDS} bands, how the energy is split between left and right (0 = all left, 255 = all right).
 * The Vibe visual then pushes each side of its circle with its own channel. Nothing is recorded or kept after the song.
 */
class StereoMap {
    static final int FPS = 30, FFT = 1024, BANDS = Spectrum.BANDS;

    final Uri uri;
    final Context ctx;
    volatile byte[] pan = new byte[0];  // frame * BANDS + band
    volatile int filled;                // frames ready so far
    volatile boolean stop, done, failed;
    Thread th;

    StereoMap(Context c, Uri u) { ctx = c.getApplicationContext(); uri = u; }

    void start() {
        th = new Thread(this::run, "stereo-map");
        th.setPriority(Thread.MIN_PRIORITY);
        th.start();
    }

    void cancel() { stop = true; }

    /** Right-channel share (0..1) of a band at a playback position, or -1 when that part is not analysed (yet). */
    float right(int ms, int band) {
        int f = (int) ((long) ms * FPS / 1000);
        byte[] p = pan;
        if (f < 0 || f >= filled || (f + 1) * BANDS > p.length) return -1f;
        return (p[f * BANDS + band] & 0xFF) / 255f;
    }

    void run() {
        MediaExtractor ex = new MediaExtractor();
        MediaCodec codec = null;
        try {
            ex.setDataSource(ctx, uri, null);
            int track = -1;
            MediaFormat fmt = null;
            for (int i = 0; i < ex.getTrackCount(); i++) {
                MediaFormat f = ex.getTrackFormat(i);
                String mime = f.getString(MediaFormat.KEY_MIME);
                if (mime != null && mime.startsWith("audio/")) { track = i; fmt = f; break; }
            }
            if (track < 0) { failed = true; return; }
            ex.selectTrack(track);
            long durUs = fmt.containsKey(MediaFormat.KEY_DURATION) ? fmt.getLong(MediaFormat.KEY_DURATION) : 0;
            int maxFrames = (int) Math.min(60L * 60 * FPS, (durUs > 0 ? durUs / 1000000L : 600L) * FPS + FPS * 5);
            byte[] out = new byte[maxFrames * BANDS];
            codec = MediaCodec.createDecoderByType(fmt.getString(MediaFormat.KEY_MIME));
            codec.configure(fmt, null, null, 0);
            codec.start();

            int sr = fmt.containsKey(MediaFormat.KEY_SAMPLE_RATE) ? fmt.getInteger(MediaFormat.KEY_SAMPLE_RATE) : 44100;
            int ch = fmt.containsKey(MediaFormat.KEY_CHANNEL_COUNT) ? fmt.getInteger(MediaFormat.KEY_CHANNEL_COUNT) : 2;
            int hop = Math.max(FFT / 2, sr / FPS);
            float[] l = new float[FFT], r = new float[FFT], im = new float[FFT];
            float[] win = new float[FFT];
            for (int i = 0; i < FFT; i++) win[i] = (float) (0.5 - 0.5 * Math.cos(2 * Math.PI * i / (FFT - 1)));
            float[] mL = new float[FFT / 2], mR = new float[FFT / 2];
            int phase = 0, frame = 0;

            MediaCodec.BufferInfo info = new MediaCodec.BufferInfo();
            boolean inDone = false, outDone = false;
            while (!outDone && !stop && frame < maxFrames) {
                if (!inDone) {
                    int ii = codec.dequeueInputBuffer(5000);
                    if (ii >= 0) {
                        ByteBuffer ib = codec.getInputBuffer(ii);
                        int n = ib == null ? -1 : ex.readSampleData(ib, 0);
                        if (n < 0) { codec.queueInputBuffer(ii, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM); inDone = true; }
                        else { codec.queueInputBuffer(ii, 0, n, ex.getSampleTime(), 0); ex.advance(); }
                    }
                }
                int oi = codec.dequeueOutputBuffer(info, 5000);
                if (oi == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                    MediaFormat of = codec.getOutputFormat();
                    if (of.containsKey(MediaFormat.KEY_CHANNEL_COUNT)) ch = of.getInteger(MediaFormat.KEY_CHANNEL_COUNT);
                    if (of.containsKey(MediaFormat.KEY_SAMPLE_RATE)) { sr = of.getInteger(MediaFormat.KEY_SAMPLE_RATE); hop = Math.max(FFT / 2, sr / FPS); }
                } else if (oi >= 0) {
                    ByteBuffer ob = codec.getOutputBuffer(oi);
                    if (ob != null && info.size > 0) {
                        ob.position(info.offset);
                        ob.limit(info.offset + info.size);
                        ShortBuffer sb = ob.order(ByteOrder.nativeOrder()).asShortBuffer();
                        int total = sb.remaining() / Math.max(1, ch);
                        for (int s = 0; s < total && frame < maxFrames; s++) {
                            float a = sb.get() / 32768f, b = a;
                            if (ch >= 2) { b = sb.get() / 32768f; for (int x = 2; x < ch; x++) sb.get(); }
                            if (phase < FFT) { l[phase] = a; r[phase] = b; }
                            phase++;
                            if (phase >= hop) {
                                for (int i = phase < FFT ? phase : FFT; i < FFT; i++) { l[i] = 0; r[i] = 0; }
                                mags(l, win, im, mL);
                                mags(r, win, im, mR);
                                fold(mL, mR, out, frame * BANDS);
                                frame++;
                                filled = frame;
                                pan = out;
                                phase = 0;
                            }
                        }
                    }
                    codec.releaseOutputBuffer(oi, false);
                    if ((info.flags & MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0) outDone = true;
                }
            }
            done = true;
        } catch (Throwable e) {
            failed = true;
        } finally {
            try { if (codec != null) { codec.stop(); codec.release(); } } catch (Throwable e) { }
            try { ex.release(); } catch (Throwable e) { }
        }
    }

    /** Hann-windowed 1024 point FFT magnitudes (bins 0 .. 511) of x. im is scratch space. */
    static void mags(float[] x, float[] win, float[] im, float[] mag) {
        int n = FFT;
        float[] re = new float[n];
        for (int i = 0; i < n; i++) { re[i] = x[i] * win[i]; im[i] = 0; }
        for (int i = 1, j = 0; i < n; i++) { // bit reversal
            int bit = n >> 1;
            for (; (j & bit) != 0; bit >>= 1) j ^= bit;
            j ^= bit;
            if (i < j) { float t = re[i]; re[i] = re[j]; re[j] = t; }
        }
        for (int len = 2; len <= n; len <<= 1) {
            double ang = -2 * Math.PI / len;
            float wr = (float) Math.cos(ang), wi = (float) Math.sin(ang);
            for (int i = 0; i < n; i += len) {
                float cr = 1, ci = 0;
                for (int k = 0; k < len / 2; k++) {
                    int a = i + k, b = i + k + len / 2;
                    float tr = re[b] * cr - im[b] * ci, ti = re[b] * ci + im[b] * cr;
                    re[b] = re[a] - tr; im[b] = im[a] - ti;
                    re[a] += tr; im[a] += ti;
                    float nr = cr * wr - ci * wi;
                    ci = cr * wi + ci * wr;
                    cr = nr;
                }
            }
        }
        for (int i = 0; i < n / 2; i++) mag[i] = (float) Math.sqrt(re[i] * re[i] + im[i] * im[i]);
    }

    /** Same log-spaced bands as {@link Spectrum#fold}; stores the right share of each band as a byte (128 = centred / silent). */
    static void fold(float[] mL, float[] mR, byte[] out, int off) {
        int bins = mL.length;
        double lo = 1, hi = bins * 0.62;
        for (int b = 0; b < BANDS; b++) {
            int from = (int) Math.round(lo * Math.pow(hi / lo, b / (double) BANDS));
            int to = (int) Math.round(lo * Math.pow(hi / lo, (b + 1) / (double) BANDS));
            if (to <= from) to = from + 1;
            double pl = 0, pr = 0;
            for (int k = from; k < to && k < bins; k++) { pl = Math.max(pl, mL[k]); pr = Math.max(pr, mR[k]); }
            int v = 128;
            if (pl + pr > 0.02) v = (int) Math.round(pr / (pl + pr) * 255);
            out[off + b] = (byte) v;
        }
    }
}
