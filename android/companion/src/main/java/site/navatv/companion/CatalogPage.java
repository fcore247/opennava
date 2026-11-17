package site.navatv.companion;

import static site.navatv.companion.Surface.*;

import android.graphics.*;
import android.graphics.drawable.GradientDrawable;
import android.os.Handler;
import android.os.Looper;
import android.text.*;
import android.util.LruCache;
import android.view.*;
import android.view.inputmethod.*;
import android.widget.*;

import site.navatv.launcher.PhoneCatalog;

import java.io.*;
import java.net.*;
import java.util.*;
import java.util.concurrent.*;

final class CatalogPage {
    private final MainActivity a;
    private final Surface u;
    private final Handler main = new Handler(Looper.getMainLooper());
    private final LruCache<String, Bitmap> photos =
            new LruCache<String, Bitmap>(12 * 1024 * 1024) {
                @Override
                protected int sizeOf(String key, Bitmap bitmap) {
                    return bitmap.getAllocationByteCount();
                }
            };
    private final Map<String, Bitmap> posters = new HashMap<>();
    private static final String[] SENDERS = {"ard", "zdf", "arte"};
    private static final String[][] TOPICS = {
        {"Tagesschau", "Tatort", "Dokus", "Natur"},
        {"heute", "Terra X", "Krimis", "Bares für Rares"},
        {"Stadt Land Kunst", "Zu Tisch", "Geschichte", "Natur"}
    };
    private final ExecutorService pictures = Executors.newFixedThreadPool(3);
    private final Map<String, List<PhoneCatalog.Item>> recent = new LinkedHashMap<>();
    private final Map<String, Long> fetched = new HashMap<>();
    private Future<?> work;
    private Runnable pending;
    private int request;
    private LinearLayout results;
    private EditText input;

    CatalogPage(MainActivity a) {
        this.a = a;
        u = a.ui;
    }

    void close() {
        main.removeCallbacksAndMessages(null);
        if (work != null) work.cancel(true);
        pictures.shutdownNow();
        a.catalogue.close();
    }

    private Bitmap poster(String id) {
        if (!posters.containsKey(id))
            try (InputStream s = a.getAssets().open(id + "-cover.jpg")) {
                posters.put(id, BitmapFactory.decodeStream(s));
            } catch (IOException ignored) {
            }
        return posters.get(id);
    }

    void index() {
        a.body.addView(u.title("Libraries", 43, INK));
        u.space(a.body, 18);
        for (String id : SENDERS) {
            cover(id);
            u.space(a.body, 10);
        }
    }

    private void cover(String provider) {
        FrameLayout card = new FrameLayout(a);
        card.setBackground(u.shape(GREEN, 24, 0));
        card.setClipToOutline(true);
        ImageView image = new ImageView(a);
        image.setScaleType(ImageView.ScaleType.CENTER_CROP);
        image.setImageBitmap(poster(provider));
        card.addView(image, new FrameLayout.LayoutParams(MATCH, MATCH));
        View shade = new View(a);
        shade.setBackground(
                new GradientDrawable(
                        GradientDrawable.Orientation.LEFT_RIGHT,
                        new int[] {0xe91b3026, 0x9c1c261e, 0x1015211c}));
        card.addView(shade);
        LinearLayout labels = u.column();
        labels.setGravity(Gravity.BOTTOM);
        u.pad(labels, 21, 13, 20, 15);
        labels.addView(u.text("Library", 12, 0xffe4e8db, 630));
        labels.addView(u.title(provider.toUpperCase(Locale.ROOT), 46, WHITE));
        card.addView(labels, new FrameLayout.LayoutParams(MATCH, MATCH));
        FrameLayout arrow = new FrameLayout(a);
        arrow.setBackground(u.shape(0x540d170f, 22, 0x82f2f1e6));
        arrow.addView(
                new Art(a, "arrow", WHITE),
                new FrameLayout.LayoutParams(u.d(19), u.d(19), Gravity.CENTER));
        FrameLayout.LayoutParams pos =
                new FrameLayout.LayoutParams(u.d(36), u.d(36), Gravity.RIGHT | Gravity.BOTTOM);
        pos.setMargins(0, 0, u.d(16), u.d(17));
        card.addView(arrow, pos);
        card.setOnClickListener(v -> a.openProvider(provider));
        card.setFocusable(true);
        card.setContentDescription(provider.toUpperCase(Locale.ROOT) + " library");
        int height =
                Math.min(
                        166,
                        Math.max(112, a.getResources().getConfiguration().screenHeightDp / 5 - 3));
        a.body.addView(card, u.lp(MATCH, height));
    }

    void station() {
        final String id = a.provider;
        final String title = id.toUpperCase(Locale.ROOT);
        int gen = a.generation;
        LinearLayout crumbs = u.row();
        crumbs.addView(new Art(a, "left", MUTED), u.lp(15, 15));
        u.gap(crumbs, 3);
        crumbs.addView(u.text("All libraries", 13, MUTED, 590));
        u.action(
                crumbs,
                0,
                12,
                0,
                () -> {
                    a.provider = "";
                    a.query = "";
                    a.render();
                });
        a.body.addView(crumbs, u.lp(WRAP, 41));
        LinearLayout line = u.row();
        line.addView(u.title(title, 44, INK), u.weight(WRAP));
        line.addView(
                u.button("Live", "onair", true, () -> a.play(id, "", title + " live", true)),
                u.lp(87, 46));
        a.body.addView(line);
        u.space(a.body, 14);
        LinearLayout search = u.row();
        search.setBackground(u.shape(WHITE, 15, LINE));
        u.pad(search, 15, 0, 10, 0);
        search.addView(new Art(a, "search", MUTED), u.lp(20, 20));
        u.gap(search, 10);
        input = a.field("Search " + title + "", android.text.InputType.TYPE_CLASS_TEXT, a.query);
        input.setBackgroundColor(0);
        input.setSelectAllOnFocus(false);
        input.setSelection(input.length());
        input.setImeOptions(EditorInfo.IME_ACTION_SEARCH);
        search.addView(input, u.weight(50));
        a.body.addView(search);
        a.body.setFocusableInTouchMode(true);
        a.body.requestFocus();
        u.space(a.body, 9);
        HorizontalScrollView hz = new HorizontalScrollView(a);
        hz.setHorizontalScrollBarEnabled(false);
        LinearLayout chips = u.row();
        for (String topic : TOPICS[Arrays.asList(SENDERS).indexOf(id)]) {
            TextView chip = u.text(topic, 12, INK, 650);
            chip.setGravity(Gravity.CENTER);
            u.pad(chip, 13, 0, 13, 0);
            u.action(
                    chip,
                    PALE,
                    22,
                    LINE,
                    () -> {
                        input.setText(topic);
                        input.setSelection(topic.length());
                        hideKeyboard();
                    });
            chips.addView(chip, u.lp(WRAP, 43));
            u.gap(chips, 6);
        }
        hz.addView(chips);
        a.body.addView(hz);
        u.space(a.body, 8);
        LinearLayout tv = u.row();
        tv.addView(new Art(a, "tv", GREEN), u.lp(17, 17));
        u.gap(tv, 7);
        tv.addView(u.text("Open on TV", 13, GREEN, 650));
        u.pad(tv, 3, 0, 9, 0);
        u.action(tv, 0, 12, 0, () -> a.sendSearch(id, input.getText().toString().trim()));
        a.body.addView(tv, u.lp(WRAP, 43));
        u.space(a.body, 7);
        results = u.column();
        a.body.addView(results);
        input.addTextChangedListener(
                new TextWatcher() {
                    public void beforeTextChanged(
                            CharSequence s, int start, int count, int after) {}

                    public void onTextChanged(CharSequence s, int st, int before, int count) {
                        a.query = s.toString();
                        if (pending != null) main.removeCallbacks(pending);
                        int serial = ++request;
                        if (work != null) work.cancel(true);
                        results.removeAllViews();
                        results.addView(u.text("Search…", 14, MUTED, 550));
                        pending =
                                () -> {
                                    if (gen == a.generation && serial == request)
                                        load(gen, id, a.query.trim(), false);
                                };
                        main.postDelayed(pending, 360);
                    }

                    public void afterTextChanged(Editable e) {}
                });
        input.setOnEditorActionListener(
                (v, action, event) -> {
                    if (action != EditorInfo.IME_ACTION_SEARCH
                            && action != EditorInfo.IME_ACTION_DONE) return false;
                    hideKeyboard();
                    if (pending != null) main.removeCallbacks(pending);
                    load(gen, id, a.query.trim(), false);
                    return true;
                });
        load(gen, id, a.query.trim(), false);
    }

    private void hideKeyboard() {
        if (input != null)
            ((InputMethodManager) a.getSystemService(android.content.Context.INPUT_METHOD_SERVICE))
                    .hideSoftInputFromWindow(input.getWindowToken(), 0);
    }

    private void load(int gen, String provider, String q, boolean force) {
        final int serial = ++request;
        String key = provider + '|' + q;
        if (!force
                && recent.containsKey(key)
                && System.currentTimeMillis() - fetched.get(key) < 180_000) {
            paint(provider, q, recent.get(key), gen, serial);
            return;
        }
        if (work != null) work.cancel(true);
        results.removeAllViews();
        LinearLayout busy = u.row();
        ProgressBar spinner = new ProgressBar(a);
        busy.addView(spinner, u.lp(20, 20));
        u.gap(busy, 11);
        busy.addView(
                u.text(
                        q.isEmpty() ? "Loading programmes…" : "Searching programmes…",
                        14,
                        MUTED,
                        500));
        results.addView(busy, u.lp(MATCH, 45));
        work =
                a.browse.submit(
                        () -> {
                            try {
                                List<PhoneCatalog.Item> items =
                                        q.isEmpty()
                                                ? a.catalogue.home(provider)
                                                : a.catalogue.search(provider, q);
                                main.post(
                                        () -> {
                                            if (!current(gen, serial)) return;
                                            if (recent.size() > 22) {
                                                recent.clear();
                                                fetched.clear();
                                            }
                                            recent.put(key, items);
                                            fetched.put(key, System.currentTimeMillis());
                                            paint(provider, q, items, gen, serial);
                                        });
                            } catch (Exception error) {
                                main.post(
                                        () -> {
                                            if (!current(gen, serial)) return;
                                            results.removeAllViews();
                                            results.addView(
                                                    u.text(
                                                            "This library is currently unavailable.",
                                                            15,
                                                            INK,
                                                            630));
                                            u.space(results, 8);
                                            results.addView(
                                                    u.button(
                                                            "Try again",
                                                            null,
                                                            false,
                                                            () -> load(gen, provider, q, true)),
                                                    u.lp(WRAP, 48));
                                        });
                            }
                        });
    }

    private boolean current(int gen, int serial) {
        return !a.isDestroyed() && !a.isFinishing() && a.generation == gen && request == serial;
    }

    private void paint(
            String provider, String q, List<PhoneCatalog.Item> items, int gen, int serial) {
        results.removeAllViews();
        if (items.isEmpty()) {
            u.space(results, 8);
            results.addView(u.text("No matching programmes.", 17, INK, 650));
            u.space(results, 5);
            results.addView(u.text("Try another search term.", 14, MUTED, 470));
            return;
        }
        results.addView(u.text(q.isEmpty() ? "Recommendations" : "Search results", 18, INK, 700));
        u.space(results, 5);
        append(provider, items, 0, gen, serial);
    }

    private void append(
            String provider, List<PhoneCatalog.Item> items, int offset, int gen, int serial) {
        int end = Math.min(offset + 10, items.size());
        for (int n = offset; n < end; n++) {
            PhoneCatalog.Item item = items.get(n);
            LinearLayout b = u.row();
            u.pad(b, 0, 10, 0, 10);
            FrameLayout thumb = new FrameLayout(a);
            thumb.setBackground(u.shape(GREEN, 9, 0));
            thumb.setClipToOutline(true);
            TextView mark = u.text(provider.toUpperCase(Locale.ROOT), 14, WHITE, 680);
            mark.setGravity(Gravity.CENTER);
            thumb.addView(mark);
            ImageView im = new ImageView(a);
            im.setScaleType(ImageView.ScaleType.CENTER_CROP);
            thumb.addView(im);
            b.addView(thumb, u.lp(94, 62));
            u.gap(b, 12);
            LinearLayout copy = u.column();
            TextView t = u.text(item.title, 15, INK, 690);
            t.setMaxLines(2);
            t.setEllipsize(android.text.TextUtils.TruncateAt.END);
            copy.addView(t);
            String meta =
                    provider.toUpperCase(Locale.ROOT)
                            + (item.duration > 0
                                    ? "  ·  "
                                            + Math.max(1, Math.round(item.duration / 60.0))
                                            + " min"
                                    : "");
            u.space(copy, 4);
            copy.addView(u.text(meta, 12, MUTED, 490));
            b.addView(copy, u.weight(WRAP));
            u.gap(b, 7);
            b.addView(new Art(a, "cast", GREEN), u.lp(19, 19));
            u.action(b, 0, 12, 0, () -> a.play(provider, item.detail, item.title, false));
            b.setContentDescription(item.title + " · Watch on TV");
            results.addView(b);
            results.addView(u.rule(), u.lp(MATCH, 1));
            photo(im, item.image, gen, serial);
        }
        if (end < items.size()) {
            u.space(results, 8);
            LinearLayout more = u.button("More programmes", null, false, () -> {});
            more.setOnClickListener(
                    v -> {
                        results.removeView(more);
                        append(provider, items, end, gen, serial);
                    });
            results.addView(more, u.lp(WRAP, 48));
        }
    }

    private void photo(ImageView im, String url, int gen, int serial) {
        if (url == null || !url.startsWith("https://")) return;
        Bitmap known = photos.get(url);
        if (known != null) {
            im.setImageBitmap(known);
            return;
        }
        pictures.execute(
                () -> {
                    if (!current(gen, serial)) return;
                    HttpURLConnection conn = null;
                    try {
                        conn = (HttpURLConnection) new URL(url).openConnection();
                        conn.setConnectTimeout(3500);
                        conn.setReadTimeout(5000);
                        conn.setRequestProperty("User-Agent", "OpenNava/1.0 (Android)");
                        try (InputStream in = conn.getInputStream();
                                ByteArrayOutputStream out = new ByteArrayOutputStream()) {
                            byte[] b = new byte[8192];
                            int n;
                            while ((n = in.read(b)) != -1) {
                                if (out.size() + n > 4_000_000) return;
                                out.write(b, 0, n);
                            }
                            byte[] data = out.toByteArray();
                            BitmapFactory.Options opts = new BitmapFactory.Options();
                            opts.inJustDecodeBounds = true;
                            BitmapFactory.decodeByteArray(data, 0, data.length, opts);
                            opts.inJustDecodeBounds = false;
                            opts.inSampleSize = 1;
                            while (opts.outWidth / opts.inSampleSize > 650
                                    || opts.outHeight / opts.inSampleSize > 650)
                                opts.inSampleSize *= 2;
                            Bitmap bm = BitmapFactory.decodeByteArray(data, 0, data.length, opts);
                            if (bm != null) {
                                photos.put(url, bm);
                                main.post(
                                        () -> {
                                            if (current(gen, serial)) im.setImageBitmap(bm);
                                        });
                            }
                        }
                    } catch (IOException ignored) {
                    } finally {
                        if (conn != null) conn.disconnect();
                    }
                });
    }
}
