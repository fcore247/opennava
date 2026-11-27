package site.navatv.launcher;

import android.content.Context;
import android.content.Intent;
import android.content.pm.ActivityInfo;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.drawable.Drawable;
import android.net.Uri;
import android.os.Bundle;
import android.os.ParcelFileDescriptor;
import android.util.Base64;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.File;
import java.io.FileNotFoundException;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.text.Collator;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Tells the paired companion which apps this TV can open, with the picture the TV itself shows. */
final class AppShelf {
    private static final int LIMIT = 120;
    private static final String PACKAGE = "[A-Za-z0-9_]+(\\.[A-Za-z0-9_]+)+";

    static boolean handles(String method) { return "apps_get".equals(method); }

    /** One entry per app; the TV entry wins over the phone entry of the same package. */
    private static Map<String, ResolveInfo> installed(Context context) {
        PackageManager pm = context.getPackageManager();
        Map<String, ResolveInfo> apps = new LinkedHashMap<>();
        for (String category : new String[] {Intent.CATEGORY_LEANBACK_LAUNCHER, Intent.CATEGORY_LAUNCHER}) {
            for (ResolveInfo info : pm.queryIntentActivities(new Intent(Intent.ACTION_MAIN).addCategory(category), 0)) {
                ActivityInfo activity = info.activityInfo;
                if (activity == null || !activity.exported || activity.packageName.equals(context.getPackageName())) continue;
                if (!apps.containsKey(activity.packageName)) apps.put(activity.packageName, info);
            }
        }
        return apps;
    }

    private static Drawable banner(PackageManager pm, ResolveInfo info) {
        Drawable banner = info.activityInfo.loadBanner(pm);
        return banner != null ? banner : info.activityInfo.applicationInfo.loadBanner(pm);
    }

    static Bundle companion(Context context) {
        PackageManager pm = context.getPackageManager();
        List<JSONObject> rows = new ArrayList<>();
        for (Map.Entry<String, ResolveInfo> app : installed(context).entrySet()) {
            ResolveInfo info = app.getValue();
            CharSequence label = info.loadLabel(pm);
            String name = label == null || label.length() == 0 ? app.getKey() : label.toString();
            long version = 0;
            try { version = pm.getPackageInfo(app.getKey(), 0).lastUpdateTime; }
            catch (PackageManager.NameNotFoundException removed) { continue; }
            try {
                rows.add(new JSONObject().put("package", app.getKey())
                        .put("name", name.length() > 60 ? name.substring(0, 60) : name)
                        .put("component", app.getKey() + "/" + info.activityInfo.name)
                        .put("banner", banner(pm, info) != null)
                        .put("version", version));
            } catch (JSONException impossible) { throw new IllegalStateException(impossible); }
        }
        Collator order = Collator.getInstance();
        rows.sort((a, b) -> order.compare(a.optString("name"), b.optString("name")));
        if (rows.size() > LIMIT) rows = rows.subList(0, LIMIT);
        Bundle out = new Bundle();
        out.putString("apps64", Base64.encodeToString(new JSONArray(rows).toString().getBytes(StandardCharsets.UTF_8),
                Base64.URL_SAFE | Base64.NO_PADDING | Base64.NO_WRAP));
        out.putBoolean("handled", true);
        return out;
    }

    /** `content read --uri content://…/appart/<package>`: the TV banner as 320×180, or else the icon. */
    static ParcelFileDescriptor open(Context context, Uri uri, String mode) throws FileNotFoundException {
        List<String> path = uri.getPathSegments();
        if (path.size() != 2 || !path.get(1).matches(PACKAGE) || !"r".equals(mode))
            throw new FileNotFoundException("Unknown app picture");
        ResolveInfo info = installed(context).get(path.get(1));
        if (info == null) throw new FileNotFoundException("This app is not installed");
        PackageManager pm = context.getPackageManager();
        Drawable picture = banner(pm, info);
        boolean wide = picture != null;
        if (!wide) picture = info.loadIcon(pm);
        int width = wide ? 320 : 144, height = wide ? 180 : 144;
        Bitmap bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888);
        picture.setBounds(0, 0, width, height);
        picture.draw(new Canvas(bitmap));
        File file = new File(context.getCacheDir(), "appart-" + path.get(1) + ".png");
        try (OutputStream out = new FileOutputStream(file)) {
            bitmap.compress(Bitmap.CompressFormat.PNG, 100, out);
        } catch (IOException failed) {
            throw new FileNotFoundException("The picture could not be prepared");
        } finally {
            bitmap.recycle();
        }
        ParcelFileDescriptor descriptor = ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY);
        file.delete();   // The open descriptor keeps the data until the companion has read it.
        return descriptor;
    }

    private AppShelf() {}
}
