"""Offline tests for device targeting, release verification and non-destructive updates."""
import hashlib
import tempfile
import unittest
from pathlib import Path
from unittest import mock

import navatv
import setup
import update


def manifest():
    return {'schema': 1, 'version': '1.0.0', 'apps': {
        k: dict(package=spec[0], asset=spec[1], versionCode=100,
                sha256=hashlib.sha256(spec[0].encode()).hexdigest())
        for k, spec in navatv.APPS.items()}}


class SetupTests(unittest.TestCase):
    def test_ambiguous_device(self):
        with mock.patch.object(navatv, 'device_kind', return_value='tv'):
            with self.assertRaisesRegex(navatv.Failure, '2 matching tv'):
                navatv.target('adb', {'first': 'device', 'second': 'device'}, 'tv', None)
            with self.assertRaisesRegex(navatv.Failure, 'not a matching device'):
                navatv.target('adb', {'first': 'device'}, 'phone', 'first')

    def test_release_rejects_unexpected_artifacts_and_bad_local_bytes(self):
        release = manifest()
        with tempfile.TemporaryDirectory() as tmp:
            name = release['apps']['tv']['asset']
            (Path(tmp) / name).write_bytes(b'damaged')
            with self.assertRaisesRegex(navatv.Failure, 'SHA-256'):
                navatv.apk(release, 'tv', tmp, tmp)
        release['apps']['tv']['asset'] = '../something.apk'
        with self.assertRaises(navatv.Failure):
            navatv.validate_manifest(release)

    def test_verification_checks_actual_signer(self):
        digest = (navatv.ROOT / 'release-key.sha256').read_text().strip()
        item = manifest()['apps']['tv']
        with mock.patch.object(navatv, 'find_tool', side_effect=lambda cmd: cmd), mock.patch.object(navatv, 'command') as run:
            run.side_effect = ['Signer #1 certificate SHA-256 digest: ' + '0' * 64]
            with self.assertRaisesRegex(navatv.Failure, 'signing certificate'):
                navatv.verify_signer_and_package(Path('sample.apk'), item)
            run.side_effect = ['Signer #1 certificate SHA-256 digest: ' + digest, "package: name='other.app' versionCode='100'"]
            with self.assertRaisesRegex(navatv.Failure, 'Package name'):
                navatv.verify_signer_and_package(Path('sample.apk'), item)

    def test_tv_profile_is_read_back(self):
        with mock.patch.object(setup, 'shell', side_effect=['Status: ok', 'Result: Bundle[{profile=senior}]']) as run:
            setup.profile('adb', 'TV', '0', 'senior')
            self.assertIn('get_profile', run.call_args.args)

    def test_offline_timer_does_not_download(self):
        with mock.patch('sys.argv', ['update.py', '--scheduled', '--tv', 'TV']), \
             mock.patch.object(update, 'find_tool', return_value='adb'), \
             mock.patch.object(update, 'device_map', return_value={}), \
             mock.patch.object(update, 'metadata') as download:
            update.main()
            download.assert_not_called()

    def test_updates_skip_absent_or_newer_packages(self):
        for code in (None, 100, 101):
            with self.subTest(code=code), mock.patch('sys.argv', ['update.py', '--tv', 'TV']), \
                 mock.patch.object(update, 'find_tool', return_value='adb'), \
                 mock.patch.object(update, 'target', return_value='TV'), \
                 mock.patch.object(update, 'device_map', return_value={'TV': 'device'}), \
                 mock.patch.object(update, 'active_user', return_value='0'), \
                 mock.patch.object(update, 'version_code', return_value=code), \
                 mock.patch.object(update, 'metadata', return_value=manifest()), \
                 mock.patch.object(update, 'install') as install:
                update.main()
                install.assert_not_called()

    def test_setup_retry_applies_profile_without_reinstall(self):
        with mock.patch('sys.argv', ['setup.py', '--profile', 'full', '--tv', 'TV']), \
             mock.patch.object(setup, 'find_tool', return_value='adb'), \
             mock.patch.object(setup, 'target', return_value='TV'), \
             mock.patch.object(setup, 'device_map', return_value={'TV': 'device'}), \
             mock.patch.object(setup, 'active_user', return_value='0'), \
             mock.patch.object(setup, 'version_code', return_value=100), \
             mock.patch.object(setup, 'metadata', return_value=manifest()), \
             mock.patch.object(setup, 'profile') as profile, mock.patch.object(setup, 'install') as install:
            setup.main()
            install.assert_not_called()
            profile.assert_called_once_with('adb', 'TV', '0', 'full')


if __name__ == '__main__':
    unittest.main()
