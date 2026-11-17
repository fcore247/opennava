package site.navatv.launcher;

import android.app.Activity;
import android.content.ActivityNotFoundException;
import android.content.Context;
import android.content.Intent;

/** Keeps the playing station on the back stack while browsing its catalogue. */
public final class LiveLibraryActivity extends MainActivity {
    private static Intent destination(Context context, LiveGuide.Channel channel) {
        switch (channel.provider) {
            case "ard": case "zdf": case "arte":
                return new Intent(context, LiveLibraryActivity.class).putExtra("provider", channel.provider);
            case "rtl":
                return context.getPackageManager().getLeanbackLaunchIntentForPackage("de.rtli.tvnow");
            default:
                return null;
        }
    }

    static boolean available(Context context, LiveGuide.Channel channel) {
        return destination(context, channel) != null;
    }

    static boolean open(Activity source, LiveGuide.Channel channel) {
        Intent intent = destination(source, channel);
        if (intent == null) return false;
        try {
            source.startActivity(intent);
            return true;
        } catch (ActivityNotFoundException | SecurityException unavailable) {
            return false;
        }
    }

    @Override String initialLibrary() { return getIntent().getStringExtra("provider"); }
}
