package site.navatv.companion;

import static site.navatv.companion.Surface.*;

import android.graphics.drawable.GradientDrawable;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

/** A rounded picture or colour tile that gives way slightly under the finger. */
final class HomeTile {
    static FrameLayout frame(MainActivity a, Surface u, int radius, int[] colours) {
        FrameLayout tile = new FrameLayout(a);
        GradientDrawable ground = new GradientDrawable(GradientDrawable.Orientation.TL_BR, colours);
        ground.setCornerRadius(u.d(radius));
        tile.setBackground(ground);
        tile.setClipToOutline(true);
        return tile;
    }

    static void photo(MainActivity a, Surface u, FrameLayout tile, String asset) {
        ImageView picture = new ImageView(a);
        picture.setScaleType(ImageView.ScaleType.CENTER_CROP);
        try (java.io.InputStream in = a.getAssets().open(asset)) {
            android.graphics.BitmapFactory.Options options = new android.graphics.BitmapFactory.Options();
            options.inSampleSize = 2;
            picture.setImageBitmap(android.graphics.BitmapFactory.decodeStream(in, null, options));
        } catch (java.io.IOException missing) { /* the tile keeps its colour */ }
        tile.addView(picture, new FrameLayout.LayoutParams(MATCH, MATCH));
        View shade = new View(a);
        shade.setBackground(new GradientDrawable(GradientDrawable.Orientation.BOTTOM_TOP,
                new int[] {0xe60b0d14, 0x550b0d14, 0x100b0d14}));
        tile.addView(shade, new FrameLayout.LayoutParams(MATCH, MATCH));
    }

    /** Title and one line below it, in the bottom left corner. */
    static TextView[] words(MainActivity a, Surface u, FrameLayout tile, String title, String detail, int size) {
        LinearLayout words = u.column();
        u.pad(words, 16, 0, 16, 15);
        TextView first = u.text(title, size, WHITE, 800);
        first.setSingleLine();
        first.setEllipsize(android.text.TextUtils.TruncateAt.END);
        words.addView(first);
        u.space(words, 5);
        TextView second = u.text(detail, 12.5f, 0xffd5d7e4, 580);
        second.setSingleLine();
        second.setEllipsize(android.text.TextUtils.TruncateAt.END);
        words.addView(second);
        tile.addView(words, new FrameLayout.LayoutParams(MATCH, WRAP, Gravity.BOTTOM));
        return new TextView[] {first, second};
    }

    /** A brand mark in a small rounded square, top left. */
    static void mark(MainActivity a, Surface u, FrameLayout tile, View content, int colour) {
        FrameLayout badge = new FrameLayout(a);
        badge.setBackground(u.shape(colour, 14, 0));
        int size = content instanceof Art ? u.d(23) : WRAP;
        badge.addView(content, new FrameLayout.LayoutParams(size, size, Gravity.CENTER));
        FrameLayout.LayoutParams place = new FrameLayout.LayoutParams(u.d(46), u.d(46), Gravity.TOP | Gravity.START);
        place.setMargins(u.d(14), u.d(14), 0, 0);
        tile.addView(badge, place);
    }

    static void press(View tile, String description, Runnable run) {
        tile.setContentDescription(description);
        tile.setFocusable(true);
        tile.setForeground(new android.graphics.drawable.RippleDrawable(
                android.content.res.ColorStateList.valueOf(0x33ffffff), null,
                new android.graphics.drawable.ColorDrawable(0xffffffff)));
        tile.setOnClickListener(v -> run.run());
        tile.setOnTouchListener((v, event) -> {
            int action = event.getActionMasked();
            if (action == MotionEvent.ACTION_DOWN) v.animate().scaleX(.965f).scaleY(.965f).setDuration(90).start();
            else if (action == MotionEvent.ACTION_UP || action == MotionEvent.ACTION_CANCEL)
                v.animate().scaleX(1).scaleY(1).setDuration(160).start();
            return false;
        });
    }

    private HomeTile() {}
}
