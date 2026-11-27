package site.navatv.rustpreview;

import android.Manifest;
import android.content.ContentProvider;
import android.content.ContentValues;
import android.database.Cursor;
import android.net.Uri;
import android.os.Binder;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.util.Base64;

import com.liskovsoft.mediaserviceinterfaces.oauth.Account;

import org.json.JSONException;
import org.json.JSONObject;

import java.nio.charset.StandardCharsets;
import java.util.concurrent.TimeUnit;

import io.reactivex.android.schedulers.AndroidSchedulers;
import io.reactivex.disposables.Disposable;
import site.navatv.full.YoutubeBridge;

/**
 * NavaTube for the paired companion. The Google account lives only on this TV: the phone sees whether
 * somebody is signed in, can start Google's device pairing, and reads this TV's video lists.
 * No token or password ever leaves the TV. Android enforces the DUMP permission.
 */
public final class NavaTubeProvider extends ContentProvider {
    private static final int ENCODING = Base64.URL_SAFE | Base64.NO_PADDING | Base64.NO_WRAP;
    private static final long PAIRING_MINUTES = 10;

    private final Handler main = new Handler(Looper.getMainLooper());
    // The pairing state is only changed on the main thread and read from binder threads.
    private volatile String pairing = "idle", code = "";
    private Disposable request;
    private int generation;

    @Override public boolean onCreate() { return true; }

    @Override public Bundle call(String method, String arg, Bundle extras) {
        getContext().enforceCallingOrSelfPermission(Manifest.permission.DUMP, "NavaTube access");
        long caller = Binder.clearCallingIdentity();
        try {
            Bundle out = new Bundle();
            if ("now".equals(method)) {
                // Asked every second or two while something plays; it does not need the account.
                out.putString("nava64", Base64.encodeToString(
                        site.navatv.full.NowPlaying.read().getBytes(StandardCharsets.UTF_8), ENCODING));
                out.putBoolean("handled", true);
                return out;
            }
            if (!YoutubeSession.awaitReady()) {
                out.putBoolean("handled", false);
                return out;
            }
            String answer;
            switch (method == null ? "" : method) {
                case "status":
                    answer = status();
                    break;
                case "sign_in":
                    main.post(this::beginPairing);
                    answer = "{}";
                    break;
                case "cancel":
                    main.post(() -> endPairing("idle"));
                    answer = "{}";
                    break;
                case "sign_out":
                    main.post(() -> endPairing("idle"));
                    YoutubeSession.signOut();
                    answer = status();
                    break;
                case "feed":
                    answer = YoutubeBridge.videos(null, decode(arg, 200));
                    break;
                case "more":
                    answer = YoutubeBridge.more(null, arg == null ? "" : arg);
                    break;
                default:
                    throw new IllegalArgumentException("Unknown NavaTube method");
            }
            out.putString("nava64", Base64.encodeToString(answer.getBytes(StandardCharsets.UTF_8), ENCODING));
            out.putBoolean("handled", true);
            return out;
        } finally { Binder.restoreCallingIdentity(caller); }
    }

    private static String decode(String arg, int limit) {
        if (arg == null || arg.isEmpty() || arg.length() > limit * 6) return "";
        String text = new String(Base64.decode(arg, ENCODING), StandardCharsets.UTF_8);
        return text.length() > limit ? text.substring(0, limit) : text;
    }

    private String status() {
        try {
            Account account = YoutubeSession.service().getSelectedAccount();
            JSONObject result = new JSONObject().put("signedIn", account != null)
                    .put("name", account == null || account.getName() == null ? "" : account.getName())
                    .put("revision", YoutubeSession.accountRevision())
                    .put("pairing", account != null ? "idle" : pairing);
            if (account == null && "waiting".equals(pairing)) result.put("code", code);
            return result.toString();
        } catch (JSONException impossible) { throw new IllegalStateException(impossible); }
    }

    private void endPairing(String state) {
        generation++;
        if (request != null) request.dispose();   // interrupts the SDK's token polling
        request = null;
        code = "";
        pairing = state;
    }

    private void beginPairing() {
        if (YoutubeSession.service().isSigned() || ("waiting".equals(pairing) && request != null)
                || "starting".equals(pairing)) return;
        endPairing("starting");
        final int current = generation;
        request = YoutubeSession.service().signInObserve()
                .timeout(PAIRING_MINUTES, TimeUnit.MINUTES).observeOn(AndroidSchedulers.mainThread())
                .subscribe(next -> {
                    if (current != generation) return;
                    if (next == null || !next.matches("[A-Za-z0-9 -]{4,20}")) {
                        endPairing("failed");
                        return;
                    }
                    code = next;
                    pairing = "waiting";
                }, error -> {
                    if (current == generation) endPairing("failed");
                }, () -> {
                    if (current != generation) return;
                    if (YoutubeSession.service().isSigned()) {
                        YoutubeSession.changed();
                        endPairing("idle");
                    } else endPairing("failed");
                });
    }

    @Override public String getType(Uri uri) { return null; }
    @Override public Cursor query(Uri uri, String[] fields, String where, String[] args, String order) { throw new UnsupportedOperationException(); }
    @Override public Uri insert(Uri uri, ContentValues values) { throw new UnsupportedOperationException(); }
    @Override public int delete(Uri uri, String where, String[] args) { throw new UnsupportedOperationException(); }
    @Override public int update(Uri uri, ContentValues values, String where, String[] args) { throw new UnsupportedOperationException(); }
}
