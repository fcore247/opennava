package site.navatv.companion;

import static site.navatv.companion.Surface.*;

import android.app.Dialog;
import android.graphics.drawable.ColorDrawable;
import android.graphics.drawable.GradientDrawable;
import android.view.Gravity;
import android.view.Window;
import android.view.WindowManager;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.function.Consumer;

/** A panel that rises from the bottom. Submenus replace its content and Back returns one level. */
final class Sheet {
    private final MainActivity a;
    private final Surface u;
    private final Dialog dialog;
    private final TextView title;
    private final FrameLayout back;
    private final LinearLayout body;
    private final ScrollView scroll;
    private final Deque<Page> trail = new ArrayDeque<>();
    private Runnable onClose;

    private static final class Page {
        final String title;
        final Consumer<LinearLayout> content;
        Page(String title, Consumer<LinearLayout> content) {
            this.title = title;
            this.content = content;
        }
    }

    Sheet(MainActivity activity, float screenShare) {
        a = activity;
        u = a.ui;
        dialog = new Dialog(a, android.R.style.Theme_Material_Dialog_NoActionBar);
        dialog.requestWindowFeature(Window.FEATURE_NO_TITLE);
        LinearLayout panel = u.column();
        GradientDrawable shape = new GradientDrawable();
        shape.setColor(0xff161a25);
        float r = u.d(30);
        shape.setCornerRadii(new float[] {r, r, r, r, 0, 0, 0, 0});
        panel.setBackground(shape);
        android.view.View handle = new android.view.View(a);
        handle.setBackground(u.shape(0xff454b5e, 3, 0));
        LinearLayout.LayoutParams grip = u.lp(42, 5);
        grip.gravity = Gravity.CENTER_HORIZONTAL;
        grip.topMargin = u.d(10);
        panel.addView(handle, grip);
        LinearLayout top = u.row();
        u.pad(top, 12, 6, 12, 2);
        back = iconButton("left", "Back one level", this::back);
        top.addView(back, u.lp(48, 48));
        title = u.text("", 19, INK, 750);
        title.setSingleLine();
        title.setEllipsize(android.text.TextUtils.TruncateAt.END);
        u.pad(title, 6, 0, 6, 0);
        top.addView(title, u.weight(48));
        top.addView(iconButton("close", "Close", dialog::dismiss), u.lp(48, 48));
        panel.addView(top);
        scroll = new ScrollView(a);
        scroll.setVerticalScrollBarEnabled(false);
        body = u.column();
        u.pad(body, 18, 6, 18, 28);
        scroll.addView(body);
        panel.addView(scroll, new LinearLayout.LayoutParams(MATCH, 0, 1));
        dialog.setContentView(panel);
        dialog.setOnKeyListener((d, key, event) -> {
            if (key != android.view.KeyEvent.KEYCODE_BACK || trail.size() < 2) return false;
            if (event.getAction() == android.view.KeyEvent.ACTION_UP) back();
            return true;
        });
        dialog.setOnDismissListener(d -> { if (onClose != null) onClose.run(); });
        dialog.show();
        Window window = dialog.getWindow();
        if (window != null) {
            window.setBackgroundDrawable(new ColorDrawable(0));
            window.getDecorView().setPadding(0, 0, 0, 0);
            window.setGravity(Gravity.BOTTOM);
            window.setLayout(WindowManager.LayoutParams.MATCH_PARENT,
                    Math.round(a.getResources().getDisplayMetrics().heightPixels * screenShare));
            window.setDimAmount(.62f);
            window.setNavigationBarColor(0xff161a25);
        }
    }

    private FrameLayout iconButton(String icon, String label, Runnable run) {
        FrameLayout button = new FrameLayout(a);
        button.addView(new Art(a, icon, INK), new FrameLayout.LayoutParams(u.d(20), u.d(20), Gravity.CENTER));
        u.action(button, 0, 24, 0, run);
        button.setContentDescription(label);
        return button;
    }

    /** Open a level. The content may be redrawn later with {@link #refresh}. */
    void show(String name, Consumer<LinearLayout> content) {
        trail.push(new Page(name, content));
        refresh();
        scroll.scrollTo(0, 0);
    }

    void refresh() {
        Page page = trail.peek();
        if (page == null || !dialog.isShowing()) return;
        title.setText(page.title);
        back.setVisibility(trail.size() > 1 ? android.view.View.VISIBLE : android.view.View.INVISIBLE);
        body.removeAllViews();
        page.content.accept(body);
    }

    private void back() {
        if (trail.size() < 2) return;
        trail.pop();
        refresh();
        scroll.scrollTo(0, 0);
    }

    boolean showing() { return dialog.isShowing(); }

    void onClose(Runnable run) { onClose = run; }

    void dismiss() { dialog.dismiss(); }
}
