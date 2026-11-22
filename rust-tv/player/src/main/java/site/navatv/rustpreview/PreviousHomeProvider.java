package site.navatv.rustpreview;

import android.content.ContentProvider;
import android.content.ContentValues;
import android.database.Cursor;
import android.net.Uri;
import android.os.Binder;
import android.os.Bundle;
import site.navatv.launcher.LauncherChoice;

/** One read-only migration fact for the matching classic launcher. */
public final class PreviousHomeProvider extends ContentProvider {
    @Override public boolean onCreate() { return true; }

    @Override public Bundle call(String method, String arg, Bundle extras) {
        getContext().enforceCallingOrSelfPermission("site.navatv.launcher.permission.HOME_LAYOUT", "Launcher migration");
        if (!"previous_home".equals(method)) throw new IllegalArgumentException("Unknown migration query");
        long identity = Binder.clearCallingIdentity();
        try {
            Bundle result = new Bundle();
            result.putBoolean("rust", LauncherChoice.previouslyUsedRust(getContext()));
            return result;
        } finally {
            Binder.restoreCallingIdentity(identity);
        }
    }

    @Override public String getType(Uri uri) { return null; }
    @Override public Cursor query(Uri uri, String[] p, String s, String[] a, String o) { throw new UnsupportedOperationException(); }
    @Override public Uri insert(Uri uri, ContentValues v) { throw new UnsupportedOperationException(); }
    @Override public int delete(Uri uri, String s, String[] a) { throw new UnsupportedOperationException(); }
    @Override public int update(Uri uri, ContentValues v, String s, String[] a) { throw new UnsupportedOperationException(); }
}
