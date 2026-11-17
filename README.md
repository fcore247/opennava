# OpenNava

[NavaOS](https://navaos.site/os/) inspired me to build OpenNava.

OpenNava gives an Android-based television two home screens. The **full launcher** puts apps and media together. The **senior view** brings live TV, broadcaster libraries, the programme guide and recordings to the front. The Android companion lets you browse on a phone and send a selection to the television.

**[Choose your home screen](https://opennava.site/)** · **[Download the TV app](https://github.com/fcore247/opennava/releases/latest/download/OpenNava.apk)** · **[Download the Android companion](https://github.com/fcore247/opennava/releases/latest/download/OpenNava-Companion.apk)**

## Set it up with your AI agent

Give one of these requests to a coding agent with terminal access to your computer. Your agent can guide you through the steps that need a button press on the TV.

- **Full launcher** — “Set up OpenNava's full launcher on my TV using [the setup instructions](https://github.com/fcore247/opennava/blob/main/AGENTS.md#full-launcher). Use profile `full`.”
- **Senior view** — “Set up OpenNava's senior view on my TV using [the setup instructions](https://github.com/fcore247/opennava/blob/main/AGENTS.md#senior-view). Use profile `senior`.”

Add “Install the companion on my Android phone” to include a phone. Add “Make OpenNava my Home app” to request the Android Home role. The selected profile can be changed later by running setup again with the other profile.

## Connect your devices

You need Python 3.8 or newer and Android SDK Platform Tools (`adb`) on the computer. The TV app targets Android-based Google TV, Android TV and Fire TV devices running Android API 26 or newer. The companion requires Android 11 or newer. Fire TV devices that run Vega OS use a different app platform. [Download Google's Platform Tools](https://developer.android.com/tools/releases/platform-tools)

1. **Google TV / Android TV.** In Settings → System → About, press the Android TV OS build entry seven times to enable developer options. Under Developer options, open Wireless debugging if the device offers it. Choose **Pair device with pairing code**. On the computer run `adb pair HOST:PAIRING_PORT`, then enter the code at the prompt. Back on the main Wireless debugging screen, read its **IP address & port** and run `adb connect HOST:CONNECTION_PORT`. The two ports can differ. [Google's ADB pairing guide](https://developer.android.com/tools/adb#wireless)
2. **Android-based Fire TV.** In Settings → My Fire TV → Developer Options, enable ADB Debugging. If developer options are hidden, select the device name under About seven times. Find its address in About → Network, then run `adb connect HOST:5555`. Approve the computer on the TV. [Amazon's Fire TV connection guide](https://developer.amazon.com/docs/fire-tv/connecting-adb-to-device.html)
3. **Optional Android phone.** Enable developer options and USB debugging, connect the phone by USB, unlock it and accept this computer's debugging prompt. You can also use Android's wireless pairing procedure. [Android developer options](https://developer.android.com/studio/debug/dev-options)

Start on the same local network. An already configured private VPN such as Tailscale can also carry a reachable TV connection. Keep ADB on private addresses and leave the pairing code on the device or in the local terminal prompt.

## Install from the terminal

Clone the repository and inspect the connected devices.

```sh
git clone https://github.com/fcore247/opennava.git
cd opennava
python3 tools/setup.py --list
```

Choose **one** profile. Replace the uppercase placeholders with exact serials from the list, including the port when shown.

```sh
python3 tools/setup.py --profile full --tv TV_SERIAL
```

```sh
python3 tools/setup.py --profile senior --tv TV_SERIAL --phone PHONE_SERIAL
```

If there is exactly one eligible TV, you may omit `--tv`. To install just the phone app, use `python3 tools/setup.py --phone-only --phone PHONE_SERIAL`. Add `--dry-run` to preview. For a download you already have, put the official APKs together in a directory and pass `--assets-dir DIRECTORY`.

Setup verifies the chosen release's APK SHA-256 against [release.json](release.json), installs for the active Android user and checks the installed version. It asks the TV to persist the requested profile and reads the setting back. It keeps existing OpenNava data when updating. If Android reports a different signing certificate, stop and inspect the installed app before choosing how to handle its data.

**Default Home.** The commands above open OpenNava. To request the Android Home role, add `--set-home` to the TV setup command. The installer reports the previous Home component and checks Android's resolved Home activity. Press Home once to test the actual remote. Some vendor firmware reserves the Home button; where that happens, launch OpenNava under Apps.

**Go back.** Select the previous launcher under the device's default Home app settings, or run the restore command printed by `--set-home`. Open Apps to launch OpenNava again. To remove it, use Android Settings → Apps → OpenNava → Uninstall after restoring the previous Home app. Android removes OpenNava's local data when uninstalling.

**Pair the phone remote.** Open OpenNava's companion connection screen on the TV and scan its QR code from the companion's connection menu, or enter the private TV address manually. The phone establishes its own ADB identity. Complete its pairing or authorization prompt on the TV, even if this computer is already authorized.

## Updates

Android handles updates in place using the existing OpenNava signing identity. The [release page](https://github.com/fcore247/opennava/releases/latest) includes `SHA256SUMS` and the machine-readable `update.json`.

### Update on the TV or phone

Open **Software updates** inside OpenNava or the companion to check for a release. Automatic checks are enabled by default. Android schedules them when the device is idle and connected. Each app verifies the release and asks Android to install it. Depending on the Android version and how the app was first installed, you may need to allow installs from this source or confirm the Android installation prompt. Turn off **Automatic updates** on that screen to opt out on that device.

### Update from your computer

For unattended updates, install Android SDK **Build Tools** (for `apksigner` and `aapt`) and a Java runtime on the computer that has ADB access. Set `ANDROID_HOME` to the SDK or add its build-tools and platform-tools directories to `PATH`. [Android SDK Manager](https://developer.android.com/tools/sdkmanager)

```sh
python3 tools/update.py --tv TV_SERIAL --phone PHONE_SERIAL --check
python3 tools/update.py --tv TV_SERIAL --phone PHONE_SERIAL
```

Each target is optional. The updater only touches an already installed package when the official release has a higher version code. Before installing, it checks the APK hash, actual package and version, and the verified APK signing certificate against the [pinned OpenNava fingerprint](release-key.sha256). An update keeps the selected profile, settings and recordings.

### Hourly updates

On macOS or a Linux desktop with a systemd user manager, run this once while the intended devices are connected.

```sh
python3 tools/update.py --install-timer --tv TV_SERIAL --phone PHONE_SERIAL
```

The per-user job stores a local copy of its updater and enrolled Android identities. It checks hourly while that user and computer can run the job, reconnects enrolled network addresses and tries again later for offline devices or apps in use. Removing and re-enrolling an Android user, or changing the TV's address or port, requires running the enrollment command again. Re-run it from a newer checkout to refresh the updater itself. Remove the job with `python3 tools/update.py --remove-timer`.

On macOS, logs are in `~/Library/Logs/OpenNava/`. On Linux, use `journalctl --user -u site.navatv.updates.service`. On Windows, Task Scheduler can periodically run your Python executable with the full path to `tools/update.py --scheduled --tv TV_SERIAL`. Keep that checkout available and set the SDK tools on the scheduled task's account.

## Build from source

The Android TV and phone sources live under [android](android). See the [build guide](android/BUILD.md) and [third-party notices](android/NOTICE.md).

Maintainers build both signed APKs with matching version names, then run the following commands from the checkout. Use the official OpenNava release signing key and the same version in both invocations. Review and commit the generated `release.json`, then push main **between** these steps.

```sh
python3 tools/publish.py prepare --version 1.0.0 --assets-dir /path/to/signed-apks
python3 tools/publish.py publish --version 1.0.0 --assets-dir /path/to/signed-apks
```

The publisher checks package identities, version names, signatures, the companion's embedded TV APK and the uploaded release hashes. Only after all four files are verified does it make the draft public. The update clients discover the new stable release on their next check. Increase each app's version code for every update intended to replace an installed build.
