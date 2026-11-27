package site.navatv.launcher;

import android.content.Context;
import android.graphics.BitmapFactory;
import android.net.Uri;
import android.os.Bundle;
import android.os.ParcelFileDescriptor;
import android.util.Base64;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.File;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * The viewer's own photos for the Rust launcher's background. The paired companion first writes each
 * photo through the provider, then saves which ones rotate and how often.
 */
final class BackgroundStore {
    private static final int ENCODING = Base64.URL_SAFE | Base64.NO_PADDING | Base64.NO_WRAP;
    private static final int LIMIT = 10;
    private static final long PHOTO_BYTES = 4 * 1024 * 1024;
    private static final String ID = "bg-[0-9a-f]{12}";

    private static File folder(Context context) {
        return new File(context.getFilesDir(), "backgrounds");
    }

    static boolean handles(String method) {
        return "backgrounds_get".equals(method) || "backgrounds_set".equals(method);
    }

    /** `content write --uri content://…/background/bg-0123456789ab` */
    static ParcelFileDescriptor open(Context context, Uri uri, String mode) throws FileNotFoundException {
        List<String> path = uri.getPathSegments();
        if (path.size() != 2 || !"background".equals(path.get(0)) || !path.get(1).matches(ID)
                || mode == null || !mode.startsWith("w")) throw new FileNotFoundException("Unknown background");
        File folder = folder(context);
        if (!folder.isDirectory() && !folder.mkdirs()) throw new FileNotFoundException("No room for photos");
        return ParcelFileDescriptor.open(new File(folder, path.get(1) + ".upload"),
                ParcelFileDescriptor.MODE_WRITE_ONLY | ParcelFileDescriptor.MODE_CREATE
                        | ParcelFileDescriptor.MODE_TRUNCATE);
    }

    private static boolean photo(File file) {
        if (!file.isFile() || file.length() == 0 || file.length() > PHOTO_BYTES) return false;
        BitmapFactory.Options bounds = new BitmapFactory.Options();
        bounds.inJustDecodeBounds = true;
        BitmapFactory.decodeFile(file.getPath(), bounds);
        return "image/jpeg".equals(bounds.outMimeType)
                && bounds.outWidth > 0 && bounds.outWidth <= 4096
                && bounds.outHeight > 0 && bounds.outHeight <= 4096;
    }

    private static JSONObject read(Context context) {
        try {
            return new JSONObject(new String(Files.readAllBytes(
                    new File(context.getFilesDir(), "backgrounds.json").toPath()), StandardCharsets.UTF_8));
        } catch (IOException | JSONException missing) { return new JSONObject(); }
    }

    static synchronized Bundle companion(Context context, String method, String arg) {
        Bundle out = new Bundle();
        boolean handled = false;
        if ("backgrounds_set".equals(method) && arg != null && arg.length() <= 4000) {
            try {
                handled = save(context, new JSONObject(
                        new String(Base64.decode(arg, ENCODING), StandardCharsets.UTF_8)));
            } catch (IllegalArgumentException | JSONException | IOException invalid) {
                handled = false;
            }
        } else if ("backgrounds_get".equals(method)) {
            handled = true;
        }
        out.putString("backgrounds64", Base64.encodeToString(
                read(context).toString().getBytes(StandardCharsets.UTF_8), ENCODING));
        out.putBoolean("handled", handled);
        return out;
    }

    private static boolean save(Context context, JSONObject request) throws JSONException, IOException {
        long interval = request.getLong("interval");
        if (interval != 30 && interval != 3600 && interval != 86400) return false;
        JSONArray images = request.getJSONArray("images");
        if (images.length() > LIMIT) return false;
        File folder = folder(context);
        Set<String> kept = new HashSet<>();
        // Check every photo before changing anything, so a broken transfer keeps the old background.
        for (int i = 0; i < images.length(); i++) {
            String id = images.getString(i);
            if (!id.matches(ID) || !kept.add(id + ".jpg")) return false;
            if (!photo(new File(folder, id + ".upload")) && !photo(new File(folder, id + ".jpg"))) return false;
        }
        for (int i = 0; i < images.length(); i++) {
            String id = images.getString(i);
            File upload = new File(folder, id + ".upload");
            if (upload.isFile()) Files.move(upload.toPath(), new File(folder, id + ".jpg").toPath(),
                    StandardCopyOption.REPLACE_EXISTING);
        }
        JSONObject next = new JSONObject().put("interval", interval).put("since", System.currentTimeMillis())
                .put("images", images).put("off", new JSONArray());
        File temporary = new File(context.getFilesDir(), "backgrounds.json.tmp");
        Files.write(temporary.toPath(), next.toString().getBytes(StandardCharsets.UTF_8));
        Files.move(temporary.toPath(), new File(context.getFilesDir(), "backgrounds.json").toPath(),
                StandardCopyOption.REPLACE_EXISTING);
        File[] files = folder.listFiles();
        if (files != null) for (File file : files) if (!kept.contains(file.getName())) file.delete();
        return true;
    }

    private BackgroundStore() {}
}
