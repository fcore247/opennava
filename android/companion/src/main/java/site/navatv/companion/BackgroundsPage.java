package site.navatv.companion;

import static site.navatv.companion.Surface.*;

import android.content.Context;
import android.content.SharedPreferences;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.ImageDecoder;
import android.net.Uri;
import android.view.Gravity;
import android.view.View;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;

/**
 * Photos from this phone as the TV background. The choice is kept on the phone and sent to the TV,
 * which keeps its own copies and changes them without the phone.
 */
final class BackgroundsPage {
    static final int LIMIT = 10;
    private static final int[] SECONDS = {30, 3_600, 86_400};
    private static final String[] LABELS = {"Every 30 sec", "Every hour", "Every day"};

    private final MainActivity a;
    private final Surface u;
    private final SharedPreferences saved;
    private final File folder;
    private final List<String> photos = new ArrayList<>();
    private String status = "";
    private boolean importing, sending, again;

    BackgroundsPage(MainActivity activity) {
        a = activity;
        u = a.ui;
        saved = a.getSharedPreferences("backgrounds", Context.MODE_PRIVATE);
        folder = new File(a.getFilesDir(), "backgrounds");
        for (String id : saved.getString("photos", "").split(","))
            if (id.matches("bg-[0-9a-f]{12}") && file(id).isFile()) photos.add(id);
    }

    private File file(String id) { return new File(folder, id + ".jpg"); }

    private int interval() {
        int index = Arrays.binarySearch(SECONDS, saved.getInt("interval", 3_600));
        return index < 0 ? 1 : index;
    }

    /** Shown under the settings entry. */
    String summary() {
        if (photos.isEmpty()) return "Use your own photos on the TV";
        if (photos.size() == 1) return "1 photo";
        return photos.size() + " photos · " + LABELS[interval()].toLowerCase(Locale.ROOT);
    }

    private void store() {
        saved.edit().putString("photos", String.join(",", photos)).putBoolean("dirty", true).apply();
    }

    void build() {
        u.space(a.body, 4);
        a.body.addView(u.text("Pick one photo for a fixed background, or several to have them take turns.", 14, MUTED, 500));
        u.space(a.body, 16);
        preview();
        u.space(a.body, 14);
        if (photos.size() < LIMIT) {
            LinearLayout add = u.button(importing ? "Preparing photos…" : "Add photos", "plus", true,
                    () -> { if (!importing) a.choosePhotos(); });
            a.body.addView(add, u.lp(MATCH, 56));
        }
        if (!photos.isEmpty()) {
            u.space(a.body, 18);
            grid();
            u.space(a.body, 7);
            a.body.addView(u.text(photos.size() + " of " + LIMIT + " · Tap a photo to remove it", 12, MUTED, 520));
        }
        if (photos.size() > 1) {
            u.space(a.body, 22);
            LinearLayout card = u.column();
            card.setBackground(u.shape(CARD, 24, 0));
            u.pad(card, 12, 16, 12, 6);
            TextView heading = u.text("Change photo", 17, INK, 760);
            u.pad(heading, 6, 0, 0, 0);
            heading.setAccessibilityHeading(true);
            card.addView(heading);
            u.space(card, 10);
            card.addView(new IntervalSlider(a, u, LABELS, interval(), index -> {
                saved.edit().putInt("interval", SECONDS[index]).putBoolean("dirty", true).apply();
                send();
            }), u.lp(MATCH, WRAP));
            a.body.addView(card, u.lp(MATCH, WRAP));
        }
        u.space(a.body, 18);
        sendArea();
    }

    private void preview() {
        FrameLayout frame = new FrameLayout(a);
        frame.setBackground(u.shape(CARD, 22, LINE));
        frame.setClipToOutline(true);
        if (photos.isEmpty()) {
            TextView empty = u.text("Your TV shows the standard landscape", 14, MUTED, 560);
            empty.setGravity(Gravity.CENTER);
            frame.addView(empty, new FrameLayout.LayoutParams(MATCH, MATCH));
        } else {
            frame.addView(picture(photos.get(0), 4), new FrameLayout.LayoutParams(MATCH, MATCH));
            TextView label = u.text("TV preview", 12, INK, 760);
            label.setBackground(u.shape(0xb0101218, 12, 0));
            u.pad(label, 11, 6, 11, 6);
            FrameLayout.LayoutParams place = new FrameLayout.LayoutParams(WRAP, WRAP, Gravity.TOP | Gravity.START);
            place.setMargins(u.d(12), u.d(12), 0, 0);
            frame.addView(label, place);
        }
        int width = a.getResources().getDisplayMetrics().widthPixels - 2 * u.d(20);
        a.body.addView(frame, new LinearLayout.LayoutParams(MATCH, Math.min(u.d(260), width * 9 / 16)));
    }

    private ImageView picture(String id, int sample) {
        ImageView view = new ImageView(a);
        view.setScaleType(ImageView.ScaleType.CENTER_CROP);
        BitmapFactory.Options options = new BitmapFactory.Options();
        options.inSampleSize = sample;
        view.setImageBitmap(BitmapFactory.decodeFile(file(id).getPath(), options));
        return view;
    }

    private void grid() {
        LinearLayout line = null;
        int tile = (a.getResources().getDisplayMetrics().widthPixels - 2 * u.d(20) - 2 * u.d(8)) / 3;
        for (int i = 0; i < photos.size(); i++) {
            if (i % 3 == 0) {
                if (i > 0) u.space(a.body, 8);
                line = u.row();
                a.body.addView(line, u.lp(MATCH, WRAP));
            }
            String id = photos.get(i);
            FrameLayout cell = new FrameLayout(a);
            cell.setBackground(u.shape(CARD, 16, 0));
            cell.setClipToOutline(true);
            cell.addView(picture(id, 8), new FrameLayout.LayoutParams(MATCH, MATCH));
            FrameLayout badge = new FrameLayout(a);
            badge.setBackground(u.shape(0xc0101218, 13, 0));
            badge.addView(new Art(a, "close", INK), new FrameLayout.LayoutParams(u.d(12), u.d(12), Gravity.CENTER));
            FrameLayout.LayoutParams place = new FrameLayout.LayoutParams(u.d(26), u.d(26), Gravity.TOP | Gravity.END);
            place.setMargins(0, u.d(6), u.d(6), 0);
            cell.addView(badge, place);
            cell.setContentDescription("Remove photo " + (i + 1));
            cell.setOnClickListener(v -> {
                photos.remove(id);
                file(id).delete();
                store();
                a.render();
                send();
            });
            LinearLayout.LayoutParams size = new LinearLayout.LayoutParams(tile, tile * 10 / 16);
            if (i % 3 > 0) size.leftMargin = u.d(8);
            line.addView(cell, size);
        }
    }

    private void sendArea() {
        if (!status.isEmpty()) {
            TextView line = u.text(status, 14, status.startsWith("On your TV") ? 0xff7fe3b0 : ACCENT, 650);
            line.setAccessibilityLiveRegion(View.ACCESSIBILITY_LIVE_REGION_POLITE);
            a.body.addView(line);
            u.space(a.body, 10);
        }
        if (!saved.getBoolean("dirty", false)) return;
        if (!a.connected()) {
            a.body.addView(u.text("Saved on this phone. Connect your TV to send the change.", 13, MUTED, 520));
            u.space(a.body, 9);
            a.body.addView(u.button("Connect a TV", "cast", false, () -> a.go("devices")), u.lp(MATCH, 52));
        } else if (!sending) {
            a.body.addView(u.button("Send to TV", "tv", false, this::send), u.lp(MATCH, 52));
        }
    }

    private void show(String text) {
        status = text;
        if (a.page.equals("backgrounds")) a.render();
    }

    /** Copies the chosen photos into the app, no larger than a TV needs. */
    void add(List<Uri> chosen) {
        if (chosen.isEmpty() || importing) return;
        importing = true;
        show("");
        a.browse.execute(() -> {
            List<String> added = new ArrayList<>();
            int failed = 0;
            for (Uri uri : chosen) {
                try { added.add(prepare(uri)); }
                catch (Exception unreadable) { failed++; }
            }
            int unreadable = failed;
            a.runOnUiThread(() -> {
                importing = false;
                int skipped = 0;
                for (String id : added) {
                    if (photos.contains(id)) continue;
                    if (photos.size() < LIMIT) photos.add(id);
                    else { file(id).delete(); skipped++; }
                }
                store();
                show(unreadable > 0 ? unreadable + " photo(s) could not be opened."
                        : skipped > 0 ? "Your TV keeps up to " + LIMIT + " photos." : "");
                send();
            });
        });
    }

    private String prepare(Uri uri) throws Exception {
        // ImageDecoder applies the camera orientation.
        Bitmap bitmap = ImageDecoder.decodeBitmap(ImageDecoder.createSource(a.getContentResolver(), uri),
                (decoder, info, source) -> {
                    int width = info.getSize().getWidth(), height = info.getSize().getHeight();
                    float scale = Math.min(1f, Math.max(1920f / width, 1080f / height));
                    scale = Math.min(scale, 2560f / Math.max(width, height));
                    decoder.setTargetSize(Math.max(1, Math.round(width * scale)), Math.max(1, Math.round(height * scale)));
                    decoder.setAllocator(ImageDecoder.ALLOCATOR_SOFTWARE);
                });
        ByteArrayOutputStream jpeg = new ByteArrayOutputStream();
        bitmap.compress(Bitmap.CompressFormat.JPEG, 86, jpeg);
        bitmap.recycle();
        byte[] bytes = jpeg.toByteArray();
        StringBuilder id = new StringBuilder("bg-");
        byte[] digest = MessageDigest.getInstance("SHA-256").digest(bytes);
        for (int i = 0; i < 6; i++) id.append(String.format(Locale.ROOT, "%02x", digest[i] & 255));
        if (!folder.isDirectory() && !folder.mkdirs()) throw new IOException("No room for photos");
        try (OutputStream out = new FileOutputStream(file(id.toString()))) { out.write(bytes); }
        return id.toString();
    }

    void send() {
        if (!saved.getBoolean("dirty", false)) return;
        if (!a.connected()) {
            if (a.page.equals("backgrounds")) a.render();
            return;
        }
        if (sending) { again = true; return; }
        sending = true;
        List<String> ids = new ArrayList<>(photos);
        List<File> files = new ArrayList<>();
        for (String id : ids) files.add(file(id));
        int seconds = SECONDS[interval()];
        show("Sending to your TV…");
        a.runTv(() -> {
            a.tv.putBackgrounds(ids, files, seconds);
            return true;
        }, done -> {
            sending = false;
            if (again) {
                again = false;
                send();
                return;
            }
            saved.edit().putBoolean("dirty", false).apply();
            show(ids.isEmpty() ? "On your TV: the standard landscape." : "On your TV ✓");
        }, failed -> {
            sending = again = false;
            show(a.error(failed));
        });
    }
}
