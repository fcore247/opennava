package site.navatv.keys;

/** Replays representative Fire OS remote logs, including the absence of a valid action. */
public final class FireKeyEventCheck {
    private static final long START = 1794849963000L;
    private static final String DOWN = "1794849963.032 10189 1830 1900 I com_amazon_devicecont..: Received Key name: ALEXA, code: 322, action: 0";
    private static final String UP = "1794849963.177 10189 1830 1900 I com_amazon_devicecont..: Received Key name: ALEXA, code: 322, action: 1";
    private static final String HOME = "1794849963.000 1000 671 724 I ActivityTaskManager: START u0 {act=android.intent.action.MAIN cat=[android.intent.category.HOME] flg=0x10000100 cmp=com.amazon.tv.launcher/.ui.HomeActivity_vNext (has extras)} from uid 0";
    private static final String NETFLIX = "1794849963.200 1000 671 918 I ActivityTaskManager: START u0 {act=android.intent.action.MAIN cat=[android.intent.category.LAUNCHER] flg=0x10000000 pkg=com.netflix.ninja cmp=com.netflix.ninja/.MainActivity (has extras)} from uid 1000";
    private static final String BUTTON = "1794849963.235 1000 671 918 W ContextImpl: Calling a method in the system process without a qualified user: com.amazon.genericappadapter.applaunchstrategy.BasePartnerAppLauncher.recordPartnerButtonMetrics:85";
    private static int checks;

    private static void expect(FireKeyEvent.Monitor monitor, String input, String kind) {
        FireKeyEvent actual = monitor.parse(input);
        if (kind == null ? actual != null : actual == null || !kind.equals(actual.kind)) {
            throw new AssertionError(input + " (expected: " + kind + ")");
        }
        ++checks;
    }

    public static void main(String[] args) {
        FireKeyEvent.Monitor monitor = new FireKeyEvent.Monitor(START);
        expect(monitor, DOWN, null);
        expect(monitor, UP, null);
        expect(monitor, HOME, "home");
        expect(monitor, HOME.replace("1000 671", "system 671"), "home");
        expect(monitor, HOME.replace("963.000", "962.999"), null);
        expect(monitor, HOME.replace("1000 671", "10222 671"), null);
        expect(monitor, HOME.replace("uid 0", "uid 1000"), null);
        expect(monitor, NETFLIX, null);
        expect(monitor, BUTTON, "netflix");
        expect(monitor, BUTTON, null);
        expect(monitor, NETFLIX.replace("uid 1000", "uid 10199"), null);
        expect(monitor, NETFLIX.replace("671 918", "671 900"), null);
        expect(monitor, BUTTON.replace(".235", ".900"), null);
        monitor = new FireKeyEvent.Monitor(START);
        expect(monitor, BUTTON, null);
        expect(monitor, NETFLIX, "netflix");
        System.out.println(checks + " Fire TV parser checks passed");
    }
}
