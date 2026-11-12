package com.chxd.vidsicplayer;

import android.content.Context;
import android.content.res.ColorStateList;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.ColorFilter;
import android.graphics.Matrix;
import android.content.res.XmlResourceParser;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.PixelFormat;
import android.graphics.PorterDuff;
import android.graphics.PorterDuffColorFilter;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.Icon;
import android.util.Xml;
import android.widget.ImageView;

import org.xmlpull.v1.XmlPullParser;

import java.io.File;
import java.io.FileOutputStream;
import java.io.StringReader;
import java.lang.reflect.Field;
import java.nio.charset.StandardCharsets;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Central place for every icon in the app.
 *
 * - list(): every ic_*.xml in res/drawable, found by reflection, so a new file is picked up automatically.
 * - get()/set(): use the user's override when the feature is on, otherwise the built-in drawable.
 * - parse(): validates an uploaded Android vector XML and turns it into something drawable.
 */
final class Ic {
    private Ic() { }

    static final String PREF_ON = "ovd_on";
    static final String PREFIX = "ic_";
    /** Not customizable: the launcher icon is drawn by the home screen, not by the app. */
    static final String[] SKIP = {"ic_launcher"};
    static final int MAX_BYTES = 128 * 1024;

    /** Bumped whenever overrides change so cached notifications / bitmaps know to redraw. */
    static volatile int rev;

    static final class Entry {
        final String name;
        final int id;
        Entry(String n, int i) { name = n; id = i; }
    }

    private static final Map<String, Model> MODELS = new HashMap<>();
    private static final Map<Integer, String> NAMES = new HashMap<>();
    private static boolean loaded;

    // ---------------------------------------------------------------- discovery

    static List<Entry> list(Context c) {
        List<Entry> out = new ArrayList<>();
        for (Field f : R.drawable.class.getFields()) {
            String n = f.getName();
            if (!n.startsWith(PREFIX) || skipped(n)) continue;
            try {
                int id = f.getInt(null);
                android.util.TypedValue tv = new android.util.TypedValue();
                c.getResources().getValue(id, tv, true);
                if (tv.string == null || !tv.string.toString().endsWith(".xml")) continue;
                out.add(new Entry(n, id));
            } catch (Exception e) { }
        }
        Collections.sort(out, (a, b) -> a.name.compareTo(b.name));
        return out;
    }

    static boolean skipped(String n) {
        for (String s : SKIP) if (n.startsWith(s)) return true;
        return false;
    }

    static String nameOf(Context c, int res) {
        synchronized (MODELS) {
            String n = NAMES.get(res);
            if (n == null) {
                try { n = c.getResources().getResourceEntryName(res); } catch (Exception e) { n = ""; }
                NAMES.put(res, n);
            }
            return n;
        }
    }

    // ---------------------------------------------------------------- storage

    static boolean enabled(Context c) {
        try { return Engine.get(c.getApplicationContext()).store.bool(PREF_ON, false); } catch (Exception e) { return false; }
    }

    static File dir(Context c) {
        File d = new File(c.getFilesDir(), "icons");
        if (!d.isDirectory()) d.mkdirs();
        return d;
    }

    static File file(Context c, String name) { return new File(dir(c), name + ".xml"); }

    static String readSaved(Context c, String name) {
        File f = file(c, name);
        if (!f.isFile()) return null;
        try { return new String(java.nio.file.Files.readAllBytes(f.toPath()), StandardCharsets.UTF_8); } catch (Exception e) { return null; }
    }

    static boolean hasSaved(Context c, String name) { return file(c, name).isFile(); }

    static void save(Context c, String name, String xml) throws Exception {
        try (FileOutputStream o = new FileOutputStream(file(c, name))) { o.write(xml.getBytes(StandardCharsets.UTF_8)); }
    }

    static void delete(Context c, String name) { file(c, name).delete(); }

    /** Forget everything cached so the next draw picks up changes. */
    static void invalidate() {
        synchronized (MODELS) { MODELS.clear(); loaded = false; }
        rev++;
        WidgetCovers.clearGlyphs();
    }

    private static void ensure(Context c) {
        synchronized (MODELS) {
            if (loaded) return;
            loaded = true;
            MODELS.clear();
            File[] fs = dir(c).listFiles();
            if (fs == null) return;
            for (File f : fs) {
                String n = f.getName();
                if (!n.endsWith(".xml")) continue;
                n = n.substring(0, n.length() - 4);
                String t = readSaved(c, n);
                if (t == null) continue;
                try { MODELS.put(n, parse(t)); } catch (Exception e) { }
            }
        }
    }

    // ---------------------------------------------------------------- drawing

    /** The drawable to show for res: the user's version when overriding is on, else the built-in one. */
    static Drawable get(Context c, int res) {
        if (enabled(c)) {
            ensure(c);
            Model m;
            synchronized (MODELS) { m = MODELS.get(nameOf(c, res)); }
            if (m != null) return new Vec(m, c.getResources().getDisplayMetrics().density);
        }
        return c.getDrawable(res);
    }

    static void set(ImageView v, int res) {
        Drawable d = get(v.getContext(), res);
        if (d == null) v.setImageResource(res); else v.setImageDrawable(d);
    }

    /** Draws one named icon from the given xml (or the built-in when xml is null) for previews. */
    static Drawable preview(Context c, Entry e, String xml) {
        if (xml != null) {
            try { return new Vec(parse(xml), c.getResources().getDisplayMetrics().density); } catch (Exception x) { }
        }
        return c.getDrawable(e.id);
    }

    /**
     * Icon for a notification button. Many systems recolor these, so state is shown with brightness:
     * full strength when on, dimmed when off (null = a plain button with no on/off state).
     */
    static Icon notif(Context c, int res, Boolean on) {
        int px = 96;
        Bitmap b = Bitmap.createBitmap(px, px, Bitmap.Config.ARGB_8888);
        Canvas cv = new Canvas(b);
        Drawable d = get(c, res);
        if (d == null) return Icon.createWithResource(c, res);
        d = d.mutate();
        d.setTint(0xFFFFFFFF);
        if (on != null) d.setAlpha(on ? 255 : 95);
        d.setBounds(8, 8, px - 8, px - 8);
        d.draw(cv);
        return Icon.createWithBitmap(b);
    }

    /** Rebuilds the readable XML of a built-in drawable from its compiled form, so it can be exported and edited. */
    static String builtinXml(Context c, int id) throws Exception {
        XmlResourceParser p = c.getResources().getXml(id);
        try {
            StringBuilder sb = new StringBuilder("<?xml version=\"1.0\" encoding=\"utf-8\"?>\n");
            boolean pending = false;
            int depth = 0, ev;
            while ((ev = p.next()) != XmlPullParser.END_DOCUMENT) {
                if (ev == XmlPullParser.START_TAG) {
                    if (pending) sb.append(">\n");
                    for (int i = 0; i < depth; i++) sb.append("    ");
                    sb.append('<').append(p.getName());
                    if (depth == 0) sb.append(" xmlns:android=\"http://schemas.android.com/apk/res/android\"");
                    for (int i = 0; i < p.getAttributeCount(); i++) {
                        String ns = p.getAttributeNamespace(i);
                        if (ns == null || ns.isEmpty()) continue;
                        String n = p.getAttributeName(i), v = p.getAttributeValue(i);
                        v = exportValue(n, v);
                        sb.append("\n");
                        for (int k = 0; k <= depth; k++) sb.append("    ");
                        sb.append("android:").append(n).append("=\"")
                                .append(v.replace("&", "&amp;").replace("<", "&lt;").replace("\"", "&quot;")).append('"');
                    }
                    pending = true;
                    depth++;
                } else if (ev == XmlPullParser.END_TAG) {
                    depth--;
                    if (pending) { sb.append("/>\n"); pending = false; }
                    else {
                        for (int i = 0; i < depth; i++) sb.append("    ");
                        sb.append("</").append(p.getName()).append(">\n");
                    }
                }
            }
            return sb.toString();
        } finally {
            p.close();
        }
    }

    private static String exportValue(String name, String v) {
        if (v == null) return "";
        if (v.matches("-?[0-9.]+dip")) return v.replace("dip", "dp");
        if (v.matches("[0-9]+")) {
            int n = Integer.parseInt(v);
            if (name.equals("strokeLineCap")) return n == 1 ? "round" : n == 2 ? "square" : "butt";
            if (name.equals("strokeLineJoin")) return n == 1 ? "round" : n == 2 ? "bevel" : "miter";
            if (name.equals("fillType")) return n == 1 ? "evenOdd" : "nonZero";
        }
        return v;
    }

    // ---------------------------------------------------------------- model

    static final class Model {
        final float vw, vh, alpha;
        final List<Shape> shapes = new ArrayList<>();
        Model(float w, float h, float a) { vw = w; vh = h; alpha = a; }
    }

    static final class Shape {
        Path path;
        int fill, stroke;
        boolean hasFill, hasStroke, evenOdd;
        float strokeW, miter = 4f;
        Paint.Cap cap = Paint.Cap.BUTT;
        Paint.Join join = Paint.Join.MITER;
    }

    static final class Vec extends Drawable {
        final Model m;
        final float density;
        final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
        ColorFilter filter;
        int alpha = 255;

        Vec(Model m, float density) { this.m = m; this.density = density; }

        @Override public void draw(Canvas c) {
            android.graphics.Rect b = getBounds();
            if (b.isEmpty()) return;
            c.save();
            c.translate(b.left, b.top);
            c.scale(b.width() / m.vw, b.height() / m.vh);
            for (Shape s : m.shapes) {
                s.path.setFillType(s.evenOdd ? Path.FillType.EVEN_ODD : Path.FillType.WINDING);
                paint.setColorFilter(filter);
                if (s.hasFill) {
                    paint.reset();
                    paint.setAntiAlias(true);
                    paint.setColorFilter(filter);
                    paint.setStyle(Paint.Style.FILL);
                    paint.setColor(s.fill);
                    paint.setAlpha(Math.round(((s.fill >>> 24) / 255f) * m.alpha * alpha));
                    c.drawPath(s.path, paint);
                }
                if (s.hasStroke && s.strokeW > 0) {
                    paint.reset();
                    paint.setAntiAlias(true);
                    paint.setColorFilter(filter);
                    paint.setStyle(Paint.Style.STROKE);
                    paint.setStrokeWidth(s.strokeW);
                    paint.setStrokeCap(s.cap);
                    paint.setStrokeJoin(s.join);
                    paint.setStrokeMiter(s.miter);
                    paint.setColor(s.stroke);
                    paint.setAlpha(Math.round(((s.stroke >>> 24) / 255f) * m.alpha * alpha));
                    c.drawPath(s.path, paint);
                }
            }
            c.restore();
        }

        @Override public void setAlpha(int a) { alpha = a; invalidateSelf(); }
        @Override public void setColorFilter(ColorFilter cf) { filter = cf; invalidateSelf(); }
        @Override public void setTintList(ColorStateList t) {
            filter = t == null ? null : new PorterDuffColorFilter(t.getDefaultColor(), PorterDuff.Mode.SRC_IN);
            invalidateSelf();
        }
        @Override public int getOpacity() { return PixelFormat.TRANSLUCENT; }
        @Override public int getIntrinsicWidth() { return Math.round(m.vw * density); }
        @Override public int getIntrinsicHeight() { return Math.round(m.vh * density); }
    }

    // ---------------------------------------------------------------- validation + parsing

    /** Throws an Exception with a plain-language reason if the text is not a usable Android vector icon. */
    static Model parse(String xml) throws Exception {
        if (xml == null || xml.trim().isEmpty()) throw new Exception("The file is empty.");
        if (xml.length() > MAX_BYTES) throw new Exception("The file is too large for an icon.");
        if (xml.charAt(0) == '\uFEFF') xml = xml.substring(1);
        XmlPullParser p = Xml.newPullParser();
        try {
            p.setInput(new StringReader(xml));
        } catch (Exception e) { throw new Exception("This is not readable XML."); }

        Model model = null;
        ArrayDeque<Matrix> stack = new ArrayDeque<>();
        stack.push(new Matrix());
        int depth = 0;
        try {
            int ev;
            while ((ev = p.next()) != XmlPullParser.END_DOCUMENT) {
                if (ev == XmlPullParser.START_TAG) {
                    String tag = p.getName();
                    depth++;
                    if (model == null) {
                        if (!tag.equals("vector"))
                            throw new Exception("The root tag is <" + tag + ">. An icon must be a <vector> drawable.");
                        float vw = num(attr(p, "viewportWidth"), -1), vh = num(attr(p, "viewportHeight"), -1);
                        if (vw <= 0 || vh <= 0) throw new Exception("The <vector> needs viewportWidth and viewportHeight greater than 0.");
                        model = new Model(vw, vh, num(attr(p, "alpha"), 1f));
                    } else if (tag.equals("group")) {
                        Matrix m = new Matrix();
                        float px = num(attr(p, "pivotX"), 0), py = num(attr(p, "pivotY"), 0);
                        m.postTranslate(-px, -py);
                        m.postScale(num(attr(p, "scaleX"), 1), num(attr(p, "scaleY"), 1));
                        m.postRotate(num(attr(p, "rotation"), 0));
                        m.postTranslate(px + num(attr(p, "translateX"), 0), py + num(attr(p, "translateY"), 0));
                        m.postConcat(stack.peek());
                        stack.push(m);
                    } else if (tag.equals("path")) {
                        String d = attr(p, "pathData");
                        if (d == null || d.trim().isEmpty()) throw new Exception("A <path> has no pathData.");
                        Shape s = new Shape();
                        s.path = parsePath(d);
                        s.path.transform(stack.peek());
                        String fc = attr(p, "fillColor"), sc = attr(p, "strokeColor");
                        if (fc != null) { s.fill = color(fc); s.hasFill = true; }
                        if (sc != null) { s.stroke = color(sc); s.hasStroke = true; }
                        float fa = num(attr(p, "fillAlpha"), 1f), sa = num(attr(p, "strokeAlpha"), 1f);
                        s.fill = withAlpha(s.fill, fa);
                        s.stroke = withAlpha(s.stroke, sa);
                        s.strokeW = num(attr(p, "strokeWidth"), 0f);
                        s.miter = num(attr(p, "strokeMiterLimit"), 4f);
                        String cap = attr(p, "strokeLineCap"), join = attr(p, "strokeLineJoin"), ft = attr(p, "fillType");
                        if ("round".equals(cap)) s.cap = Paint.Cap.ROUND; else if ("square".equals(cap)) s.cap = Paint.Cap.SQUARE;
                        if ("round".equals(join)) s.join = Paint.Join.ROUND; else if ("bevel".equals(join)) s.join = Paint.Join.BEVEL;
                        s.evenOdd = "evenOdd".equals(ft);
                        model.shapes.add(s);
                    } else {
                        throw new Exception("<" + tag + "> is not supported. Use only <vector>, <group> and <path>.");
                    }
                } else if (ev == XmlPullParser.END_TAG) {
                    depth--;
                    if (p.getName().equals("group") && stack.size() > 1) stack.pop();
                }
            }
        } catch (org.xmlpull.v1.XmlPullParserException e) {
            throw new Exception("The XML is malformed.");
        }
        if (model == null) throw new Exception("No <vector> found.");
        if (model.shapes.isEmpty()) throw new Exception("The vector has no <path> to draw.");
        return model;
    }

    private static String attr(XmlPullParser p, String name) {
        for (int i = 0; i < p.getAttributeCount(); i++) {
            String n = p.getAttributeName(i);
            int c = n.indexOf(':');
            if (c >= 0) n = n.substring(c + 1);
            if (n.equals(name)) return p.getAttributeValue(i);
        }
        return null;
    }

    private static float num(String s, float def) {
        if (s == null) return def;
        s = s.trim().replaceAll("[a-zA-Z%]+$", "");
        try { return Float.parseFloat(s); } catch (Exception e) { return def; }
    }

    private static int color(String s) throws Exception {
        s = s.trim();
        if (!s.startsWith("#")) throw new Exception("Colors must be written like #FFFFFF. References such as " + s + " can't be used.");
        String h = s.substring(1);
        try {
            if (h.length() == 3) h = "" + h.charAt(0) + h.charAt(0) + h.charAt(1) + h.charAt(1) + h.charAt(2) + h.charAt(2);
            if (h.length() == 4) h = "" + h.charAt(0) + h.charAt(0) + h.charAt(1) + h.charAt(1) + h.charAt(2) + h.charAt(2) + h.charAt(3) + h.charAt(3);
            if (h.length() == 6) return 0xFF000000 | (int) Long.parseLong(h, 16);
            if (h.length() == 8) return (int) Long.parseLong(h, 16);
        } catch (Exception e) { }
        throw new Exception("\"" + s + "\" is not a valid color.");
    }

    private static int withAlpha(int c, float a) {
        int al = Math.round(((c >>> 24) & 0xFF) * Math.max(0f, Math.min(1f, a)));
        return (al << 24) | (c & 0xFFFFFF);
    }

    // ---------------------------------------------------------------- SVG-style path data

    private static final class Tok {
        final String s;
        int i;
        Tok(String s) { this.s = s; }

        void ws() { while (i < s.length() && (s.charAt(i) == ' ' || s.charAt(i) == ',' || s.charAt(i) == '\n' || s.charAt(i) == '\t' || s.charAt(i) == '\r')) i++; }
        boolean more() { ws(); return i < s.length(); }
        boolean cmd() {
            ws();
            if (i >= s.length()) return false;
            char c = s.charAt(i);
            return Character.isLetter(c) && c != 'e' && c != 'E';
        }
        float num() throws Exception {
            ws();
            int st = i, n = s.length();
            if (i < n && (s.charAt(i) == '+' || s.charAt(i) == '-')) i++;
            boolean dot = false, dig = false;
            while (i < n) {
                char c = s.charAt(i);
                if (c >= '0' && c <= '9') { dig = true; i++; }
                else if (c == '.' && !dot) { dot = true; i++; }
                else break;
            }
            if (!dig) throw new Exception("The pathData has a number it can't read.");
            if (i < n && (s.charAt(i) == 'e' || s.charAt(i) == 'E')) {
                int save = i;
                i++;
                if (i < n && (s.charAt(i) == '+' || s.charAt(i) == '-')) i++;
                int ds = i;
                while (i < n && s.charAt(i) >= '0' && s.charAt(i) <= '9') i++;
                if (i == ds) i = save;
            }
            return Float.parseFloat(s.substring(st, i));
        }
        boolean flag() throws Exception {
            ws();
            if (i >= s.length()) throw new Exception("The pathData ended too early.");
            char c = s.charAt(i);
            if (c != '0' && c != '1') throw new Exception("The pathData has a bad arc flag.");
            i++;
            return c == '1';
        }
    }

    static Path parsePath(String d) throws Exception {
        Tok t = new Tok(d);
        Path p = new Path();
        char cmd = 0, last = 0;
        float x = 0, y = 0, mx = 0, my = 0, cx = 0, cy = 0, qx = 0, qy = 0;
        boolean first = true;
        try {
            while (t.more()) {
                if (t.cmd()) cmd = t.s.charAt(t.i++);
                else if (cmd == 0 || cmd == 'z' || cmd == 'Z') throw new Exception("The pathData has numbers without a command.");
                char u = Character.toUpperCase(cmd);
                boolean rel = cmd != u;
                if (first && u != 'M') throw new Exception("The pathData must start with M.");
                first = false;
                float ox = rel ? x : 0, oy = rel ? y : 0;
                switch (u) {
                    case 'M': {
                        x = ox + t.num(); y = oy + t.num();
                        p.moveTo(x, y);
                        mx = x; my = y;
                        cmd = rel ? 'l' : 'L';
                        break;
                    }
                    case 'L': x = ox + t.num(); y = oy + t.num(); p.lineTo(x, y); break;
                    case 'H': x = ox + t.num(); p.lineTo(x, y); break;
                    case 'V': y = oy + t.num(); p.lineTo(x, y); break;
                    case 'C': {
                        float x1 = ox + t.num(), y1 = oy + t.num(), x2 = ox + t.num(), y2 = oy + t.num();
                        x = ox + t.num(); y = oy + t.num();
                        p.cubicTo(x1, y1, x2, y2, x, y);
                        cx = x2; cy = y2;
                        break;
                    }
                    case 'S': {
                        float x1 = (last == 'C' || last == 'S') ? 2 * x - cx : x, y1 = (last == 'C' || last == 'S') ? 2 * y - cy : y;
                        float x2 = ox + t.num(), y2 = oy + t.num();
                        x = ox + t.num(); y = oy + t.num();
                        p.cubicTo(x1, y1, x2, y2, x, y);
                        cx = x2; cy = y2;
                        break;
                    }
                    case 'Q': {
                        qx = ox + t.num(); qy = oy + t.num();
                        x = ox + t.num(); y = oy + t.num();
                        p.quadTo(qx, qy, x, y);
                        break;
                    }
                    case 'T': {
                        qx = (last == 'Q' || last == 'T') ? 2 * x - qx : x;
                        qy = (last == 'Q' || last == 'T') ? 2 * y - qy : y;
                        x = ox + t.num(); y = oy + t.num();
                        p.quadTo(qx, qy, x, y);
                        break;
                    }
                    case 'A': {
                        float rx = t.num(), ry = t.num(), rot = t.num();
                        boolean fa = t.flag(), fs = t.flag();
                        float nx = ox + t.num(), ny = oy + t.num();
                        arc(p, x, y, rx, ry, rot, fa, fs, nx, ny);
                        x = nx; y = ny;
                        break;
                    }
                    case 'Z': p.close(); x = mx; y = my; break;
                    default: throw new Exception("The pathData uses an unknown command \"" + cmd + "\".");
                }
                last = u;
            }
        } catch (NumberFormatException e) {
            throw new Exception("The pathData has a number it can't read.");
        } catch (StringIndexOutOfBoundsException e) {
            throw new Exception("The pathData ended too early.");
        }
        if (first) throw new Exception("The pathData is empty.");
        return p;
    }

    private static double ang(double ux, double uy, double vx, double vy) {
        double dot = ux * vx + uy * vy, len = Math.sqrt(ux * ux + uy * uy) * Math.sqrt(vx * vx + vy * vy);
        double a = Math.acos(Math.max(-1, Math.min(1, dot / len)));
        return ux * vy - uy * vx < 0 ? -a : a;
    }

    private static void arc(Path p, double x0, double y0, double rx, double ry, double phi, boolean fa, boolean fs, double x, double y) {
        if (x0 == x && y0 == y) return;
        rx = Math.abs(rx); ry = Math.abs(ry);
        if (rx == 0 || ry == 0) { p.lineTo((float) x, (float) y); return; }
        double cp = Math.cos(Math.toRadians(phi)), sp = Math.sin(Math.toRadians(phi));
        double dx = (x0 - x) / 2, dy = (y0 - y) / 2;
        double x1 = cp * dx + sp * dy, y1 = -sp * dx + cp * dy;
        double lam = x1 * x1 / (rx * rx) + y1 * y1 / (ry * ry);
        if (lam > 1) { double s = Math.sqrt(lam); rx *= s; ry *= s; }
        double num = rx * rx * ry * ry - rx * rx * y1 * y1 - ry * ry * x1 * x1;
        double den = rx * rx * y1 * y1 + ry * ry * x1 * x1;
        double co = Math.sqrt(Math.max(0, num / den));
        if (fa == fs) co = -co;
        double cx1 = co * rx * y1 / ry, cy1 = -co * ry * x1 / rx;
        double cxx = cp * cx1 - sp * cy1 + (x0 + x) / 2, cyy = sp * cx1 + cp * cy1 + (y0 + y) / 2;
        double th1 = ang(1, 0, (x1 - cx1) / rx, (y1 - cy1) / ry);
        double dth = ang((x1 - cx1) / rx, (y1 - cy1) / ry, (-x1 - cx1) / rx, (-y1 - cy1) / ry);
        if (!fs && dth > 0) dth -= 2 * Math.PI; else if (fs && dth < 0) dth += 2 * Math.PI;
        int n = (int) Math.ceil(Math.abs(dth) / (Math.PI / 2));
        if (n < 1) n = 1;
        double d = dth / n, k = 4.0 / 3 * Math.tan(d / 4), a = th1;
        for (int i = 0; i < n; i++) {
            double c1 = Math.cos(a), s1 = Math.sin(a), c2 = Math.cos(a + d), s2 = Math.sin(a + d);
            double[] q1 = map(c1 - k * s1, s1 + k * c1, rx, ry, cp, sp, cxx, cyy);
            double[] q2 = map(c2 + k * s2, s2 - k * c2, rx, ry, cp, sp, cxx, cyy);
            double[] q3 = map(c2, s2, rx, ry, cp, sp, cxx, cyy);
            p.cubicTo((float) q1[0], (float) q1[1], (float) q2[0], (float) q2[1], (float) q3[0], (float) q3[1]);
            a += d;
        }
    }

    private static double[] map(double u, double v, double rx, double ry, double cp, double sp, double cx, double cy) {
        return new double[]{cx + rx * u * cp - ry * v * sp, cy + rx * u * sp + ry * v * cp};
    }
}
