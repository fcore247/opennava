package site.navatv.launcher;

import android.animation.ValueAnimator;
import android.content.Context;
import android.graphics.Canvas;
import android.graphics.LinearGradient;
import android.graphics.Matrix;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RadialGradient;
import android.graphics.Shader;
import android.view.View;
import android.view.animation.LinearInterpolator;

/** The OpenNava mark with a soft moving reflection while the player waits for video. */
public final class PlaybackLoadingMark extends View {
    private final Path mark = new Path();
    private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Shader base = new LinearGradient(5, 32, 44, 4,
            new int[]{0xff9daffc, 0xffc4a9f1, 0xfff6b9a3}, new float[]{0, .52f, 1},
            Shader.TileMode.CLAMP);
    private final Shader reflection = new LinearGradient(-12, 2, 12, -2,
            new int[]{0x00ffffff, 0xbbfff8ff, 0x00ffffff}, null, Shader.TileMode.CLAMP);
    private final Shader halo = new RadialGradient(27, 22, 51,
            new int[]{0xe3211e32, 0x99322d47, 0x00242032}, new float[]{0, .45f, 1},
            Shader.TileMode.CLAMP);
    private final Matrix reflectionPosition = new Matrix();
    private final ValueAnimator sweep = ValueAnimator.ofFloat(-13, 75);
    private float highlightX = -13;

    public PlaybackLoadingMark(Context context) {
        super(context);
        // Same two strokes and proportions as the launcher icon.
        mark.moveTo(2, 38);
        mark.lineTo(19, 6);
        mark.lineTo(31, 6);
        mark.lineTo(14, 38);
        mark.close();
        mark.moveTo(20, 6);
        mark.lineTo(34, 30);
        mark.lineTo(41, 17);
        mark.lineTo(52, 38);
        mark.lineTo(34, 38);
        mark.lineTo(17, 12);
        mark.close();
        setContentDescription("Video wird geladen");
        setImportantForAccessibility(IMPORTANT_FOR_ACCESSIBILITY_YES);
        sweep.setDuration(2200);
        sweep.setInterpolator(new LinearInterpolator());
        sweep.setRepeatCount(ValueAnimator.INFINITE);
        sweep.addUpdateListener(animation -> {
            highlightX = (float) animation.getAnimatedValue();
            invalidate();
        });
    }

    @Override protected void onMeasure(int widthSpec, int heightSpec) {
        float density = getResources().getDisplayMetrics().density;
        setMeasuredDimension(resolveSize(Math.round(104 * density), widthSpec),
                resolveSize(Math.round(80 * density), heightSpec));
    }

    @Override protected void onDraw(Canvas canvas) {
        float scale = Math.min(getWidth() / 104f, getHeight() / 80f);
        canvas.save();
        canvas.translate(getWidth() / 2f, getHeight() / 2f);
        canvas.scale(scale, scale);
        canvas.translate(-27, -22);
        canvas.save();
        canvas.scale(1f, .76f, 27, 22);
        paint.setShader(halo);
        canvas.drawCircle(27, 22, 51, paint);
        canvas.restore();
        paint.setShader(base);
        canvas.drawPath(mark, paint);
        reflectionPosition.setTranslate(highlightX, 22);
        reflection.setLocalMatrix(reflectionPosition);
        paint.setShader(reflection);
        canvas.drawPath(mark, paint);
        canvas.restore();
    }

    @Override public void onVisibilityAggregated(boolean visible) {
        super.onVisibilityAggregated(visible);
        if (visible && ValueAnimator.areAnimatorsEnabled()) {
            if (!sweep.isStarted()) sweep.start();
        } else {
            sweep.cancel();
            highlightX = -13;
            invalidate();
        }
    }

    @Override protected void onDetachedFromWindow() {
        sweep.cancel();
        super.onDetachedFromWindow();
    }
}
