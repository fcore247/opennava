package site.navatv.launcher;

import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;

/** Phone-facing view of the same channel line-up and broadcaster schedules as the TV. */
public final class PhoneGuide {
    private static final ZoneId BERLIN = ZoneId.of("Europe/Berlin");
    private static final DateTimeFormatter CLOCK = DateTimeFormatter.ofPattern("HH:mm", Locale.ENGLISH).withZone(BERLIN);
    private static final DateTimeFormatter DAY = DateTimeFormatter.ofPattern("EEEE, d MMMM", Locale.ENGLISH).withZone(BERLIN);

    public static final class Channel {
        public final String id, name, provider;
        public final int number;
        public final boolean regional, hasGuide;
        private final LiveGuide.Channel source;

        private Channel(LiveGuide.Channel source, int number) {
            this.source = source;
            this.number = number;
            id = source.id;
            name = source.name;
            provider = source.provider;
            regional = source.regional;
            hasGuide = !id.equals("rtl") && !id.equals("panoramabilder");
        }
    }

    public static final class Show {
        public final String title, provider, detail, eventId;
        public final long start, end;

        private Show(LiveGuide.Programme source) {
            title = source.title;
            provider = source.provider;
            detail = source.detail;
            eventId = source.eventId;
            start = source.start;
            end = source.end;
        }
    }

    public static final class Schedule {
        public final List<Show> shows;
        /** Either neighbour, as well as the current show, may be absent in a broadcaster's schedule. */
        public final Show previous, current, next;

        private Schedule(List<LiveGuide.Programme> rows, long at) {
            List<Show> result = new ArrayList<>(rows.size());
            Show last = null, onAir = null, following = null;
            for (LiveGuide.Programme row : rows) {
                Show show = new Show(row);
                result.add(show);
                if (show.end <= at && (last == null || last.end < show.end)) last = show;
                else if (show.start <= at && at < show.end) onAir = show;
                else if (show.start > at && following == null) following = show;
            }
            shows = Collections.unmodifiableList(result);
            previous = last;
            current = onAir;
            next = following;
        }
    }

    public static List<Channel> channels(boolean includeRtl) {
        List<Channel> result = new ArrayList<>();
        for (LiveGuide.Channel channel : LiveGuide.channels(includeRtl)) result.add(new Channel(channel, result.size() + 1));
        return Collections.unmodifiableList(result);
    }

    /** Call off the UI thread. A source failure is distinct from an unavailable or empty schedule. */
    public static Schedule load(Channel channel, long atMillis) throws Exception {
        List<LiveGuide.Programme> rows = channel.hasGuide
                ? LiveGuide.programmes(channel.source, atMillis) : Collections.<LiveGuide.Programme>emptyList();
        return new Schedule(rows, atMillis);
    }

    public static String clock(long stamp) { return CLOCK.format(Instant.ofEpochMilli(stamp)); }
    public static String dayLabel(long stamp) { return DAY.format(Instant.ofEpochMilli(stamp)); }

    private PhoneGuide() {}
}
