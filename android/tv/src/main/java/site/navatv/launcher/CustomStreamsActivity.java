package site.navatv.launcher;

import android.app.AlertDialog;
import android.app.ListActivity;
import android.os.Bundle;
import android.text.InputType;
import android.view.View;
import android.widget.ArrayAdapter;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/** Remote-friendly list of the viewer's own streams. Long addresses are easiest to add from the paired phone. */
public final class CustomStreamsActivity extends ListActivity {
    private List<CustomStreams.Entry> entries = new ArrayList<>();

    @Override protected void onCreate(Bundle state) {
        super.onCreate(state);
        int inset = Math.round(26 * getResources().getDisplayMetrics().density);
        getListView().setPadding(inset, inset, inset, inset);
        TextView heading = new TextView(this);
        heading.setText("My streams\nAdd HLS (.m3u8), DASH or video addresses here, or from OpenNava Companion on your phone.");
        heading.setTextSize(20);
        heading.setTextColor(0xfff3edf9);
        heading.setPadding(0, 0, 0, inset / 2);
        getListView().addHeaderView(heading, null, false);
    }

    @Override protected void onResume() {
        super.onResume();
        show();
    }

    private void show() {
        CustomStreamStore.load(this);
        entries = CustomStreamStore.list(this);
        List<String> rows = new ArrayList<>();
        rows.add("+  Add a stream");
        for (CustomStreams.Entry item : entries) rows.add(item.name + "\n" + item.url);
        setListAdapter(new ArrayAdapter<>(this, android.R.layout.simple_list_item_1, rows));
        getListView().requestFocus();
    }

    @Override protected void onListItemClick(android.widget.ListView list, View row, int position, long id) {
        int index = position - list.getHeaderViewsCount() - 1;
        if (index < 0) addDialog();
        else if (index < entries.size()) removeDialog(entries.get(index));
    }

    private EditText field(String hint, int type) {
        EditText input = new EditText(this);
        input.setHint(hint);
        input.setSingleLine();
        input.setInputType(type);
        return input;
    }

    private void addDialog() {
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        int inset = Math.round(22 * getResources().getDisplayMetrics().density);
        box.setPadding(inset, inset / 3, inset, 0);
        EditText name = field("Name (optional)", InputType.TYPE_CLASS_TEXT);
        EditText address = field("https://…", InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_URI);
        box.addView(name);
        box.addView(address);
        AlertDialog dialog = new AlertDialog.Builder(this).setTitle("Add a stream").setView(box)
                .setNegativeButton("Cancel", null).setPositiveButton("Save", null).create();
        // Stay open while the address needs correcting; a remote makes retyping expensive.
        dialog.setOnShowListener(ignored -> dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(v -> {
            try {
                CustomStreams.Entry entry = CustomStreams.entry(name.getText().toString(), address.getText().toString());
                if (!CustomStreamStore.addAll(this, Collections.singletonList(entry))) {
                    say("You can save up to " + CustomStreams.LIMIT + " streams.");
                    return;
                }
                dialog.dismiss();
                show();
                say(entry.name + " is now in your channels.");
            } catch (IllegalArgumentException invalid) {
                address.setError(invalid.getMessage());
                address.requestFocus();
            }
        }));
        dialog.show();
    }

    private void removeDialog(CustomStreams.Entry entry) {
        new AlertDialog.Builder(this).setTitle(entry.name).setMessage("Remove this stream from your channels?")
                .setNegativeButton("Keep", null)
                .setPositiveButton("Remove", (dialog, which) -> {
                    CustomStreamStore.remove(this, entry.id);
                    show();
                }).show();
    }

    private void say(String message) { Toast.makeText(this, message, Toast.LENGTH_LONG).show(); }
}
