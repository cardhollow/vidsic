package com.chxd.vidsicplayer;

import android.net.Uri;

class Media {
    long id;
    Uri uri;
    String title, artist, album, folder, path, name;
    long duration, added, size;
    boolean video, hidden, folderHidden;
    String filePath; // set only for files found by the hidden-file scan

    String key() { return (video ? "v" : "a") + id; }
}
