package com.chxd.vidsicplayer;

import android.app.Activity;
import android.content.Intent;
import android.os.Bundle;
import java.util.ArrayList;
import java.util.List;

public class WidgetPlay extends Activity {
    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        Intent in = getIntent();
        final Group g = Group.parse(in.getStringExtra("group"));
        final String item = in.getStringExtra("item");
        if (g == null || item == null) { finish(); return; }
        if (g.photo()) { openApp(g, item, false); return; }
        new Thread(() -> {
            List<Media> l = new ArrayList<>();
            int at = -1;
            try {
                l = g.media(this);
                for (int i = 0; i < l.size(); i++) if (l.get(i).key().equals(item)) { at = i; break; }
            } catch (Throwable e) { }
            final List<Media> list = l;
            final int idx = at;
            runOnUiThread(() -> {
                if (idx < 0) { openApp(g, item, true); return; }
                try {
                    Engine en = Engine.get(getApplicationContext());
                    if (list.get(idx).video) en.setAudioMode(false);
                    en.play(new ArrayList<>(list), idx);
                    finish();
                } catch (Throwable e) { openApp(g, item, true); }
            });
        }).start();
    }

    void openApp(Group g, String item, boolean exitAfter) {
        Intent in = new Intent(this, MainActivity.class).setAction(MainActivity.ACT_GROUP)
                .putExtra("group", g.ser()).putExtra("item", item).putExtra("play", true).putExtra("exit", exitAfter);
        try { startActivity(in); } catch (Exception e) { }
        finish();
    }
}
