package site.navatv.companion;

import android.animation.ValueAnimator;
import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.os.SystemClock;
import android.view.View;

/** Four bars that move while something plays and rest when it is paused. */
final class Equalizer extends View {
    private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private boolean moving;

    Equalizer(Context context) {
        super(context);
        paint.setColor(Surface.WHITE);
        setImportantForAccessibility(IMPORTANT_FOR_ACCESSIBILITY_NO);
    }

    void moving(boolean value) {
        if (moving == value) return;
        moving = value;
        invalidate();
    }

    @Override protected void onDraw(Canvas canvas) {
        float gap = getWidth() / 11f, width = gap * 2;
        double time = SystemClock.uptimeMillis() / 1000.0;
        boolean animate = moving && ValueAnimator.areAnimatorsEnabled();
        double[] speed = {7.1, 9.4, 6.2, 8.3};
        for (int i = 0; i < 4; i++) {
            float level = animate ? (float) (.28 + .72 * Math.abs(Math.sin(time * speed[i] + i * 1.7))) : moving ? .6f : .22f;
            float x = i * (width + gap), height = Math.max(width, getHeight() * level);
            canvas.drawRoundRect(x, getHeight() - height, x + width, getHeight(), width / 2, width / 2, paint);
        }
        if (animate && isShown()) postInvalidateDelayed(40);
    }
}
