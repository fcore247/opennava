package site.navatv.companion;

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
    public static final String TV_PACKAGE = "site.navatv.launcher";
    private static final String OLD_NavaTV = "site.navatv.full";
    private static final int COMPANION_VERSION = 100;
    private static final int LIVE_TUNE_VERSION = 37;
    private static final String LIVE_CALL = "content call --uri content://site.navatv.launcher.companion --method ";
    private static final Pattern LIVE_CHANNEL = Pattern.compile("\\bchannel=([a-z0-9_-]{1,64})\\b");
    private static final String APK_ASSET = "OpenNava.apk";
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
        public final boolean canUpdateSenior, homeMigrationPending, supportsLiveTune, hasRtl;
        Device(String host, int port, String name, String model, int senior, int nava, boolean television, boolean migrateHome, String tvVersionName, String androidId) {
            this.host = host;
            this.port = port;
            this.name = name;
            this.model = model;
            this.androidId = androidId;
            this.seniorVersion = senior;
            this.navaVersion = nava;
            this.canUpdateSenior = television && senior < COMPANION_VERSION;
            this.homeMigrationPending = migrateHome;
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
        int nava = versionCode(packageVersion(OLD_NavaTV));
        if (!tv && launcher < 0 && nava < 0) throw new IOException("This address is not a supported TV.");
        if (alias.isEmpty() || alias.equals("null") || alias.equalsIgnoreCase(model))
            alias = mf.equalsIgnoreCase("Amazon") ? "Fire TV" : model;
        boolean migrateHome = false;
        if (mf.equalsIgnoreCase("Amazon") && nava >= 0) {
            String services = shell("settings get secure enabled_accessibility_services").trim();
            for (String active : services.split(":")) {
                if ((OLD_NavaTV + "/" + OLD_NavaTV + ".NavaHomeService").equals(active)
                        || (OLD_NavaTV + "/.NavaHomeService").equals(active)) migrateHome = true;
            }
        }
        String androidId = shell("settings get secure android_id").trim();
        if (!androidId.matches("(?i)[0-9a-f]{1,16}")) androidId = "";
        return new Device(host, port, alias, model, launcher, nava, tv, migrateHome, tvVersionName, androidId);
    }

    private String packageVersion(String pkg) throws Exception {
        return shell("dumpsys package " + pkg + " | grep -E -m 2 'versionCode=|versionName=' || true");
    }

    private static int versionCode(String packageVersion) {
        Matcher found = VERSION.matcher(packageVersion);
        return found.find() ? Integer.parseInt(found.group(1)) : -1;
    }

    public void key(int keyCode) throws Exception {
        switch (keyCode) {
            case 3: case 4: case 19: case 20: case 21: case 22: case 23: case 24: case 25: case 26:
            case 66: case 67: case 82: case 85: case 86: case 87: case 88: case 89: case 90:
            case 126: case 127: case 164: break;
            default: throw new IOException("Unknown remote control key.");
        }
        shell("input keyevent " + keyCode);
    }

    public void sendText(String text) throws Exception {
        if (text == null || !TEXT.matcher(text).matches())
            throw new IOException("Direct text entry supports unaccented Latin letters. Library search also supports accented characters.");
        shell("input text " + quote(text.replace(" ", "%s")));
    }

    public void search(String provider, String query) throws Exception {
        String value = query == null ? "" : query.trim();
        companion(value.isEmpty() ? "open" : "search", provider, value, "");
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
        if (device.seniorVersion < COMPANION_VERSION) throw new IOException("Install the TV update under Devices.");
        if (!"ard".equals(provider) && !"zdf".equals(provider) && !"arte".equals(provider))
            throw new IOException("Unknown library.");
        if ("search".equals(operation) && value.length() > 220) throw new IOException("Enter a shorter search.");
        String requestedTitle = title == null ? "" : title;
        if (requestedTitle.length() > 300) requestedTitle = requestedTitle.substring(0,300);
        launchCompanion("--es operation " + operation + " --es provider " + provider
                + " --es value " + quote(value) + " --es title " + quote(requestedTitle));
    }

    private void launchCompanion(String extras) throws Exception {
        String result = shell("am start -n " + TV_PACKAGE + "/.CompanionActivity -a " + TV_PACKAGE + ".COMPANION " + extras);
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
        try (AdbStream stream = session.open("shell,v2,raw:" + command)) {
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
            if (before.seniorVersion >= COMPANION_VERSION) return before;
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
            if (archive == null || !TV_PACKAGE.equals(archive.packageName) || archive.getLongVersionCode() != COMPANION_VERSION)
                throw new IOException("The bundled TV app is invalid.");
            push(session, local, remote);
            String digest = shell("sha256sum " + remote).trim();
            if (!digest.startsWith(hex(sha.digest()) + " ")) throw new IOException("The update transfer was incomplete.");
            String result = shell("pm install -r " + remote).trim();
            if (!result.equals("Success")) throw new IOException("Installation declined: " + result);
            Device refreshed = discover(selected.host, selected.port);
            if (refreshed.seniorVersion != COMPANION_VERSION) throw new IOException("Could not confirm the installed version.");
            target = refreshed;
            return refreshed.homeMigrationPending ? migrateFireHome() : refreshed;
        } finally {
            if (session == link && session.isConnectionEstablished()) {
                try { shell("rm -f " + remote); } catch (Exception ignored) {}
            }
            local.delete();
        }
    }

    /** Transfer only the legacy Fire TV Home service that is already active on this TV. */
    public Device migrateFireHome() throws Exception {
        Device current = device();
        if (current == null || current.seniorVersion < COMPANION_VERSION)
            throw new IOException("Install the current TV app first.");
        if (!current.homeMigrationPending) return current;
        try (InputStream script = context.getAssets().open("fire-home.sh")) {
            ByteArrayOutputStream text = new ByteArrayOutputStream();
            byte[] buffer = new byte[2048];
            int count;
            while ((count = script.read(buffer)) != -1) text.write(buffer, 0, count);
            shell(text.toString("UTF-8"));
            target = discover(current.host, current.port);
            if (target.homeMigrationPending) throw new IOException("The old service is still active.");
            return target;
        } catch (Exception source) {
            throw new IOException("The TV app is installed. Home button migration is incomplete.", source);
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
