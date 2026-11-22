# OpenNava Rust preview

A separate native TV app for Android TV, Google TV and Fire TV with Fire OS, on Android API 26 or later. The public APK is `OpenNava-Rust-TV.apk` (`site.navatv.rustpreview`) and supports 32-bit and 64-bit ARM.

## Try it and return

In classic OpenNava, open **Software updates** and select the Rust preview. Android asks for confirmation when installing it for the first time. In the classic full profile on OpenNava 1.1.1 or later, choose **Open Rust preview** for a single session or **Use Rust as my launcher** to remember the switch. You can also open the installed preview from the TV's Apps screen.

Choose **Einstellungen → Zur klassischen Ansicht** to return. This action is also available from the exit screen when pressing Back on the preview home. A preview launched by classic OpenNava returns to the same TV session and profile. From a standalone launch it opens the official classic launcher, including older versions. **Vorschau schließen** closes the preview. Its watchlist and preferences are stored in its own application directory. Android's Home setting remains independent of both launch paths.

## Included

- Remote and touch navigation, visible focus, search keyboard and a local watchlist.
- Open **Einstellungen → Personalisierung** for the Android companion download. On the paired phone, pin installed TV apps, group them in folders and choose a row or grid. The TV updates its home while it is open.
- ARD, ZDF and ARTE browsing, search, episode details and native in-app playback, subject to provider availability.
- YouTube videos open in the native player with a single press. Search from the YouTube screen or the launcher's main search. **Alle** includes videos alongside broadcaster results, and **YouTube** narrows the search. Move right from the last visible result to keep browsing. Back from playback returns to the selected video.
- Pair your account under **Einstellungen → YouTube-Konto verwalten** or **YouTube → YouTube-Konto**. Scan the QR with a phone and approve the connection on Google's screen. Both search entry points and the player share the account on this TV. Guests can browse selected public channels. Sign out from either account entry.
- Live TV plays inside the preview. Select **Live-TV** on the home screen to watch and change channels with the remote. The player’s compact controls hide while watching. External TV apps remain available under **Apps → Weitere TV-Apps**.

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

The packager checks both native Rust libraries byte for byte, player DEX, native activity declarations, licenses, signature and ZIP alignment. It builds the player libraries from the repository's pinned upstream revisions with its preview-specific TV OAuth scope. Rust-only tests exercise D-pad focus, native catalogue navigation, return and exit actions, watchlist persistence and rendering bounds. See [asset provenance and dependency notices](ASSETS.md).
