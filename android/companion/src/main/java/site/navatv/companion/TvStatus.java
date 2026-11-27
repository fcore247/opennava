package site.navatv.companion;

import java.util.Objects;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * What the television is doing right now, read from Android's own service reports.
 * This covers every app on the TV, not only OpenNava. Free of Android classes.
 */
final class TvStatus {
    /** One shell round trip. Every report is optional: some TVs withhold parts of it. */
    static final String COMMAND = "dumpsys power 2>/dev/null | grep -m1 mWakefulness=; echo @@;"
            + "dumpsys activity activities 2>/dev/null | grep -m1 -E '(topResumedActivity=|mResumedActivity[=:] ?)ActivityRecord'; echo @@;"
            + "dumpsys media_session 2>/dev/null | sed -n '/Sessions Stack/,$p'"
            + " | grep -E '^    [^ ]|^      (package=|active=|state=|metadata:)' | head -n 60; echo @@;"
            + "cmd media_session volume --stream 3 --get 2>/dev/null | grep 'volume is'; echo @@;"
            + "dumpsys input_method 2>/dev/null | grep -m1 mInputShown=; true";

    static final String OPENNAVA_RUST = "site.navatv.rustpreview";

    final boolean awake, keyboard;
    final String app, activity;
    /** Empty when nothing reports media. */
    final String mediaApp, mediaTitle, mediaSubtitle;
    final boolean mediaPlaying;
    /** -1 when the TV does not report its volume. */
    final int volume, volumeMax;

    private TvStatus(boolean awake, boolean keyboard, String app, String activity, String mediaApp,
                     String mediaTitle, String mediaSubtitle, boolean mediaPlaying, int volume, int volumeMax) {
        this.awake = awake;
        this.keyboard = keyboard;
        this.app = app;
        this.activity = activity;
        this.mediaApp = mediaApp;
        this.mediaTitle = mediaTitle;
        this.mediaSubtitle = mediaSubtitle;
        this.mediaPlaying = mediaPlaying;
        this.volume = volume;
        this.volumeMax = volumeMax;
    }

    boolean hasMedia() { return !mediaApp.isEmpty(); }

    static TvStatus parse(String report) {
        String[] parts = (report == null ? "" : report).split("@@", -1);
        String power = part(parts, 0), top = part(parts, 1), sound = part(parts, 3);
        boolean awake = !(power.contains("=Asleep") || power.contains("=Dozing"));
        String app = "", activity = "";
        Matcher record = Pattern.compile("ActivityRecord\\{\\S+ u\\d+ ([A-Za-z0-9_.]+)/([A-Za-z0-9_.$]+)").matcher(top);
        if (record.find()) {
            app = record.group(1);
            activity = record.group(2);
        }
        int volume = -1, volumeMax = -1;
        Matcher level = Pattern.compile("volume is (\\d+) in range \\[\\d+\\.\\.(\\d+)\\]").matcher(sound);
        if (level.find()) {
            volume = Integer.parseInt(level.group(1));
            volumeMax = Integer.parseInt(level.group(2));
        }

        // The stack is ordered by priority. Take the app on screen first, otherwise whatever is audibly playing.
        String[] best = null;
        int rank = 0;
        for (String block : part(parts, 2).split("(?m)^    (?=\\S)")) {
            String owner = field(block, "(?m)^\\s*package=(\\S+)");
            if (owner.isEmpty() || !block.contains("active=true")) continue;
            String state = field(block, "state=PlaybackState \\{state=(?:[A-Z_]+\\()?(\\d+)");
            boolean playing = state.equals("3") || state.equals("6");
            boolean paused = state.equals("2");
            if (!playing && !paused) continue;
            int score = owner.equals(app) ? (playing ? 4 : 3) : playing ? 2 : 0;
            if (score > rank) {
                rank = score;
                String description = field(block, "(?m)^\\s*metadata: size=\\d+, description=(.*)$");
                String[] words = titles(description);
                best = new String[] {owner, words[0], words[1], playing ? "playing" : "paused"};
            }
        }
        boolean keyboard = part(parts, 4).contains("mInputShown=true");
        return new TvStatus(awake, keyboard, app, activity,
                best == null ? "" : best[0], best == null ? "" : best[1], best == null ? "" : best[2],
                best != null && best[3].equals("playing"), volume, volumeMax);
    }

    private static String part(String[] parts, int index) { return index < parts.length ? parts[index] : ""; }

    private static String field(String text, String pattern) {
        Matcher found = Pattern.compile(pattern).matcher(text);
        return found.find() ? found.group(1).trim() : "";
    }

    // Android prints "title, subtitle, description". Commas inside the title stay with the title.
    private static String[] titles(String description) {
        String value = description;
        int last = value.lastIndexOf(", ");
        if (last >= 0) value = value.substring(0, last);
        int middle = value.lastIndexOf(", ");
        String title = middle < 0 ? value : value.substring(0, middle);
        String subtitle = middle < 0 ? "" : value.substring(middle + 2);
        if (title.equals("null")) title = "";
        if (subtitle.equals("null") || subtitle.equals(title)) subtitle = "";
        return new String[] {title, subtitle};
    }

    boolean onHomeScreen() {
        if (app.equals("com.google.android.apps.tv.launcherx") || app.equals("com.google.android.tvlauncher")
                || app.equals("com.amazon.tv.launcher")) return true;
        if (app.equals(OPENNAVA_RUST)) return activity.endsWith("NativeActivity") || activity.endsWith("HomeEntryActivity");
        return false;
    }

    /** An app of its own is in front: not OpenNava, a start screen, the settings or a system window. */
    boolean inOtherApp() {
        if (app.isEmpty() || app.startsWith("site.navatv.") || onHomeScreen()) return false;
        switch (app) {
            case "android": case "com.android.systemui":
            case "com.android.tv.settings": case "com.amazon.tv.settings.v2":
            case "com.android.permissioncontroller": case "com.google.android.permissioncontroller":
            case "com.android.packageinstaller": case "com.google.android.packageinstaller":
            case "com.android.dreams.basic": case "com.google.android.apps.tv.dreamx":
            case "com.google.android.backdrop": case "com.amazon.bueller.photos": case "com.amazon.ftv.screensaver":
            case "com.google.android.katniss": case "com.google.android.tungsten.setupwraith":
            case "com.spocky.projengmenu":
                return false;
            default:
                return true;
        }
    }

    static String appName(String pkg) {
        switch (pkg) {
            case "": return "TV";
            case OPENNAVA_RUST: return "OpenNava";
            case "com.google.android.apps.tv.launcherx": case "com.google.android.tvlauncher": return "Google TV";
            case "com.amazon.tv.launcher": return "Fire TV";
            case "com.google.android.youtube.tv": case "com.amazon.firetv.youtube": return "YouTube";
            case "com.netflix.ninja": return "Netflix";
            case "com.amazon.amazonvideo.livingroom": case "com.amazon.avod": return "Prime Video";
            case "com.disney.disneyplus": return "Disney+";
            case "com.spotify.tv.android": return "Spotify";
            case "de.swr.avp.ard.tv": return "ARD Mediathek";
            case "com.zdf.android.mediathek": return "ZDFmediathek";
            case "tv.arte.plus7": return "ARTE";
            case "de.rtli.tvnow": return "RTL+";
            case "org.xbmc.kodi": return "Kodi";
            case "com.plexapp.android": return "Plex";
            case "org.videolan.vlc": return "VLC";
            case "com.android.tv.settings": case "com.amazon.tv.settings.v2": return "TV settings";
            default:
                String[] words = pkg.split("\\.");
                String name = words[words.length - 1];
                if (words.length > 1 && (name.equals("tv") || name.equals("android") || name.equals("app")))
                    name = words[words.length - 2];
                return name.isEmpty() ? pkg : Character.toUpperCase(name.charAt(0)) + name.substring(1);
        }
    }

    @Override public boolean equals(Object object) {
        if (!(object instanceof TvStatus)) return false;
        TvStatus o = (TvStatus) object;
        return awake == o.awake && keyboard == o.keyboard && mediaPlaying == o.mediaPlaying && volume == o.volume
                && volumeMax == o.volumeMax && app.equals(o.app) && activity.equals(o.activity)
                && mediaApp.equals(o.mediaApp) && mediaTitle.equals(o.mediaTitle) && mediaSubtitle.equals(o.mediaSubtitle);
    }

    @Override public int hashCode() { return Objects.hash(awake, app, activity, mediaTitle, mediaPlaying, volume); }
}
