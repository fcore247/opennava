package site.navatv.launcher;

import android.app.Activity;
import android.content.Intent;
import android.os.Bundle;

/** Applies the explicit choice supplied by the installer on an authorized ADB connection. */
public final class ProfileActivity extends Activity {
    @Override protected void onCreate(Bundle state) {
        super.onCreate(state);
        String profile = getIntent().getStringExtra("profile");
        boolean saved = "full".equals(profile) ? VisitorMode.selectFull(this)
                : "senior".equals(profile) && VisitorMode.selectPersonal(this);
        if (saved) {
            Intent home = new Intent(Intent.ACTION_MAIN).setClass(this, MainActivity.class);
            home.addCategory(Intent.CATEGORY_HOME);
            home.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TASK);
            startActivity(home);
        }
        finish();
    }
}
