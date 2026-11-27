package site.navatv.rustpreview;

import android.app.Activity;
import android.content.Intent;
import android.os.Bundle;

/** Plays the viewer's own video, or opens NavaTube, for the paired phone. Android enforces the DUMP permission. */
public final class CompanionPlayActivity extends Activity {
    @Override public void onCreate(Bundle state) {
        super.onCreate(state);
        try {
            if ("navatube".equals(getIntent().getStringExtra("open_page"))) {
                HomeEntryActivity.openNavaTube(this);
                return;
            }
            String video = getIntent().getStringExtra("video_id");
            if (video != null && video.matches("[A-Za-z0-9_-]{11}")) {
                startActivity(new Intent(this, site.navatv.full.NavaPlayerActivity.class).putExtra("video_id", video)
                        .putExtra("title", getIntent().getStringExtra("title"))
                        .putExtra("author", getIntent().getStringExtra("author")));
                return;
            }
            String url = getIntent().getStringExtra("stream_url");
            if (url != null && url.length() <= 1500) {
                startActivity(new Intent(this, StreamActivity.class)
                        .putExtra("stream_url", url).putExtra("title", getIntent().getStringExtra("title")));
            }
        } finally { finish(); }
    }
}
