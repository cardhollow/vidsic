package com.chxd.vidsicplayer;

import java.io.IOException;
import java.io.OutputStream;
import java.util.Arrays;

final class GifEncoder {
    private final OutputStream out;
    private final int w, h;
    private boolean first = true;
    private final int[] cache = new int[32768];

    GifEncoder(OutputStream out, int w, int h) {
        this.out = out;
        this.w = w;
        this.h = h;
    }

    private void s(int v) throws IOException { out.write(v & 0xFF); out.write((v >> 8) & 0xFF); }

    private void header() throws IOException {
        out.write("GIF89a".getBytes("US-ASCII"));
        s(w);
        s(h);
        out.write(0x70);
        out.write(0);
        out.write(0);

        out.write(0x21); out.write(0xFF); out.write(11);
        out.write("NETSCAPE2.0".getBytes("US-ASCII"));
        out.write(3); out.write(1); s(0); out.write(0);
    }

    void addFrame(int[] px, int delayCs) throws IOException {
        if (first) { header(); first = false; }
        byte[] pal = new byte[768];
        palette(px, pal);
        Arrays.fill(cache, -1);
        byte[] idx = new byte[px.length];
        for (int i = 0; i < px.length; i++) {
            int c = px[i];
            int r = (c >> 16) & 0xFF, g = (c >> 8) & 0xFF, b = c & 0xFF;
            int key = ((r >> 3) << 10) | ((g >> 3) << 5) | (b >> 3);
            int v = cache[key];
            if (v < 0) {
                int best = 0, bd = Integer.MAX_VALUE;
                for (int k = 0; k < 256; k++) {
                    int dr = r - (pal[k * 3] & 0xFF), dg = g - (pal[k * 3 + 1] & 0xFF), db = b - (pal[k * 3 + 2] & 0xFF);
                    int d = dr * dr * 3 + dg * dg * 4 + db * db * 2;
                    if (d < bd) { bd = d; best = k; if (d == 0) break; }
                }
                v = best;
                cache[key] = v;
            }
            idx[i] = (byte) v;
        }

        out.write(0x21); out.write(0xF9); out.write(4);
        out.write(0x04);
        s(Math.max(2, delayCs));
        out.write(0); out.write(0);

        out.write(0x2C);
        s(0); s(0); s(w); s(h);
        out.write(0x87);
        out.write(pal);
        out.write(8);
        lzw(idx);
        out.write(0);
    }

    void finish() throws IOException {
        if (first) return;
        out.write(0x3B);
        out.flush();
    }

    private void palette(int[] px, byte[] pal) {
        int step = Math.max(1, px.length / 6000), n = 0;
        int[] samp = new int[px.length / step + 1];
        for (int i = 0; i < px.length; i += step) samp[n++] = px[i] & 0xFFFFFF;
        int[][] box = new int[256][2];
        int boxes = 1;
        box[0][0] = 0;
        box[0][1] = n;
        while (boxes < 256) {
            int bi = -1, bestSpan = 0;
            for (int i = 0; i < boxes; i++) {
                if (box[i][1] - box[i][0] < 2) continue;
                int span = range(samp, box[i][0], box[i][1])[3];
                if (span > bestSpan) { bestSpan = span; bi = i; }
            }
            if (bi < 0 || bestSpan == 0) break;
            int[] r = range(samp, box[bi][0], box[bi][1]);
            final int ch = r[4];
            sort(samp, box[bi][0], box[bi][1], ch);
            int mid = (box[bi][0] + box[bi][1]) / 2;
            box[boxes][0] = mid;
            box[boxes][1] = box[bi][1];
            box[bi][1] = mid;
            boxes++;
        }
        for (int i = 0; i < 256; i++) {
            if (i < boxes && box[i][1] > box[i][0]) {
                long sr = 0, sg = 0, sb = 0;
                int cnt = box[i][1] - box[i][0];
                for (int k = box[i][0]; k < box[i][1]; k++) {
                    sr += (samp[k] >> 16) & 0xFF;
                    sg += (samp[k] >> 8) & 0xFF;
                    sb += samp[k] & 0xFF;
                }
                pal[i * 3] = (byte) (sr / cnt);
                pal[i * 3 + 1] = (byte) (sg / cnt);
                pal[i * 3 + 2] = (byte) (sb / cnt);
            } else {
                pal[i * 3] = pal[i * 3 + 1] = pal[i * 3 + 2] = 0;
            }
        }
    }

    private static int[] range(int[] a, int s, int e) {
        int rl = 255, rh = 0, gl = 255, gh = 0, bl = 255, bh = 0;
        for (int i = s; i < e; i++) {
            int c = a[i], r = (c >> 16) & 0xFF, g = (c >> 8) & 0xFF, b = c & 0xFF;
            if (r < rl) rl = r; if (r > rh) rh = r;
            if (g < gl) gl = g; if (g > gh) gh = g;
            if (b < bl) bl = b; if (b > bh) bh = b;
        }
        int dr = rh - rl, dg = gh - gl, db = bh - bl;
        int ch = 0, span = dr;
        if (dg > span) { span = dg; ch = 1; }
        if (db > span) { span = db; ch = 2; }
        return new int[]{rl, gl, bl, span, ch};
    }

    private static void sort(int[] a, int s, int e, int ch) {
        final int shift = ch == 0 ? 16 : ch == 1 ? 8 : 0;
        long[] t = new long[e - s];
        for (int i = s; i < e; i++) t[i - s] = ((long) ((a[i] >> shift) & 0xFF) << 32) | (a[i] & 0xFFFFFFFFL);
        Arrays.sort(t);
        for (int i = s; i < e; i++) a[i] = (int) (t[i - s] & 0xFFFFFF);
    }

    private static final int HSIZE = 8192, MAXMAX = 4096;
    private final int[] hKey = new int[HSIZE], hVal = new int[HSIZE];
    private int nBits, maxcode, freeEnt, curAcc, curBits, blockLen;
    private boolean clearFlg;
    private final byte[] block = new byte[255];

    private void lzw(byte[] px) throws IOException {
        Arrays.fill(hKey, -1);
        nBits = 9;
        maxcode = 511;
        freeEnt = 258;
        curAcc = 0;
        curBits = 0;
        blockLen = 0;
        clearFlg = false;
        emit(256);
        int ent = px[0] & 0xFF;
        for (int i = 1; i < px.length; i++) {
            int c = px[i] & 0xFF, key = (ent << 8) | c;
            int hi = (int) ((key * 2654435761L >>> 19) & (HSIZE - 1));
            boolean found = false;
            while (hKey[hi] != -1) {
                if (hKey[hi] == key) { found = true; break; }
                hi = (hi + 1) & (HSIZE - 1);
            }
            if (found) { ent = hVal[hi]; continue; }
            emit(ent);
            ent = c;
            if (freeEnt < MAXMAX) {
                hKey[hi] = key;
                hVal[hi] = freeEnt++;
            } else {
                Arrays.fill(hKey, -1);
                freeEnt = 258;
                clearFlg = true;
                emit(256);
            }
        }
        emit(ent);
        emit(257);
        if (curBits > 0) pushByte(curAcc & 0xFF);
        flushBlock();
    }

    private void emit(int code) throws IOException {
        curAcc |= code << curBits;
        curBits += nBits;
        while (curBits >= 8) {
            pushByte(curAcc & 0xFF);
            curAcc >>>= 8;
            curBits -= 8;
        }
        if (freeEnt > maxcode || clearFlg) {
            if (clearFlg) {
                nBits = 9;
                maxcode = 511;
                clearFlg = false;
            } else {
                nBits++;
                maxcode = nBits == 12 ? MAXMAX : (1 << nBits) - 1;
            }
        }
    }

    private void pushByte(int b) throws IOException {
        block[blockLen++] = (byte) b;
        if (blockLen == 255) flushBlock();
    }

    private void flushBlock() throws IOException {
        if (blockLen > 0) {
            out.write(blockLen);
            out.write(block, 0, blockLen);
            blockLen = 0;
        }
    }
}
