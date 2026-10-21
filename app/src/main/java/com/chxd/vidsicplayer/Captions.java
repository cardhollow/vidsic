package com.chxd.vidsicplayer;

import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.Charset;
import java.nio.charset.CodingErrorAction;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Subtitles: parses SubRip (.srt), WebVTT (.vtt), TTML / DFXP / XML (.ttml .dfxp .xml), SubStation Alpha (.ass .ssa),
 * YouTube SBV (.sbv), lyrics (.lrc) and MicroDVD (.sub), and answers "what is on screen at this time".
 */
final class Captions {
    static class Cue {
        long s, e;
        String t;
    }

    static final Pattern TIME = Pattern.compile("(?:(\\d{1,3}):)?(\\d{1,2}):(\\d{2})[.,](\\d{1,3})");
    static final Pattern TAGS = Pattern.compile("<[^>]*>|\\{\\\\[^}]*\\}");

    final ArrayList<Cue> cues = new ArrayList<>();

    /** Bytes of a subtitle file -> text. UTF-8 (with or without BOM), else Windows-1252. */
    static String decode(byte[] b) {
        int off = 0;
        if (b.length >= 3 && (b[0] & 0xFF) == 0xEF && (b[1] & 0xFF) == 0xBB && (b[2] & 0xFF) == 0xBF) off = 3;
        try {
            return Charset.forName("UTF-8").newDecoder()
                    .onMalformedInput(CodingErrorAction.REPORT).onUnmappableCharacter(CodingErrorAction.REPORT)
                    .decode(ByteBuffer.wrap(b, off, b.length - off)).toString();
        } catch (CharacterCodingException e) {
            return new String(b, off, b.length - off, Charset.forName("windows-1252"));
        }
    }

    static long time(String s) {
        Matcher m = TIME.matcher(s);
        if (!m.find()) return -1;
        long h = m.group(1) == null ? 0 : Long.parseLong(m.group(1));
        long mi = Long.parseLong(m.group(2)), se = Long.parseLong(m.group(3));
        long ms = Long.parseLong((m.group(4) + "00").substring(0, 3));
        return ((h * 60 + mi) * 60 + se) * 1000 + ms;
    }

    static String clean(String line) {
        String t = TAGS.matcher(line).replaceAll("");
        return t.replace("&nbsp;", " ").replace("&lt;", "<").replace("&gt;", ">")
                .replace("&quot;", "\"").replace("&#39;", "'").replace("&amp;", "&").trim();
    }

    static final Pattern LRC = Pattern.compile("^\\s*\\[(\\d{1,3}):(\\d{2})(?:[.:](\\d{1,3}))?\\]");
    static final Pattern SBV = Pattern.compile("^\\s*\\d+:\\d{2}:\\d{2}\\.\\d+\\s*,\\s*\\d+:\\d{2}:\\d{2}\\.\\d+\\s*$");
    static final Pattern DVD = Pattern.compile("^\\s*\\{(\\d+)\\}\\{(\\d*)\\}(.*)$");

    /** Detects the format and parses it. Returns null when the text holds no cues at all. */
    static Captions parse(String text) {
        if (text == null) return null;
        String head = text.length() > 4000 ? text.substring(0, 4000) : text;
        Captions c;
        if (head.contains("<tt") || head.contains("<?xml") || head.contains("<p ")) c = parseTtml(text);
        else if (text.contains("[Events]")) c = parseAss(text);
        else if (Pattern.compile("(?m)^\\s*\\[\\d{1,3}:\\d{2}").matcher(head).find()) c = parseLrc(text); // may start with [ar:..] tags
        else if (DVD.matcher(firstLine(text)).find()) c = parseDvd(text);
        else if (SBV.matcher(firstLine(text)).find()) c = parseSbv(text);
        else c = parseSrt(text);
        return c == null || c.cues.isEmpty() ? null : c.sorted();
    }

    static String firstLine(String t) {
        for (String l : t.replace("\r", "\n").split("\n")) if (!l.trim().isEmpty()) return l;
        return "";
    }

    Captions sorted() {
        Collections.sort(cues, new Comparator<Cue>() {
            @Override public int compare(Cue x, Cue y) { return Long.compare(x.s, y.s); }
        });
        return this;
    }

    void add(long s, long e, String t) {
        t = t == null ? "" : t.trim();
        if (s < 0 || e < 0 || t.isEmpty()) return;
        Cue q = new Cue();
        q.s = s;
        q.e = Math.max(e, s + 200);
        q.t = t;
        cues.add(q);
    }

    static String entities(String t) {
        Matcher m = Pattern.compile("&#(x?)([0-9a-fA-F]+);").matcher(t);
        StringBuffer sb = new StringBuffer();
        while (m.find()) {
            String r;
            try { r = new String(Character.toChars(Integer.parseInt(m.group(2), m.group(1).isEmpty() ? 10 : 16))); }
            catch (Exception e) { r = ""; }
            m.appendReplacement(sb, Matcher.quoteReplacement(r));
        }
        m.appendTail(sb);
        return sb.toString().replace("&nbsp;", " ").replace("&lt;", "<").replace("&gt;", ">")
                .replace("&quot;", "\"").replace("&apos;", "'").replace("&amp;", "&");
    }

    // ---------- TTML / DFXP ----------
    // <p begin end dur> (TTML), <p t d> in milliseconds (YouTube timedtext v3), <text start dur> in seconds (timedtext v1)
    static final Pattern TT_P = Pattern.compile("<(p|text)\\b([^>]*[^/>])?>(.*?)</\\1>", Pattern.DOTALL);
    static final Pattern TT_ATTR = Pattern.compile("(?<![\\w:-])(begin|end|dur|start|t|d)\\s*=\\s*[\"']([^\"']*)[\"']");

    /** Clock time (h:m:s.f), offset time (12.5s, 300ms, 90000t) or frames (h:m:s:f at 24 fps). */
    static long ttTime(String v, double tickRate) {
        v = v.trim();
        try {
            if (v.matches("-?[0-9.]+(h|m|s|ms|t|f)")) {
                if (v.endsWith("ms")) return Math.round(Double.parseDouble(v.substring(0, v.length() - 2)));
                double n = Double.parseDouble(v.substring(0, v.length() - 1));
                switch (v.charAt(v.length() - 1)) {
                    case 'h': return Math.round(n * 3600000);
                    case 'm': return Math.round(n * 60000);
                    case 's': return Math.round(n * 1000);
                    case 't': return Math.round(n * 1000 / tickRate);
                    default: return Math.round(n * 1000 / 24);
                }
            }
            String[] p = v.split(":");
            if (p.length >= 3) {
                double sec = Double.parseDouble(p[2]);
                long ms = ((Long.parseLong(p[0]) * 60 + Long.parseLong(p[1])) * 60) * 1000 + Math.round(sec * 1000);
                if (p.length == 4) ms += Math.round(Long.parseLong(p[3]) * 1000 / 24.0); // frames
                return ms;
            }
            return Math.round(Double.parseDouble(v) * 1000);
        } catch (Exception e) { return -1; }
    }

    static Captions parseTtml(String text) {
        Captions c = new Captions();
        double tick = 10000000;
        Matcher tm = Pattern.compile("tickRate\\s*=\\s*[\"'](\\d+)[\"']").matcher(text);
        if (tm.find()) tick = Double.parseDouble(tm.group(1));
        Matcher m = TT_P.matcher(text);
        while (m.find()) {
            String b = null, e = null, d = null, ms0 = null, msd = null;
            Matcher am = TT_ATTR.matcher(m.group(2) == null ? "" : m.group(2));
            while (am.find()) {
                String k = am.group(1), v = am.group(2);
                if (k.equals("begin") || k.equals("start")) b = v;
                else if (k.equals("end")) e = v;
                else if (k.equals("dur")) d = v;
                else if (k.equals("t")) ms0 = v;
                else msd = v;
            }
            long s0, e0;
            if (ms0 != null) { // plain milliseconds
                try { s0 = Long.parseLong(ms0.trim()); e0 = s0 + (msd == null ? 3000 : Long.parseLong(msd.trim())); }
                catch (Exception ex) { continue; }
            } else {
                if (b == null) continue;
                s0 = ttTime(b, tick);
                e0 = e != null ? ttTime(e, tick) : d != null && s0 >= 0 ? s0 + ttTime(d, tick) : -1;
            }
            String body = m.group(3).replaceAll("(?i)<br\\s*/?>", "\n").replaceAll("<[^>]*>", "");
            StringBuilder sb = new StringBuilder();
            for (String l : entities(body).split("\n")) {
                l = l.replaceAll("\\s+", " ").trim();
                if (!l.isEmpty()) { if (sb.length() > 0) sb.append('\n'); sb.append(l); }
            }
            c.add(s0, e0, sb.toString());
        }
        return c;
    }

    // ---------- SubStation Alpha ----------
    static long assTime(String v) {
        Matcher m = Pattern.compile("(\\d+):(\\d{1,2}):(\\d{1,2})[.:](\\d{1,3})").matcher(v.trim());
        if (!m.find()) return -1;
        String f = m.group(4);
        long ms = f.length() == 3 ? Long.parseLong(f) : f.length() == 2 ? Long.parseLong(f) * 10 : Long.parseLong(f) * 100;
        return ((Long.parseLong(m.group(1)) * 60 + Long.parseLong(m.group(2))) * 60 + Long.parseLong(m.group(3))) * 1000 + ms;
    }

    static Captions parseAss(String text) {
        Captions c = new Captions();
        boolean ev = false;
        int iS = 1, iE = 2, iT = 9, n = 10;
        for (String l : text.replace("\r\n", "\n").replace('\r', '\n').split("\n")) {
            String t = l.trim();
            if (t.startsWith("[")) { ev = t.equalsIgnoreCase("[Events]"); continue; }
            if (!ev) continue;
            if (t.regionMatches(true, 0, "Format:", 0, 7)) {
                String[] f = t.substring(7).split(",");
                n = f.length;
                for (int i = 0; i < f.length; i++) {
                    String k = f[i].trim();
                    if (k.equalsIgnoreCase("Start")) iS = i;
                    else if (k.equalsIgnoreCase("End")) iE = i;
                    else if (k.equalsIgnoreCase("Text")) iT = i;
                }
            } else if (t.regionMatches(true, 0, "Dialogue:", 0, 9)) {
                String[] f = t.substring(9).split(",", n); // the text may itself contain commas
                if (f.length <= iT) continue;
                String tx = f[iT].replaceAll("\\{[^}]*\\}", "").replace("\\N", "\n").replace("\\n", "\n").replace("\\h", " ");
                c.add(assTime(f[iS]), assTime(f[iE]), tx);
            }
        }
        return c;
    }

    // ---------- SBV (YouTube) ----------
    static Captions parseSbv(String text) {
        Captions c = new Captions();
        for (String blk : text.replace("\r\n", "\n").replace('\r', '\n').split("\n\\s*\n")) {
            String[] l = blk.trim().split("\n", 2);
            if (l.length < 2) continue;
            String[] t = l[0].split(",");
            if (t.length < 2) continue;
            c.add(time(t[0]), time(t[1]), clean(l[1].replace("\n", " \n ")).replaceAll(" ?\n ?", "\n"));
        }
        return c;
    }

    // ---------- LRC (lyrics): a line stays up until the next one starts ----------
    static Captions parseLrc(String text) {
        Captions c = new Captions();
        ArrayList<long[]> t = new ArrayList<>();
        ArrayList<String> tx = new ArrayList<>();
        for (String l : text.replace("\r\n", "\n").replace('\r', '\n').split("\n")) {
            ArrayList<Long> ts = new ArrayList<>();
            Matcher m = LRC.matcher(l);
            while (m.find()) { // several stamps can share one line; the pattern is anchored to the start
                String f = m.group(3) == null ? "0" : m.group(3);
                long ms = f.length() == 3 ? Long.parseLong(f) : f.length() == 2 ? Long.parseLong(f) * 10 : Long.parseLong(f) * 100;
                ts.add((Long.parseLong(m.group(1)) * 60 + Long.parseLong(m.group(2))) * 1000 + ms);
                l = l.substring(m.end());
                m = LRC.matcher(l);
            }
            String body = l.trim();
            for (long s0 : ts) { t.add(new long[]{s0}); tx.add(body); }
        }
        Integer[] ord = new Integer[t.size()];
        for (int i = 0; i < ord.length; i++) ord[i] = i;
        final ArrayList<long[]> tt = t;
        java.util.Arrays.sort(ord, new Comparator<Integer>() {
            @Override public int compare(Integer x, Integer y) { return Long.compare(tt.get(x)[0], tt.get(y)[0]); }
        });
        for (int i = 0; i < ord.length; i++) {
            long s0 = t.get(ord[i])[0];
            long e0 = i + 1 < ord.length ? t.get(ord[i + 1])[0] : s0 + 5000;
            c.add(s0, e0, tx.get(ord[i]));
        }
        return c;
    }

    // ---------- MicroDVD: frame numbers, 23.976 fps unless the first line is {1}{1}fps ----------
    static Captions parseDvd(String text) {
        Captions c = new Captions();
        double fps = 23.976;
        boolean first = true;
        for (String l : text.replace("\r\n", "\n").replace('\r', '\n').split("\n")) {
            Matcher m = DVD.matcher(l);
            if (!m.find()) continue;
            long a = Long.parseLong(m.group(1)), b = m.group(2).isEmpty() ? a + 50 : Long.parseLong(m.group(2));
            if (first) {
                first = false;
                try { if (a <= 1 && b <= 1) { fps = Double.parseDouble(m.group(3).trim()); continue; } } catch (Exception e) { }
            }
            String t = m.group(3).replaceAll("\\{[^}]*\\}", "").replace("|", "\n");
            c.add(Math.round(a * 1000 / fps), Math.round(b * 1000 / fps), t);
        }
        return c;
    }

    static Captions parseSrt(String text) {
        String[] lines = text.replace("\r\n", "\n").replace('\r', '\n').split("\n");
        Captions c = new Captions();
        int i = 0;
        while (i < lines.length) {
            String l = lines[i];
            int arrow = l.indexOf("-->");
            if (arrow < 0) { i++; continue; }
            long s = time(l.substring(0, arrow)), e = time(l.substring(arrow + 3));
            i++;
            StringBuilder sb = new StringBuilder();
            while (i < lines.length && !lines[i].trim().isEmpty()) {
                if (lines[i].contains("-->")) break; // a damaged file without blank line between cues
                String t = clean(lines[i]);
                if (!t.isEmpty()) { if (sb.length() > 0) sb.append('\n'); sb.append(t); }
                i++;
            }
            if (s < 0 || e < 0 || sb.length() == 0) continue;
            Cue q = new Cue();
            q.s = s;
            q.e = Math.max(e, s + 200);
            q.t = sb.toString();
            c.cues.add(q);
        }
        return c;
    }

    /** Text visible at the given time, or null. */
    String at(long ms) {
        int lo = 0, hi = cues.size() - 1, best = -1;
        while (lo <= hi) {
            int mid = (lo + hi) >>> 1;
            if (cues.get(mid).s <= ms) { best = mid; lo = mid + 1; } else hi = mid - 1;
        }
        if (best < 0) return null;
        StringBuilder sb = null;
        for (int k = best; k >= 0 && k > best - 4; k--) { // a few cues back, in case several overlap
            Cue q = cues.get(k);
            if (q.e <= ms) continue;
            if (sb == null) sb = new StringBuilder(q.t);
            else sb.insert(0, q.t + "\n");
        }
        return sb == null ? null : sb.toString();
    }
}
