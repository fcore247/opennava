package site.navatv.updates;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageInstaller;

public final class InstallResult extends BroadcastReceiver {
    @Override public void onReceive(Context c, Intent result) {
        if (!"site.navatv.INSTALL_RESULT".equals(result.getAction())) return;
        int status = result.getIntExtra(PackageInstaller.EXTRA_STATUS, PackageInstaller.STATUS_FAILURE);
        if (result.getBooleanExtra("preview", false)) {
            PreviewActivity.installResult(c, result, status);
            return;
        }
        UpdateActivity foreground = UpdateActivity.foreground();
        if (status == PackageInstaller.STATUS_PENDING_USER_ACTION) {
            Intent consent = result.getParcelableExtra(Intent.EXTRA_INTENT);
            if (result.getBooleanExtra("interactive", false) && foreground != null && consent != null) {
                foreground.startActivity(consent);
                return;
            }
            Updates.prefs(c).edit().putLong("needsApproval", result.getLongExtra("releaseCode", 0)).apply();
            int session = result.getIntExtra(PackageInstaller.EXTRA_SESSION_ID, -1);
            if (session >= 0) {
                try { c.getPackageManager().getPackageInstaller().abandonSession(session); }
                catch (RuntimeException ignored) { /* Android may already have closed it. */ }
            }
        } else if (status == PackageInstaller.STATUS_SUCCESS) {
            Updates.prefs(c).edit().remove("needsApproval").apply();
        }
        if (foreground != null) foreground.message(status == PackageInstaller.STATUS_SUCCESS ?
                "Update installed." : "Open software updates to retry the installation.");
    }
}
