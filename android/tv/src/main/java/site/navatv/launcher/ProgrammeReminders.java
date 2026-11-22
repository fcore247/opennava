package site.navatv.launcher;

import android.app.Activity;
import android.app.Application;
import android.content.Context;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.Gravity;
import android.view.View;
import android.widget.FrameLayout;
import android.widget.TextView;
import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/** Reminder deadlines are checked whenever our television UI is visible, including during playback. */
public final class ProgrammeReminders implements Application.ActivityLifecycleCallbacks {
    static final class Entry {
        final String channel, title, event;
        final long start, end;
        boolean delivered;
        Entry(String channel, String title, String event, long start, long end, boolean delivered) {
            this.channel = channel;
            this.title = title;
            this.event = event;
            this.start = start;
            this.end = end;
            this.delivered = delivered;
        }
        boolean matches(String station, LiveGuide.Programme p) {
            return channel.equals(station) && start == p.start && title.equals(p.title)
                    && (event.isEmpty() || p.eventId.isEmpty() || event.equals(p.eventId));
        }
    }

    private static final String PREFS = "programme-reminders-v1";
    private static ProgrammeReminders instance;
    private final Handler main = new Handler(Looper.getMainLooper());
    private final Runnable check = this::checkDeadline;
    private Activity visible;
    private TextView banner;
    private final Runnable dismiss = this::removeBanner;

    /** Only read on the main thread. Cleared on pause, including external TV activities. */
    static Activity foreground() { return instance == null ? null : instance.visible; }

    public static void install(Application app) {
        if (instance != null) return;
        instance = new ProgrammeReminders();
        app.registerActivityLifecycleCallbacks(instance);
    }

    static List<Entry> entries(Context context) {
        String raw = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString("items", "[]");
        ArrayList<Entry> result = new ArrayList<>();
        boolean expired = false;
        try {
            JSONArray rows = new JSONArray(raw);
            long now = System.currentTimeMillis();
            for (int i = 0; i < rows.length(); i++) {
                JSONObject row = rows.getJSONObject(i);
                Entry entry = new Entry(row.getString("channel"), row.getString("title"), row.optString("event"),
                        row.getLong("start"), row.getLong("end"), row.optBoolean("delivered"));
                if (entry.end > now) result.add(entry);
                else expired = true;
            }
        } catch (JSONException invalid) { android.util.Log.w("TV guide", "Could not read reminders", invalid); }
        result.sort(Comparator.comparingLong(item -> item.start));
        if (expired) save(context, result);
        return result;
    }

    static boolean toggle(Context context, String channel, LiveGuide.Programme programme) {
        List<Entry> entries = entries(context);
        boolean removed = entries.removeIf(item -> item.matches(channel, programme));
        if (!removed && programme.start > System.currentTimeMillis()) {
            entries.add(new Entry(channel, programme.title, programme.eventId, programme.start, programme.end, false));
        }
        save(context, entries);
        if (instance != null) instance.schedule();
        return !removed;
    }

    static void remove(Context context, Entry target) {
        List<Entry> entries = entries(context);
        entries.removeIf(item -> item.channel.equals(target.channel) && item.start == target.start && item.title.equals(target.title));
        save(context, entries);
        if (instance != null) instance.schedule();
    }

    private static void save(Context context, List<Entry> entries) {
        JSONArray rows = new JSONArray();
        try {
            for (Entry entry : entries) rows.put(new JSONObject().put("channel", entry.channel).put("title", entry.title)
                    .put("event", entry.event).put("start", entry.start).put("end", entry.end).put("delivered", entry.delivered));
        } catch (JSONException invalid) { throw new IllegalStateException(invalid); }
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putString("items", rows.toString()).apply();
    }

    static String channelName(String id) {
        for (LiveGuide.Channel channel : LiveGuide.channels()) if (channel.id.equals(id)) return channel.name;
        return id;
    }

    private void schedule() {
        main.removeCallbacks(check);
        if (visible == null) return;
        long now = System.currentTimeMillis();
        for (Entry entry : entries(visible)) {
            if (!entry.delivered) {
                main.postDelayed(check, Math.max(0, Math.min(60000, entry.start - now)));
                break;
            }
        }
    }

    private void checkDeadline() {
        if (visible == null || visible.isFinishing() || visible.isDestroyed()) return;
        long now = System.currentTimeMillis();
        List<Entry> entries = entries(visible);
        ArrayList<String> messages = new ArrayList<>();
        for (Entry entry : entries) {
            if (!entry.delivered && entry.start <= now && now < entry.end) {
                messages.add(entry.title + "  ·  " + channelName(entry.channel));
                entry.delivered = true;
            }
        }
        if (!messages.isEmpty()) {
            showBanner(android.text.TextUtils.join("\n", messages));
            save(visible, entries);
        }
        schedule();
    }

    private void showBanner(String message) {
        removeBanner();
        FrameLayout layer = visible.findViewById(android.R.id.content);
        if (layer == null) return;
        float density = visible.getResources().getDisplayMetrics().density;
        int inset = Math.round(20 * density);
        banner = new TextView(visible);
        banner.setText("Programme reminder\n" + message);
        banner.setTextColor(0xff1a3025);
        banner.setTextSize(17);
        banner.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
        banner.setMaxWidth((int)(visible.getResources().getDisplayMetrics().widthPixels * .52f));
        banner.setPadding(inset, inset / 2, inset, inset / 2);
        GradientDrawable shape = new GradientDrawable();
        shape.setCornerRadius(inset);
        shape.setColor(0xffe8efdb);
        banner.setBackground(shape);
        banner.setElevation(inset / 2f);
        // Passive overlay: remote focus, playback and system Home always stay with the viewer.
        banner.setFocusable(false);
        banner.setClickable(false);
        banner.setAccessibilityLiveRegion(View.ACCESSIBILITY_LIVE_REGION_POLITE);
        FrameLayout.LayoutParams params = new FrameLayout.LayoutParams(-2, -2, Gravity.TOP | Gravity.END);
        params.setMargins(inset * 2, inset, inset * 2, 0);
        layer.addView(banner, params);
        main.postDelayed(dismiss, 11000);
    }

    private void removeBanner() {
        main.removeCallbacks(dismiss);
        if (banner != null && banner.getParent() instanceof android.view.ViewGroup) {
            ((android.view.ViewGroup)banner.getParent()).removeView(banner);
        }
        banner = null;
    }

    @Override public void onActivityResumed(Activity activity) { visible = activity; schedule(); }
    @Override public void onActivityPaused(Activity activity) {
        if (visible == activity) { removeBanner(); visible = null; main.removeCallbacks(check); }
    }
    @Override public void onActivityCreated(Activity a, Bundle b) {}
    @Override public void onActivityStarted(Activity a) {}
    @Override public void onActivityStopped(Activity a) {}
    @Override public void onActivitySaveInstanceState(Activity a, Bundle b) {}
    @Override public void onActivityDestroyed(Activity a) {}
}
