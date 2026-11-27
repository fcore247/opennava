package site.navatv.companion;

import android.content.ComponentName;
import android.content.Context;
import android.content.pm.PackageInfo;
import android.net.Uri;
import android.os.Build;
import io.github.muntashirakon.adb.AdbConnection;
import io.github.muntashirakon.adb.AdbStream;
import io.github.muntashirakon.adb.PairingConnectionCtx;
import java.io.*;
import java.net.InetAddress;
import java.net.UnknownHostException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Locale;
import java.util.Objects;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import static java.util.concurrent.TimeUnit.SECONDS;

/** A controller instance belongs to one phone. Network and package operations run off the UI thread. */
public final class TvConnection {
    /** The one TV app: the native OpenNava launcher. */
    public static final String TV_PACKAGE = "site.navatv.rustpreview";
    private static final String RUST_TV = "site.navatv.rustpreview";
    /** The first version of it that takes every command of this phone app. */
    static final int NATIVE_TV_VERSION = 14;
    private static final int MIN_COMPANION_TV_VERSION = 100;
    static final int FIRST_TV_WITH_STARTUP_UPDATES = 112;
    private static final int BUNDLED_TV_VERSION = BuildConfig.VERSION_CODE;
    private static final int LIVE_TUNE_VERSION = 37;
    private static final String LIVE_CALL = "content call --user \"$(am get-current-user)\""
            + " --uri content://site.navatv.rustpreview.live --method ";
    private static final Pattern LIVE_CHANNEL = Pattern.compile("\\bchannel=([a-z0-9_-]{1,64})\\b");
    private static final String APK_ASSET = "OpenNava-TV.apk";
    private static final Pattern VERSION = Pattern.compile("(?m)^\\s*versionCode=(\\d+)");
    private static final Pattern VERSION_NAME = Pattern.compile("(?m)^\\s*versionName=([^\\r\\n]+)");
    private static final Pattern TEXT = Pattern.compile("[A-Za-z0-9 ._@,:+!?/()=-]{1,220}");
    private final Context context;
    private volatile AdbConnection link;
    private volatile Device target;

    public static final class Device {
        public final String name, model, host;
        final String androidId;
        public final int port, seniorVersion, navaVersion;
        public final boolean canUpdateSenior, supportsLiveTune, hasRtl;
        Device(String host, int port, String name, String model, int senior, int nava, boolean television, String tvVersionName, String androidId) {
            this.host = host;
            this.port = port;
            this.name = name;
            this.model = model;
            this.androidId = androidId;
            this.seniorVersion = senior;
            this.navaVersion = nava;
            this.canUpdateSenior = television && senior < BUNDLED_TV_VERSION;
            this.supportsLiveTune = senior >= LIVE_TUNE_VERSION;
            this.hasRtl = tvVersionName.toLowerCase(Locale.ROOT).endsWith("-rtl");
        }
    }

    public TvConnection(Context context) { this.context = context.getApplicationContext(); }
    public boolean isConnected() {
        AdbConnection adb = link;
        return target != null && adb != null && adb.isConnectionEstablished();
    }
    public Device device() { return isConnected() ? target : null; }

    static final class LiveState {
        static final LiveState OTHER = new LiveState("", false, false, "unavailable", false);
        final String channel;
        final boolean paused, seekable;
        final boolean recordEnd;
        final String recordMode;

        private LiveState(String channel, boolean paused, boolean seekable, String recordMode, boolean recordEnd) {
            this.channel = channel;
            this.paused = paused;
            this.seekable = seekable;
            this.recordMode = recordMode;
            this.recordEnd = recordEnd;
        }

        @Override public boolean equals(Object object) {
            if (!(object instanceof LiveState)) return false;
            LiveState other = (LiveState) object;
            return channel.equals(other.channel) && paused == other.paused && seekable == other.seekable
                    && recordEnd == other.recordEnd && recordMode.equals(other.recordMode);
        }

        @Override public int hashCode() { return Objects.hash(channel, paused, seekable, recordMode, recordEnd); }
    }

    LiveState liveState() throws Exception {
        String result = shell(LIVE_CALL + "live_state");
        return parseLiveState(result);
    }

    private static LiveState parseLiveState(String result) {
        if (!"live".equals(bundleValue(result, "mode"))) return LiveState.OTHER;
        Matcher station = LIVE_CHANNEL.matcher(result);
        if (!station.find()) return LiveState.OTHER;
        String recordMode = result.contains("recordMode=") ? bundleValue(result, "recordMode") : "dialog";
        return new LiveState(station.group(1), result.contains("paused=true"), result.contains("seekable=true"),
                recordMode, result.contains("recordEnd=true"));
    }

    private static String bundleValue(String result, String key) {
        Matcher value = Pattern.compile("(?:^|[,{]\\s*)" + key + "=([A-Za-z0-9_=-]+)").matcher(result);
        return value.find() ? value.group(1) : "";
    }

    private static String bundleText(String result, String key) {
        String encoded = bundleValue(result, key);
        try { return new String(android.util.Base64.decode(encoded, android.util.Base64.URL_SAFE), StandardCharsets.UTF_8); }
        catch (IllegalArgumentException invalid) { return ""; }
    }

    static final class ScreenState {
        final String screen, provider, channel, query, title, section, origin;
        final LiveState live;

        ScreenState(String screen, String provider, String result) {
            this.screen = screen;
            this.provider = provider;
            channel = bundleValue(result, "channel");
            section = bundleValue(result, "section");
            origin = bundleValue(result, "origin");
            query = bundleText(result, "query64");
            title = bundleText(result, "title64");
            live = "live".equals(screen) ? parseLiveState(result) : LiveState.OTHER;
        }

        boolean sameView(ScreenState other) {
            return other != null && screen.equals(other.screen) && provider.equals(other.provider)
                    && channel.equals(other.channel) && section.equals(other.section) && origin.equals(other.origin)
                    && query.equals(other.query) && title.equals(other.title);
        }
    }

    static final int FIRST_TV_WITH_OWN_STREAMS = 120;

    /** Reads the television without sending it anything. */
    TvStatus status() throws Exception { return TvStatus.parse(shell(TvStatus.COMMAND)); }

    java.util.List<site.navatv.launcher.CustomStreams.Entry> ownStreams() throws Exception {
        String result = shell(LIVE_CALL + "channels_get");
        if (!result.contains("channels64=")) throw new IOException("Update the TV app to use your own streams.");
        return site.navatv.launcher.CustomStreams.parse(bundleText(result, "channels64"));
    }

    /** Long lists exceed adbd's command limit, so they travel through stdin. */
    void addOwnStreams(java.util.List<site.navatv.launcher.CustomStreams.Entry> entries) throws Exception {
        for (int start = 0; start < entries.size(); start += 15) {
            String json = site.navatv.launcher.CustomStreams.write(
                    entries.subList(start, Math.min(entries.size(), start + 15)));
            byte[] input = (java.util.Base64.getUrlEncoder().withoutPadding()
                    .encodeToString(json.getBytes(StandardCharsets.UTF_8)) + "\n").getBytes(StandardCharsets.US_ASCII);
            String result = shell(active(), "IFS= read -r streams && " + LIVE_CALL
                    + "channels_add --arg \"$streams\"", input);
            if (!result.contains("handled=true"))
                throw new IOException("The TV could not save these streams. It keeps up to "
                        + site.navatv.launcher.CustomStreams.LIMIT + ".");
        }
    }

    void removeOwnStream(String id) throws Exception {
        if (!id.matches("custom-[0-9a-f]{12}")) throw new IOException("Unknown stream.");
        if (!shell(LIVE_CALL + "channels_remove --arg " + id).contains("handled=true"))
            throw new IOException("The TV could not remove this stream.");
    }

    private static final String RUST_CALL = "content call --user \"$(am get-current-user)\""
            + " --uri content://site.navatv.rustpreview.companion --method ";

    /** The page open in OpenNava Rust as {screen, provider, library, query}; empty if it cannot say. */
    org.json.JSONObject rustScreen() throws Exception {
        String json = bundleText(shell(RUST_CALL + "screen_state"), "rust64");
        return json.isEmpty() ? new org.json.JSONObject() : new org.json.JSONObject(json);
    }

    java.util.List<site.navatv.launcher.OwnLibrary.Source> librarySources() throws Exception {
        String result = shell(RUST_CALL + "library_sources_get");
        if (!result.contains("sources64=")) throw new IOException("Install the current OpenNava Rust on your TV.");
        return site.navatv.launcher.OwnLibrary.parseSources(bundleText(result, "sources64"));
    }

    void putLibrary(site.navatv.launcher.OwnLibrary.Source source) throws Exception {
        String one = site.navatv.launcher.OwnLibrary.writeSources(java.util.Collections.singletonList(source));
        byte[] input = (java.util.Base64.getUrlEncoder().withoutPadding().encodeToString(
                one.substring(1, one.length() - 1).getBytes(StandardCharsets.UTF_8)) + "\n")
                .getBytes(StandardCharsets.US_ASCII);
        String result = shell(active(), "IFS= read -r library && " + RUST_CALL
                + "library_source_put --arg \"$library\"", input);
        if (!result.contains("handled=true")) throw new IOException("The TV could not save this library.");
    }

    void removeLibrary(String id) throws Exception {
        if (!id.matches("lib-[0-9a-f]{12}")) throw new IOException("Unknown library.");
        if (!shell(RUST_CALL + "library_source_remove --arg " + id).contains("handled=true"))
            throw new IOException("The TV could not remove this library.");
    }

    /** Tells both TV apps the language and country chosen on the phone, such as "en-us". */
    void setLocale(String value) throws Exception {
        if (!value.matches("(de|en)-(de|at|ch|us)")) throw new IOException("Unknown language or country.");
        shell(RUST_CALL + "locale_set --arg " + value);
        // A live player that is already open rebuilds its channel list.
        shell(LIVE_CALL + "locale_set --arg " + value);
    }

    /** Lets the setup screen on the TV follow what the phone is showing. Older TV apps just ignore it. */
    void setupStep(String step) throws Exception {
        if (!step.matches("connected|tour-[1-9]|done")) throw new IOException("Unknown setup step.");
        shell(RUST_CALL + "setup_phone --arg " + step);
    }

    /** The apps this TV can open, as a JSON array from OpenNava Rust. */
    String installedApps() throws Exception {
        String result;
        try { result = shell(RUST_CALL + "apps_get"); }
        catch (IOException refused) {
            if (!String.valueOf(refused.getMessage()).startsWith("TV declined")) throw refused;
            result = "";
        }
        if (!result.contains("apps64=")) throw new IOException("Install the newest OpenNava Rust on your TV to see its apps.");
        return bundleText(result, "apps64");
    }

    byte[] appPicture(String pkg) throws Exception {
        if (!pkg.matches("[A-Za-z0-9_]+(\\.[A-Za-z0-9_]+)+")) throw new IOException("Unknown app.");
        String encoded = shell("content read --user \"$(am get-current-user)\" --uri"
                + " content://site.navatv.rustpreview.companion/appart/" + pkg + " | base64");
        return java.util.Base64.getMimeDecoder().decode(encoded.trim());
    }

    void launchApp(String component) throws Exception {
        if (!component.matches("[A-Za-z0-9_.]+/[A-Za-z0-9_.$]+")) throw new IOException("Unknown app.");
        String result = shell("am start --user current -n " + quote(component));
        if (result.contains("Error") || result.contains("Exception"))
            throw new IOException("Your TV could not open this app.");
    }

    /** For TVs that cannot report their apps: open the first of these packages that is installed. */
    void launchPackage(String[] packages) throws Exception {
        for (String pkg : packages) {
            if (!pkg.matches("[A-Za-z0-9_.]+")) continue;
            String result = shell("monkey -p " + pkg + " -c android.intent.category.LEANBACK_LAUNCHER"
                    + " -c android.intent.category.LAUNCHER 1 2>&1 || true");
            if (result.contains("Events injected: 1")) return;
        }
        throw new IOException("This app is not installed on your TV.");
    }

    void openNavaTube() throws Exception {
        // Only a TV app that reports its apps also understands the NavaTube request.
        installedApps();
        String result = shell("am start -n " + RUST_TV + "/.CompanionPlayActivity -d opennava://command/"
                + UUID.randomUUID() + " --es open_page navatube");
        if (result.contains("Error") || result.contains("Exception"))
            throw new IOException("NavaTube could not be opened on your TV.");
    }

    /** NavaTube's account and video lists. The Google account itself never leaves the TV. */
    org.json.JSONObject navaTube(String method, String argument) throws Exception {
        if (!method.matches("status|sign_in|cancel|sign_out|feed|more|now")) throw new IOException("Unknown NavaTube action.");
        String command = "content call --user \"$(am get-current-user)\""
                + " --uri content://site.navatv.rustpreview.navatube --method " + method;
        if (!argument.isEmpty()) {
            // A search is Base64 encoded for the TV; a page cursor is already a plain ID.
            String safe = "feed".equals(method) ? java.util.Base64.getUrlEncoder().withoutPadding()
                    .encodeToString(argument.getBytes(StandardCharsets.UTF_8)) : argument;
            if (!safe.matches("[A-Za-z0-9_-]{1,1200}")) throw new IOException("Unknown NavaTube request.");
            command += " --arg " + safe;
        }
        String result;
        try { result = shell(command); }
        catch (IOException refused) {
            if (!String.valueOf(refused.getMessage()).startsWith("TV declined")) throw refused;
            result = "";
        }
        if (!result.contains("nava64=")) throw new IOException("Install the newest OpenNava Rust on your TV to use NavaTube here.");
        return new org.json.JSONObject(bundleText(result, "nava64"));
    }

    void playNavaTube(String id, String title, String author) throws Exception {
        if (!id.matches("[A-Za-z0-9_-]{11}")) throw new IOException("Unknown video.");
        String result = shell("am start -n " + RUST_TV + "/.CompanionPlayActivity -d opennava://command/"
                + UUID.randomUUID() + " --es video_id " + id
                + " --es title " + quote(title.length() > 200 ? title.substring(0, 200) : title)
                + " --es author " + quote(author.length() > 120 ? author.substring(0, 120) : author));
        if (result.contains("Error") || result.contains("Exception"))
            throw new IOException("Your TV could not play this video.");
    }

    /** Copies the photos the TV does not have yet, then tells it which ones to rotate and how often. */
    void putBackgrounds(java.util.List<String> ids, java.util.List<File> files, int seconds) throws Exception {
        AdbConnection session = active();
        String state = shell(session, RUST_CALL + "backgrounds_get");
        if (!state.contains("backgrounds64=")) throw new IOException("Install the newest OpenNava Rust on your TV.");
        String json = bundleText(state, "backgrounds64");
        org.json.JSONArray present = new org.json.JSONObject(json.isEmpty() ? "{}" : json).optJSONArray("images");
        java.util.Set<String> onTv = new java.util.HashSet<>();
        for (int i = 0; present != null && i < present.length(); i++) onTv.add(present.optString(i));
        for (int i = 0; i < ids.size(); i++) {
            String id = ids.get(i);
            if (!id.matches("bg-[0-9a-f]{12}")) throw new IOException("Unknown photo.");
            if (onTv.contains(id)) continue;
            String remote = "/data/local/tmp/opennava-" + UUID.randomUUID() + ".jpg";
            try {
                push(session, files.get(i), remote);
                shell(session, "content write --user \"$(am get-current-user)\" --uri"
                        + " content://site.navatv.rustpreview.companion/background/" + id + " < " + remote);
            } finally {
                if (session.isConnectionEstablished()) try { shell(session, "rm -f " + remote); } catch (Exception ignored) {}
            }
        }
        String request = new org.json.JSONObject().put("interval", seconds)
                .put("images", new org.json.JSONArray(ids)).toString();
        String encoded = java.util.Base64.getUrlEncoder().withoutPadding()
                .encodeToString(request.getBytes(StandardCharsets.UTF_8));
        if (!shell(session, RUST_CALL + "backgrounds_set --arg " + encoded).contains("handled=true"))
            throw new IOException("The TV could not save these photos.");
    }

    /** A video from the viewer's own library plays in the Rust launcher's player. */
    void playOwn(String url, String title) throws Exception {
        String label = title.length() > 200 ? title.substring(0, 200) : title;
        String result = shell("am start -n " + RUST_TV + "/.CompanionPlayActivity -d opennava://command/"
                + UUID.randomUUID() + " --es stream_url " + quote(url) + " --es title " + quote(label));
        if (result.contains("Error") || result.contains("Exception"))
            throw new IOException("Install the current OpenNava Rust on your TV.");
    }

    ScreenState screenState() throws Exception {
        String result = shell(LIVE_CALL + "screen_state");
        String screen = bundleValue(result, "screen");
        if (screen.isEmpty()) throw new IOException("The TV did not report its current screen.");
        String provider = bundleValue(result, "provider");
        if ("other".equals(screen)) {
            String top = shell("dumpsys activity activities | grep -m 1 -E '(topResumedActivity=|mResumedActivity[=:] ?)ActivityRecord' || true");
            if (top.contains(" de.swr.avp.ard.tv/")) provider = "ard";
            else if (top.contains(" com.zdf.android.mediathek/")) provider = "zdf";
            else if (top.contains(" tv.arte.plus7/")) provider = "arte";
            if (!provider.isEmpty()) screen = "catalog";
        }
        return new ScreenState(screen, provider, result);
    }

    private static final String[] HOME_PROVIDERS = {"site.navatv.rustpreview.companion"};

    private AdbConnection homeSession(Device expected) throws IOException {
        AdbConnection session = active();
        if (expected == null || expected != target || session != link)
            throw new IOException(context.getString(R.string.home_tv_changed));
        return session;
    }

    private static String homeCall(String authority, String method) {
        return "content call --user \"$(am get-current-user)\" --uri content://" + authority + " --method " + method;
    }

    TvHomeLayout loadHome(Device expected) throws Exception {
        AdbConnection session = homeSession(expected);
        for (String authority : HOME_PROVIDERS) {
            String result;
            try {
                result = shell(session, homeCall(authority, "home_get"));
            } catch (IOException error) {
                // Different Android versions disagree about the exit status of missing providers.
                String message = String.valueOf(error.getMessage());
                if (message.contains("Could not find provider") || message.contains("Unknown authority")
                        || message.contains("Unknown method") || message.contains("UnsupportedOperationException")) continue;
                throw error;
            }
            String data = bundleValue(result, "data64");
            if (!data.isEmpty()) {
                String json = new String(java.util.Base64.getUrlDecoder().decode(data), StandardCharsets.UTF_8);
                return new TvHomeLayout(authority, new org.json.JSONObject(json));
            }
        }
        throw new UnsupportedOperationException(context.getString(R.string.home_update_required));
    }

    long saveHome(Device expected, String authority, org.json.JSONObject request) throws Exception {
        AdbConnection session = homeSession(expected);
        if (!java.util.Arrays.asList(HOME_PROVIDERS).contains(authority)) throw new IOException("Unknown TV provider");
        String encoded = java.util.Base64.getUrlEncoder().withoutPadding()
                .encodeToString(request.toString().getBytes(StandardCharsets.UTF_8));
        // A full layout can exceed adbd's OPEN service-name limit. Stream it through stdin.
        byte[] input = (encoded + "\n").getBytes(StandardCharsets.US_ASCII);
        String result = shell(session, "IFS= read -r home && " + homeCall(authority, "home_set")
                + " --arg \"$home\"", input);
        if ("conflict".equals(bundleValue(result, "code")))
            throw new java.util.ConcurrentModificationException(context.getString(R.string.home_conflict));
        if (!"true".equals(bundleValue(result, "handled")))
            throw new IOException(context.getString(R.string.home_save_failed));
        return Long.parseLong(bundleValue(result, "revision"));
    }

    void setReminder(String channel, long start, String title, boolean enabled) throws Exception {
        org.json.JSONObject request = new org.json.JSONObject().put("channel", channel).put("start", start)
                .put("title", title).put("enabled", enabled);
        String arg = android.util.Base64.encodeToString(request.toString().getBytes(StandardCharsets.UTF_8),
                android.util.Base64.URL_SAFE | android.util.Base64.NO_PADDING | android.util.Base64.NO_WRAP);
        if (!shell(LIVE_CALL + "reminder_set --arg " + quote(arg)).contains("handled=true"))
            throw new IOException("The TV could not save this reminder. Check the current schedule.");
    }

    void liveControl(String action) throws Exception {
        if (!action.matches("next|previous|pause|rewind|forward|guide|record|record_(1|5|30|60|end)")) throw new IOException("Unknown live TV control.");
        String response = shell(LIVE_CALL + "live_control --arg " + action);
        if (!response.contains("handled=true")) throw new IOException("This action is unavailable on the TV.");
    }

    public void pair(String host, int port, String code) throws Exception {
        if (code == null || !code.matches("[0-9]{6}")) throw new IOException("Enter the six-digit code shown on your TV.");
        String address = resolveTvAddress(host, port);
        AdbIdentity identity = AdbIdentity.load(context);
        try (PairingConnectionCtx pairing = new PairingConnectionCtx(address, port, code.getBytes(StandardCharsets.US_ASCII),
                identity.key, identity.certificate, "OpenNava Companion")) {
            pairing.start();
        } catch (IOException e) {
            throw new IOException("Pairing failed. Check the pairing port and current code on your TV.", e);
        }
    }

    public Device connect(String host, int port) throws Exception {
        return connect(host, port, false);
    }

    Device connect(String host, int port, boolean alreadyAuthorized) throws Exception {
        String address = resolveTvAddress(host, port);
        disconnect();
        AdbIdentity identity = AdbIdentity.load(context);
        AdbConnection next = new AdbConnection.Builder(address, port).setApi(Build.VERSION.SDK_INT)
                .setPrivateKey(identity.key).setCertificate(identity.certificate).setDeviceName("OpenNava Companion").build();
        link = next;
        try {
            if (!next.connect(alreadyAuthorized ? 6 : 42, SECONDS, alreadyAuthorized))
                throw new IOException("The TV did not authorize this connection.");
            Device connected = discover(host.trim(), port);
            if (link != next) throw new IOException("Disconnected.");
            target = connected;
            return connected;
        } catch (Exception e) {
            disconnect();
            throw new IOException("Connection failed. Check the IP address and connection port, then approve the request on your TV.", e);
        }
    }

    static String resolveTvAddress(String host, int port) throws IOException {
        if (port < 1 || port > 65535 || host == null) throw new IOException("Invalid TV address.");
        String address = host.trim();
        if (address.startsWith("[") && address.endsWith("]") && address.contains(":"))
            address = address.substring(1, address.length() - 1);
        if (address.isEmpty() || address.length() > 255 || !address.matches("[a-zA-Z0-9_.:%-]+"))
            throw new IOException("Enter the Wi-Fi address, Tailscale IP or device name.");
        InetAddress[] resolved;
        try {
            resolved = InetAddress.getAllByName(address);
        } catch (UnknownHostException unavailable) {
            throw new IOException("Address not found. Check the device name or use the Tailscale IP.", unavailable);
        }
        for (InetAddress ip : resolved) {
            byte[] raw = ip.getAddress();
            boolean uniqueLocal = raw.length == 16 && (raw[0] & 0xfe) == 0xfc;
            // Tailscale uses the shared IPv4 block 100.64.0.0/10; its IPv6 addresses are ULA.
            boolean sharedV4 = raw.length == 4 && raw[0] == 100 && (raw[1] & 0xc0) == 0x40;
            if (ip.isSiteLocalAddress() || ip.isLinkLocalAddress() || uniqueLocal || sharedV4)
                return ip.getHostAddress();
        }
        throw new IOException("Use your TV’s private Wi-Fi or Tailscale address.");
    }

    public void disconnect() {
        target = null;
        AdbConnection old = link;
        link = null;
        if (old != null) {
            try { old.close(); } catch (IOException ignored) {}
        }
    }

    private Device discover(String host, int port) throws Exception {
        String model = shell("getprop ro.product.model").trim();
        String alias = shell("settings get global device_name").trim();
        String mf = shell("getprop ro.product.manufacturer").trim();
        boolean tv = shell("pm list features").contains("feature:android.software.leanback");
        String launcherPackage = packageVersion(TV_PACKAGE);
        int launcher = versionCode(launcherPackage);
        Matcher name = VERSION_NAME.matcher(launcherPackage);
        String tvVersionName = name.find() ? name.group(1).trim() : "";
        if (!tv && launcher < 0) throw new IOException("This address is not a supported TV.");
        if (alias.isEmpty() || alias.equals("null") || alias.equalsIgnoreCase(model))
            alias = mf.equalsIgnoreCase("Amazon") ? "Fire TV" : model;
        String androidId = shell("settings get secure android_id").trim();
        if (!androidId.matches("(?i)[0-9a-f]{1,16}")) androidId = "";
        // The rest of the app asks by feature level: missing, too old, or able to do everything.
        int level = launcher < 0 ? -1 : launcher >= NATIVE_TV_VERSION ? BUNDLED_TV_VERSION : 1;
        return new Device(host, port, alias, model, level, -1, tv, tvVersionName, androidId);
    }

    private String packageVersion(String pkg) throws Exception {
        return shell("dumpsys package " + pkg + " | grep -E -m 2 'versionCode=|versionName=' || true");
    }

    private static int versionCode(String packageVersion) {
        Matcher found = VERSION.matcher(packageVersion);
        return found.find() ? Integer.parseInt(found.group(1)) : -1;
    }

    private boolean usesNativeHomeRoute(AdbConnection session) throws Exception {
        String resolved = shell(session, "cmd package resolve-activity --brief --user current"
                + " -a android.intent.action.MAIN -c android.intent.category.HOME 2>/dev/null | tail -n 1").trim();
        ComponentName home = ComponentName.unflattenFromString(resolved);
        if (home == null) return false;
        if (RUST_TV.equals(home.getPackageName())) return true;
        if (!"com.amazon.tv.launcher".equals(home.getPackageName())) return false;

        String settings = shell(session, "settings --user current get secure accessibility_enabled;"
                + " settings --user current get secure enabled_accessibility_services");
        String[] values = settings.trim().split("\\r?\\n", 2);
        if (values.length != 2 || !"1".equals(values[0])) return false;
        ComponentName rustHomeService = new ComponentName(RUST_TV, RUST_TV + ".FireHomeService");
        for (String entry : values[1].trim().split(":")) {
            if (rustHomeService.equals(ComponentName.unflattenFromString(entry))) return true;
        }
        return false;
    }

    /** Open the selected launcher profile on this TV, including when another app owns the Home key. */
    public void openHome() throws Exception {
        AdbConnection session = active();
        if (usesNativeHomeRoute(session)) {
            // Fire OS routes this single press according to the OpenNava/Amazon setting on the TV.
            shell(session, "input keyevent 3");
            return;
        }
        String entry = RUST_TV + "/.HomeEntryActivity";
        String found = shell(session, "cmd package resolve-activity --brief --user current -n " + entry + " 2>/dev/null || true").trim();
        if (!found.endsWith(entry) && !found.endsWith(RUST_TV + "/" + RUST_TV + ".HomeEntryActivity"))
            throw new IOException("Install OpenNava on the connected TV to open its home screen.");
        startHome(session, "-a android.intent.action.MAIN -c android.intent.category.LEANBACK_LAUNCHER", entry);
    }

    private static void startHome(AdbConnection session, String action, String component) throws Exception {
        String result = shell(session, "am start -W --user current --activity-clear-top --activity-single-top "
                + action + " -n " + component);
        if (result.contains("Error") || result.contains("Exception") || !result.contains("Status: ok"))
            throw new IOException("The TV could not open the launcher home screen.");
    }

    public void key(int keyCode) throws Exception {
        switch (keyCode) {
            case 3: case 4: case 19: case 20: case 21: case 22: case 23: case 24: case 25: case 26:
            case 66: case 67: case 82: case 85: case 86: case 87: case 88: case 89: case 90:
            case 126: case 127: case 164: case 224: break;
            default: throw new IOException("Unknown remote control key.");
        }
        shell("input keyevent " + keyCode);
    }

    public void sendText(String text) throws Exception {
        if (text == null || !TEXT.matcher(text).matches())
            throw new IOException("Direct text entry supports unaccented Latin letters. Library search also supports accented characters.");
        shell("input text " + quote(text.replace(" ", "%s")));
    }

    public void play(String provider, String programmeRef) throws Exception { openMedia(provider, programmeRef, "", false); }

    public void tune(String channelId) throws Exception {
        Device target = device();
        if (target == null) throw new IOException("Connect to a TV first.");
        if (!target.supportsLiveTune) throw new IOException("Update the TV app to change channels.");
        if (channelId == null || !channelId.matches("[a-z0-9_-]{1,64}") || ("rtl".equals(channelId) && !target.hasRtl))
            throw new IOException("This channel is unavailable on your TV.");
        launchCompanion("--es operation tune --es value " + quote(channelId));
    }

    PhoneLibrary libraryEntries() throws Exception {
        return PhoneLibrary.decode(shell(LIVE_CALL + "library_list"));
    }

    void libraryDelete(PhoneLibrary.Entry entry) throws Exception {
        String response = shell(LIVE_CALL + "library_delete --arg " + quote(entry.key()));
        if (!response.contains("handled=true"))
            throw new IOException("This entry cannot be deleted right now.");
    }

    void libraryPlay(PhoneLibrary.Entry entry, int episode) throws Exception {
        if (!entry.playable || episode < 0 || episode >= Math.max(1, entry.episodes.size()))
            throw new IOException("This programme is currently unavailable.");
        launchCompanion("--es operation library_play --es value " + quote(entry.key()) + " --ei episode " + episode);
    }

    public void openMedia(String provider, String itemId, String title, boolean live) throws Exception {
        if (live) { companion("live", provider, "", title); return; }
        if (itemId == null || itemId.isEmpty() || itemId.length() > 2048)
            throw new IOException("This programme has no playback address.");
        Uri uri = Uri.parse(itemId);
        String host = uri.getHost();
        boolean supported = ("ard".equals(provider) && "api.ardmediathek.de".equals(host))
                || ("zdf".equals(provider) && "zdf-prod-futura.zdf.de".equals(host))
                || ("arte".equals(provider) && "api.arte.tv".equals(host));
        if (!supported || !"https".equals(uri.getScheme()) || uri.getUserInfo() != null || uri.getPort() != -1)
            throw new IOException("Unknown programme address.");
        companion("play", provider, itemId, title);
    }

    private void companion(String operation, String provider, String value, String title) throws Exception {
        Device device = device();
        if (device == null) throw new IOException("Connect to a TV first.");
        if (device.seniorVersion < MIN_COMPANION_TV_VERSION) throw new IOException("Install the TV update under Devices.");
        if (!"ard".equals(provider) && !"zdf".equals(provider) && !"arte".equals(provider))
            throw new IOException("Unknown library.");
        String requestedTitle = title == null ? "" : title;
        if (requestedTitle.length() > 300) requestedTitle = requestedTitle.substring(0,300);
        launchCompanion("--es operation " + operation + " --es provider " + provider
                + " --es value " + quote(value) + " --es title " + quote(requestedTitle));
    }

    private void launchCompanion(String extras) throws Exception {
        // Android compares intents without their extras. Without distinct data it would treat a second command
        // as "already running", bring the old task forward and silently drop the new channel or programme.
        String result = shell("am start -n " + TV_PACKAGE + "/site.navatv.launcher.CompanionActivity"
                + " -a site.navatv.launcher.COMPANION -d "
                + "opennava://command/" + UUID.randomUUID() + " " + extras);
        if (result.contains("Error") || result.contains("Exception")) throw new IOException("The TV could not open this action.");
    }

    private static String quote(String s) throws IOException {
        if (s == null || s.indexOf('\0') >= 0 || s.length() > 2100) throw new IOException("Invalid content.");
        return "'" + s.replace("'", "'\\''") + "'";
    }

    private AdbConnection active() throws IOException {
        AdbConnection adb = link;
        if (adb == null || !adb.isConnectionEstablished()) throw new IOException("The TV connection was interrupted.");
        return adb;
    }

    /** Read shell v2 packets through EXIT. A dropped transport must never be mistaken for success. */
    private String shell(String command) throws Exception {
        return shell(active(), command);
    }

    private static String shell(AdbConnection session, String command) throws Exception {
        return shell(session, command, null);
    }

    private static String shell(AdbConnection session, String command, byte[] stdin) throws Exception {
        try (AdbStream stream = session.open("shell,v2,raw:" + command)) {
            if (stdin != null) {
                byte[] frame = ByteBuffer.allocate(5 + stdin.length).order(ByteOrder.LITTLE_ENDIAN)
                        .put((byte) 0).putInt(stdin.length).put(stdin).array();
                stream.openOutputStream().write(frame);
            }
            DataInputStream input = new DataInputStream(stream.openInputStream());
            ByteArrayOutputStream text = new ByteArrayOutputStream();
            byte[] header = new byte[5];
            while (true) {
                input.readFully(header);
                int channel = header[0] & 255;
                int length = ByteBuffer.wrap(header,1,4).order(ByteOrder.LITTLE_ENDIAN).getInt();
                if (length < 0 || length > 2097152 || length + text.size() > 2097152) throw new IOException("Invalid response from your TV.");
                byte[] body = new byte[length];
                input.readFully(body);
                if (channel == 3) {
                    if (length != 1 || body[0] != 0) throw new IOException("TV declined the action: " + text.toString("UTF-8").trim());
                    return text.toString("UTF-8");
                }
                if (channel == 1 || channel == 2) text.write(body);
            }
        }
    }

    private final Object updateLock = new Object();

    String updatePairedTv(Device expected, boolean automatic) throws Exception {
        synchronized (updateLock) { return installPairedUpdate(expected, automatic); }
    }

    /** Check a single authorized TV. A reconnect can never redirect an in-flight installation. */
    private String installPairedUpdate(Device expected, boolean automatic) throws Exception {
        if (expected == null || expected.seniorVersion <= 0) return "OpenNava is not installed on this TV.";
        AdbConnection session = active();
        if (expected != target || !expected.androidId.equals(shell(session, "settings get secure android_id").trim()))
            throw new IOException("The selected TV changed.");
        if (automatic && (!site.navatv.updates.Updates.enabled(context) || !idle(session))) return "The TV is in use.";
        site.navatv.updates.ReleaseFeed release = site.navatv.updates.ReleaseFeed.latest(TV_PACKAGE);
        String pkg = "dumpsys package " + TV_PACKAGE + " | grep -m 1 versionCode= || true";
        int installed = versionCode(shell(session, pkg));
        if (installed <= 0) throw new IOException("OpenNava is not installed on this TV.");
        if (automatic && installed >= FIRST_TV_WITH_STARTUP_UPDATES) return "Updates are managed on the TV.";
        if (release.versionCode <= installed) return "The TV is up to date.";
        File local = release.download(context);
        String remote = "/data/local/tmp/navatv-" + UUID.randomUUID() + ".apk";
        try {
            if (session != link || (automatic && (!site.navatv.updates.Updates.enabled(context) || !idle(session)))) return "The update will be retried when the TV is idle.";
            push(session, local, remote);
            if (!shell(session, "sha256sum " + remote).startsWith(release.sha256 + " "))
                throw new IOException("The TV received an incomplete update.");
            if (session != link || (automatic && (!site.navatv.updates.Updates.enabled(context) || !idle(session)))) return "The update will be retried when the TV is idle.";
            int beforeInstall = versionCode(shell(session, pkg));
            if (beforeInstall >= release.versionCode) return "The TV is up to date.";
            if (automatic && beforeInstall >= FIRST_TV_WITH_STARTUP_UPDATES) return "Updates are managed on the TV.";
            if (beforeInstall <= 0) throw new IOException("OpenNava was removed while downloading.");
            String result = shell(session, "pm install -r " + remote).trim();
            if (!"Success".equals(result) || versionCode(shell(session, pkg)) < release.versionCode)
                throw new IOException("The TV could not verify installation: " + result);
            return "OpenNava " + release.version + " is installed on the TV.";
        } finally {
            if (session.isConnectionEstablished()) try { shell(session, "rm -f " + remote); } catch (Exception ignored) {}
            local.delete();
        }
    }

    private boolean idle(AdbConnection session) throws Exception {
        String power = shell(session, "dumpsys power | grep -m1 mWakefulness= || true");
        if (!(power.contains("=Asleep") || power.contains("=Dozing"))) return false;
        return !shell(session, "dumpsys activity services " + TV_PACKAGE + " | grep -F RecordingService || true").contains("RecordingService");
    }

    public Device updateSenior() throws Exception {
        Device selected = device();
        if (selected == null) throw new IOException("Connect to a TV first.");
        Device before = discover(selected.host, selected.port);
        if (!before.canUpdateSenior) {
            target = before;
            if (before.seniorVersion >= BUNDLED_TV_VERSION) return before;
            throw new IOException("No update is available for this TV.");
        }
        File local = File.createTempFile("fernsehen-", ".apk", context.getCacheDir());
        String remote = "/data/local/tmp/fernsehen-" + UUID.randomUUID() + ".apk";
        AdbConnection session = active();
        try {
            MessageDigest sha = MessageDigest.getInstance("SHA-256");
            try (InputStream in = context.getAssets().open(APK_ASSET); OutputStream out = new FileOutputStream(local)) {
                byte[] buffer = new byte[65536];
                int count;
                while ((count = in.read(buffer)) != -1) { sha.update(buffer,0,count); out.write(buffer,0,count); }
            }
            PackageInfo archive = context.getPackageManager().getPackageArchiveInfo(local.getPath(),0);
            if (archive == null || !TV_PACKAGE.equals(archive.packageName) || archive.getLongVersionCode() != NATIVE_TV_VERSION)
                throw new IOException("The bundled TV app is invalid.");
            push(session, local, remote);
            String digest = shell("sha256sum " + remote).trim();
            if (!digest.startsWith(hex(sha.digest()) + " ")) throw new IOException("The update transfer was incomplete.");
            String result = shell("pm install -r " + remote).trim();
            if (!result.equals("Success")) throw new IOException("Installation declined: " + result);
            Device refreshed = discover(selected.host, selected.port);
            if (refreshed.canUpdateSenior) throw new IOException("Could not confirm the installed version.");
            target = refreshed;
            return refreshed;
        } finally {
            if (session == link && session.isConnectionEstablished()) {
                try { shell("rm -f " + remote); } catch (Exception ignored) {}
            }
            local.delete();
        }
    }

    private static String hex(byte[] bytes) {
        StringBuilder out = new StringBuilder(bytes.length*2);
        for (byte b : bytes) out.append(String.format(Locale.ROOT,"%02x",b & 255));
        return out.toString();
    }

    private static void syncHeader(OutputStream output, String id, int length) throws IOException {
        output.write(ByteBuffer.allocate(8).order(ByteOrder.LITTLE_ENDIAN)
                .put(id.getBytes(StandardCharsets.US_ASCII)).putInt(length).array());
    }

    private static void push(AdbConnection connection, File apk, String path) throws Exception {
        try (AdbStream stream = connection.open("sync:");
             InputStream file = new FileInputStream(apk)) {
            OutputStream output = stream.openOutputStream();
            byte[] destination = (path + ",33204").getBytes(StandardCharsets.UTF_8);
            syncHeader(output,"SEND",destination.length);
            output.write(destination);
            byte[] buffer = new byte[Math.min(65536,connection.getMaxData())];
            int count;
            while ((count = file.read(buffer)) != -1) {
                syncHeader(output,"DATA",count);
                output.write(buffer,0,count);
            }
            syncHeader(output,"DONE",(int)(System.currentTimeMillis()/1000));
            byte[] status = new byte[8];
            new DataInputStream(stream.openInputStream()).readFully(status);
            if (!"OKAY".equals(new String(status,0,4,StandardCharsets.US_ASCII))) throw new IOException("The TV could not save the update.");
        }
    }
}
