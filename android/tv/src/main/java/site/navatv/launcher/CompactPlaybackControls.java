package site.navatv.launcher;

import android.content.Context;
import android.content.res.ColorStateList;
import android.text.TextUtils;
import android.text.format.DateFormat;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.View;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.SeekBar;
import android.widget.TextView;

import java.util.Date;
import java.util.Locale;
import java.util.function.LongConsumer;

/** On-demand transport over the same edge gradients and divided action strip as Live TV. */
public final class CompactPlaybackControls extends FrameLayout {
    private final PlayerChrome chrome;
    private final PlayerChrome.ActionButton play, back, next;
    private final TextView title, author, elapsed, end;
    private final SeekBar positionBar;
    private final Runnable hide = () -> setVisibility(GONE);
    private final java.text.DateFormat clock;
    private long duration, clockMinute = -1;
    private boolean mayHide, dragging;

    public CompactPlaybackControls(Context context, String source, String initialTitle, String initialAuthor,
                                  Runnable toggle, LongConsumer skip, LongConsumer seekTo) {
        super(context);
        chrome = new PlayerChrome(context, this, source);
        clock = DateFormat.getTimeFormat(context);
        LinearLayout details = new LinearLayout(context);
        details.setOrientation(LinearLayout.VERTICAL);
        addView(details, chrome.at(-1, -2, Gravity.TOP | Gravity.START, 52, 48, 100, 0));
        title = chrome.label(25, 760, PlayerChrome.PEARL);
        title.setSingleLine(); title.setEllipsize(TextUtils.TruncateAt.END);
        details.addView(title);
        author = chrome.label(14, 620, PlayerChrome.MUTED);
        author.setSingleLine(); author.setEllipsize(TextUtils.TruncateAt.END);
        details.addView(author);
        setMetadata(source, initialTitle, initialAuthor);

        LinearLayout timeline = new LinearLayout(context);
        timeline.setGravity(Gravity.CENTER_VERTICAL);
        addView(timeline, chrome.at(-1, -2, Gravity.BOTTOM, 54, 0, 54, 73));
        elapsed = chrome.label(13, 650, PlayerChrome.PEARL);
        end = chrome.label(13, 650, PlayerChrome.PEARL);
        timeline.addView(elapsed);
        positionBar = new SeekBar(context);
        positionBar.setContentDescription("Wiedergabeposition");
        positionBar.setFocusable(false);
        positionBar.setMax(1000);
        positionBar.setThumbTintList(ColorStateList.valueOf(PlayerChrome.LAVENDER));
        positionBar.setProgressTintList(ColorStateList.valueOf(PlayerChrome.LAVENDER));
        positionBar.setProgressBackgroundTintList(ColorStateList.valueOf(0x887d7991));
        positionBar.setSecondaryProgressTintList(ColorStateList.valueOf(0xff77768b));
        positionBar.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            public void onStartTrackingTouch(SeekBar bar) { dragging = true; removeCallbacks(hide); }
            public void onProgressChanged(SeekBar bar, int value, boolean user) {
                if (user && !dragging && duration > 0) seekTo.accept(duration * value / 1000);
            }
            public void onStopTrackingTouch(SeekBar bar) {
                dragging = false;
                if (duration > 0) seekTo.accept(duration * bar.getProgress() / 1000);
                reveal();
            }
        });
        timeline.addView(positionBar, new LinearLayout.LayoutParams(0, chrome.dp(28), 1));
        timeline.addView(end);

        play = chrome.button("Pause", PlayerChrome.Icon.PAUSE, () -> { toggle.run(); reveal(); });
        back = chrome.button("10 Sek. zurück", PlayerChrome.Icon.REWIND, () -> { skip.accept(-10000); reveal(); });
        next = chrome.button("10 Sek. vor", PlayerChrome.Icon.FORWARD, () -> { skip.accept(10000); reveal(); });
        chrome.setActions(0, play, back, next);
        setOnClickListener(v -> { if (mayHide) hide.run(); else reveal(); });
    }

    public void setMetadata(String source, String name, String by) {
        chrome.heading.setText(source);
        if (name != null) title.setText(name);
        if (by != null) author.setText(by);
        author.setVisibility(TextUtils.isEmpty(author.getText()) ? GONE : VISIBLE);
    }

    public void update(boolean playing, boolean allowHide, boolean retry, boolean seekable, boolean live,
                       long position, long buffered, long length) {
        duration = length;
        String caption = retry ? "Erneut versuchen" : playing ? "Pause" : "Abspielen";
        play.update(caption, retry ? PlayerChrome.Icon.RETRY : playing ? PlayerChrome.Icon.PAUSE : PlayerChrome.Icon.PLAY, true);
        back.update("10 Sek. zurück", PlayerChrome.Icon.REWIND, seekable);
        next.update("10 Sek. vor", PlayerChrome.Icon.FORWARD, seekable);
        positionBar.setEnabled(seekable && length > 0);
        if (!dragging) positionBar.setProgress(length <= 0 ? 0 : (int)Math.min(1000, position * 1000 / length));
        positionBar.setSecondaryProgress(length <= 0 ? 0 : (int)Math.min(1000, buffered * 1000 / length));
        elapsed.setText(live ? "LIVE" : time(position));
        end.setText(live || length <= 0 ? "" : time(length));
        long now = System.currentTimeMillis();
        if (now / 60000 != clockMinute) {
            clockMinute = now / 60000;
            chrome.clock.setText(clock.format(new Date(now)));
        }
        if (mayHide != allowHide) {
            mayHide = allowHide;
            if (mayHide) scheduleHide(); else reveal();
        }
    }

    public void reveal() {
        if (getVisibility() != VISIBLE) {
            setVisibility(VISIBLE);
            chrome.select(0, !isInTouchMode());
        }
        scheduleHide();
    }

    private void scheduleHide() {
        removeCallbacks(hide);
        if (mayHide && !dragging) postDelayed(hide, 4000);
    }

    /** Match the Live TV D-pad: first OK reveals the strip, then arrows choose an action. */
    public boolean handleNavigation(KeyEvent event) {
        int code = event.getKeyCode();
        boolean select = code == KeyEvent.KEYCODE_DPAD_CENTER || code == KeyEvent.KEYCODE_ENTER
                || code == KeyEvent.KEYCODE_NUMPAD_ENTER;
        boolean direction = code == KeyEvent.KEYCODE_DPAD_LEFT || code == KeyEvent.KEYCODE_DPAD_RIGHT;
        if (!select && !direction && code != KeyEvent.KEYCODE_DPAD_UP && code != KeyEvent.KEYCODE_DPAD_DOWN
                && code != KeyEvent.KEYCODE_MENU && code != KeyEvent.KEYCODE_INFO) return false;
        if (event.getAction() != KeyEvent.ACTION_DOWN) return true;
        if (event.getRepeatCount() != 0) return true;
        boolean visible = getVisibility() == VISIBLE;
        reveal();
        if (select && visible) {
            int index = chrome.selected();
            (index == 0 ? play : index == 1 ? back : next).performClick();
        } else if (direction) chrome.moveSelection(code == KeyEvent.KEYCODE_DPAD_LEFT ? -1 : 1, true);
        return true;
    }

    @Override protected void onDetachedFromWindow() { removeCallbacks(hide); super.onDetachedFromWindow(); }

    private static String time(long value) {
        long sec = Math.max(0, value / 1000);
        return sec >= 3600 ? String.format(Locale.ROOT,"%d:%02d:%02d",sec/3600,sec/60%60,sec%60)
                : String.format(Locale.ROOT,"%d:%02d",sec/60,sec%60);
    }
}
