package site.navatv.companion;

import static site.navatv.companion.Surface.*;

import android.app.Dialog;
import android.graphics.Color;
import android.view.Gravity;
import android.view.View;
import android.view.Window;
import android.view.WindowManager;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.Space;
import android.widget.TextView;

/** The four activities and the full-screen navigation share the same destinations. */
final class HomePage {
    private final MainActivity a;
    private final Surface u;
    private static final int FOREST = 0xff183b30;
    private Dialog menu;

    HomePage(MainActivity activity) {
        a = activity;
        u = a.ui;
    }

    void build() {
        u.space(a.body, 10);
        TextView greeting = u.title("What would you like to do?", 48, INK);
        greeting.setAccessibilityHeading(true);
        greeting.setLineSpacing(0f, .98f);
        a.body.addView(greeting);
        u.space(a.body, 23);

        android.content.res.Configuration config = a.getResources().getConfiguration();
        int contentWidth = Math.min(510, config.screenWidthDp - 40);
        boolean stacked = (contentWidth - 11) / 2 - 29 < 112 * config.fontScale
                || config.fontScale > 1.25f;
        LinearLayout first = stacked ? u.column() : u.row();
        LinearLayout second = stacked ? u.column() : u.row();
        tile(first, stacked, "Live TV", "Choose channel", "tv", 0xff254c3e, WHITE,
                0xffd9edb7, 0xff2a4c37, () -> a.go("live"));
        separate(first, stacked);
        tile(first, stacked, "Libraries", "ARD · ZDF · ARTE", "library", 0xffeddbc6, 0xff3a332b,
                0xfffbf1e3, 0xff67492e, () -> a.go("catalog"));
        tile(second, stacked, "TV guide", "What's on and when", "schedule", 0xffdce7e7, 0xff243e41,
                0xffedf5f3, 0xff34585c, () -> a.go("programme"));
        separate(second, stacked);
        tile(second, stacked, "Recordings", "Recordings and reminders", "saved", 0xffeae2e8, 0xff413740,
                0xfff8eff3, 0xff6d4f63, () -> a.go("recordings"));
        a.body.addView(first);
        u.space(a.body, 11);
        a.body.addView(second);
        u.space(a.body, 14);
        a.body.addView(u.button(a.getString(R.string.home_edit), "home", false, () -> a.go("personalization")));
    }

    private void separate(LinearLayout row, boolean stacked) {
        if (stacked) u.space(row, 11); else u.gap(row, 11);
    }

    private void tile(LinearLayout row, boolean stacked, String label, String detail, String symbol,
                      int background, int ink, int iconBackground, int iconColor, Runnable click) {
        LinearLayout card = u.column();
        u.pad(card, 15, 16, 14, 16);
        u.action(card, background, 24, 0, click);
        card.setContentDescription(label + ". " + detail);
        card.setMinimumHeight(u.d(stacked ? 138 : 171));
        LinearLayout top = u.row();
        FrameLayout disc = new FrameLayout(a);
        disc.setBackground(u.shape(iconBackground, 15, 0));
        disc.addView(new Art(a, symbol, iconColor),
                new FrameLayout.LayoutParams(u.d(25), u.d(25), Gravity.CENTER));
        top.addView(disc, u.lp(44, 44));
        top.addView(new Space(a), u.weight(1));
        top.addView(new Art(a, "arrowUpRight", ink), u.lp(17, 17));
        card.addView(top);
        card.addView(new Space(a), new LinearLayout.LayoutParams(1, u.d(14), 1f));
        TextView title = u.title(label, 27, ink);
        card.addView(title);
        u.space(card, 3);
        card.addView(u.text(detail, 13, ink, 550));
        row.addView(card, stacked ? u.lp(MATCH, WRAP) : u.weight(WRAP));
    }

    boolean open() { return menu != null && menu.isShowing(); }

    void dismiss() {
        if (menu != null) menu.dismiss();
    }

    void show() {
        if (open()) return;
        Dialog dialog = new Dialog(a, android.R.style.Theme_Material_NoActionBar);
        menu = dialog;
        LinearLayout surface = u.column();
        surface.setBackgroundColor(FOREST);
        int margin = Math.max(21, (a.getResources().getConfiguration().screenWidthDp - 510) / 2);
        u.pad(surface, margin, 8, margin, 0);
        LinearLayout bar = u.row();
        TextView title = u.title("Menu", 43, WHITE);
        title.setAccessibilityHeading(true);
        bar.addView(title, u.weight(75));
        FrameLayout close = new FrameLayout(a);
        close.addView(new Art(a, "close", WHITE),
                new FrameLayout.LayoutParams(u.d(22), u.d(22), Gravity.CENTER));
        u.action(close, 0xff305245, 24, 0xff466456, dialog::dismiss);
        close.setContentDescription("Close menu");
        bar.addView(close, u.lp(48, 48));
        surface.addView(bar);
        ScrollView list = new ScrollView(a);
        list.setVerticalScrollBarEnabled(false);
        LinearLayout items = u.column();
        list.addView(items);
        u.space(items, 7);

        menuRow(items, "cast", "Connection", a.connected() ? a.device.name : "Connect a TV", () -> a.go("devices"));
        menuRow(items, "home", a.getString(R.string.home_menu), a.getString(R.string.home_title), () -> a.go("personalization"));
        menuRow(items, "remote", "Manual remote", "", () -> a.go("remote"));
        menuRow(items, "settings", "Software updates", "", a::openUpdates);
        separator(items);
        menuRow(items, "onair", "Live TV", "", () -> a.go("live"));
        menuRow(items, "schedule", "TV guide", "", () -> a.go("programme"));
        menuRow(items, "saved", "Recordings", "", () -> a.go("recordings"));
        separator(items);
        TextView libraries = u.text("Libraries", 16, 0xffd7e6ce, 650);
        libraries.setAccessibilityHeading(true);
        items.addView(libraries);
        u.space(items, 8);
        for (String id : new String[]{"ard", "zdf", "arte"}) {
            menuRow(items, "library", id.toUpperCase(java.util.Locale.GERMAN), "", () -> a.openProvider(id));
        }
        u.space(items, 20);
        surface.addView(list, new LinearLayout.LayoutParams(MATCH, 0, 1f));
        u.addHomeBar(surface, () -> {
            dismiss();
            a.returnHome();
        });
        dialog.setContentView(surface);
        dialog.setOnDismissListener(d -> {
            if (menu == dialog) menu = null;
            a.restoreMenuFocus();
        });
        dialog.show();
        Window window = dialog.getWindow();
        if (window != null) {
            window.setStatusBarColor(FOREST);
            window.setNavigationBarColor(FOREST);
            android.view.WindowInsetsController insets = window.getInsetsController();
            if (insets != null) insets.setSystemBarsAppearance(0,
                    android.view.WindowInsetsController.APPEARANCE_LIGHT_STATUS_BARS
                            | android.view.WindowInsetsController.APPEARANCE_LIGHT_NAVIGATION_BARS);
            window.setLayout(WindowManager.LayoutParams.MATCH_PARENT, WindowManager.LayoutParams.MATCH_PARENT);
        }
        close.requestFocus();
    }

    private void separator(LinearLayout items) {
        u.space(items, 12);
        View line = new View(a);
        line.setBackgroundColor(0xff466456);
        items.addView(line, u.lp(MATCH, 1));
        u.space(items, 13);
    }

    private void menuRow(LinearLayout items, String icon, String title, String detail, Runnable run) {
        LinearLayout row = u.row();
        u.pad(row, 8, 7, 7, 7);
        row.setMinimumHeight(u.d(detail.isEmpty() ? 52 : 64));
        row.addView(new Art(a, icon, 0xffd7e9bc), u.lp(24, 24));
        u.gap(row, 17);
        LinearLayout words = u.column();
        words.addView(u.text(title, 18, WHITE, 630));
        if (!detail.isEmpty()) {
            u.space(words, 3);
            words.addView(u.text(detail, 13, 0xffc2d3c7, 480));
        }
        row.addView(words, u.weight(WRAP));
        row.addView(new Art(a, "right", 0xffa9c2b1), u.lp(17, 17));
        u.action(row, Color.TRANSPARENT, 15, 0, () -> {
            dismiss();
            run.run();
        });
        row.setContentDescription(detail.isEmpty() ? title : title + ". " + detail);
        items.addView(row);
    }
}
