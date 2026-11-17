#!/usr/bin/env python3
"""Install a pinned OpenNava release and apply the chosen TV home screen."""
import argparse
import re
import subprocess
import sys
import tempfile
import time
from pathlib import Path

from navatv import (APPS, Failure, active_user, apk, command, device_kind,
                    device_map, find_tool, install, metadata, shell, target, version_code)

TV_PACKAGE = APPS['tv'][0]


def home(adb, serial, user):
    result = shell(adb, serial, 'cmd', 'package', 'resolve-activity', '--brief', '--user', user,
                   '-a', 'android.intent.action.MAIN', '-c', 'android.intent.category.HOME')
    return result.splitlines()[-1] if result else ''


def set_home(adb, serial, user):
    before = home(adb, serial, user)
    if before.startswith(TV_PACKAGE + '/'):
        print('OpenNava is already the default Home app.')
        return
    if re.fullmatch(r'[\w.]+/[\w.$]+', before):
        print(f'Previous Home app: {before}\nTo restore it: adb -s "{serial}" shell cmd package set-home-activity --user {user} {before}', flush=True)
    shell(adb, serial, 'cmd', 'package', 'set-home-activity', '--user', user, TV_PACKAGE + '/.MainActivity')
    # Role assignment and intent-resolution updates can complete asynchronously on TV firmware.
    for _ in range(5):
        resolved = home(adb, serial, user)
        if resolved.startswith(TV_PACKAGE + '/'):
            break
        time.sleep(.3)
    else:
        raise Failure(f'OpenNava is installed and its profile is active. Android still resolves Home to {resolved or "another activity"}. '
                      'Try the Home app selector in Settings if available, or open OpenNava from Apps.')
    print('Android now resolves the Home action to OpenNava. Test the physical Home button as well.')


def profile(adb, serial, user, choice):
    result = shell(adb, serial, 'am', 'start', '-W', '--user', user, '-n', TV_PACKAGE + '/.ProfileActivity',
                   '--es', 'profile', choice)
    if 'Error' in result or 'Exception' in result:
        raise Failure('Could not open the requested profile: ' + result)
    deadline = time.monotonic() + 10
    while time.monotonic() < deadline:
        state = shell(adb, serial, 'content', 'call', '--user', user,
                      '--uri', 'content://' + TV_PACKAGE + '.companion', '--method', 'get_profile')
        if re.search(r'\bprofile=' + choice + r'\b', state):
            print(f'{serial}: {choice} profile confirmed by the TV.')
            return
        time.sleep(.5)
    raise Failure('TV did not confirm its saved profile.')


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--list', action='store_true', help='List connected devices and their types; make no changes.')
    parser.add_argument('--profile', choices=['full', 'senior'], help='Initial profile on the TV.')
    parser.add_argument('--tv', metavar='SERIAL', help='Exact device serial shown by --list.')
    parser.add_argument('--phone', metavar='SERIAL', help='Also install the Android companion on this phone.')
    parser.add_argument('--phone-only', action='store_true', help='Only install the Android companion on --phone.')
    parser.add_argument('--set-home', action='store_true', help='Also request and verify the Android Home role.')
    parser.add_argument('--dry-run', action='store_true', help='Inspect devices and show the plan without downloading or installing.')
    parser.add_argument('--assets-dir', type=Path, help='Use already downloaded official APKs from this directory.')
    args = parser.parse_args()
    adb = find_tool('adb')
    devices = device_map(adb)
    if args.list:
        if not devices:
            print('No devices connected. See the pairing instructions in README.md.')
        for serial, state in devices.items():
            if state == 'device':
                print(f'{serial}\t{device_kind(adb, serial)}\t{shell(adb, serial, "getprop", "ro.product.model")}')
            else:
                print(f'{serial}\t{state}')
        return
    if args.phone_only and (not args.phone or args.tv or args.profile or args.set_home):
        parser.error('--phone-only needs --phone SERIAL and cannot be combined with TV options.')
    if not args.phone_only and not args.profile:
        parser.error('Choose --profile full or --profile senior.')
    targets = {}
    if not args.phone_only:
        targets['tv'] = target(adb, devices, 'tv', args.tv)
    if args.phone:
        targets['phone'] = target(adb, devices, 'phone', args.phone)
    manifest = metadata()
    users = {k: active_user(adb, s) for k, s in targets.items()}
    needs_install = {}
    for kind, serial in targets.items():
        current = version_code(adb, serial, users[kind], APPS[kind][0])
        requested = manifest['apps'][kind]['versionCode']
        if current is not None and current > requested:
            raise Failure(f'{serial} already has a newer version. Check out a current release before running setup.')
        needs_install[kind] = current != requested
        print(f'{kind}: {serial} · {APPS[kind][0]} · installed {current if current is not None else "none"} → {requested}')
    if args.profile:
        print(f'TV profile: {args.profile}. Request default Home: {args.set_home}.')
    if args.dry_run:
        return
    with tempfile.TemporaryDirectory(prefix='opennava-setup-') as directory:
        files = {k: apk(manifest, k, directory, args.assets_dir) for k in targets if needs_install[k]}
        for kind, path in files.items():
            install(adb, targets[kind], users[kind], path, manifest['apps'][kind])
    if 'tv' in targets:
        profile(adb, targets['tv'], users['tv'], args.profile)
        if args.set_home:
            set_home(adb, targets['tv'], users['tv'])


if __name__ == '__main__':
    try:
        main()
    except (Failure, OSError, ValueError, subprocess.TimeoutExpired) as error:
        sys.exit(str(error))
