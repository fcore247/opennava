package site.navatv.companion;

import static site.navatv.companion.Surface.*;

import android.graphics.*;
import android.view.*;
import android.widget.*;

import java.util.function.IntConsumer;

final class RemoteControl {
    private final Surface u;
    private final IntConsumer key;

    RemoteControl(Surface u, IntConsumer key) {
        this.u = u;
        this.key = key;
    }

    void build(LinearLayout body, Runnable sendText, Runnable tvHome) {
        u.space(body, 16);
        LinearLayout panel = u.column();
        panel.setGravity(Gravity.CENTER_HORIZONTAL);
        panel.setBackground(u.shape(CARD, 30, LINE));
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
        LinearLayout home = u.column();
        home.setGravity(Gravity.CENTER);
        home.addView(new Art(u.context, "home", INK), u.lp(21, 21));
        u.space(home, 4);
        home.addView(u.text("TV home", 11, INK, 650), u.lp(WRAP, WRAP));
        u.action(home, 0, 14, 0, tvHome);
        home.setContentDescription("Open the TV home screen");
        shortcuts.addView(home, u.weight(59));
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
        u.action(b, CARD, 17, LINE, () -> key.accept(code));
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
                            new Art(u.context, icons[i], 0xfff4f3f9),
                            new FrameLayout.LayoutParams(u.d(22), u.d(22), Gravity.CENTER));
                final int keyCode = codes[i];
                u.action(
                        b,
                        ok ? 0xff5a4d96 : 0,
                        length,
                        ok ? 0xff8676c9 : 0,
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
                    new LinearGradient(0, 0, s, s, 0xff2a2f42, 0xff1b1f2c, Shader.TileMode.CLAMP));
            brush.setStyle(Paint.Style.FILL);
            c.drawCircle(center, center, center - 1, brush);
            brush.setShader(null);
            brush.setStyle(Paint.Style.STROKE);
            brush.setStrokeWidth(u.d(1));
            brush.setColor(0xff3b4258);
            c.drawCircle(center, center, center - u.d(4), brush);
            brush.setColor(0xff12151e);
            brush.setStrokeWidth(u.d(3));
            c.drawCircle(center, center, s * .195f, brush);
            brush.setColor(0x70596077);
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
