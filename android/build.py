#!/usr/bin/env python3
"""Build and sign the phone companion, which carries the signed native TV app."""
import argparse
import hashlib
import json
import os
from pathlib import Path
import shutil
import subprocess
from tempfile import TemporaryDirectory
from zipfile import ZipFile

ANDROID = Path(__file__).resolve().parent
PLAYER = ANDROID / 'player'
CACHE = Path(os.environ.get('NAVATV_CACHE', Path.home() / '.cache/navatv')).resolve()
GRADLE_SHA256 = {
    '7.5': 'cb87f222c5585bd46838ad4db78463a5c5f3d336e5e2b98dc7c0c586527351c2',
    '8.13': '20f1b1176237254a6fc204d8434196fa11a4cfb387567519c61556e8710aed78',
}


def run(*args, env=None, cwd=None):
    subprocess.run([str(part) for part in args], check=True, env=env, cwd=cwd)


def git(path, *args):
    return subprocess.check_output(['git', '-C', str(path), *args], text=True).strip()


def sha256(path):
    digest = hashlib.sha256()
    with path.open('rb') as stream:
        for block in iter(lambda: stream.read(1024 * 1024), b''):
            digest.update(block)
    return digest.hexdigest()


def gradle(version):
    override = os.environ.get('NAVATV_GRADLE_' + version.replace('.', '_'))
    if override:
        return Path(override).resolve(strict=True)
    tool_dir = CACHE / 'tools'
    tool_dir.mkdir(parents=True, exist_ok=True)
    name = 'gradle-' + version
    executable = tool_dir / name / 'bin/gradle'
    if not executable.exists():
        archive = tool_dir / (name + '-bin.zip')
        if not archive.exists():
            with TemporaryDirectory(dir=tool_dir) as temp:
                download = Path(temp) / archive.name
                run('curl', '--fail', '--location', '--silent', '--show-error', '--retry', '2',
                    '--proto', '=https', '--proto-redir', '=https', '--output', download,
                    'https://services.gradle.org/distributions/' + archive.name)
                if sha256(download) != GRADLE_SHA256[version]:
                    raise SystemExit('Gradle distribution checksum mismatch.')
                download.replace(archive)
        if sha256(archive) != GRADLE_SHA256[version]:
            raise SystemExit('Gradle distribution checksum mismatch.')
        with ZipFile(archive) as zipped:
            zipped.extractall(tool_dir)
        executable.chmod(0o755)
    return executable


def upstream():
    pins = json.loads((PLAYER / 'source-pins.json').read_text())
    source = Path(os.environ.get('NAVATV_PLAYER_SOURCE', CACHE / 'SmartTube')).resolve()
    if not source.exists():
        source.mkdir(parents=True)
        run('git', 'init', '-q', source)
        git(source, 'remote', 'add', 'origin', pins['SmartTube']['url'])
        run('git', '-C', source, 'fetch', '--depth=1', 'origin', pins['SmartTube']['commit'])
        git(source, 'checkout', '-q', '--detach', 'FETCH_HEAD')
    if git(source, 'rev-parse', 'HEAD') != pins['SmartTube']['commit']:
        raise SystemExit('Unexpected SmartTube revision in ' + str(source))
    if not all((source / name / '.git').exists() for name in ('MediaServiceCore', 'SharedModules')):
        run('git', '-C', source, 'submodule', 'update', '--init', '--depth=1')
    for name in ('SmartTube', 'MediaServiceCore', 'SharedModules'):
        repo = source if name == 'SmartTube' else source / name
        if git(repo, 'rev-parse', 'HEAD') != pins[name]['commit'] or git(repo, 'status', '--porcelain', '--untracked-files=all'):
            raise SystemExit('Player source is modified or at a different revision: ' + str(repo))
    return source


def sign(unsigned, destination, minimum, sdk_tools, key, alias, password, env):
    with TemporaryDirectory(dir=destination.parent) as temp:
        aligned = Path(temp) / 'aligned.apk'
        signed = Path(temp) / destination.name
        run(sdk_tools / 'zipalign', '-f', '-P', '16', '4', unsigned, aligned, env=env)
        run(sdk_tools / 'apksigner', 'sign', '--ks', key, '--ks-key-alias', alias,
            '--ks-pass', 'file:' + str(password), '--out', signed, aligned, env=env)
        run(sdk_tools / 'apksigner', 'verify', '--min-sdk-version', minimum, signed, env=env)
        run(sdk_tools / 'zipalign', '-c', '-P', '16', '4', signed, env=env)
        signed.replace(destination)
    print(destination.name, sha256(destination), flush=True)


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--output', type=Path, default=ANDROID / 'build/release')
    parser.add_argument('--keystore', type=Path, required=True, help='Existing RSA release or development signing keystore')
    parser.add_argument('--alias', default='navatv')
    parser.add_argument('--password-file', type=Path, required=True, help='File containing the keystore and key password')
    parser.add_argument('--tv-apk', type=Path, help='The signed native TV app (rust-tv/tools/package_tv.py) to bundle')
    parser.add_argument('--only-fire-keys', action='store_true',
                        help='Build only the optional Fire TV shortcut helper; use the same key as the launcher.')
    args = parser.parse_args()
    env = dict(os.environ)
    if not env.get('JAVA_HOME') or not (env.get('ANDROID_HOME') or env.get('ANDROID_SDK_ROOT')):
        parser.error('Set JAVA_HOME (JDK 17) and ANDROID_HOME (Android SDK).')
    sdk = Path(env.get('ANDROID_HOME') or env['ANDROID_SDK_ROOT']).resolve()
    sdk_tools = sdk / 'build-tools/36.0.0'
    required = [sdk / 'platforms/android-36/android.jar', sdk_tools / 'apksigner',
                sdk_tools / 'aapt2', sdk_tools / 'zipalign']
    for path in required:
        if not path.is_file():
            parser.error('Android SDK component unavailable: ' + str(path))
    env.update(ANDROID_HOME=str(sdk), ANDROID_SDK_ROOT=str(sdk))
    output = args.output.resolve()
    output.mkdir(parents=True, exist_ok=True)
    key, password = args.keystore.resolve(strict=True), args.password_file.resolve(strict=True)
    if args.only_fire_keys:
        run(gradle('8.13'), '-p', ANDROID, '--configure-on-demand', ':fire-keys:assembleRelease', env=env)
        sign(ANDROID / 'fire-keys/build/outputs/apk/release/fire-keys-release-unsigned.apk',
             output / 'OpenNava-Fire-Keys.apk', 28, sdk_tools, key, args.alias, password, env)
        return
    if not args.tv_apk:
        parser.error('Build the TV app first (rust-tv/tools/package_tv.py) and pass it with --tv-apk.')
    tv = args.tv_apk.resolve(strict=True)
    modern = gradle('8.13')
    payload = ANDROID / 'build/payload/OpenNava-TV.apk'
    payload.parent.mkdir(parents=True, exist_ok=True)
    shutil.copyfile(tv, payload)
    run(modern, '-p', ANDROID, '--configure-on-demand', ':companion:assembleRelease', env=env)
    phone = output / 'OpenNava-Companion.apk'
    sign(ANDROID / 'companion/build/outputs/apk/release/companion-release-unsigned.apk',
         phone, 30, sdk_tools, key, args.alias, password, env)
    with ZipFile(phone) as apk, apk.open('assets/OpenNava-TV.apk') as embedded:
        if hashlib.sha256(embedded.read()).hexdigest() != sha256(tv):
            raise SystemExit('Bundled TV payload differs from the signed launcher.')


if __name__ == '__main__':
    main()
