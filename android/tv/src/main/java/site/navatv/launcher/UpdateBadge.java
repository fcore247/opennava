package site.navatv.launcher;

import android.content.SharedPreferences;
import android.webkit.WebView;

import site.navatv.updates.Updates;

/** Reflects the native update check in the bundled classic launcher views. */
public final class UpdateBadge implements SharedPreferences.OnSharedPreferenceChangeListener {
    private final WebView view;

    public UpdateBadge(WebView view) {
        this.view = view;
    }

    public void attach() {
        Updates.prefs(view.getContext()).registerOnSharedPreferenceChangeListener(this);
        refresh();
    }

    public void detach() {
        Updates.prefs(view.getContext()).unregisterOnSharedPreferenceChangeListener(this);
    }

    @Override public void onSharedPreferenceChanged(SharedPreferences prefs, String key) {
        refresh();
    }

    public void refresh() {
        boolean available = Updates.available(view.getContext());
        view.evaluateJavascript("(function(){var hint=document.getElementById('update-description');"
                + "if(!hint)return;var pending=" + available + ";"
                + "document.documentElement.classList.toggle('update-available',pending);"
                + "hint.textContent=pending?(navigator.language.indexOf('de')===0?"
                + "'Softwareupdate verfügbar':'Software update available'):'';})();", null);
    }
}
