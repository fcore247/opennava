package site.navatv.companion;

import static site.navatv.companion.Surface.*;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.LinearGradient;
import android.graphics.Paint;
import android.graphics.Shader;
import android.os.Bundle;
import android.view.HapticFeedbackConstants;
import android.view.KeyEvent;
import android.view.MotionEvent;
import android.view.View;
import android.view.accessibility.AccessibilityNodeInfo;
import android.widget.SeekBar;

import java.util.function.IntConsumer;

/** A thick track with one dot per choice. The knob follows the finger and snaps to the nearest dot. */
final class IntervalSlider extends View {
    private final String[] labels;
    private final IntConsumer changed;
    private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final float density;
    private int index, committed;
    private float knob = -1, drag = -1;

    IntervalSlider(Context context, Surface ui, String[] labels, int selected, IntConsumer changed) {
        super(context);
        this.labels = labels;
        this.changed = changed;
        index = committed = selected;
        density = context.getResources().getDisplayMetrics().density;
        paint.setTypeface(ui.sans);
        setFocusable(true);
        describe();
    }

    private float dp(float value) { return value * density; }
    private float first() { return dp(30); }
    private float span() { return getWidth() - 2 * first(); }
    private float stop(int i) { return first() + span() * i / (labels.length - 1); }

    @Override protected void onMeasure(int width, int height) {
        setMeasuredDimension(MeasureSpec.getSize(width), Math.round(dp(100)));
    }

    @Override protected void onDraw(Canvas canvas) {
        float middle = dp(32), half = dp(17);
        if (knob < 0) knob = stop(index);
        float target = drag >= 0 ? drag : stop(index);
        knob += (target - knob) * (drag >= 0 ? 1f : .35f);
        if (Math.abs(target - knob) < .5f) knob = target;
        else postInvalidateOnAnimation();
        paint.setShader(null);
        paint.setColor(PALE);
        canvas.drawRoundRect(first() - half, middle - half, stop(labels.length - 1) + half, middle + half, half, half, paint);
        paint.setShader(new LinearGradient(first(), 0, Math.max(knob, first() + 1), 0, 0xff9e86f4, LILAC, Shader.TileMode.CLAMP));
        canvas.drawRoundRect(first() - half, middle - half, knob + half, middle + half, half, half, paint);
        paint.setShader(null);
        for (int i = 0; i < labels.length; i++) {
            paint.setColor(stop(i) < knob ? 0xff2b2052 : 0xffc9ccdb);
            canvas.drawCircle(stop(i), middle, dp(6), paint);
        }
        paint.setColor(0x55000000);
        canvas.drawCircle(knob, middle + dp(3), dp(25), paint);
        paint.setColor(WHITE);
        canvas.drawCircle(knob, middle, dp(25), paint);
        paint.setColor(0xff8c70e8);
        canvas.drawCircle(knob, middle, dp(9), paint);
        paint.setTextSize(dp(13));
        for (int i = 0; i < labels.length; i++) {
            paint.setColor(i == index ? INK : MUTED);
            paint.setFontVariationSettings(i == index ? "'wght' 780" : "'wght' 520");
            Paint.Align align = i == 0 ? Paint.Align.LEFT : i == labels.length - 1 ? Paint.Align.RIGHT : Paint.Align.CENTER;
            paint.setTextAlign(align);
            float x = i == 0 ? dp(5) : i == labels.length - 1 ? getWidth() - dp(5) : stop(i);
            canvas.drawText(labels[i], x, dp(84), paint);
        }
    }

    private int nearest(float x) {
        return Math.max(0, Math.min(labels.length - 1, Math.round((x - first()) / (span() / (labels.length - 1)))));
    }

    @Override public boolean onTouchEvent(MotionEvent event) {
        switch (event.getActionMasked()) {
            case MotionEvent.ACTION_DOWN:
            case MotionEvent.ACTION_MOVE:
                // The slider owns this gesture; the page behind it must not scroll.
                getParent().requestDisallowInterceptTouchEvent(true);
                drag = Math.max(first(), Math.min(stop(labels.length - 1), event.getX()));
                move(nearest(drag));
                invalidate();
                return true;
            case MotionEvent.ACTION_UP:
                drag = -1;
                commit();
                invalidate();
                performClick();
                return true;
            case MotionEvent.ACTION_CANCEL:
                drag = -1;
                move(committed);
                invalidate();
                return true;
            default:
                return false;
        }
    }

    @Override public boolean performClick() {
        super.performClick();
        return true;
    }

    private void move(int next) {
        if (next == index) return;
        index = next;
        performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK);
        describe();
        invalidate();
    }

    private void commit() {
        if (committed == index) return;
        committed = index;
        changed.accept(index);
    }

    private boolean step(int direction) {
        int next = Math.max(0, Math.min(labels.length - 1, index + direction));
        if (next == index) return false;
        move(next);
        commit();
        return true;
    }

    private void describe() { setContentDescription("Change photo " + labels[index].toLowerCase(java.util.Locale.ROOT)); }

    @Override public boolean onKeyDown(int key, KeyEvent event) {
        if (key == KeyEvent.KEYCODE_DPAD_LEFT) return step(-1);
        if (key == KeyEvent.KEYCODE_DPAD_RIGHT) return step(1);
        return super.onKeyDown(key, event);
    }

    @Override public void onInitializeAccessibilityNodeInfo(AccessibilityNodeInfo info) {
        super.onInitializeAccessibilityNodeInfo(info);
        info.setClassName(SeekBar.class.getName());
        info.addAction(AccessibilityNodeInfo.AccessibilityAction.ACTION_SCROLL_FORWARD);
        info.addAction(AccessibilityNodeInfo.AccessibilityAction.ACTION_SCROLL_BACKWARD);
    }

    @Override public boolean performAccessibilityAction(int action, Bundle arguments) {
        if (action == AccessibilityNodeInfo.ACTION_SCROLL_FORWARD) return step(1);
        if (action == AccessibilityNodeInfo.ACTION_SCROLL_BACKWARD) return step(-1);
        return super.performAccessibilityAction(action, arguments);
    }
}
