package site.navatv.launcher;

import android.animation.ValueAnimator;
import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.LinearGradient;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RadialGradient;
import android.graphics.Shader;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.os.PowerManager;
import android.os.SystemClock;
import android.text.TextUtils;
import android.util.SparseArray;
import android.view.Gravity;
import android.view.View;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.TextView;

/** Shared header, animated bottom edge and remote controls for the native TV players. */
final class PlayerChrome {
    static final int PEARL = 0xfff8f7ff, LAVENDER = 0xffbcbaff, MUTED = 0xffd4d2e8;
    static final int NAVY = 0xff1a1b32, VIOLET_EDGE = 0xff474660;

    private final Context context;
    private final FrameLayout overlay;
    private final SparseArray<Typeface> fonts = new SparseArray<>();
    final TextView heading, clock;
    private ActionButton[] buttons;
    private int primaryIndex, focus;

    PlayerChrome(Context context, FrameLayout overlay, String title) {
        this.context = context;
        this.overlay = overlay;
        View topFade = new View(context);
        topFade.setBackground(new GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM,
                new int[] {0xdf080916, 0xcf10101a, 0xb0080912, 0x00090912}));
        overlay.addView(topFade, new FrameLayout.LayoutParams(-1, dp(272), Gravity.TOP));
        overlay.addView(new BottomMist(context), new FrameLayout.LayoutParams(-1, dp(226), Gravity.BOTTOM));

        LinearLayout masthead = new LinearLayout(context);
        masthead.setGravity(Gravity.CENTER_VERTICAL);
        overlay.addView(masthead, at(-2, -2, Gravity.TOP | Gravity.START, 36, 20, 0, 0));
        mark(masthead, 3, 8, 0xff9da5ff);
        mark(masthead, 3, 14, 0xffb4a2ff);
        mark(masthead, 3, 10, 0xffe9a9c0);
        heading = label(16, 780, PEARL);
        heading.setText(title);
        heading.setPadding(dp(6), 0, 0, 0);
        masthead.addView(heading);

        LinearLayout clockGroup = new LinearLayout(context);
        clockGroup.setGravity(Gravity.CENTER_VERTICAL);
        overlay.addView(clockGroup, at(-2, -2, Gravity.TOP | Gravity.END, 0, 21, 43, 0));
        mark(clockGroup, 5, 5, 0xffc5a0de);
        clock = label(13, 680, MUTED);
        clock.setPadding(dp(4), 0, 0, 0);
        clockGroup.addView(clock);
    }

    ActionButton button(String label, Icon icon, Runnable action) {
        ActionButton button = new ActionButton(label, icon);
        button.setOnClickListener(v -> action.run());
        return button;
    }

    void setActions(int primaryIndex, ActionButton... actions) {
        this.primaryIndex = primaryIndex;
        buttons = actions;
        focus = primaryIndex;
        LinearLayout strip = new LinearLayout(context);
        strip.setGravity(Gravity.CENTER_VERTICAL);
        strip.setPadding(dp(48), dp(4), dp(48), dp(24));
        overlay.addView(strip, new FrameLayout.LayoutParams(-1, dp(78), Gravity.BOTTOM));

        for (int i = 0; i < buttons.length; i++) {
            if (i > 0) {
                View divider = new View(context);
                divider.setBackgroundColor(0x609a95bb);
                strip.addView(divider, new LinearLayout.LayoutParams(dp(1), dp(22)));
            }
            strip.addView(buttons[i], new LinearLayout.LayoutParams(0, -1, 1));
            final int index = i;
            buttons[i].setOnFocusChangeListener((v, hasFocus) -> {
                if (hasFocus && focus != index) {
                    focus = index;
                    styleButtons();
                }
            });
        }
        styleButtons();
    }

    void select(int index, boolean requestFocus) {
        focus = buttons[index].isEnabled() ? index : primaryIndex;
        styleButtons();
        if (requestFocus) buttons[focus].requestFocus();
    }

    boolean moveSelection(int direction, boolean requestFocus) {
        for (int i = focus + direction; i >= 0 && i < buttons.length; i += direction) {
            if (buttons[i].isEnabled()) {
                select(i, requestFocus);
                return true;
            }
        }
        return false;
    }

    int selected() { return focus; }

    private void styleButtons() {
        for (int i = 0; i < buttons.length; i++) buttons[i].highlight(i == focus);
    }

    FrameLayout.LayoutParams at(int width, int height, int gravity, int l, int t, int r, int b) {
        FrameLayout.LayoutParams p = new FrameLayout.LayoutParams(width, height, gravity);
        p.setMargins(dp(l), dp(t), dp(r), dp(b));
        return p;
    }

    private void mark(LinearLayout row, int width, int height, int color) {
        View mark = new View(context);
        GradientDrawable pill = new GradientDrawable();
        pill.setColor(color);
        pill.setCornerRadius(dp(4));
        mark.setBackground(pill);
        LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(dp(width), dp(height));
        p.rightMargin = dp(2);
        row.addView(mark, p);
    }

    enum Icon { PLAY, PAUSE, RECORD, LIBRARY, RETRY, REWIND, FORWARD }

    /** Soft pools of colour rise from the bottom edge. All gradients stay transparent at the top. */
    private static final class BottomMist extends View {
        private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG | Paint.DITHER_FLAG);
        private final Shader base = new LinearGradient(0, 0, 0, 1,
                new int[] {0x000e1023, 0x160e1028, 0x68141632, 0xca191a39, 0xfa1c1c3c},
                new float[] {0f, .24f, .51f, .78f, 1f}, Shader.TileMode.CLAMP);
        private final Shader indigo = glow(0xa45555bb, 0x565051aa, 0x005051aa);
        private final Shader violet = glow(0x996e4ea3, 0x50654f99, 0x00654f99);
        private final Shader mauve = glow(0x88875696, 0x49734a89, 0x00734a89);
        private final PowerManager power;
        private boolean visible, drifting;
        private long checkPowerAt;

        BottomMist(Context context) {
            super(context);
            power = context.getSystemService(PowerManager.class);
            setImportantForAccessibility(IMPORTANT_FOR_ACCESSIBILITY_NO);
        }

        private static Shader glow(int core, int halo, int edge) {
            return new RadialGradient(0, 0, 1, new int[] {core, halo, edge},
                    new float[] {0f, .53f, 1f}, Shader.TileMode.CLAMP);
        }

        @Override public void onVisibilityAggregated(boolean isVisible) {
            super.onVisibilityAggregated(isVisible);
            visible = isVisible;
            checkPowerAt = 0;
            if (isVisible) invalidate();
        }

        @Override public void onWindowFocusChanged(boolean hasFocus) {
            super.onWindowFocusChanged(hasFocus);
            checkPowerAt = 0;
            if (hasFocus && visible) invalidate();
        }

        @Override protected void onDraw(Canvas canvas) {
            long now = SystemClock.uptimeMillis();
            if (now >= checkPowerAt) {
                drifting = ValueAnimator.areAnimatorsEnabled() && power != null && !power.isPowerSaveMode();
                checkPowerAt = now + 2500;
            }
            // The three banks drift at different rates in a continuous loop lasting several minutes.
            double phase = drifting ? (now % 360000L) * (2 * Math.PI / 360000.0) : 0;
            float a = (float) Math.sin(5 * phase), b = (float) Math.sin(7 * phase + 1.1);
            float c = (float) Math.sin(4 * phase + 2.4);
            canvas.save();
            canvas.scale(getWidth(), getHeight());
            paint.setShader(base);
            canvas.drawRect(0, 0, 1, 1, paint);
            drawGlow(canvas, indigo, .14f + .10f * a, .98f + .06f * c, .69f, .81f);
            drawGlow(canvas, violet, .56f + .12f * b, 1.13f + .09f * a, .61f, .93f);
            drawGlow(canvas, mauve, .93f + .09f * c, 1.04f + .07f * b, .38f, .67f);
            canvas.restore();
            if (visible && hasWindowFocus() && drifting) postInvalidateDelayed(110);
        }

        private void drawGlow(Canvas canvas, Shader shader, float x, float y, float width, float height) {
            canvas.save();
            canvas.translate(x, y);
            canvas.scale(width, height);
            paint.setShader(shader);
            canvas.drawCircle(0, 0, 1, paint);
            canvas.restore();
        }
    }

    final class ActionButton extends LinearLayout {
        final TransportIcon symbol;
        final TextView caption;

        private ActionButton(String text, Icon icon) {
            super(context);
            setGravity(Gravity.CENTER);
            setFocusable(true);
            setDefaultFocusHighlightEnabled(false);
            symbol = new TransportIcon(icon);
            addView(symbol, new LinearLayout.LayoutParams(dp(33), dp(33)));
            caption = label(16, 700, PEARL);
            caption.setLetterSpacing(-.02f);
            caption.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
            oneLine(caption);
            LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(-2, -2);
            p.leftMargin = dp(9);
            addView(caption, p);
            update(text, icon, true);
        }

        void update(String text, Icon icon, boolean enabled) {
            if (!text.contentEquals(caption.getText())) {
                caption.setText(text);
                setContentDescription(text);
            }
            if (symbol.icon != icon) {
                symbol.icon = icon;
                symbol.invalidate();
            }
            if (isEnabled() != enabled) {
                boolean wasSelected = buttons != null && buttons[focus] == this;
                boolean moveFocus = hasFocus();
                setEnabled(enabled);
                setFocusable(enabled);
                setAlpha(enabled ? 1f : .47f);
                if (!enabled && wasSelected) select(primaryIndex, moveFocus);
            }
        }

        void highlight(boolean selected) {
            setSelected(selected);
            setBackground(selected ? shape(0xff34344f, 13, 0xffaaa9d7) : null);
            caption.setTextColor(selected ? Color.WHITE : MUTED);
            symbol.selected = selected;
            symbol.invalidate();
        }

        @Override public CharSequence getAccessibilityClassName() {
            return android.widget.Button.class.getName();
        }
    }

    /** A ring and a native vector symbol stay crisp at TV density and never pick an emoji font. */
    private final class TransportIcon extends View {
        private final Paint ink = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Path path = new Path();
        private Icon icon;
        private boolean selected;

        TransportIcon(Icon icon) {
            super(context);
            this.icon = icon;
            setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
        }

        @Override protected void onDraw(Canvas canvas) {
            super.onDraw(canvas);
            canvas.save();
            canvas.scale(getWidth() / 34f, getHeight() / 34f);
            ink.setStyle(Paint.Style.FILL);
            if (selected) {
                ink.setColor(0xff9397f2);
                canvas.drawCircle(17, 17, 16.5f, ink);
                ink.setColor(NAVY);
                canvas.drawCircle(17, 17, 15.2f, ink);
            }
            ink.setColor(selected ? 0xffefeeff : 0xff303047);
            canvas.drawCircle(17, 17, selected ? 13.8f : 14f, ink);
            ink.setColor(selected ? NAVY : PEARL);
            ink.setStrokeWidth(1.7f);
            ink.setStrokeCap(Paint.Cap.ROUND);
            ink.setStrokeJoin(Paint.Join.ROUND);
            switch (icon) {
                case PLAY:
                    path.reset();
                    path.moveTo(14, 11.6f); path.lineTo(22.5f, 17); path.lineTo(14, 22.4f); path.close();
                    canvas.drawPath(path, ink);
                    break;
                case PAUSE:
                    canvas.drawRoundRect(12.5f, 11.4f, 15.8f, 22.6f, 1, 1, ink);
                    canvas.drawRoundRect(18.2f, 11.4f, 21.5f, 22.6f, 1, 1, ink);
                    break;
                case RECORD:
                    ink.setColor(selected ? 0xffa83853 : 0xfff7a2b4);
                    canvas.drawCircle(17, 17, 4.8f, ink);
                    break;
                case LIBRARY:
                    ink.setStyle(Paint.Style.STROKE);
                    canvas.drawRoundRect(11, 13, 22.5f, 21.5f, 1.4f, 1.4f, ink);
                    canvas.drawLine(13, 10.5f, 21, 10.5f, ink);
                    ink.setStyle(Paint.Style.FILL);
                    path.reset();
                    path.moveTo(15.4f, 15.1f); path.lineTo(19, 17.2f); path.lineTo(15.4f, 19.4f); path.close();
                    canvas.drawPath(path, ink);
                    break;
                case REWIND:
                case FORWARD:
                    if (icon == Icon.REWIND) canvas.scale(-1, 1, 17, 17);
                    path.reset();
                    for (int x = 11; x <= 17; x += 6) {
                        path.moveTo(x, 12.3f); path.lineTo(x + 6.5f, 17); path.lineTo(x, 21.7f); path.close();
                    }
                    canvas.drawPath(path, ink);
                    break;
                case RETRY:
                    ink.setStyle(Paint.Style.STROKE);
                    canvas.drawArc(11.5f, 11.5f, 22.5f, 22.5f, -55, 300, false, ink);
                    canvas.drawLine(18.2f, 10.7f, 20.8f, 12.7f, ink);
                    canvas.drawLine(20.8f, 12.7f, 18.3f, 14.7f, ink);
                    break;
            }
            canvas.restore();
        }
    }

    static void oneLine(TextView view) {
        view.setSingleLine(true);
        view.setEllipsize(TextUtils.TruncateAt.END);
    }

    TextView label(int size, int weight, int color) {
        TextView view = new TextView(context);
        Typeface font = fonts.get(weight);
        if (font == null) {
            try {
                font = new Typeface.Builder(context.getAssets(), "assets/fonts/Manrope.ttf")
                        .setFontVariationSettings("'wght' " + weight).setWeight(weight).build();
            } catch (RuntimeException ignored) { }
            if (font == null) font = Typeface.create("sans-serif", weight >= 650 ? Typeface.BOLD : Typeface.NORMAL);
            fonts.put(weight, font);
        }
        view.setTypeface(font);
        view.setTextSize(size);
        view.setTextColor(color);
        view.setIncludeFontPadding(false);
        return view;
    }

    GradientDrawable shape(int fill, int radius, int line) {
        GradientDrawable drawable = new GradientDrawable();
        drawable.setColor(fill);
        drawable.setCornerRadius(dp(radius));
        drawable.setStroke(dp(1), line);
        return drawable;
    }
    int dp(int value) { return Math.round(context.getResources().getDisplayMetrics().density * value); }
}
