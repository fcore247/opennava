package site.navatv.launcher;

import android.app.Activity;
import android.content.Intent;
import android.os.Bundle;

/** Entry point for the authenticated ADB shell; Android enforces its DUMP permission. */
public final class CompanionActivity extends Activity {
    @Override public void onCreate(Bundle state) {
        super.onCreate(state);
        Intent request = getIntent();
        try {
            String provider = request.getStringExtra("provider");
            String operation = request.getStringExtra("operation");
            if (!"site.navatv.launcher.COMPANION".equals(request.getAction())) return;
            String value = request.getStringExtra("value");
            if ("library_play".equals(operation)) {
                Intent next = CompanionLibrary.play(this, value, request.getIntExtra("episode", 0));
                if (next != null) startActivity(next);
                else android.widget.Toast.makeText(this, "This item cannot be played right now.", android.widget.Toast.LENGTH_SHORT).show();
                return;
            }
            if ("tune".equals(operation)) {
                Intent live = LiveTvActivity.companionTuneIntent(this, value);
                if (live != null) startActivity(live);
                return;
            }
            if (!("ard".equals(provider) || "zdf".equals(provider) || "arte".equals(provider))) return;
            if ("play".equals(operation)) {
                startActivity(new Intent(this, PlayerActivity.class).putExtra("provider", provider)
                        .putExtra("programme", value).putExtra("title", request.getStringExtra("title")));
            } else if ("live".equals(operation)) {
                Intent live = LiveTvActivity.companionTuneIntent(this, "ard".equals(provider) ? "daserste" : provider);
                if (live != null) startActivity(live);
            }
        } finally { finish(); }
    }
}
