package site.navatv.launcher;

import android.content.Context;
import android.content.Intent;
import android.media.MediaMetadata;
import android.media.session.MediaSession;
import android.media.session.PlaybackState;
import android.os.Handler;
import android.os.SystemClock;
import android.view.KeyEvent;

/** Routes physical and Bluetooth transport keys while the live player is in the foreground. */
final class LiveTvMediaSession {
    interface Transport {
        boolean key(KeyEvent event);
        void setPlaying(boolean playing);
    }
    private static final long ACTIONS = PlaybackState.ACTION_PLAY | PlaybackState.ACTION_PAUSE
            | PlaybackState.ACTION_PLAY_PAUSE;
    private final MediaSession session;
    private String lastStation, lastTitle;

    LiveTvMediaSession(Context context, Handler main, Transport transport) {
        session = new MediaSession(context, "Senior live TV");
        session.setFlags(MediaSession.FLAG_HANDLES_MEDIA_BUTTONS | MediaSession.FLAG_HANDLES_TRANSPORT_CONTROLS);
        session.setMediaButtonReceiver(null);
        session.setCallback(new MediaSession.Callback() {
            @Override public boolean onMediaButtonEvent(Intent intent) {
                KeyEvent event = intent.getParcelableExtra(Intent.EXTRA_KEY_EVENT);
                if (event != null && transport.key(event)) return true;
                return super.onMediaButtonEvent(intent);
            }
            @Override public void onPlay() { transport.setPlaying(true); }
            @Override public void onPause() { transport.setPlaying(false); }
        }, main);
        state(PlaybackState.STATE_BUFFERING, 0);
        session.setActive(true);
    }

    void metadata(String station, String title) {
        if (station.equals(lastStation) && title.equals(lastTitle)) return;
        lastStation = station;
        lastTitle = title;
        session.setMetadata(new MediaMetadata.Builder()
                .putString(MediaMetadata.METADATA_KEY_TITLE, title)
                .putString(MediaMetadata.METADATA_KEY_ARTIST, station)
                .build());
    }

    void state(int state, long position) {
        session.setPlaybackState(new PlaybackState.Builder().setActions(ACTIONS)
                .setState(state, Math.max(0, position), state == PlaybackState.STATE_PLAYING ? 1f : 0f,
                        SystemClock.elapsedRealtime()).build());
    }

    void close() {
        session.setActive(false);
        session.setCallback(null);
        session.release();
    }
}
