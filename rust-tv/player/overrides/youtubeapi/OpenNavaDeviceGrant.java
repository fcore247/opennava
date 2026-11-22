package com.liskovsoft.youtubeapi.service.internal;

import android.os.SystemClock;

import com.liskovsoft.googlecommon.common.helpers.RetrofitHelper;
import com.liskovsoft.googlecommon.common.models.auth.AccessToken;
import com.liskovsoft.googlecommon.common.models.auth.UserCode;
import com.liskovsoft.youtubeapi.app.AppService;
import com.liskovsoft.youtubeapi.auth.V2.AuthApi;
import com.liskovsoft.youtubeapi.auth.V2.AuthApiHelper;

import org.json.JSONObject;

import io.reactivex.ObservableEmitter;
import okhttp3.ResponseBody;
import retrofit2.Call;
import retrofit2.Response;

/** Poll one Google TV challenge using its returned interval and expiry. */
final class OpenNavaDeviceGrant {
    static AccessToken waitFor(UserCode challenge, ObservableEmitter<String> emitter) throws InterruptedException {
        AuthApi api = RetrofitHelper.create(AuthApi.class);
        AppService client = AppService.instance();
        int delay = Math.max(5, challenge.getInterval());
        long expiresAt = SystemClock.elapsedRealtime() + Math.min(3600, Math.max(1, challenge.getExpiresIn())) * 1000L;
        String body = AuthApiHelper.getRefreshTokenQuery(challenge.getDeviceCode(), client.getClientId(), client.getClientSecret());
        while (!emitter.isDisposed() && SystemClock.elapsedRealtime() + delay * 1000L < expiresAt) {
            Thread.sleep(delay * 1000L);
            if (emitter.isDisposed()) return null;
            Call<AccessToken> request = api.getAccessToken(body);
            emitter.setCancellable(request::cancel);
            try {
                Response<AccessToken> response = request.execute();
                AccessToken token = response.body();
                if (token != null && token.getRefreshToken() != null && !token.getRefreshToken().isEmpty()) return token;
                String error = token == null ? null : token.getError();
                if (!response.isSuccessful()) {
                    try (ResponseBody problem = response.errorBody()) {
                        if (problem != null) error = new JSONObject(problem.string()).optString("error");
                    }
                }
                if ("authorization_pending".equals(error)) continue;
                if ("slow_down".equals(error) || response.code() == 429) {
                    delay = Math.min(120, delay + 5);
                    continue;
                }
                if (response.code() >= 500) {
                    delay = Math.min(120, delay * 2);
                    continue;
                }
                throw new IllegalStateException("Google device grant ended.");
            } catch (java.io.IOException transientFailure) {
                if (emitter.isDisposed()) return null;
                delay = Math.min(120, delay * 2);
            } catch (org.json.JSONException unreadable) {
                throw new IllegalStateException("Google device response was unreadable.");
            }
        }
        if (emitter.isDisposed()) return null;
        throw new IllegalStateException("Google device grant expired.");
    }
}
