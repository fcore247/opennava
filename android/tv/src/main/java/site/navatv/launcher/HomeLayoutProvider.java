package site.navatv.launcher;

import android.Manifest;
import android.content.ContentProvider;
import android.content.ContentValues;
import android.database.Cursor;
import android.net.Uri;
import android.os.Binder;
import android.os.Bundle;

/** Same-signer canonical layout in classic; authenticated shell endpoint in the standalone preview. */
public final class HomeLayoutProvider extends ContentProvider {
    @Override public boolean onCreate() { return true; }

    @Override public Bundle call(String method, String arg, Bundle extras) {
        boolean classic = "site.navatv.launcher".equals(getContext().getPackageName());
        String permission = classic ? "site.navatv.launcher.permission.HOME_LAYOUT" : Manifest.permission.DUMP;
        getContext().enforceCallingOrSelfPermission(permission, "Home layout access");
        long caller = Binder.clearCallingIdentity();
        try {
            if (classic && ("launcher_get".equals(method) || "launcher_set".equals(method) || "launcher_adopt_rust".equals(method))) {
                Bundle result = new Bundle();
                if ("launcher_adopt_rust".equals(method)) site.navatv.updates.LauncherProfile.adoptRust(getContext());
                if ("launcher_set".equals(method)) result.putBoolean("saved", site.navatv.updates.LauncherProfile.save(getContext(), arg));
                result.putString("launcher", site.navatv.updates.LauncherProfile.get(getContext()));
                return result;
            }
            if (!classic) return HomeLayoutStore.companion(getContext(), method, arg);
            if ("read".equals(method)) return HomeLayoutStore.read(getContext());
            if ("write".equals(method) || "adopt".equals(method)) return HomeLayoutStore.write(getContext(), arg, "adopt".equals(method));
            throw new IllegalArgumentException("Unknown home layout method");
        } finally { Binder.restoreCallingIdentity(caller); }
    }

    @Override public String getType(Uri uri) { return null; }
    @Override public Cursor query(Uri uri, String[] fields, String where, String[] args, String order) { throw new UnsupportedOperationException(); }
    @Override public Uri insert(Uri uri, ContentValues values) { throw new UnsupportedOperationException(); }
    @Override public int delete(Uri uri, String where, String[] args) { throw new UnsupportedOperationException(); }
    @Override public int update(Uri uri, ContentValues values, String where, String[] args) { throw new UnsupportedOperationException(); }
}
