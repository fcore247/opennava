package site.navatv.companion;

import static site.navatv.companion.Surface.*;

import android.widget.LinearLayout;
import android.widget.TextView;

/** Everything that is set up once, out of the way of watching. */
final class SettingsPage {
    private final MainActivity a;
    private final Surface u;

    SettingsPage(MainActivity activity) {
        a = activity;
        u = a.ui;
    }

    private LinearLayout card;

    void build() {
        chapter("Your TV");
        row("cast", "Connection", a.connected() ? a.device.name : "Connect a TV", () -> a.go("devices"));
        row("apps", "TV apps", "Open any app installed on your TV", () -> a.go("apps"));
        row("power", "Standby / Wake", "Switch the TV on or off", () -> a.sendKey(26));
        chapter("Account");
        row("account", "YouTube account", a.accountSummary(), () -> a.go("account"));
        chapter("Appearance");
        row("menu", "Language and country", ("de".equals(Region.language(a)) ? "Deutsch" : "English")
                + "  ·  " + Region.countryName(Region.country(a)), this::chooseRegion);
        row("saved", "Background images", a.backgroundSummary(), () -> a.go("backgrounds"));
        row("home", a.getString(R.string.home_menu), a.getString(R.string.home_title), () -> a.go("personalization"));
        chapter("Content");
        row("library", "My libraries", "Add your own media libraries", () -> a.go("libraries"));
        row("onair", "My streams", "Add your own live channels", () -> a.go("streams"));
        row("saved", "Recordings", "Recordings and reminders", () -> a.go("recordings"));
        chapter("App");
        row("forward", "Software updates", "Keep the phone and TV current", a::openUpdates);
        row("scan", "Run setup again", "Connect a TV and take the tour", a::runSetup);
    }

    private void chooseRegion() {
        Sheet sheet = new Sheet(a, .8f);
        sheet.onClose(() -> {
            a.sendRegion();
            a.render();
        });
        sheet.show(L.t("Language and country"), body -> {
            heading(body, "LANGUAGE");
            for (String code : new String[] {"de", "en"})
                choice(sheet, body, "de".equals(code) ? "Deutsch" : "English", code.equals(Region.language(a)),
                        () -> Region.save(a, code, Region.country(a)));
            heading(body, "COUNTRY");
            for (String code : new String[] {"de", "at", "ch", "us"})
                choice(sheet, body, Region.countryName(code), code.equals(Region.country(a)),
                        () -> Region.save(a, Region.language(a), code));
        });
    }

    private void heading(LinearLayout body, String name) {
        u.space(body, 12);
        TextView label = u.text(name, 12, LILAC, 800);
        u.pad(label, 6, 0, 0, 0);
        body.addView(label);
        u.space(body, 8);
    }

    private void choice(Sheet sheet, LinearLayout body, String name, boolean selected, Runnable pick) {
        LinearLayout row = u.row();
        u.pad(row, 16, 0, 14, 0);
        row.addView(u.text(name, 16, INK, 700), u.weight(WRAP));
        if (selected) row.addView(new Art(a, "ok", LILAC), u.lp(18, 18));
        u.action(row, selected ? 0x26cabaff : PALE, 18, 0, () -> {
            pick.run();
            a.regionChanged();
            sheet.refresh();
        });
        row.setContentDescription(name + (selected ? ", selected" : ""));
        body.addView(row, u.lp(MATCH, 52));
        u.space(body, 8);
    }

    /** Each chapter is one card with its entries inside. */
    private void chapter(String name) {
        u.space(a.body, 18);
        TextView label = u.text(L.t(name).toUpperCase(java.util.Locale.ROOT), 12, LILAC, 800);
        label.setLetterSpacing(.08f);
        label.setContentDescription(name);
        label.setAccessibilityHeading(true);
        u.pad(label, 8, 0, 0, 0);
        a.body.addView(label);
        u.space(a.body, 9);
        card = u.column();
        card.setBackground(u.shape(CARD, 24, 0));
        u.pad(card, 6, 6, 6, 6);
        a.body.addView(card, u.lp(MATCH, WRAP));
    }

    private void row(String icon, String title, String detail, Runnable run) {
        if (card.getChildCount() > 0) {
            LinearLayout.LayoutParams line = u.lp(MATCH, 1);
            line.setMarginStart(u.d(62));
            line.setMarginEnd(u.d(12));
            card.addView(u.rule(), line);
        }
        LinearLayout row = u.row();
        u.pad(row, 10, 11, 12, 11);
        row.setMinimumHeight(u.d(68));
        android.widget.FrameLayout tile = new android.widget.FrameLayout(a);
        tile.setBackground(u.shape(PALE, 13, 0));
        tile.addView(new Art(a, icon, LILAC),
                new android.widget.FrameLayout.LayoutParams(u.d(21), u.d(21), android.view.Gravity.CENTER));
        row.addView(tile, u.lp(40, 40));
        u.gap(row, 12);
        LinearLayout words = u.column();
        words.addView(u.text(title, 16, INK, 700));
        u.space(words, 4);
        words.addView(u.text(detail, 13, MUTED, 520));
        row.addView(words, u.weight(WRAP));
        row.addView(new Art(a, "right", MUTED), u.lp(15, 15));
        u.action(row, 0, 18, 0, run);
        row.setContentDescription(L.t(title) + ". " + L.t(detail));
        card.addView(row);
    }
}
