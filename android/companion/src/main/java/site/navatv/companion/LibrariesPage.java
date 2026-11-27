package site.navatv.companion;

import static site.navatv.companion.Surface.*;

import android.content.ClipData;
import android.content.ClipboardManager;
import android.text.InputType;
import android.text.TextUtils;
import android.view.Gravity;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.TextView;

import site.navatv.launcher.OwnLibrary;

/** The viewer's own libraries: by feed address here, or prepared by their AI agent on a computer. */
final class LibrariesPage {
    private static final String AGENT_REQUEST = "Add a media library to my OpenNava TV. Use the OpenNava MCP server "
            + "(tools/opennava_mcp.py from https://github.com/fcore247/opennava, see guide/MEDIATHEK_FORMAT.md). "
            + "The library I want: ";

    private final MainActivity a;
    private final Surface u;

    LibrariesPage(MainActivity activity) {
        a = activity;
        u = a.ui;
    }

    void build() {
        u.space(a.body, 4);
        a.body.addView(u.text("Your libraries appear next to ARD, ZDF and ARTE, on the TV and here. "
                + "Only add content you are allowed to watch.", 14, MUTED, 520));
        u.space(a.body, 16);
        for (OwnLibrary.Source source : a.hub.sources()) row(source);
        if (a.hub.sources().isEmpty()) {
            a.body.addView(u.text("No libraries of your own yet.", 15, INK, 650));
            u.space(a.body, 12);
        }
        card("Add a video feed", "Many providers publish a video feed (RSS). Paste its address.",
                "Add feed address", this::addDialog);
        card("No feed? Ask your AI agent", "With the OpenNava MCP server, an AI agent on your computer can build a "
                + "library from what a provider offers and send it to your TV.", "Copy a request for my agent", () -> {
            a.getSystemService(ClipboardManager.class)
                    .setPrimaryClip(ClipData.newPlainText("OpenNava", AGENT_REQUEST));
            a.say("Copied. Paste it to your agent and name the library you want.");
        });
    }

    private void card(String title, String detail, String action, Runnable run) {
        u.space(a.body, 6);
        LinearLayout card = u.column();
        card.setBackground(u.shape(CARD, 22, 0));
        u.pad(card, 18, 17, 18, 18);
        card.addView(u.text(title, 17, INK, 760));
        u.space(card, 7);
        card.addView(u.text(detail, 14, MUTED, 500));
        u.space(card, 14);
        card.addView(u.button(action, null, true, run), u.lp(MATCH, 52));
        a.body.addView(card);
        u.space(a.body, 6);
    }

    private void row(OwnLibrary.Source source) {
        LibraryHub.Info info = a.hub.info(source.id);
        LinearLayout row = u.row();
        row.setBackground(u.shape(CARD, 19, 0));
        u.pad(row, 14, 6, 6, 6);
        TextView badge = u.text(source.name.substring(0, 1).toUpperCase(java.util.Locale.ROOT), 18, WHITE, 800);
        badge.setGravity(Gravity.CENTER);
        badge.setBackground(u.shape(info.color, 13, 0));
        row.addView(badge, u.lp(42, 42));
        u.gap(row, 12);
        LinearLayout words = u.column();
        u.pad(words, 0, 8, 6, 8);
        words.addView(u.text(source.name, 16, INK, 740));
        u.space(words, 4);
        TextView where = u.text(source.url.isEmpty() ? "Sent by your agent" : source.url, 12, MUTED, 500);
        where.setSingleLine();
        where.setEllipsize(TextUtils.TruncateAt.MIDDLE);
        words.addView(where);
        u.action(words, 0, 12, 0, () -> a.openProvider(source.id));
        words.setContentDescription("Open " + source.name);
        row.addView(words, u.weight(WRAP));
        FrameLayout remove = new FrameLayout(a);
        remove.addView(new Art(a, "close", MUTED), new FrameLayout.LayoutParams(u.d(18), u.d(18), Gravity.CENTER));
        u.action(remove, 0, 14, 0, () -> a.homeDialog().setTitle(source.name)
                .setMessage("Remove this library from your TV?")
                .setNegativeButton("Keep", null)
                .setPositiveButton("Remove", (dialog, which) -> {
                    if (!a.connected()) { a.needConnection(); return; }
                    TvConnection tv = a.tv;
                    a.runTv(() -> { tv.removeLibrary(source.id); return null; }, done -> a.reloadLibraries());
                }).show());
        remove.setContentDescription("Remove " + source.name);
        row.addView(remove, u.lp(48, 48));
        a.body.addView(row);
        u.space(a.body, 9);
    }

    private void addDialog() {
        if (!a.connected()) {
            a.needConnection();
            return;
        }
        EditText address = a.field("https://… feed address", InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_URI, "");
        EditText name = a.field("Name (optional)", InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_CAP_WORDS, "");
        LinearLayout form = u.column();
        u.pad(form, 24, 8, 24, 0);
        form.addView(address);
        form.addView(name);
        a.homeDialog().setTitle("Add a video feed").setView(form).setNegativeButton("Cancel", null)
                .setPositiveButton("Check and add", (dialog, which) -> {
                    String url = address.getText().toString().trim();
                    String label = name.getText().toString().trim();
                    a.say("Checking the feed…");
                    a.browse.execute(() -> {
                        try {
                            OwnLibrary.source(label, "", url, "");
                            OwnLibrary.Content content = OwnLibrary.parse(LibraryHub.download(url));
                            OwnLibrary.Source source = OwnLibrary.source(
                                    label.isEmpty() ? content.name : label, "", url, "");
                            a.runOnUiThread(() -> save(source, content.all().size()));
                        } catch (Exception failed) {
                            a.runOnUiThread(() -> a.problem("This feed cannot be added. " + a.error(failed)));
                        }
                    });
                }).show();
    }

    private void save(OwnLibrary.Source source, int videos) {
        if (a.isFinishing() || a.isDestroyed()) return;
        TvConnection tv = a.tv;
        a.runTv(() -> { tv.putLibrary(source); return null; }, done -> {
            a.say(source.name + " added · " + videos + " videos");
            a.reloadLibraries();
        });
    }
}
