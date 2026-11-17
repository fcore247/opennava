#!/usr/bin/env python3
"""Update existing OpenNava apps from their official, signed release."""
import argparse
import re
import subprocess
import sys
import tempfile

import timer
from navatv import (APPS, Failure, active_user, apk, command, device_map, find_tool,
                    install, metadata, shell, target, verify_signer_and_package, version_code)


def identity(adb, serial, user):
    value = shell(adb, serial, 'settings', '--user', user, 'get', 'secure', 'android_id').lower()
    if not re.fullmatch(r'[0-9a-f]{1,16}', value):
        raise Failure(f'{serial}: Android device identity unavailable.')
    return value


def using_app(adb, serial, package):
    power = shell(adb, serial, 'dumpsys', 'power')
    if re.search(r'\bmWakefulness=(Asleep|Dozing)\b', power):
        return False
    activities = shell(adb, serial, 'dumpsys', 'activity', 'activities')
    return any(package + '/' in line and ('topResumedActivity' in line or 'mResumedActivity' in line)
               for line in activities.splitlines())


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--tv', metavar='SERIAL')
    parser.add_argument('--phone', metavar='SERIAL')
    mode = parser.add_mutually_exclusive_group()
    mode.add_argument('--check', action='store_true', help='Report versions without installing.')
    mode.add_argument('--install-timer', action='store_true', help='Enable hourly background checks on this computer.')
    mode.add_argument('--remove-timer', action='store_true', help='Remove the background schedule from this computer.')
    mode.add_argument('--scheduled', action='store_true', help='Skip offline or actively used devices.')
    parser.add_argument('--tv-id', help=argparse.SUPPRESS)
    parser.add_argument('--phone-id', help=argparse.SUPPRESS)
    args = parser.parse_args()
    if args.remove_timer:
        return timer.change(False, [])
    devices = {k: getattr(args, k) for k in APPS if getattr(args, k)}
    if not devices:
        parser.error('Specify --tv SERIAL and/or --phone SERIAL.')
    adb = find_tool('adb')
    if args.install_timer:
        find_tool('aapt')
        find_tool('apksigner')
    connected = device_map(adb)
    ready, registration = {}, []
    for kind, serial in devices.items():
        pinned = getattr(args, kind + '_id')
        if (args.scheduled and connected.get(serial) not in ('device', 'unauthorized') and
                re.fullmatch(r'(?:[\w.-]+|\[[0-9A-Fa-f:]+\]):\d+', serial)):
            try:
                command(adb, 'connect', serial, timeout=12)
            except (Failure, OSError, subprocess.TimeoutExpired):
                pass
            connected = device_map(adb)
        if args.scheduled and connected.get(serial) != 'device':
            print(f'{kind}: offline; will retry later.')
            continue
        target(adb, connected, kind, serial)
        user = active_user(adb, serial)
        if args.install_timer or pinned:
            key = identity(adb, serial, user)
            if pinned and pinned != key:
                print(f'{serial}: address or Android user changed; skipping.')
                continue
            registration.extend(['--' + kind, serial, '--' + kind + '-id', key])
        code = version_code(adb, serial, user, APPS[kind][0])
        if code is None:
            if args.install_timer:
                raise Failure(f'{serial}: install {APPS[kind][0]} with tools/setup.py before enrolling it.')
            print(f'{serial}: {APPS[kind][0]} is not installed; skipping.')
            continue
        ready[kind] = (serial, user, code)
    if args.install_timer:
        return timer.change(True, registration)
    if not ready:
        return
    manifest = metadata(latest=True)
    with tempfile.TemporaryDirectory(prefix='opennava-update-') as directory:
        for kind, (serial, user, code) in ready.items():
            item = manifest['apps'][kind]
            print(f'{serial}: installed {code}, latest {item["versionCode"]}.')
            if args.check or code >= item['versionCode']:
                continue
            if args.scheduled and using_app(adb, serial, APPS[kind][0]):
                print('OpenNava is in use; will retry later.')
                continue
            path = apk(manifest, kind, directory)
            verify_signer_and_package(path, item)
            # Check again after the download; another installer may have finished in the meantime.
            if active_user(adb, serial) != user:
                print(f'{serial}: active Android user changed during download; skipping.')
                continue
            pinned = getattr(args, kind + '_id')
            if pinned and identity(adb, serial, user) != pinned:
                print(f'{serial}: enrolled device changed during download; skipping.')
                continue
            if args.scheduled and using_app(adb, serial, item['package']):
                print('OpenNava is now in use; will retry later.')
                continue
            current = version_code(adb, serial, user, item['package'])
            if current is None or current >= item['versionCode']:
                continue
            install(adb, serial, user, path, item)


if __name__ == '__main__':
    try:
        main()
    except (Failure, OSError, ValueError, subprocess.TimeoutExpired) as error:
        sys.exit(str(error))
