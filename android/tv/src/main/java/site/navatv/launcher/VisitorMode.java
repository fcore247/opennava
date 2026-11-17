package site.navatv.launcher;

import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.ActivityInfo;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.util.Log;

/** The personal launcher owns the selected Home destination for both apps. */
final class VisitorMode {
    private static final String VISITOR_PACKAGE = "site.navatv.full";
    private static final String VISIT_HOME = VISITOR_PACKAGE + ".VISIT_HOME";
    private static final String PERSONAL_PACKAGE = "site.navatv.launcher";
    private static final String RETURN_ACTION = PERSONAL_PACKAGE + ".RETURN_TO_SENIOR";
    private static final ComponentName RETURN_ALIAS = new ComponentName(PERSONAL_PACKAGE, PERSONAL_PACKAGE + ".ReturnToSenior");
    private static final String MODE = "mode", PERSONAL = "PERSONAL", VISITOR = "VISITOR";

    private VisitorMode() { }

    static boolean isReturn(Intent intent) {
        return intent != null && RETURN_ACTION.equals(intent.getAction()) && RETURN_ALIAS.equals(intent.getComponent());
    }

    private static SharedPreferences preferences(Context context) {
        return context.getSharedPreferences("home-destination", Context.MODE_PRIVATE);
    }

    private static boolean setMode(Context context, String destination) {
        SharedPreferences preferences = preferences(context);
        if (destination.equals(preferences.getString(MODE, initialMode(context)))) return true;
        return preferences.edit().putString(MODE, destination).commit();
    }

    static boolean selectFull(Context context) { return setMode(context, VISITOR); }

    static boolean selectPersonal(Context context) { return setMode(context, PERSONAL); }
    static boolean isVisitor(Context context) { return VISITOR.equals(preferences(context).getString(MODE, initialMode(context))); }

    static boolean integrated(Context context) {
        try {
            context.getPackageManager().getActivityInfo(new ComponentName(context.getPackageName(), VISITOR_PACKAGE + ".MainActivity"), 0);
            return true;
        } catch (PackageManager.NameNotFoundException absent) { return false; }
    }

    private static String initialMode(Context context) {
        return VISITOR;
    }

    private static Intent visitorIntent(Context context) {
        return new Intent(VISIT_HOME).setClassName(integrated(context) ? context.getPackageName() : VISITOR_PACKAGE, VISITOR_PACKAGE + ".MainActivity")
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP | Intent.FLAG_ACTIVITY_SINGLE_TOP);
    }

    static boolean available(Context context) {
        if (integrated(context)) return true;
        PackageManager packages = context.getPackageManager();
        try {
            if (packages.checkSignatures(context.getPackageName(), VISITOR_PACKAGE) != PackageManager.SIGNATURE_MATCH) return false;
            ResolveInfo resolved = packages.resolveActivity(visitorIntent(context), 0);
            ActivityInfo activity = resolved == null ? null : resolved.activityInfo;
            return activity != null && activity.exported && activity.enabled && activity.applicationInfo.enabled
                    && (activity.permission == null || context.checkSelfPermission(activity.permission) == PackageManager.PERMISSION_GRANTED);
        } catch (RuntimeException unavailable) { return false; }
    }

    /** Called on the main thread when a person explicitly chooses the visitor home. */
    static boolean enter(Context context) {
        if (available(context) && setMode(context, VISITOR) && launch(context)) return true;
        selectPersonal(context);
        return false;
    }

    /** Called by both actual Android HOME intents and the Google TV Home window redirect. */
    static boolean launchIfSelected(Context context) {
        if (!isVisitor(context)) return false;
        if (available(context) && launch(context)) return true;
        selectPersonal(context);
        return false;
    }

    private static boolean launch(Context context) {
        try {
            context.startActivity(visitorIntent(context));
            return true;
        } catch (RuntimeException unavailable) {
            Log.w("Libraries", "Could not open the full launcher", unavailable);
            return false;
        }
    }
}
