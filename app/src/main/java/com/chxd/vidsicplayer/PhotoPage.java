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

class PhotoPage {
    static final int HDR = 0, PHOTO = 1, ALBUM = 2, COL = 3, ACT = 4, EMPTY = 5, SUG = 6, PGRID = 7, GGRID = 8, N = 9;
    static final int T_PHOTOS = 0, T_ALBUMS = 1, T_COL = 2, T_FAV = 3, T_GIF = 4, T_SVG = 5, NTABS = 6;
    static final String[] SORTS = {"Name", "Date", "Size", "Most viewed"};

    static class Row {
        int type;
        String title, sub;
        Photo ph, cover;
        Runnable click, more, removeFn;
        List<Photo> ctx;
        List<Row> cells;
    }

    static class Cells {
        FrameLayout[] cell;
        ImageView[] pic, heart;
        FrameLayout[] check;
    }

    final MainActivity a;
    final Store s;
    final LinearLayout root;
    final EditText search;
    final ImageView clear;
    final TextView[] tabs = new TextView[NTABS];
    final View[] bars = new View[NTABS];
    final TextView info;
    final ImageView showBtn, sortBtn, viewBtn, selBtn, allBtn;
    final LinearLayout actionBar;
    final ListView lv;
    HorizontalScrollView tabScroll;
    final ArrayList<Row> rows = new ArrayList<>();
    final ArrayList<Photo> shown = new ArrayList<>();
    final LinkedHashSet<String> sel = new LinkedHashSet<>();
    final BaseAdapter ad;
    int tab = T_PHOTOS;
    String openAlbum, openCol, pending = "";
    boolean showAll, selecting;

    PhotoPage(MainActivity act) {
        a = act;
        s = act.store;
        root = new LinearLayout(a);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(Ui.BG);
        root.setFocusableInTouchMode(true);
        root.setDescendantFocusability(ViewGroup.FOCUS_BEFORE_DESCENDANTS);

        LinearLayout sb = new LinearLayout(a);
        sb.setGravity(Gravity.CENTER_VERTICAL);
        sb.setBackground(Ui.shape(Ui.SURF, 24, a));
        sb.setPadding(Ui.dp(a, 14), 0, Ui.dp(a, 4), 0);
        sb.addView(Ui.icon(a, R.drawable.ic_search, Ui.SUB, 20));
        search = new EditText(a);
        search.setHint("Search photos");
        search.setHintTextColor(Ui.SUB);
        search.setTextColor(Ui.TXT);
        search.setTextSize(15);
        search.setBackground(null);
        search.setSingleLine(true);
        search.setImeOptions(EditorInfo.IME_ACTION_SEARCH);
        search.setPadding(Ui.dp(a, 10), 0, 0, 0);
        Ui.tintEdit(a, search);
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

        String[] names = {"Photos", "Albums", "Collection", "Favorite", "GIF", "SVG"};
        HorizontalScrollView hs = new HorizontalScrollView(a);
        tabScroll = hs;
        hs.setHorizontalScrollBarEnabled(false);
        hs.setOverScrollMode(View.OVER_SCROLL_NEVER);
        LinearLayout tb = new LinearLayout(a);
        for (int i = 0; i < NTABS; i++) {
            final int id = i;
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
            col.setOnClickListener(v -> { tab = id; openAlbum = null; openCol = null; exitSelect(false); unfocus(); refresh(); });
            tb.addView(col, new LinearLayout.LayoutParams(Ui.WRAP, Ui.WRAP));
        }
        hs.addView(tb);
        root.addView(hs, new LinearLayout.LayoutParams(Ui.MATCH, Ui.WRAP));

        LinearLayout tool = new LinearLayout(a);
        tool.setGravity(Gravity.CENTER_VERTICAL);
        tool.setPadding(Ui.dp(a, 16), 0, Ui.dp(a, 6), 0);
        info = Ui.text(a, "", 12, Ui.SUB, false);
        tool.addView(info, new LinearLayout.LayoutParams(0, Ui.WRAP, 1));
        allBtn = Ui.btn(a, R.drawable.ic_select_all, Ui.TXT, 22, 9);
        allBtn.setOnClickListener(v -> selectAll());
        showBtn = Ui.btn(a, R.drawable.ic_slideshow, Ui.TXT, 22, 9);
        showBtn.setOnClickListener(v -> slideshowMenu(shown));
        sortBtn = Ui.btn(a, R.drawable.ic_sort, Ui.TXT, 22, 9);
        sortBtn.setOnClickListener(v -> showSort());
        viewBtn = Ui.btn(a, R.drawable.ic_grid, Ui.TXT, 22, 9);
        viewBtn.setOnClickListener(v -> { s.setPgrid(!s.pgrid()); refresh(); });
        selBtn = Ui.btn(a, R.drawable.ic_select, Ui.TXT, 22, 9);
        selBtn.setOnClickListener(v -> { if (selecting) exitSelect(true); else { selecting = true; sel.clear(); refresh(); } });
        tool.addView(allBtn);
        tool.addView(showBtn);
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
            public View getView(int i, View cv, ViewGroup p) { return build(rows.get(i), cv); }
        };
        lv.setAdapter(ad);
        enableSwipe(lv);
        root.addView(lv, new LinearLayout.LayoutParams(Ui.MATCH, 0, 1));

        actionBar = new LinearLayout(a);
        actionBar.setBackgroundColor(Ui.SURF2);
        actionBar.setVisibility(View.GONE);
        actionBar.addView(act(R.drawable.ic_slideshow, "Slideshow", Ui.TXT, () -> slideshowMenu(picked())), new LinearLayout.LayoutParams(0, Ui.WRAP, 1));
        actionBar.addView(act(R.drawable.ic_collections, "Collection", Ui.TXT, () -> a.pops.pickCollection(picked(), () -> exitSelect(true))), new LinearLayout.LayoutParams(0, Ui.WRAP, 1));
        actionBar.addView(act(R.drawable.ic_grid, "Create", Ui.TXT, () -> {
            final List<Photo> l = picked();
            if (l.size() < 2) { a.toast("Pick at least 2 pictures"); return; }
            new Ui.Sheet(a, "Create", l.size() + " pictures")
                    .item(R.drawable.ic_grid, "Collage", Ui.TXT, () -> { exitSelect(true); a.openCollage(l); })
                    .item(R.drawable.ic_slideshow, "GIF", Ui.TXT, () -> { exitSelect(true); a.openGif(l); })
                    .show();
        }), new LinearLayout.LayoutParams(0, Ui.WRAP, 1));
        actionBar.addView(act(R.drawable.ic_heart, "Favorite", Ui.TXT, this::actFav), new LinearLayout.LayoutParams(0, Ui.WRAP, 1));
        actionBar.addView(act(R.drawable.ic_delete, "Delete", Ui.RED, () -> a.pops.confirmDelete(picked(), () -> exitSelect(true))), new LinearLayout.LayoutParams(0, Ui.WRAP, 1));
        root.addView(actionBar, new LinearLayout.LayoutParams(Ui.MATCH, Ui.WRAP));
        refresh();
    }


    /** Swipe left = next tab, swipe right = previous tab. */
    void swipeTab(int dir) {
        int j = tab + dir;
        if (j < 0 || j >= NTABS) return;
        tab = j; openAlbum = null; openCol = null;
        exitSelect(false); unfocus(); refresh();
        centerTab(j);
    }

    void centerTab(int j) {
        final View col = (View) tabs[j].getParent();
        tabScroll.post(() -> tabScroll.smoothScrollTo(Math.max(0, col.getLeft() - (tabScroll.getWidth() - col.getWidth()) / 2), 0));
    }

    void enableSwipe(ListView list) {
        final android.view.GestureDetector gd = new android.view.GestureDetector(a, new android.view.GestureDetector.SimpleOnGestureListener() {
            @Override public boolean onFling(android.view.MotionEvent e1, android.view.MotionEvent e2, float vx, float vy) {
                if (e1 == null || e2 == null) return false;
                float dx = e2.getX() - e1.getX(), dy = e2.getY() - e1.getY();
                if (Math.abs(dx) > Ui.dp(a, 80) && Math.abs(dx) > Math.abs(dy) * 2f) swipeTab(dx < 0 ? 1 : -1);
                return false;
            }
        });
        list.setOnTouchListener((v, ev) -> { gd.onTouchEvent(ev); return false; });
    }

    String sk() { return "srch_p"; }

    void unfocus() {
        InputMethodManager imm = (InputMethodManager) a.getSystemService(Context.INPUT_METHOD_SERVICE);
        imm.hideSoftInputFromWindow(search.getWindowToken(), 0);
        root.requestFocus();
    }

    boolean onBack() {
        if (selecting) { exitSelect(true); return true; }
        if (openAlbum != null || openCol != null) { openAlbum = null; openCol = null; refresh(); return true; }
        if (!search.getText().toString().isEmpty()) { search.setText(""); return true; }
        if (search.hasFocus()) { unfocus(); return true; }
        if (tab != T_PHOTOS) { tab = T_PHOTOS; refresh(); return true; }
        return false;
    }

    List<Photo> sorted(List<Photo> in) {
        List<Photo> l = new ArrayList<>(in);
        final int mode = s.psortMode();
        Comparator<Photo> c = (x, y) -> {
            switch (mode) {
                case 0: return x.title.compareToIgnoreCase(y.title);
                case 2: return Long.compare(x.size, y.size);
                case 3: {
                    int c3 = Integer.compare(s.photoViews(x), s.photoViews(y));
                    return c3 != 0 ? c3 : Long.compare(x.added, y.added);
                }
                default: return Long.compare(x.added, y.added);
            }
        };
        Collections.sort(l, s.psortAsc() ? c : Collections.<Photo>reverseOrder(c));
        return l;
    }

    void showSort() {
        final Dialog d = new Dialog(a);
        d.requestWindowFeature(Window.FEATURE_NO_TITLE);
        final int[] mode = {s.psortMode()};
        final boolean[] asc = {s.psortAsc()};
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
        ok.setOnClickListener(v -> { s.setPsort(mode[0], asc[0]); d.dismiss(); refresh(); });
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

    void slideshowMenu(final List<Photo> list) {
        if (list.isEmpty()) { a.toast(selecting ? "Select something first" : "No pictures here"); return; }
        final List<Photo> src = new ArrayList<>(list);
        Ui.Sheet sh = new Ui.Sheet(a, "Slideshow", src.size() + (src.size() == 1 ? " picture" : " pictures"));
        sh.item(R.drawable.ic_play, "Play", Ui.TXT, () -> { exitSelect(true); a.openViewer(src, -1, true, -1); });
        sh.item(R.drawable.ic_shuffle, "Play shuffled", Ui.TXT, () -> { exitSelect(true); a.openViewer(src, -1, true, 1); });
        sh.item(R.drawable.ic_settings, "Slideshow settings", Ui.TXT, () -> PhotoViewer.slideshowSettings(a, null));
        sh.show();
    }

    void exitSelect(boolean redraw) {
        if (!selecting && sel.isEmpty()) return;
        selecting = false;
        sel.clear();
        if (redraw) refresh();
    }

    void toggleSel(Photo p) {
        if (!sel.remove(p.key())) sel.add(p.key());
        refresh();
    }

    void selectAll() {
        boolean all = true;
        for (Photo p : shown) if (!sel.contains(p.key())) { all = false; break; }
        if (all) sel.clear(); else for (Photo p : shown) sel.add(p.key());
        refresh();
    }

    List<Photo> picked() {
        List<Photo> l = new ArrayList<>();
        Set<String> seen = new LinkedHashSet<>();
        for (Photo p : shown) if (sel.contains(p.key()) && seen.add(p.key())) l.add(p);
        return l;
    }

    View act(int icon, String label, int color, final Runnable r) {
        LinearLayout c = new LinearLayout(a);
        c.setOrientation(LinearLayout.VERTICAL);
        c.setGravity(Gravity.CENTER);
        c.setPadding(0, Ui.dp(a, 8), 0, Ui.dp(a, 8));
        c.setBackground(Ui.rip(0, 0, a));
        c.addView(Ui.icon(a, icon, color, 22));
        TextView t = Ui.text(a, label, 11, color, true);
        t.setGravity(Gravity.CENTER);
        c.addView(t, new LinearLayout.LayoutParams(Ui.WRAP, Ui.WRAP));
        c.setOnClickListener(v -> {
            if (sel.isEmpty()) { a.toast("Select something first"); return; }
            r.run();
        });
        return c;
    }

    void actFav() {
        List<Photo> l = picked();
        a.pops.toggleFav(l);
        exitSelect(true);
    }

    Row hdr(String t, String act, Runnable r) {
        Row x = new Row(); x.type = HDR; x.title = t; x.sub = act; x.click = r; return x;
    }

    Row empty(String t) { Row x = new Row(); x.type = EMPTY; x.title = t; return x; }

    void photos(List<Photo> list, List<Photo> ctx, Runnable removeFn) {
        if (list.isEmpty()) { rows.add(empty("Nothing here yet")); return; }
        for (Photo p : list) {
            Row x = new Row(); x.type = PHOTO; x.ph = p; x.ctx = ctx; x.removeFn = removeFn;
            rows.add(x);
            shown.add(p);
        }
    }

    List<Photo> resolve(List<String> keys) {
        List<Photo> out = new ArrayList<>();
        for (String k : keys) { Photo p = a.photoByKey.get(k); if (p != null) out.add(p); }
        return out;
    }

    String count(int n) { return n + (n == 1 ? " picture" : " pictures"); }

    void refresh() {
        rows.clear();
        shown.clear();
        for (int i = 0; i < NTABS; i++) {
            tabs[i].setTextColor(i == tab ? Ui.ACC : Ui.SUB);
            bars[i].setBackgroundColor(i == tab ? Ui.ACC : Color.TRANSPARENT);
        }
        List<Photo> all = a.photos;
        scopeName = null;
        List<Photo> scoped = s.bool("search_scope", false) ? scope() : null;
        search.setHint(scoped != null ? "Search in " + scopeName : "Search photos");
        String q = search.getText().toString().trim().toLowerCase();
        List<String> past = s.list(sk());

        if (q.isEmpty() && search.hasFocus()) {
            rows.add(hdr("Recent searches", past.isEmpty() ? null : "Clear", () ->
                    a.confirm("Clear search history?", "Your recent searches will be removed.", "Clear", () -> {
                        s.save(sk(), new ArrayList<>());
                        refresh();
                    })));
            if (past.isEmpty()) rows.add(empty("Searches are saved once you open a picture from the results"));
            for (String p : past) sug(p);
        } else if (!q.isEmpty()) {
            int n = 0;
            for (String p : past) if (p.toLowerCase().contains(q) && !p.equalsIgnoreCase(q) && n++ < 3) sug(p);
            List<Photo> res = new ArrayList<>();
            for (Photo p : scoped != null ? scoped : all)
                if (p.title.toLowerCase().contains(q) || p.name.toLowerCase().contains(q) || p.folder.toLowerCase().contains(q)) res.add(p);
            res = sorted(res);
            rows.add(hdr("Results (" + res.size() + ")", null, null));
            photos(res, res, null);
        } else if (tab == T_PHOTOS) {
            List<Photo> hist = resolve(s.list("hist_p"));
            if (!hist.isEmpty()) {
                int n = hist.size(), keep = 6;
                boolean more = n > keep;
                rows.add(hdr("History", more ? (showAll ? "Show less" : "Show all " + n) : null, () -> { showAll = !showAll; refresh(); }));
                photos(showAll || !more ? hist : hist.subList(0, keep), hist, null);
            }
            rows.add(hdr("All photos (" + all.size() + ")", null, null));
            if (all.isEmpty()) rows.add(empty(a.imageOk() ? "No pictures found" : "No picture access yet. Use Grant media access in Settings."));
            else { List<Photo> sa = sorted(all); photos(sa, sa, null); }
        } else if (tab == T_ALBUMS) {
            Map<String, List<Photo>> g = new LinkedHashMap<>();
            for (Photo p : all) {
                List<Photo> l = g.get(p.path);
                if (l == null) { l = new ArrayList<>(); g.put(p.path, l); }
                l.add(p);
            }
            if (openAlbum == null || !g.containsKey(openAlbum)) {
                openAlbum = null;
                if (g.isEmpty()) rows.add(empty("No albums"));
                List<Map.Entry<String, List<Photo>>> es = new ArrayList<>(g.entrySet());
                final int mode = s.psortMode();
                Comparator<Map.Entry<String, List<Photo>>> c = (x, y) -> {
                    if (mode == 0) return x.getValue().get(0).folder.compareToIgnoreCase(y.getValue().get(0).folder);
                    if (mode == 2) return Long.compare(total(x.getValue()), total(y.getValue()));
                    if (mode == 3) return Long.compare(views(x.getValue()), views(y.getValue()));
                    return Long.compare(newest(x.getValue()), newest(y.getValue()));
                };
                Collections.sort(es, s.psortAsc() ? c : Collections.<Map.Entry<String, List<Photo>>>reverseOrder(c));
                for (Map.Entry<String, List<Photo>> en : es) {
                    final String key = en.getKey();
                    Row x = new Row();
                    x.type = ALBUM;
                    x.title = en.getValue().get(0).folder;
                    x.sub = count(en.getValue().size());
                    x.cover = newestPhoto(en.getValue());
                    x.click = () -> { openAlbum = key; refresh(); };
                    final List<Photo> albumItems = en.getValue();
                    x.more = () -> albumMenu(albumItems);
                    rows.add(x);
                }
            } else {
                List<Photo> l = sorted(g.get(openAlbum));
                rows.add(hdr(l.get(0).folder, "Back", () -> { openAlbum = null; refresh(); }));
                photos(l, l, null);
            }
        } else if (tab == T_COL) {
            if (openCol == null || !s.collections().contains(openCol)) {
                openCol = null;
                Row x = new Row(); x.type = ACT; x.title = "New collection";
                x.click = () -> a.promptName("New collection", n -> {
                    if (!s.createCollection(n)) a.toast("A collection with that name already exists");
                    refresh();
                });
                rows.add(x);
                List<String> names = s.collections();
                if (names.isEmpty()) rows.add(empty("No collections yet. Select pictures > Collection, or use ⋮ on any picture."));
                for (final String n : names) {
                    List<Photo> items = resolve(s.collectionItems(n));
                    Row r = new Row();
                    r.type = COL;
                    r.title = n;
                    r.sub = count(items.size());
                    r.cover = items.isEmpty() ? null : items.get(0);
                    r.click = () -> { openCol = n; refresh(); };
                    r.more = () -> collectionMenu(n);
                    rows.add(r);
                }
            } else {
                final String name = openCol;
                rows.add(hdr(name, "Back", () -> { openCol = null; refresh(); }));
                List<Photo> l = sorted(resolve(s.collectionItems(name)));
                if (l.isEmpty()) rows.add(empty("Empty collection. Use ⋮ on any picture > Add to collection"));
                else for (final Photo p : l) {
                    Row r = new Row(); r.type = PHOTO; r.ph = p; r.ctx = l;
                    r.removeFn = () -> {
                        s.removeFromCollection(Collections.singletonList(p), name);
                        refresh();
                    };
                    rows.add(r);
                    shown.add(p);
                }
            }
        } else if (tab == T_GIF || tab == T_SVG) {
            final boolean gifTab = tab == T_GIF;
            List<Photo> kind = new ArrayList<>();
            for (Photo p : all) if (gifTab ? p.gif : p.svg) kind.add(p);
            kind = sorted(kind);
            if (gifTab) {
                Row x = new Row(); x.type = ACT; x.title = "Create a GIF";
                x.click = () -> {
                    tab = T_PHOTOS; openAlbum = null; openCol = null;
                    selecting = true; sel.clear();
                    a.toast("Pick 2 or more pictures, then tap Create > GIF");
                    refresh();
                };
                rows.add(x);
            }
            rows.add(hdr((gifTab ? "GIFs (" : "SVG drawings (") + kind.size() + ")", "Add to home",
                    () -> HomeAdd.show(a, new Group(gifTab ? Group.P_GIF : Group.P_SVG, false, ""), gifTab ? "GIFs" : "SVG drawings")));
            if (kind.isEmpty()) rows.add(empty(!a.imageOk() ? "No picture access yet. Use Grant media access in Settings."
                    : gifTab ? "No GIFs found yet. Create one from your pictures." : "No SVG drawings found"));
            else photos(kind, kind, null);
        } else {
            List<Photo> fav = sorted(resolve(s.list("pfav")));
            rows.add(hdr("Favorites (" + fav.size() + ")", "Add to home", () -> HomeAdd.show(a, new Group(Group.P_FAV, false, ""), "Favorites")));
            photos(fav, fav, null);
        }
        gridify();
        updateToolbar();
        ad.notifyDataSetChanged();
    }

    static long total(List<Photo> l) { long r = 0; for (Photo p : l) r += p.size; return r; }
    static long newest(List<Photo> l) { long r = 0; for (Photo p : l) r = Math.max(r, p.added); return r; }
    static Photo newestPhoto(List<Photo> l) {
        Photo b = l.get(0);
        for (Photo p : l) if (p.added > b.added) b = p;
        return b;
    }

    String scopeName;

    /** Pictures of the group that is open right now (album, collection, favorites, GIFs, SVG drawings), or null when none is. */
    List<Photo> scope() {
        List<Photo> out = new ArrayList<>();
        if (tab == T_ALBUMS && openAlbum != null) {
            for (Photo p : a.photos) if (openAlbum.equals(p.path)) out.add(p);
            if (out.isEmpty()) return null;
            scopeName = out.get(0).folder;
        } else if (tab == T_COL && openCol != null && s.collections().contains(openCol)) {
            out = resolve(s.collectionItems(openCol));
            scopeName = openCol;
        } else if (tab == T_FAV) {
            out = resolve(s.list("pfav"));
            scopeName = "favorites";
        } else if (tab == T_GIF || tab == T_SVG) {
            for (Photo p : a.photos) if (tab == T_GIF ? p.gif : p.svg) out.add(p);
            scopeName = tab == T_GIF ? "GIFs" : "SVG drawings";
        } else {
            return null;
        }
        return out;
    }

    void openGroup(Group g) {
        exitSelect(false);
        openAlbum = null; openCol = null;
        switch (g.kind) {
            case Group.P_ALBUM: tab = T_ALBUMS; openAlbum = g.name; break;
            case Group.P_COL: tab = T_COL; openCol = g.name; break;
            case Group.P_FAV: tab = T_FAV; break;
            case Group.P_GIF: tab = T_GIF; break;
            case Group.P_SVG: tab = T_SVG; break;
            default: tab = T_PHOTOS; break;
        }
        refresh();
    }

    void collagePick(List<Photo> src, boolean gif) {
        List<Photo> l = new ArrayList<>(src);
        Collections.sort(l, (x, y) -> Long.compare(y.added, x.added));
        int max = gif ? GifMaker.MAX : CollageMaker.MAX;
        if (l.size() > max) l = new ArrayList<>(l.subList(0, max));
        if (gif) a.openGif(l); else a.openCollage(l);
    }

    long views(List<Photo> l) {
        long t = 0;
        for (Photo p : l) t += s.photoViews(p);
        return t;
    }

    void albumMenu(final List<Photo> items) {
        new Ui.Sheet(a, items.get(0).folder, count(items.size()))
                .item(R.drawable.ic_slideshow, "Slideshow", Ui.TXT, () -> slideshowMenu(new ArrayList<>(items)))
                .item(R.drawable.ic_collections, "Add to collection", Ui.TXT, () -> a.pops.pickCollection(new ArrayList<>(items), null))
                .item(R.drawable.ic_add, "Add to home screen", Ui.TXT, () -> HomeAdd.show(a, new Group(Group.P_ALBUM, false, items.get(0).path), items.get(0).folder))
                .item(R.drawable.ic_grid, "Make a collage", Ui.TXT, () -> collagePick(items, false))
                .item(R.drawable.ic_slideshow, "Make a GIF", Ui.TXT, () -> collagePick(items, true))
                .item(R.drawable.ic_heart, "Add to favorites", Ui.TXT, () -> {
                    s.setPhotoFavs(items, true);
                    a.toast("Added to favorites");
                    refresh();
                })
                .item(R.drawable.ic_delete, "Delete pictures", Ui.RED, () -> a.pops.confirmDelete(new ArrayList<>(items), null))
                .show();
    }

    void collectionMenu(final String n) {
        new Ui.Sheet(a, n, "Collection")
                .item(R.drawable.ic_slideshow, "Slideshow", Ui.TXT, () -> slideshowMenu(resolve(s.collectionItems(n))))
                .item(R.drawable.ic_add, "Add to home screen", Ui.TXT, () -> HomeAdd.show(a, new Group(Group.P_COL, false, n), n))
                .item(R.drawable.ic_grid, "Make a collage", Ui.TXT, () -> collagePick(resolve(s.collectionItems(n)), false))
                .item(R.drawable.ic_slideshow, "Make a GIF", Ui.TXT, () -> collagePick(resolve(s.collectionItems(n)), true))
                .item(R.drawable.ic_edit, "Rename collection", Ui.TXT, () -> a.promptName("Rename collection", n, nn -> {
                    if (nn.equals(n)) return;
                    if (!s.renameCollection(n, nn)) a.toast("A collection with that name already exists");
                    refresh();
                }))
                .item(R.drawable.ic_delete, "Delete collection", Ui.RED, () ->
                        a.confirmDanger("Delete collection?", "\"" + n + "\" will be deleted. The pictures in it stay on your device.", "Delete",
                                () -> { s.deleteCollection(n); refresh(); }))
                .show();
    }

    void sug(final String p) {
        Row x = new Row(); x.type = SUG; x.title = p;
        x.click = () -> { search.setText(p); search.setSelection(p.length()); unfocus(); };
        x.more = () -> {
            List<String> l = s.list(sk());
            l.remove(p);
            s.save(sk(), l);
            refresh();
        };
        rows.add(x);
    }

    void updateToolbar() {
        sortBtn.setVisibility(selecting ? View.GONE : View.VISIBLE);
        viewBtn.setVisibility(selecting ? View.GONE : View.VISIBLE);
        showBtn.setVisibility(selecting ? View.GONE : View.VISIBLE);
        allBtn.setVisibility(selecting ? View.VISIBLE : View.GONE);
        Ic.set(selBtn, selecting ? R.drawable.ic_close : R.drawable.ic_select);
        selBtn.setColorFilter(selecting ? Ui.ACC : Ui.TXT);
        Ic.set(viewBtn, s.pgrid() ? R.drawable.ic_view_list : R.drawable.ic_grid);
        actionBar.setVisibility(selecting ? View.VISIBLE : View.GONE);
        if (selecting) info.setText(sel.size() + " selected");
        else info.setText("Sorted by " + SORTS[s.psortMode()] + (s.psortAsc() ? " ↑" : " ↓"));
        info.setTextColor(selecting ? Ui.ACC : Ui.SUB);
    }

    int cols() {
        float dp = a.getResources().getDisplayMetrics().widthPixels / a.getResources().getDisplayMetrics().density;
        return Math.max(3, Math.round(dp / 110f));
    }

    void gridify() {
        if (!s.pgrid()) return;
        int cols = cols();
        ArrayList<Row> out = new ArrayList<>();
        int i = 0;
        while (i < rows.size()) {
            Row r = rows.get(i);
            if (r.type == PHOTO || r.type == ALBUM || r.type == COL) {
                Row g = new Row();
                g.type = r.type == PHOTO ? PGRID : GGRID;
                g.cells = new ArrayList<>();
                int per = r.type == PHOTO ? cols : Math.max(2, cols - 1);
                while (i < rows.size() && rows.get(i).type == r.type && g.cells.size() < per) g.cells.add(rows.get(i++));
                out.add(g);
            } else { out.add(r); i++; }
        }
        rows.clear();
        rows.addAll(out);
    }

    void open(Photo p, List<Photo> ctx) {
        if (!pending.isEmpty()) { s.pushFront(sk(), pending, 20); pending = ""; }
        unfocus();
        a.openViewer(ctx, ctx.indexOf(p), false, -1);
    }

    void options(final Photo p, final Row r) {
        final List<Photo> ctx = r.ctx;
        Ui.Sheet sh = new Ui.Sheet(a, p.title, p.folder);
        sh.item(R.drawable.ic_image, "Open", Ui.TXT, () -> open(p, ctx));
        sh.item(R.drawable.ic_slideshow, "Slideshow from here", Ui.TXT, () -> a.openViewer(ctx, Math.max(0, ctx.indexOf(p)), true, -1));
        sh.item(R.drawable.ic_collections, "Add to collection", Ui.TXT, () -> a.pops.pickCollection(Collections.singletonList(p), null));
        boolean f = s.isPhotoFav(p);
        sh.item(f ? R.drawable.ic_heart_fill : R.drawable.ic_heart, f ? "Remove from favorites" : "Add to favorites", Ui.TXT,
                () -> a.pops.toggleFav(Collections.singletonList(p)));
        if (r.removeFn != null) sh.item(R.drawable.ic_close, "Remove from collection", Ui.TXT, r.removeFn);
        sh.item(R.drawable.ic_edit, p.svg || p.gif ? "Edit as PNG" : "Edit picture", Ui.TXT, () -> a.openEditor(p));
        sh.item(R.drawable.ic_edit, "Rename", Ui.TXT, () -> a.promptName("Rename", p.title, n -> a.pops.rename(p, n, null)));
        if (!p.svg) sh.item(R.drawable.ic_image, "Add as widget", Ui.TXT, () -> HomeAdd.pinPicture(a, p));
        sh.item(R.drawable.ic_info, "Info", Ui.TXT, () -> a.pops.info(p));
        sh.item(R.drawable.ic_delete, "Delete", Ui.RED, () -> a.pops.confirmDelete(Collections.singletonList(p), null));
        sh.show();
    }

    View build(final Row r, View cv) {
        Context c = a;
        if (r.type == PGRID) return photoGrid(r, cv);
        LinearLayout row = new LinearLayout(c);
        row.setGravity(Gravity.CENTER_VERTICAL);
        switch (r.type) {
            case HDR: {
                row.setPadding(Ui.dp(c, 16), Ui.dp(c, 10), Ui.dp(c, 16), Ui.dp(c, 6));
                row.addView(Ui.text(c, r.title, 17, Ui.TXT, true), new LinearLayout.LayoutParams(0, Ui.WRAP, 1));
                if (r.sub != null) {
                    TextView ac = Ui.text(c, r.sub, 13, Ui.ACC, true);
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
                row.setPadding(Ui.dp(c, 16), 0, Ui.dp(c, 4), 0);
                row.setMinimumHeight(Ui.dp(c, 48));
                row.setBackground(Ui.rip(0, 0, c));
                row.addView(Ui.icon(c, R.drawable.ic_history, Ui.SUB, 20));
                TextView t = Ui.text(c, r.title, 15, Ui.TXT, false);
                t.setPadding(Ui.dp(c, 14), 0, 0, 0);
                row.addView(t, new LinearLayout.LayoutParams(0, Ui.WRAP, 1));
                ImageView rm = Ui.btn(c, R.drawable.ic_close, Ui.SUB, 18, 11);
                rm.setContentDescription("Remove from search history");
                rm.setOnClickListener(v -> r.more.run());
                row.addView(rm);
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
            case GGRID: {
                row.setGravity(Gravity.TOP);
                row.setPadding(Ui.dp(c, 12), Ui.dp(c, 4), Ui.dp(c, 12), Ui.dp(c, 4));
                int per = Math.max(2, cols() - 1);
                int cw = (c.getResources().getDisplayMetrics().widthPixels - Ui.dp(c, 24)) / per;
                for (int i = 0; i < per; i++) {
                    View cell = i < r.cells.size() ? groupCell(r.cells.get(i), cw) : new View(c);
                    row.addView(cell, new LinearLayout.LayoutParams(cw, Ui.WRAP));
                }
                return row;
            }
            case ALBUM:
            case COL: {
                row.setPadding(Ui.dp(c, 16), Ui.dp(c, 8), Ui.dp(c, 4), Ui.dp(c, 8));
                row.setBackground(Ui.rip(0, 0, c));
                row.addView(Ui.photoBox(c, r.cover, 52), new LinearLayout.LayoutParams(Ui.dp(c, 52), Ui.dp(c, 52)));
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
            default: {
                final Photo p = r.ph;
                row.setPadding(Ui.dp(c, 16), Ui.dp(c, 6), Ui.dp(c, 4), Ui.dp(c, 6));
                row.setBackground(Ui.rip(0, 0, c));
                if (selecting) {
                    LinearLayout.LayoutParams kp = new LinearLayout.LayoutParams(Ui.dp(c, 22), Ui.dp(c, 22));
                    kp.setMargins(0, 0, Ui.dp(c, 12), 0);
                    row.addView(Ui.check(c, sel.contains(p.key())), kp);
                }
                row.addView(Ui.photoBox(c, p, 52), new LinearLayout.LayoutParams(Ui.dp(c, 52), Ui.dp(c, 52)));
                LinearLayout col = new LinearLayout(c);
                col.setOrientation(LinearLayout.VERTICAL);
                col.setPadding(Ui.dp(c, 14), 0, Ui.dp(c, 6), 0);
                col.addView(Ui.text(c, p.title, 15, Ui.TXT, false));
                col.addView(Ui.text(c, p.folder + "  •  " + PhotoOps.fmtSize(p.size), 12, Ui.SUB, false));
                row.addView(col, new LinearLayout.LayoutParams(0, Ui.WRAP, 1));
                if (s.isPhotoFav(p)) row.addView(Ui.icon(c, R.drawable.ic_heart_fill, Ui.ACC, 16));
                if (selecting) row.setPadding(Ui.dp(c, 16), Ui.dp(c, 6), Ui.dp(c, 16), Ui.dp(c, 6));
                else {
                    ImageView mo = Ui.btn(c, R.drawable.ic_more, Ui.SUB, 22, 10);
                    mo.setOnClickListener(v -> options(p, r));
                    row.addView(mo);
                }
                row.setOnClickListener(v -> { if (selecting) toggleSel(p); else open(p, r.ctx); });
                row.setOnLongClickListener(v -> { if (selecting) toggleSel(p); else options(p, r); return true; });
                return row;
            }
        }
    }

    View photoGrid(final Row r, View cv) {
        final int cols = cols();
        final Context c = a;
        Cells h = cv != null && cv.getTag() instanceof Cells && ((Cells) cv.getTag()).cell.length == cols ? (Cells) cv.getTag() : null;
        LinearLayout row;
        if (h == null) {
            row = new LinearLayout(c);
            row.setPadding(Ui.dp(c, 2), Ui.dp(c, 1), Ui.dp(c, 2), Ui.dp(c, 1));
            h = new Cells();
            h.cell = new FrameLayout[cols];
            h.pic = new ImageView[cols];
            h.heart = new ImageView[cols];
            h.check = new FrameLayout[cols];
            int cw = (c.getResources().getDisplayMetrics().widthPixels - Ui.dp(c, 4)) / cols, side = cw - Ui.dp(c, 2);
            for (int i = 0; i < cols; i++) {
                FrameLayout f = new FrameLayout(c);
                f.setBackground(Ui.shape(Ui.SURF2, 4, c));
                f.setClipToOutline(true);
                ImageView pic = new ImageView(c);
                pic.setScaleType(ImageView.ScaleType.CENTER_CROP);
                f.addView(pic, new FrameLayout.LayoutParams(Ui.MATCH, Ui.MATCH));
                ImageView heart = Ui.icon(c, R.drawable.ic_heart_fill, 0xFFFFFFFF, 14);
                FrameLayout.LayoutParams hp = new FrameLayout.LayoutParams(Ui.dp(c, 14), Ui.dp(c, 14), Gravity.BOTTOM | Gravity.START);
                hp.setMargins(Ui.dp(c, 5), 0, 0, Ui.dp(c, 5));
                f.addView(heart, hp);
                FrameLayout ck = new FrameLayout(c);
                FrameLayout.LayoutParams kp = new FrameLayout.LayoutParams(Ui.dp(c, 22), Ui.dp(c, 22), Gravity.TOP | Gravity.START);
                kp.setMargins(Ui.dp(c, 6), Ui.dp(c, 6), 0, 0);
                f.addView(ck, kp);
                LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(side, side);
                lp.setMargins(Ui.dp(c, 1), Ui.dp(c, 1), Ui.dp(c, 1), Ui.dp(c, 1));
                row.addView(f, lp);
                h.cell[i] = f; h.pic[i] = pic; h.heart[i] = heart; h.check[i] = ck;
            }
            row.setTag(h);
        } else row = (LinearLayout) cv;
        for (int i = 0; i < cols; i++) {
            if (i >= r.cells.size()) { h.cell[i].setVisibility(View.INVISIBLE); h.cell[i].setOnClickListener(null); h.cell[i].setOnLongClickListener(null); continue; }
            final Row pr = r.cells.get(i);
            final Photo p = pr.ph;
            h.cell[i].setVisibility(View.VISIBLE);
            Ui.photoThumb(c, p, h.pic[i], Ui.dp(c, 120));
            h.heart[i].setVisibility(s.isPhotoFav(p) ? View.VISIBLE : View.GONE);
            h.check[i].removeAllViews();
            if (selecting) {
                h.check[i].setVisibility(View.VISIBLE);
                h.check[i].addView(Ui.check(c, sel.contains(p.key())), new FrameLayout.LayoutParams(Ui.MATCH, Ui.MATCH));
                h.pic[i].setAlpha(sel.contains(p.key()) ? 0.55f : 1f);
            } else {
                h.check[i].setVisibility(View.GONE);
                h.pic[i].setAlpha(1f);
            }
            h.cell[i].setOnClickListener(v -> { if (selecting) toggleSel(p); else open(p, pr.ctx); });
            h.cell[i].setOnLongClickListener(v -> {
                if (selecting) toggleSel(p);
                else { selecting = true; sel.clear(); sel.add(p.key()); refresh(); }
                return true;
            });
        }
        return row;
    }

    View groupCell(final Row r, int cw) {
        Context c = a;
        LinearLayout box = new LinearLayout(c);
        box.setOrientation(LinearLayout.VERTICAL);
        int pad = Ui.dp(c, 4);
        box.setPadding(pad, pad, pad, pad);
        int side = cw - 2 * pad;
        FrameLayout pic = Ui.photoBox(c, r.cover, Math.round(side / c.getResources().getDisplayMetrics().density));
        if (r.more != null) {
            ImageView mo = Ui.btn(c, R.drawable.ic_more, 0xFFFFFFFF, 18, 6);
            mo.setBackground(Ui.shape(0x66000000, 100, c));
            mo.setOnClickListener(v -> r.more.run());
            FrameLayout.LayoutParams mp = new FrameLayout.LayoutParams(Ui.dp(c, 30), Ui.dp(c, 30), Gravity.TOP | Gravity.END);
            mp.setMargins(0, Ui.dp(c, 4), Ui.dp(c, 4), 0);
            pic.addView(mo, mp);
        }
        box.addView(pic, new LinearLayout.LayoutParams(side, side));
        TextView t = Ui.text(c, r.title, 13, Ui.TXT, true);
        t.setPadding(Ui.dp(c, 2), Ui.dp(c, 6), Ui.dp(c, 2), 0);
        box.addView(t);
        TextView st = Ui.text(c, r.sub, 11, Ui.SUB, false);
        st.setPadding(Ui.dp(c, 2), 0, Ui.dp(c, 2), 0);
        box.addView(st);
        box.setOnClickListener(v -> r.click.run());
        return box;
    }

    View circle(int icon, int bg, int tint) {
        LinearLayout f = new LinearLayout(a);
        f.setGravity(Gravity.CENTER);
        f.setBackground(Ui.shape(bg, 100, a));
        f.addView(Ui.icon(a, icon, tint, 22));
        f.setLayoutParams(new LinearLayout.LayoutParams(Ui.dp(a, 44), Ui.dp(a, 44)));
        return f;
    }
}
