package site.navatv.companion;

import static site.navatv.companion.Surface.*;

import android.graphics.drawable.GradientDrawable;
import android.os.Handler;
import android.os.Looper;
import android.text.Editable;
import android.text.InputType;
import android.text.TextUtils;
import android.text.TextWatcher;
import android.view.Gravity;
import android.view.View;
import android.view.inputmethod.EditorInfo;
import android.view.inputmethod.InputMethodManager;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.HorizontalScrollView;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.TextView;

import java.util.List;
import java.util.concurrent.Future;

/** The same media-library layout for every provider: lead story, swipeable rows and a grid. */
final class LibraryPage {
    private final MainActivity a;
    private final Surface u;
    private final Handler main = new Handler(Looper.getMainLooper());
    private LinearLayout results;
    private Runnable typing;
    private Future<?> work;
    private int request;

    LibraryPage(MainActivity activity) {
        a = activity;
        u = a.ui;
    }

    void close() {
        main.removeCallbacksAndMessages(null);
        if (work != null) work.cancel(true);
    }

    void build() {
        LibraryHub.Info current = a.hub.info(a.provider);
        a.provider = current.id;
        switcher(current);
        u.space(a.body, 12);
        search(current);
        u.space(a.body, 14);
        results = u.column();
        a.body.addView(results);
        load(current, a.query.trim());
    }

    private void switcher(LibraryHub.Info current) {
        HorizontalScrollView strip = new HorizontalScrollView(a);
        strip.setHorizontalScrollBarEnabled(false);
        LinearLayout row = u.row();
        View selected = null;
        for (LibraryHub.Info info : a.hub.all()) {
            boolean on = info.id.equals(current.id);
            LinearLayout tab = u.column();
            tab.setGravity(Gravity.CENTER_HORIZONTAL);
            u.pad(tab, 4, 4, 4, 0);
            TextView name = u.text(info.name, on ? 25 : 19, on ? INK : MUTED, on ? 800 : 620);
            name.setSingleLine();
            tab.addView(name, u.lp(WRAP, 36));
            View line = new View(a);
            line.setBackground(u.shape(on ? info.color : 0, 2, 0));
            tab.addView(line, u.lp(on ? 30 : 1, 4));
            u.action(tab, 0, 12, 0, () -> a.openProvider(info.id));
            tab.setContentDescription(info.name + (on ? ", selected" : ""));
            row.addView(tab, u.lp(WRAP, 52));
            u.gap(row, 14);
            if (on) selected = tab;
        }
        TextView add = u.text("＋ Add", 14, LILAC, 720);
        add.setGravity(Gravity.CENTER);
        u.pad(add, 14, 0, 14, 0);
        u.action(add, PALE, 18, 0, () -> a.go("libraries"));
        row.addView(add, u.lp(WRAP, 38));
        strip.addView(row);
        a.body.addView(strip);
        View target = selected;
        if (target != null) strip.post(() -> strip.scrollTo(Math.max(0, target.getLeft() - u.d(40)), 0));
    }

    private void search(LibraryHub.Info current) {
        LinearLayout box = u.row();
        box.setBackground(u.shape(PALE, 26, 0));
        u.pad(box, 18, 0, 8, 0);
        box.addView(new Art(a, "search", MUTED), u.lp(19, 19));
        u.gap(box, 11);
        EditText input = a.field("Search " + current.name, InputType.TYPE_CLASS_TEXT, a.query);
        input.setBackgroundColor(0);
        input.setSelectAllOnFocus(false);
        input.setImeOptions(EditorInfo.IME_ACTION_SEARCH);
        box.addView(input, u.weight(50));
        a.body.addView(box);
        a.body.setFocusableInTouchMode(true);
        a.body.requestFocus();
        input.addTextChangedListener(new TextWatcher() {
            public void beforeTextChanged(CharSequence s, int start, int count, int after) {}
            public void afterTextChanged(Editable e) {}
            public void onTextChanged(CharSequence s, int start, int before, int count) {
                a.query = s.toString();
                if (typing != null) main.removeCallbacks(typing);
                typing = () -> load(current, a.query.trim());
                main.postDelayed(typing, 450);
            }
        });
        input.setOnEditorActionListener((v, action, event) -> {
            a.getSystemService(InputMethodManager.class).hideSoftInputFromWindow(input.getWindowToken(), 0);
            if (typing != null) main.removeCallbacks(typing);
            load(current, a.query.trim());
            return true;
        });
    }

    private void load(LibraryHub.Info info, String query) {
        int serial = ++request;
        int generation = a.generation;
        if (work != null) work.cancel(true);
        results.removeAllViews();
        LinearLayout busy = u.row();
        busy.setGravity(Gravity.CENTER);
        busy.addView(new ProgressBar(a), u.lp(26, 26));
        results.addView(busy, u.lp(MATCH, 120));
        work = a.browse.submit(() -> {
            try {
                List<LibraryHub.Shelf> shelves = a.hub.load(info.id, query);
                main.post(() -> { if (current(serial, generation)) paint(info, query, shelves); });
            } catch (Exception failed) {
                main.post(() -> {
                    if (!current(serial, generation)) return;
                    results.removeAllViews();
                    empty(info.name + " is unavailable right now",
                            LibraryHub.builtIn(info.id) ? "Check your connection and try again." : a.error(failed));
                    results.addView(u.button("Try again", null, false, () -> load(info, query)), u.lp(MATCH, 52));
                });
            }
        });
    }

    private boolean current(int serial, int generation) {
        return !a.isFinishing() && !a.isDestroyed() && serial == request && generation == a.generation;
    }

    private void empty(String title, String detail) {
        u.space(results, 26);
        results.addView(u.text(title, 18, INK, 730));
        u.space(results, 8);
        results.addView(u.text(detail, 14, MUTED, 520));
        u.space(results, 18);
    }

    private void paint(LibraryHub.Info info, String query, List<LibraryHub.Shelf> shelves) {
        results.removeAllViews();
        if (shelves.isEmpty()) {
            empty("Nothing found", "Try another search.");
            return;
        }
        boolean lead = query.isEmpty();
        if (lead) {
            results.addView(hero(info, shelves.get(0).cards.get(0)));
        }
        for (int i = 0; i < shelves.size(); i++) {
            LibraryHub.Shelf shelf = shelves.get(i);
            List<LibraryHub.Card> cards = i == 0 && lead ? shelf.cards.subList(1, shelf.cards.size()) : shelf.cards;
            if (cards.isEmpty()) continue;
            u.space(results, 22);
            TextView heading = u.text(shelf.title, 19, INK, 760);
            heading.setAccessibilityHeading(true);
            results.addView(heading);
            u.space(results, 11);
            if (shelf.grid) grid(info, cards, 0);
            else row(info, cards);
        }
    }

    private FrameLayout cover(LibraryHub.Info info, LibraryHub.Card card, int radius) {
        FrameLayout frame = new FrameLayout(a);
        GradientDrawable ground = new GradientDrawable(GradientDrawable.Orientation.TL_BR,
                new int[] {(info.color & 0x00ffffff) | 0xaa000000, CARD});
        ground.setCornerRadius(u.d(radius));
        frame.setBackground(ground);
        frame.setClipToOutline(true);
        TextView mark = u.text(info.name, 15, 0xccffffff, 760);
        mark.setGravity(Gravity.CENTER);
        frame.addView(mark, new FrameLayout.LayoutParams(MATCH, MATCH));
        ImageView image = new ImageView(a);
        image.setScaleType(ImageView.ScaleType.CENTER_CROP);
        frame.addView(image, new FrameLayout.LayoutParams(MATCH, MATCH));
        a.images.into(image, card.image);
        return frame;
    }

    private int contentWidth() {
        return Math.min(510, a.getResources().getConfiguration().screenWidthDp - 40);
    }

    private View hero(LibraryHub.Info info, LibraryHub.Card card) {
        int height = Math.round(contentWidth() * .66f);
        FrameLayout frame = cover(info, card, 24);
        View shade = new View(a);
        shade.setBackground(new GradientDrawable(GradientDrawable.Orientation.BOTTOM_TOP,
                new int[] {0xf20a0b10, 0x660a0b10, 0x000a0b10}));
        frame.addView(shade, new FrameLayout.LayoutParams(MATCH, MATCH));
        LinearLayout words = u.column();
        u.pad(words, 18, 0, 18, 16);
        TextView title = u.text(card.title, 23, WHITE, 800);
        title.setMaxLines(2);
        title.setEllipsize(TextUtils.TruncateAt.END);
        words.addView(title);
        String meta = meta(card);
        if (!meta.isEmpty()) {
            u.space(words, 6);
            TextView line = u.text(meta, 13, 0xffd8dbe6, 560);
            line.setSingleLine();
            line.setEllipsize(TextUtils.TruncateAt.END);
            words.addView(line);
        }
        u.space(words, 13);
        words.addView(u.button("Play on TV", "resume", true, () -> play(card)), u.lp(WRAP, 44));
        frame.addView(words, new FrameLayout.LayoutParams(MATCH, WRAP, Gravity.BOTTOM));
        frame.setOnClickListener(v -> detail(info, card));
        frame.setContentDescription(card.title + ". Details");
        frame.setLayoutParams(u.lp(MATCH, height));
        return frame;
    }

    private LinearLayout tile(LibraryHub.Info info, LibraryHub.Card card, int width) {
        LinearLayout tile = u.column();
        tile.addView(cover(info, card, 15), u.lp(width, Math.round(width * 9f / 16)));
        u.space(tile, 8);
        TextView title = u.text(card.title, 14, INK, 680);
        title.setMaxLines(2);
        title.setMinLines(2);
        title.setEllipsize(TextUtils.TruncateAt.END);
        title.setGravity(Gravity.TOP);
        tile.addView(title, u.lp(width, WRAP));
        u.space(tile, 3);
        TextView meta = u.text(meta(card), 12, MUTED, 520);
        meta.setSingleLine();
        meta.setEllipsize(TextUtils.TruncateAt.END);
        tile.addView(meta, u.lp(width, WRAP));
        u.action(tile, 0, 15, 0, () -> detail(info, card));
        tile.setContentDescription(card.title + ". " + meta(card));
        return tile;
    }

    private void row(LibraryHub.Info info, List<LibraryHub.Card> cards) {
        HorizontalScrollView strip = new HorizontalScrollView(a);
        strip.setHorizontalScrollBarEnabled(false);
        LinearLayout row = u.row();
        row.setGravity(Gravity.TOP);
        for (int i = 0; i < Math.min(30, cards.size()); i++) {
            row.addView(tile(info, cards.get(i), 190), u.lp(190, WRAP));
            u.gap(row, 12);
        }
        strip.addView(row);
        results.addView(strip);
    }

    private void grid(LibraryHub.Info info, List<LibraryHub.Card> cards, int from) {
        int width = (contentWidth() - 12) / 2;
        int end = Math.min(cards.size(), from + 12);
        for (int i = from; i < end; i += 2) {
            LinearLayout row = u.row();
            row.setGravity(Gravity.TOP);
            row.addView(tile(info, cards.get(i), width), u.lp(width, WRAP));
            u.gap(row, 12);
            if (i + 1 < end) row.addView(tile(info, cards.get(i + 1), width), u.lp(width, WRAP));
            results.addView(row);
            u.space(results, 16);
        }
        if (end < cards.size()) {
            LinearLayout more = u.button("Show more", null, false, () -> {});
            more.setOnClickListener(v -> {
                results.removeView(more);
                grid(info, cards, end);
            });
            results.addView(more, u.lp(MATCH, 52));
        }
    }

    static String meta(LibraryHub.Card card) {
        String length = card.duration > 0 ? Math.max(1, Math.round(card.duration / 60f)) + " min" : "";
        if (card.subtitle.isEmpty()) return length;
        return length.isEmpty() ? card.subtitle : length + " · " + card.subtitle;
    }

    private void detail(LibraryHub.Info info, LibraryHub.Card card) {
        Sheet sheet = new Sheet(a, .82f);
        sheet.show(info.name, body -> {
            body.addView(cover(info, card, 20), u.lp(MATCH, Math.round((contentWidth()) * 9f / 16)));
            u.space(body, 17);
            body.addView(u.text(card.title, 23, INK, 800));
            String meta = meta(card);
            if (!meta.isEmpty()) {
                u.space(body, 7);
                body.addView(u.text(meta, 13, MUTED, 560));
            }
            u.space(body, 18);
            body.addView(u.button("Play on TV", "resume", true, () -> {
                sheet.dismiss();
                play(card);
            }), u.lp(MATCH, 56));
            if (!card.description.isEmpty()) {
                u.space(body, 17);
                body.addView(u.text(card.description, 15, 0xffd3d6e2, 480));
            }
        });
    }

    void play(LibraryHub.Card card) {
        if (LibraryHub.builtIn(card.library)) {
            a.play(card.library, card.detail, card.title, false);
            return;
        }
        if (!a.connected()) {
            a.needConnection();
            return;
        }
        TvConnection tv = a.tv;
        a.runTv(() -> {
            tv.playOwn(card.detail, card.title);
            return null;
        }, done -> a.played(card.library, card.title));
    }
}
