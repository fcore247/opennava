package site.navatv.companion;

import static site.navatv.companion.Surface.*;

import android.content.res.ColorStateList;
import android.view.Gravity;
import android.view.View;
import android.widget.FrameLayout;
import android.widget.HorizontalScrollView;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.TextView;

import site.navatv.launcher.PhoneGuide;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Calendar;
import java.util.List;

/** The first page of the phone: choose what to watch. */
final class StartPage {
    private final MainActivity a;
    private final Surface u;
    private TextView liveTitle, liveDetail, liveBadge;
    private ProgressBar liveProgress;
    private boolean welcomed, editing;
    private HomeBoard board;

    /** The tiles this app brings along, in their first order. */
    private static final String[] OWN = {"live", "libraries", "navatube", "netflix", "youtube", "shelf"};
    private static final String[] OWN_NAMES = {"Live TV", "Libraries", "NavaTube", "Netflix", "YouTube", "Apps on your TV"};
    private static final String[] OWN_NOTES = {"What is on right now", "ARD · ZDF · ARTE", "Your videos",
            "Open on your TV", "Open on your TV", "A row of your TV’s apps"};
    private static final String APP = "app:";

    StartPage(MainActivity activity) {
        a = activity;
        u = a.ui;
    }

    boolean editing() { return editing; }

    /** While a widget is in the hand the page must not be rebuilt under it. */
    boolean dragging() { return board != null && board.dragging(); }

    void stopEditing() { editing = false; }

    void edit(boolean value) {
        editing = value;
        a.render();
    }

    private android.content.SharedPreferences prefs() {
        return a.getSharedPreferences("home", android.content.Context.MODE_PRIVATE);
    }

    private List<String> widgets() {
        String saved = prefs().getString("widgets", null);
        if (saved == null) return new ArrayList<>(Arrays.asList(OWN));
        List<String> ids = new ArrayList<>();
        for (String id : saved.split(",")) {
            boolean own = Arrays.asList(OWN).contains(id);
            boolean app = id.startsWith(APP) && id.substring(APP.length()).matches("[A-Za-z0-9_]+(\\.[A-Za-z0-9_]+)+");
            if ((own || app) && !ids.contains(id)) ids.add(id);
        }
        return ids;
    }

    private void keep(List<String> ids) { prefs().edit().putString("widgets", String.join(",", ids)).apply(); }

    void build() {
        liveTitle = null;
        a.apps.refresh(false);
        int hour = Calendar.getInstance().get(Calendar.HOUR_OF_DAY);
        LinearLayout head = u.row();
        TextView greeting = u.title(hour < 5 ? "Good evening" : hour < 12 ? "Good morning"
                : hour < 18 ? "Good afternoon" : "Good evening", 38, INK);
        u.pad(greeting, 2, 4, 0, 0);
        greeting.setAccessibilityHeading(true);
        head.addView(greeting, u.weight(WRAP));
        head.addView(editButton(), u.lp(editing ? WRAP : 46, 46));
        a.body.addView(head);
        u.space(a.body, 3);
        TextView question = u.text(editing ? "Hold and drag to arrange. Tap − to remove."
                : "What would you like to watch?", 15, editing ? LILAC : MUTED, 540);
        u.pad(question, 3, 0, 0, 0);
        a.body.addView(question);
        u.space(a.body, 18);

        board = new HomeBoard(a);
        for (String id : widgets()) widget(id);
        board.reordered = this::keep;
        board.removed = id -> {
            List<String> ids = widgets();
            ids.remove(id);
            keep(ids);
            a.render();
        };
        a.body.addView(board, u.lp(MATCH, WRAP));
        board.editing(editing);
        if (editing) editTools();
        else if (widgets().isEmpty()) {
            TextView empty = u.text("Your start page is empty.\nTap the pencil to add widgets.", 15, MUTED, 560);
            empty.setGravity(Gravity.CENTER);
            u.pad(empty, 20, 60, 20, 60);
            a.body.addView(empty, u.lp(MATCH, WRAP));
        }
        tvChanged();
        welcome();
    }

    private View editButton() {
        if (editing) {
            TextView done = u.text("Done", 14.5f, ON_LILAC, 800);
            done.setGravity(Gravity.CENTER);
            u.pad(done, 22, 0, 22, 0);
            u.action(done, LILAC, 23, 0, () -> edit(false));
            done.setContentDescription("Finish editing the start page");
            return done;
        }
        FrameLayout pencil = new FrameLayout(a);
        pencil.addView(new Art(a, "pencil", LILAC), new FrameLayout.LayoutParams(u.d(21), u.d(21), Gravity.CENTER));
        u.action(pencil, CARD, 23, LINE, () -> edit(true));
        pencil.setContentDescription("Edit start page");
        return pencil;
    }

    private void widget(String id) {
        switch (id) {
            case "live":
                board.add(id, live(), true, 214, "Live TV");
                break;
            case "libraries": {
                FrameLayout tile = HomeTile.frame(a, u, 26, new int[] {0xff24463a, 0xff10201c});
                HomeTile.photo(a, u, tile, "home/forest.jpg");
                HomeTile.mark(a, u, tile, new Art(a, "library", WHITE), 0xcc0f2a22);
                HomeTile.words(a, u, tile, "Libraries",
                        "us".equals(Region.country(a)) && Region.chosen(a) ? "UCTV · TWiT" : "ARD · ZDF · ARTE", 20);
                HomeTile.press(tile, "Open our media libraries", () -> a.go("library"));
                board.add(id, tile, false, 150, "Libraries");
                break;
            }
            case "navatube":
                board.add(id, navaTube(), false, 150, "NavaTube");
                break;
            case "netflix":
                board.add(id, service("Netflix", "N", new int[] {0xff4a0c12, 0xff130506}, 0xff000000, 0xffe50914,
                        TvApps.NETFLIX), false, 150, "Netflix");
                break;
            case "youtube":
                board.add(id, service("YouTube", "▶", new int[] {0xff7d1620, 0xff25090d}, 0xffff2438, WHITE,
                        TvApps.YOUTUBE), false, 150, "YouTube");
                break;
            case "shelf":
                board.add(id, shelf(), true, 0, "Apps on your TV");
                break;
            default:
                String pkg = id.substring(APP.length());
                board.add(id, appTile(pkg), false, 150, appName(pkg));
        }
    }

    private String appName(String pkg) {
        TvApps.Entry entry = a.apps.find(new String[] {pkg});
        return entry != null ? entry.name : prefs().getString("name." + pkg, TvStatus.appName(pkg));
    }

    /** One app of the TV as a widget of its own, with the picture the TV shows for it. */
    private View appTile(String pkg) {
        TvApps.Entry entry = a.apps.find(new String[] {pkg});
        String name = appName(pkg);
        FrameLayout tile = HomeTile.frame(a, u, 26, new int[] {0xff2b2f45, 0xff151826});
        if (entry != null) {
            FrameLayout art = AppsPage.art(a, u, entry, 18);
            // Sixteen by nine, with room underneath for the two lines of text.
            FrameLayout.LayoutParams place = new FrameLayout.LayoutParams(u.d(128), u.d(72), Gravity.TOP | Gravity.START);
            place.setMargins(u.d(12), u.d(11), u.d(12), 0);
            tile.addView(art, place);
        } else {
            TextView letter = u.title(name.substring(0, 1), 52, LILAC);
            FrameLayout.LayoutParams place = new FrameLayout.LayoutParams(WRAP, WRAP, Gravity.TOP | Gravity.START);
            place.setMargins(u.d(18), u.d(16), 0, 0);
            tile.addView(letter, place);
        }
        boolean missing = a.connected() && !a.apps.list().isEmpty() && entry == null;
        HomeTile.words(a, u, tile, name, missing ? "Not installed on this TV" : "Open on your TV", 16);
        if (missing) tile.setAlpha(.55f);
        HomeTile.press(tile, "Open " + name + " on your TV", () -> {
            if (entry != null) a.openApp(entry);
            else a.openService(name, new String[] {pkg});
        });
        return tile;
    }

    private void editTools() {
        u.space(a.body, 14);
        LinearLayout add = u.row();
        add.setGravity(Gravity.CENTER);
        android.graphics.drawable.GradientDrawable dashed = u.shape(0x14cabaff, 26, 0);
        dashed.setStroke(u.d(2), 0x88cabaff, u.d(8), u.d(6));
        add.setBackground(dashed);
        add.addView(new Art(a, "plus", LILAC), u.lp(22, 22));
        u.gap(add, 10);
        add.addView(u.text("Add widget", 16, LILAC, 800));
        add.setOnClickListener(v -> addSheet());
        add.setFocusable(true);
        add.setContentDescription("Add widget");
        a.body.addView(add, u.lp(MATCH, 78));
        u.space(a.body, 10);
        TextView reset = u.text("Reset to the original layout", 13.5f, MUTED, 680);
        reset.setGravity(Gravity.CENTER);
        u.action(reset, 0, 20, 0, () -> {
            prefs().edit().remove("widgets").apply();
            a.render();
        });
        a.body.addView(reset, u.lp(MATCH, 46));
    }

    /** Everything that can go on the start page: our own tiles and every app the TV reports. */
    private void addSheet() {
        Sheet sheet = new Sheet(a, .84f);
        a.apps.refresh(true);
        sheet.onClose(a::render);
        sheet.show("Add widgets", body -> {
            section(body, "OpenNava");
            for (int i = 0; i < OWN.length; i++) {
                View art = new Art(a, new String[] {"onair", "library", "resume", "tv", "tv", "apps"}[i], LILAC);
                choice(sheet, body, OWN[i], OWN_NAMES[i], OWN_NOTES[i], art, 22);
            }
            List<TvApps.Entry> apps = a.apps.list();
            section(body, a.connected() ? L.t("Apps on your TV") + "  ·  " + apps.size() : L.t("Apps on your TV"));
            if (!a.connected() || apps.isEmpty()) {
                TextView note = u.text(!a.connected() ? "Connect your TV to add its apps."
                        : a.apps.problem.isEmpty() ? "Asking your TV for its apps…" : a.apps.problem, 14, MUTED, 560);
                u.pad(note, 8, 8, 8, 8);
                body.addView(note);
                TextView again = u.text("Look again", 14, LILAC, 780);
                u.pad(again, 8, 12, 8, 12);
                again.setOnClickListener(v -> sheet.refresh());
                body.addView(again);
                return;
            }
            for (TvApps.Entry app : apps)
                choice(sheet, body, APP + app.id, app.name, "App on your TV", AppsPage.art(a, u, app, 10), 0);
        });
    }

    private void section(LinearLayout body, String name) {
        u.space(body, 10);
        TextView label = u.text(L.t(name).toUpperCase(java.util.Locale.ROOT), 12, LILAC, 800);
        label.setLetterSpacing(.08f);
        u.pad(label, 6, 0, 0, 0);
        body.addView(label);
        u.space(body, 8);
    }

    private void choice(Sheet sheet, LinearLayout body, String id, String name, String note, View art, int glyph) {
        boolean added = widgets().contains(id);
        LinearLayout row = u.row();
        u.pad(row, 10, 9, 10, 9);
        FrameLayout frame = new FrameLayout(a);
        if (glyph > 0) {
            frame.setBackground(u.shape(PALE, 14, 0));
            frame.addView(art, new FrameLayout.LayoutParams(u.d(glyph), u.d(glyph), Gravity.CENTER));
        } else frame.addView(art, new FrameLayout.LayoutParams(MATCH, MATCH));
        row.addView(frame, u.lp(86, 50));
        u.gap(row, 13);
        LinearLayout words = u.column();
        TextView first = u.text(name, 15.5f, INK, 720);
        first.setSingleLine();
        first.setEllipsize(android.text.TextUtils.TruncateAt.END);
        words.addView(first);
        u.space(words, 5);
        words.addView(u.text(note, 12.5f, MUTED, 520));
        row.addView(words, u.weight(WRAP));
        u.gap(row, 10);
        FrameLayout mark = new FrameLayout(a);
        mark.setBackground(u.shape(added ? LILAC : PALE, 19, 0));
        mark.addView(new Art(a, added ? "ok" : "plus", added ? ON_LILAC : INK),
                new FrameLayout.LayoutParams(u.d(18), u.d(18), Gravity.CENTER));
        row.addView(mark, u.lp(38, 38));
        u.action(row, added ? 0x1acabaff : 0, 18, 0, () -> {
            List<String> ids = widgets();
            if (!ids.remove(id)) {
                ids.add(id);
                if (id.startsWith(APP)) prefs().edit().putString("name." + id.substring(APP.length()), name).apply();
            }
            keep(ids);
            sheet.refresh();
        });
        row.setContentDescription((added ? "Remove " : "Add ") + name);
        body.addView(row, u.lp(MATCH, WRAP));
    }

    private View navaTube() {
        FrameLayout tile = HomeTile.frame(a, u, 26, new int[] {0xff8d72e8, 0xff3b2a78, 0xff1c1540});
        HomeTile.mark(a, u, tile, u.text("▶", 18, 0xff3b2a78, 800), 0xffe8defe);
        HomeTile.words(a, u, tile, "NavaTube", a.navaTubeSummary(), 20);
        HomeTile.press(tile, "Open NavaTube", () -> a.go("navatube"));
        return tile;
    }

    private View service(String name, String glyph, int[] ground, int badge, int ink, String[] packages) {
        FrameLayout tile = HomeTile.frame(a, u, 26, ground);
        HomeTile.mark(a, u, tile, u.text(glyph, glyph.equals("N") ? 27 : 17, ink, 800), badge);
        boolean missing = a.connected() && !a.apps.list().isEmpty() && a.apps.find(packages) == null;
        HomeTile.words(a, u, tile, name, missing ? "Not installed on this TV" : "Open on your TV", 19);
        if (missing) tile.setAlpha(.55f);
        HomeTile.press(tile, "Open " + name + " on your TV", () -> a.openService(name, packages));
        return tile;
    }

    private View live() {
        FrameLayout tile = HomeTile.frame(a, u, 30, new int[] {0xff1d3b52, 0xff0d1722});
        HomeTile.photo(a, u, tile, "home/ocean.jpg");
        LinearLayout badge = u.row();
        badge.setBackground(u.shape(0xcc0d1018, 14, 0));
        u.pad(badge, 12, 7, 13, 7);
        View dot = new View(a);
        dot.setBackground(u.shape(RED, 5, 0));
        badge.addView(dot, u.lp(8, 8));
        u.gap(badge, 7);
        liveBadge = u.text("LIVE TV", 11.5f, WHITE, 820);
        liveBadge.setLetterSpacing(.07f);
        badge.addView(liveBadge);
        FrameLayout.LayoutParams badgePlace = new FrameLayout.LayoutParams(WRAP, WRAP, Gravity.TOP | Gravity.START);
        badgePlace.setMargins(u.d(16), u.d(16), 0, 0);
        tile.addView(badge, badgePlace);

        LinearLayout bottom = u.row();
        bottom.setGravity(Gravity.BOTTOM);
        u.pad(bottom, 18, 0, 16, 16);
        LinearLayout words = u.column();
        liveTitle = u.text("", 25, WHITE, 800);
        liveTitle.setMaxLines(2);
        liveTitle.setEllipsize(android.text.TextUtils.TruncateAt.END);
        words.addView(liveTitle);
        u.space(words, 7);
        liveDetail = u.text("", 13, 0xffd5d7e4, 600);
        liveDetail.setSingleLine();
        words.addView(liveDetail);
        u.space(words, 11);
        liveProgress = new ProgressBar(a, null, android.R.attr.progressBarStyleHorizontal);
        liveProgress.setMax(1000);
        liveProgress.setProgressTintList(ColorStateList.valueOf(LILAC));
        liveProgress.setProgressBackgroundTintList(ColorStateList.valueOf(0x66ffffff));
        words.addView(liveProgress, u.lp(MATCH, 4));
        bottom.addView(words, u.weight(WRAP));
        u.gap(bottom, 16);
        FrameLayout go = new FrameLayout(a);
        go.setBackground(u.shape(LILAC, 29, 0));
        go.addView(new Art(a, "right", ON_LILAC), new FrameLayout.LayoutParams(u.d(22), u.d(22), Gravity.CENTER));
        bottom.addView(go, u.lp(58, 58));
        tile.addView(bottom, new FrameLayout.LayoutParams(MATCH, WRAP, Gravity.BOTTOM));
        HomeTile.press(tile, "Open live TV", () -> a.go("live"));
        return tile;
    }

    /** Keeps the live tile in step with the television without rebuilding the page. */
    void tvChanged() {
        if (liveTitle == null) return;
        PhoneTvContextController.Snapshot now = a.snapshot();
        String playing = now == null ? "" : now.live().channel;
        List<String> recent = a.recentChannels();
        String id = !playing.isEmpty() ? playing : recent.isEmpty() ? "" : recent.get(0);
        List<PhoneGuide.Channel> channels = a.channels();
        PhoneGuide.Channel channel = channels.get(0);
        for (PhoneGuide.Channel candidate : channels) if (candidate.id.equals(id)) channel = candidate;
        PhoneGuide.Show show = a.livePage.onAir(channel);
        liveBadge.setText(L.t(playing.isEmpty() ? "LIVE TV" : "LIVE ON YOUR TV"));
        liveTitle.setText(show == null ? channel.name : show.title);
        liveDetail.setText(show == null ? L.t("All channels and the TV guide") : channel.name);
        if (show != null && show.end > show.start) {
            liveProgress.setVisibility(View.VISIBLE);
            liveProgress.setProgress((int) Math.max(0, Math.min(1000,
                    (System.currentTimeMillis() - show.start) * 1000 / (show.end - show.start))));
        } else liveProgress.setVisibility(View.GONE);
    }

    /** The first TV apps, with the full launcher one tap away. */
    private View shelf() {
        LinearLayout shelf = u.column();
        LinearLayout head = u.row();
        TextView title = u.text("Apps on your TV", 19, INK, 800);
        title.setAccessibilityHeading(true);
        u.pad(title, 2, 0, 0, 0);
        head.addView(title, u.weight(WRAP));
        TextView all = u.text("See all  ›", 14, LILAC, 760);
        u.pad(all, 12, 10, 4, 10);
        all.setOnClickListener(v -> a.go("apps"));
        all.setFocusable(true);
        all.setContentDescription("See all apps on your TV");
        if (editing) all.setVisibility(View.INVISIBLE);
        head.addView(all);
        shelf.addView(head);
        u.space(shelf, 7);
        List<TvApps.Entry> apps = a.apps.list();
        if (!a.connected() || apps.isEmpty()) {
            FrameLayout card = HomeTile.frame(a, u, 22, new int[] {CARD, CARD});
            String text = !a.connected() ? "Connect your TV to see its apps"
                    : a.apps.loading() ? "Asking your TV for its apps…"
                    : !a.apps.problem.isEmpty() ? a.apps.problem : "No apps reported yet";
            TextView message = u.text(text, 14, MUTED, 600);
            message.setGravity(Gravity.CENTER);
            u.pad(message, 20, 0, 20, 0);
            card.addView(message, new FrameLayout.LayoutParams(MATCH, MATCH));
            HomeTile.press(card, text, () -> a.go(a.connected() ? "apps" : "devices"));
            shelf.addView(card, u.lp(MATCH, 90));
            return shelf;
        }
        HorizontalScrollView strip = new HorizontalScrollView(a);
        strip.setHorizontalScrollBarEnabled(false);
        strip.setClipToPadding(false);
        LinearLayout line = u.row();
        for (int i = 0; i < Math.min(10, apps.size()); i++) {
            TvApps.Entry app = apps.get(i);
            LinearLayout cell = u.column();
            cell.addView(AppsPage.art(a, u, app, 16), u.lp(144, 81));
            u.space(cell, 7);
            TextView name = u.text(app.name, 12.5f, 0xffdcdeea, 640);
            name.setSingleLine();
            name.setEllipsize(android.text.TextUtils.TruncateAt.END);
            u.pad(name, 3, 0, 3, 3);
            cell.addView(name, u.lp(144, WRAP));
            HomeTile.press(cell, "Open " + app.name + " on your TV", () -> a.openApp(app));
            LinearLayout.LayoutParams place = u.lp(144, WRAP);
            if (i > 0) place.leftMargin = u.d(10);
            line.addView(cell, place);
        }
        strip.addView(line);
        shelf.addView(strip, u.lp(MATCH, WRAP));
        return shelf;
    }

    /** The tiles rise into place once, when the app is opened. */
    private void welcome() {
        if (welcomed || editing) return;
        welcomed = true;
        for (int i = 0; i < a.body.getChildCount(); i++) {
            View child = a.body.getChildAt(i);
            child.setAlpha(0);
            child.setTranslationY(u.d(22));
            child.animate().alpha(1).translationY(0).setStartDelay(i * 28L).setDuration(330)
                    .setInterpolator(new android.view.animation.DecelerateInterpolator(1.8f)).start();
        }
    }
}
