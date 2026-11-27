package site.navatv.companion;

import static site.navatv.companion.Surface.*;

import android.animation.ValueAnimator;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.Rect;
import android.graphics.RectF;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.animation.DecelerateInterpolator;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.TextView;

/** A walk through the real app: the page dims and one thing at a time stays lit. */
final class Tour {
    private static final class Stop {
        final String title, text;
        /** Content descriptions of the views that stay lit; none means a card in the middle. */
        final String[] targets;
        Stop(String title, String text, String... targets) {
            this.title = title;
            this.text = text;
            this.targets = targets;
        }
    }

    private static final Stop[] STOPS = {
            new Stop("Live TV", "See what is on right now. Tap to open all channels, then send one to your TV.",
                    "Open live TV"),
            new Stop("Libraries and NavaTube", "Browse ARD, ZDF and ARTE or NavaTube here and press play. It starts on the big screen.",
                    "Open our media libraries", "Open NavaTube"),
            new Stop("Make it yours", "Tap the pencil to move tiles around, remove them, or add any app from your TV.",
                    "Edit start page"),
            new Stop("All channels", "Spin through your channels and the TV guide.", "Live"),
            new Stop("All libraries", "Search every library and your own ones.", "Library"),
            new Stop("Your remote", "Arrows, volume and typing. Home takes your TV back to its start page.", "Remote"),
            new Stop("The mini player", "While something plays, a small player floats above the tabs. Tap it to open it, or close it with the cross."),
            new Stop("Ask your AI agent to set up your library", "For example: “Add this video feed to OpenNava.” Your agent needs a computer that is allowed to reach your TV.")};

    private final MainActivity a;
    private final Surface u;
    private Overlay overlay;
    private int index = -1;

    Tour(MainActivity activity) {
        a = activity;
        u = a.ui;
    }

    boolean running() { return index >= 0; }

    void start() {
        index = 0;
        overlay = null;
        attach();
        a.reportTourStep(index + 1);
    }

    /** The page below may be rebuilt at any time; the tour then lays itself over the new one. */
    void attach() {
        if (!running()) return;
        ViewGroup content = a.findViewById(android.R.id.content);
        if (overlay == null) overlay = new Overlay();
        if (overlay.getParent() != null) ((ViewGroup) overlay.getParent()).removeView(overlay);
        content.addView(overlay, new ViewGroup.LayoutParams(MATCH, MATCH));
        overlay.postDelayed(() -> { if (running() && overlay != null) overlay.aim(false); }, 380);
    }

    private void next() {
        if (!running()) return;
        if (index == STOPS.length - 1) {
            finish();
            return;
        }
        index++;
        overlay.aim(true);
        a.reportTourStep(index + 1);
    }

    void finish() {
        if (!running()) return;
        index = -1;
        Overlay leaving = overlay;
        overlay = null;
        if (leaving != null) leaving.animate().alpha(0).setDuration(240).withEndAction(() -> {
            if (leaving.getParent() != null) ((ViewGroup) leaving.getParent()).removeView(leaving);
        }).start();
        a.finishTvSetup();
    }

    private static View find(View view, String description) {
        CharSequence own = view.getContentDescription();
        if (own != null && description.contentEquals(own)) return view;
        if (view instanceof ViewGroup) {
            ViewGroup group = (ViewGroup) view;
            for (int i = 0; i < group.getChildCount(); i++) {
                View found = find(group.getChildAt(i), description);
                if (found != null) return found;
            }
        }
        return null;
    }

    private final class Overlay extends FrameLayout {
        private final Paint dim = new Paint(Paint.ANTI_ALIAS_FLAG), ring = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final RectF hole = new RectF();
        private final LinearLayout card = u.column();
        private final TextView count, title, text, button;
        private ValueAnimator travel;

        Overlay() {
            super(a);
            setWillNotDraw(false);
            // Touches stay with the tour until it is over.
            setClickable(true);
            dim.setColor(0xdc07070f);
            ring.setStyle(Paint.Style.STROKE);
            ring.setStrokeWidth(u.d(2));
            ring.setColor(LILAC);
            card.setBackground(u.shape(0xff201d36, 28, 0x55cabaff));
            u.pad(card, 22, 20, 22, 14);
            card.setElevation(u.d(14));
            count = u.text("", 11.5f, LILAC, 820);
            count.setLetterSpacing(.1f);
            card.addView(count);
            u.space(card, 9);
            title = u.title("", 31, WHITE);
            card.addView(title);
            u.space(card, 8);
            text = u.text("", 14.5f, 0xffc4c6d8, 520);
            text.setLineSpacing(u.d(3), 1);
            card.addView(text);
            u.space(card, 16);
            LinearLayout row = u.row();
            TextView skip = u.text("Skip tour", 13.5f, MUTED, 700);
            u.pad(skip, 4, 12, 14, 12);
            skip.setOnClickListener(v -> finish());
            skip.setFocusable(true);
            row.addView(skip);
            row.addView(new View(a), u.weight(1));
            button = u.text("Next", 15, ON_LILAC, 800);
            button.setGravity(Gravity.CENTER);
            u.pad(button, 28, 0, 28, 0);
            u.action(button, LILAC, 23, 0, Tour.this::next);
            row.addView(button, u.lp(WRAP, 46));
            card.addView(row, u.lp(MATCH, WRAP));
            FrameLayout.LayoutParams place = new FrameLayout.LayoutParams(MATCH, WRAP, Gravity.TOP);
            place.setMargins(u.d(18), 0, u.d(18), 0);
            addView(card, place);
            card.setAlpha(0);
        }

        /** Moves the light and the card to the current stop. */
        void aim(boolean animate) {
            if (!running() || getWidth() == 0) {
                if (running()) postDelayed(() -> aim(animate), 60);
                return;
            }
            Stop stop = STOPS[index];
            count.setText((L.german ? "SCHRITT " : "STEP ") + (index + 1) + (L.german ? " VON " : " OF ") + STOPS.length);
            title.setText(L.t(stop.title));
            // The American line-up has no German broadcasters to mention.
            String words = index == 1 && "us".equals(Region.country(a))
                    ? "Browse your libraries or NavaTube here and press play. It starts on the big screen." : stop.text;
            text.setText(L.t(words));
            button.setText(L.t(index == STOPS.length - 1 ? "Done" : "Next"));
            RectF goal = new RectF();
            int[] mine = new int[2], theirs = new int[2];
            getLocationInWindow(mine);
            View page = ((ViewGroup) getParent()).getChildAt(0);
            for (String name : stop.targets) {
                View target = find(page, name);
                if (target == null || target.getWidth() == 0) continue;
                target.getLocationInWindow(theirs);
                Rect visible = new Rect();
                if (!target.getGlobalVisibleRect(visible)) continue;
                RectF box = new RectF(theirs[0] - mine[0], theirs[1] - mine[1],
                        theirs[0] - mine[0] + target.getWidth(), theirs[1] - mine[1] + target.getHeight());
                if (goal.isEmpty()) goal.set(box);
                else goal.union(box);
            }
            if (!goal.isEmpty()) goal.inset(-u.d(6), -u.d(6));
            else goal.set(getWidth() / 2f, getHeight() * .42f, getWidth() / 2f, getHeight() * .42f);
            measureChild(card, MeasureSpec.makeMeasureSpec(getWidth() - u.d(36), MeasureSpec.EXACTLY),
                    MeasureSpec.makeMeasureSpec(0, MeasureSpec.UNSPECIFIED));
            int height = card.getMeasuredHeight();
            float below = goal.bottom + u.d(18), above = goal.top - u.d(18) - height;
            float y = goal.width() == 0 ? (getHeight() - height) / 2f
                    : below + height < getHeight() - u.d(16) ? below : Math.max(u.d(16), above);
            if (travel != null) travel.cancel();
            RectF from = new RectF(hole.isEmpty() && !animate ? goal : hole);
            travel = ValueAnimator.ofFloat(0, 1);
            travel.setDuration(animate ? 460 : 1);
            travel.setInterpolator(new DecelerateInterpolator(2f));
            travel.addUpdateListener(animation -> {
                float f = (Float) animation.getAnimatedValue();
                hole.set(from.left + (goal.left - from.left) * f, from.top + (goal.top - from.top) * f,
                        from.right + (goal.right - from.right) * f, from.bottom + (goal.bottom - from.bottom) * f);
                invalidate();
            });
            travel.start();
            card.animate().cancel();
            if (card.getAlpha() == 0) card.setTranslationY(y + u.d(20));
            card.animate().alpha(1).translationY(y).setDuration(460).setInterpolator(new DecelerateInterpolator(2f)).start();
        }

        @Override protected void onDraw(Canvas canvas) {
            Path shade = new Path();
            shade.setFillType(Path.FillType.EVEN_ODD);
            shade.addRect(0, 0, getWidth(), getHeight(), Path.Direction.CW);
            float radius = Math.min(u.d(30), hole.height() / 2);
            if (!hole.isEmpty()) shade.addRoundRect(hole, radius, radius, Path.Direction.CW);
            canvas.drawPath(shade, dim);
            if (!hole.isEmpty()) canvas.drawRoundRect(hole, radius, radius, ring);
        }
    }
}
