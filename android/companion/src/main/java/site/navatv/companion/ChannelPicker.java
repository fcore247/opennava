package site.navatv.companion;

import static site.navatv.companion.Surface.*;

import android.app.Dialog;
import android.view.Gravity;
import android.view.View;
import android.view.Window;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.TextView;

import site.navatv.launcher.PhoneGuide.Channel;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;

/** The wheel previews locally; only the confirmation passes a station to the live or guide page. */
final class ChannelPicker {
    private final MainActivity a;
    private final Surface u;
    private final List<Channel> channels;
    private final Dialog dialog;
    private final boolean tune;
    private final Consumer<Channel> select;
    private final String[] remembered = new String[2];
    private boolean regional;
    private ChannelWheel wheel;
    private TextView mainTab, regionalTab;
    private LinearLayout confirm;

    static Dialog show(MainActivity a, List<Channel> channels, String selectedId, boolean tune,
                       Consumer<Channel> select) {
        if (a.isFinishing() || a.isDestroyed() || channels.isEmpty()) return null;
        ChannelPicker picker = new ChannelPicker(a, channels, selectedId, tune, select);
        picker.open();
        return picker.dialog;
    }

    private ChannelPicker(MainActivity a, List<Channel> channels, String selectedId, boolean tune,
                          Consumer<Channel> select) {
        this.a = a;
        u = a.ui;
        this.channels = channels;
        this.tune = tune;
        this.select = select;
        Channel initial = channels.get(0);
        for (Channel candidate : channels) if (candidate.id.equals(selectedId)) initial = candidate;
        regional = initial.regional;
        remembered[regional ? 1 : 0] = initial.id;
        dialog = new Dialog(a, android.R.style.Theme_Material_Light_NoActionBar);
    }

    private void open() {
        dialog.requestWindowFeature(Window.FEATURE_NO_TITLE);
        boolean compact = a.getResources().getConfiguration().screenHeightDp < 500;
        LinearLayout page = u.column();
        page.setBackgroundColor(PAPER);
        LinearLayout header = u.column();
        page.addView(header);
        LinearLayout top = u.row();
        boolean guide = a.page.equals("programme");
        top.addView(new Art(a, guide ? "schedule" : "onair", GREEN), u.lp(21, 21));
        u.gap(top, 8);
        top.addView(u.text(guide ? "TV guide" : "Live TV", 15, GREEN, 690), u.weight(WRAP));
        FrameLayout close = new FrameLayout(a);
        close.addView(new Art(a, "close", GREEN), new FrameLayout.LayoutParams(u.d(21), u.d(21), Gravity.CENTER));
        u.action(close, PALE, 26, LINE, dialog::dismiss);
        close.setContentDescription("Close");
        top.addView(close, u.lp(48, 48));
        header.addView(top);
        if (!compact) u.space(header, 16);
        TextView title = u.title("Choose channel", compact ? 32 : 48, INK);
        title.setAccessibilityHeading(true);
        header.addView(title);
        u.space(header, compact ? 5 : 19);
        LinearLayout tabs = u.row();
        tabs.setBackground(u.shape(PALE, 22, LINE));
        u.pad(tabs, 4, 4, 4, 4);
        mainTab = tab(tabs, "Main channels");
        regionalTab = tab(tabs, "Regional");
        header.addView(tabs);

        wheel = new ChannelWheel(a, this::preview);
        page.addView(wheel, new LinearLayout.LayoutParams(MATCH, 0, 1f));

        LinearLayout footer = u.column();
        confirm = u.row();
        confirm.setGravity(Gravity.CENTER);
        u.pad(confirm, 12, 9, 12, 9);
        u.action(confirm, GREEN, 20, 0, this::commit);
        confirm.setMinimumHeight(u.d(compact ? 48 : 60));
        confirm.addView(new Art(a, tune ? "tv" : "schedule", WHITE), u.lp(22, 22));
        u.gap(confirm, 10);
        confirm.addView(u.text(tune ? "Watch channel" : "View schedule", 17, WHITE, 680));
        footer.addView(confirm);
        page.addView(footer);

        page.addOnLayoutChangeListener((v, l, t, r, b, oldL, oldT, oldR, oldB) -> {
            if (r - l == oldR - oldL) return;
            int margin = Math.max(u.d(20), (r - l - u.d(540)) / 2);
            header.setPadding(margin, u.d(compact ? 3 : 12), margin, 0);
            wheel.setPadding(margin, 0, margin, 0);
            footer.setPadding(margin, u.d(4), margin, u.d(compact ? 6 : 18));
        });
        dialog.setContentView(page);
        Window window = dialog.getWindow();
        window.setStatusBarColor(PAPER);
        window.setNavigationBarColor(PAPER);
        window.getDecorView().setSystemUiVisibility(View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR | View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR);
        dialog.show();
        window.setLayout(MATCH, MATCH);
        showGroup();
    }

    private TextView tab(LinearLayout tabs, String name) {
        TextView tab = u.text(name, 15, INK, 680);
        tab.setGravity(Gravity.CENTER);
        tab.setMinimumHeight(u.d(48));
        tabs.addView(tab, u.weight(WRAP));
        return tab;
    }

    private void styleTab(TextView tab, boolean group) {
        boolean active = regional == group;
        u.action(tab, active ? WHITE : 0, 18, active ? LINE : 0, () -> {
            if (regional == group) return;
            remembered[regional ? 1 : 0] = wheel.centered().id;
            regional = group;
            showGroup();
        });
        tab.setSelected(active);
        tab.setTextColor(active ? GREEN : MUTED);
    }

    private void showGroup() {
        styleTab(mainTab, false);
        styleTab(regionalTab, true);
        List<Channel> stations = new ArrayList<>();
        for (Channel station : channels) if (station.regional == regional) stations.add(station);
        wheel.show(stations, remembered[regional ? 1 : 0]);
    }

    private void preview(Channel channel) {
        String action = tune ? channel.name + " · Watch channel" : "View schedule for " + channel.name + "";
        confirm.setContentDescription(action);
    }

    private void commit() {
        Channel channel = wheel.centered();
        dialog.dismiss();
        select.accept(channel);
    }
}
