package site.navatv.companion;

import static site.navatv.companion.Surface.*;

import android.animation.ValueAnimator;
import android.content.Context;
import android.graphics.Rect;
import android.view.Gravity;
import android.view.HapticFeedbackConstants;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewConfiguration;
import android.view.ViewGroup;
import android.view.animation.DecelerateInterpolator;
import android.widget.FrameLayout;
import android.widget.ScrollView;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;

/**
 * The widgets of the start page in a grid of two columns. While editing, a widget is picked up
 * by holding it; the others make room as it travels, and a quick swipe still scrolls the page.
 */
final class HomeBoard extends ViewGroup {
    private static final class Item extends FrameLayout {
        String id;
        boolean wide;
        /** In pixels; 0 lets the widget choose its height. */
        int fixed;
        View remove;
        final Rect place = new Rect();
        ValueAnimator wobble;
        Item(Context context) { super(context); setClipChildren(false); setClipToPadding(false); }
    }

    private final Surface u;
    private final List<Item> items = new ArrayList<>();
    private final int gap, slop;
    private boolean editing, settling;
    private Item touched, held;
    private float downX, downY, heldLeft, heldTop, lastRawY;
    private long movedAt;
    Consumer<List<String>> reordered = ids -> {};
    Consumer<String> removed = id -> {};

    private final Runnable pickUp = this::lift;
    private final Runnable edgeScroll = new Runnable() {
        @Override public void run() {
            ScrollView scroll = scroller();
            if (held == null || scroll == null) return;
            int[] at = new int[2];
            scroll.getLocationOnScreen(at);
            int zone = u.d(90);
            int step = lastRawY < at[1] + zone ? -u.d(9) : lastRawY > at[1] + scroll.getHeight() - zone ? u.d(9) : 0;
            if (step != 0 && scroll.canScrollVertically(step)) {
                scroll.scrollBy(0, step);
                heldTop += step;
                downY -= step;
                follow(0, 0);
            }
            postOnAnimation(this);
        }
    };

    HomeBoard(MainActivity activity) {
        super(activity);
        u = activity.ui;
        gap = u.d(12);
        slop = ViewConfiguration.get(activity).getScaledTouchSlop();
        setClipChildren(false);
        setClipToPadding(false);
    }

    boolean dragging() { return held != null; }

    void add(String id, View content, boolean wide, int heightDp, String name) {
        Item item = new Item(getContext());
        item.id = id;
        item.wide = wide;
        item.fixed = heightDp <= 0 ? 0 : u.d(heightDp);
        item.addView(content, new FrameLayout.LayoutParams(MATCH, heightDp <= 0 ? WRAP : MATCH));
        FrameLayout remove = new FrameLayout(getContext());
        FrameLayout dot = new FrameLayout(getContext());
        dot.setBackground(u.shape(0xffff5d6c, 14, 0x66ffffff));
        dot.addView(new Art(getContext(), "minus", WHITE), new FrameLayout.LayoutParams(u.d(16), u.d(16), Gravity.CENTER));
        remove.addView(dot, new FrameLayout.LayoutParams(u.d(28), u.d(28), Gravity.CENTER));
        remove.setContentDescription("Remove " + name);
        remove.setFocusable(true);
        remove.setElevation(u.d(5));
        remove.setOnClickListener(v -> removed.accept(item.id));
        // Inside the corner, so that no parent cuts the button off or swallows its taps.
        item.addView(remove, new FrameLayout.LayoutParams(u.d(46), u.d(46), Gravity.TOP | Gravity.END));
        remove.setVisibility(GONE);
        item.remove = remove;
        items.add(item);
        addView(item);
    }

    void editing(boolean value) {
        editing = value;
        int index = 0;
        for (Item item : items) {
            item.remove.setVisibility(value ? VISIBLE : GONE);
            if (item.wobble != null) item.wobble.cancel();
            item.wobble = null;
            item.setRotation(0);
            if (!value || !ValueAnimator.areAnimatorsEnabled()) continue;
            item.remove.setScaleX(0);
            item.remove.setScaleY(0);
            item.remove.animate().scaleX(1).scaleY(1).setStartDelay(index * 25L).setDuration(220).start();
            float angle = item.wide ? .28f : .55f;
            ValueAnimator wobble = ValueAnimator.ofFloat(-angle, angle);
            wobble.setDuration(155 + (index % 3) * 18L);
            wobble.setRepeatMode(ValueAnimator.REVERSE);
            wobble.setRepeatCount(ValueAnimator.INFINITE);
            wobble.addUpdateListener(a -> item.setRotation((Float) a.getAnimatedValue()));
            wobble.start();
            item.wobble = wobble;
            index++;
        }
    }

    @Override protected void onDetachedFromWindow() {
        for (Item item : items) if (item.wobble != null) item.wobble.cancel();
        removeCallbacks(pickUp);
        removeCallbacks(edgeScroll);
        held = null;
        super.onDetachedFromWindow();
    }

    // ---- layout -------------------------------------------------------------------------------

    @Override protected void onMeasure(int widthSpec, int heightSpec) {
        int width = MeasureSpec.getSize(widthSpec), half = (width - gap) / 2;
        int y = 0, column = 0, rowHeight = 0;
        for (Item item : items) {
            int wide = item.wide ? width : half;
            item.measure(MeasureSpec.makeMeasureSpec(wide, MeasureSpec.EXACTLY), item.fixed > 0
                    ? MeasureSpec.makeMeasureSpec(item.fixed, MeasureSpec.EXACTLY)
                    : MeasureSpec.makeMeasureSpec(0, MeasureSpec.UNSPECIFIED));
            int height = item.getMeasuredHeight();
            if (item.wide && column == 1) {
                y += rowHeight + gap;
                column = 0;
            }
            int left = column == 1 ? width - half : 0;
            item.place.set(left, y, left + wide, y + height);
            if (item.wide) y += height + gap;
            else if (column == 0) {
                rowHeight = height;
                column = 1;
            } else {
                y += Math.max(rowHeight, height) + gap;
                column = 0;
            }
        }
        if (column == 1) y += rowHeight + gap;
        setMeasuredDimension(width, Math.max(0, y - gap));
    }

    @Override protected void onLayout(boolean changed, int l, int t, int r, int b) {
        for (Item item : items) {
            float fromX = item.getLeft() + item.getTranslationX(), fromY = item.getTop() + item.getTranslationY();
            boolean known = item.getWidth() > 0;
            item.layout(item.place.left, item.place.top, item.place.right, item.place.bottom);
            if (item == held) {
                continue;
            }
            if (settling && known && (fromX != item.place.left || fromY != item.place.top)) {
                item.setTranslationX(fromX - item.place.left);
                item.setTranslationY(fromY - item.place.top);
                item.animate().translationX(0).translationY(0).setStartDelay(0).setDuration(250)
                        .setInterpolator(new DecelerateInterpolator(1.8f)).start();
            }
        }
        settling = false;
        if (held != null) follow(0, 0);
    }

    // ---- dragging -----------------------------------------------------------------------------

    private ScrollView scroller() {
        for (android.view.ViewParent p = getParent(); p != null; p = p.getParent())
            if (p instanceof ScrollView) return (ScrollView) p;
        return null;
    }

    private Item at(float x, float y) {
        for (Item item : items) if (item != held && item.place.contains((int) x, (int) y)) return item;
        return null;
    }

    @Override public boolean onInterceptTouchEvent(MotionEvent event) {
        if (!editing || event.getActionMasked() != MotionEvent.ACTION_DOWN) return false;
        Item item = at(event.getX(), event.getY());
        if (item == null) return false;
        // The remove button keeps its own tap.
        boolean onRemove = event.getX() > item.place.right - u.d(46) && event.getY() < item.place.top + u.d(46);
        return !onRemove;
    }

    @Override public boolean onTouchEvent(MotionEvent event) {
        if (!editing) return false;
        lastRawY = event.getRawY();
        switch (event.getActionMasked()) {
            case MotionEvent.ACTION_DOWN:
                downX = event.getX();
                downY = event.getY();
                touched = at(downX, downY);
                if (touched != null) postDelayed(pickUp, 200);
                return touched != null;
            case MotionEvent.ACTION_MOVE:
                if (held != null) {
                    follow(event.getX() - downX, event.getY() - downY);
                    reorder();
                } else if (Math.abs(event.getX() - downX) > slop || Math.abs(event.getY() - downY) > slop) {
                    removeCallbacks(pickUp);
                }
                return true;
            case MotionEvent.ACTION_UP:
            case MotionEvent.ACTION_CANCEL:
                removeCallbacks(pickUp);
                drop();
                return true;
            default:
                return true;
        }
    }

    private float dragX, dragY;

    private void lift() {
        if (touched == null || !editing) return;
        held = touched;
        heldLeft = held.getLeft();
        heldTop = held.getTop();
        dragX = 0;
        dragY = 0;
        getParent().requestDisallowInterceptTouchEvent(true);
        performHapticFeedback(HapticFeedbackConstants.LONG_PRESS);
        if (held.wobble != null) held.wobble.cancel();
        held.setRotation(0);
        held.animate().cancel();
        held.animate().scaleX(1.05f).scaleY(1.05f).setStartDelay(0).setDuration(140).start();
        held.setTranslationZ(u.d(16));
        held.setAlpha(.95f);
        postOnAnimation(edgeScroll);
    }

    /** The held widget stays under the finger wherever the grid has put its slot. */
    private void follow(float x, float y) {
        if (held == null) return;
        if (x != 0 || y != 0) {
            dragX = x;
            dragY = y;
        }
        held.setTranslationX(heldLeft + dragX - held.getLeft());
        held.setTranslationY(heldTop + dragY - held.getTop());
    }

    private void reorder() {
        // One step at a time, so the others have arrived before the next one moves.
        if (android.os.SystemClock.uptimeMillis() - movedAt < 270) return;
        float x = heldLeft + dragX + held.getWidth() / 2f, y = heldTop + dragY + held.getHeight() / 2f;
        Item target = at(x, y);
        if (target == null) return;
        int from = items.indexOf(held), to = items.indexOf(target);
        if (from == to) return;
        items.remove(from);
        items.add(to, held);
        movedAt = android.os.SystemClock.uptimeMillis();
        settling = true;
        performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK);
        requestLayout();
    }

    private void drop() {
        removeCallbacks(edgeScroll);
        touched = null;
        Item item = held;
        if (item == null) return;
        held = null;
        item.animate().translationX(0).translationY(0).scaleX(1).scaleY(1).alpha(1).setStartDelay(0).setDuration(230)
                .setInterpolator(new DecelerateInterpolator(1.8f)).withEndAction(() -> {
                    item.setTranslationZ(0);
                    if (editing && item.wobble != null) item.wobble.start();
                }).start();
        List<String> ids = new ArrayList<>();
        for (Item each : items) ids.add(each.id);
        reordered.accept(ids);
    }
}
