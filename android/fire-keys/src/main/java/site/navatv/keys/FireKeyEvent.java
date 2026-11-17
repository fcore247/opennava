package site.navatv.keys;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Recognizes the Home and Netflix shortcuts emitted by the Fire TV framework. */
final class FireKeyEvent {
    private static final Pattern LINE = Pattern.compile(
            "^\\s*(\\d{9,})\\.(\\d{3})\\d*\\s+(\\w+)\\s+(\\d+)\\s+(\\d+)\\s+[VDIWEF]\\s+"
            + "(ActivityTaskManager|ContextImpl)\\s*:\\s*(.*)$");
    private static final Pattern SYSTEM_HOME = Pattern.compile(
            "^START u\\d+ \\{act=android\\.intent\\.action\\.MAIN cat=\\[android\\.intent\\.category\\.HOME\\]"
            + " [^}]*\\bcmp=com\\.amazon\\.tv\\.launcher/(?:\\.ui\\.HomeActivity_vNext|com\\.amazon\\.tv\\.launcher\\.ui\\.HomeActivity_vNext)"
            + "(?=[ }])[^}]*\\} from uid 0(?:$|\\s.*)");
    private static final Pattern NETFLIX_START = Pattern.compile(
            "^START u\\d+ \\{act=android\\.intent\\.action\\.MAIN cat=\\[android\\.intent\\.category\\.LAUNCHER\\]"
            + " [^}]*\\bpkg=com\\.netflix\\.ninja [^}]*\\bcmp=com\\.netflix\\.ninja/\\.MainActivity \\(has extras\\)\\} from uid 1000(?:$|\\s.*)");
    private static final String PARTNER_BUTTON = "com.amazon.genericappadapter.applaunchstrategy.BasePartnerAppLauncher.recordPartnerButtonMetrics";
    final String kind;
    final long wallMs;

    private FireKeyEvent(String kind, long wallMs) {
        this.kind = kind;
        this.wallMs = wallMs;
    }

    static final class Monitor {
        private long partnerMs, netflixMs;
        private String partnerThread, netflixThread;
        private final long startMs;

        Monitor(long startMs) {
            this.startMs = startMs;
        }

        FireKeyEvent parse(String line) {
            if (!line.contains("START u") && !line.contains(PARTNER_BUTTON)) return null;
            Matcher entry = LINE.matcher(line);
            if (!entry.matches()) return null;
            String uid = entry.group(3);
            if (!uid.equals("1000") && !uid.equals("system")) return null;
            String thread = entry.group(4) + "/" + entry.group(5);
            String tag = entry.group(6);
            String message = entry.group(7);
            try {
                long stamp = Math.addExact(Math.multiplyExact(Long.parseLong(entry.group(1)), 1000L),
                        Long.parseLong(entry.group(2)));
                if (stamp < startMs) return null;
                if (tag.equals("ActivityTaskManager")) {
                    if (SYSTEM_HOME.matcher(message).matches()) return new FireKeyEvent("home", stamp);
                    if (!NETFLIX_START.matcher(message).matches()) return null;
                    netflixMs = stamp;
                    netflixThread = thread;
                } else if (message.contains(PARTNER_BUTTON)) {
                    partnerMs = stamp;
                    partnerThread = thread;
                } else return null;
                if (Math.abs(netflixMs - partnerMs) > 500 || !thread.equals(netflixThread) || !thread.equals(partnerThread)) return null;
                FireKeyEvent result = new FireKeyEvent("netflix", netflixMs);
                netflixMs = partnerMs = 0;
                return result;
            } catch (ArithmeticException | NumberFormatException invalidStamp) { return null; }
        }

    }
}
