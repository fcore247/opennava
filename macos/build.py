#!/usr/bin/env python3
"""Build the universal OpenNava Studio macOS application."""
import argparse
import plistlib
import shutil
import subprocess
import sys
import tempfile
from pathlib import Path

HERE = Path(__file__).resolve().parent
PROJECT = HERE.parent
sys.path.insert(0, str(PROJECT / 'tools'))
from navatv import Failure, apk, metadata, verify_signer_and_package


def run(*args, quiet=False):
    subprocess.run([str(arg) for arg in args], check=True,
                   stdout=subprocess.DEVNULL if quiet else None)


def build(args):
    manifest = metadata()
    release = manifest['version']
    output = args.output.expanduser().resolve()
    output.mkdir(parents=True, exist_ok=True)
    with tempfile.TemporaryDirectory(dir=output) as temp:
        staging = Path(temp)
        app = staging / 'OpenNava Studio.app'
        executable = app / 'Contents/MacOS/OpenNava Studio'
        resources = app / 'Contents/Resources'
        executable.parent.mkdir(parents=True)
        resources.mkdir()
        slices = []
        for arch in ('arm64', 'x86_64'):
            path = staging / arch
            run('xcrun', 'swiftc', '-swift-version', '5', '-O', '-parse-as-library',
                '-target', arch + '-apple-macos13.0', *sorted((HERE / 'Sources').glob('*.swift')), '-o', path)
            slices.append(path)
        run('xcrun', 'lipo', '-create', *slices, '-output', executable)
        icon = staging / 'icon.png'
        run('xcrun', 'swift', HERE / 'Artwork/Icon.swift', PROJECT / 'docs/assets/opennava-mark.svg', icon)
        icons = staging / 'Studio.iconset'
        icons.mkdir()
        for size in (16, 32, 128, 256, 512):
            for scale, suffix in ((1, ''), (2, '@2x')):
                run('sips', '-z', size * scale, size * scale, icon,
                    '--out', icons / f'icon_{size}x{size}{suffix}.png', quiet=True)
        run('iconutil', '-c', 'icns', icons, '-o', resources / 'Studio.icns')
        if args.include_launcher or args.assets_dir:
            path = apk(manifest, 'tv', staging, args.assets_dir)
            item = manifest['apps']['tv']
            verify_signer_and_package(path, item)
            shutil.copyfile(path, resources / item['asset'])
            (resources / 'TVBuild.plist').write_bytes(plistlib.dumps(
                {'package': item['package'], 'versionCode': item['versionCode'], 'versionName': release}))
        (app / 'Contents/Info.plist').write_bytes(plistlib.dumps({
            'CFBundleIdentifier': 'site.navatv.studio',
            'CFBundleName': 'OpenNava Studio',
            'CFBundleDisplayName': 'OpenNava Studio',
            'CFBundleExecutable': 'OpenNava Studio',
            'CFBundlePackageType': 'APPL',
            'CFBundleIconFile': 'Studio',
            'CFBundleVersion': '1',
            'CFBundleShortVersionString': release,
            'LSMinimumSystemVersion': '13.0',
            'NSPrincipalClass': 'NSApplication',
            'NSHighResolutionCapable': True,
            'NSLocalNetworkUsageDescription': 'Find and control the Android TVs you authorize on your network.',
            'NSBonjourServices': ['_adb._tcp', '_adb-tls-connect._tcp', '_adb-tls-pairing._tcp']
        }))
        run('codesign', '--force', '--sign', '-', app)
        run('codesign', '--verify', '--strict', '--deep', app)
        destination = output / app.name
        backup = staging / 'previous.app'
        if destination.exists():
            destination.rename(backup)
        try:
            app.rename(destination)
        except OSError:
            if backup.exists():
                backup.rename(destination)
            raise
        if args.zip:
            archive = output / 'OpenNava-Studio-macOS.zip'
            run('ditto', '-c', '-k', '--keepParent', '--norsrc', '--noextattr', '--noacl', destination, staging / 'release.zip')
            (staging / 'release.zip').replace(archive)
            print(archive)
        print(destination)


if __name__ == '__main__':
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--output', type=Path, default=HERE / 'build', help='Output folder')
    parser.add_argument('--include-launcher', action='store_true', help='Embed the pinned official TV APK')
    parser.add_argument('--assets-dir', type=Path, help='Embed the official TV APK from this directory')
    parser.add_argument('--zip', action='store_true', help='Create the downloadable Studio app ZIP')
    try:
        build(parser.parse_args())
    except (Failure, OSError, subprocess.CalledProcessError) as problem:
        sys.exit(str(problem))
