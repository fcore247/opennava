# OpenNava Studio for Mac

OpenNava Studio puts a connected Android television in a window on your Mac. Use the direction pad or keyboard, see what is on the TV, or open an interactive scrcpy mirror. Studio can control a TV before OpenNava is installed.

**[Download OpenNava Studio for macOS](https://github.com/fcore247/opennava/releases/download/tools-v1.0.0/OpenNava-Studio-macOS.zip)**

## Open Studio

1. Use a Mac running macOS Ventura 13 or newer. The download contains an Intel and an Apple Silicon executable in the same application.
2. Install **scrcpy** and **Android Platform Tools**. If you use Homebrew, run the commands below. Existing installations work too. [scrcpy for macOS](https://github.com/Genymobile/scrcpy/blob/master/doc/macos.md)
3. Unzip the download, move **OpenNava Studio** to Applications and open it. The release is signed locally with an ad-hoc signature. macOS may ask for confirmation under System Settings → Privacy & Security → Open Anyway.
4. Click **Connect a TV**. Approve Local Network access if macOS asks.

```sh
brew install scrcpy
brew install --cask android-platform-tools
```

## Connect your television

| Television | In Studio |
| --- | --- |
| Google TV or Android TV with wireless pairing | In Developer options → Wireless debugging, choose **Pair device with pairing code**. Select **Pair with a code** in Studio, enter the address with the pairing port and the six-digit code. Return to the main Wireless debugging screen and use its **connection port** under **Connect** if the TV does not appear automatically. |
| Android-based Fire TV | Enable **ADB debugging** in the TV’s Developer options. Under **Connect**, enter the TV’s IP address. Studio uses port 5555 when you omit the port. Approve the prompt on the TV. |
| TV in your Tailscale network | Connect both devices to your tailnet. Choose **Tailscale**, enter the TV’s Tailscale IP or MagicDNS name and its current debugging port. Use **Pair this Mac with Google TV** for a first-time pairing. The TV must be reachable on that port. |
| TV already authorized by ADB | Open Studio, or choose **Refresh devices**. Use the device menu to select the intended television. |

Pairing codes are temporary. Studio keeps your last selected device identifier and a successfully used Tailscale endpoint in its local preferences. Android Platform Tools manages its own host keys and device authorizations. [Android wireless debugging](https://developer.android.com/tools/adb#connect-to-a-device-over-wi-fi)

## Control the screen

| Action | Control |
| --- | --- |
| Move the TV focus | Direction pad or arrow keys |
| Select the highlighted item | **OK** or Return |
| Go back | **Back** or Escape |
| Show the TV home screen | **Home** |
| Pause or resume playback | **Play or pause** |
| Open or close the live mirror | **Open live view** or ⌘L to open |
| Refresh the still preview | Circular arrow above the image |
| Connect or refresh devices | ⌘K or ⌘R |

The live mirror opens in a separate scrcpy window. Audio continues on the television. Some streaming apps protect the video region from screen capture.

## Install the TV launcher from Studio

The downloadable Studio app includes the official TV APK for its release. Select the television, choose **Install OpenNava** or **Update OpenNava**, then confirm the device name. Studio checks the selected television again, installs the APK, verifies the new version and opens it. Existing installations keep their settings and profile. To select a specific profile or request the Android Home role, follow the [main setup guide](../AGENTS.md).

If the installed version is newer or already matches the embedded APK, Studio shows the version. The [main releases](https://github.com/fcore247/opennava/releases) provide newer TV builds independently of Studio.

## Build from source

Install the Xcode Command Line Tools and Python 3.8 or newer. From the repository root, build and open a remote-only application using the local macOS SDK.

```sh
python3 macos/build.py
open "macos/build/OpenNava Studio.app"
```

To include the TV installer, install Android SDK Build Tools with `aapt` and `apksigner` and a compatible Java runtime. Set `ANDROID_HOME` and `JAVA_HOME` as described in the [Android build guide](../android/BUILD.md). The builder downloads the official APK pinned by this checkout, checks its SHA-256 against the release manifest and verifies the Android package, version code and signing certificate before embedding it.

```sh
python3 macos/build.py --include-launcher --zip
```

To use a previously downloaded official TV APK, pass `--assets-dir /path/to/release-assets`. Use `--output /path/to/output` to change the destination. The build compiles both macOS architectures, combines them with `lipo`, and signs and verifies the finished app. The default output directory is `macos/build/`.

Studio looks for the tools on your PATH, in the standard Apple Silicon and Intel Homebrew directories, and in the Android SDK. To use custom executables, launch the application binary from a terminal with `OPENNAVA_ADB` or `OPENNAVA_SCRCPY` set to an absolute executable path. The tools keep their own licenses and installations.
