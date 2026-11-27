package site.navatv.companion;

import static site.navatv.companion.Surface.*;

import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.Intent;
import android.graphics.Typeface;
import android.net.Uri;
import android.os.Handler;
import android.os.Looper;
import android.text.InputType;
import android.text.TextUtils;
import android.view.Gravity;
import android.view.inputmethod.EditorInfo;
import android.view.inputmethod.InputMethodManager;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.TextView;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.regex.Pattern;

/**
 * NavaTube on the phone: the TV's own video lists and its Google account. Signing in or out here or
 * on the TV counts for both, because the account only exists on the TV.
 */
final class NavaTubePage {
    private static final Pattern VIDEO = Pattern.compile("[A-Za-z0-9_-]{11}");
    private static final long POLL = 3000;

    private static final class Video {
        final String id, title, author;
        Video(JSONObject row) {
            id = row.optString("id");
            title = row.optString("title").trim();
            author = row.optString("author").trim();
        }
    }

    private final MainActivity a;
    private final Surface u;
    private final Handler main = new Handler(Looper.getMainLooper());
    // Loading a feed can take a while; asking who is signed in must not wait behind it.
    private final ExecutorService feeds = Executors.newSingleThreadExecutor();
    private final ExecutorService account = Executors.newSingleThreadExecutor();
    private final Runnable poll = this::askStatus;

    private LinearLayout accountBox, results;
    private final List<Video> videos = new ArrayList<>();
    private String owner = "", query = "", next = "", feedProblem = "", accountProblem = "";
    private boolean known, signedIn, loading, loaded, asking, started;
    private String name = "", pairing = "idle", code = "";
    private long askedAt;
    private int generation;

    NavaTubePage(MainActivity activity) {
        a = activity;
        u = a.ui;
    }

    /** Shown on the start page tile. */
    String summary() {
        sameTv();
        if (a.connected() && !known) refreshAccount();
        if (!a.connected() || !known || !signedIn) return "Your videos";
        return name.isEmpty() ? "Signed in" : name;
    }

    private void sameTv() {
        String current = !a.connected() ? "" : a.device.androidId.isEmpty() ? a.device.host : a.device.androidId;
        if (current.equals(owner)) return;
        owner = current;
        generation++;
        videos.clear();
        known = signedIn = loading = loaded = asking = false;
        name = code = next = feedProblem = accountProblem = "";
        pairing = "idle";
        askedAt = 0;
    }

    private static boolean accountPage(String page) { return page.equals("navatube") || page.equals("account"); }

    private boolean showing() { return accountPage(a.page) && accountBox != null && accountBox.isAttachedToWindow(); }

    /** The same account, as its own page in the phone's settings. */
    void buildAccount() {
        sameTv();
        u.space(a.body, 4);
        TextView intro = u.text("One account for NavaTube on your TV and in this app. Signing in or out on one device "
                + "does the same on the other.", 14, MUTED, 500);
        intro.setLineSpacing(0, 1.15f);
        a.body.addView(intro);
        u.space(a.body, 16);
        results = null;
        if (!a.connected()) {
            accountBox = null;
            a.body.addView(u.text("Connect your TV to see or change the account.", 15, ACCENT, 620));
            u.space(a.body, 12);
            a.body.addView(u.button("Connect a TV", "cast", true, () -> a.go("devices")), u.lp(MATCH, 56));
            return;
        }
        accountBox = u.column();
        a.body.addView(accountBox, u.lp(MATCH, WRAP));
        u.space(a.body, 16);
        TextView privacy = u.text("Your password is only ever entered on Google’s own page. The account is kept on the TV.",
                12.5f, MUTED, 500);
        privacy.setLineSpacing(0, 1.15f);
        a.body.addView(privacy);
        drawAccount();
        started = true;
        askStatus();
    }

    /** Shown under the settings entry. */
    String accountSummary() {
        sameTv();
        if (!a.connected()) return "Connect your TV first";
        refreshAccount();
        if (!known) return "Asking your TV…";
        if (!signedIn) return "Not signed in";
        return name.isEmpty() ? "Signed in" : "Signed in as " + name;
    }

    void build() {
        sameTv();
        u.space(a.body, 4);
        if (!a.connected()) {
            accountBox = results = null;
            a.body.addView(u.text("NavaTube runs with your TV. Connect it to see your videos here.", 15, MUTED, 520));
            u.space(a.body, 14);
            a.body.addView(u.button("Connect a TV", "cast", true, () -> a.go("devices")), u.lp(MATCH, 56));
            return;
        }
        accountBox = u.column();
        a.body.addView(accountBox, u.lp(MATCH, WRAP));
        u.space(a.body, 14);
        search();
        u.space(a.body, 18);
        results = u.column();
        a.body.addView(results, u.lp(MATCH, WRAP));
        drawAccount();
        drawResults();
        started = true;
        askStatus();
        if (!loaded && !loading) load(false);
    }

    void stop() {
        started = false;
        main.removeCallbacks(poll);
    }

    private void refreshAccount() {
        long time = android.os.SystemClock.elapsedRealtime();
        if (askedAt != 0 && time - askedAt < 30_000) return;
        askStatus();
    }

    private void askStatus() {
        main.removeCallbacks(poll);
        sameTv();
        if (!a.connected() || asking) return;
        asking = true;
        askedAt = android.os.SystemClock.elapsedRealtime();
        TvConnection connection = a.tv;
        int forTv = generation;
        account.execute(() -> {
            JSONObject answer = null;
            String failure = "";
            try { answer = connection.navaTube("status", ""); }
            catch (Exception unavailable) { failure = a.error(unavailable); }
            JSONObject result = answer;
            String message = failure;
            main.post(() -> {
                if (forTv != generation) return;
                asking = false;
                apply(result, message);
                if (started && accountPage(a.page)) main.postDelayed(poll, POLL);
            });
        });
    }

    private void apply(JSONObject status, String failure) {
        if (status == null) {
            if (!failure.equals(accountProblem)) {
                accountProblem = failure;
                if (showing()) drawAccount();
            }
            return;
        }
        boolean now = status.optBoolean("signedIn");
        String who = status.optString("name"), step = status.optString("pairing", "idle"), digits = status.optString("code");
        boolean first = !known;
        boolean accountChanged = known && (now != signedIn || !who.equals(name));
        if (!first && !accountChanged && step.equals(pairing) && digits.equals(code) && accountProblem.isEmpty()) return;
        known = true;
        signedIn = now;
        name = who;
        pairing = step;
        code = digits;
        accountProblem = "";
        if (showing()) drawAccount();
        else if (a.page.equals("home") || a.page.equals("settings")) a.render();
        // The TV or this phone signed in or out: show the matching videos.
        if (accountChanged) {
            query = "";
            if (a.page.equals("navatube")) a.render();
            load(false);
        }
    }

    private void command(String method) {
        TvConnection connection = a.tv;
        account.execute(() -> {
            String failure = "";
            try { connection.navaTube(method, ""); }
            catch (Exception unavailable) { failure = a.error(unavailable); }
            String message = failure;
            main.post(() -> {
                if (!message.isEmpty()) a.problem(message);
                askStatus();
            });
        });
    }

    // ---- account ------------------------------------------------------------------------------

    private LinearLayout card(int top, int bottom) {
        LinearLayout card = u.column();
        android.graphics.drawable.GradientDrawable ground = new android.graphics.drawable.GradientDrawable(
                android.graphics.drawable.GradientDrawable.Orientation.TL_BR, new int[] {top, bottom});
        ground.setCornerRadius(u.d(24));
        card.setBackground(ground);
        u.pad(card, 18, 16, 18, 18);
        accountBox.addView(card, u.lp(MATCH, WRAP));
        return card;
    }

    private void heading(LinearLayout card, String title, String detail) {
        card.addView(u.text(title, 19, WHITE, 800));
        u.space(card, 6);
        TextView line = u.text(detail, 13.5f, 0xffd8d3f0, 540);
        line.setLineSpacing(0, 1.15f);
        card.addView(line);
    }

    private void drawAccount() {
        if (accountBox == null) return;
        accountBox.removeAllViews();
        if (!accountProblem.isEmpty()) {
            LinearLayout card = card(0xff3a2a24, 0xff2a1f1c);
            heading(card, "NavaTube is not available", accountProblem);
            return;
        }
        if (!known) {
            LinearLayout card = card(CARD, CARD);
            card.addView(u.text("Asking your TV…", 14, MUTED, 600));
            return;
        }
        if (signedIn) {
            LinearLayout row = u.row();
            row.setBackground(u.shape(CARD, 24, 0));
            u.pad(row, 12, 11, 10, 11);
            String shown = name.isEmpty() ? "YouTube account" : name;
            TextView initial = u.text(shown.substring(0, shown.offsetByCodePoints(0, 1)).toUpperCase(java.util.Locale.ROOT), 19, ON_LILAC, 800);
            initial.setGravity(Gravity.CENTER);
            initial.setBackground(u.shape(LILAC, 22, 0));
            row.addView(initial, u.lp(44, 44));
            u.gap(row, 12);
            LinearLayout words = u.column();
            TextView who = u.text(shown, 16, INK, 760);
            who.setSingleLine();
            who.setEllipsize(TextUtils.TruncateAt.END);
            words.addView(who);
            u.space(words, 4);
            words.addView(u.text("Signed in on your TV and phone", 12.5f, 0xff7fe3b0, 620));
            row.addView(words, u.weight(WRAP));
            TextView out = u.text("Sign out", 13, MUTED, 740);
            u.pad(out, 12, 12, 10, 12);
            out.setFocusable(true);
            out.setOnClickListener(v -> a.homeDialog().setTitle("Sign out of NavaTube?")
                    .setMessage("This signs you out on your TV as well.")
                    .setNegativeButton("Cancel", null)
                    .setPositiveButton("Sign out", (d, w) -> command("sign_out")).show());
            row.addView(out);
            accountBox.addView(row, u.lp(MATCH, WRAP));
            return;
        }
        LinearLayout card = card(0xff4a3a92, 0xff231b4a);
        if (pairing.equals("waiting") && code.matches("[A-Za-z0-9 -]{4,20}")) {
            heading(card, "Confirm with Google", "Open Google on this phone and confirm this code. Your TV signs in with it.");
            u.space(card, 14);
            TextView digits = u.text(code, 30, 0xff231b4a, 800);
            digits.setTypeface(Typeface.MONOSPACE, Typeface.BOLD);
            digits.setGravity(Gravity.CENTER);
            digits.setTextIsSelectable(true);
            digits.setBackground(u.shape(0xffece6ff, 18, 0));
            u.pad(digits, 8, 15, 8, 15);
            card.addView(digits, u.lp(MATCH, WRAP));
            u.space(card, 12);
            card.addView(u.button("Copy code and open Google", "forward", true, this::openGoogle), u.lp(MATCH, 54));
            u.space(card, 8);
            card.addView(u.button("Cancel", null, false, () -> command("cancel")), u.lp(MATCH, 48));
            u.space(card, 10);
            TextView hint = u.text("Or visit yt.be/activate on any device. This page updates by itself.", 12, 0xffbdb4e6, 540);
            card.addView(hint);
        } else if (pairing.equals("starting") || pairing.equals("waiting")) {
            heading(card, "Getting your code…", "Your TV is asking Google for a sign-in code.");
        } else {
            boolean failed = pairing.equals("failed");
            heading(card, failed ? "That didn’t work" : "Make NavaTube yours",
                    failed ? "The code expired or the connection to Google was interrupted."
                            : "Sign in with Google to see your recommendations. You are then signed in on your TV too.");
            u.space(card, 14);
            card.addView(u.button(failed ? "Try again" : "Sign in with Google", "forward", true, () -> {
                pairing = "starting";
                drawAccount();
                command("sign_in");
            }), u.lp(MATCH, 54));
        }
    }

    /** Google's own page for TV codes; only the short code that is on screen goes into the address. */
    private void openGoogle() {
        String shown = code;
        if (!shown.matches("[A-Za-z0-9 -]{4,20}")) return;
        ClipboardManager clipboard = (ClipboardManager) a.getSystemService(Context.CLIPBOARD_SERVICE);
        if (clipboard != null) clipboard.setPrimaryClip(ClipData.newPlainText("NavaTube code", shown));
        Uri link = new Uri.Builder().scheme("https").authority("youtube.com").appendPath("qr")
                .appendPath("activate").appendPath(shown.replace(' ', '-')).build();
        try { a.startActivity(new Intent(Intent.ACTION_VIEW, link)); }
        catch (android.content.ActivityNotFoundException noBrowser) {
            a.problem("No browser found. Visit yt.be/activate on another device and enter " + shown + ".");
        }
    }

    // ---- videos -------------------------------------------------------------------------------

    private void search() {
        LinearLayout bar = u.row();
        bar.setBackground(u.shape(CARD, 27, 0));
        u.pad(bar, 20, 0, 6, 0);
        EditText field = a.field("Search NavaTube", InputType.TYPE_CLASS_TEXT, query);
        field.setSelectAllOnFocus(false);
        field.setBackground(null);
        field.setImeOptions(EditorInfo.IME_ACTION_SEARCH);
        field.setOnEditorActionListener((v, action, event) -> {
            if (action != EditorInfo.IME_ACTION_SEARCH) return false;
            submit(field);
            return true;
        });
        bar.addView(field, u.weight(54));
        FrameLayout go = new FrameLayout(a);
        go.addView(new Art(a, query.isEmpty() ? "right" : "close", ON_LILAC), new FrameLayout.LayoutParams(u.d(18), u.d(18), Gravity.CENTER));
        u.action(go, LILAC, 21, 0, () -> {
            if (!query.isEmpty() && field.getText().toString().trim().equals(query)) field.setText("");
            submit(field);
        });
        go.setContentDescription(query.isEmpty() ? "Search" : "Clear search");
        bar.addView(go, u.lp(42, 42));
        a.body.addView(bar, u.lp(MATCH, WRAP));
    }

    private void submit(EditText field) {
        InputMethodManager keyboard = a.getSystemService(InputMethodManager.class);
        if (keyboard != null) keyboard.hideSoftInputFromWindow(field.getWindowToken(), 0);
        field.clearFocus();
        String wanted = field.getText().toString().trim();
        if (wanted.length() > 200) wanted = wanted.substring(0, 200);
        boolean same = wanted.equals(query) && loaded;
        query = wanted;
        if (!same) {
            loading = false;
            a.render();
            load(false);
        }
    }

    private void load(boolean more) {
        sameTv();
        if (!a.connected() || (more && (loading || next.isEmpty()))) return;
        if (!more) {
            videos.clear();
            next = "";
            loaded = false;
        }
        loading = true;
        feedProblem = "";
        int forTv = generation;
        String wanted = query, cursor = next;
        int request = ++requests;
        if (showing()) drawResults();
        TvConnection connection = a.tv;
        feeds.execute(() -> {
            JSONObject answer = null;
            String failure = "";
            try { answer = connection.navaTube(more ? "more" : "feed", more ? cursor : wanted); }
            catch (Exception unavailable) { failure = a.error(unavailable); }
            JSONObject result = answer;
            String message = failure;
            main.post(() -> {
                if (forTv != generation || request != requests) return;
                loading = false;
                loaded = true;
                if (result == null) feedProblem = message;
                else if (result.has("error")) feedProblem = "Your TV could not load these videos. Please try again.";
                else {
                    next = result.optString("next");
                    JSONArray rows = result.optJSONArray("items");
                    java.util.Set<String> seen = new java.util.HashSet<>();
                    for (Video video : videos) seen.add(video.id);
                    for (int i = 0; rows != null && i < rows.length(); i++) {
                        JSONObject row = rows.optJSONObject(i);
                        if (row == null) continue;
                        Video video = new Video(row);
                        if (VIDEO.matcher(video.id).matches() && !video.title.isEmpty() && seen.add(video.id)) videos.add(video);
                    }
                }
                if (showing()) drawResults();
            });
        });
    }

    private int requests;

    private void drawResults() {
        if (results == null) return;
        results.removeAllViews();
        String title = !query.isEmpty() ? "Results for “" + query + "”" : signedIn ? "For you" : "Videos";
        TextView label = u.text(title, 19, INK, 800);
        label.setSingleLine();
        label.setEllipsize(TextUtils.TruncateAt.END);
        label.setAccessibilityHeading(true);
        u.pad(label, 2, 0, 0, 0);
        results.addView(label);
        u.space(results, 12);
        for (Video video : videos) video(video);
        if (loading) {
            ProgressBar spinner = new ProgressBar(a);
            spinner.setIndeterminateTintList(android.content.res.ColorStateList.valueOf(LILAC));
            results.addView(spinner, u.lp(MATCH, 48));
            return;
        }
        if (!feedProblem.isEmpty()) {
            results.addView(u.text(feedProblem, 14, ACCENT, 620));
            u.space(results, 10);
            results.addView(u.button("Try again", null, false, () -> load(!videos.isEmpty() && !next.isEmpty())), u.lp(MATCH, 50));
            return;
        }
        if (videos.isEmpty()) {
            results.addView(u.text(!query.isEmpty() ? "Nothing found. Try other words."
                    : signedIn ? "No recommendations yet."
                    : "Search for any video above, or sign in to see your recommendations.", 14.5f, MUTED, 540));
        } else if (!next.isEmpty()) {
            results.addView(u.button("Load more", "plus", false, () -> load(true)), u.lp(MATCH, 52));
            u.space(results, 10);
        }
        u.space(results, 6);
        results.addView(u.button("Show NavaTube on TV", "tv", false, a::openNavaTube), u.lp(MATCH, 52));
    }

    private void video(Video video) {
        LinearLayout cell = u.column();
        int width = Math.min(a.getResources().getDisplayMetrics().widthPixels, u.d(550)) - 2 * u.d(20);
        FrameLayout picture = HomeTile.frame(a, u, 20, new int[] {PALE, CARD});
        ImageView thumbnail = new ImageView(a);
        // YouTube's 4:3 stills carry black bars; cropping to 16:9 removes them.
        thumbnail.setScaleType(ImageView.ScaleType.CENTER_CROP);
        picture.addView(thumbnail, new FrameLayout.LayoutParams(MATCH, MATCH));
        a.images.into(thumbnail, "https://i.ytimg.com/vi/" + video.id + "/hqdefault.jpg");
        FrameLayout play = new FrameLayout(a);
        play.setBackground(u.shape(0xd9141722, 22, 0));
        TextView glyph = u.text("▶", 15, WHITE, 800);
        glyph.setGravity(Gravity.CENTER);
        play.addView(glyph, new FrameLayout.LayoutParams(MATCH, MATCH));
        FrameLayout.LayoutParams place = new FrameLayout.LayoutParams(u.d(44), u.d(44), Gravity.BOTTOM | Gravity.END);
        place.setMargins(0, 0, u.d(11), u.d(11));
        picture.addView(play, place);
        cell.addView(picture, new LinearLayout.LayoutParams(MATCH, width * 9 / 16));
        u.space(cell, 10);
        TextView title = u.text(video.title, 15.5f, INK, 720);
        title.setMaxLines(2);
        title.setEllipsize(TextUtils.TruncateAt.END);
        title.setLineSpacing(0, 1.12f);
        u.pad(title, 3, 0, 3, 0);
        cell.addView(title);
        if (!video.author.isEmpty()) {
            u.space(cell, 5);
            TextView author = u.text(video.author, 13, MUTED, 560);
            author.setSingleLine();
            u.pad(author, 3, 0, 3, 0);
            cell.addView(author);
        }
        HomeTile.press(cell, "Play " + video.title + " on your TV", () -> {
            TvConnection connection = a.tv;
            a.runTv(() -> { connection.playNavaTube(video.id, video.title, video.author); return null; },
                    done -> a.say("Playing on your TV."));
        });
        results.addView(cell, u.lp(MATCH, WRAP));
        u.space(results, 20);
    }

    void close() {
        stop();
        feeds.shutdownNow();
        account.shutdownNow();
    }
}
