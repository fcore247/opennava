# OpenNava Rust preview

A separate native TV app for Android TV, Google TV and Fire TV with Fire OS, on Android API 26 or later. The public APK is `OpenNava-Rust-TV.apk` (`site.navatv.rustpreview`) and supports 32-bit and 64-bit ARM.

## Choose your home screen

Open **Software updates** in Classic to install the Rust preview, or open **OpenNava Rust** from your TV’s Apps screen. With OpenNava 1.1.4 and Rust 0.2.6 installed, all three views offer the same choice:

- **Rust** — **Einstellungen → Startoberfläche wählen**.
- **Classic** — **Quick settings → Choose home screen**.
- **Senior** — **Switch view → Choose home screen**.

Select **Neuer Launcher**, **Classic** or **Seniorenansicht** with the remote. The TV saves the selection and opens it immediately. The Home command in the phone companion also uses this selection, including older companions that open the classic OpenNava app directly. Earlier senior, full-launcher and Rust preferences carry forward. To try Rust for one session, use **Open Rust preview** in Classic. Rust keeps its own watchlist and settings.

### Home on Fire TV

On a supported Fire TV Stick, enable **OpenNava als Fire-TV-Startseite** in the TV’s accessibility settings. A single Home press opens your selected OpenNava view. For the double-press shortcut on the Fire TV Stick 4K Max, install the matching **OpenNava Fire Keys 1.0.1** or later and grant the helper Android’s `READ_LOGS` permission. Two quick Home presses open Amazon Home. Under **Einstellungen → Home-Taste** in Rust, select **Amazon** to use Amazon Home on a single press again.

On Android TV devices that offer a default Home app, choose OpenNava in system settings.

## Included

- Remote and touch navigation, visible focus, search keyboard and a local watchlist.
- Open **Einstellungen → Nach Updates suchen** to check for a new Rust version. When an update is available, a green dot appears beside **Einstellungen** and the entry reads **Update verfügbar**.
- Open **Einstellungen → Personalisierung** for the Android companion download. To connect the installed companion, open **Einstellungen → Handy verbinden** and scan its TV-specific QR code under **Devices → Scan QR code** on the phone. On the paired phone, pin installed TV apps, group them in folders and choose a row or grid. The TV updates its home while it is open.
- ARD, ZDF and ARTE browsing, search, episode details and native in-app playback, subject to provider availability.
- YouTube videos open in the native player with a single press. Search from the YouTube screen or the launcher's main search. **Alle** includes videos alongside broadcaster results, and **YouTube** narrows the search. Move right from the last visible result to keep browsing. Back from playback returns to the selected video.
- Pair your account under **Einstellungen → YouTube-Konto verwalten** or **YouTube → YouTube-Konto**. Scan the QR with a phone and approve the connection on Google's screen. Both search entry points and the player share the account on this TV. Guests can browse selected public channels. Sign out from either account entry.
- Select **Live-TV** to open the senior launcher’s native player. Press Up or Down to change channels, hold Up for the programme guide, or hold Down for the channel list. When the installed classic launcher provides its signed Live TV entry, the player uses its channel preferences, reminders and recordings. Standalone playback stores them with the Rust app. Back returns to the Rust home after closing any open controls. External TV apps remain available under **Apps → Weitere TV-Apps**.

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
