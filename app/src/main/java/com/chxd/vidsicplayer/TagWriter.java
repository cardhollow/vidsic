package com.chxd.vidsicplayer;

import android.content.Context;
import android.database.Cursor;
import android.os.Environment;
import android.os.ParcelFileDescriptor;
import android.net.Uri;
import java.io.Closeable;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.RandomAccessFile;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.charset.Charset;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Writes title / artist / album / genre / description straight into the media file's own tags
 * (ID3v2 for MP3, iTunes-style atoms for M4A / MP4 / M4V / 3GP, Vorbis comments for FLAC),
 * so the change is real and survives a rescan or a copy of the file. Only the tag area is touched;
 * audio and video data is never re-encoded.
 */
final class TagWriter {
    static final int OK = 0, UNSUPPORTED = 1, FAILED = 2, DENIED = 3, NO_SAFE_SPACE = 4, SKIPPED = 5;
    static final Charset UTF8 = Charset.forName("UTF-8"), L1 = Charset.forName("ISO-8859-1"), U16 = Charset.forName("UTF-16LE");

    /** A null field means "leave it alone", an empty string means "clear it". */
    static final class Fields {
        String title, artist, album, genre, desc;
        /** Embedded picture (JPEG or PNG bytes). null = leave the file's picture alone. */
        byte[] cover;
        String coverMime = "image/jpeg";
        int coverW, coverH;
        boolean any() { return title != null || artist != null || album != null || genre != null || desc != null || cover != null; }
    }

    // ------------------------------------------------------------------ file access

    static final class Io implements Closeable {
        final FileChannel rd, wr;
        final Closeable[] open;
        final Context context;
        final Media media;
        final String directPath;

        Io(FileChannel rd, FileChannel wr, Context context, Media media, String directPath, Closeable... open) {
            this.rd = rd; this.wr = wr; this.context = context; this.media = media;
            this.directPath = directPath; this.open = open;
        }

        long length() throws IOException { return rd.size(); }

        byte[] read(long pos, int len) throws IOException {
            ByteBuffer bb = ByteBuffer.allocate(len);
            int got = 0;
            while (got < len) {
                int n = rd.read(bb, pos + got);
                if (n <= 0) break;
                got += n;
            }
            if (got == len) return bb.array();
            byte[] cut = new byte[got];
            System.arraycopy(bb.array(), 0, cut, 0, got);
            return cut;
        }

        void write(long pos, byte[] b) throws IOException {
            ByteBuffer bb = ByteBuffer.wrap(b);
            long p = pos;
            while (bb.hasRemaining()) {
                int n = wr.write(bb, p);
                if (n <= 0) throw new IOException("Could not write media data");
                p += n;
            }
        }

        void truncate(long n) throws IOException { wr.truncate(n); }
        void force() throws IOException { wr.force(true); }

        @Override public void close() {
            for (Closeable c : open) { try { c.close(); } catch (Throwable e) { } }
        }
    }

    static Io open(Context c, Media m) throws IOException {
        String directPath = m.filePath != null ? m.filePath : pathOf(c, m);
        if (m.filePath != null) {
            RandomAccessFile raf = new RandomAccessFile(new File(m.filePath), "rw");
            return new Io(raf.getChannel(), raf.getChannel(), c, m, directPath, raf);
        }
        try {
            ParcelFileDescriptor pfd = c.getContentResolver().openFileDescriptor(m.uri, "rw");
            if (pfd == null) throw new IOException("no descriptor");
            FileInputStream in = new FileInputStream(pfd.getFileDescriptor());
            FileOutputStream out = new FileOutputStream(pfd.getFileDescriptor());
            return new Io(in.getChannel(), out.getChannel(), c, m, directPath, in, out, pfd);
        } catch (SecurityException | java.io.FileNotFoundException e) {
            if (Environment.isExternalStorageManager()) {
                String p = pathOf(c, m);
                if (p != null) {
                    RandomAccessFile raf = new RandomAccessFile(new File(p), "rw");
                    return new Io(raf.getChannel(), raf.getChannel(), c, m, p, raf);
                }
            }
            if (e instanceof SecurityException) throw (SecurityException) e;
            throw e;
        }
    }

    static Io openRead(Context c, Media m) throws IOException {
        String directPath = m.filePath != null ? m.filePath : pathOf(c, m);
        if (m.filePath != null) {
            RandomAccessFile raf = new RandomAccessFile(new File(m.filePath), "r");
            return new Io(raf.getChannel(), raf.getChannel(), c, m, directPath, raf);
        }
        try {
            ParcelFileDescriptor pfd = c.getContentResolver().openFileDescriptor(m.uri, "r");
            if (pfd == null) throw new IOException("no descriptor");
            FileInputStream in = new FileInputStream(pfd.getFileDescriptor());
            return new Io(in.getChannel(), in.getChannel(), c, m, directPath, in, pfd);
        } catch (SecurityException | java.io.FileNotFoundException e) {
            if (Environment.isExternalStorageManager()) {
                String path = pathOf(c, m);
                if (path != null) {
                    RandomAccessFile raf = new RandomAccessFile(new File(path), "r");
                    return new Io(raf.getChannel(), raf.getChannel(), c, m, path, raf);
                }
            }
            if (e instanceof SecurityException) throw (SecurityException) e;
            throw e;
        }
    }

    @SuppressWarnings("deprecation")
    static String pathOf(Context c, Media m) {
        if (m.filePath != null) return m.filePath;
        try (Cursor cu = c.getContentResolver().query(m.uri, new String[]{"_data"}, null, null, null)) {
            if (cu != null && cu.moveToFirst()) return cu.getString(0);
        } catch (Throwable e) { }
        return null;
    }

    /**
     * Replaces a tag region in the original media file. Equal-size writes journal only the tag
     * region. If the replacement must grow or shrink, the original suffix is streamed into a
     * private transaction journal first; then the existing file is resized in place. The journal
     * is not indexed as media and is deleted after commit. If the process dies mid-resize, startup
     * restores the original suffix and file length from that journal.
     */
    static void replace(Io io, long start, long oldEnd, byte[] repl) throws IOException {
        long size = io.length();
        long oldLen = oldEnd - start;
        if (start < 0 || oldEnd < start || oldEnd > size || oldLen > Integer.MAX_VALUE)
            throw new NoSafeSpaceException();
        long newSize = size + ((long) repl.length - oldLen);
        if (newSize < start + repl.length || newSize < 0) throw new NoSafeSpaceException();
        long oldModified = lastModified(io.directPath);
        boolean resized = repl.length != oldLen;
        File journal;
        if (!resized && oldLen <= 64L * 1024 * 1024) {
            byte[] old = io.read(start, (int) oldLen);
            if (old.length != oldLen) throw new IOException("Could not read original tag bytes");
            journal = createJournal(io, start, size, old, oldModified);
        } else {
            // Keep enough original data to undo both the resize and an interrupted shift.
            // Only the original file at its existing URI/path is edited; this private journal is
            // temporary recovery data, not a second media file in the library.
            journal = createRangeJournal(io, start, size, oldModified);
        }
        try {
            long tail = size - oldEnd;
            int chunk = 1 << 20;
            if (repl.length > oldLen) {
                // Move the tail backwards so data is not overwritten before it is copied.
                for (long pos = tail; pos > 0; ) {
                    int n = (int) Math.min(chunk, pos);
                    pos -= n;
                    byte[] b = io.read(oldEnd + pos, n);
                    if (b.length != n) throw new IOException("Could not read media data while resizing tags");
                    io.write(start + repl.length + pos, b);
                }
            } else if (repl.length < oldLen) {
                // Move the tail forwards from its beginning when closing the gap.
                long to = start + repl.length;
                for (long pos = 0; pos < tail; ) {
                    int n = (int) Math.min(chunk, tail - pos);
                    byte[] b = io.read(oldEnd + pos, n);
                    if (b.length != n) throw new IOException("Could not read media data while resizing tags");
                    io.write(to + pos, b);
                    pos += n;
                }
            }
            io.write(start, repl);
            io.truncate(newSize);
            io.force();
            restoreLastModified(io.directPath, oldModified);
            if (!journal.delete() && journal.exists()) throw new IOException("Could not finalize metadata write");
        } catch (Throwable failure) {
            // Restore using the original journal. If rollback itself cannot complete, leave the
            // journal intact so the next application launch can retry recovery.
            int restored = restoreJournal(io.context, journal);
            if (restored > 0) journal.delete();
            if (failure instanceof IOException) throw (IOException) failure;
            throw new IOException(failure);
        }
    }

    private static long lastModified(String path) {
        if (path == null) return -1;
        try { File f = new File(path); return f.isFile() ? f.lastModified() : -1; }
        catch (Throwable ignored) { return -1; }
    }

    private static boolean restoreLastModified(String path, long modified) {
        if (path == null || modified < 0) return true;
        try {
            File f = new File(path);
            if (!f.isFile()) return false;
            if (f.lastModified() != modified && !f.setLastModified(modified)) return false;
            return f.lastModified() == modified;
        } catch (Throwable e) { return false; }
    }

    private static final class NoSafeSpaceException extends IOException { private static final long serialVersionUID = 1L; }

    private static final int JOURNAL_V1 = 0x56535458; // VSTX: original tag bytes only
    private static final int JOURNAL_V2 = 0x56535432; // VST2: original file suffix, streamed

    private static File journalDir(Context c) {
        return new File(c.getFilesDir(), "vidsic_tag_transactions");
    }

    private static File createJournal(Io io, long start, long originalSize, byte[] oldBytes, long oldModified) throws IOException {
        File dir = journalDir(io.context);
        if (!dir.exists() && !dir.mkdirs()) throw new IOException("Cannot create tag recovery journal");
        String name = "tag_" + java.util.UUID.randomUUID().toString();
        File tempFile = new File(dir, name + ".tmp");
        File journal = new File(dir, name + ".txn");
        try (FileOutputStream fos = new FileOutputStream(tempFile);
             DataOutputStream out = new DataOutputStream(new BufferedOutputStream(fos))) {
            out.writeInt(JOURNAL_V1);
            out.writeUTF(io.media != null && io.media.uri != null ? io.media.uri.toString() : "");
            out.writeUTF(io.directPath == null ? "" : io.directPath);
            out.writeLong(start);
            out.writeLong(originalSize);
            out.writeLong(oldModified);
            out.writeInt(oldBytes.length);
            out.write(oldBytes);
            out.flush();
            fos.getFD().sync();
        } catch (Throwable e) {
            tempFile.delete();
            if (e instanceof IOException) throw (IOException) e;
            throw new IOException(e);
        }
        if (!tempFile.renameTo(journal)) {
            tempFile.delete();
            throw new IOException("Could not finalize tag recovery journal");
        }
        return journal;
    }

    private static File createRangeJournal(Io io, long start, long originalSize, long oldModified) throws IOException {
        if (start < 0 || start > originalSize) throw new NoSafeSpaceException();
        long backupLength = originalSize - start;
        File dir = journalDir(io.context);
        if (!dir.exists() && !dir.mkdirs()) throw new IOException("Cannot create tag recovery journal");
        // Fail before touching the media when the private app storage is clearly insufficient.
        if (backupLength > Math.max(0L, dir.getUsableSpace() - (1L << 20)))
            throw new NoSafeSpaceException();
        String name = "tag_" + java.util.UUID.randomUUID().toString();
        File tempFile = new File(dir, name + ".tmp");
        File journal = new File(dir, name + ".txn");
        try (FileOutputStream fos = new FileOutputStream(tempFile);
             DataOutputStream out = new DataOutputStream(new BufferedOutputStream(fos))) {
            out.writeInt(JOURNAL_V2);
            out.writeUTF(io.media != null && io.media.uri != null ? io.media.uri.toString() : "");
            out.writeUTF(io.directPath == null ? "" : io.directPath);
            out.writeLong(start);
            out.writeLong(originalSize);
            out.writeLong(oldModified);
            out.writeLong(backupLength);
            long pos = start;
            int chunk = 1 << 20;
            while (pos < originalSize) {
                int n = (int) Math.min(chunk, originalSize - pos);
                byte[] b = io.read(pos, n);
                if (b.length != n) throw new IOException("Could not read original data for recovery journal");
                out.write(b);
                pos += n;
            }
            out.flush();
            fos.getFD().sync();
        } catch (Throwable e) {
            tempFile.delete();
            if (e instanceof IOException) throw (IOException) e;
            throw new IOException(e);
        }
        if (!tempFile.renameTo(journal)) {
            tempFile.delete();
            throw new IOException("Could not finalize tag recovery journal");
        }
        return journal;
    }

    static final class Recovery {
        int restored, pending, timestampNotRestored;
    }

    /** Roll back any metadata write interrupted by process death before scanning the library. */
    static Recovery recoverInterruptedWrites(Context c) {
        Recovery r = new Recovery();
        File dir = journalDir(c);
        File[] fs = dir.listFiles((d, name) -> name.endsWith(".txn"));
        if (fs == null) return r;
        for (File f : fs) {
            int outcome = restoreJournal(c, f);
            if (outcome > 0) {
                r.restored++;
                if (outcome == 2) r.timestampNotRestored++;
                f.delete();
            } else r.pending++;
        }
        // Temporary journals are created before any media write and are ignored by recovery.
        File[] temps = dir.listFiles((d, name) -> name.endsWith(".tmp"));
        if (temps != null) for (File f : temps) f.delete();
        return r;
    }

    // 0 = pending; 1 = bytes and timestamp restored; 2 = data restored but timestamp could not be.
    private static int restoreJournal(Context c, File journal) {
        try (RandomAccessFile jf = new RandomAccessFile(journal, "r")) {
            int magic = jf.readInt();
            String uriText = jf.readUTF(), path = jf.readUTF();
            long start = jf.readLong(), originalSize = jf.readLong(), oldModified = jf.readLong();
            if (start < 0 || originalSize < start) return 0;
            if (magic == JOURNAL_V1) {
                int n = jf.readInt();
                if (n < 0 || n > 64 * 1024 * 1024 || start + n > originalSize || jf.length() - jf.getFilePointer() != n) return 0;
                byte[] old = new byte[n];
                jf.readFully(old);
                return restoreTagBytes(c, path, uriText, start, originalSize, old, oldModified);
            }
            if (magic == JOURNAL_V2) {
                long len = jf.readLong();
                long dataStart = jf.getFilePointer();
                if (len < 0 || len != originalSize - start || jf.length() - dataStart != len) return 0;
                return restoreSuffix(c, path, uriText, start, originalSize, oldModified, jf, dataStart, len);
            }
        } catch (Throwable e) { }
        return 0;
    }

    private static int restoreTagBytes(Context c, String path, String uriText, long start, long originalSize, byte[] old, long oldModified) {
        if (path != null && !path.isEmpty()) {
            try {
                File f = new File(path);
                if (f.isFile()) {
                    try (RandomAccessFile raf = new RandomAccessFile(f, "rw")) {
                        if (raf.length() != originalSize || start + old.length > originalSize) return 0;
                        raf.seek(start); raf.write(old); raf.getFD().sync();
                        boolean timeOk = oldModified < 0 || restoreLastModified(path, oldModified);
                        return timeOk ? 1 : 2;
                    }
                }
            } catch (Throwable e) { }
        }
        if (uriText != null && !uriText.isEmpty()) {
            try (ParcelFileDescriptor pfd = c.getContentResolver().openFileDescriptor(Uri.parse(uriText), "rw")) {
                if (pfd == null) return 0;
                try (FileOutputStream out = new FileOutputStream(pfd.getFileDescriptor())) {
                    FileChannel ch = out.getChannel();
                    if (ch.size() != originalSize || start + old.length > originalSize) return 0;
                    ByteBuffer bb = ByteBuffer.wrap(old);
                    long pos = start;
                    while (bb.hasRemaining()) {
                        int n = ch.write(bb, pos);
                        if (n <= 0) return 0;
                        pos += n;
                    }
                    ch.force(true);
                    boolean timeOk = oldModified < 0 || restoreLastModified(path, oldModified);
                    return timeOk ? 1 : 2;
                }
            } catch (Throwable e) { }
        }
        return 0;
    }

    private static int restoreSuffix(Context c, String path, String uriText, long start, long originalSize,
                                     long oldModified, RandomAccessFile journal, long dataStart, long len) {
        if (path != null && !path.isEmpty()) {
            try {
                File f = new File(path);
                if (f.isFile()) {
                    try (RandomAccessFile raf = new RandomAccessFile(f, "rw")) {
                        journal.seek(dataStart);
                        byte[] buf = new byte[1 << 20];
                        long done = 0;
                        while (done < len) {
                            int n = (int) Math.min(buf.length, len - done);
                            journal.readFully(buf, 0, n);
                            raf.seek(start + done);
                            raf.write(buf, 0, n);
                            done += n;
                        }
                        raf.setLength(originalSize);
                        raf.getFD().sync();
                        boolean timeOk = oldModified < 0 || restoreLastModified(path, oldModified);
                        return timeOk ? 1 : 2;
                    }
                }
            } catch (Throwable e) { }
        }
        if (uriText != null && !uriText.isEmpty()) {
            try (ParcelFileDescriptor pfd = c.getContentResolver().openFileDescriptor(Uri.parse(uriText), "rw")) {
                if (pfd == null) return 0;
                try (FileOutputStream out = new FileOutputStream(pfd.getFileDescriptor())) {
                    FileChannel ch = out.getChannel();
                    journal.seek(dataStart);
                    byte[] buf = new byte[1 << 20];
                    long done = 0;
                    while (done < len) {
                        int n = (int) Math.min(buf.length, len - done);
                        journal.readFully(buf, 0, n);
                        ByteBuffer bb = ByteBuffer.wrap(buf, 0, n);
                        long pos = start + done;
                        while (bb.hasRemaining()) {
                            int wrote = ch.write(bb, pos);
                            if (wrote <= 0) return 0;
                            pos += wrote;
                        }
                        done += n;
                    }
                    ch.truncate(originalSize);
                    ch.force(true);
                    boolean timeOk = oldModified < 0 || restoreLastModified(path, oldModified);
                    return timeOk ? 1 : 2;
                }
            } catch (Throwable e) { }
        }
        return 0;
    }

    // ------------------------------------------------------------------ entry point

    static int write(Context c, Media m, Fields f) {
        if (!f.any()) return OK;
        Io io = null;
        try {
            io = open(c, m);
            byte[] h = io.read(0, 12);
            if (h.length < 8) return UNSUPPORTED;
            if (h[0] == 'f' && h[1] == 'L' && h[2] == 'a' && h[3] == 'C') return flac(io, f);
            if (h[4] == 'f' && h[5] == 't' && h[6] == 'y' && h[7] == 'p') return mp4(io, f, m.video);
            if ((h[0] == 'I' && h[1] == 'D' && h[2] == '3') || ((h[0] & 255) == 0xFF && (h[1] & 0xE0) == 0xE0)) return mp3(io, f);
            return UNSUPPORTED;
        } catch (SecurityException e) {
            return DENIED;
        } catch (java.io.FileNotFoundException e) {
            return DENIED;
        } catch (NoSafeSpaceException e) {
            return NO_SAFE_SPACE;
        } catch (Throwable e) {
            return FAILED;
        } finally {
            if (io != null) io.close();
        }
    }

    /** True only when every requested value already matches the file's embedded tags. */
    static boolean alreadyMatches(Context c, Media m, Fields wanted) {
        return !differingFields(c, m, wanted).any();
    }

    /**
     * Returns only requested fields whose actual embedded values differ. Media/Store display values
     * are never used for comparison. If the original tags cannot be inspected, return all requested
     * fields so the writer can safely decide whether it supports the file.
     */
    static Fields differingFields(Context c, Media m, Fields wanted) {
        Fields diff = new Fields();
        if (wanted == null || !wanted.any()) return diff;
        Fields actual = readEmbedded(c, m);
        if (actual == null) return copyFields(wanted);
        if (!matches(wanted.title, actual.title)) diff.title = wanted.title;
        if (!matches(wanted.artist, actual.artist)) diff.artist = wanted.artist;
        if (!matches(wanted.album, actual.album)) diff.album = wanted.album;
        if (!matches(wanted.genre, actual.genre)) diff.genre = wanted.genre;
        if (!matches(wanted.desc, actual.desc)) diff.desc = wanted.desc;
        // A picture is always rewritten: comparing image bytes is not worth the risk of a false "match".
        if (wanted.cover != null) { diff.cover = wanted.cover; diff.coverMime = wanted.coverMime; diff.coverW = wanted.coverW; diff.coverH = wanted.coverH; }
        return diff;
    }

    private static Fields copyFields(Fields from) {
        Fields out = new Fields();
        if (from == null) return out;
        out.title = from.title; out.artist = from.artist; out.album = from.album;
        out.genre = from.genre; out.desc = from.desc;
        out.cover = from.cover; out.coverMime = from.coverMime; out.coverW = from.coverW; out.coverH = from.coverH;
        return out;
    }

    private static Fields readEmbedded(Context c, Media m) {
        Io io = null;
        try {
            io = openRead(c, m);
            byte[] h = io.read(0, 12);
            if (h.length < 8) return null;
            if (h[0] == 'f' && h[1] == 'L' && h[2] == 'a' && h[3] == 'C') return readFlac(io);
            if (h[4] == 'f' && h[5] == 't' && h[6] == 'y' && h[7] == 'p') return readMp4(io);
            if ((h[0] == 'I' && h[1] == 'D' && h[2] == '3') || ((h[0] & 255) == 0xFF && (h[1] & 0xE0) == 0xE0)) return readMp3(io);
            return null;
        } catch (Throwable e) {
            return null;
        } finally {
            if (io != null) io.close();
        }
    }

    private static boolean matches(String expected, String actual) {
        if (expected == null) return true;
        if (expected.isEmpty()) return actual == null || actual.isEmpty();
        return expected.equals(actual);
    }

    private static Fields readMp3(Io io) throws IOException {
        Fields f = new Fields();
        byte[] h = io.read(0, 10);
        if (h.length < 10 || h[0] != 'I' || h[1] != 'D' || h[2] != '3') return f;
        int ver = u8(h, 3), flags = u8(h, 5), len = synchsafe(h, 6);
        if ((ver != 3 && ver != 4) || (flags & 0x80) != 0 || len < 0 || len > 32 * 1024 * 1024 || 10L + len > io.length()) return null;
        byte[] body = io.read(10, len);
        if (body.length != len) return null;
        int p = 0;
        if ((flags & 0x40) != 0) {
            if (body.length < 4) return null;
            int es = ver == 4 ? synchsafe(body, 0) : be32(body, 0) + 4;
            if (es < 4 || es > body.length) return null;
            p = es;
        }
        while (p + 10 <= body.length) {
            if (body[p] == 0) break;
            String id = new String(body, p, 4, L1);
            int fs = ver == 4 ? synchsafe(body, p + 4) : be32(body, p + 4);
            if (fs < 0 || p + 10 + fs > body.length) return null;
            if (fs > 1 && (id.equals("TIT2") || id.equals("TPE1") || id.equals("TALB") || id.equals("TCON"))) {
                String val = decodeId3Text(body, p + 10, fs);
                if (id.equals("TIT2") && f.title == null) f.title = val;
                else if (id.equals("TPE1") && f.artist == null) f.artist = val;
                else if (id.equals("TALB") && f.album == null) f.album = val;
                else if (id.equals("TCON") && f.genre == null) f.genre = val;
            }
            p += 10 + fs;
        }
        return f;
    }

    private static String decodeId3Text(byte[] b, int p, int len) {
        if (len <= 1) return "";
        int enc = u8(b, p++);
        Charset cs = enc == 0 ? L1 : enc == 1 ? Charset.forName("UTF-16")
                : enc == 2 ? Charset.forName("UTF-16BE") : UTF8;
        String s = new String(b, p, len - 1, cs);
        while (!s.isEmpty() && s.charAt(0) == '\uFEFF') s = s.substring(1);
        int z = s.indexOf('\0');
        if (z >= 0) s = s.substring(0, z);
        return s;
    }

    private static Fields readFlac(Io io) throws IOException {
        Fields f = new Fields();
        long size = io.length(), pos = 4;
        boolean last = false;
        while (!last) {
            byte[] hd = io.read(pos, 4);
            if (hd.length < 4) return null;
            last = (hd[0] & 0x80) != 0;
            int type = hd[0] & 0x7F;
            int len = (u8(hd, 1) << 16) | (u8(hd, 2) << 8) | u8(hd, 3);
            if (pos + 4L + len > size) return null;
            if (type == 4) {
                byte[] v = io.read(pos + 4, len);
                if (v.length != len || v.length < 8) return null;
                int p = 0, vl = le32(v, p); p += 4;
                if (vl < 0 || p + vl + 4 > v.length) return null;
                p += vl;
                int count = le32(v, p); p += 4;
                if (count < 0 || count > 100000) return null;
                for (int i = 0; i < count; i++) {
                    if (p + 4 > v.length) return null;
                    int n = le32(v, p); p += 4;
                    if (n < 0 || p + n > v.length) return null;
                    String c = new String(v, p, n, UTF8); p += n;
                    int eq = c.indexOf('=');
                    if (eq <= 0) continue;
                    String k = c.substring(0, eq).toUpperCase(Locale.US), val = c.substring(eq + 1);
                    if (k.equals("TITLE")) f.title = val;
                    else if (k.equals("ARTIST")) f.artist = val;
                    else if (k.equals("ALBUM")) f.album = val;
                    else if (k.equals("GENRE")) f.genre = val;
                }
            }
            pos += 4L + len;
            if (pos > size) return null;
        }
        return f;
    }

    private static Fields readMp4(Io io) throws IOException {
        long size = io.length(), pos = 0;
        long moovStart = -1, moovLen = -1;
        while (pos + 8 <= size) {
            byte[] hd = io.read(pos, 16);
            if (hd.length < 8) return null;
            long sz = be32(hd, 0) & 0xFFFFFFFFL;
            int hl = 8;
            if (sz == 1) {
                if (hd.length < 16) return null;
                sz = ((be32(hd, 8) & 0xFFFFFFFFL) << 32) | (be32(hd, 12) & 0xFFFFFFFFL); hl = 16;
            } else if (sz == 0) sz = size - pos;
            if (sz < hl || pos + sz > size) return null;
            String type = new String(hd, 4, 4, L1);
            if (type.equals("moov")) { moovStart = pos; moovLen = sz; break; }
            pos += sz;
        }
        if (moovStart < 0 || moovLen < 8 || moovLen > 120L * 1024 * 1024) return null;
        byte[] moov = io.read(moovStart, (int) moovLen);
        if (moov.length != moovLen) return null;
        int hdr = be32(moov, 0) == 1 ? 16 : 8;
        Fields f = new Fields();
        List<byte[]> top = kids(moov, hdr, moov.length);
        for (byte[] atom : top) {
            if (!typeAt(atom, 0).equals("udta")) continue;
            for (byte[] u : kids(atom, 8, atom.length)) {
                if (!typeAt(u, 0).equals("meta")) continue;
                for (byte[] meta : kids(u, 12, u.length)) {
                    if (!typeAt(meta, 0).equals("ilst")) continue;
                    for (byte[] item : kids(meta, 8, meta.length)) {
                        String itemType = typeAt(item, 0);
                        String val = readMp4TextItem(item);
                        if (val == null) continue;
                        if (itemType.equals(new String(NAM, L1))) f.title = val;
                        else if (itemType.equals(new String(ART, L1))) f.artist = val;
                        else if (itemType.equals(new String(ALB, L1))) f.album = val;
                        else if (itemType.equals(new String(GEN, L1))) f.genre = val;
                        else if (itemType.equals(new String(DESC, L1))) f.desc = val;
                    }
                }
            }
        }
        return f;
    }

    private static String readMp4TextItem(byte[] item) throws IOException {
        for (byte[] data : kids(item, 8, item.length)) {
            if (!typeAt(data, 0).equals("data") || data.length < 16) continue;
            int type = u8(data, 11);
            String val = new String(data, 16, data.length - 16, type == 2 ? Charset.forName("UTF-16BE") : UTF8);
            while (!val.isEmpty() && val.charAt(0) == '\uFEFF') val = val.substring(1);
            return val;
        }
        return null;
    }

    // ------------------------------------------------------------------ helpers

    static int u8(byte[] d, int p) { return d[p] & 255; }
    static int be32(byte[] d, int p) { return (u8(d, p) << 24) | (u8(d, p + 1) << 16) | (u8(d, p + 2) << 8) | u8(d, p + 3); }
    static int le32(byte[] d, int p) { return u8(d, p) | (u8(d, p + 1) << 8) | (u8(d, p + 2) << 16) | (u8(d, p + 3) << 24); }
    static int synchsafe(byte[] d, int p) { return (u8(d, p) << 21) | (u8(d, p + 1) << 14) | (u8(d, p + 2) << 7) | u8(d, p + 3); }
    static void putBe32(byte[] d, int p, int v) { d[p] = (byte) (v >>> 24); d[p + 1] = (byte) (v >>> 16); d[p + 2] = (byte) (v >>> 8); d[p + 3] = (byte) v; }
    static void putLe32(byte[] d, int p, int v) { d[p] = (byte) v; d[p + 1] = (byte) (v >>> 8); d[p + 2] = (byte) (v >>> 16); d[p + 3] = (byte) (v >>> 24); }
    static void putSynch(byte[] d, int p, int v) { d[p] = (byte) ((v >> 21) & 127); d[p + 1] = (byte) ((v >> 14) & 127); d[p + 2] = (byte) ((v >> 7) & 127); d[p + 3] = (byte) (v & 127); }

    static byte[] cat(List<byte[]> l) {
        int n = 0;
        for (byte[] b : l) n += b.length;
        byte[] out = new byte[n];
        int p = 0;
        for (byte[] b : l) { System.arraycopy(b, 0, out, p, b.length); p += b.length; }
        return out;
    }

    // ------------------------------------------------------------------ MP3 (ID3v2)

    static byte[] id3Text(String id, String text, int ver) {
        byte[] body;
        if (ver == 4) {
            byte[] t = text.getBytes(UTF8);
            body = new byte[1 + t.length];
            body[0] = 3;
            System.arraycopy(t, 0, body, 1, t.length);
        } else {
            byte[] t = text.getBytes(U16);
            body = new byte[1 + 2 + t.length];
            body[0] = 1;
            body[1] = (byte) 0xFF;
            body[2] = (byte) 0xFE;
            System.arraycopy(t, 0, body, 3, t.length);
        }
        byte[] fr = new byte[10 + body.length];
        System.arraycopy(id.getBytes(L1), 0, fr, 0, 4);
        if (ver == 4) putSynch(fr, 4, body.length); else putBe32(fr, 4, body.length);
        System.arraycopy(body, 0, fr, 10, body.length);
        return fr;
    }

    /** APIC frame: text encoding 0, MIME (latin1, NUL), picture type 3 (front cover), empty description, data. */
    static byte[] id3Apic(Fields f, int ver) {
        byte[] mime = (f.coverMime == null ? "image/jpeg" : f.coverMime).getBytes(L1);
        byte[] body = new byte[1 + mime.length + 1 + 1 + 1 + f.cover.length];
        int p = 0;
        body[p++] = 0;
        System.arraycopy(mime, 0, body, p, mime.length);
        p += mime.length;
        body[p++] = 0;
        body[p++] = 3;
        body[p++] = 0;
        System.arraycopy(f.cover, 0, body, p, f.cover.length);
        byte[] fr = new byte[10 + body.length];
        System.arraycopy("APIC".getBytes(L1), 0, fr, 0, 4);
        if (ver == 4) putSynch(fr, 4, body.length); else putBe32(fr, 4, body.length);
        System.arraycopy(body, 0, fr, 10, body.length);
        return fr;
    }

    static int mp3(Io io, Fields f) throws IOException {
        long size = io.length();
        byte[] h = io.read(0, 10);
        boolean has = h.length == 10 && h[0] == 'I' && h[1] == 'D' && h[2] == '3';
        int ver = 4;
        long oldTotal = 0;
        List<byte[]> out = new ArrayList<>();
        if (has) {
            ver = u8(h, 3);
            if (ver != 3 && ver != 4) return UNSUPPORTED;
            int flags = u8(h, 5);
            if ((flags & 0x80) != 0) return UNSUPPORTED;
            int tsize = synchsafe(h, 6);
            oldTotal = 10 + tsize + ((flags & 0x10) != 0 ? 10 : 0);
            if (oldTotal > size) return FAILED;
            byte[] body = io.read(10, tsize);
            int p = 0;
            if ((flags & 0x40) != 0) {
                int es = ver == 4 ? synchsafe(body, 0) : be32(body, 0) + 4;
                if (es < 4 || es > body.length) return FAILED;
                p = es;
            }
            while (p + 10 <= body.length) {
                if (body[p] == 0) break;
                String id = new String(body, p, 4, L1);
                int fs = ver == 4 ? synchsafe(body, p + 4) : be32(body, p + 4);
                if (fs < 0 || p + 10 + fs > body.length) break;
                boolean ours = (id.equals("TIT2") && f.title != null) || (id.equals("TPE1") && f.artist != null)
                        || (id.equals("TALB") && f.album != null) || (id.equals("TCON") && f.genre != null)
                        || (id.equals("APIC") && f.cover != null);
                if (!ours) {
                    byte[] fr = new byte[10 + fs];
                    System.arraycopy(body, p, fr, 0, fr.length);
                    out.add(fr);
                }
                p += 10 + fs;
            }
        }
        if (f.title != null && !f.title.isEmpty()) out.add(id3Text("TIT2", f.title, ver));
        if (f.artist != null && !f.artist.isEmpty()) out.add(id3Text("TPE1", f.artist, ver));
        if (f.album != null && !f.album.isEmpty()) out.add(id3Text("TALB", f.album, ver));
        if (f.genre != null && !f.genre.isEmpty()) out.add(id3Text("TCON", f.genre, ver));
        if (f.cover != null) out.add(id3Apic(f, ver));
        byte[] frames = cat(out);
        long room = has ? oldTotal - 10 : 0;
        int payload = frames.length <= room ? (int) room : frames.length + 2048;
        byte[] tag = new byte[10 + payload];
        tag[0] = 'I'; tag[1] = 'D'; tag[2] = '3';
        tag[3] = (byte) ver;
        putSynch(tag, 6, payload);
        System.arraycopy(frames, 0, tag, 10, frames.length);
        replace(io, 0, oldTotal, tag);
        return OK;
    }

    // ------------------------------------------------------------------ FLAC (Vorbis comments)

    static boolean isFrontCoverPicture(byte[] d) {
        return d != null && d.length >= 4 && be32(d, 0) == 3;
    }

    static byte[] flacPicture(Fields f) {
        byte[] mime = (f.coverMime == null ? "image/jpeg" : f.coverMime).getBytes(L1);
        byte[] b = new byte[4 + 4 + mime.length + 4 + 4 + 4 + 4 + 4 + 4 + f.cover.length];
        int p = 0;
        putBe32(b, p, 3); p += 4;
        putBe32(b, p, mime.length); p += 4;
        System.arraycopy(mime, 0, b, p, mime.length); p += mime.length;
        putBe32(b, p, 0); p += 4;               // description length
        putBe32(b, p, f.coverW); p += 4;
        putBe32(b, p, f.coverH); p += 4;
        putBe32(b, p, 24); p += 4;              // colour depth
        putBe32(b, p, 0); p += 4;               // indexed colours
        putBe32(b, p, f.cover.length); p += 4;
        System.arraycopy(f.cover, 0, b, p, f.cover.length);
        return b;
    }

    static int flac(Io io, Fields f) throws IOException {
        long size = io.length();
        long pos = 4;
        List<Integer> types = new ArrayList<>();
        List<byte[]> datas = new ArrayList<>();
        boolean last = false;
        while (!last) {
            byte[] hd = io.read(pos, 4);
            if (hd.length < 4) return FAILED;
            last = (hd[0] & 0x80) != 0;
            int type = hd[0] & 0x7F;
            int len = (u8(hd, 1) << 16) | (u8(hd, 2) << 8) | u8(hd, 3);
            if (pos + 4 + len > size) return FAILED;
            types.add(type);
            datas.add(type == 1 ? new byte[0] : io.read(pos + 4, len));
            pos += 4 + len;
        }
        long metaEnd = pos;
        int vi = types.indexOf(4);
        String vendor = "Vidsic";
        List<String> comments = new ArrayList<>();
        if (vi >= 0) {
            byte[] v = datas.get(vi);
            int p = 0;
            int vl = le32(v, p);
            p += 4;
            if (vl < 0 || p + vl > v.length) return FAILED;
            vendor = new String(v, p, vl, UTF8);
            p += vl;
            int n = le32(v, p);
            p += 4;
            for (int i = 0; i < n; i++) {
                if (p + 4 > v.length) return FAILED;
                int cl = le32(v, p);
                p += 4;
                if (cl < 0 || p + cl > v.length) return FAILED;
                comments.add(new String(v, p, cl, UTF8));
                p += cl;
            }
        }
        List<String> kept = new ArrayList<>();
        for (String s : comments) {
            String k = s.contains("=") ? s.substring(0, s.indexOf('=')).toUpperCase(Locale.US) : "";
            if ((k.equals("TITLE") && f.title != null) || (k.equals("ARTIST") && f.artist != null)
                    || (k.equals("ALBUM") && f.album != null) || (k.equals("GENRE") && f.genre != null)) continue;
            kept.add(s);
        }
        if (f.title != null && !f.title.isEmpty()) kept.add("TITLE=" + f.title);
        if (f.artist != null && !f.artist.isEmpty()) kept.add("ARTIST=" + f.artist);
        if (f.album != null && !f.album.isEmpty()) kept.add("ALBUM=" + f.album);
        if (f.genre != null && !f.genre.isEmpty()) kept.add("GENRE=" + f.genre);
        List<byte[]> vb = new ArrayList<>();
        byte[] vend = vendor.getBytes(UTF8);
        byte[] t4 = new byte[4];
        putLe32(t4, 0, vend.length);
        vb.add(t4);
        vb.add(vend);
        t4 = new byte[4];
        putLe32(t4, 0, kept.size());
        vb.add(t4);
        for (String s : kept) {
            byte[] sb = s.getBytes(UTF8);
            t4 = new byte[4];
            putLe32(t4, 0, sb.length);
            vb.add(t4);
            vb.add(sb);
        }
        byte[] vorbis = cat(vb);
        if (vorbis.length >= (1 << 24)) return FAILED;

        List<Integer> nt = new ArrayList<>();
        List<byte[]> nd = new ArrayList<>();
        boolean placed = false;
        for (int i = 0; i < types.size(); i++) {
            int t = types.get(i);
            if (t == 1) continue;
            if (t == 6 && f.cover != null && isFrontCoverPicture(datas.get(i))) continue;
            if (t == 4) { nt.add(4); nd.add(vorbis); placed = true; }
            else { nt.add(t); nd.add(datas.get(i)); }
        }
        if (!placed) { nt.add(4); nd.add(vorbis); }
        if (f.cover != null) {
            byte[] pic = flacPicture(f);
            if (pic.length >= (1 << 24)) return FAILED;
            nt.add(6);
            nd.add(pic);
        }
        long used = 0;
        for (byte[] d : nd) used += 4 + d.length;
        long oldLen = metaEnd - 4, diff = oldLen - used;
        int pad;
        if (diff == 0) pad = -1;
        else if (diff >= 4) pad = (int) (diff - 4);
        else pad = 4096;
        List<byte[]> blocks = new ArrayList<>();
        for (int i = 0; i < nd.size(); i++) {
            boolean isLast = pad < 0 && i == nd.size() - 1;
            byte[] hd = new byte[4];
            hd[0] = (byte) ((isLast ? 0x80 : 0) | nt.get(i));
            hd[1] = (byte) (nd.get(i).length >> 16);
            hd[2] = (byte) (nd.get(i).length >> 8);
            hd[3] = (byte) nd.get(i).length;
            blocks.add(hd);
            blocks.add(nd.get(i));
        }
        if (pad >= 0) {
            byte[] hd = new byte[4 + pad];
            hd[0] = (byte) (0x80 | 1);
            hd[1] = (byte) (pad >> 16);
            hd[2] = (byte) (pad >> 8);
            hd[3] = (byte) pad;
            blocks.add(hd);
        }
        replace(io, 4, metaEnd, cat(blocks));
        return OK;
    }

    // ------------------------------------------------------------------ MP4 / M4A / M4V / 3GP (iTunes atoms)

    static byte[] atom(String type, byte[]... parts) {
        int n = 8;
        for (byte[] p : parts) n += p.length;
        byte[] out = new byte[n];
        putBe32(out, 0, n);
        System.arraycopy(type.getBytes(L1), 0, out, 4, 4);
        int p = 8;
        for (byte[] b : parts) { System.arraycopy(b, 0, out, p, b.length); p += b.length; }
        return out;
    }

    static String typeAt(byte[] d, int p) { return new String(d, p + 4, 4, L1); }

    /** Child atoms in d[from, to). 32-bit sizes only. */
    static List<byte[]> kids(byte[] d, int from, int to) throws IOException {
        List<byte[]> l = new ArrayList<>();
        int p = from;
        while (p + 8 <= to) {
            int sz = be32(d, p);
            if (sz < 8 || p + sz > to) throw new IOException("bad atom");
            byte[] a = new byte[sz];
            System.arraycopy(d, p, a, 0, sz);
            l.add(a);
            p += sz;
        }
        if (p != to) throw new IOException("trailing bytes");
        return l;
    }

    static byte[] itunesText(byte[] type4, String text) {
        byte[] t = text.getBytes(UTF8);
        byte[] data = new byte[8 + t.length];
        data[3] = 1;
        System.arraycopy(t, 0, data, 8, t.length);
        byte[] inner = atom("data", data);
        byte[] out = new byte[8 + inner.length];
        putBe32(out, 0, out.length);
        System.arraycopy(type4, 0, out, 4, 4);
        System.arraycopy(inner, 0, out, 8, inner.length);
        return out;
    }

    static final byte[] NAM = {(byte) 0xA9, 'n', 'a', 'm'}, ART = {(byte) 0xA9, 'A', 'R', 'T'}, ALB = {(byte) 0xA9, 'a', 'l', 'b'},
            GEN = {(byte) 0xA9, 'g', 'e', 'n'}, DESC = {'d', 'e', 's', 'c'};

    static boolean same(byte[] atom, byte[] t4) {
        return atom[4] == t4[0] && atom[5] == t4[1] && atom[6] == t4[2] && atom[7] == t4[3];
    }

    static byte[] buildIlst(byte[] oldIlst, Fields f, boolean video) throws IOException {
        List<byte[]> items = new ArrayList<>();
        if (oldIlst != null) {
            for (byte[] it : kids(oldIlst, 8, oldIlst.length)) {
                if ((f.title != null && same(it, NAM)) || (f.artist != null && same(it, ART)) || (f.album != null && same(it, ALB))
                        || (f.genre != null && (same(it, GEN) || typeAt(it, 0).equals("gnre")))
                        || (f.desc != null && same(it, DESC))
                        || (f.cover != null && typeAt(it, 0).equals("covr"))) continue;
                items.add(it);
            }
        }
        if (f.title != null && !f.title.isEmpty()) items.add(itunesText(NAM, f.title));
        if (f.artist != null && !f.artist.isEmpty()) items.add(itunesText(ART, f.artist));
        if (f.album != null && !f.album.isEmpty()) items.add(itunesText(ALB, f.album));
        if (f.genre != null && !f.genre.isEmpty()) items.add(itunesText(GEN, f.genre));
        if (f.desc != null && !f.desc.isEmpty()) items.add(itunesText(DESC, f.desc));
        if (f.cover != null) items.add(covrItem(f));
        return atom("ilst", cat(items));
    }

    /** covr item: one data atom, flags 13 = JPEG, 14 = PNG. */
    static byte[] covrItem(Fields f) {
        byte[] data = new byte[8 + f.cover.length];
        data[3] = (byte) ("image/png".equals(f.coverMime) ? 14 : 13);
        System.arraycopy(f.cover, 0, data, 8, f.cover.length);
        return atom("covr", atom("data", data));
    }

    static byte[] hdlr() {
        byte[] p = new byte[25];
        p[8] = 'm'; p[9] = 'd'; p[10] = 'i'; p[11] = 'r';
        p[12] = 'a'; p[13] = 'p'; p[14] = 'p'; p[15] = 'l';
        return atom("hdlr", p);
    }

    static byte[] buildMeta(byte[] oldMeta, Fields f, boolean video) throws IOException {
        List<byte[]> kids = new ArrayList<>();
        byte[] oldIlst = null;
        boolean hasHdlr = false;
        if (oldMeta != null) {
            for (byte[] k : kids(oldMeta, 12, oldMeta.length)) {
                String t = typeAt(k, 0);
                if (t.equals("ilst")) { oldIlst = k; kids.add(null); }
                else { if (t.equals("hdlr")) hasHdlr = true; kids.add(k); }
            }
        }
        byte[] ilst = buildIlst(oldIlst, f, video);
        List<byte[]> parts = new ArrayList<>();
        parts.add(new byte[4]);
        if (oldMeta == null || !hasHdlr) parts.add(hdlr());
        boolean put = false;
        for (byte[] k : kids) {
            if (k == null) { parts.add(ilst); put = true; } else parts.add(k);
        }
        if (!put) parts.add(ilst);
        return atom("meta", cat(parts));
    }

    static byte[] buildUdta(byte[] oldUdta, Fields f, boolean video) throws IOException {
        List<byte[]> parts = new ArrayList<>();
        boolean done = false;
        if (oldUdta != null) {
            for (byte[] k : kids(oldUdta, 8, oldUdta.length)) {
                if (typeAt(k, 0).equals("meta") && !done) { parts.add(buildMeta(k, f, video)); done = true; }
                else parts.add(k);
            }
        }
        if (!done) parts.add(buildMeta(null, f, video));
        return atom("udta", cat(parts));
    }

    /** Add {@code delta} to every chunk offset in stco / co64 that points at or after {@code threshold}. */
    static void shiftOffsets(byte[] d, int from, int to, long delta, long threshold) throws IOException {
        int p = from;
        while (p + 8 <= to) {
            int sz = be32(d, p);
            if (sz < 8 || p + sz > to) throw new IOException("bad atom");
            String t = typeAt(d, p);
            if (t.equals("trak") || t.equals("mdia") || t.equals("minf") || t.equals("stbl")) shiftOffsets(d, p + 8, p + sz, delta, threshold);
            else if (t.equals("stco")) {
                int n = be32(d, p + 12);
                for (int i = 0; i < n; i++) {
                    int q = p + 16 + i * 4;
                    long v = be32(d, q) & 0xFFFFFFFFL;
                    if (v >= threshold) {
                        v += delta;
                        if (v < 0 || v > 0xFFFFFFFFL) throw new IOException("offset overflow");
                        putBe32(d, q, (int) v);
                    }
                }
            } else if (t.equals("co64")) {
                int n = be32(d, p + 12);
                for (int i = 0; i < n; i++) {
                    int q = p + 16 + i * 8;
                    long v = ((be32(d, q) & 0xFFFFFFFFL) << 32) | (be32(d, q + 4) & 0xFFFFFFFFL);
                    if (v >= threshold) {
                        v += delta;
                        putBe32(d, q, (int) (v >>> 32));
                        putBe32(d, q + 4, (int) v);
                    }
                }
            }
            p += sz;
        }
    }

    static int mp4(Io io, Fields f, boolean video) throws IOException {
        long size = io.length();
        List<long[]> top = new ArrayList<>();
        List<String> names = new ArrayList<>();
        long pos = 0;
        while (pos + 8 <= size) {
            byte[] hd = io.read(pos, 16);
            long sz = be32(hd, 0) & 0xFFFFFFFFL;
            int hl = 8;
            if (sz == 1) { sz = ((be32(hd, 8) & 0xFFFFFFFFL) << 32) | (be32(hd, 12) & 0xFFFFFFFFL); hl = 16; }
            else if (sz == 0) sz = size - pos;
            if (sz < hl || pos + sz > size) return FAILED;
            top.add(new long[]{pos, sz});
            names.add(new String(hd, 4, 4, L1));
            pos += sz;
        }
        if (pos != size) return FAILED;
        int mi = names.indexOf("moov");
        if (mi < 0) return UNSUPPORTED;
        long mStart = top.get(mi)[0], mLen = top.get(mi)[1];
        if (mLen > 120L * 1024 * 1024) return UNSUPPORTED;
        byte[] moov = io.read(mStart, (int) mLen);
        if (moov.length != mLen) return FAILED;
        int hdr = be32(moov, 0) == 1 ? 16 : 8;
        List<byte[]> mk = kids(moov, hdr, moov.length);
        List<byte[]> nk = new ArrayList<>();
        boolean done = false;
        for (byte[] k : mk) {
            if (typeAt(k, 0).equals("udta") && !done) { nk.add(buildUdta(k, f, video)); done = true; }
            else nk.add(k);
        }
        if (!done) nk.add(buildUdta(null, f, video));
        byte[] newMoov = atom("moov", cat(nk));
        // If media data follows moov, changing moov's size changes chunk offsets. Grow in-place
        // when needed and update stco/co64 entries; no second media file is produced.
        boolean dataAfter = false, fragmented = names.contains("moof") || names.contains("sidx");
        for (int i = 0; i < names.size(); i++) if (names.get(i).equals("mdat") && top.get(i)[0] > mStart) dataAfter = true;
        long oldEnd = mStart + mLen;
        long oldRegionLong = mLen;
        boolean absorbFree = dataAfter && mi + 1 < names.size()
                && (names.get(mi + 1).equals("free") || names.get(mi + 1).equals("skip"));
        if (absorbFree) {
            oldEnd += top.get(mi + 1)[1];
            oldRegionLong += top.get(mi + 1)[1];
        }
        if (oldRegionLong > Integer.MAX_VALUE) return UNSUPPORTED;
        int oldRegion = (int) oldRegionLong;
        byte[] region;
        if (!dataAfter) {
            region = newMoov;
        } else if (newMoov.length <= oldRegion && oldRegion - newMoov.length == 0) {
            region = newMoov;
        } else if (newMoov.length <= oldRegion && oldRegion - newMoov.length >= 8) {
            int extra = oldRegion - newMoov.length;
            region = new byte[oldRegion];
            System.arraycopy(newMoov, 0, region, 0, newMoov.length);
            putBe32(region, newMoov.length, extra);
            System.arraycopy("free".getBytes(L1), 0, region, newMoov.length + 4, 4);
        } else {
            int pad = 2048;
            if (newMoov.length < oldRegion && oldRegion - newMoov.length < 8) pad = 8;
            region = new byte[newMoov.length + pad];
            System.arraycopy(newMoov, 0, region, 0, newMoov.length);
            putBe32(region, newMoov.length, pad);
            System.arraycopy("free".getBytes(L1), 0, region, newMoov.length + 4, 4);
        }
        long delta = region.length - oldRegionLong;
        if (dataAfter && delta != 0) {
            if (fragmented) return UNSUPPORTED;
            byte[] mv = new byte[newMoov.length];
            System.arraycopy(region, 0, mv, 0, mv.length);
            shiftOffsets(mv, 8, mv.length, delta, oldEnd);
            System.arraycopy(mv, 0, region, 0, mv.length);
        }
        replace(io, mStart, oldEnd, region);
        return OK;
    }
}
