package com.chxd.vidsicplayer;

import android.content.Context;
import java.util.ArrayList;
import java.util.List;

final class Group {

    static final String ALL = "all", FOLDER = "folder", ARTIST = "artist", ALBUM = "album", GENRE = "genre",
            PLAYLIST = "playlist", FAV = "fav";

    static final String P_ALL = "pall", P_ALBUM = "palbum", P_COL = "pcol", P_FAV = "pfav", P_GIF = "pgif", P_SVG = "psvg";

    final String kind, name;
    final boolean video;

    Group(String kind, boolean video, String name) {
        this.kind = kind;
        this.video = video;
        this.name = name == null ? "" : name;
    }

    boolean photo() { return kind.startsWith("p"); }

    String ser() { return kind + "\u001f" + (video ? "1" : "0") + "\u001f" + name; }

    static Group parse(String s) {
        if (s == null) return null;
        String[] p = s.split("\u001f", 3);
        if (p.length < 3) return null;
        return new Group(p[0], "1".equals(p[1]), p[2]);
    }

    int icon() {
        switch (kind) {
            case FOLDER: return R.drawable.ic_folder;
            case ARTIST: return R.drawable.ic_person;
            case ALBUM: return R.drawable.ic_album;
            case GENRE: return R.drawable.ic_music;
            case PLAYLIST: return R.drawable.ic_playlist;
            case FAV: case P_FAV: return R.drawable.ic_heart_fill;
            case ALL: return video ? R.drawable.ic_movie : R.drawable.ic_music;
            case P_COL: return R.drawable.ic_collections;
            case P_GIF: return R.drawable.ic_slideshow;
            default: return photo() ? R.drawable.ic_image : R.drawable.ic_folder;
        }
    }

    String kindLabel() {
        switch (kind) {
            case ALL: return video ? "All videos" : "All audios";
            case FOLDER: return "Folder";
            case ARTIST: return video ? "Author" : "Artist";
            case ALBUM: return "Album";
            case GENRE: return "Genre";
            case PLAYLIST: return "Playlist";
            case FAV: return "Favorites";
            case P_ALL: return "All pictures";
            case P_ALBUM: return "Photo album";
            case P_COL: return "Collection";
            case P_FAV: return "Favorite pictures";
            case P_GIF: return "GIFs";
            case P_SVG: return "SVG drawings";
            default: return "Group";
        }
    }

    private static List<Media> audios, videos;
    private static List<Photo> photos;
    private static long loadedAt;

    private static synchronized void ensure(Context c) {
        long now = android.os.SystemClock.uptimeMillis();
        if (audios != null && now - loadedAt < 60000) return;
        Store s = Engine.get(c.getApplicationContext()).store;
        List<Media> la = new ArrayList<>(), lv = new ArrayList<>();
        for (Media m : MediaLoader.load(c, false)) if (!m.hidden || s.bool("hid_a", false)) { s.applyMeta(m); la.add(m); }
        for (Media m : MediaLoader.load(c, true)) if (!m.hidden || s.bool("hid_v", false)) { s.applyMeta(m); lv.add(m); }
        List<Photo> lp = new ArrayList<>();
        try { lp = PhotoLoader.load(c); } catch (Throwable e) { }
        audios = la;
        videos = lv;
        photos = lp;
        loadedAt = now;
    }

    static synchronized void invalidate() { audios = null; }

    static List<Media> library(Context c, boolean video) { ensure(c); return new ArrayList<>(video ? videos : audios); }

    static List<Photo> allPictures(Context c) { ensure(c); return new ArrayList<>(photos); }

    static String albumOf(Media m) { return m.album == null || m.album.isEmpty() ? "Unknown album" : m.album; }

    static String genreOf(Media m) { return m.genre == null || m.genre.isEmpty() ? "Unknown genre" : m.genre; }

    List<Media> media(Context c) {
        if (photo()) return new ArrayList<>();
        Store s = Engine.get(c.getApplicationContext()).store;
        List<Media> lib = library(c, video), out = new ArrayList<>();
        if (kind.equals(PLAYLIST) || kind.equals(FAV)) {
            List<String> keys = kind.equals(FAV) ? s.list("fav") : s.items(video, name);
            for (String k : keys) for (Media m : lib) if (m.key().equals(k)) { out.add(m); break; }
            return out;
        }
        for (Media m : lib) {
            boolean in;
            switch (kind) {
                case FOLDER: in = name.equals(m.path); break;
                case ARTIST: in = name.equals(m.artist); break;
                case ALBUM: in = name.equals(albumOf(m)); break;
                case GENRE: in = name.equals(genreOf(m)); break;
                default: in = true; break;
            }
            if (in) out.add(m);
        }
        return out;
    }

    List<Photo> pictureItems(Context c) {
        if (!photo()) return new ArrayList<>();
        Store s = Engine.get(c.getApplicationContext()).store;
        List<Photo> lib = allPictures(c), out = new ArrayList<>();
        if (kind.equals(P_COL) || kind.equals(P_FAV)) {
            List<String> keys = kind.equals(P_FAV) ? s.list("pfav") : s.collectionItems(name);
            for (String k : keys) for (Photo p : lib) if (p.key().equals(k)) { out.add(p); break; }
            return out;
        }
        for (Photo p : lib) {
            boolean in;
            switch (kind) {
                case P_ALBUM: in = name.equals(p.path); break;
                case P_GIF: in = p.gif; break;
                case P_SVG: in = p.svg; break;
                default: in = true; break;
            }
            if (in) out.add(p);
        }
        return out;
    }

    String title(Context c) {
        if (kind.equals(FOLDER) || kind.equals(P_ALBUM)) {
            String[] seg = name.split("/");
            return seg.length > 0 && !seg[seg.length - 1].isEmpty() ? seg[seg.length - 1] : "Other";
        }
        if (kind.equals(ALL) || kind.equals(FAV) || kind.equals(P_ALL) || kind.equals(P_FAV) || kind.equals(P_GIF) || kind.equals(P_SVG))
            return kindLabel();
        return name;
    }

    static List<Group> all(Context c, boolean wantMedia, boolean wantPhotos) {
        List<Group> out = new ArrayList<>();
        Store s = Engine.get(c.getApplicationContext()).store;
        if (wantMedia) for (boolean v : new boolean[]{false, true}) {
            out.add(new Group(ALL, v, ""));
            out.add(new Group(FAV, v, ""));
            for (String n : s.playlists(v)) out.add(new Group(PLAYLIST, v, n));
            java.util.LinkedHashSet<String> folders = new java.util.LinkedHashSet<>(), artists = new java.util.LinkedHashSet<>(),
                    albums = new java.util.LinkedHashSet<>(), genres = new java.util.LinkedHashSet<>();
            for (Media m : library(c, v)) {
                if (m.path != null) folders.add(m.path);
                if (m.artist != null && !m.artist.isEmpty()) artists.add(m.artist);
                if (!v) { albums.add(albumOf(m)); genres.add(genreOf(m)); }
            }
            for (String x : folders) out.add(new Group(FOLDER, v, x));
            for (String x : artists) out.add(new Group(ARTIST, v, x));
            for (String x : albums) out.add(new Group(ALBUM, v, x));
            for (String x : genres) out.add(new Group(GENRE, v, x));
        }
        if (wantPhotos) {
            out.add(new Group(P_ALL, false, ""));
            out.add(new Group(P_FAV, false, ""));
            out.add(new Group(P_GIF, false, ""));
            out.add(new Group(P_SVG, false, ""));
            for (String n : s.collections()) out.add(new Group(P_COL, false, n));
            java.util.LinkedHashSet<String> paths = new java.util.LinkedHashSet<>();
            for (Photo p : allPictures(c)) if (p.path != null) paths.add(p.path);
            for (String x : paths) out.add(new Group(P_ALBUM, false, x));
        }
        return out;
    }
}
