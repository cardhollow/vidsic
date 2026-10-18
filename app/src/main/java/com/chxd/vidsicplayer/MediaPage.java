package com.chxd.vidsicplayer;

import android.app.Dialog;
import android.content.Context;
import android.graphics.Color;
import android.graphics.drawable.ColorDrawable;
import android.text.Editable;
import android.text.TextWatcher;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.Window;
import android.view.inputmethod.EditorInfo;
import android.view.inputmethod.InputMethodManager;
import android.widget.BaseAdapter;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.HorizontalScrollView;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ListView;
import android.widget.TextView;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * One page for Audio or Video.
 * Tabs: Audios/Videos | Folders | Authors | Albums (audio only) | Playlist | Favorite
 * Under the tabs: sort, list/grid switch and multi-select.
 */
class MediaPage {
    static final int HDR = 0, MEDIA = 1, FOLDER = 2, PL = 3, ACT = 4, EMPTY = 5, SUG = 6, GRID = 7, N = 8;
    static final int T_ALL = 0, T_FOLDERS = 1, T_AUTHORS = 2, T_ALBUMS = 3, T_PL = 4, T_FAV = 5;
    static final String[] SORTS = {"Name", "Date", "Duration", "Size", "Most played"};

    static class Row {
        int type, icon;
        String title, sub, action;
        Media m;
        Runnable click, more;
        List<Media> ctx;
        Runnable removeFn;
        List<Row> cells;
    }

    /** A folder / author / album / playlist entry. */
    static class G {
        String title, sub;
        int icon;
        boolean pl;
        List<Media> items;
        Runnable click, more;
    }

    final MainActivity a;
    final boolean video;
    final Store s;
    final LinearLayout root;
    final EditText search;
    final ImageView clear;
    final int[] tabIds;
    final TextView[] tabs;
    final View[] bars;
    final TextView info;
    final ImageView sortBtn, viewBtn, selBtn, allBtn;
    final LinearLayout actionBar;
    final ListView lv;
    final ArrayList<Row> rows = new ArrayList<>();
    final ArrayList<Media> shown = new ArrayList<>(); // media rows in display order
    final LinkedHashSet<String> sel = new LinkedHashSet<>();
    final BaseAdapter ad;
    int tab = T_ALL;
    String openFolder, openGrp, openPl, pending = "";
    boolean showAll, selecting;

    MediaPage(MainActivity act, boolean video) {
        this.a = act;
        this.video = video;
        this.s = act.store;
        root = new LinearLayout(a);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(Ui.BG);
        root.setFocusableInTouchMode(true);
        root.setDescendantFocusability(ViewGroup.FOCUS_BEFORE_DESCENDANTS);

        // search bar
        LinearLayout sb = new LinearLayout(a);
        sb.setGravity(Gravity.CENTER_VERTICAL);
        sb.setBackground(Ui.shape(Ui.SURF, 24, a));
        sb.setPadding(Ui.dp(a, 14), 0, Ui.dp(a, 4), 0);
        sb.addView(Ui.icon(a, R.drawable.ic_search, Ui.SUB, 20));
        search = new EditText(a);
        search.setHint(video ? "Search videos" : "Search audios");
        search.setHintTextColor(Ui.SUB);
        search.setTextColor(Ui.TXT);
        search.setTextSize(15);
        search.setBackground(null);
        search.setSingleLine(true);
        search.setImeOptions(EditorInfo.IME_ACTION_SEARCH);
        search.setPadding(Ui.dp(a, 10), 0, 0, 0);
        sb.addView(search, new LinearLayout.LayoutParams(0, Ui.MATCH, 1));
        clear = Ui.btn(a, R.drawable.ic_close, Ui.SUB, 18, 9);
        clear.setVisibility(View.GONE);
        clear.setOnClickListener(v -> search.setText(""));
        sb.addView(clear);
        LinearLayout.LayoutParams sp = new LinearLayout.LayoutParams(Ui.MATCH, Ui.dp(a, 48));
        sp.setMargins(Ui.dp(a, 16), Ui.dp(a, 12), Ui.dp(a, 16), Ui.dp(a, 4));
        root.addView(sb, sp);

        search.addTextChangedListener(new TextWatcher() {
            public void beforeTextChanged(CharSequence x, int i, int j, int k) { }
            public void onTextChanged(CharSequence x, int i, int j, int k) { }
            public void afterTextChanged(Editable e) {
                pending = e.toString().trim();
                clear.setVisibility(pending.isEmpty() ? View.GONE : View.VISIBLE);
                refresh();
            }
        });
        search.setOnFocusChangeListener((v, f) -> refresh());
        search.setOnEditorActionListener((v, id, ev) -> { unfocus(); return true; });

        // header tabs (scrollable: there can be six of them)
        tabIds = video ? new int[]{T_ALL, T_FOLDERS, T_AUTHORS, T_PL, T_FAV}
                : new int[]{T_ALL, T_FOLDERS, T_AUTHORS, T_ALBUMS, T_PL, T_FAV};
        String[] names = video ? new String[]{"Videos", "Folders", "Authors", "Playlist", "Favorite"}
                : new String[]{"Audios", "Folders", "Authors", "Albums", "Playlist", "Favorite"};
        tabs = new TextView[tabIds.length];
        bars = new View[tabIds.length];
        HorizontalScrollView hs = new HorizontalScrollView(a);
        hs.setHorizontalScrollBarEnabled(false);
        hs.setOverScrollMode(View.OVER_SCROLL_NEVER);
        LinearLayout tb = new LinearLayout(a);
        for (int i = 0; i < tabIds.length; i++) {
            final int id = tabIds[i];
            LinearLayout col = new LinearLayout(a);
            col.setOrientation(LinearLayout.VERTICAL);
            col.setBackground(Ui.rip(0, 0, a));
            tabs[i] = Ui.text(a, names[i], 14, Ui.SUB, true);
            tabs[i].setGravity(Gravity.CENTER);
            tabs[i].setPadding(Ui.dp(a, 14), Ui.dp(a, 12), Ui.dp(a, 14), Ui.dp(a, 10));
            bars[i] = new View(a);
            col.addView(tabs[i], new LinearLayout.LayoutParams(Ui.WRAP, Ui.WRAP));
            LinearLayout.LayoutParams bp = new LinearLayout.LayoutParams(Ui.MATCH, Ui.dp(a, 3));
            bp.setMargins(Ui.dp(a, 10), 0, Ui.dp(a, 10), 0);
            col.addView(bars[i], bp);
            col.setOnClickListener(v -> { tab = id; openFolder = null; openGrp = null; openPl = null; exitSelect(false); unfocus(); refresh(); });
            tb.addView(col, new LinearLayout.LayoutParams(Ui.WRAP, Ui.WRAP));
        }
        hs.addView(tb);
        root.addView(hs, new LinearLayout.LayoutParams(Ui.MATCH, Ui.WRAP));

        // tool row: [sorted by ...] ........ [sort] [list/grid] [select]
        LinearLayout tool = new LinearLayout(a);
        tool.setGravity(Gravity.CENTER_VERTICAL);
        tool.setPadding(Ui.dp(a, 16), 0, Ui.dp(a, 6), 0);
        info = Ui.text(a, "", 12, Ui.SUB, false);
        tool.addView(info, new LinearLayout.LayoutParams(0, Ui.WRAP, 1));
        allBtn = Ui.btn(a, R.drawable.ic_select_all, Ui.TXT, 22, 9);
        allBtn.setOnClickListener(v -> selectAll());
        sortBtn = Ui.btn(a, R.drawable.ic_sort, Ui.TXT, 22, 9);
        sortBtn.setOnClickListener(v -> showSort());
        viewBtn = Ui.btn(a, R.drawable.ic_grid, Ui.TXT, 22, 9);
        viewBtn.setOnClickListener(v -> { s.setGrid(video, !s.grid(video)); refresh(); });
        selBtn = Ui.btn(a, R.drawable.ic_select, Ui.TXT, 22, 9);
        selBtn.setOnClickListener(v -> { if (selecting) exitSelect(true); else { selecting = true; sel.clear(); refresh(); } });
        tool.addView(allBtn);
        tool.addView(sortBtn);
        tool.addView(viewBtn);
        tool.addView(selBtn);
        root.addView(tool, new LinearLayout.LayoutParams(Ui.MATCH, Ui.WRAP));

        lv = new ListView(a);
        lv.setDivider(null);
        lv.setSelector(new ColorDrawable(0));
        lv.setOverScrollMode(View.OVER_SCROLL_NEVER);
        lv.setClipToPadding(false);
        lv.setPadding(0, Ui.dp(a, 4), 0, Ui.dp(a, 12));
        ad = new BaseAdapter() {
            public int getCount() { return rows.size(); }
            public Object getItem(int i) { return rows.get(i); }
            public long getItemId(int i) { return i; }
            public int getViewTypeCount() { return N; }
            public int getItemViewType(int i) { return rows.get(i).type; }
            public View getView(int i, View cv, ViewGroup p) { return build(rows.get(i)); }
        };
        lv.setAdapter(ad);
        root.addView(lv, new LinearLayout.LayoutParams(Ui.MATCH, 0, 1));

        // multi-select action bar: Play | Queue | Playlist | Favorite | Delete
        actionBar = new LinearLayout(a);
        actionBar.setBackgroundColor(Ui.SURF2);
        actionBar.setVisibility(View.GONE);
        actionBar.addView(act(R.drawable.ic_play, "Play", Ui.TXT, this::actPlay), new LinearLayout.LayoutParams(0, Ui.WRAP, 1));
        actionBar.addView(act(R.drawable.ic_add_queue, "Queue", Ui.TXT, this::actQueue), new LinearLayout.LayoutParams(0, Ui.WRAP, 1));
        actionBar.addView(act(R.drawable.ic_playlist, "Playlist", Ui.TXT, this::actPlaylist), new LinearLayout.LayoutParams(0, Ui.WRAP, 1));
        actionBar.addView(act(R.drawable.ic_heart, "Favorite", Ui.TXT, this::actFav), new LinearLayout.LayoutParams(0, Ui.WRAP, 1));
        actionBar.addView(act(R.drawable.ic_delete, "Delete", Ui.RED, this::actDelete), new LinearLayout.LayoutParams(0, Ui.WRAP, 1));
        root.addView(actionBar, new LinearLayout.LayoutParams(Ui.MATCH, Ui.WRAP));
        refresh();
    }

    String sk() { return s.sk(video); }

    void unfocus() {
        InputMethodManager imm = (InputMethodManager) a.getSystemService(Context.INPUT_METHOD_SERVICE);
        imm.hideSoftInputFromWindow(search.getWindowToken(), 0);
        root.requestFocus();
    }

    boolean onBack() {
        if (selecting) { exitSelect(true); return true; }
        if (openFolder != null || openGrp != null || openPl != null) { openFolder = null; openGrp = null; openPl = null; refresh(); return true; }
        if (!search.getText().toString().isEmpty()) { search.setText(""); return true; }
        if (search.hasFocus()) { unfocus(); return true; }
        if (tab != T_ALL) { tab = T_ALL; refresh(); return true; }
        return false;
    }

    // ---------- sorting ----------
    Comparator<Media> mediaCmp() {
        final int mode = s.sortMode(video);
        Comparator<Media> c = (x, y) -> {
            switch (mode) {
                case 0: return x.title.compareToIgnoreCase(y.title);
                case 2: return Long.compare(x.duration, y.duration);
                case 3: return Long.compare(x.size, y.size);
                case 4: return Integer.compare(s.plays(x), s.plays(y));
                default: return Long.compare(x.added, y.added);
            }
        };
        Comparator<Media> out = s.sortAsc(video) ? c : Collections.<Media>reverseOrder(c);
        return out;
    }

    List<Media> sorted(List<Media> in) {
        List<Media> l = new ArrayList<>(in);
        Collections.sort(l, mediaCmp());
        return l;
    }

    long metric(List<Media> l, int mode) {
        long r = 0;
        for (Media m : l) {
            if (mode == 1) r = Math.max(r, m.added);
            else if (mode == 2) r += m.duration;
            else if (mode == 3) r += m.size;
            else if (mode == 4) r += s.plays(m);
        }
        return r;
    }

    void addGroups(List<G> gs) {
        final int mode = s.sortMode(video);
        Comparator<G> c = (x, y) -> mode == 0 ? x.title.compareToIgnoreCase(y.title)
                : Long.compare(metric(x.items, mode), metric(y.items, mode));
        Comparator<G> cc = s.sortAsc(video) ? c : Collections.<G>reverseOrder(c);
        Collections.sort(gs, cc);
        for (G g : gs) {
            Row x = new Row();
            x.type = g.pl ? PL : FOLDER;
            x.title = g.title;
            x.sub = g.sub;
            x.icon = g.icon;
            x.click = g.click;
            x.more = g.more;
            rows.add(x);
        }
    }

    void showSort() {
        final Dialog d = new Dialog(a);
        d.requestWindowFeature(Window.FEATURE_NO_TITLE);
        final int[] mode = {s.sortMode(video)};
        final boolean[] asc = {s.sortAsc(video)};
        LinearLayout box = new LinearLayout(a);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setBackground(Ui.shape(Ui.SURF, 20, a));
        box.setPadding(0, Ui.dp(a, 18), 0, Ui.dp(a, 12));

        TextView t = Ui.text(a, "Sort by", 18, Ui.TXT, true);
        t.setPadding(Ui.dp(a, 22), 0, Ui.dp(a, 22), Ui.dp(a, 8));
        box.addView(t);

        final Ui.Radio[] rm = new Ui.Radio[SORTS.length];
        for (int i = 0; i < SORTS.length; i++) {
            final int k = i;
            rm[i] = new Ui.Radio(a, i == mode[0]);
            box.addView(optRow(SORTS[i], rm[i], () -> { mode[0] = k; for (int j = 0; j < rm.length; j++) rm[j].set(j == k); }));
        }
        box.addView(line());
        final Ui.Radio[] ra = {new Ui.Radio(a, asc[0]), new Ui.Radio(a, !asc[0])};
        box.addView(optRow("Ascending", ra[0], () -> { asc[0] = true; ra[0].set(true); ra[1].set(false); }));
        box.addView(optRow("Descending", ra[1], () -> { asc[0] = false; ra[0].set(false); ra[1].set(true); }));
        box.addView(line());

        LinearLayout btns = new LinearLayout(a);
        btns.setGravity(Gravity.END | Gravity.CENTER_VERTICAL);
        btns.setPadding(Ui.dp(a, 12), Ui.dp(a, 10), Ui.dp(a, 16), 0);
        TextView cancel = Ui.text(a, "Cancel", 15, Ui.ACC, true);
        cancel.setPadding(Ui.dp(a, 18), Ui.dp(a, 10), Ui.dp(a, 18), Ui.dp(a, 10));
        cancel.setBackground(Ui.rip(0, 20, a));
        cancel.setOnClickListener(v -> d.dismiss());
        TextView ok = Ui.text(a, "Confirm", 15, Ui.ONACC, true);
        ok.setPadding(Ui.dp(a, 22), Ui.dp(a, 10), Ui.dp(a, 22), Ui.dp(a, 10));
        ok.setBackground(Ui.rip(Ui.ACC, 20, a));
        ok.setOnClickListener(v -> { s.setSort(video, mode[0], asc[0]); d.dismiss(); refresh(); });
        btns.addView(cancel);
        btns.addView(ok);
        box.addView(btns, new LinearLayout.LayoutParams(Ui.MATCH, Ui.WRAP));

        d.setContentView(box);
        d.show();
        Window w = d.getWindow();
        w.setBackgroundDrawable(new ColorDrawable(0));
        int sw = a.getResources().getDisplayMetrics().widthPixels;
        w.setLayout(Math.min(Ui.dp(a, 340), sw * 9 / 10), ViewGroup.LayoutParams.WRAP_CONTENT);
    }

    View optRow(String label, Ui.Radio r, Runnable click) {
        LinearLayout row = new LinearLayout(a);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding(Ui.dp(a, 22), 0, Ui.dp(a, 22), 0);
        row.setMinimumHeight(Ui.dp(a, 46));
        row.setBackground(Ui.rip(0, 0, a));
        row.addView(r);
        TextView t = Ui.text(a, label, 16, Ui.TXT, false);
        t.setPadding(Ui.dp(a, 16), 0, 0, 0);
        row.addView(t);
        row.setOnClickListener(v -> click.run());
        return row;
    }

    View line() {
        View v = new View(a);
        v.setBackgroundColor(Ui.SURF2);
        LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(Ui.MATCH, Math.max(1, Ui.dp(a, 1)));
        p.setMargins(0, Ui.dp(a, 8), 0, Ui.dp(a, 8));
        v.setLayoutParams(p);
        return v;
    }

    // ---------- multi-select ----------
    void exitSelect(boolean redraw) {
        if (!selecting && sel.isEmpty()) return;
        selecting = false;
        sel.clear();
        if (redraw) refresh();
    }

    void toggleSel(Media m) {
        if (!sel.remove(m.key())) sel.add(m.key());
        refresh();
    }

    void selectAll() {
        boolean all = true;
        for (Media m : shown) if (!sel.contains(m.key())) { all = false; break; }
        if (all) sel.clear(); else for (Media m : shown) sel.add(m.key());
        refresh();
    }

    List<Media> picked() {
        List<Media> l = new ArrayList<>();
        Set<String> seen = new LinkedHashSet<>();
        for (Media m : shown) if (sel.contains(m.key()) && seen.add(m.key())) l.add(m);
        return l;
    }

    View act(int icon, String label, int color, Runnable r) {
        LinearLayout c = new LinearLayout(a);
        c.setOrientation(LinearLayout.VERTICAL);
        c.setGravity(Gravity.CENTER);
        c.setPadding(0, Ui.dp(a, 8), 0, Ui.dp(a, 8));
        c.setBackground(Ui.rip(0, 0, a));
        ImageView i = Ui.icon(a, icon, color, 22);
        c.addView(i);
        TextView t = Ui.text(a, label, 11, color, true);
        t.setGravity(Gravity.CENTER);
        c.addView(t, new LinearLayout.LayoutParams(Ui.WRAP, Ui.WRAP));
        c.setOnClickListener(v -> {
            if (sel.isEmpty()) { a.toast("Select something first"); return; }
            r.run();
        });
        return c;
    }

    void actPlay() { List<Media> l = picked(); exitSelect(true); a.playMedia(l, 0); }
    void actQueue() {
        List<Media> l = picked();
        a.engine.addAllToQueue(l);
        a.toast(l.size() + (l.size() == 1 ? " item" : " items") + " added to queue");
        exitSelect(true);
    }
    void actPlaylist() { a.pickPlaylist(picked(), () -> exitSelect(true)); }
    void actFav() {
        List<Media> l = picked();
        boolean allFav = true;
        for (Media m : l) if (!s.isFav(m)) { allFav = false; break; }
        for (Media m : l) s.setFav(m, !allFav);
        a.toast(allFav ? "Removed from favorites" : "Added to favorites");
        exitSelect(false);
        a.refreshAll();
        a.player.changed();
    }
    void actDelete() { a.confirmDeleteMany(picked(), () -> exitSelect(true)); }

    // ---------- rows ----------
    Row hdr(String t, String act, Runnable r) {
        Row x = new Row(); x.type = HDR; x.title = t; x.action = act; x.click = r; return x;
    }

    Row empty(String t) { Row x = new Row(); x.type = EMPTY; x.title = t; return x; }

    void media(List<Media> list, List<Media> ctx, Runnable removeFn) {
        if (list.isEmpty()) { rows.add(empty("Nothing here yet")); return; }
        for (Media m : list) {
            Row x = new Row(); x.type = MEDIA; x.m = m; x.ctx = ctx; x.removeFn = removeFn;
            rows.add(x);
            shown.add(m);
        }
    }

    List<Media> resolve(List<String> keys) {
        List<Media> out = new ArrayList<>();
        for (String k : keys) { Media m = a.byKey.get(k); if (m != null && m.video == video && a.visible(m, false)) out.add(m); }
        return out;
    }

    String count(int n) { return n + (video ? (n == 1 ? " video" : " videos") : (n == 1 ? " audio" : " audios")); }

    void refresh() {
        rows.clear();
        shown.clear();
        for (int i = 0; i < tabIds.length; i++) {
            tabs[i].setTextColor(tabIds[i] == tab ? Ui.ACC : Ui.SUB);
            bars[i].setBackgroundColor(tabIds[i] == tab ? Ui.ACC : Color.TRANSPARENT);
        }
        List<Media> all = a.media(video);
        String q = search.getText().toString().trim().toLowerCase();
        List<String> past = s.list(sk());

        if (q.isEmpty() && search.hasFocus()) {
            Row h = hdr("Recent searches", past.isEmpty() ? null : "Clear", () ->
                    a.confirm("Clear search history?", "Your recent searches will be removed.", "Clear", () -> {
                        s.save(sk(), new ArrayList<>());
                        refresh();
                    }));
            rows.add(h);
            if (past.isEmpty()) rows.add(empty("Searches are saved once you play something from the results"));
            for (String p : past) sug(p);
        } else if (!q.isEmpty()) {
            int n = 0;
            for (String p : past) if (p.toLowerCase().contains(q) && !p.equalsIgnoreCase(q) && n++ < 3) sug(p);
            List<Media> res = new ArrayList<>();
            for (Media m : all)
                if (m.title.toLowerCase().contains(q) || m.artist.toLowerCase().contains(q)) res.add(m);
            res = sorted(res);
            rows.add(hdr("Results (" + res.size() + ")", null, null));
            media(res, res, null);
        } else if (tab == T_ALL) {
            List<Media> hist = resolve(s.list(s.hk(video)));
            if (!hist.isEmpty() && s.bool("showhist", true)) {
                int n = hist.size();
                boolean more = n > 5;
                rows.add(hdr("History", more ? (showAll ? "Show less" : "Show all " + n + (video ? " videos" : " audios")) : null,
                        () -> { showAll = !showAll; refresh(); }));
                media(showAll || !more ? hist : hist.subList(0, 5), hist, null);
            }
            rows.add(hdr(video ? "All videos (" + all.size() + ")" : "All audios (" + all.size() + ")", null, null));
            if (all.isEmpty()) rows.add(empty("No media found. Check media access in Settings."));
            else { List<Media> sa = sorted(all); media(sa, sa, null); }
        } else if (tab == T_FOLDERS) {
            Map<String, List<Media>> g = new LinkedHashMap<>();
            for (Media m : a.media(video, true)) {
                if (m.folderHidden && !s.bool("hid_f", false)) continue;
                if (!g.containsKey(m.path)) g.put(m.path, new ArrayList<Media>());
                g.get(m.path).add(m);
            }
            if (openFolder == null) {
                if (g.isEmpty()) rows.add(empty("No folders"));
                List<G> gs = new ArrayList<>();
                for (Map.Entry<String, List<Media>> en : g.entrySet()) {
                    final String key = en.getKey();
                    G x = new G();
                    x.title = en.getValue().get(0).folder;
                    x.items = en.getValue();
                    x.sub = count(x.items.size());
                    x.icon = R.drawable.ic_folder;
                    x.click = () -> { openFolder = key; refresh(); };
                    gs.add(x);
                }
                addGroups(gs);
            } else {
                List<Media> l = g.containsKey(openFolder) ? sorted(g.get(openFolder)) : new ArrayList<Media>();
                rows.add(hdr(l.isEmpty() ? "Folder" : l.get(0).folder, "Back", () -> { openFolder = null; refresh(); }));
                media(l, l, null);
            }
        } else if (tab == T_AUTHORS || tab == T_ALBUMS) {
            final boolean authors = tab == T_AUTHORS;
            Map<String, List<Media>> g = new LinkedHashMap<>();
            for (Media m : all) {
                String k = authors ? m.artist : (m.album == null ? "Unknown album" : m.album);
                if (!g.containsKey(k)) g.put(k, new ArrayList<Media>());
                g.get(k).add(m);
            }
            if (openGrp == null) {
                if (g.isEmpty()) rows.add(empty(authors ? "No authors" : "No albums"));
                List<G> gs = new ArrayList<>();
                for (Map.Entry<String, List<Media>> en : g.entrySet()) {
                    final String key = en.getKey();
                    G x = new G();
                    x.title = key;
                    x.items = en.getValue();
                    x.sub = count(x.items.size());
                    if (!authors) {
                        String ar = x.items.get(0).artist;
                        for (Media m : x.items) if (!m.artist.equals(ar)) { ar = "Various artists"; break; }
                        x.sub += "  •  " + ar;
                    }
                    x.icon = authors ? R.drawable.ic_person : R.drawable.ic_album;
                    x.click = () -> { openGrp = key; refresh(); };
                    gs.add(x);
                }
                addGroups(gs);
            } else {
                List<Media> l = g.containsKey(openGrp) ? sorted(g.get(openGrp)) : new ArrayList<Media>();
                rows.add(hdr(openGrp, "Back", () -> { openGrp = null; refresh(); }));
                media(l, l, null);
            }
        } else if (tab == T_PL) {
            if (openPl == null) {
                Row x = new Row(); x.type = ACT; x.title = "New playlist";
                x.click = () -> a.promptName("New playlist", n -> { s.createPlaylist(video, n); refresh(); });
                rows.add(x);
                List<String> names = s.playlists(video);
                if (names.isEmpty()) rows.add(empty("No playlists yet"));
                List<G> gs = new ArrayList<>();
                for (final String n : names) {
                    G g = new G();
                    g.pl = true;
                    g.title = n;
                    g.items = resolve(s.items(video, n));
                    g.sub = count(g.items.size());
                    g.icon = R.drawable.ic_playlist;
                    g.click = () -> { openPl = n; refresh(); };
                    g.more = () -> new Ui.Sheet(a, n, "Playlist")
                            .item(R.drawable.ic_delete, "Delete playlist", Ui.RED, () ->
                                    a.confirmDanger("Delete playlist?", "\"" + n + "\" will be deleted. The " + (video ? "videos" : "audios") + " in it stay on your device.",
                                            "Delete", () -> { s.deletePlaylist(video, n); refresh(); })).show();
                    gs.add(g);
                }
                addGroups(gs);
            } else {
                final String name = openPl;
                rows.add(hdr(name, "Back", () -> { openPl = null; refresh(); }));
                List<Media> l = sorted(resolve(s.items(video, name)));
                if (l.isEmpty()) { rows.add(empty("Empty playlist. Use ⋮ on any item > Add to playlist")); }
                else for (final Media m : l) {
                    Row x = new Row(); x.type = MEDIA; x.m = m; x.ctx = l;
                    x.removeFn = () -> { s.removeFromPlaylist(m, name); a.refreshAll(); };
                    rows.add(x);
                    shown.add(m);
                }
            }
        } else {
            List<Media> fav = sorted(resolve(s.list("fav")));
            rows.add(hdr("Favorites (" + fav.size() + ")", null, null));
            media(fav, fav, null);
        }
        gridify();
        updateToolbar();
        ad.notifyDataSetChanged();
    }

    void sug(String p) {
        Row x = new Row(); x.type = SUG; x.title = p;
        x.click = () -> { search.setText(p); search.setSelection(p.length()); unfocus(); };
        rows.add(x);
    }

    void updateToolbar() {
        sortBtn.setVisibility(selecting ? View.GONE : View.VISIBLE);
        viewBtn.setVisibility(selecting ? View.GONE : View.VISIBLE);
        allBtn.setVisibility(selecting ? View.VISIBLE : View.GONE);
        selBtn.setImageResource(selecting ? R.drawable.ic_close : R.drawable.ic_select);
        selBtn.setColorFilter(selecting ? Ui.ACC : Ui.TXT);
        viewBtn.setImageResource(s.grid(video) ? R.drawable.ic_view_list : R.drawable.ic_grid);
        actionBar.setVisibility(selecting ? View.VISIBLE : View.GONE);
        if (selecting) info.setText(sel.size() + " selected");
        else info.setText("Sorted by " + SORTS[s.sortMode(video)] + (s.sortAsc(video) ? " ↑" : " ↓"));
        info.setTextColor(selecting ? Ui.ACC : Ui.SUB);
    }

    // ---------- grid ----------
    int cols() {
        float dp = a.getResources().getDisplayMetrics().widthPixels / a.getResources().getDisplayMetrics().density;
        return Math.max(2, Math.round(dp / 125f));
    }

    void gridify() {
        if (!s.grid(video)) return;
        int cols = cols();
        ArrayList<Row> out = new ArrayList<>();
        int i = 0;
        while (i < rows.size()) {
            Row r = rows.get(i);
            if (r.type == MEDIA || r.type == FOLDER || r.type == PL) {
                Row g = new Row();
                g.type = GRID;
                g.cells = new ArrayList<>();
                while (i < rows.size() && rows.get(i).type == r.type && g.cells.size() < cols) g.cells.add(rows.get(i++));
                out.add(g);
            } else { out.add(r); i++; }
        }
        rows.clear();
        rows.addAll(out);
    }

    View cell(final Row r, int cw) {
        Context c = a;
        LinearLayout box = new LinearLayout(c);
        box.setOrientation(LinearLayout.VERTICAL);
        int pad = Ui.dp(c, 4);
        box.setPadding(pad, pad, pad, pad);
        int side = cw - 2 * pad;
        FrameLayout pic;
        if (r.type == MEDIA) {
            final Media m = r.m;
            pic = Ui.thumbBox(c, m, Math.round(side / c.getResources().getDisplayMetrics().density));
            Media cur = a.engine.cur();
            boolean now = cur != null && cur.key().equals(m.key());
            if (now) {
                FrameLayout.LayoutParams ep = new FrameLayout.LayoutParams(Ui.dp(c, 18), Ui.dp(c, 16), Gravity.BOTTOM | Gravity.START);
                ep.setMargins(Ui.dp(c, 8), 0, 0, Ui.dp(c, 8));
                pic.addView(new Ui.Eq(c, Ui.ACC, a.engine.isPlaying()), ep);
            }
            if (selecting) {
                FrameLayout.LayoutParams kp = new FrameLayout.LayoutParams(Ui.dp(c, 22), Ui.dp(c, 22), Gravity.TOP | Gravity.START);
                kp.setMargins(Ui.dp(c, 6), Ui.dp(c, 6), 0, 0);
                pic.addView(Ui.check(c, sel.contains(m.key())), kp);
            } else {
                ImageView mo = Ui.btn(c, R.drawable.ic_more, 0xFFFFFFFF, 18, 6);
                mo.setBackground(Ui.shape(0x66000000, 100, c));
                mo.setOnClickListener(v -> a.showOptions(m, r.removeFn));
                FrameLayout.LayoutParams mp = new FrameLayout.LayoutParams(Ui.dp(c, 30), Ui.dp(c, 30), Gravity.TOP | Gravity.END);
                mp.setMargins(0, Ui.dp(c, 4), Ui.dp(c, 4), 0);
                pic.addView(mo, mp);
            }
            box.addView(pic, new LinearLayout.LayoutParams(side, side));
            TextView t = Ui.text(c, m.title, 13, now ? Ui.ACC : Ui.TXT, now);
            t.setPadding(Ui.dp(c, 2), Ui.dp(c, 6), Ui.dp(c, 2), 0);
            box.addView(t);
            TextView st = Ui.text(c, m.artist + "  •  " + Ui.fmt(m.duration), 11, Ui.SUB, false);
            st.setPadding(Ui.dp(c, 2), 0, Ui.dp(c, 2), 0);
            box.addView(st);
            box.setOnClickListener(v -> { if (selecting) toggleSel(m); else play(m, r.ctx); });
            box.setOnLongClickListener(v -> { if (selecting) toggleSel(m); else a.showOptions(m, r.removeFn); return true; });
        } else {
            pic = new FrameLayout(c);
            pic.setBackground(Ui.shape(Ui.SURF2, 12, c));
            ImageView ic = new ImageView(c);
            ic.setImageResource(r.icon);
            ic.setColorFilter(Ui.ACC);
            int ip = side / 4;
            ic.setPadding(ip, ip, ip, ip);
            pic.addView(ic, new FrameLayout.LayoutParams(Ui.MATCH, Ui.MATCH));
            if (r.more != null) {
                ImageView mo = Ui.btn(c, R.drawable.ic_more, Ui.SUB, 18, 6);
                mo.setOnClickListener(v -> r.more.run());
                pic.addView(mo, new FrameLayout.LayoutParams(Ui.dp(c, 30), Ui.dp(c, 30), Gravity.TOP | Gravity.END));
            }
            box.addView(pic, new LinearLayout.LayoutParams(side, side));
            TextView t = Ui.text(c, r.title, 13, Ui.TXT, true);
            t.setPadding(Ui.dp(c, 2), Ui.dp(c, 6), Ui.dp(c, 2), 0);
            box.addView(t);
            TextView st = Ui.text(c, r.sub, 11, Ui.SUB, false);
            st.setPadding(Ui.dp(c, 2), 0, Ui.dp(c, 2), 0);
            box.addView(st);
            box.setOnClickListener(v -> r.click.run());
        }
        return box;
    }

    // ---------- row views ----------
    View build(Row r) {
        Context c = a;
        LinearLayout row = new LinearLayout(c);
        row.setGravity(Gravity.CENTER_VERTICAL);
        switch (r.type) {
            case HDR: {
                row.setPadding(Ui.dp(c, 16), Ui.dp(c, 10), Ui.dp(c, 16), Ui.dp(c, 6));
                TextView t = Ui.text(c, r.title, 17, Ui.TXT, true);
                row.addView(t, new LinearLayout.LayoutParams(0, Ui.WRAP, 1));
                if (r.action != null) {
                    TextView ac = Ui.text(c, r.action, 13, Ui.ACC, true);
                    ac.setPadding(Ui.dp(c, 8), Ui.dp(c, 6), 0, Ui.dp(c, 6));
                    ac.setOnClickListener(v -> r.click.run());
                    row.addView(ac);
                }
                return row;
            }
            case EMPTY: {
                TextView t = Ui.text(c, r.title, 14, Ui.SUB, false);
                t.setSingleLine(false);
                t.setGravity(Gravity.CENTER);
                t.setPadding(Ui.dp(c, 24), Ui.dp(c, 28), Ui.dp(c, 24), Ui.dp(c, 28));
                return t;
            }
            case SUG: {
                row.setPadding(Ui.dp(c, 16), 0, Ui.dp(c, 16), 0);
                row.setMinimumHeight(Ui.dp(c, 48));
                row.setBackground(Ui.rip(0, 0, c));
                row.addView(Ui.icon(c, R.drawable.ic_history, Ui.SUB, 20));
                TextView t = Ui.text(c, r.title, 15, Ui.TXT, false);
                t.setPadding(Ui.dp(c, 14), 0, 0, 0);
                row.addView(t);
                row.setOnClickListener(v -> r.click.run());
                return row;
            }
            case ACT: {
                row.setPadding(Ui.dp(c, 16), Ui.dp(c, 8), Ui.dp(c, 16), Ui.dp(c, 8));
                row.setBackground(Ui.rip(0, 0, c));
                row.addView(circle(R.drawable.ic_add, Ui.ACC, Ui.ONACC));
                TextView t = Ui.text(c, r.title, 16, Ui.ACC, true);
                t.setPadding(Ui.dp(c, 14), 0, 0, 0);
                row.addView(t);
                row.setOnClickListener(v -> r.click.run());
                return row;
            }
            case GRID: {
                row.setGravity(Gravity.TOP);
                row.setPadding(Ui.dp(c, 12), Ui.dp(c, 4), Ui.dp(c, 12), Ui.dp(c, 4));
                int cols = cols();
                int cw = (c.getResources().getDisplayMetrics().widthPixels - Ui.dp(c, 24)) / cols;
                for (int i = 0; i < cols; i++) {
                    View cell = i < r.cells.size() ? cell(r.cells.get(i), cw) : new View(c);
                    row.addView(cell, new LinearLayout.LayoutParams(cw, Ui.WRAP));
                }
                return row;
            }
            case FOLDER:
            case PL: {
                row.setPadding(Ui.dp(c, 16), Ui.dp(c, 8), Ui.dp(c, 4), Ui.dp(c, 8));
                row.setBackground(Ui.rip(0, 0, c));
                row.addView(circle(r.icon != 0 ? r.icon : R.drawable.ic_folder, Ui.SURF2, Ui.ACC));
                LinearLayout col = new LinearLayout(c);
                col.setOrientation(LinearLayout.VERTICAL);
                col.setPadding(Ui.dp(c, 14), 0, 0, 0);
                col.addView(Ui.text(c, r.title, 16, Ui.TXT, false));
                col.addView(Ui.text(c, r.sub, 13, Ui.SUB, false));
                row.addView(col, new LinearLayout.LayoutParams(0, Ui.WRAP, 1));
                if (r.more != null) {
                    ImageView mo = Ui.btn(c, R.drawable.ic_more, Ui.SUB, 22, 10);
                    mo.setOnClickListener(v -> r.more.run());
                    row.addView(mo);
                } else row.setPadding(Ui.dp(c, 16), Ui.dp(c, 8), Ui.dp(c, 16), Ui.dp(c, 8));
                row.setOnClickListener(v -> r.click.run());
                return row;
            }
            default: { // MEDIA
                final Media m = r.m;
                row.setPadding(Ui.dp(c, 16), Ui.dp(c, 6), Ui.dp(c, 4), Ui.dp(c, 6));
                row.setBackground(Ui.rip(0, 0, c));
                if (selecting) {
                    View ck = Ui.check(c, sel.contains(m.key()));
                    LinearLayout.LayoutParams kp = new LinearLayout.LayoutParams(Ui.dp(c, 22), Ui.dp(c, 22));
                    kp.setMargins(0, 0, Ui.dp(c, 12), 0);
                    row.addView(ck, kp);
                }
                row.addView(Ui.thumbBox(c, m, 52), new LinearLayout.LayoutParams(Ui.dp(c, 52), Ui.dp(c, 52)));
                LinearLayout col = new LinearLayout(c);
                col.setOrientation(LinearLayout.VERTICAL);
                col.setPadding(Ui.dp(c, 14), 0, Ui.dp(c, 6), 0);
                Media cur = a.engine.cur();
                boolean now = cur != null && cur.key().equals(m.key());
                col.addView(Ui.text(c, m.title, 15, now ? Ui.ACC : Ui.TXT, now));
                col.addView(Ui.text(c, m.artist + "  •  " + Ui.fmt(m.duration), 12, Ui.SUB, false));
                row.addView(col, new LinearLayout.LayoutParams(0, Ui.WRAP, 1));
                if (s.isFav(m)) row.addView(Ui.icon(c, R.drawable.ic_heart_fill, Ui.ACC, 16));
                if (now) {
                    // audio spectrum for whatever is playing, right before the 3 dots
                    LinearLayout.LayoutParams ep = new LinearLayout.LayoutParams(Ui.dp(c, 18), Ui.dp(c, 16));
                    ep.setMargins(Ui.dp(c, 10), 0, Ui.dp(c, 2), 0);
                    row.addView(new Ui.Eq(c, Ui.ACC, a.engine.isPlaying()), ep);
                }
                if (selecting) row.setPadding(Ui.dp(c, 16), Ui.dp(c, 6), Ui.dp(c, 16), Ui.dp(c, 6));
                else {
                    ImageView mo = Ui.btn(c, R.drawable.ic_more, Ui.SUB, 22, 10);
                    mo.setOnClickListener(v -> a.showOptions(m, r.removeFn));
                    row.addView(mo);
                }
                row.setOnClickListener(v -> { if (selecting) toggleSel(m); else play(m, r.ctx); });
                row.setOnLongClickListener(v -> { if (selecting) toggleSel(m); else a.showOptions(m, r.removeFn); return true; });
                return row;
            }
        }
    }

    View circle(int icon, int bg, int tint) {
        LinearLayout f = new LinearLayout(a);
        f.setGravity(Gravity.CENTER);
        f.setBackground(Ui.shape(bg, 100, a));
        f.addView(Ui.icon(a, icon, tint, 22));
        f.setLayoutParams(new LinearLayout.LayoutParams(Ui.dp(a, 44), Ui.dp(a, 44)));
        return f;
    }

    void play(Media m, List<Media> ctx) {
        if (!pending.isEmpty()) { s.pushFront(sk(), pending, 20); pending = ""; }
        unfocus();
        a.playMedia(ctx, ctx.indexOf(m));
    }
}
