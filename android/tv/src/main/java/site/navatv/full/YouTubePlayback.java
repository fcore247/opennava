package site.navatv.full;

import android.os.Handler;
import android.os.Looper;

import com.liskovsoft.mediaserviceinterfaces.data.MediaItemFormatInfo;
import com.liskovsoft.youtubeapi.service.YouTubeServiceManager;

import java.io.IOException;

import io.reactivex.Observable;
import io.reactivex.disposables.Disposable;
import io.reactivex.observables.ConnectableObservable;
import io.reactivex.schedulers.Schedulers;

/** Starts the selected format request while Android opens the player window. */
final class YouTubePlayback {
    private static final Handler main = new Handler(Looper.getMainLooper());
    private static Pending pending;

    private YouTubePlayback() { }

    static synchronized void prepare(String videoId) {
        if (!NavaYouTube.validVideoId(videoId)) return;
        if (pending != null) {
            if (pending.videoId.equals(videoId)) return;
            pending.run();
        }
        pending = new Pending(videoId);
        // Release the request if the player window never starts.
        main.postDelayed(pending, 10_000);
    }

    static Observable<MediaItemFormatInfo> format(String videoId) {
        return Observable.defer(() -> claimOrResolve(videoId));
    }

    private static synchronized Observable<MediaItemFormatInfo> claimOrResolve(String videoId) {
        if (pending == null || !pending.videoId.equals(videoId)) return resolve(videoId);
        Pending selected = pending;
        pending = null;
        main.removeCallbacks(selected);
        // A finished result may be replayed on the caller's thread. Manifest construction belongs on I/O.
        return selected.result.observeOn(Schedulers.io()).doFinally(selected.connection::dispose);
    }

    private static Observable<MediaItemFormatInfo> resolve(String videoId) {
        return Observable.<MediaItemFormatInfo>create(emitter -> {
            if (emitter.isDisposed()) return;
            try {
                MediaItemFormatInfo info = YouTubeServiceManager.instance().getMediaItemService().getFormatInfo(videoId);
                if (info == null) {
                    emitter.tryOnError(new IOException("No YouTube format returned"));
                    return;
                }
                emitter.onNext(info);
                emitter.onComplete();
            } catch (RuntimeException failure) {
                emitter.tryOnError(failure);
            }
        }).subscribeOn(Schedulers.io());
    }

    private static final class Pending implements Runnable {
        final String videoId;
        final ConnectableObservable<MediaItemFormatInfo> result;
        final Disposable connection;

        Pending(String videoId) {
            this.videoId = videoId;
            result = resolve(videoId).replay(1);
            connection = result.connect();
        }

        @Override public void run() {
            synchronized (YouTubePlayback.class) {
                if (pending != this) return;
                pending = null;
                main.removeCallbacks(this);
                connection.dispose();
            }
        }
    }
}
