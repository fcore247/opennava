package site.navatv.companion;

import static site.navatv.companion.Surface.*;

import android.graphics.*;
import android.view.*;
import android.widget.*;

import site.navatv.launcher.PhoneGuide;
import java.util.function.Consumer;
import java.util.function.IntConsumer;

final class RemoteControl {
    private final Surface u;
    private final IntConsumer key;

    RemoteControl(Surface u, IntConsumer key) {
        this.u = u;
        this.key = key;
    }

    void buildLive(LinearLayout body, TvConnection.LiveState state, boolean hasRtl, Consumer<String> control) {
        String station = state.channel;
        for (PhoneGuide.Channel channel : PhoneGuide.channels(hasRtl)) {
            if (channel.id.equals(state.channel)) { station = channel.name; break; }
        }
        u.space(body, 15);
        LinearLayout card = u.column();
        card.setBackground(u.shape(GREEN, 26, 0));
        u.pad(card, 18, 17, 18, 17);
        LinearLayout label = u.row();
        label.addView(new Art(u.context, "onair", APRICOT), u.lp(20, 20));
        u.gap(label, 7);
        label.addView(u.text(state.paused ? "Live TV · Paused" : "Live TV", 14, WHITE, 660));
        card.addView(label);
        u.space(card, 7);
        card.addView(u.title(station, 38, WHITE));
        u.space(card, 15);
        LinearLayout channels = u.row();
        channels.addView(u.button("Channel −", "left", false, () -> control.accept("previous")), u.weight(52));
        u.gap(channels, 8);
        channels.addView(u.button("Channel +", "right", false, () -> control.accept("next")), u.weight(52));
        channels.getChildAt(0).setContentDescription("Previous channel");
        channels.getChildAt(2).setContentDescription("Next channel");
        card.addView(channels);
        body.addView(card);
        u.space(body, 15);
        LinearLayout transport = u.row();
        seekButton(transport, "rewind", "Rewind", state.seekable, () -> control.accept("rewind"));
        u.gap(transport, 7);
        transport.addView(u.button(state.paused ? "Resume" : "Pause", state.paused ? "resume" : "pause", true,
                () -> control.accept("pause")), new LinearLayout.LayoutParams(0, u.d(58), 1.8f));
        u.gap(transport, 7);
        seekButton(transport, "forward", "Fast forward", state.seekable, () -> control.accept("forward"));
        body.addView(transport);
        u.space(body, 10);
        LinearLayout options = u.row();
        options.addView(u.button("TV guide", "library", false, () -> control.accept("guide")), u.weight(53));
        u.gap(options, 8);
        options.addView(u.button("Record", "record", false, () -> control.accept("record")), u.weight(53));
        body.addView(options);
        u.space(body, 17);
        LinearLayout system = u.row();
        system.setBackground(u.shape(WHITE, 18, LINE));
        quick(system, "back", "Back", 4);
        quick(system, "minus", "Volume down", 25);
        quick(system, "plus", "Volume up", 24);
        body.addView(system);
    }

    private void seekButton(LinearLayout row, String icon, String label, boolean enabled, Runnable action) {
        FrameLayout button = new FrameLayout(u.context);
        button.addView(new Art(u.context, icon, INK), new FrameLayout.LayoutParams(u.d(22), u.d(22), Gravity.CENTER));
        u.action(button, WHITE, 16, LINE, action);
        button.setContentDescription(label);
        button.setEnabled(enabled);
        button.setAlpha(enabled ? 1f : .4f);
        row.addView(button, u.weight(58));
    }

    void build(LinearLayout body, Runnable sendText) {
        u.space(body, 16);
        LinearLayout panel = u.column();
        panel.setGravity(Gravity.CENTER_HORIZONTAL);
        panel.setBackground(u.shape(0xffe9ebe1, 30, LINE));
        u.pad(panel, 14, 13, 14, 11);
        LinearLayout header = u.row();
        header.addView(u.text("Navigation", 14, MUTED, 650), u.weight(-2));
        View power = iconButton("power", "Standby / Wake", 26);
        header.addView(power, u.lp(48, 48));
        panel.addView(header, u.lp(MATCH, 41));
        int available = u.context.getResources().getConfiguration().screenWidthDp;
        int shortSide =
                Math.max(
                        199,
                        Math.min(
                                Math.min(available - 110, 252),
                                u.context.getResources().getConfiguration().screenHeightDp / 3));
        Dial dial = new Dial(shortSide);
        panel.addView(dial, u.lp(shortSide, shortSide));
        u.space(panel, 9);
        LinearLayout shortcuts = u.row();
        quick(shortcuts, "back", "Back", 4);
        quick(shortcuts, "menu", "Menu", 82);
        panel.addView(shortcuts, u.lp(MATCH, 59));
        body.addView(panel);
        u.space(body, 17);
        body.addView(u.text("Playback", 17, INK, 710));
        u.space(body, 9);
        LinearLayout playback = u.row();
        media(playback, "rewind", "Rewind", 89);
        u.gap(playback, 8);
        media(playback, "playpause", "Play / Pause", 85);
        u.gap(playback, 8);
        media(playback, "forward", "Fast forward", 90);
        body.addView(playback);
        u.space(body, 12);
        LinearLayout extras = u.row();
        LinearLayout volume = u.row();
        volume.setBackground(u.shape(PALE, 18, LINE));
        volume.setGravity(Gravity.CENTER);
        volume.addView(iconButton("minus", "Volume down", 25), u.weight(48));
        volume.addView(iconButton("volume", "Mute / Unmute", 164), u.weight(48));
        volume.addView(iconButton("plus", "Volume up", 24), u.weight(48));
        extras.addView(volume, u.weight(48));
        u.gap(extras, 8);
        extras.addView(u.button("Send text", "message", false, sendText), u.weight(48));
        body.addView(extras);
    }

    private FrameLayout iconButton(String name, String description, int code) {
        FrameLayout b = new FrameLayout(u.context);
        FrameLayout.LayoutParams center =
                new FrameLayout.LayoutParams(u.d(21), u.d(21), Gravity.CENTER);
        b.addView(new Art(u.context, name, INK), center);
        u.action(b, 0, 16, 0, () -> key.accept(code));
        b.setContentDescription(description);
        return b;
    }

    private void quick(LinearLayout bar, String icon, String title, int code) {
        LinearLayout b = u.column();
        b.setGravity(Gravity.CENTER);
        b.addView(new Art(u.context, icon, INK), u.lp(21, 21));
        u.space(b, 4);
        b.addView(u.text(title, 11, INK, 650), u.lp(WRAP, WRAP));
        u.action(b, 0, 14, 0, () -> key.accept(code));
        b.setContentDescription(title);
        bar.addView(b, u.weight(59));
    }

    private void media(LinearLayout bar, String icon, String title, int code) {
        FrameLayout b = iconButton(icon, title, code);
        u.action(b, WHITE, 17, LINE, () -> key.accept(code));
        bar.addView(b, u.weight(52));
    }

    private final class Dial extends FrameLayout {
        final int side;
        final Paint brush = new Paint(Paint.ANTI_ALIAS_FLAG);

        Dial(int side) {
            super(u.context);
            this.side = side;
            setWillNotDraw(false);
            String[] icons = {"up", "left", "right", "down", "ok"};
            String[] labels = {"Up", "Left", "Right", "Down", "Select"};
            int[] codes = {19, 21, 22, 20, 23};
            float[][] centers = {
                {.5f, .175f}, {.175f, .5f}, {.825f, .5f}, {.5f, .825f}, {.5f, .5f}
            };
            for (int i = 0; i < icons.length; i++) {
                boolean ok = i == 4;
                int length = ok ? Math.round(side * .37f) : 48;
                FrameLayout b = new FrameLayout(u.context);
                if (ok) {
                    TextView sign = u.text("OK", 20, WHITE, 680);
                    sign.setGravity(Gravity.CENTER);
                    b.addView(sign, new FrameLayout.LayoutParams(-1, -1));
                } else
                    b.addView(
                            new Art(u.context, icons[i], 0xfff1f0df),
                            new FrameLayout.LayoutParams(u.d(22), u.d(22), Gravity.CENTER));
                final int keyCode = codes[i];
                u.action(
                        b,
                        ok ? 0xff49634e : 0,
                        length,
                        ok ? 0xff687a65 : 0,
                        () -> {
                            b.performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP);
                            key.accept(keyCode);
                        });
                b.setContentDescription(labels[i]);
                FrameLayout.LayoutParams p = new FrameLayout.LayoutParams(u.d(length), u.d(length));
                p.leftMargin = u.d(side * centers[i][0] - length * .5f);
                p.topMargin = u.d(side * centers[i][1] - length * .5f);
                addView(b, p);
            }
        }

        @Override
        protected void onDraw(Canvas c) {
            super.onDraw(c);
            float s = getWidth(), center = s * .5f;
            brush.setShader(
                    new LinearGradient(0, 0, s, s, 0xff334d3d, 0xff233a2f, Shader.TileMode.CLAMP));
            brush.setStyle(Paint.Style.FILL);
            c.drawCircle(center, center, center - 1, brush);
            brush.setShader(null);
            brush.setStyle(Paint.Style.STROKE);
            brush.setStrokeWidth(u.d(1));
            brush.setColor(0xff5b6d59);
            c.drawCircle(center, center, center - u.d(4), brush);
            brush.setColor(0xff273f31);
            brush.setStrokeWidth(u.d(3));
            c.drawCircle(center, center, s * .195f, brush);
            brush.setColor(0x706b7a69);
            brush.setStrokeWidth(u.d(1));
            for (int i = 0; i < 4; i++) {
                c.save();
                c.rotate(45 + 90 * i, center, center);
                c.drawLine(center, s * .065f, center, s * .24f, brush);
                c.restore();
            }
        }
    }
}
