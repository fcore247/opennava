package site.navatv.full;

import android.app.Application;
import com.liskovsoft.sharedutils.prefs.GlobalPreferences;

/** Shared SmartTube state belongs to OpenNava's private application sandbox. */
public final class NavaApplication extends Application {
    @Override public void onCreate() {
        super.onCreate();
        com.google.android.exoplayer2.util.Log.setLogLevel(com.google.android.exoplayer2.util.Log.LOG_LEVEL_OFF);
        GlobalPreferences.instance(getApplicationContext());
    }
}
