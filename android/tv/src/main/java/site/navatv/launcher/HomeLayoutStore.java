package site.navatv.launcher;

import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager;
import android.content.pm.ProviderInfo;
import android.content.pm.ResolveInfo;
import android.net.Uri;
import android.os.Bundle;
import android.util.Base64;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.nio.charset.StandardCharsets;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.Set;

/** Full-mode home shared by the launcher, its separately installed preview and the paired phone. */
public final class HomeLayoutStore {
    private static final String CLASSIC = "site.navatv.launcher";
    public static final Uri CHANGES = Uri.parse("content://" + CLASSIC + ".homelayout");
    private static final String PREFS = "tv-home-layout";
    private static final int B64 = Base64.URL_SAFE | Base64.NO_WRAP | Base64.NO_PADDING;
    private static final String INITIAL = "{\"revision\":0,\"layout\":\"row\",\"items\":[]}";
    private HomeLayoutStore() {}

    public static LinkedHashMap<String, ResolveInfo> launchableApps(Context ctx) {
        PackageManager pm = ctx.getPackageManager();
        LinkedHashMap<String, ResolveInfo> apps = new LinkedHashMap<>();
        for (String category : new String[]{Intent.CATEGORY_LEANBACK_LAUNCHER, Intent.CATEGORY_LAUNCHER}) {
            for (ResolveInfo info : pm.queryIntentActivities(new Intent(Intent.ACTION_MAIN).addCategory(category), 0)) {
                if (info.activityInfo == null || !info.activityInfo.enabled || !info.activityInfo.applicationInfo.enabled
                        || !info.activityInfo.exported) continue;
                String name = info.activityInfo.packageName;
                if (name.equals(ctx.getPackageName()) || name.equals(CLASSIC) || name.equals("site.navatv.full")
                        || name.equals("site.navatv.rustpreview") || name.equals("com.google.android.apps.tv.launcherx")
                        || name.equals("com.amazon.tv.launcher") || name.equals("com.amazon.tv.settings.v2")) continue;
                boolean userApp = (info.activityInfo.applicationInfo.flags & ApplicationInfo.FLAG_SYSTEM) == 0;
                boolean mediaApp = name.equals("com.amazon.firebat") || name.equals("com.amazon.firetv.youtube")
                        || name.equals("com.netflix.ninja") || name.equals("com.disney.disneyplus")
                        || name.equals("de.swr.ard.avp.mobile.android.amazon") || name.equals("com.viki.kindle")
                        || name.equals("com.spotify.tv.android") || name.startsWith("com.apple.atve.");
                if (Intent.CATEGORY_LEANBACK_LAUNCHER.equals(category) || userApp || mediaApp) apps.putIfAbsent(name, info);
            }
        }
        return apps;
    }

    private static SharedPreferences prefs(Context ctx) { return ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE); }

    private static boolean usesClassic(Context ctx) {
        if (CLASSIC.equals(ctx.getPackageName())) return false;
        ProviderInfo info = ctx.getPackageManager().resolveContentProvider(CHANGES.getAuthority(), 0);
        return info != null && CLASSIC.equals(info.packageName)
                && ctx.getPackageManager().checkSignatures(ctx.getPackageName(), CLASSIC) == PackageManager.SIGNATURE_MATCH;
    }

    private static Bundle result(boolean handled, String code) {
        Bundle result = new Bundle();
        result.putBoolean("handled", handled);
        if (code != null) result.putString("code", code);
        return result;
    }

    private static String local(Context ctx) { return prefs(ctx).getString("layout", INITIAL); }

    private static String state(Context ctx) throws JSONException {
        if (!usesClassic(ctx)) return local(ctx);
        Bundle bundle = ctx.getContentResolver().call(CHANGES, "read", null, null);
        if (bundle == null || bundle.getString("layout") == null) throw new IllegalStateException("Home layout unavailable");
        String current = bundle.getString("layout");
        String saved = local(ctx);
        if (INITIAL.equals(current) && !INITIAL.equals(saved)) {
            // A standalone preview may already have a layout when the matching classic launcher is installed.
            JSONObject migration = new JSONObject(saved);
            migration.put("revision", 0);
            Bundle imported = ctx.getContentResolver().call(CHANGES, "adopt", migration.toString(), null);
            if (imported != null && imported.getBoolean("handled")) current = imported.getString("layout");
            else {
                if (imported == null || !"conflict".equals(imported.getString("code"))) {
                    throw new IllegalStateException("Could not migrate the saved home");
                }
                Bundle latest = ctx.getContentResolver().call(CHANGES, "read", null, null);
                if (latest == null || latest.getString("layout") == null) throw new IllegalStateException("Home layout unavailable");
                current = latest.getString("layout");
            }
        }
        if (!current.equals(saved)) prefs(ctx).edit().putString("layout", current).apply();
        return current;
    }

    public static String layout(Context ctx) {
        try { return state(ctx); }
        catch (RuntimeException | JSONException unavailable) { return ""; }
    }

    public static String revision(Context ctx) {
        try {
            String source = CLASSIC.equals(ctx.getPackageName()) || usesClassic(ctx) ? "classic:" : "local:";
            return source + new JSONObject(state(ctx)).getLong("revision");
        }
        catch (RuntimeException | JSONException e) { return "unavailable"; }
    }

    public static String snapshot(Context ctx) {
        try {
            JSONObject layout = new JSONObject(state(ctx));
            JSONArray apps = new JSONArray();
            PackageManager pm = ctx.getPackageManager();
            for (ResolveInfo info : launchableApps(ctx).values()) {
                String pkg = info.activityInfo.packageName;
                CharSequence label = info.loadLabel(pm);
                apps.put(new JSONObject().put("package", pkg).put("name", label == null ? pkg : label.toString()));
            }
            layout.put("apps", apps);
            return layout.toString();
        } catch (RuntimeException | JSONException unavailable) { return ""; }
    }

    /** Called after an explicit DUMP check on the authenticated ADB caller. */
    public static Bundle companion(Context ctx, String method, String arg) {
        if ("home_get".equals(method)) {
            String json = snapshot(ctx);
            if (json.isEmpty()) return result(false, "unavailable");
            Bundle out = result(true, null);
            out.putString("data64", Base64.encodeToString(json.getBytes(StandardCharsets.UTF_8), B64));
            return out;
        }
        if (!"home_set".equals(method) || arg == null || arg.length() > 40000) return result(false, "invalid");
        try {
            String raw = new String(Base64.decode(arg, B64), StandardCharsets.UTF_8);
            Bundle saved = usesClassic(ctx) ? ctx.getContentResolver().call(CHANGES, "write", raw, null) : write(ctx, raw, false);
            if (saved == null) return result(false, "unavailable");
            String layout = saved.getString("layout");
            if (layout != null && !CLASSIC.equals(ctx.getPackageName())) prefs(ctx).edit().putString("layout", layout).apply();
            saved.remove("layout");
            return saved;
        } catch (IllegalArgumentException e) { return result(false, "invalid"); }
        catch (RuntimeException e) { return result(false, "unavailable"); }
    }

    static Bundle read(Context ctx) {
        Bundle data = new Bundle();
        data.putString("layout", local(ctx));
        return data;
    }

    private static void collectPackages(JSONArray items, Set<String> packages) throws JSONException {
        for (int i = 0; i < items.length(); i++) {
            JSONObject item = items.getJSONObject(i);
            if ("app".equals(item.getString("type"))) packages.add(item.getString("package"));
            else {
                JSONArray apps = item.getJSONArray("apps");
                for (int j = 0; j < apps.length(); j++) packages.add(apps.getString(j));
            }
        }
    }

    private static String checkedPackage(String pkg, Set<String> allowed, Set<String> seen) throws JSONException {
        if (!allowed.contains(pkg) || !seen.add(pkg) || seen.size() > 48) throw new JSONException("Invalid app");
        return pkg;
    }

    static synchronized Bundle write(Context ctx, String raw, boolean adopting) {
        if (raw == null || raw.length() > 30000) return result(false, "invalid");
        try {
            JSONObject value = new JSONObject(raw), old = new JSONObject(local(ctx));
            long rev = old.getLong("revision");
            if (value.getLong("revision") != rev || (adopting && rev != 0)) return result(false, "conflict");
            String layout = value.getString("layout");
            JSONArray items = value.getJSONArray("items");
            if (!(layout.equals("row") || layout.equals("grid")) || items.length() > 32) return result(false, "invalid");
            Set<String> allowed = new HashSet<>(launchableApps(ctx).keySet());
            collectPackages(old.getJSONArray("items"), allowed);
            if (adopting) {
                // Same-signer migration preserves an app slot even when that app has been uninstalled.
                Set<String> previous = new HashSet<>();
                collectPackages(items, previous);
                for (String pkg : previous) {
                    if (pkg.length() > 255 || !pkg.matches("[A-Za-z][A-Za-z0-9_]*(\\.[A-Za-z][A-Za-z0-9_]*)+"))
                        return result(false, "invalid");
                }
                allowed.addAll(previous);
            }
            Set<String> packages = new HashSet<>(), folders = new HashSet<>();
            JSONArray checked = new JSONArray();
            for (int i = 0; i < items.length(); i++) {
                JSONObject item = items.getJSONObject(i);
                String type = item.getString("type");
                JSONObject entry = new JSONObject().put("type", type);
                if ("app".equals(type)) {
                    entry.put("package", checkedPackage(item.getString("package"), allowed, packages));
                } else if ("folder".equals(type)) {
                    String id = item.getString("id"), name = item.getString("name").trim();
                    JSONArray children = item.getJSONArray("apps");
                    if (!id.matches("[a-zA-Z0-9_-]{1,80}") || !folders.add(id) || name.isEmpty()
                            || name.codePointCount(0, name.length()) > 32 || name.matches("(?s).*\\p{Cc}.*")
                            || children.length() > 16) return result(false, "invalid");
                    JSONArray apps = new JSONArray();
                    for (int j = 0; j < children.length(); j++) apps.put(checkedPackage(children.getString(j), allowed, packages));
                    entry.put("id", id).put("name", name).put("apps", apps);
                } else return result(false, "invalid");
                checked.put(entry);
            }
            String json = new JSONObject().put("revision", rev + 1).put("layout", layout).put("items", checked).toString();
            if (!prefs(ctx).edit().putString("layout", json).commit()) return result(false, "storage");
            Uri changes = CLASSIC.equals(ctx.getPackageName()) ? CHANGES : Uri.parse("content://" + ctx.getPackageName() + ".companion");
            ctx.getContentResolver().notifyChange(changes, null);
            Bundle reply = result(true, null);
            reply.putString("layout", json);
            reply.putLong("revision", rev + 1);
            return reply;
        } catch (JSONException | IllegalArgumentException e) { return result(false, "invalid"); }
    }
}
