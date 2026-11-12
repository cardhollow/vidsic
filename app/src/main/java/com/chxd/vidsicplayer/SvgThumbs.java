package com.chxd.vidsicplayer;

import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.view.View;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.FrameLayout;
import android.widget.ImageView;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.util.ArrayDeque;

final class SvgThumbs {
    static class Job {
        Photo p;
        int px;
        ImageView iv;
        String ck;
        boolean done;
    }

    final MainActivity a;
    WebView wv;
    final ArrayDeque<Job> queue = new ArrayDeque<>();
    boolean busy;
    int size;
    Job cur;
    int token;

    SvgThumbs(MainActivity act) { a = act; }

    void request(Photo p, int px, ImageView iv, String ck) {
        Job j = new Job();
        j.p = p;
        j.px = px;
        j.iv = iv;
        j.ck = ck;
        queue.add(j);
        if (!busy) next();
    }

    private void ensure() {
        if (wv != null) {
            if (wv.getParent() != a.root) {
                if (wv.getParent() instanceof android.view.ViewGroup) ((android.view.ViewGroup) wv.getParent()).removeView(wv);
                a.root.addView(wv, 0, new FrameLayout.LayoutParams(size, size));
            }
            return;
        }
        try {
            size = Ui.dp(a, 160);
            wv = new WebView(a);
            WebSettings ws = wv.getSettings();
            ws.setJavaScriptEnabled(false);
            ws.setAllowFileAccess(false);
            ws.setAllowContentAccess(false);
            ws.setBlockNetworkLoads(true);
            wv.setVerticalScrollBarEnabled(false);
            wv.setHorizontalScrollBarEnabled(false);
            wv.setBackgroundColor(0xFFFFFFFF);
            wv.setLayerType(View.LAYER_TYPE_SOFTWARE, null);
            wv.setAlpha(0.01f);
            wv.setWebViewClient(new WebViewClient() {
                @Override public void onPageFinished(WebView v, String url) {
                    final int t = token;
                    v.postDelayed(() -> { if (t == token) capture(); }, 160);
                }
            });
            a.root.addView(wv, 0, new FrameLayout.LayoutParams(size, size));
        } catch (Throwable t) { wv = null; }
    }

    private void next() {
        while (true) {
            cur = queue.poll();
            if (cur == null) { busy = false; return; }
            if (!cur.p.key().equals(cur.iv.getTag())) continue;
            Bitmap hit = Ui.PCACHE.get(cur.ck);
            if (hit != null) { cur.iv.setImageBitmap(hit); continue; }
            break;
        }
        busy = true;
        ensure();
        if (wv == null) { fail(); return; }
        final Job j = cur;
        final int t = ++token;
        Ui.EX.execute(() -> {
            String html = null;
            try (InputStream in = a.getContentResolver().openInputStream(j.p.uri)) {
                ByteArrayOutputStream bo = new ByteArrayOutputStream();
                byte[] buf = new byte[16384];
                int n, total = 0;
                while ((n = in.read(buf)) > 0 && total < 4 * 1024 * 1024) { bo.write(buf, 0, n); total += n; }
                String b64 = android.util.Base64.encodeToString(bo.toByteArray(), android.util.Base64.NO_WRAP);
                html = "<html><head><meta name='viewport' content='width=device-width, initial-scale=1'>"
                        + "<style>html,body{margin:0;height:100%;background:#fff;overflow:hidden}"
                        + "img{position:absolute;left:0;top:0;width:100%;height:100%;object-fit:contain}</style></head>"
                        + "<body><img src='data:image/svg+xml;base64," + b64 + "'></body></html>";
            } catch (Throwable e) { }
            final String h = html;
            Ui.H.post(() -> {
                if (t != token || wv == null) return;
                if (h == null) { fail(); return; }
                wv.loadDataWithBaseURL("about:blank", h, "text/html", "UTF-8", null);
                wv.postDelayed(() -> { if (t == token && busy && cur == j && !j.done) capture(); }, 3000);
            });
        });
    }

    private void capture() {
        final Job j = cur;
        if (j == null || j.done) return;
        j.done = true;
        try {
            Bitmap bm = Bitmap.createBitmap(Math.max(1, wv.getWidth()), Math.max(1, wv.getHeight()), Bitmap.Config.ARGB_8888);
            wv.draw(new Canvas(bm));
            if (j.px > 0 && j.px < bm.getWidth()) bm = Bitmap.createScaledBitmap(bm, j.px, j.px, true);
            Ui.PCACHE.put(j.ck, bm);
            if (j.p.key().equals(j.iv.getTag())) j.iv.setImageBitmap(bm);
        } catch (Throwable e) { tile(j); }
        token++;
        wv.loadUrl("about:blank");
        next();
    }

    private void fail() {
        if (cur != null) tile(cur);
        next();
    }

    private void tile(Job j) {
        Bitmap t = Ui.svgTile(j.px);
        Ui.PCACHE.put(j.ck, t);
        if (j.p.key().equals(j.iv.getTag())) j.iv.setImageBitmap(t);
    }
}
