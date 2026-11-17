#!/system/bin/sh
# Run on the TV after the signed universal APK was successfully installed.
# Carry over only an active legacy OpenNava Home-button service and its existing permission.
set -eu
[ "$(getprop ro.product.manufacturer)" = Amazon ] || exit 0
oldpkg=site.navatv.full
newpkg=site.navatv.launcher
old="$oldpkg/$oldpkg.NavaHomeService"
new="$newpkg/$oldpkg.NavaHomeService"
before=$(settings get secure enabled_accessibility_services)
case ":$before:" in
    *":$old:"*|*":$oldpkg/.NavaHomeService:"*) ;;
    *) exit 0 ;;
esac
pm path "$newpkg" | grep -q '^package:' || exit 1
if dumpsys package "$oldpkg" | grep -q 'android.permission.WRITE_SECURE_SETTINGS: granted=true'; then
    pm grant "$newpkg" android.permission.WRITE_SECURE_SETTINGS
fi
after=""
IFS=:
set -f
for service in $before; do
    case "$service" in "$old"|"$oldpkg/.NavaHomeService"|"$new"|"") continue ;; esac
    after="${after}${service}:"
done
[ "$(settings get secure enabled_accessibility_services)" = "$before" ] || exit 1
settings put secure enabled_accessibility_services "${after}$new"
