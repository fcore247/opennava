"""Shared release and ADB operations. Uses only the Python standard library."""
from __future__ import annotations

import hashlib
import json
import os
from pathlib import Path
import re
import shutil
import subprocess
import ssl
import sys
import urllib.error
import urllib.request

ROOT = Path(__file__).resolve().parents[1]
RELEASES = 'https://github.com/fcore247/opennava/releases'
APPS = {
    'tv': ('site.navatv.launcher', 'OpenNava.apk', 26),
    'phone': ('site.navatv.companion', 'OpenNava-Companion.apk', 30),
}

# The preview is installed only after the viewer opts in on the TV.
PREVIEW = ('site.navatv.rustpreview', 'OpenNava-Rust-TV.apk', 26)


class Failure(Exception):
    pass


def command(*args, timeout=40):
    p = subprocess.run(list(map(str, args)), capture_output=True, text=True, timeout=timeout)
    if p.returncode:
        raise Failure((p.stderr.strip() or p.stdout.strip() or f'{args[0]} exited ({p.returncode})'))
    return p.stdout.strip()


def sdk_dirs():
    return [Path(p).expanduser() for p in (os.getenv('ANDROID_HOME'), os.getenv('ANDROID_SDK_ROOT'),
            '~/Library/Android/sdk', '~/Android/Sdk', os.path.join(os.getenv('LOCALAPPDATA', '~'), 'Android', 'Sdk')) if p]


def find_tool(name):
    found = shutil.which(name)
    if found:
        return found
    for root in sdk_dirs():
        directories = [root / 'platform-tools'] if name == 'adb' else sorted(
            (root / 'build-tools').glob('*'), key=lambda p: tuple(int(n) for n in re.findall(r'\d+', p.name)), reverse=True)
        for d in directories:
            found = shutil.which(name, path=str(d))
            if found:
                return found
    raise Failure(f'{name} not found. Install Android SDK tools and set ANDROID_HOME or PATH (see README).')


def device_map(adb):
    devices = {}
    for line in command(adb, 'devices', '-l').splitlines():
        fields = line.split()
        if len(fields) >= 2 and fields[0] != 'List' and not line.startswith('*'):
            devices[fields[0]] = fields[1]
    return devices


def shell(adb, serial, *args):
    return command(adb, '-s', serial, 'shell', *args)


def device_kind(adb, serial):
    features = set(shell(adb, serial, 'pm', 'list', 'features').splitlines())
    characteristics = shell(adb, serial, 'getprop', 'ro.build.characteristics').split(',')
    if ('television' in characteristics or features.intersection(
            {'feature:android.hardware.type.television', 'feature:android.software.leanback', 'feature:amazon.hardware.fire_tv'})):
        return 'tv'
    if ('feature:android.hardware.touchscreen' in features and not features.intersection(
            {'feature:android.hardware.type.watch', 'feature:android.hardware.type.automotive'})):
        return 'phone'
    return 'unknown'


def target(adb, devices, kind, serial):
    if serial is None:
        candidates = [s for s, state in devices.items() if state == 'device' and device_kind(adb, s) == kind]
        if len(candidates) != 1:
            raise Failure(f'{len(candidates)} matching {kind} device(s). Run tools/setup.py --list, then pass --{kind} SERIAL.')
        serial = candidates[0]
    if devices.get(serial) != 'device':
        raise Failure(f'{serial} is not ready ({devices.get(serial, "not connected")}). Check the ADB connection and authorization on the device.')
    if device_kind(adb, serial) != kind:
        raise Failure(f'{serial} is not a matching device for {kind}.')
    sdk = shell(adb, serial, 'getprop', 'ro.build.version.sdk')
    if not sdk.isdigit() or int(sdk) < APPS[kind][2]:
        raise Failure(f'{serial}: Android API {APPS[kind][2]} or newer required (found: {sdk}).')
    return serial


def active_user(adb, serial):
    user = shell(adb, serial, 'am', 'get-current-user')
    if not user.isdigit():
        raise Failure('Could not identify the current Android user.')
    return user


def version_code(adb, serial, user, package):
    paths = shell(adb, serial, 'pm', 'list', 'packages', '--user', user, package)
    if 'package:' + package not in paths.splitlines():
        return None
    info = shell(adb, serial, 'dumpsys', 'package', package)
    version = re.search(r'(?m)^\s*versionCode=(\d+)', info)
    if not version:
        raise Failure(f'{serial}: Unable to determine the installed version of {package}.')
    return int(version.group(1))


def validate_manifest(manifest):
    if manifest.get('schema') != 1 or not re.fullmatch(r'\d+\.\d+\.\d+', manifest.get('version', '')):
        raise Failure('Unsupported release manifest.')
    expected = dict(APPS)
    if 'preview' in manifest.get('apps', {}):
        expected['preview'] = PREVIEW
        name = manifest['apps']['preview'].get('versionName', '')
        if not isinstance(name, str) or not re.fullmatch(r'\d+\.\d+\.\d+', name):
            raise Failure('Invalid preview version.')
    for kind, (package, asset, _) in expected.items():
        item = manifest.get('apps', {}).get(kind, {})
        if (item.get('package') != package or item.get('asset') != asset
                or type(item.get('versionCode')) is not int or item['versionCode'] < 1
                or not re.fullmatch(r'[0-9a-f]{64}', item.get('sha256', ''))):
            raise Failure(f'Invalid release metadata for {kind}.')
    return manifest


def open_release(url, timeout):
    context = ssl.create_default_context()
    # The python.org macOS installer can ship without a populated OpenSSL trust bundle.
    # Extend its trusted roots with the system CA file, keeping certificate verification enabled.
    system_certs = Path('/etc/ssl/cert.pem')
    if sys.platform == 'darwin' and not os.getenv('SSL_CERT_FILE') and system_certs.is_file():
        context.load_verify_locations(cafile=str(system_certs))
    return urllib.request.urlopen(url, timeout=timeout, context=context)


def metadata(latest=False):
    if not latest:
        return validate_manifest(json.loads((ROOT / 'release.json').read_text(encoding='utf-8')))
    with open_release(RELEASES + '/latest/download/update.json', timeout=30) as response:
        return validate_manifest(json.load(response))


def apk(manifest, kind, directory, local_dir=None):
    item = manifest['apps'][kind]
    name = item['asset']
    if local_dir:
        path = Path(local_dir) / name
    else:
        url = f'{RELEASES}/download/v{manifest["version"]}/{name}'
        path = Path(directory) / name
        print(f'Downloading {name} from release v{manifest["version"]} …', flush=True)
        with open_release(url, timeout=60) as source, path.open('wb') as output:
            shutil.copyfileobj(source, output, 1024 * 1024)
    digest = hashlib.sha256()
    with path.open('rb') as handle:
        for chunk in iter(lambda: handle.read(1024 * 1024), b''):
            digest.update(chunk)
    if digest.hexdigest() != item['sha256']:
        raise Failure(f'SHA-256 mismatch for {name}.')
    return path


def verify_signer_and_package(path, item):
    fingerprint = (ROOT / 'release-key.sha256').read_text(encoding='utf-8').strip().lower()
    if not re.fullmatch(r'[0-9a-f]{64}', fingerprint):
        raise Failure('Invalid OpenNava release certificate fingerprint.')
    output = command(find_tool('apksigner'), 'verify', '--print-certs', path, timeout=120)
    signers = re.findall(r'(?im)^Signer #\d+ certificate SHA-256 digest:\s*([0-9a-f]{64})\s*$', output)
    if [s.lower() for s in signers] != [fingerprint]:
        raise Failure(f'Unexpected signing certificate for {path.name}.')
    badging = command(find_tool('aapt'), 'dump', 'badging', path)
    pkg = re.search(r"(?m)^package: name='([^']+)' versionCode='(\d+)'", badging)
    if not pkg or pkg.group(1) != item['package'] or int(pkg.group(2)) != item['versionCode']:
        raise Failure(f'Package name or version in {path.name} does not match the release manifest.')


def install(adb, serial, user, path, item):
    reply = command(adb, '-s', serial, 'install', '-r', '--user', user, path, timeout=300)
    if 'Success' not in reply.splitlines():
        raise Failure(f'{serial}: Installation failed: {reply}')
    if version_code(adb, serial, user, item['package']) != item['versionCode']:
        raise Failure(f'{serial}: Could not verify the installed version.')
    print(f'{serial}: {item["package"]} · version code {item["versionCode"]} installed.')
