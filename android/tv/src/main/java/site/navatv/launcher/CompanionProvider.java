package site.navatv.launcher;

import android.Manifest;
import android.app.Activity;
import android.content.ContentProvider;
import android.content.ContentValues;
import android.database.Cursor;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.PowerManager;

import java.util.concurrent.ExecutionException;
import java.util.concurrent.FutureTask;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.function.Supplier;

/** The companion's authenticated ADB shell can inspect and control the current live session. */
public final class CompanionProvider extends ContentProvider {
    private final Handler main = new Handler(Looper.getMainLooper());

    @Override public boolean onCreate() { return true; }

    @Override public Bundle call(String method, String arg, Bundle extras) {
        // call() has no implicit read/write operation. Check the caller before switching threads.
        getContext().enforceCallingOrSelfPermission(Manifest.permission.DUMP, "Companion access requires DUMP");
        if (method != null && method.startsWith("luna_settings_")) {
            Bundle out = LunaTvSettings.call(getContext(), method, arg);
            if ("luna_settings_set".equals(method) && out.getBoolean("saved")) main.post(() -> {
                Activity current = ProgrammeReminders.foreground();
                if (current instanceof site.navatv.full.MainActivity)
                    ((site.navatv.full.MainActivity) current).lunaSettingsChanged();
            });
            return out;
        }
        if ("luna_status".equals(method) || "luna_save".equals(method)) {
            Bundle result = new Bundle();
            if ("luna_status".equals(method)) {
                result.putBoolean("ready", true);
            } else {
                boolean saved = LunaWatchlist.get(getContext()).save(arg);
                result.putBoolean("saved", saved);
                if (saved) main.post(() -> {
                    Activity current = ProgrammeReminders.foreground();
                    if (current instanceof site.navatv.full.MainActivity)
                        ((site.navatv.full.MainActivity) current).lunaWatchlistChanged();
                });
            }
            return result;
        }
        if ("library_list".equals(method))
            return CompanionLibrary.list(getContext(), onMain(() -> ProgrammeReminders.entries(getContext())));
        if ("library_delete".equals(method) && arg != null && arg.startsWith("recording:"))
            return CompanionLibrary.delete(getContext(), arg);
        if ("reminder_set".equals(method)) {
            try {
                CompanionLibrary.ReminderChange change = new CompanionLibrary.ReminderChange(arg);
                if (onMain(() -> change.apply(getContext(), null)).getBoolean("handled")) return CompanionLibrary.result(true);
                LiveGuide.Programme programme = change.resolve();
                return onMain(() -> change.apply(getContext(), programme));
            } catch (Exception unavailable) {
                return CompanionLibrary.result(false);
            }
        }
        return onMain(() -> invoke(method, arg));
    }

    private <T> T onMain(Supplier<T> action) {
        if (Looper.myLooper() == Looper.getMainLooper()) return action.get();

        FutureTask<T> task = new FutureTask<>(action::get);
        main.post(task);
        try {
            return task.get(3, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Live TV query interrupted", e);
        } catch (ExecutionException | TimeoutException e) {
            throw new IllegalStateException("Live TV did not respond", e);
        } finally {
            main.removeCallbacks(task);
        }
    }

    private Bundle invoke(String method, String arg) {
        switch (method) {
            case "get_profile":
                Bundle profile = new Bundle();
                profile.putString("profile", VisitorMode.isVisitor(getContext()) ? "full" : "senior");
                return profile;
            case "screen_state": return screen();
            case "live_state": return LiveTvActivity.companionState();
            case "live_control": return CompanionLibrary.result(LiveTvActivity.companionControl(arg));
            case "library_delete": return CompanionLibrary.delete(getContext(), arg);
            default: throw new IllegalArgumentException("Unknown companion method");
        }
    }

    private Bundle screen() {
        Bundle out = new Bundle();
        PowerManager power = getContext().getSystemService(PowerManager.class);
        Activity activity = ProgrammeReminders.foreground();
        String screen = "other";
        if (power != null && !power.isInteractive() || activity instanceof AmbientActivity) screen = "idle";
        else if (activity instanceof MainActivity) {
            MainActivity catalog = (MainActivity) activity;
            String provider = catalog.companionProvider();
            screen = provider.isEmpty() ? "home" : "catalog";
            out.putString("provider", provider);
            out.putString("section", catalog.companionPage());
            out.putString("query64", CompanionLibrary.encode(catalog.companionQuery()));
        } else if (activity instanceof LiveTvActivity) {
            LiveTvActivity live = (LiveTvActivity) activity;
            screen = "live";
            out.putAll(LiveTvActivity.companionState());
            out.putString("channel", live.companionChannel());
            out.putString("section", live.companionSection());
        } else if (activity instanceof TvProgrammeActivity) {
            TvProgrammeActivity guide = (TvProgrammeActivity) activity;
            screen = "guide";
            out.putString("channel", guide.companionChannel());
            if (guide.companionReminders()) out.putString("section", "reminders");
        } else if (activity instanceof RecordingsActivity) screen = "recordings";
        else if (activity instanceof PlayerActivity) {
            PlayerActivity player = (PlayerActivity) activity;
            screen = "player";
            String provider = player.companionProvider();
            if ("ard".equals(provider) || "zdf".equals(provider) || "arte".equals(provider)) out.putString("provider", provider);
            out.putString("title64", CompanionLibrary.encode(player.companionTitle()));
            if (player.fromRecordings()) out.putString("origin", "recordings");
        } else if (activity != null && (activity.getClass().getName().startsWith("site.navatv.full.") || activity instanceof PhoneLinkActivity)) {
            screen = "visitor";
        }
        out.putString("screen", screen);
        return out;
    }

    @Override public String getType(Uri uri) { return null; }
    @Override public Cursor query(Uri uri, String[] fields, String selection, String[] args, String order) {
        throw new UnsupportedOperationException();
    }
    @Override public Uri insert(Uri uri, ContentValues values) { throw new UnsupportedOperationException(); }
    @Override public int delete(Uri uri, String where, String[] args) { throw new UnsupportedOperationException(); }
    @Override public int update(Uri uri, ContentValues values, String where, String[] args) { throw new UnsupportedOperationException(); }
}
