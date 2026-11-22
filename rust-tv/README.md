# OpenNava Rust preview

A separate native TV app for Android TV, Google TV and Fire TV with Fire OS, on Android API 26 or later. The public APK is `OpenNava-Rust-TV.apk` (`site.navatv.rustpreview`) and supports 32-bit and 64-bit ARM.

## Try it and return

In classic OpenNava, open **Software updates** and select the Rust preview. Android asks for confirmation when installing it for the first time. You can also open the installed preview from the TV's Apps screen.

Choose **Einstellungen → Zur klassischen Ansicht** to return. This action is also available from the exit screen when pressing Back on the preview home. A preview launched by classic OpenNava returns to the same TV session and profile. From a standalone launch it opens the official classic launcher, including older versions. **Vorschau schließen** closes the preview. Its watchlist and preferences are stored in its own application directory. Android's Home setting remains independent of both launch paths.

## Included

- Remote and touch navigation, visible focus, search keyboard and a local watchlist.
- ARD, ZDF and ARTE browsing, search, episode details and native in-app playback, subject to provider availability.
- YouTube uploads from selected public channels and the shared OpenNava in-app player. Catalogue and account-personalized feeds are separate features. The preview displays public uploads.
- Live TV and streaming shortcuts open the corresponding installed apps.

The TV interface and renderer run in Rust with Android's NativeActivity, tiny-skia and fontdue. Android draws the resulting native surface at the TV's display size. The bundled native Android player activities handle playback. The preview currently stores its watchlist on the TV and has no Luna synchronization.

## Build and verify

Install Rust 1.88 or newer, JDK 17, Android SDK platforms 34 and 36, build tools 36.0.0 and NDK 28. Set `ANDROID_HOME`, `ANDROID_NDK_ROOT` and `JAVA_HOME` to their installation directories. Run these commands from `rust-tv/` using a signing keystore and password file stored outside the repository.

```sh
rustup target add armv7-linux-androideabi aarch64-linux-android
cargo test --locked --lib
cargo run --locked --bin snapshot -- /tmp/opennava-rust-previews
python3 tools/package_tv.py --keystore /path/to/keystore.p12 \
  --ks-pass-file /path/to/password-file --ks-key-alias my-key
```

The default output is `target/release/apk/OpenNava-Rust-TV.apk`. Add `--output PATH` to choose another location. Official builds also pass `--expected-signer ../release-key.sha256` to verify the signed certificate against the repository pin. Classic OpenNava accepts preview builds signed with the same release certificate.

The packager checks both native Rust libraries byte for byte, player DEX, native activity declarations, licenses, signature and ZIP alignment. It builds the player libraries from the repository's pinned upstream revisions or uses the existing OpenNava Android build export. Rust-only tests exercise D-pad focus, native catalogue navigation, return and exit actions, watchlist persistence and rendering bounds. See [asset provenance and dependency notices](ASSETS.md).
