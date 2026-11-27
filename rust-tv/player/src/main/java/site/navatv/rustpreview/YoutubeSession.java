package site.navatv.rustpreview;

import android.app.Application;

import com.liskovsoft.googlecommon.common.helpers.RetrofitOkHttpHelper;
import com.liskovsoft.mediaserviceinterfaces.oauth.Account;
import com.liskovsoft.sharedutils.prefs.GlobalPreferences;
import com.liskovsoft.youtubeapi.service.YouTubeServiceManager;
import com.liskovsoft.youtubeapi.service.YouTubeSignInService;
import com.liskovsoft.youtubeapi.service.internal.YouTubeAccountManager;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicLong;

import site.navatv.full.YoutubeBridge;

/** One SDK account for all YouTube entry points in the native preview process. */
public final class YoutubeSession extends Application {
    private static final AtomicLong revision = new AtomicLong();

    private static final java.util.concurrent.CountDownLatch ready = new java.util.concurrent.CountDownLatch(1);

    /** A cold start can deliver the companion's call while the SDK is still restoring the account. */
    static boolean awaitReady() {
        try { return ready.await(12, java.util.concurrent.TimeUnit.SECONDS); }
        catch (InterruptedException stopped) {
            Thread.currentThread().interrupt();
            return false;
        }
    }

    /** Changes whenever somebody signs in or out, from the TV or from the phone. */
    public static long accountRevision() {
        return revision.get();
    }

    @Override public void onCreate() {
        super.onCreate();
        site.navatv.launcher.ProgrammeReminders.install(this);
        GlobalPreferences.instance(this);
        // Complete the SDK's local restore before a cold launch can reach search or playback.
        YouTubeAccountManager.instance(service()).init();
        ready.countDown();
    }

    static YouTubeSignInService service() {
        return YouTubeSignInService.instance();
    }

    /** Binds cached result pages to the account grant which requested them. */
    public static final class Viewer {
        private final long revisionAtStart = revision.get();
        private final Account owner = service().getSelectedAccount();

        public boolean signedIn() { return owner != null; }

        public boolean isCurrent() {
            return revisionAtStart == revision.get() && Objects.equals(owner, service().getSelectedAccount());
        }
    }

    /** Background only. If a stored grant expired, never label guest results as personalized. */
    public static Viewer forRequest() {
        Viewer viewer = new Viewer();
        if (viewer.signedIn()) {
            service().checkAuth();
            String authorization = RetrofitOkHttpHelper.getAuthHeaders().get("Authorization");
            if (authorization == null || !authorization.regionMatches(true, 0, "Bearer ", 0, 7)
                    || authorization.length() <= 7 || "null".equals(authorization.substring(7))) {
                throw new IllegalStateException("Das YouTube-Konto muss erneut verbunden werden.");
            }
        }
        if (!viewer.isCurrent()) throw new IllegalStateException("Das YouTube-Konto wurde gewechselt.");
        return viewer;
    }

    static void changed() {
        revision.incrementAndGet();
        YoutubeBridge.clearPages();
        // Format choices include the account's playback rights. Discard any guest or previous format.
        YouTubeServiceManager.instance().invalidateCache();
    }

    static void signOut() {
        YouTubeSignInService sdk = service();
        List<Account> accounts = sdk.getAccounts();
        if (accounts != null) {
            // One Google consent may contain several brand channels on this TV.
            for (Account saved : new ArrayList<>(accounts)) sdk.removeAccount(saved);
        }
        changed();
        // invalidateCache() invalidates the SDK's token cache but leaves its applied headers
        // until checkAuth(). With no saved account this clears the headers without a network login.
        sdk.checkAuth();
    }
}
