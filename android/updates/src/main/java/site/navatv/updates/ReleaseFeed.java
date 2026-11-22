package site.navatv.updates;

import android.content.Context;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.content.pm.Signature;
import android.os.Build;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Locale;

/** Only stable releases from this repository and APKs signed by OpenNava are accepted. */
public final class ReleaseFeed {
    public static final String TV = "site.navatv.launcher";
    public static final String PREVIEW = "site.navatv.rustpreview";
    public static final String PHONE = "site.navatv.companion";
    private static final String RELEASES = "https://github.com/fcore247/opennava/releases/";
    private static final String SIGNER = "578636674ca41cd11a62d70f49f3ea043bc685cee26c68d787c45b9c238b9998";
    private static final long MAX_APK = 256L * 1024 * 1024;

    public final String version, packageName, asset, sha256;
    private final String releaseTag;
    public final long versionCode;

    private ReleaseFeed(JSONObject manifest, String pkg) throws Exception {
        String key, expectedAsset;
        if (TV.equals(pkg)) { key = "tv"; expectedAsset = "OpenNava.apk"; }
        else if (PHONE.equals(pkg)) { key = "phone"; expectedAsset = "OpenNava-Companion.apk"; }
        else if (PREVIEW.equals(pkg)) { key = "preview"; expectedAsset = "OpenNava-Rust-TV.apk"; }
        else throw new IOException("Unknown update target.");
        JSONObject entry = manifest.getJSONObject("apps").getJSONObject(key);
        releaseTag = manifest.getString("version");
        version = PREVIEW.equals(pkg) ? entry.getString("versionName") : releaseTag;
        packageName = entry.getString("package");
        versionCode = entry.getLong("versionCode");
        sha256 = entry.getString("sha256");
        asset = entry.getString("asset");
        String semantic = "[0-9]+\\.[0-9]+\\.[0-9]+";
        if (manifest.getInt("schema") != 1 || !releaseTag.matches(semantic) || !version.matches(semantic) ||
                !pkg.equals(packageName) || versionCode <= 0 || !sha256.matches("[a-f0-9]{64}") ||
                !asset.equals(expectedAsset)) throw new IOException("Invalid OpenNava release information.");
    }

    private static HttpURLConnection open(String address) throws IOException {
        URL url = new URL(address);
        for (int attempt = 0; attempt < 7; attempt++) {
            String host = url.getHost();
            if (!"https".equals(url.getProtocol()) || url.getUserInfo() != null ||
                    !(host.equals("github.com") || host.endsWith(".githubusercontent.com")))
                throw new IOException("Unexpected release host.");
            HttpURLConnection connection = (HttpURLConnection)url.openConnection();
            connection.setInstanceFollowRedirects(false);
            connection.setConnectTimeout(12000);
            connection.setReadTimeout(40000);
            connection.setRequestProperty("User-Agent", "OpenNava-Updater");
            int status = connection.getResponseCode();
            if (status == 200) return connection;
            String redirect = connection.getHeaderField("Location");
            connection.disconnect();
            if (status < 300 || status >= 400 || redirect == null)
                throw new IOException("Release server returned HTTP " + status + ".");
            url = new URL(url, redirect);
        }
        throw new IOException("Too many release redirects.");
    }

    public static ReleaseFeed latest(String pkg) throws Exception {
        HttpURLConnection c = open(RELEASES + "latest/download/update.json");
        try (InputStream in = c.getInputStream(); ByteArrayOutputStream body = new ByteArrayOutputStream()) {
            byte[] buf = new byte[4096];
            int n;
            while ((n = in.read(buf)) != -1) {
                if (body.size() + n > 65536) throw new IOException("Update manifest too large.");
                body.write(buf, 0, n);
            }
            return new ReleaseFeed(new JSONObject(body.toString(StandardCharsets.UTF_8.name())), pkg);
        } finally { c.disconnect(); }
    }

    /** Caller owns the returned temporary file. Validation occurs before the file is returned. */
    public File download(Context context) throws Exception {
        File apk = File.createTempFile("navatv-release-", ".apk", context.getCacheDir());
        boolean valid = false;
        try {
            HttpURLConnection c = open(RELEASES + "download/v" + releaseTag + "/" + asset);
            try (InputStream in = c.getInputStream(); FileOutputStream out = new FileOutputStream(apk)) {
                MessageDigest digest = MessageDigest.getInstance("SHA-256");
                byte[] buffer = new byte[65536];
                long size = 0;
                int n;
                while ((n = in.read(buffer)) != -1) {
                    if (Thread.currentThread().isInterrupted()) throw new IOException("Update interrupted.");
                    if ((size += n) > MAX_APK) throw new IOException("Update package too large.");
                    out.write(buffer, 0, n);
                    digest.update(buffer, 0, n);
                }
                if (!hex(digest.digest()).equals(sha256)) throw new IOException("Package checksum does not match the release.");
            } finally { c.disconnect(); }
            PackageInfo info = context.getPackageManager().getPackageArchiveInfo(apk.getPath(),
                    Build.VERSION.SDK_INT >= 28 ? PackageManager.GET_SIGNING_CERTIFICATES : PackageManager.GET_SIGNATURES);
            if (info == null || !packageName.equals(info.packageName) || code(info) != versionCode ||
                    !version.equals(info.versionName)) throw new IOException("Wrong package or release version.");
            if (!officialSigner(info))
                throw new IOException("This package was not signed by OpenNava.");
            valid = true;
            return apk;
        } finally { if (!valid) apk.delete(); }
    }

    static boolean officialSigner(PackageInfo info) throws Exception {
        Signature[] signatures = Build.VERSION.SDK_INT >= 28
                ? (info.signingInfo == null ? null : info.signingInfo.getApkContentsSigners()) : info.signatures;
        return signatures != null && signatures.length == 1 &&
                hex(MessageDigest.getInstance("SHA-256").digest(signatures[0].toByteArray())).equals(SIGNER);
    }

    public static long code(PackageInfo info) {
        return Build.VERSION.SDK_INT >= 28 ? info.getLongVersionCode() : info.versionCode;
    }

    private static String hex(byte[] bytes) {
        StringBuilder text = new StringBuilder(bytes.length * 2);
        for (byte b : bytes) text.append(String.format(Locale.ROOT, "%02x", b & 255));
        return text.toString();
    }
}
