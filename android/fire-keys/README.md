# OpenNava Fire Keys

This optional helper recognizes two remote shortcuts on supported Android-based Fire TV firmware. Double-pressing Home opens the programme guide in senior view when OpenNava's Home redirection is active. Pressing the Netflix shortcut selects the senior home. Opening Netflix from its normal app tile still launches Netflix. The on-screen guide and the live player's **hold Up** shortcut work independently of this helper.

The native Rust launcher from version 0.2.5 can bind to the dedicated Home service in Fire Keys 1.0.1 or later. With its Home redirection enabled, one press opens OpenNava and two quick presses open Amazon Home. This service sends Home signals only to the same-signer native launcher.

## Set it up

1. Install the [Rust launcher](https://github.com/fcore247/opennava/releases/latest/download/OpenNava-Rust-TV.apk) or the [classic and senior launcher](https://github.com/fcore247/opennava/releases/latest/download/OpenNava.apk) and pair your own computer with the Fire TV. Check its serial and model with `adb devices -l`.
2. Download [OpenNava-Fire-Keys.apk](https://github.com/fcore247/opennava/releases/download/tools-v1.0.1/OpenNava-Fire-Keys.apk). Substitute the exact serial for `FIRE_TV_SERIAL` below. Android installs a service, so this package has no app tile.

   ```sh
   adb -s FIRE_TV_SERIAL install -r OpenNava-Fire-Keys.apk
   adb -s FIRE_TV_SERIAL shell pm grant site.navatv.keys android.permission.READ_LOGS
   ```

3. In the TV's accessibility settings, enable **OpenNava als Fire-TV-Startseite** for Rust, or **OpenNava Home button** for the classic launcher if your firmware offers it. OpenNava binds to the helper when its signature and permission match. Try the actual remote while using senior view. Fire OS versions and remotes differ in which button events they expose.

**What the permission enables.** Android's `READ_LOGS` grant can read system logs. The helper opens only the system buffer with filters for `ActivityTaskManager` and `ContextImpl`. It checks recent events in memory and sends the recognized button and timestamp to the matching launcher. Its manifest requests no internet or storage permissions. [Service implementation](src/main/java/site/navatv/keys/KeySignalService.java) · [Launcher receiver](../tv/src/main/java/site/navatv/launcher/FireButtonConnection.java)

To remove the helper and its grant:

```sh
adb -s FIRE_TV_SERIAL uninstall site.navatv.keys
```

## Build and test

From the repository root, use JDK 17, Python, Android SDK platform 36 and Build Tools 36.0.0. Set `JAVA_HOME` and `ANDROID_HOME` as in the [Android build guide](../BUILD.md). Use the same private signing key as your own launcher build. Android enforces a signature-level permission on the service and the broadcast.

```sh
python3 android/build.py --only-fire-keys --keystore /your/private/navatv.p12 \
  --password-file /your/private/password --output ../opennava-tools
```

The parser can also be tested on a computer with just the JDK. From this directory:

```sh
classes=$(mktemp -d)
javac -d "$classes" src/main/java/site/navatv/keys/FireKeyEvent.java tests/FireKeyEventCheck.java
java -cp "$classes" site.navatv.keys.FireKeyEventCheck
```
