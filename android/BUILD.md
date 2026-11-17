# Build OpenNava

Use Linux or macOS with Python 3.9 or newer, Git, curl, JDK 17, Android SDK platforms 34 and 36, and build-tools 36.0.0. Set JAVA_HOME and ANDROID_HOME to those installations. The first build downloads the pinned player source, the two Gradle distributions and Maven dependencies. Gradle 7.5 builds the pinned upstream libraries against Android 34. Gradle 8.13 builds the launcher and companion against Android 36.

Generate your own key once, from a directory outside the checkout. Use the same password for the keystore and private key. Keep the key and the password outside version control.

```sh
keytool -genkeypair -alias navatv -keyalg RSA -keysize 3072 -validity 10000 -storetype PKCS12 -keystore /your/private/navatv.p12
```

Save the password in a file readable only by its owner. From the repository root, run this command.

```sh
python3 android/build.py --keystore /your/private/navatv.p12 --password-file /your/private/password --output /your/output
```

Both modes run from `OpenNava.apk`. `OpenNava-Companion.apk` includes the signed TV package for transfer to an authorized television. The build validates APK signing and alignment and verifies that the embedded binary matches the TV release. Installation and profile selection are documented in the root project.

The optional [Fire Keys helper](fire-keys/README.md) can be built independently with `--only-fire-keys` and the launcher's signing key.

Each build verifies the pinned Git revisions and downloaded Gradle distribution checksums. Source checkouts are cached under `~/.cache/navatv`. To use a different cache, set `NAVATV_CACHE`. An existing clean copy of the exact player revisions can be supplied through `NAVATV_PLAYER_SOURCE`. `NAVATV_GRADLE_7_5` and `NAVATV_GRADLE_8_13` accept paths to locally installed matching Gradle executables. If you use separate Gradle caches, set `GRADLE_USER_HOME` for the project build and `NAVATV_LEGACY_GRADLE_HOME` for the player.

The public configuration starts in the full launcher. The installer can select the senior view, and the choice persists on the device. The senior scenery follows the time of day. To enable weather for a custom build, add an `android/tv/src/main/assets/assets/wetterort.json` file with a location name and numeric latitude and longitude.
