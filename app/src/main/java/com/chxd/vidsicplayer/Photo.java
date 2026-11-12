package com.chxd.vidsicplayer;

import android.net.Uri;

class Photo {
    long id;
    Uri uri;
    String title, name, folder, path;
    long added, size;
    int w, h;
    boolean gif;
    boolean svg;

    String key() { return (svg ? "ps" : "p") + id; }
}
