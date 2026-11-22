package site.navatv.companion;

import static site.navatv.companion.Surface.*;
import static site.navatv.companion.TvHomeLayout.*;

import android.app.AlertDialog;
import android.content.ClipData;
import android.content.Intent;
import android.net.Uri;
import android.os.Bundle;
import android.text.Editable;
import android.text.InputType;
import android.text.TextWatcher;
import android.view.DragEvent;
import android.view.Gravity;
import android.view.View;
import android.widget.ArrayAdapter;
import android.widget.EditText;
import android.widget.HorizontalScrollView;
import android.widget.LinearLayout;
import android.widget.ListView;
import android.widget.TextView;

import org.json.JSONObject;

import java.text.Collator;
import java.util.ArrayList;
import java.util.Collections;
import java.util.ConcurrentModificationException;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/** Edits a revision of the selected TV's layout; only Save commits it to that television. */
final class HomeEditorPage {
    private final MainActivity a;
    private final Surface u;
    private TvConnection.Device owner;
    private TvHomeLayout layout;
    private String openFolder, error;
    private boolean loading, saving, dirty, conflict, needsUpdate;
    private int generation;
    private String draftJson, draftTv, draftFolder;

    private String targetId(TvConnection.Device device) {
        return device.androidId.isEmpty() ? device.host + ":" + device.port + "/" + device.name : device.androidId;
    }

    void saveDraft(Bundle state) {
        rememberDraft();
        if (draftJson == null) return;
        state.putString("homeDraft", draftJson);
        state.putString("homeDraftTv", draftTv);
        state.putString("homeDraftFolder", draftFolder);
    }

    void restoreDraft(Bundle state) {
        draftJson = state.getString("homeDraft");
        draftTv = state.getString("homeDraftTv");
        draftFolder = state.getString("homeDraftFolder");
    }

    private void rememberDraft() {
        if (!dirty || layout == null || owner == null) return;
        try {
            draftJson = layout.request().toString();
            draftTv = targetId(owner);
            draftFolder = openFolder;
        } catch (org.json.JSONException ignored) {
            // A representable draft is required before the editor enables Save.
        }
    }

    private void applyDraft(TvConnection.Device target, TvHomeLayout current) {
        if (draftJson == null || !targetId(target).equals(draftTv)) return;
        try {
            JSONObject draft = new JSONObject(draftJson);
            // A lost save acknowledgement can leave an already applied revision in the phone draft.
            JSONObject actual = current.request();
            if (actual.getString("layout").equals(draft.getString("layout"))
                    && actual.getJSONArray("items").toString().equals(draft.getJSONArray("items").toString())) {
                draftJson = null;
                return;
            }
            org.json.JSONArray apps = new org.json.JSONArray();
            for (java.util.Map.Entry<String, String> entry : current.apps.entrySet())
                apps.put(new JSONObject().put("package",entry.getKey()).put("name",entry.getValue()));
            layout = new TvHomeLayout(current.authority, draft.put("apps", apps));
            openFolder = draftFolder;
            dirty = true;
            conflict = layout.revision != current.revision;
            if (conflict) error = text(R.string.home_conflict);
        } catch (org.json.JSONException invalid) {
            draftJson = null;
        }
    }

    HomeEditorPage(MainActivity activity) {
        a = activity;
        u = a.ui;
    }

    void enter() {
        // Reopening a clean editor reads the TV again. A local draft survives normal navigation.
        if (!dirty && !saving && !loading) clear();
    }

    private void clear() {
        generation++;
        owner = null;
        layout = null;
        openFolder = error = null;
        dirty = saving = loading = conflict = needsUpdate = false;
    }

    private String text(int id) { return a.getString(id); }

    private void refresh() {
        if (a.page.equals("personalization")) a.render();
    }

    void build() {
        if (a.connected() && owner != null && owner != a.device) {
            rememberDraft();
            clear();
        }
        a.body.addView(u.title(text(R.string.home_title), 42, INK));
        u.space(a.body, 10);
        if (!a.connected()) {
            a.body.addView(u.text(text(R.string.home_connect), 16, MUTED, 530));
            u.space(a.body, 18);
            action(R.string.home_device, "cast", true, () -> a.go("devices"));
            return;
        }
        a.body.addView(u.text(a.device.name, 15, MUTED, 580));
        u.space(a.body, 18);
        if (layout == null) {
            if (!loading && error == null) load();
            if (error == null) {
                a.body.addView(u.text(text(R.string.home_loading), 16, MUTED, 540));
            } else {
                a.body.addView(u.text(error, 16, INK, 540));
                u.space(a.body, 12);
                if (needsUpdate) {
                    action(R.string.home_update_link, "arrowUpRight", true, () -> {
                        try {
                            a.startActivity(new Intent(Intent.ACTION_VIEW,
                                    Uri.parse("https://opennava.site/handy/#rust-prototype")));
                        } catch (android.content.ActivityNotFoundException missing) {
                            a.say(text(R.string.home_download_unavailable));
                        }
                    });
                    u.space(a.body, 8);
                }
                action(R.string.home_retry, null, false, this::reload);
            }
            return;
        }

        Tile folder = layout.folder(openFolder);
        if (folder == null) {
            LinearLayout toggle = u.row();
            toggle.setBackground(u.shape(PALE, 18, LINE));
            u.pad(toggle, 4, 4, 4, 4);
            segment(toggle, false);
            segment(toggle, true);
            a.body.addView(toggle);
        } else {
            action(R.string.home_parent, "back", false, () -> { openFolder = null; refresh(); });
            u.space(a.body, 16);
            LinearLayout heading = u.row();
            heading.addView(u.title(folder.name, 32, INK), u.weight(WRAP));
            TextView more = u.text("•••", 18, GREEN, 720);
            more.setGravity(Gravity.CENTER);
            more.setContentDescription(text(R.string.home_folder_options));
            u.action(more, PALE, 18, LINE, () -> menu(folder, null));
            heading.addView(more, u.lp(48, 48));
            a.body.addView(heading);
        }
        u.space(a.body, 12);
        drawTiles(folder);

        u.space(a.body, 14);
        LinearLayout buttons = u.row();
        buttons.addView(u.button(text(R.string.home_add), "plus", false, () -> pickApps(folder)), u.weight(WRAP));
        if (folder == null) {
            u.gap(buttons, 8);
            buttons.addView(u.button(text(R.string.home_folder_new), "folder", false, this::newFolder), u.weight(WRAP));
        }
        a.body.addView(buttons);
        if (folder == null && layout.tiles.size() >= 2) {
            u.space(a.body, 10);
            a.body.addView(u.text(text(R.string.home_hint), 13, MUTED, 480));
        }
        u.space(a.body, 22);
        if (error != null) {
            a.body.addView(u.text(error, 15, ACCENT, 580));
            u.space(a.body, 10);
            if (conflict) {
                action(R.string.home_reload, "tv", false, () -> a.homeDialog().setMessage(R.string.home_discard)
                        .setNegativeButton(android.R.string.cancel, null).setPositiveButton(R.string.home_reload,
                                (d, w) -> reload()).show());
                return;
            }
        }
        if (dirty || saving) {
            a.body.addView(u.text(text(R.string.home_unsaved), 13, MUTED, 540));
            u.space(a.body, 7);
            View save = action(saving ? R.string.home_saving : R.string.home_save, "ok", true, this::save);
            save.setEnabled(!saving);
        }
    }

    boolean back() {
        if (openFolder == null) return false;
        openFolder = null;
        refresh();
        return true;
    }

    private void load() {
        owner = a.device;
        final TvConnection.Device target = owner;
        final int token = ++generation;
        loading = true;
        a.runTv(() -> a.tv.loadHome(target), value -> {
            if (generation != token) return;
            loading = false;
            layout = value;
            applyDraft(target, value);
            refresh();
        }, problem -> {
            if (generation != token) return;
            loading = false;
            needsUpdate = problem instanceof UnsupportedOperationException;
            error = a.error(problem);
            refresh();
        });
    }

    private void reload() {
        draftJson = null;
        clear();
        refresh();
    }

    private void edit() {
        dirty = true;
        if (!conflict) error = null;
        refresh();
    }

    private boolean editable() {
        return !saving && !conflict && layout != null && a.connected() && owner == a.device;
    }

    private void save() {
        if (!editable() || !dirty) return;
        final JSONObject request;
        try { request = layout.request(); }
        catch (org.json.JSONException bad) { a.problem(text(R.string.home_save_failed)); return; }
        final TvConnection.Device target = owner;
        final String authority = layout.authority;
        final int token = generation;
        saving = true;
        refresh();
        a.runTv(() -> a.tv.saveHome(target, authority, request), revision -> {
            if (token != generation) return;
            saving = dirty = false;
            draftJson = null;
            error = null;
            layout.revision = revision;
            a.say(text(R.string.home_saved));
            refresh();
        }, problem -> {
            if (token != generation) return;
            saving = false;
            conflict = problem instanceof ConcurrentModificationException;
            error = a.error(problem);
            refresh();
        });
    }

    private View action(int label, String icon, boolean primary, Runnable handler) {
        View button = u.button(text(label), icon, primary, handler);
        a.body.addView(button, u.lp(MATCH, WRAP));
        return button;
    }

    private void segment(LinearLayout group, boolean grid) {
        boolean selected = grid == layout.grid;
        TextView item = u.text(text(grid ? R.string.home_grid : R.string.home_row), 15, selected ? WHITE : GREEN, 650);
        item.setGravity(Gravity.CENTER);
        item.setSelected(selected);
        u.action(item, selected ? GREEN : PALE, 15, 0, () -> {
            if (editable() && layout.grid != grid) { layout.grid = grid; edit(); }
        });
        group.addView(item, u.weight(46));
    }

    private void drawTiles(Tile folder) {
        List<Tile> entries;
        if (folder == null) entries = layout.tiles;
        else {
            entries = new ArrayList<>();
            for (String pkg : folder.children) entries.add(Tile.app(pkg));
        }
        if (entries.isEmpty()) {
            TextView empty = u.text(text(folder == null ? R.string.home_empty : R.string.home_folder_empty), 15, MUTED, 530);
            empty.setGravity(Gravity.CENTER);
            empty.setMinimumHeight(u.d(94));
            empty.setBackground(u.shape(WHITE, 18, LINE));
            a.body.addView(empty);
            return;
        }

        if (!layout.grid && folder == null) {
            HorizontalScrollView scroll = new HorizontalScrollView(a);
            scroll.setHorizontalScrollBarEnabled(false);
            LinearLayout strip = u.row();
            for (Tile item : entries) {
                if (strip.getChildCount() != 0) u.gap(strip, 8);
                strip.addView(card(item, null), u.lp(116, WRAP));
            }
            scroll.addView(strip);
            a.body.addView(scroll);
        } else {
            int columns = a.getResources().getConfiguration().fontScale > 1.35f ? 2 : 3;
            for (int i = 0; i < entries.size(); i += columns) {
                LinearLayout row = u.row();
                for (int j = 0; j < columns; j++) {
                    if (j > 0) u.gap(row, 7);
                    if (i + j < entries.size()) row.addView(card(entries.get(i + j), folder), u.weight(WRAP));
                    else row.addView(new View(a), u.weight(0));
                }
                if (i > 0) u.space(a.body, 7);
                a.body.addView(row);
            }
        }
    }

    private LinearLayout card(Tile item, Tile parent) {
        String label = item.isFolder() ? item.name : layout.name(item.id);
        LinearLayout card = u.column();
        u.pad(card, 9, 11, 9, 10);
        card.setMinimumHeight(u.d(108));
        int[] colors = {0xffdae4dc, 0xffe5deee, 0xffdce7ef, 0xffefdecf};
        int color = item.isFolder() ? 0xffd9e7ce : colors[Math.floorMod(item.id.hashCode(), colors.length)];
        if (item.isFolder()) card.addView(new Art(a, "folder", GREEN), u.lp(28, 28));
        else {
            String first = label.isEmpty() ? "·" : label.substring(0, label.offsetByCodePoints(0, 1));
            TextView badge = u.text(first, 16, GREEN, 760);
            badge.setGravity(Gravity.CENTER);
            badge.setBackground(u.shape(0x99ffffff, 10, 0));
            card.addView(badge, u.lp(32, 32));
        }
        u.space(card, 8);
        TextView name = u.text(label, 14, INK, 700);
        name.setMaxLines(2);
        name.setEllipsize(android.text.TextUtils.TruncateAt.END);
        card.addView(name);
        if (item.isFolder()) card.addView(u.text(a.getString(R.string.home_folder_apps, item.children.size()), 12, MUTED, 500));
        else if (!layout.apps.containsKey(item.id)) card.addView(u.text(text(R.string.home_unknown_app), 12, MUTED, 500));
        u.action(card, color, 16, 0, () -> {
            if (item.isFolder()) { openFolder = item.id; refresh(); }
            else menu(item, parent);
        });
        card.setContentDescription(item.isFolder() ? label + ", " + a.getString(R.string.home_folder_apps, item.children.size()) : label);
        card.setOnLongClickListener(v -> editable() && v.startDragAndDrop(
                ClipData.newPlainText("", ""), new View.DragShadowBuilder(v), item, 0));
        card.setOnDragListener((v, e) -> {
            if (!(e.getLocalState() instanceof Tile) || !editable()) return false;
            Tile from = (Tile) e.getLocalState();
            switch (e.getAction()) {
                case DragEvent.ACTION_DRAG_STARTED: return !from.id.equals(item.id);
                case DragEvent.ACTION_DRAG_ENTERED: card.setAlpha(.65f); break;
                case DragEvent.ACTION_DRAG_EXITED: case DragEvent.ACTION_DRAG_ENDED: card.setAlpha(1); break;
                case DragEvent.ACTION_DROP:
                    card.setAlpha(1);
                    drop(from, item, parent);
                    break;
                default: break;
            }
            return true;
        });
        return card;
    }

    private void drop(Tile from, Tile to, Tile parent) {
        if (!editable() || from.id.equals(to.id)) return;
        if (parent != null) {
            int dest = parent.children.indexOf(to.id);
            if (dest < 0 || !parent.children.remove(from.id)) return;
            parent.children.add(dest, from.id);
        } else if (!layout.tiles.contains(from) || !layout.tiles.contains(to)) {
            return;
        } else if (from.isFolder()) {
            int dest = layout.tiles.indexOf(to);
            layout.tiles.remove(from);
            layout.tiles.add(dest, from);
        } else if (to.isFolder()) {
            move(from, null, to);
            return;
        } else {
            rename(text(R.string.home_folder_new), folderName -> {
                if (!editable() || !layout.tiles.contains(from) || !layout.tiles.contains(to)) return;
                Tile folder = Tile.folder(folderName);
                folder.children.add(to.id);
                folder.children.add(from.id);
                layout.tiles.set(layout.tiles.indexOf(to), folder);
                layout.tiles.remove(from);
                edit();
            });
            return;
        }
        edit();
    }

    private void newFolder() {
        if (!editable()) return;
        if (layout.tiles.size() >= MAX_TOP) { a.say(text(R.string.home_full)); return; }
        rename("", name -> {
            if (!editable()) return;
            Tile tile = Tile.folder(name);
            layout.tiles.add(tile);
            openFolder = tile.id;
            edit();
        });
    }

    private void rename(String previous, MainActivity.Done<String> done) {
        LinearLayout box = u.column();
        u.pad(box, 22, 5, 22, 0);
        EditText field = a.field(text(R.string.home_name), InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_CAP_SENTENCES, previous);
        field.selectAll();
        box.addView(field);
        AlertDialog d = a.homeDialog().setTitle(R.string.home_name).setView(box)
                .setNegativeButton(android.R.string.cancel, null).setPositiveButton(R.string.home_done, null).create();
        d.show();
        d.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(v -> {
            String name = field.getText().toString().trim();
            if (name.isEmpty() || name.codePointCount(0, name.length()) > MAX_NAME || name.codePoints().anyMatch(Character::isISOControl)) {
                field.setError(text(R.string.home_name_error));
                return;
            }
            d.dismiss();
            done.accept(name);
        });
    }

    private void pickApps(Tile folder) {
        if (!editable()) return;
        int capacity = Math.min(MAX_APPS - layout.appCount(),
                folder == null ? MAX_TOP - layout.tiles.size() : MAX_FOLDER - folder.children.size());
        if (capacity < 1) { a.say(text(R.string.home_full)); return; }
        List<String> available = new ArrayList<>();
        for (String pkg : layout.apps.keySet()) if (!layout.contains(pkg)) available.add(pkg);
        if (available.isEmpty()) { a.say(text(R.string.home_picker_empty)); return; }
        Collator collator = Collator.getInstance();
        available.sort((left, right) -> collator.compare(layout.name(left), layout.name(right)));
        Set<String> chosen = new LinkedHashSet<>();
        List<String> visible = new ArrayList<>();
        LinearLayout box = u.column();
        u.pad(box, 16, 5, 16, 0);
        EditText filter = a.field(text(R.string.home_search), InputType.TYPE_CLASS_TEXT, "");
        box.addView(filter);
        TextView empty = u.text(text(R.string.home_no_matches), 15, MUTED, 520);
        box.addView(empty);
        ListView list = new ListView(a);
        list.setChoiceMode(ListView.CHOICE_MODE_MULTIPLE);
        box.addView(list, u.lp(MATCH, Math.min(330, a.getResources().getConfiguration().screenHeightDp / 3)));
        Runnable update = () -> {
            String needle = filter.getText().toString().toLowerCase(java.util.Locale.ROOT).trim();
            visible.clear();
            for (String pkg : available) if (layout.name(pkg).toLowerCase(java.util.Locale.ROOT).contains(needle)) visible.add(pkg);
            List<String> names = new ArrayList<>();
            for (String pkg : visible) names.add(layout.name(pkg));
            list.clearChoices();
            list.setAdapter(new ArrayAdapter<>(a, android.R.layout.simple_list_item_multiple_choice, names));
            for (int i = 0; i < visible.size(); i++) list.setItemChecked(i, chosen.contains(visible.get(i)));
            empty.setVisibility(visible.isEmpty() ? View.VISIBLE : View.GONE);
        };
        list.setOnItemClickListener((p, v, index, id) -> {
            String pkg = visible.get(index);
            if (!list.isItemChecked(index)) chosen.remove(pkg);
            else if (chosen.size() < capacity) chosen.add(pkg);
            else { list.setItemChecked(index, false); a.say(text(R.string.home_full)); }
        });
        filter.addTextChangedListener(new TextWatcher() {
            @Override public void beforeTextChanged(CharSequence s, int start, int count, int after) {}
            @Override public void onTextChanged(CharSequence s, int start, int before, int count) { update.run(); }
            @Override public void afterTextChanged(Editable s) {}
        });
        update.run();
        a.homeDialog().setTitle(R.string.home_add).setView(box).setNegativeButton(android.R.string.cancel, null)
                .setPositiveButton(R.string.home_done, (d, w) -> {
                    if (!editable() || chosen.isEmpty()) return;
                    for (String pkg : chosen) {
                        if (folder == null) layout.tiles.add(Tile.app(pkg)); else folder.children.add(pkg);
                    }
                    edit();
                }).show();
    }

    private void menu(Tile item, Tile parent) {
        if (!editable()) return;
        List<String> labels = new ArrayList<>();
        List<Runnable> actions = new ArrayList<>();
        int index = parent == null ? layout.tiles.indexOf(item) : parent.children.indexOf(item.id);
        int size = parent == null ? layout.tiles.size() : parent.children.size();
        if (index < 0) return;
        if (item.isFolder()) {
            option(labels, actions, R.string.home_rename, () -> rename(item.name, name -> {
                if (editable()) { item.name = name; edit(); }
            }));
        } else if (parent != null || layout.tiles.stream().anyMatch(Tile::isFolder)) {
            option(labels, actions, R.string.home_move, () -> destinations(item, parent));
        }
        if (index > 0) option(labels, actions, R.string.home_earlier, () -> {
            Collections.swap(parent == null ? layout.tiles : parent.children, index, index - 1); edit();
        });
        if (index + 1 < size) option(labels, actions, R.string.home_later, () -> {
            Collections.swap(parent == null ? layout.tiles : parent.children, index, index + 1); edit();
        });
        if (item.isFolder()) {
            option(labels, actions, R.string.home_ungroup, () -> {
                if (layout.tiles.size() - 1 + item.children.size() > MAX_TOP) { a.say(text(R.string.home_full)); return; }
                layout.tiles.remove(index);
                int offset = index;
                for (String pkg : item.children) layout.tiles.add(offset++, Tile.app(pkg));
                openFolder = null;
                edit();
            });
        } else {
            option(labels, actions, R.string.home_remove, () -> {
                if (parent == null) layout.tiles.remove(item); else parent.children.remove(item.id);
                edit();
            });
        }
        a.homeDialog().setTitle(item.isFolder() ? item.name : layout.name(item.id))
                .setItems(labels.toArray(new String[0]), (d, i) -> { if (editable()) actions.get(i).run(); }).show();
    }

    private void option(List<String> labels, List<Runnable> actions, int label, Runnable run) {
        labels.add(text(label));
        actions.add(run);
    }

    private void destinations(Tile item, Tile parent) {
        List<Tile> destinations = new ArrayList<>();
        List<String> names = new ArrayList<>();
        if (parent != null) { destinations.add(null); names.add(text(R.string.home_parent)); }
        for (Tile folder : layout.tiles) {
            if (folder.isFolder() && folder != parent) {
                destinations.add(folder);
                names.add(folder.name);
            }
        }
        a.homeDialog().setTitle(R.string.home_move).setItems(names.toArray(new String[0]),
                (d, i) -> move(item, parent, destinations.get(i))).show();
    }

    private void move(Tile item, Tile parent, Tile dest) {
        if (!editable()) return;
        if (dest == null && layout.tiles.size() >= MAX_TOP) { a.say(text(R.string.home_full)); return; }
        if (dest != null && dest.children.size() >= MAX_FOLDER) { a.say(text(R.string.home_folder_full)); return; }
        if (parent == null) layout.tiles.remove(item); else parent.children.remove(item.id);
        if (dest == null) layout.tiles.add(Tile.app(item.id)); else dest.children.add(item.id);
        edit();
    }
}
