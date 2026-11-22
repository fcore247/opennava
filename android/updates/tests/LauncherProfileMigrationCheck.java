package site.navatv.updates;

public final class LauncherProfileMigrationCheck {
    private static void expect(String launcher, String legacy, boolean preferredRust, boolean nativeWasHome, String expected) {
        String result = LauncherProfile.resolve(launcher, legacy, preferredRust, nativeWasHome);
        if (!expected.equals(result)) throw new AssertionError("Expected " + expected + ", got " + result);
    }

    public static void main(String[] args) {
        expect("", "PERSONAL", false, true, "rust");
        expect("", "VISITOR", false, true, "rust");
        expect("", "PERSONAL", false, false, "senior");
        expect("", "VISITOR", true, false, "rust");
        expect("", "VISITOR", false, false, "classic");
        expect("", "", false, false, "");
        for (String explicit : new String[]{"rust", "classic", "senior"}) {
            expect(explicit, "PERSONAL", true, true, explicit);
        }
        System.out.println("9 launcher migration checks passed");
    }
}
