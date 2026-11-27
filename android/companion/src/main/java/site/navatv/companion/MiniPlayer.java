package site.navatv.companion;

import static site.navatv.companion.Surface.*;

import android.content.res.ColorStateList;
import android.graphics.Outline;
import android.graphics.drawable.GradientDrawable;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.text.TextUtils;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.ViewOutlineProvider;
import android.view.animation.DecelerateInterpolator;
import android.view.animation.OvershootInterpolator;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.SeekBar;
import android.widget.TextView;

import java.util.Locale;
import java.util.Objects;

/**
 * The floating player at the bottom of the phone. It is there on every page while the TV plays
 * something. A tap or a swipe up opens it into a large card.
 */
final class MiniPlayer {
    /** Everything the player shows. */
    static final class State {
        /** Identifies what plays, so that a new item replaces the artwork. */
        String key = "";
        String title = "", detail = "";
        /** An https picture, a bundled asset, or a TV app whose banner to show. */
        String picture = "", asset = "";
        TvApps.Entry app;
        boolean playing, live;
        /** False when the TV does not say whether it is paused. */
        boolean known = true;
        long position, duration, measuredAt;
        Runnable toggle, back, forward, stop, open;
    }

    private final MainActivity a;
    private final Surface u;
    private final Handler main = new Handler(Looper.getMainLooper());
    private final FrameLayout card;
    private final LinearLayout small, large;

    private final FrameLayout smallArt, largeArt;
    private final ImageView smallPicture, largePicture;
    private final Equalizer smallBars, largeBars;
    private final TextView smallTitle, smallDetail, largeTitle, largeDetail, elapsed, total, openLink;
    private final ProgressBar line;
    private final SeekBar bar;
    private final FrameLayout smallToggle, largeToggle, smallClose, largeBack, largeForward, largeStop;
    private final LinearLayout times;

    private State state;
    private boolean expanded, visible;
    private float downY;
    /** The viewer's tap shows at once; the TV confirms it a moment later. */
    private Boolean expectedPlaying;
    private long expectedUntil;
    /** Told when the player appears, disappears or changes size, so the page can make room. */
    Runnable layoutChanged = () -> {};
    Runnable expandedChanged = () -> {};

    private final Runnable second = new Runnable() {
        @Override public void run() {
            if (!visible) return;
            progress();
            main.postDelayed(this, 500);
        }
    };

    MiniPlayer(MainActivity activity) {
        a = activity;
        u = a.ui;
        card = new FrameLayout(a);
        GradientDrawable ground = new GradientDrawable(GradientDrawable.Orientation.TL_BR,
                new int[] {0xfa3a3064, 0xfa221e3c, 0xfa161824});
        ground.setCornerRadius(u.d(28));
        ground.setStroke(u.d(1), 0x40d9ccff);
        card.setBackground(ground);
        card.setElevation(u.d(18));
        card.setOutlineProvider(new ViewOutlineProvider() {
            @Override public void getOutline(View view, Outline outline) {
                outline.setRoundRect(0, 0, view.getWidth(), view.getHeight(), u.d(28));
            }
        });
        card.setClipToOutline(true);
        card.setOutlineAmbientShadowColor(0xff6f52e0);
        card.setOutlineSpotShadowColor(0xff6f52e0);
        card.setClickable(true);
        card.setVisibility(View.GONE);   // appears only while the TV plays something

        // ---- the slim player ------------------------------------------------------------------
        small = u.column();
        LinearLayout row = u.row();
        u.pad(row, 10, 10, 8, 9);
        smallArt = art(18);
        smallPicture = (ImageView) smallArt.getChildAt(0);
        smallBars = bars(smallArt, 22, 18, 7);
        row.addView(smallArt, u.lp(62, 62));
        u.gap(row, 13);
        LinearLayout words = u.column();
        smallTitle = single(u.text("", 15, WHITE, 780));
        words.addView(smallTitle);
        u.space(words, 6);
        smallDetail = single(u.text("", 12.5f, 0xffc9c0f0, 580));
        words.addView(smallDetail);
        row.addView(words, u.weight(WRAP));
        u.gap(row, 6);
        smallToggle = round("pause", "Pause on TV", 48, LILAC, ON_LILAC, 20, v -> toggle());
        row.addView(smallToggle, u.lp(48, 48));
        smallClose = round("close", "Stop on TV", 40, 0x1fffffff, WHITE, 15, v -> run(state == null ? null : state.stop));
        LinearLayout.LayoutParams closePlace = u.lp(40, 40);
        closePlace.leftMargin = u.d(10);
        row.addView(smallClose, closePlace);
        small.addView(row);
        line = new ProgressBar(a, null, android.R.attr.progressBarStyleHorizontal);
        line.setMax(1000);
        line.setProgressTintList(ColorStateList.valueOf(LILAC));
        line.setProgressBackgroundTintList(ColorStateList.valueOf(0x33ffffff));
        LinearLayout.LayoutParams linePlace = u.lp(MATCH, 3);
        linePlace.setMargins(u.d(18), 0, u.d(18), u.d(6));
        small.addView(line, linePlace);
        card.addView(small, new FrameLayout.LayoutParams(MATCH, WRAP));

        // ---- the large player -----------------------------------------------------------------
        large = u.column();
        u.pad(large, 16, 8, 16, 16);
        View handle = new View(a);
        handle.setBackground(u.shape(0x66ffffff, 3, 0));
        LinearLayout.LayoutParams grip = u.lp(42, 5);
        grip.gravity = Gravity.CENTER_HORIZONTAL;
        large.addView(handle, grip);
        u.space(large, 12);
        largeArt = art(22);
        largePicture = (ImageView) largeArt.getChildAt(0);
        largeBars = bars(largeArt, 26, 22, 14);
        int width = Math.min(a.getResources().getDisplayMetrics().widthPixels, u.d(560)) - u.d(24) - u.d(32);
        large.addView(largeArt, new LinearLayout.LayoutParams(MATCH, Math.min(width * 9 / 16, u.d(240))));
        u.space(large, 14);
        largeTitle = u.text("", 19, WHITE, 800);
        largeTitle.setMaxLines(2);
        largeTitle.setEllipsize(TextUtils.TruncateAt.END);
        large.addView(largeTitle);
        u.space(large, 7);
        largeDetail = single(u.text("", 13, 0xffc9c0f0, 580));
        large.addView(largeDetail);
        u.space(large, 10);
        bar = new SeekBar(a);
        bar.setMax(1000);
        bar.setProgressTintList(ColorStateList.valueOf(LILAC));
        bar.setProgressBackgroundTintList(ColorStateList.valueOf(0x40ffffff));
        bar.setThumbTintList(ColorStateList.valueOf(WHITE));
        // The TV only skips in steps, so the bar shows the position and does not take drags.
        bar.setOnTouchListener((v, event) -> true);
        bar.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
        large.addView(bar, u.lp(MATCH, 26));
        times = u.row();
        u.pad(times, 14, 0, 14, 0);
        elapsed = u.text("", 12, MUTED, 640);
        times.addView(elapsed, u.weight(WRAP));
        total = u.text("", 12, MUTED, 640);
        times.addView(total);
        large.addView(times);
        u.space(large, 10);
        LinearLayout controls = u.row();
        controls.setGravity(Gravity.CENTER);
        largeStop = round("close", "Stop on TV", 48, 0x1fffffff, WHITE, 17, v -> run(state == null ? null : state.stop));
        largeBack = round("back10", "Skip back on TV", 54, 0x1fffffff, WHITE, 29, v -> run(state == null ? null : state.back));
        largeToggle = round("pause", "Pause on TV", 72, LILAC, ON_LILAC, 29, v -> toggle());
        largeForward = round("skip10", "Skip forward on TV", 54, 0x1fffffff, WHITE, 29, v -> run(state == null ? null : state.forward));
        FrameLayout collapse = round("down", "Make the player small", 48, 0x1fffffff, WHITE, 19, v -> expand(false));
        int[] sizes = {48, 54, 72, 54, 48};
        View[] buttons = {largeStop, largeBack, largeToggle, largeForward, collapse};
        for (int i = 0; i < buttons.length; i++) {
            LinearLayout.LayoutParams place = u.lp(sizes[i], sizes[i]);
            if (i > 0) place.leftMargin = u.d(11);
            controls.addView(buttons[i], place);
        }
        large.addView(controls, u.lp(MATCH, WRAP));
        openLink = u.text("", 13.5f, LILAC, 760);
        openLink.setGravity(Gravity.CENTER);
        u.pad(openLink, 8, 15, 8, 2);
        openLink.setOnClickListener(v -> {
            Runnable open = state == null ? null : state.open;
            expand(false);
            run(open);
        });
        large.addView(openLink, u.lp(MATCH, WRAP));
        large.setVisibility(View.GONE);
        card.addView(large, new FrameLayout.LayoutParams(MATCH, WRAP));

        View.OnTouchListener swipe = (v, event) -> {
            if (event.getActionMasked() == MotionEvent.ACTION_DOWN) downY = event.getRawY();
            if (event.getActionMasked() == MotionEvent.ACTION_UP) {
                float moved = event.getRawY() - downY;
                if (moved < -u.d(22)) expand(true);
                else if (moved > u.d(22)) expand(false);
                else v.performClick();
            }
            return true;
        };
        small.setOnTouchListener(swipe);
        small.setOnClickListener(v -> expand(true));
        small.setFocusable(true);
        large.setOnTouchListener(swipe);
    }

    // ---- building blocks ----------------------------------------------------------------------

    private FrameLayout art(int radius) {
        FrameLayout frame = HomeTile.frame(a, u, radius, new int[] {0xff6f5bc4, 0xff2a2352});
        ImageView picture = new ImageView(a);
        picture.setScaleType(ImageView.ScaleType.CENTER_CROP);
        frame.addView(picture, new FrameLayout.LayoutParams(MATCH, MATCH));
        return frame;
    }

    private Equalizer bars(FrameLayout over, int width, int height, int margin) {
        Equalizer equalizer = new Equalizer(a);
        FrameLayout badge = new FrameLayout(a);
        badge.setBackground(u.shape(0xb30f1018, 9, 0));
        badge.addView(equalizer, new FrameLayout.LayoutParams(u.d(width - 10), u.d(height - 8), Gravity.CENTER));
        FrameLayout.LayoutParams place = new FrameLayout.LayoutParams(u.d(width), u.d(height), Gravity.BOTTOM | Gravity.START);
        place.setMargins(u.d(margin / 2 + 2), 0, 0, u.d(margin / 2 + 2));
        over.addView(badge, place);
        return equalizer;
    }

    private static TextView single(TextView view) {
        view.setSingleLine();
        view.setEllipsize(TextUtils.TruncateAt.END);
        return view;
    }

    private FrameLayout round(String icon, String label, int size, int ground, int ink, int glyph, View.OnClickListener click) {
        FrameLayout button = new FrameLayout(a);
        button.addView(new Art(a, icon, ink), new FrameLayout.LayoutParams(u.d(glyph), u.d(glyph), Gravity.CENTER));
        u.action(button, ground, size / 2, 0, () -> click.onClick(button));
        button.setContentDescription(label);
        button.setTag(new Object[] {icon, ink, glyph});
        return button;
    }

    private void icon(FrameLayout button, String name, String label) {
        Object[] now = (Object[]) button.getTag();
        button.setContentDescription(label);
        if (name.equals(now[0])) return;
        int ink = (Integer) now[1], glyph = (Integer) now[2];
        button.removeAllViews();
        button.addView(new Art(a, name, ink), new FrameLayout.LayoutParams(u.d(glyph), u.d(glyph), Gravity.CENTER));
        button.setTag(new Object[] {name, ink, glyph});
    }

    private static void run(Runnable action) { if (action != null) action.run(); }

    private void toggle() {
        State current = state;
        if (current == null) return;
        if (current.known) {
            boolean now = playingNow();
            expectedPlaying = !now;
            expectedUntil = SystemClock.elapsedRealtime() + 3500;
            if (now) {
                current.position = positionNow();
                current.measuredAt = SystemClock.elapsedRealtime();
            }
            current.playing = !now;
            paint();
        }
        run(current.toggle);
    }

    private boolean playingNow() {
        if (expectedPlaying != null && SystemClock.elapsedRealtime() < expectedUntil) return expectedPlaying;
        return state != null && state.playing;
    }

    private long positionNow() {
        if (state == null) return 0;
        long position = state.position;
        if (playingNow() && state.measuredAt > 0) position += SystemClock.elapsedRealtime() - state.measuredAt;
        return state.duration > 0 ? Math.min(position, state.duration) : position;
    }

    // ---- showing ------------------------------------------------------------------------------

    /** The page is rebuilt on navigation; the player keeps its views and moves into the new page. */
    void attach(ViewGroup slot) {
        if (card.getParent() != null) ((ViewGroup) card.getParent()).removeView(card);
        slot.addView(card, new FrameLayout.LayoutParams(MATCH, WRAP, Gravity.BOTTOM));
    }

    boolean visible() { return visible; }
    boolean expanded() { return expanded; }
    /** How much of the page the slim player covers. */
    int reserved() { return visible ? u.d(98) : 0; }

    void show(State next) {
        if (next == null) {
            if (!visible) return;
            visible = false;
            state = null;
            expectedPlaying = null;
            main.removeCallbacks(second);
            expand(false);
            card.animate().cancel();
            card.animate().translationY(u.d(150)).alpha(0).setDuration(240)
                    .withEndAction(() -> { if (!visible) card.setVisibility(View.GONE); }).start();
            layoutChanged.run();
            return;
        }
        boolean newItem = state == null || !state.key.equals(next.key);
        boolean newPicture = newItem || !state.picture.equals(next.picture) || !state.asset.equals(next.asset)
                || !Objects.equals(state.app == null ? null : state.app.id, next.app == null ? null : next.app.id);
        if (newItem) expectedPlaying = null;
        if (expectedPlaying != null && next.playing == expectedPlaying) expectedPlaying = null;
        state = next;
        if (newPicture) {
            shapeArt(next);
            picture(smallPicture, next);
            picture(largePicture, next);
        }
        paint();
        if (!visible) {
            visible = true;
            card.setVisibility(View.VISIBLE);
            card.animate().cancel();
            card.setTranslationY(u.d(150));
            card.setAlpha(0);
            card.animate().translationY(0).alpha(1).setDuration(460).setInterpolator(new OvershootInterpolator(.9f))
                    .withEndAction(null).start();
            main.post(second);
            layoutChanged.run();
        } else if (newItem) {
            smallTitle.setAlpha(0);
            smallTitle.setTranslationY(u.d(9));
            smallTitle.animate().alpha(1).translationY(0).setDuration(300).start();
        }
    }

    /** TV banners are wide: the slim player gives them their shape, and plain icons are never cut. */
    private void shapeArt(State next) {
        boolean banner = next.app != null && next.app.banner;
        android.view.ViewGroup.LayoutParams place = smallArt.getLayoutParams();
        place.width = u.d(banner ? 98 : 62);
        place.height = u.d(banner ? 56 : 62);
        smallArt.setLayoutParams(place);
        largePicture.setScaleType(next.app != null && !banner
                ? ImageView.ScaleType.CENTER_INSIDE : ImageView.ScaleType.CENTER_CROP);
    }

    private void picture(ImageView view, State next) {
        view.setImageDrawable(null);
        view.setTag(null);
        if (next.app != null) a.apps.picture(next.app, view);
        else if (!next.picture.isEmpty()) a.images.into(view, next.picture);
        else if (!next.asset.isEmpty()) {
            try (java.io.InputStream in = a.getAssets().open(next.asset)) {
                android.graphics.BitmapFactory.Options options = new android.graphics.BitmapFactory.Options();
                options.inSampleSize = 2;
                view.setImageBitmap(android.graphics.BitmapFactory.decodeStream(in, null, options));
            } catch (java.io.IOException missing) { /* the tile keeps its colour */ }
        }
    }

    private void paint() {
        if (state == null) return;
        boolean playing = playingNow();
        smallTitle.setText(L.t(state.title));
        largeTitle.setText(L.t(state.title));
        String detail = (state.known && !playing ? L.t("Paused  ·  ") : "") + L.t(state.detail);
        smallDetail.setText(detail);
        largeDetail.setText(detail);
        String name = !state.known ? "playpause" : playing ? "pause" : "resume";
        String label = !state.known ? "Play or pause on TV" : playing ? "Pause on TV" : "Resume on TV";
        icon(smallToggle, name, label);
        icon(largeToggle, name, label);
        smallBars.moving(playing);
        largeBars.moving(playing);
        // Without a playback report there is nothing the equalizer could honestly show.
        ((View) smallBars.getParent()).setVisibility(state.known ? View.VISIBLE : View.GONE);
        ((View) largeBars.getParent()).setVisibility(state.known ? View.VISIBLE : View.GONE);
        smallClose.setVisibility(state.stop == null ? View.GONE : View.VISIBLE);
        largeForward.setVisibility(state.forward == null ? View.INVISIBLE : View.VISIBLE);
        largeBack.setVisibility(state.back == null ? View.INVISIBLE : View.VISIBLE);
        largeStop.setVisibility(state.stop == null ? View.INVISIBLE : View.VISIBLE);
        openLink.setVisibility(state.open == null ? View.GONE : View.VISIBLE);
        openLink.setText(L.t(state.live ? "Open live TV  ›" : "Open NavaTube  ›"));
        small.setContentDescription("Now playing on TV: " + state.title + ". Open the player");
        progress();
    }

    private void progress() {
        if (state == null) return;
        boolean timed = state.duration > 0;
        long position = positionNow();
        int fraction = timed ? (int) Math.max(0, Math.min(1000, position * 1000 / state.duration)) : state.live ? 1000 : 0;
        line.setVisibility(timed || state.live ? View.VISIBLE : View.INVISIBLE);
        line.setProgress(fraction);
        line.setProgressTintList(ColorStateList.valueOf(state.live && !timed ? RED : LILAC));
        bar.setVisibility(timed ? View.VISIBLE : View.GONE);
        times.setVisibility(timed || state.live ? View.VISIBLE : View.GONE);
        bar.setProgress(fraction);
        // Programmes from the guide have start and end times; videos have a running clock.
        elapsed.setText(state.live && !timed ? "●  LIVE" : timed ? clock(position) : "");
        elapsed.setTextColor(state.live && !timed ? 0xffff9a9a : MUTED);
        total.setText(timed ? (state.live ? "−" + clock(state.duration - position) : clock(state.duration)) : "");
    }

    private static String clock(long millis) {
        long seconds = Math.max(0, millis / 1000);
        return seconds >= 3600
                ? String.format(Locale.ROOT, "%d:%02d:%02d", seconds / 3600, seconds / 60 % 60, seconds % 60)
                : String.format(Locale.ROOT, "%d:%02d", seconds / 60, seconds % 60);
    }

    void expand(boolean open) {
        if (open == expanded || (open && !visible)) return;
        expanded = open;
        View from = open ? small : large, to = open ? large : small;
        from.setVisibility(View.GONE);
        to.setVisibility(View.VISIBLE);
        to.setAlpha(0);
        to.animate().alpha(1).setDuration(240).start();
        card.setTranslationY(u.d(open ? 70 : -14));
        card.animate().translationY(0).setDuration(300).setInterpolator(new DecelerateInterpolator(2f)).start();
        expandedChanged.run();
    }

    void close() { main.removeCallbacks(second); }
}
