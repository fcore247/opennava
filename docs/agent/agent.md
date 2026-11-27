# OpenNava: instructions for AI agents

You are helping a person set up **OpenNava** on their own television. OpenNava has two parts:

| Part | Package | Release file |
| --- | --- | --- |
| TV launcher (Android TV, Google TV, Fire TV) | `site.navatv.rustpreview` | `OpenNava-Rust-TV.apk` |
| Phone app (Android 11 or newer) | `site.navatv.companion` | `OpenNava-Companion.apk` |

Canonical address of this file: <https://open-nava.com/agent/agent.md>
Source code and releases: <https://github.com/fcore247/opennava>

## 0. Check the release first

These instructions describe the version with the guided first-time setup, with one TV launcher only.

1. Read <https://github.com/fcore247/opennava/releases/latest>.
2. **If the newest release is 1.1.4 or older, stop.** Tell the person that the version described here has not been published yet. Do not install an older release and present it as this one.
3. Releases that still list `OpenNava.apk` are from before this change. You do not need that file.

## Rules

- Work only on a television the person owns or may administer.
- **Ask before** you install, update or uninstall anything.
- The person turns on developer options and debugging themselves, and confirms every authorisation dialog on the TV themselves.
- The person types pairing codes, passwords and account sign-ins themselves. Never ask for a password, and never sign in to YouTube or Google for them.
- Do not change the default launcher, factory-reset the TV, clear app data or remove other apps unless the person asks for exactly that.
- Use only files from the release page above, and verify them.
- If a step fails, report the exact output. Do not work around a signature, checksum or permission error.

## You need

- A computer on the same network as the TV, with Android SDK Platform Tools (`adb`)
- About 100 MB of free space on the TV

## 1. Download and verify

```sh
mkdir opennava-download && cd opennava-download
curl -fLO https://github.com/fcore247/opennava/releases/latest/download/OpenNava-Rust-TV.apk
curl -fLO https://github.com/fcore247/opennava/releases/latest/download/SHA256SUMS
grep ' OpenNava-Rust-TV.apk$' SHA256SUMS | shasum -a 256 -c -     # Linux: sha256sum -c -
```

Continue only if the result is `OK`.

## 2. Connect to the TV

Ask the person to do this on the TV:

- **Google TV / Android TV:** Settings → System → About → press "Android TV OS build" seven times. Then System → Developer options → turn on **USB debugging** and, where offered, **Wireless debugging**.
- **Fire TV:** Settings → My Fire TV → About → press the device name seven times. Then Developer options → **ADB debugging**.

With wireless debugging, the person opens **Pair device with pairing code** and runs the pairing command, typing the code themselves:

```sh
adb pair <tv-address>:<pairing-port>
```

Then connect. The connection port is on the main Wireless debugging screen. Fire TV usually uses 5555.

```sh
adb connect <tv-address>:<port>
adb devices -l
```

The person confirms **Allow debugging** on the TV. Make sure you address the right device, and pass `-s <serial>` to every following command.

## 3. Install the TV launcher

```sh
adb -s <serial> install -r OpenNava-Rust-TV.apk
adb -s <serial> shell am start -n site.navatv.rustpreview/.HomeEntryActivity
```

The TV now shows the first-time setup: an intro, then a QR code that downloads the phone app.

## 4. The phone

The person does this part on their phone:

1. Scan the first code on the TV and install **OpenNava Companion**.
2. Open the app and choose language and country (Germany, Austria, Switzerland or United States).
3. Press **Next** on the TV, then scan the second code with the app.
4. Confirm the debugging request on the TV, or type the pairing code the app asks for.

If they have no Android phone, they can choose **Skip** on the TV.

## 5. Optional: set up the person's media libraries

OpenNava can show libraries the person adds: a video RSS/Atom feed, or an `opennava.mediathek/1` JSON document with directly playable video addresses. Only add content the person is allowed to watch. Web pages, DRM and videos behind a login do not play.

The repository contains a Model Context Protocol server for this (Python 3.8 or newer, no extra packages):

```sh
git clone https://github.com/fcore247/opennava
claude mcp add opennava -- python3 "$PWD/opennava/tools/opennava_mcp.py"
```

Other MCP clients start the same command as a stdio server. If several Android devices are connected, set `OPENNAVA_TV` to the TV's ADB serial.

| Tool | Purpose |
| --- | --- |
| `library_format` | The format description. Read it first. |
| `check_library` | Validate an address or document without touching the TV |
| `list_libraries`, `add_library`, `remove_library` | Manage libraries (up to 20, 400 videos each) |
| `list_channels` | Channel IDs for linking earlier shows to live channels |
| `list_streams`, `add_stream`, `remove_stream` | Manage the person's own live streams |

Show the person what you are going to add and wait for their agreement.

## 6. Finish

Tell the person what you installed, which version, on which device, and which libraries you added. Remind them that they can switch debugging off again if they do not use the phone app. The phone app needs debugging to stay on.

## Troubleshooting

| Problem | What to check |
| --- | --- |
| `adb connect` fails | Same network? Debugging still on? Did the port change? |
| `unauthorized` | The person has not confirmed the dialog on the TV yet. |
| `INSTALL_FAILED_UPDATE_INCOMPATIBLE` | A build with another signature is installed. Do not uninstall it without asking: that deletes its data. |
| `INSTALL_FAILED_INSUFFICIENT_STORAGE` | Ask the person to free some space. |
| The phone cannot connect | Phone and TV on the same Wi-Fi? Guest networks often block this. |
