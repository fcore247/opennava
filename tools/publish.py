#!/usr/bin/env python3
"""Prepare signed OpenNava artifacts, then publish the verified draft from a pushed source commit."""
import argparse
import hashlib
import json
import re
import subprocess
import sys
import tempfile
from pathlib import Path
from zipfile import ZipFile, BadZipFile

from navatv import APPS, KEYS, ROOT, Failure, command, find_tool, validate_manifest, verify_signer_and_package

REPO = 'fcore247/opennava'


def sha(handle):
    digest = hashlib.sha256()
    for block in iter(lambda: handle.read(1024 * 1024), b''):
        digest.update(block)
    return digest.hexdigest()


def digest(path):
    with path.open('rb') as stream:
        return sha(stream)


def release_files(directory, version):
    if not re.fullmatch(r'\d+\.\d+\.\d+', version):
        raise Failure('Use a stable version such as 1.0.0 (without v).')
    apps = {}
    for key, (package, name, _) in ((KEYS[kind], spec) for kind, spec in APPS.items()):
        path = directory / name
        metadata = command(find_tool('aapt'), 'dump', 'badging', path).splitlines()[0]
        app = re.search(r"^package: name='([^']+)' versionCode='(\d+)' versionName='([^']+)'", metadata)
        if not app or app.group(1) != package or (key != 'preview' and app.group(3) != version):
            raise Failure(f'Unexpected package or version for {name}.')
        item = dict(package=package, versionCode=int(app.group(2)), asset=name, sha256=digest(path))
        if key == 'preview':
            item['versionName'] = app.group(3)
        verify_signer_and_package(path, item)
        apps[key] = item
    with ZipFile(directory / APPS['phone'][1]) as bundle, bundle.open('assets/OpenNava-TV.apk') as payload:
        if sha(payload) != apps['preview']['sha256']:
            raise Failure('The companion embeds a different TV APK. Rebuild it using this release\'s signed TV payload.')
    manifest = validate_manifest(dict(schema=1, version=version, apps=apps))
    data = (json.dumps(manifest, indent=2) + '\n').encode()
    hashes = {item['asset']: item['sha256'] for item in apps.values()}
    hashes['update.json'] = hashlib.sha256(data).hexdigest()
    sums = ''.join(f'{value}  {name}\n' for name, value in hashes.items()).encode()
    return data, sums, hashes


def gh(*args):
    return command('gh', *args, '--repo', REPO, timeout=600)


def view(tag):
    fields = 'isDraft,targetCommitish,assets,url'
    result = subprocess.run(['gh','release','view',tag,'--repo',REPO,'--json',fields],
                            text=True, capture_output=True, timeout=30)
    if result.returncode == 0:
        return json.loads(result.stdout)
    if 'release not found' in result.stderr.lower() or 'HTTP 404' in result.stderr:
        return None
    raise Failure(result.stderr.strip() or 'GitHub release lookup failed.')


def git(*args):
    return command('git', '-C', ROOT, *args)


def publish(directory, version, hashes, notes):
    if git('status', '--porcelain'):
        raise Failure('Commit the finished release manifest and all release source before publishing.')
    commit = git('rev-parse', 'HEAD')
    # Read the release destination itself; a differently configured local origin cannot redirect a launch.
    url = f'https://github.com/{REPO}.git'
    main = git('ls-remote', url, 'refs/heads/main').split()
    if not main or main[0] != commit:
        raise Failure('The release checkout must match the pushed main commit.')
    tag = 'v' + version
    refs = git('ls-remote', url, f'refs/tags/{tag}', f'refs/tags/{tag}^{{}}').splitlines()
    if refs and refs[-1].split()[0] != commit:
        raise Failure('Existing tag points to a different source commit.')
    current = view(tag)
    if current and not current['isDraft']:
        raise Failure(f'{tag} is public and will not be overwritten.')
    if current and current['targetCommitish'] != commit:
        raise Failure('A draft exists for another source commit. Inspect it on GitHub.')
    if not current:
        note_args = ['--notes-file', str(notes)] if notes else ['--notes', f'OpenNava {version}. Signed TV and Android companion apps.']
        gh('release', 'create', tag, '--draft', '--target', commit, '--title', 'OpenNava ' + version, *note_args)
    current = view(tag)
    if not current or not current['isDraft'] or current['targetCommitish'] != commit:
        raise Failure('Could not verify the release draft.')
    current_assets = {a['name']: a.get('digest') for a in current['assets']}
    hashes = dict(hashes, SHA256SUMS=digest(directory / 'SHA256SUMS'))
    if set(current_assets) - set(hashes):
        raise Failure('The draft contains unexpected files. Inspect it before publishing.')
    for name, value in hashes.items():
        if current_assets.get(name) != 'sha256:' + value:
            gh('release', 'upload', tag, directory / name, '--clobber')
    receipt = view(tag)
    if not receipt or not receipt['isDraft'] or receipt['targetCommitish'] != commit:
        raise Failure('Draft was changed during upload.')
    remote = {a['name']: a.get('digest') for a in receipt['assets']}
    if set(remote) != set(hashes):
        raise Failure('GitHub did not receive the complete release.')
    # Modern GitHub includes sha256 in the asset API. Older gh builds may omit that field.
    for name, value in remote.items():
        if value:
            if value != 'sha256:' + hashes[name]:
                raise Failure(f'GitHub checksum mismatch for {name}.')
        else:
            with tempfile.TemporaryDirectory(prefix='opennava-verify-draft-') as tmp:
                gh('release', 'download', tag, '--pattern', name, '--dir', tmp)
                if digest(Path(tmp) / name) != hashes[name]:
                    raise Failure(f'GitHub checksum mismatch for {name}.')
    gh('release', 'edit', tag, '--draft=false', '--latest')
    receipt = view(tag)
    if not receipt or receipt['isDraft']:
        raise Failure('Release publication could not be confirmed.')
    print(receipt['url'])


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('action', choices=['prepare', 'publish'])
    parser.add_argument('--version', required=True, help='Stable versionName of both APKs, e.g. 1.0.0.')
    parser.add_argument('--assets-dir', type=Path, required=True)
    parser.add_argument('--notes-file', type=Path, help='Markdown release notes for a new draft.')
    args = parser.parse_args()
    folder = args.assets_dir.resolve()
    data, sums, hashes = release_files(folder, args.version)
    outputs = {ROOT / 'release.json': data, folder / 'update.json': data, folder / 'SHA256SUMS': sums}
    if args.action == 'prepare':
        for path, content in outputs.items():
            with tempfile.NamedTemporaryFile(dir=path.parent, delete=False) as temp:
                temp.write(content)
            Path(temp.name).replace(path)
        for name, value in hashes.items():
            print(f'{value}  {name}')
        return
    if any(path.read_bytes() != expected for path, expected in outputs.items()):
        raise Failure('Release files have changed. Run prepare, verify, commit and push the result.')
    publish(folder, args.version, hashes, args.notes_file)


if __name__ == '__main__':
    try:
        main()
    except (Failure, OSError, ValueError, KeyError, BadZipFile, subprocess.TimeoutExpired) as error:
        sys.exit(str(error))
