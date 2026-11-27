package site.navatv.companion;

import static site.navatv.companion.Surface.*;

import android.text.Editable;
import android.text.InputType;
import android.text.TextWatcher;
import android.view.Gravity;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

import java.util.List;
import java.util.Locale;

/** Every app installed on the TV, as the TV shows it. A tap opens it there. */
final class AppsPage {
    private final MainActivity a;
    private final Surface u;
    private LinearLayout grid;
    private String filter = "";

    AppsPage(MainActivity activity) {
        a = activity;
        u = a.ui;
    }

    /** The TV banner fills the tile; an app without one shows its icon on a quiet ground. */
    static FrameLayout art(MainActivity a, Surface u, TvApps.Entry app, int radius) {
        int hue = Math.floorMod(app.id.hashCode(), 360);
        int top = android.graphics.Color.HSVToColor(new float[] {hue, .38f, .36f});
        int bottom = android.graphics.Color.HSVToColor(new float[] {(hue + 28) % 360, .42f, .19f});
        FrameLayout tile = HomeTile.frame(a, u, radius, new int[] {top, bottom});
        TextView letter = u.text(app.name.substring(0, app.name.offsetByCodePoints(0, 1)).toUpperCase(Locale.ROOT), 26, 0x88ffffff, 800);
        letter.setGravity(Gravity.CENTER);
        tile.addView(letter, new FrameLayout.LayoutParams(MATCH, MATCH));
        ImageView picture = new ImageView(a);
        if (app.banner) {
            picture.setScaleType(ImageView.ScaleType.CENTER_CROP);
            tile.addView(picture, new FrameLayout.LayoutParams(MATCH, MATCH));
        } else {
            picture.setScaleType(ImageView.ScaleType.FIT_CENTER);
            picture.setBackgroundColor(0);
            tile.addView(picture, new FrameLayout.LayoutParams(u.d(52), u.d(52), Gravity.CENTER));
        }
        a.apps.picture(app, picture);
        picture.addOnLayoutChangeListener((v, l, t, r, b, ol, ot, or, ob) -> {
            if (picture.getDrawable() != null) letter.setVisibility(android.view.View.INVISIBLE);
        });
        return tile;
    }

    void build() {
        a.apps.refresh(false);
        u.space(a.body, 4);
        if (!a.connected()) {
            a.body.addView(u.text("Connect your TV to see the apps installed on it.", 15, MUTED, 520));
            u.space(a.body, 14);
            a.body.addView(u.button("Connect a TV", "cast", true, () -> a.go("devices")), u.lp(MATCH, 56));
            return;
        }
        List<TvApps.Entry> apps = a.apps.list();
        a.body.addView(u.text(apps.isEmpty() ? "Installed on " + a.device.name
                : apps.size() + " apps on " + a.device.name + " · Tap to open", 14, MUTED, 540));
        u.space(a.body, 13);
        if (apps.size() > 6) {
            EditText search = a.field("Search apps", InputType.TYPE_CLASS_TEXT, filter);
            search.setSelectAllOnFocus(false);
            search.setBackground(u.shape(CARD, 26, 0));
            u.pad(search, 22, 0, 22, 0);
            search.addTextChangedListener(new TextWatcher() {
                @Override public void beforeTextChanged(CharSequence s, int start, int count, int after) {}
                @Override public void onTextChanged(CharSequence s, int start, int before, int count) {}
                @Override public void afterTextChanged(Editable text) {
                    filter = text.toString();
                    fill();
                }
            });
            a.body.addView(search, u.lp(MATCH, 52));
            u.space(a.body, 16);
        } else filter = "";
        grid = u.column();
        a.body.addView(grid, u.lp(MATCH, WRAP));
        fill();
        u.space(a.body, 18);
        a.body.addView(u.button(a.apps.loading() ? "Asking your TV…" : "Refresh list", "tv", false,
                () -> { a.apps.refresh(true); a.render(); }), u.lp(MATCH, 52));
    }

    private void fill() {
        grid.removeAllViews();
        String wanted = filter.trim().toLowerCase(Locale.ROOT);
        int columns = a.getResources().getConfiguration().screenWidthDp >= 600 ? 3 : 2;
        int width = Math.min(a.getResources().getDisplayMetrics().widthPixels, u.d(550)) - 2 * u.d(20);
        int tile = (width - (columns - 1) * u.d(12)) / columns;
        LinearLayout line = null;
        int shown = 0;
        for (TvApps.Entry app : a.apps.list()) {
            if (!app.name.toLowerCase(Locale.ROOT).contains(wanted)) continue;
            if (shown % columns == 0) {
                line = u.row();
                line.setGravity(Gravity.TOP);
                grid.addView(line, u.lp(MATCH, WRAP));
                u.space(grid, 14);
            }
            LinearLayout cell = u.column();
            cell.addView(art(a, u, app, 18), new LinearLayout.LayoutParams(tile, tile * 9 / 16));
            u.space(cell, 8);
            TextView name = u.text(app.name, 13.5f, INK, 680);
            name.setSingleLine();
            name.setEllipsize(android.text.TextUtils.TruncateAt.END);
            u.pad(name, 4, 0, 4, 2);
            cell.addView(name, new LinearLayout.LayoutParams(tile, WRAP));
            HomeTile.press(cell, "Open " + app.name + " on your TV", () -> a.openApp(app));
            LinearLayout.LayoutParams place = new LinearLayout.LayoutParams(tile, WRAP);
            if (shown % columns > 0) place.leftMargin = u.d(12);
            line.addView(cell, place);
            shown++;
        }
        if (shown == 0) {
            String text = a.apps.loading() ? "Asking your TV for its apps…"
                    : !a.apps.problem.isEmpty() ? a.apps.problem
                    : wanted.isEmpty() ? "No apps reported yet." : "No app matches your search.";
            grid.addView(u.text(text, 15, MUTED, 560));
        }
    }
}
