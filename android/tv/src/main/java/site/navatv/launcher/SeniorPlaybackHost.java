package site.navatv.launcher;

import android.app.Activity;

/** Optional senior-profile startup and idle behavior for the shared native players. */
interface SeniorPlaybackHost {
    void setPlayerOpen(boolean open);
    void showAmbient(Activity owner);

    static void playback(Activity owner, boolean open) {
        if (owner.getApplication() instanceof SeniorPlaybackHost)
            ((SeniorPlaybackHost) owner.getApplication()).setPlayerOpen(open);
    }
}
