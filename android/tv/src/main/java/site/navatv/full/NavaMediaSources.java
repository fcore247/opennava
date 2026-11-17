package site.navatv.full;

import android.content.Context;
import android.net.Uri;
import android.util.Log;

import com.google.android.exoplayer2.C;
import com.google.android.exoplayer2.ext.cronet.CronetDataSourceFactory;
import com.google.android.exoplayer2.ext.cronet.CronetEngineWrapper;
import com.google.android.exoplayer2.source.ExtractorMediaSource;
import com.google.android.exoplayer2.source.MediaSource;
import com.google.android.exoplayer2.source.dash.DashMediaSource;
import com.google.android.exoplayer2.source.dash.DefaultDashChunkSource;
import com.google.android.exoplayer2.source.dash.manifest.DashManifestParser2;
import com.google.android.exoplayer2.source.hls.HlsMediaSource;
import com.google.android.exoplayer2.source.sabr.DefaultSabrChunkSource;
import com.google.android.exoplayer2.source.sabr.SabrMediaSource;
import com.google.android.exoplayer2.source.sabr.manifest.SabrManifestParser;
import com.google.android.exoplayer2.source.sabr.parser.exceptions.SabrBackoffError;
import com.google.android.exoplayer2.source.sabr.parser.exceptions.SabrPoTokenError;
import com.google.android.exoplayer2.source.sabr.parser.exceptions.SabrReloadPlayerError;
import com.google.android.exoplayer2.upstream.DataSource;
import com.google.android.exoplayer2.upstream.DefaultDataSourceFactory;
import com.google.android.exoplayer2.upstream.DefaultHttpDataSourceFactory;
import com.google.android.exoplayer2.upstream.DefaultLoadErrorHandlingPolicy;
import com.liskovsoft.googlecommon.common.helpers.DefaultHeaders;
import com.liskovsoft.mediaserviceinterfaces.data.MediaItemFormatInfo;
import com.liskovsoft.mediaserviceinterfaces.data.MediaFormat;

import com.liskovsoft.sharedutils.cronet.CronetManager;
import org.chromium.net.CronetEngine;
import java.util.concurrent.Executor;
import java.util.concurrent.Executors;
import java.io.IOException;
import java.util.List;

/** OpenNava adapter for the source modules shipped by SmartTube. No SmartTube UI dependency. */
final class NavaMediaSources {
    private static final Executor NETWORK_CALLBACKS = Executors.newSingleThreadExecutor();
    private final Context application;
    private final DataSource.Factory mediathek;

    NavaMediaSources(Context context) {
        application = context.getApplicationContext();
        mediathek = new DefaultDataSourceFactory(context.getApplicationContext(), null,
                new DefaultHttpDataSourceFactory("OpenNava/0.4.0 (Android TV)", null, 15_000, 15_000, false));
    }

    // Called on the resolver thread. The upstream engine shares HTTP/2 and QUIC connections
    // between audio, video and successive titles. Each player owns its timeout policy.
    private DataSource.Factory youtubeNetwork(int readTimeoutMs, boolean usePlatformNetwork) {
        DefaultHttpDataSourceFactory fallback = new DefaultHttpDataSourceFactory(
                DefaultHeaders.APP_USER_AGENT, null, 8_000, readTimeoutMs, true);
        if (!usePlatformNetwork) {
            try {
                CronetEngine engine = CronetManager.getEngine(application);
                if (engine != null) {
                    CronetDataSourceFactory http = new CronetDataSourceFactory(new CronetEngineWrapper(engine),
                            NETWORK_CALLBACKS, null, 8_000, readTimeoutMs, true, fallback);
                    http.getDefaultRequestProperties().set("User-Agent", DefaultHeaders.APP_USER_AGENT);
                    Log.i("OpenNava", "YouTube transport=cronet");
                    return new DefaultDataSourceFactory(application, null, http);
                }
            } catch (RuntimeException | LinkageError unavailable) {
                Log.w("OpenNava", "Cronet unavailable: " + unavailable.getClass().getSimpleName());
            }
        }
        Log.i("OpenNava", "YouTube transport=platform");
        return new DefaultDataSourceFactory(application, null, fallback);
    }

    MediaSource create(MediaItemFormatInfo info) throws IOException {
        return create(info, false);
    }

    MediaSource create(MediaItemFormatInfo info, boolean usePlatformNetwork) throws IOException {
        if (info == null || info.isUnplayable() || !info.containsMedia()) {
            throw new IOException("Video unavailable");
        }
        int videos = 0, audios = 0;
        List<MediaFormat> adaptive = info.getAdaptiveFormats();
        if (adaptive != null) for (MediaFormat format : adaptive) {
            String mime = format.getMimeType();
            if (mime != null && mime.startsWith("video/")) videos++;
            if (mime != null && mime.startsWith("audio/")) audios++;
        }
        Log.i("OpenNava", "YouTube formats model=" + info.getClass().getSimpleName()
                + " adaptiveVideo=" + videos + " adaptiveAudio=" + audios
                + " dash=" + info.containsDashFormats() + " sabr=" + info.containsSabrFormats()
                + " hls=" + info.containsHlsUrl() + " progressive=" + info.containsUrlFormats());
        // A stalled SABR endpoint should yield quickly to its alternate CDN.
        boolean useSabr = !info.containsDashFormats() && info.containsSabrFormats();
        DataSource.Factory network = youtubeNetwork(useSabr ? 4_000 : 15_000, usePlatformNetwork);
        // Prefer the same adaptive-format order as SmartTube's VideoLoaderController.
        if (info.containsDashFormats()) {
            Log.i("OpenNava", "YouTube source=adaptive-dash");
            return new DashMediaSource.Factory(new DefaultDashChunkSource.Factory(network, 1), null)
                    .createMediaSource(new DashManifestParser2().parse(info));
        }
        if (info.containsSabrFormats()) {
            Log.i("OpenNava", "YouTube source=adaptive-sabr");
            return new SabrMediaSource.Factory(new DefaultSabrChunkSource.Factory(network, 1), null)
                    .setLoadErrorHandlingPolicy(new SabrPolicy())
                    .createMediaSource(new SabrManifestParser().parse(info));
        }
        if (info.containsHlsUrl() && info.getHlsManifestUrl() != null) {
            Log.i("OpenNava", "YouTube source=hls");
            return new HlsMediaSource.Factory(network).createMediaSource(Uri.parse(info.getHlsManifestUrl()));
        }
        if (info.containsDashUrl() && info.getDashManifestUrl() != null) {
            Log.i("OpenNava", "YouTube source=dash-url");
            return new DashMediaSource.Factory(network).createMediaSource(Uri.parse(info.getDashManifestUrl()));
        }
        if (info.containsUrlFormats()) {
            List<String> urls = info.createUrlList();
            if (urls != null && !urls.isEmpty()) {
                Log.i("OpenNava", "YouTube source=progressive");
                return new ExtractorMediaSource.Factory(network).createMediaSource(Uri.parse(urls.get(0)));
            }
        }
        throw new IOException("No playable source");
    }

    MediaSource createMediathek(String streamUrl, String mimeType) throws IOException {
        if (!MediathekService.isTrustedStreamUrl(streamUrl)) throw new IOException("Untrusted stream");
        Uri uri = Uri.parse(streamUrl);
        String path = uri.getPath() == null ? "" : uri.getPath().toLowerCase(java.util.Locale.ROOT);
        String type = mimeType == null ? "" : mimeType.toLowerCase(java.util.Locale.ROOT);
        if (type.contains("mpegurl") || path.endsWith(".m3u8")) {
            Log.i("OpenNava", "Mediathek source=hls");
            return new HlsMediaSource.Factory(mediathek).createMediaSource(uri);
        }
        if (type.equals("video/mp4") || path.endsWith(".mp4")) {
            Log.i("OpenNava", "Mediathek source=mp4");
            return new ExtractorMediaSource.Factory(mediathek).createMediaSource(uri);
        }
        throw new IOException("Unsupported public stream type");
    }

    static <T extends Throwable> T findCause(Throwable error, Class<T> type) {
        Throwable cursor = error;
        for (int depth = 0; cursor != null && depth < 16; depth++, cursor = cursor.getCause()) {
            if (type.isInstance(cursor)) return type.cast(cursor);
        }
        return null;
    }

    /** Server-directed reload/backoff must reach the Activity instead of retrying stale requests. */
    private static final class SabrPolicy extends DefaultLoadErrorHandlingPolicy {
        @Override public long getRetryDelayMsFor(int dataType, long loadDurationMs, IOException error, int errorCount) {
            if (findCause(error, SabrReloadPlayerError.class) != null
                    || findCause(error, SabrBackoffError.class) != null
                    || findCause(error, SabrPoTokenError.class) != null) return C.TIME_UNSET;
            if (error.getMessage() != null && error.getMessage().contains("Wait 5 sec")) return 5_000;
            return super.getRetryDelayMsFor(dataType, loadDurationMs, error, errorCount);
        }
    }
}
