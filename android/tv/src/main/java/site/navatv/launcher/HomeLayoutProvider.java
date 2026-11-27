package site.navatv.launcher;

import android.Manifest;
import android.content.ContentProvider;
import android.content.ContentValues;
import android.database.Cursor;
import android.net.Uri;
import android.os.Binder;
import android.os.Bundle;

/** The authenticated shell endpoint of the paired phone. */
public final class HomeLayoutProvider extends ContentProvider {
    @Override public boolean onCreate() { return true; }

    @Override public Bundle call(String method, String arg, Bundle extras) {
        getContext().enforceCallingOrSelfPermission(Manifest.permission.DUMP, "Home layout access");
        long caller = Binder.clearCallingIdentity();
        try {
            if (AppShelf.handles(method)) return AppShelf.companion(getContext());
            if (SetupState.handles(method)) return SetupState.companion(getContext(), arg);
            if (RegionStore.handles(method)) return RegionStore.companion(getContext(), arg);
            if (BackgroundStore.handles(method)) return BackgroundStore.companion(getContext(), method, arg);
            if (LibraryStore.handles(method)) return LibraryStore.companion(getContext(), method, arg);
            return HomeLayoutStore.companion(getContext(), method, arg);
        } finally { Binder.restoreCallingIdentity(caller); }
    }

    @Override public android.os.ParcelFileDescriptor openFile(Uri uri, String mode) throws java.io.FileNotFoundException {
        getContext().enforceCallingOrSelfPermission(Manifest.permission.DUMP, "Background upload");
        if (!uri.getPathSegments().isEmpty() && "appart".equals(uri.getPathSegments().get(0))) {
            long caller = Binder.clearCallingIdentity();
            try { return AppShelf.open(getContext(), uri, mode); }
            finally { Binder.restoreCallingIdentity(caller); }
        }
        return BackgroundStore.open(getContext(), uri, mode);
    }

    @Override public String getType(Uri uri) { return null; }
    @Override public Cursor query(Uri uri, String[] fields, String where, String[] args, String order) { throw new UnsupportedOperationException(); }
    @Override public Uri insert(Uri uri, ContentValues values) { throw new UnsupportedOperationException(); }
    @Override public int delete(Uri uri, String where, String[] args) { throw new UnsupportedOperationException(); }
    @Override public int update(Uri uri, ContentValues values, String where, String[] args) { throw new UnsupportedOperationException(); }
}
