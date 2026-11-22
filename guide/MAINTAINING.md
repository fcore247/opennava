# Maintaining OpenNava

Run the commands below from the repository root unless a step changes directory. The TV and Android companion ship together in a stable GitHub Release. The public website is deployed separately from the `docs/` directory on the `main` branch.

Use the [architecture guide](ARCHITECTURE.md) to find a component's source. Optional components have their own build instructions for [macOS Studio](../macos/README.md), [Fire TV remote keys](../android/fire-keys/README.md) and the [legacy web redirect](../hosting/legacy-redirect/README.md).

## Change the launchers or website

| Change | Edit here |
| --- | --- |
| Full launcher | `android/tv/src/main/assets/web/` and device overrides in `android/tv/src/main/assets/google/` |
| Senior view | `android/tv/src/main/assets/index.html`, the adjacent scripts and styles, and the `assets/` media directory |
| Android TV system functions, playback or channels | The [TV application](../android/tv), including its Java channel lineup |
| Browser playback and TV controls | [Demo host](../docs/demo-host). It resolves live streams and programme schedules from the broadcasters, uses bundled samples for library previews and switches the embedded TV between profiles. |
| Public page layout, setup links and downloads | [Website source](../docs), including the [Android phone download](../docs/handy) |
| Full browser demo interface and search | [Full demo](../docs/demo/full) |

The stage command copies the Android senior launcher and shared full-launcher media, styles and licenses. It regenerates the channel list, inserts the browser bridge in the senior page and refreshes the full demo’s cache keys. Edit the full browser interface in `docs/demo/full/` and the paired-demo landing page in `docs/demo/index.html`. Keep household-specific weather, images and configuration out of the staged assets.

```sh
python3 tools/sync_web_demo.py
python3 tools/sync_web_demo.py --check
python3 -m http.server 8000 --bind 127.0.0.1 --directory docs
```

Open [the local preview](http://127.0.0.1:8000/) and try each profile inside the TV. In a second terminal, run the optional Chromium tests. Node.js 20 or later is required.

```sh
cd tests/website
npm ci
npx playwright install chromium
OPENNAVA_URL=http://127.0.0.1:8000/ npm test
```

The [browser suite](../tests/website) uses the desktop keyboard and mobile taps to operate the actual launcher UI, verify video playback and switch profiles. On-device playback, remote keys, ADB pairing and broadcaster availability require separate Android checks.

Browser live TV uses the permanent station IDs from `LiveGuide.java`. [live-streams.js](../docs/demo-host/live-streams.js) resolves current HLS streams through the same broadcaster services and reads real guide data. ARD supplies its national and regional channels and the German ARTE simulcast. ZDF supplies its three channels. Guide data comes from ARD, ZDF and ARTE in the broadcaster's timezone. Availability and rewind limits depend on the channel, region and current programme. Playback uses native HLS on supported Apple browsers or [vendored hls.js](../docs/demo-host/vendor/README.md) on compatible Media Source Extensions browsers.

The default live regression uses a local HLS fixture and fixed programme data. From `tests/website`, use `OPENNAVA_LIVE_SMOKE=1 npm test -- -g 'unmodified public broadcaster'` to verify that an actual broadcaster currently plays in the public demo.

## Deploy the site

Push the reviewed source and staged demo to `main`. GitHub Pages builds `main:/docs`, uses `docs/CNAME` for the custom domain and serves the files at [opennava.site](https://opennava.site/). A website-only change takes effect without an Android update.

```sh
git rev-parse HEAD
gh api repos/fcore247/opennava/pages/builds/latest --jq '[.commit,.status] | @tsv'
gh run list --repo fcore247/opennava --limit 3
```

Match the deployed commit to the intended change. After Pages reports `built`, run `npm test` from `tests/website` with `OPENNAVA_URL` unset to exercise the canonical HTTPS site. GitHub's Pages build is the hosted deployment check. Android builds, Python tests and browser tests currently run on the maintainer's machine.

## Build and test Android

Follow [android/BUILD.md](../android/BUILD.md) to install JDK 17, SDK platforms 34 and 36, and Build Tools 36.0.0. The build fetches the pinned upstream revisions. Set `JAVA_HOME` and `ANDROID_HOME`, and use a private key stored outside the repository. A locally signed build can be used on a separate test device or emulator. Official in-place updates require the public release signing identity.

```sh
python3 -m unittest discover -s tools -p 'test_*.py'
python3 android/build.py --keystore /private/path/opennava.p12 \
  --password-file /private/path/key-password --output ../opennava-release
```

The build signs the TV application, embeds those exact signed bytes in the Android companion, signs the companion and checks that the two TV payloads match. The output files are `OpenNava.apk` for `site.navatv.launcher` and `OpenNava-Companion.apk` for `site.navatv.companion`.

For an installation test, use a disposable TV and phone emulator or explicitly selected test hardware. `tools/setup.py --list` shows serials and device types. When the candidate is signed with the official key, prepare its metadata as described below and point setup at the tested files.

```sh
python3 tools/setup.py --tv TV_SERIAL --profile full --phone PHONE_SERIAL --assets-dir ../opennava-release
python3 tools/setup.py --tv TV_SERIAL --profile senior --assets-dir ../opennava-release
adb -s TV_SERIAL shell content call --uri content://site.navatv.launcher.companion --method get_profile
adb -s PHONE_SERIAL shell am start -W -n site.navatv.companion/.MainActivity
```

Reopen the TV app and confirm it still uses the saved profile. Also exercise a live channel, broadcaster search, playback, pause and Back. To validate an installed companion, pair the phone and try a TV command. For a launcher-role change, check the user-requested remote's physical Home button after the installer reports its resolved Home activity.

## Publish a stable Android release

1. Set the same three-part `versionName` in [tv/build.gradle](../android/tv/build.gradle) and [companion/build.gradle](../android/companion/build.gradle). Increase both `versionCode` values above the previous public release. Keep package IDs and signing identity stable so Android can replace existing installations in place.
2. Build both APKs from the intended release source using the official key. Store the private keystore and password file outside the checkout. The committed [fingerprint](../release-key.sha256) contains only the public certificate's SHA-256. Local build APKs, test fixtures and passwords stay out of the source distribution.
3. Build the [Rust preview](../rust-tv/README.md) from the same checkout and sign it with the release key. Place `OpenNava-Rust-TV.apk` alongside the signed TV and companion builds. The optional app has its own package and version. From the same checkout, prepare the release files. Replace the example version with the new version.

   ```sh
   python3 tools/publish.py prepare --version 1.1.0 --assets-dir ../opennava-release
   ```

   The script verifies each APK signature, package, version and digest, and checks the embedded TV APK. It writes the initial install manifest (`release.json`) to the root, and writes identical `update.json` and a separate `SHA256SUMS` into the release directory. Each manifest entry contains the asset filename, Android package name, integer version code and SHA-256 under `apps.tv`, `apps.phone` or `apps.preview`. The preview entry also carries its independent Android version name. Automatic TV and phone updates target only the classic apps.

4. Review both apps, the source changes and `release.json`. Commit and push the release source to `main`. Use a signed-in GitHub CLI with release write access, a clean checkout and the exact pushed commit.

   ```sh
   python3 tools/publish.py publish --version 1.1.0 --assets-dir ../opennava-release
   ```

   For custom release notes, supply `--notes-file /path/to/notes.md` when creating the draft. The publisher binds the `vVERSION` release to the current source commit. It uploads all three APKs, `update.json` and `SHA256SUMS` to a draft, checks the complete uploaded set and promotes it to the latest stable release. Re-running against the same draft can finish an interrupted upload. A public tag is immutable in this workflow. Ship a higher version to correct a published build.

5. Verify the public release independently. In the following example, start in the repository root and use an empty destination.

   ```sh
   gh release view v1.1.0 --repo fcore247/opennava --json url,tagName,isDraft,assets
   gh release download v1.1.0 --repo fcore247/opennava --dir ../opennava-public-check
   cmp release.json ../opennava-public-check/update.json
   (cd ../opennava-public-check && shasum -a 256 -c SHA256SUMS)
   curl -fsSL https://github.com/fcore247/opennava/releases/latest/download/update.json
   ```

   The latest feed should report the intended version. Fresh installs anchor their downloads to the checkout's release manifest. The native and host updaters read the latest feed and fetch APKs from the version tag it names.

## Publish optional tools

For optional desktop and Fire TV utilities, publish a separate tools release such as [tools-v1.0.0](https://github.com/fcore247/opennava/releases/tag/tools-v1.0.0). Include the Studio ZIP, signed helper APK and a checksum file for those two downloads. Mark the tools release with `--latest=false`, then verify that `/releases/latest/download/update.json` still returns the stable Android manifest. The launcher's update feed uses the latest stable Android release.

## Verify updates in place

**Native Android update.** The classic launcher, Rust launcher, and companion share [android/updates](../android/updates). Start from an older official build on an emulator, or a private same-signer fixture built from an isolated checkout. Save a profile or another identifiable setting. Open **Software updates**, check availability and allow Android to install from this source if prompted. The updater validates SHA-256, package ID, version and the signing certificate before committing an Android PackageInstaller session. Android can request confirmation depending on device policy and install ownership.

Automatic checks are enabled by default for each app. The TV job checks the feed daily with a network connection and records newer versions for the settings badge. It leaves installation to a manual request or the next cold launcher start. The phone job also waits for the device to be idle. To verify the TV flow, check that the installed version stays the same during a daily check, then relaunch and confirm the installed version and badge. For a repeatable test on an enrolled emulator, enable automatic checks, open the application once to schedule the job, and run these commands with its actual user ID, serial and package:

```sh
adb -s EMULATOR_SERIAL shell am get-current-user
adb -s EMULATOR_SERIAL shell cmd jobscheduler run -f -u USER_ID site.navatv.companion 5128534
adb -s EMULATOR_SERIAL shell dumpsys package site.navatv.companion
```

The forced job exercises the installed native updater against the published feed. Recheck `versionCode` and `versionName` after installation, launch the updated application and check the saved state. A successful job dispatch alone only confirms that Android accepted the job request.

**Host update over ADB.** The host updater needs a reachable, authorized device, Python 3, Android SDK Build Tools and Java. The first command only reports versions, and the second upgrades existing packages if the feed offers a higher version code.

```sh
python3 tools/update.py --check --tv TV_SERIAL --phone PHONE_SERIAL
python3 tools/update.py --tv TV_SERIAL --phone PHONE_SERIAL
```

Use either target by itself as needed. The host verifies the release APK hash and signing identity, updates in place, and checks the installed code. Profile selection belongs to initial setup. An unattended host can enroll the intended devices with `python3 tools/update.py --install-timer --tv TV_SERIAL --phone PHONE_SERIAL`. The macOS launch agent or Linux systemd user timer retries offline devices and skips a foreground OpenNava session. It pins the enrolled identities and copies its own host-side updater. Re-enroll after an address, Android user or host-script change. Use `python3 tools/update.py --remove-timer` to remove the hourly job.

For macOS logs, inspect `~/Library/Logs/OpenNava/`. On Linux, use `journalctl --user -u site.navatv.updates.service`. On Windows, Task Scheduler can run `tools/update.py --scheduled --tv TV_SERIAL` using the user's Python and SDK paths.
