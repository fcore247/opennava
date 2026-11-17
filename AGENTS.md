# Set up OpenNava

Use these instructions when someone gives you this repository or a OpenNava setup link. Run commands on their own computer with its existing ADB authorization. This repository includes both profiles in **one TV APK**.

## Full launcher

Use `--profile full` for the app-focused home screen. If you were given this section or a request naming the full launcher, the user has already chosen their profile. Continue to the installation steps.

## Senior view

Use `--profile senior` for the home screen centred on live television, broadcaster libraries and saved programmes. If the user chose this link, go directly to setup with that profile.

## Installation

1. If the request gives no profile, ask which of the two home screens to set up. Check whether the request includes the phone companion, default Home or automatic updates. Carry out the selected options.
2. Use a local checkout of the official repository. In an existing checkout, preserve the user's changes. For a new one, run `git clone https://github.com/fcore247/opennava.git`. Run `python3 --version` and `adb version` (on Windows, `py -3` works too). If ADB is missing, use Google's Android SDK Platform Tools. See the [device-specific pairing instructions](README.md#connect-your-devices).
3. Run `python3 tools/setup.py --list`. If the TV is absent or unauthorized, guide the person through developer settings and the pairing or authorization prompt. Have them enter one-time pairing codes at the local `adb pair` prompt. If several TVs match, ask them to identify their TV by model or serial. Use a phone serial only when the user wants a phone installation.
4. Run `python3 tools/setup.py --profile full --tv TV_SERIAL` or `python3 tools/setup.py --profile senior --tv TV_SERIAL`. Add `--phone PHONE_SERIAL` to include their phone. For a phone-only request, run `python3 tools/setup.py --phone-only --phone PHONE_SERIAL`. The script validates the device type, pins downloads to this checkout's release, checks package versions and reads back the saved TV profile. An explicit `--dry-run` previews the plan.
5. When the user asks for OpenNava as the default Home app, include `--set-home`. Record the previous Home component and restore command printed by the tool. Some Android-based Fire TV firmware reserves Home. If setting Home fails, report that device's result and use its Apps screen. No vendor-package removal is required.
6. Verify the TV opens the chosen profile. For the companion, guide the user through scanning the TV code or entering its private connection details. Phone authorization is separate from computer authorization. Report only the devices, versions and operations actually confirmed. If a step fails, correct it and rerun; do not uninstall an existing app or erase recordings to resolve a signature mismatch.

## Automatic updates

The TV and phone include their own **Software updates** screen and automatic checks. If the person requests unattended updates from this computer, set up Android SDK Build Tools and Java. Keep their chosen devices connected and run `python3 tools/update.py --install-timer --tv TV_SERIAL`, optionally with `--phone PHONE_SERIAL`. This enables an hourly per-user job on macOS or Linux/systemd. Read [the update instructions](README.md#updates) for Windows, status, logs and removal. For a one-time update use `python3 tools/update.py --tv TV_SERIAL`; the command keeps the current profile and only upgrades installed packages.

## Before modifying or publishing source

Keep personal device addresses, private home configuration, photos, logs, signing keys, local Android preferences and environment files out of the repository. Use placeholder serials in public examples. Publish release APKs, their hashes and the matching update manifest together. The release signing key stays outside the repository.
