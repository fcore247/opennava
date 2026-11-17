package site.navatv.launcher;

import com.liskovsoft.sharedutils.prefs.GlobalPreferences;

public final class FernsehenApplication extends StartupSignals implements site.navatv.full.NavaHomeService.ProgrammeShortcut {
    private FireButtonConnection buttons;
    @Override public void buttonsConnected(android.accessibilityservice.AccessibilityService service) {
        buttonsDisconnected();
        buttons = new FireButtonConnection(service);
    }
    @Override public void buttonsDisconnected() {
        if (buttons != null) buttons.close();
        buttons = null;
    }

    @Override public boolean onHomeKey(android.view.KeyEvent event) {
        return HomeProgrammeShortcut.key(this, event);
    }
    @Override public boolean guideOpening() { return HomeProgrammeShortcut.suppressRedirect(); }

    @Override public void onCreate() {
        super.onCreate();
        com.google.android.exoplayer2.util.Log.setLogLevel(com.google.android.exoplayer2.util.Log.LOG_LEVEL_OFF);
        GlobalPreferences.instance(getApplicationContext());
    }
}
