package site.navatv.launcher;

import android.net.Uri;
import android.util.AtomicFile;

import androidx.media3.common.C;
import androidx.media3.exoplayer.hls.playlist.HlsMediaPlaylist;
import androidx.media3.exoplayer.hls.playlist.HlsMultivariantPlaylist;
import androidx.media3.exoplayer.hls.playlist.HlsPlaylist;
import androidx.media3.exoplayer.hls.playlist.HlsPlaylistParser;

import java.io.*;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Keeps a playable, private VOD playlist of the live fragments actually received. */
final class RecordingHls {
    static final long RESERVE = 192L * 1024 * 1024;
    private static final int MAX_BITRATE = 1_800_000;
    private final File dir;
    private final long from;
    private final List<Track> tracks = new ArrayList<>();
    private boolean gaps;

    private static final class Capacity extends IOException {
        Capacity(String message) {
            super(message);
        }
    }

    interface Stop {
        boolean requested();
    }

    RecordingHls(File dir, long from) {
        this.dir = dir;
        this.from = from;
    }

    private static HttpURLConnection open(String address, long off, long length)
            throws IOException {
        return open(address, off, length, false);
    }

    private static HttpURLConnection open(String address, long off, long length, boolean prefix)
            throws IOException {
        URL url = new URL(address);
        for (int n = 0; n < 6; n++) {
            if (!"https".equalsIgnoreCase(url.getProtocol()) || url.getUserInfo() != null)
                throw new IOException("Invalid channel address.");
            HttpURLConnection con = (HttpURLConnection) url.openConnection();
            con.setConnectTimeout(6500);
            con.setReadTimeout(9000);
            con.setInstanceFollowRedirects(false);
            con.setRequestProperty("User-Agent", "Mozilla/5.0 (Android TV; Fernsehen)");
            if (length >= 0)
                con.setRequestProperty("Range", "bytes=" + off + "-" + (off + length - 1));
            try {
                int code = con.getResponseCode();
                if (code >= 300 && code < 400 && con.getHeaderField("Location") != null) {
                    url = new URL(url, con.getHeaderField("Location"));
                    con.disconnect();
                    continue;
                }
                if (code != 200 && code != 206
                        || length >= 0 && code != 206 && !(prefix && off == 0))
                    throw new IOException("Channel returned HTTP " + code + ".");
                return con;
            } catch (IOException e) {
                con.disconnect();
                throw e;
            }
        }
        throw new IOException("Too many redirects.");
    }

    private static HlsPlaylist playlist(String url) throws IOException {
        HttpURLConnection con = open(url, 0, -1);
        try (InputStream in = con.getInputStream()) {
            return new HlsPlaylistParser().parse(Uri.parse(con.getURL().toString()), in);
        } finally {
            con.disconnect();
        }
    }

    private static int rate(HlsMultivariantPlaylist.Variant v) {
        return v.format.averageBitrate > 0 ? v.format.averageBitrate : v.format.peakBitrate;
    }

    private static HlsMultivariantPlaylist.Variant videoVariant(HlsMultivariantPlaylist master)
            throws IOException {
        HlsMultivariantPlaylist.Variant best = null;
        for (HlsMultivariantPlaylist.Variant v : master.variants) {
            if ((v.format.roleFlags & C.ROLE_FLAG_TRICK_PLAY) != 0) continue;
            if (v.format.height <= 0
                    && v.format.codecs != null
                    && !v.format.codecs.matches(".*(avc|hev|hvc|av01|vp09|dvhe|dvh1).*")) continue;
            int b = rate(v);
            if (b <= 0) continue;
            if (best == null
                    || b <= MAX_BITRATE && (rate(best) > MAX_BITRATE || b > rate(best))
                    || rate(best) > MAX_BITRATE && b < rate(best)) best = v;
        }
        if (best == null) throw new IOException("The channel has no video track available to record.");
        return best;
    }

    private static final LinkedHashMap<String, Long> verified = new LinkedHashMap<>(32, .75f, true);
    private static final int PROBE_BYTES = 4096;
    private static final int MAX_VOD_PLAYLIST = 4 * 1024 * 1024;

    // A resolver or signed URL alone does not mean that the user's device can read the video.
    // Only a short, recognizable media response may replace a recording stored on the TV.
    static boolean verifyArchive(String url) {
        if (url == null || !url.startsWith("https://")) return false;
        synchronized (verified) {
            Long date = verified.get(url);
            if (date != null && android.os.SystemClock.elapsedRealtime() - date < 300_000)
                return true;
        }
        try {
            byte[] signature = prefix(url, 0, PROBE_BYTES);
            if (hls(signature)) {
                String next = url;
                HlsMediaPlaylist media = null;
                for (int depth = 0; depth < 3; depth++) {
                    HttpURLConnection con = open(next, 0, -1);
                    HlsPlaylist sheet;
                    try (InputStream in = con.getInputStream()) {
                        if (con.getContentLengthLong() > MAX_VOD_PLAYLIST) return false;
                        byte[] bytes = bounded(in, MAX_VOD_PLAYLIST + 1);
                        if (bytes.length > MAX_VOD_PLAYLIST) return false;
                        sheet =
                                new HlsPlaylistParser()
                                        .parse(
                                                Uri.parse(con.getURL().toString()),
                                                new ByteArrayInputStream(bytes));
                    } finally {
                        con.disconnect();
                    }
                    if (sheet instanceof HlsMediaPlaylist) {
                        media = (HlsMediaPlaylist) sheet;
                        break;
                    }
                    if (!(sheet instanceof HlsMultivariantPlaylist)) return false;
                    next = videoVariant((HlsMultivariantPlaylist) sheet).url.toString();
                }
                if (media == null
                        || !media.hasEndTag
                        || media.protectionSchemes != null
                        || media.segments.isEmpty()) return false;
                // Probe an interior fragment; an intro/logo can be available even for restricted
                // episodes.
                HlsMediaPlaylist.Segment seg = media.segments.get(media.segments.size() / 2);
                if (seg.hasGapTag
                        || seg.fullSegmentEncryptionKeyUri != null
                        || seg.drmInitData != null) return false;
                String fragment = new URL(new URL(media.baseUri), seg.url).toString();
                signature =
                        prefix(
                                fragment,
                                seg.byteRangeOffset,
                                seg.byteRangeLength >= 0
                                        ? (int) Math.min(PROBE_BYTES, seg.byteRangeLength)
                                        : PROBE_BYTES);
                if (!container(signature, true)) return false;
            } else if (!container(signature, false)) return false;
        } catch (IOException | RuntimeException unavailable) {
            return false;
        }
        synchronized (verified) {
            verified.put(url, android.os.SystemClock.elapsedRealtime());
            while (verified.size() > 50) verified.remove(verified.keySet().iterator().next());
        }
        return true;
    }

    private static boolean hls(byte[] head) {
        String text = new String(head, 0, Math.min(head.length, 128), StandardCharsets.UTF_8);
        return text.replace("\uFEFF", "").trim().startsWith("#EXTM3U");
    }

    private static boolean container(byte[] b, boolean fragment) {
        if (b.length < 32) return false;
        String atom = new String(b, 4, 4, StandardCharsets.US_ASCII);
        if (atom.equals("ftyp")
                || fragment
                        && (atom.equals("styp")
                                || atom.equals("moof")
                                || atom.equals("sidx")
                                || atom.equals("mdat"))) return true;
        if (fragment)
            for (int n = 0; n < 512 && n + 376 < b.length; n++) {
                if (b[n] == 0x47 && b[n + 188] == 0x47 && b[n + 376] == 0x47) return true;
            }
        return false;
    }

    private static byte[] prefix(String url, long off, int n) throws IOException {
        if (n <= 0) throw new IOException();
        HttpURLConnection con = open(url, Math.max(0, off), n, true);
        try (InputStream in = con.getInputStream()) {
            return bounded(in, n);
        } finally {
            con.disconnect();
        }
    }

    private static byte[] bounded(InputStream in, int max) throws IOException {
        ByteArrayOutputStream buf = new ByteArrayOutputStream(Math.min(max, 8192));
        byte[] block = new byte[Math.min(8192, max)];
        while (buf.size() < max) {
            int n = in.read(block, 0, Math.min(block.length, max - buf.size()));
            if (n < 0) break;
            buf.write(block, 0, n);
        }
        return buf.toByteArray();
    }

    void prepare(String url) throws IOException {
        if (!dir.isDirectory() && !dir.mkdirs())
            throw new IOException("No recording folder available.");
        HlsPlaylist top = playlist(url);
        String video = url, audio = null;
        int bandwidth = MAX_BITRATE;
        if (top instanceof HlsMultivariantPlaylist) {
            HlsMultivariantPlaylist master = (HlsMultivariantPlaylist) top;
            HlsMultivariantPlaylist.Variant best = videoVariant(master);
            video = best.url.toString();
            bandwidth = rate(best);
            HlsMultivariantPlaylist.Rendition preferred = null;
            int score = -1;
            for (HlsMultivariantPlaylist.Rendition r : master.audios) {
                if (r.url == null
                        || best.audioGroupId == null
                        || !best.audioGroupId.equals(r.groupId)) continue;
                int value =
                        (r.format.language != null && r.format.language.startsWith("de") ? 4 : 0)
                                + ((r.format.selectionFlags & C.SELECTION_FLAG_DEFAULT) != 0
                                        ? 2
                                        : 0)
                                + ((r.format.roleFlags & C.ROLE_FLAG_DESCRIBES_VIDEO) == 0 ? 1 : 0);
                if (value > score) {
                    preferred = r;
                    score = value;
                }
            }
            if (preferred != null) audio = preferred.url.toString();
        }
        tracks.add(new Track("video", video));
        if (audio != null) tracks.add(new Track("audio", audio));
        String audioLine =
                audio != null
                        ? "#EXT-X-MEDIA:TYPE=AUDIO,GROUP-ID=\"a\",NAME=\"Ton\",DEFAULT=YES,AUTOSELECT=YES,URI=\"audio.m3u8\"\n"
                        : "";
        write(
                new File(dir, "index.m3u8"),
                "#EXTM3U\n#EXT-X-VERSION:7\n"
                        + audioLine
                        + "#EXT-X-STREAM-INF:BANDWIDTH="
                        + bandwidth
                        + (audio != null ? ",AUDIO=\"a\"" : "")
                        + "\nvideo.m3u8\n");
    }

    /**
     * Start at the live edge on first use. Subsequent polls fill every outstanding media sequence.
     */
    boolean capture(long until, Stop stop) throws Exception {
        try {
            return captureSegments(until, stop);
        } finally {
            for (Track track : tracks) track.flush();
        }
    }

    private boolean captureSegments(long until, Stop stop) throws Exception {
        long retrySince = 0;
        do {
            try {
                for (Track track : tracks) {
                    if (stop.requested()) break;
                    HlsPlaylist pl = playlist(track.url);
                    if (!(pl instanceof HlsMediaPlaylist))
                        throw new IOException("Unrecognised live playlist.");
                    track.capture((HlsMediaPlaylist) pl, until, stop);
                }
                retrySince = 0;
            } catch (Capacity noSpace) {
                throw noSpace;
            } catch (IOException temporary) {
                if (retrySince == 0) retrySince = System.currentTimeMillis();
                if (System.currentTimeMillis() - retrySince > 24000
                        || System.currentTimeMillis() >= until) throw temporary;
            }
            long remaining = until - System.currentTimeMillis();
            if (remaining <= 0 || stop.requested()) break;
            Thread.sleep(Math.min(2200, remaining));
        } while (!Thread.currentThread().isInterrupted());
        return !gaps && retrySince == 0;
    }

    static boolean playable(File d) {
        if (!new File(d, "index.m3u8").isFile() || !new File(d, "video.m3u8").isFile())
            return false;
        try {
            String master =
                    new String(
                            Files.readAllBytes(new File(d, "index.m3u8").toPath()),
                            StandardCharsets.UTF_8);
            return !master.contains("audio.m3u8") || new File(d, "audio.m3u8").isFile();
        } catch (IOException failed) {
            return false;
        }
    }

    private static void write(File target, String text) throws IOException {
        AtomicFile atomic = new AtomicFile(target);
        FileOutputStream out = atomic.startWrite();
        try {
            out.write(text.getBytes(StandardCharsets.UTF_8));
            atomic.finishWrite(out);
        } catch (IOException failure) {
            atomic.failWrite(out);
            throw failure;
        }
    }

    private final class Track {
        final String name, url;
        final File index;
        StringBuilder body = new StringBuilder();
        long last = -1, targetSec = 15;
        long discontinuity = -1;
        int uncommitted;
        long edgeDelay = Long.MIN_VALUE;
        String initKey = "";
        String initFile = "";

        Track(String name, String url) throws IOException {
            this.name = name;
            this.url = url;
            index = new File(dir, name + ".m3u8");
            if (index.exists()) {
                String old = new String(Files.readAllBytes(index.toPath()), StandardCharsets.UTF_8);
                int start = old.indexOf("#EXT-X-DISCONTINUITY\n");
                if (start >= 0) {
                    body.append(old.substring(start).replace("#EXT-X-ENDLIST\n", ""));
                    Matcher m = Pattern.compile(name + "-(\\d+)\\.bin").matcher(old);
                    while (m.find()) last = Long.parseLong(m.group(1));
                }
            }
        }

        void capture(HlsMediaPlaylist pl, long until, Stop stop) throws IOException {
            if (pl.segments.isEmpty()) return;
            long max = pl.mediaSequence + pl.segments.size() - 1;
            if (last > max) throw new IOException("The broadcaster changed the stream sequence.");
            long first = pl.mediaSequence;
            if (last < 0 && !pl.hasProgramDateTime) first = max;
            if (last >= 0 && pl.mediaSequence > last + 1) gaps = true;
            first = Math.max(first, last + 1);
            targetSec = Math.max(targetSec, (pl.targetDurationUs + 999999) / 1000000);
            long clock = System.currentTimeMillis();
            if (pl.hasProgramDateTime && edgeDelay == Long.MIN_VALUE)
                edgeDelay = clock - (pl.startTimeUs + pl.durationUs) / 1000;
            long anchor =
                    pl.hasProgramDateTime
                            ? pl.startTimeUs / 1000 + edgeDelay
                            : clock - pl.durationUs / 1000;
            // A time-shift playlist may contain hours of fragments. Skip its historical prefix at
            // once.
            if (last < 0 && pl.hasProgramDateTime) {
                int lo = 0, hi = pl.segments.size();
                while (lo < hi) {
                    int mid = lo + (hi - lo) / 2;
                    HlsMediaPlaylist.Segment s = pl.segments.get(mid);
                    if (anchor + (s.relativeStartTimeUs + s.durationUs) / 1000 <= from - 1000)
                        lo = mid + 1;
                    else hi = mid;
                }
                first = pl.mediaSequence + lo;
            }
            for (long seq = first; seq <= max && !stop.requested(); seq++) {
                HlsMediaPlaylist.Segment seg = pl.segments.get((int) (seq - pl.mediaSequence));
                long time = anchor + seg.relativeStartTimeUs / 1000;
                if (time + seg.durationUs / 1000 <= from - 1000 || time >= until) continue;
                if (seg.fullSegmentEncryptionKeyUri != null
                        || seg.drmInitData != null
                        || pl.protectionSchemes != null)
                    throw new IOException(
                            "This programme is protected and cannot be recorded locally.");
                if (seg.hasGapTag) {
                    gaps = true;
                    continue;
                }
                String prepend = "";
                long disc = pl.discontinuitySequence + seg.relativeDiscontinuitySequence;
                if (disc != discontinuity || seq != last + 1) prepend = "#EXT-X-DISCONTINUITY\n";
                if (seg.initializationSegment != null) {
                    HlsMediaPlaylist.Segment map = seg.initializationSegment;
                    String key = map.url + "@" + map.byteRangeOffset + ":" + map.byteRangeLength;
                    if (!key.equals(initKey)) {
                        initFile = name + "-init-" + seq + ".map";
                        copy(pl.baseUri, map, initFile);
                        initKey = key;
                    }
                    if (prepend.length() > 0 || body.indexOf("URI=\"" + initFile + "\"") < 0)
                        prepend += "#EXT-X-MAP:URI=\"" + initFile + "\"\n";
                }
                String out = name + "-" + seq + ".bin";
                copy(pl.baseUri, seg, out);
                body.append(prepend)
                        .append(
                                String.format(
                                        Locale.ROOT,
                                        "#EXTINF:%.6f,\n",
                                        seg.durationUs / 1_000_000.0))
                        .append(out)
                        .append('\n');
                if (last < 0 && "video".equals(name))
                    write(new File(dir, "start-offset"), Long.toString(Math.max(0, from - time)));
                uncommitted++;
                if (last < 0 || uncommitted >= 10) flush();
                last = seq;
                discontinuity = disc;
            }
        }

        void flush() throws IOException {
            if (uncommitted == 0) return;
            write(
                    index,
                    "#EXTM3U\n#EXT-X-VERSION:7\n#EXT-X-TARGETDURATION:"
                            + targetSec
                            + "\n#EXT-X-MEDIA-SEQUENCE:0\n#EXT-X-PLAYLIST-TYPE:VOD\n"
                            + body
                            + "#EXT-X-ENDLIST\n");
            uncommitted = 0;
        }

        private void copy(String base, HlsMediaPlaylist.Segment seg, String out)
                throws IOException {
            if (dir.getUsableSpace() < RESERVE)
                throw new Capacity("Your TV is almost out of storage.");
            String address = new URL(new URL(base), seg.url).toString();
            HttpURLConnection con = open(address, seg.byteRangeOffset, seg.byteRangeLength);
            File target = new File(dir, out), tmp = new File(dir, out + ".part");
            try (InputStream in = con.getInputStream();
                    OutputStream file = new BufferedOutputStream(new FileOutputStream(tmp))) {
                byte[] buf = new byte[32768];
                int n;
                long size = 0;
                while ((n = in.read(buf)) != -1) {
                    size += n;
                    if (size > 48L * 1024 * 1024)
                        throw new IOException("Video segment too large.");
                    file.write(buf, 0, n);
                }
                if (size == 0
                        || seg.byteRangeLength >= 0 && size != seg.byteRangeLength
                        || con.getContentLengthLong() >= 0 && size != con.getContentLengthLong())
                    throw new IOException("Incomplete video segment.");
            } catch (IOException failed) {
                tmp.delete();
                throw failed;
            } finally {
                con.disconnect();
            }
            if (!tmp.renameTo(target))
                throw new IOException("Could not save the video segment.");
        }
    }
}
