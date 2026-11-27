#!/usr/bin/env python3
"""Build the Rust TV interface and its private native Android players in one signed APK."""
import argparse
import importlib.util
import json
import os
import shutil
import shlex
from pathlib import Path
import re
import subprocess
import tempfile
import zipfile

PROJECT = Path(__file__).resolve().parents[1]
ANDROID = PROJECT.parent / 'android'
TARGETS = {
    'armeabi-v7a': ('armv7-linux-androideabi', 'armv7a-linux-androideabi26-clang'),
    'arm64-v8a': ('aarch64-linux-android', 'aarch64-linux-android26-clang'),
}
PACKAGE = 'site.navatv.rustpreview'
RUST_SO = 'libopennava_rust.so'
spec = importlib.util.spec_from_file_location('opennava_android_build', ANDROID / 'build.py')
android_build = importlib.util.module_from_spec(spec)
spec.loader.exec_module(android_build)


def build_player_libraries(env, tools):
    libraries = PROJECT / 'player/build/sdk'
    player_env = dict(env, NAVATV_PLAYER_SOURCE=str(android_build.upstream()), TV_PLAYER_LIBS=str(libraries))
    if env.get('NAVATV_LEGACY_GRADLE_HOME'):
        player_env['GRADLE_USER_HOME'] = env['NAVATV_LEGACY_GRADLE_HOME']
    subprocess.run([android_build.gradle('7.5'), '-p', ANDROID / 'player',
                    '-I', ANDROID / 'player/export.gradle', '-PopennavaTvPairing=true', '-Pandroid.aapt2FromMavenOverride=' + str(tools / 'aapt2'),
                    ':app:exportPlayerLibraries'], env=player_env, check=True)


def cross_compile(env, ndk):
    hosts = ndk / 'toolchains/llvm/prebuilt'
    toolchain = next((p / 'bin' for p in sorted(hosts.iterdir())
                      if (p / 'bin' / TARGETS['arm64-v8a'][1]).is_file()), None)
    if not toolchain:
        raise SystemExit('The Android NDK needs its host LLVM toolchain for API 26.')
    # Rust panic locations and native assertion strings ship even when debug info is stripped.
    # Keep them useful for debugging without embedding a developer's account or checkout path.
    maps = [(Path.home(), '/build/home'), (PROJECT.parent, '/build/opennava'),
            (Path(env.get('CARGO_HOME', Path.home() / '.cargo')), '/build/cargo'),
            (Path(env.get('RUSTUP_HOME', Path.home() / '.rustup')), '/build/rustup'),
            (ndk, '/build/android-ndk')]
    flags = (env['CARGO_ENCODED_RUSTFLAGS'].split('\x1f') if env.get('CARGO_ENCODED_RUSTFLAGS')
             else shlex.split(env.get('RUSTFLAGS', '')))
    flags.extend('--remap-path-prefix=' + str(src) + '=' + dest for src, dest in maps)
    env['CARGO_ENCODED_RUSTFLAGS'] = '\x1f'.join(flags)
    cargo = ['cargo', 'build', '--locked', '--lib', '--release']
    for target, compiler in TARGETS.values():
        cc = str(toolchain / compiler)
        triple = target.replace('-', '_')
        cflags = [shlex.quote('-ffile-prefix-map=' + str(src) + '=' + dest) for src, dest in maps]
        for variable in ('CFLAGS_', 'CXXFLAGS_'):
            name = variable + triple
            env[name] = ' '.join([env.get(name, ''), *cflags]).strip()
        env['CC_' + triple] = cc
        env['CXX_' + triple] = cc + '++'
        env['AR_' + triple] = str(toolchain / 'llvm-ar')
        env['CARGO_TARGET_' + triple.upper() + '_LINKER'] = cc
        cargo.extend(['--target', target])
    subprocess.run(cargo, cwd=PROJECT, env=env, check=True)
    metadata = []
    for target, _ in TARGETS.values():
        metadata.append(json.loads(subprocess.check_output(
            ['cargo', 'metadata', '--locked', '--format-version=1', '--filter-platform', target],
            cwd=PROJECT, env=env, text=True)))
    target_dir = Path(metadata[0]['target_directory'])
    rust_libraries = {abi: target_dir / target / 'release' / RUST_SO
                      for abi, (target, _) in TARGETS.items()}
    return target_dir, rust_libraries, metadata


def collect_notices(metadata, out):
    packages = {p['id']: p for data in metadata for p in data['packages'] if p['source']}
    index = []
    for p in sorted(packages.values(), key=lambda p: (p['name'], p['version'])):
        version = p['name'] + '-' + p['version']
        src = Path(p['manifest_path']).parent
        dest = out / version
        dest.mkdir(parents=True)
        license_paths = {f for f in src.iterdir()
                         if f.name.upper().startswith(('LICENSE', 'LICENCE', 'COPYING', 'UNLICENSE', 'NOTICE'))}
        if p['license_file']:
            license_paths.add(src / p['license_file'])
        for path in sorted(license_paths):
            if path.is_dir():
                shutil.copytree(path, dest / path.name)
            else:
                shutil.copyfile(path, dest / path.name)
        # Some crates publish their license at the workspace root, outside the crate archive.
        if not license_paths:
            vendored = PROJECT / 'notices' / version
            if not vendored.is_dir():
                raise ValueError('Missing Rust dependency license: ' + version)
            shutil.copytree(vendored, dest, dirs_exist_ok=True)
        index.append({k: p[k] for k in ('name', 'version', 'authors', 'license', 'repository')})
    (out / 'index.json').write_text(json.dumps(index, indent=2, ensure_ascii=False) + '\n')


def verify_package(apk, rust_libraries, tools, env, expected_signer):
    with zipfile.ZipFile(apk) as archive:
        names = set(archive.namelist())
        for abi, library in rust_libraries.items():
            rust_bytes = archive.read('lib/' + abi + '/' + RUST_SO)
            if str(Path.home()).encode() + b'/' in rust_bytes:
                raise ValueError('Local home directory embedded in Rust library: ' + abi)
            if rust_bytes != library.read_bytes():
                raise ValueError('Packaged Rust library differs from the Cargo build: ' + abi)
        if not any(re.fullmatch(r'classes(?:\d+)?\.dex', name) for name in names):
            raise ValueError('Native Android player classes are missing.')
        if any(name.startswith(('assets/web/', 'assets/full/', 'assets/senior/')) for name in names):
            raise ValueError('Unexpected legacy browser interface assets.')
        for name in ('player/SmartTube-MIT.txt', 'player/Regex-1.1.11-source.zip', 'player/J2V8-License.txt',
                     'rust/index.json', 'fonts/OFL.txt', 'fonts/OFL-ReenieBeanie.txt', 'fonts/OFL-InstrumentSerif.txt', 'ASSETS.md'):
            if 'assets/licenses/' + name not in names:
                raise ValueError('Missing packaged notice: ' + name)
    xml = subprocess.check_output([tools / 'aapt', 'dump', 'xmltree', apk, 'AndroidManifest.xml'],
                                  env=env, text=True)
    for value in (PACKAGE, 'android:banner', 'android.app.NativeActivity', 'site.navatv.launcher',
                  'android.intent.category.LEANBACK_LAUNCHER', 'site.navatv.full.NavaPlayerActivity',
                  'site.navatv.launcher.CompanionActivity', 'site.navatv.rustpreview.live',
                  PACKAGE + '.StreamActivity', PACKAGE + '.YoutubeAccountActivity',
                  'site.navatv.launcher.LiveTvActivity', 'site.navatv.launcher.TvProgrammeActivity',
                  'site.navatv.launcher.RecordingsActivity', 'site.navatv.launcher.RecordingService',
                  'site.navatv.launcher.RecordingArchiveJob', 'site.navatv.launcher.PhoneLinkActivity',
                  'site.navatv.updates.UpdateActivity',
                  'site.navatv.updates.UpdateJob', 'site.navatv.updates.InstallResult',
                  'android.permission.REQUEST_INSTALL_PACKAGES', 'android.permission.RECEIVE_BOOT_COMPLETED'):
        if value not in xml:
            raise ValueError('Missing TV metadata: ' + value)
    if 'android.intent.category.HOME' not in xml:
        raise ValueError('Missing Android Home intent filter.')
    badging = subprocess.check_output([tools / 'aapt', 'dump', 'badging', apk], env=env, text=True)
    package = json.loads(subprocess.check_output(
        ['cargo', 'metadata', '--locked', '--no-deps', '--format-version=1'], cwd=PROJECT, env=env,
        text=True))['packages'][0]
    version = package['version']
    code = str(package['metadata']['android']['version-code'])
    if not re.search(r"^package: name='" + re.escape(PACKAGE) +
                     r"' versionCode='" + re.escape(code) + r"' versionName='" + re.escape(version) + "'", badging, re.MULTILINE):
        raise ValueError('Unexpected Android package or version.')
    if "sdkVersion:'26'" not in badging:
        raise ValueError('Unexpected minimum SDK.')
    subprocess.run([tools / 'zipalign', '-c', '-P', '16', '4', apk], env=env, check=True)
    certificate = subprocess.check_output([tools / 'apksigner', 'verify', '--min-sdk-version', '26',
                                           '--print-certs', apk], env=env, text=True)
    if expected_signer:
        digests = re.findall(r'^Signer #\d+ certificate SHA-256 digest: ([0-9a-f]+)$',
                             certificate, re.MULTILINE)
        if digests != [expected_signer.read_text().strip().lower()]:
            raise ValueError('APK signer does not match the expected certificate.')


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--output', type=Path, help='Destination for the final signed APK')
    parser.add_argument('--keystore', type=Path, required=True)
    parser.add_argument('--ks-pass-file', type=Path, required=True, help='Password file outside the checkout')
    parser.add_argument('--ks-key-alias', required=True)
    parser.add_argument('--expected-signer', type=Path, help='File containing the expected SHA-256 certificate digest')
    args = parser.parse_args()
    env = dict(os.environ)
    sdk = env.get('ANDROID_HOME') or env.get('ANDROID_SDK_ROOT')
    ndk = env.get('ANDROID_NDK_ROOT') or env.get('ANDROID_NDK_HOME')
    if not (sdk and ndk and env.get('JAVA_HOME')):
        parser.error('Set JAVA_HOME, ANDROID_HOME and ANDROID_NDK_ROOT.')
    sdk_tools = Path(sdk) / 'build-tools/36.0.0'
    env.update(ANDROID_HOME=sdk, ANDROID_SDK_ROOT=sdk)
    key = args.keystore.expanduser().resolve(strict=True)
    password = args.ks_pass_file.expanduser().resolve(strict=True)
    target, rust_libraries, metadata = cross_compile(env, Path(ndk))
    build_player_libraries(env, sdk_tools)
    player = PROJECT / 'player'
    with tempfile.TemporaryDirectory(dir=target) as scratch:
        notices = Path(scratch) / 'notices'
        collect_notices(metadata, notices)
        subprocess.run([android_build.gradle('8.13'), '-p', player, '-PrustNotices=' + str(notices),
                        '-PrustLibraryArm32=' + str(rust_libraries['armeabi-v7a']),
                        '-PrustLibraryArm64=' + str(rust_libraries['arm64-v8a']), 'assembleRelease'],
                       env=env, check=True)
    unsigned = player / 'build/outputs/apk/release/opennava-rust-player-release-unsigned.apk'
    output = args.output.resolve() if args.output else target / 'release/apk/OpenNava-Rust-TV.apk'
    output.parent.mkdir(parents=True, exist_ok=True)
    with tempfile.TemporaryDirectory(dir=output.parent) as work:
        aligned = Path(work) / 'aligned.apk'
        signed = Path(work) / 'signed.apk'
        subprocess.run([sdk_tools / 'zipalign', '-f', '-P', '16', '4', unsigned, aligned],
                       env=env, check=True)
        subprocess.run([sdk_tools / 'apksigner', 'sign', '--ks', key,
                        '--ks-key-alias', args.ks_key_alias, '--ks-pass', 'file:' + str(password),
                        '--v4-signing-enabled', 'false', '--out', signed, aligned], env=env, check=True)
        verify_package(signed, rust_libraries, sdk_tools, env, args.expected_signer)
        os.replace(signed, output)
    print(output)


if __name__ == '__main__':
    main()
