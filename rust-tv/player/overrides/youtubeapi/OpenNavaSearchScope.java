package com.liskovsoft.googlecommon.common.helpers;

import com.liskovsoft.youtubeapi.service.YouTubeSignInService;

import retrofit2.Call;

/** Preserve signed-in search semantics if the provider rejects the initial request. */
final class OpenNavaSearchScope {
    static void requireGuestForAnonymousSearch(Call<?> request) {
        if ("/youtubei/v1/search".equals(request.request().url().encodedPath())
                && YouTubeSignInService.instance().isSigned()) {
            throw new IllegalStateException("Die angemeldete YouTube-Suche ist gerade nicht verfügbar.");
        }
    }
}
