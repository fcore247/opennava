# How OpenNava fits together

One TV installation contains both home screens. Android starts `site.navatv.launcher.MainActivity` for the Home intent. It reads the profile choice and either presents the senior home or starts the full launcher inside the same package. Switching profiles keeps the installation, media library and recordings on the TV. [TV manifest](../android/tv/src/main/AndroidManifest.xml) · [Profile routing](../android/tv/src/main/java/site/navatv/launcher/VisitorMode.java)

## Follow an action through the TV

| When a viewer… | Where to start reading |
| --- | --- |
| Opens the full home or searches YouTube | The [full launcher assets](../android/tv/src/main/assets/web/) and [Google TV overrides](../android/tv/src/main/assets/google/) drive the UI. Its [Android bridge](../android/tv/src/main/java/site/navatv/full/MainActivity.java) handles platform operations. [NavaYouTube](../android/tv/src/main/java/site/navatv/full/NavaYouTube.java) and the [full player](../android/tv/src/main/java/site/navatv/full/NavaPlayerActivity.java) use the pinned player libraries. |
| Chooses a library in senior view | [Senior home](../android/tv/src/main/assets/index.html) calls the [senior bridge](../android/tv/src/main/java/site/navatv/launcher/MainActivity.java). [MediathekService](../android/tv/src/main/java/site/navatv/launcher/MediathekService.java) and [ArteCatalog](../android/tv/src/main/java/site/navatv/launcher/ArteCatalog.java) resolve broadcaster catalogues and media. [PlayerActivity](../android/tv/src/main/java/site/navatv/launcher/PlayerActivity.java) presents on-demand and recorded video. |
| Tunes a station or pauses live TV | [LiveGuide](../android/tv/src/main/java/site/navatv/launcher/LiveGuide.java) holds station identities and resolves schedules, replay references and current stream addresses. [LiveTvActivity](../android/tv/src/main/java/site/navatv/launcher/LiveTvActivity.java) handles tuning, playback and seeking within the available live window. The [player controls](../android/tv/src/main/java/site/navatv/launcher/PlayerChrome.java) supply the title, clock, animated mist and action strip. |
| Saves a programme | [Recordings](../android/tv/src/main/java/site/navatv/launcher/Recordings.java) owns the library. An available broadcaster replay can satisfy a saved entry. [RecordingService](../android/tv/src/main/java/site/navatv/launcher/RecordingService.java) handles a local live capture, while [ProgrammeReminders](../android/tv/src/main/java/site/navatv/launcher/ProgrammeReminders.java) tracks future broadcasts. |

The station catalogue in the public distribution covers ARD, ZDF and ARTE, including regional services. Publisher programme data, regional restrictions, time-shift windows and available archives can change independently of an OpenNava release. The full launcher builds its SmartTube-based playback libraries from [exact upstream source revisions](../android/player/source-pins.json). The [Android notices](../android/NOTICE.md) identify the artwork, fonts, libraries and corresponding source locations.

## Control a real television

**Android phone.** The [companion](../android/companion/src/main/java/site/navatv/companion/) uses its own ADB identity. On a reachable private network, a QR invitation or manual address identifies the TV, then Android authorizes the connection. The TV's [CompanionProvider](../android/tv/src/main/java/site/navatv/launcher/CompanionProvider.java) checks the caller's Android permission and exposes the active screen, live controls, recordings and reminders. [PhoneTvContextController](../android/companion/src/main/java/site/navatv/companion/PhoneTvContextController.java) follows changes on the TV. The [companion build](../android/companion/build.gradle) reuses the TV's catalogue code and packages a copy of the signed TV APK for installation from the phone.

**Mac.** [OpenNava Studio](../macos/README.md) lists authorized ADB devices, sends remote actions and uses scrcpy for interactive mirroring. The simple remote and the mirror use the selected device's serial. The [Swift sources](../macos/Sources/) and app bundle builder live together.

**Fire TV shortcuts.** The optional [Fire Keys service](../android/fire-keys/README.md) recognizes certain vendor-reserved remote shortcuts. The launcher and helper use a signature-level permission. The user grants system-log access to the helper separately on their selected Fire TV.

## Browser preview

GitHub Pages serves the [website](../docs/index.html). The [TV host](../docs/demo-host/television.js) scales an embedded launcher, switches profiles and supports fullscreen. The two launchers under [docs/demo](../docs/demo/) are staged from Android assets by [sync_web_demo.py](../tools/sync_web_demo.py). Edit the Android source for changes shared with the television and run the staging script. Browser-specific video, device actions and sample catalogue data belong in [the demo host](../docs/demo-host/).

The [browser tests](../tests/website/) exercise the interactive launcher. The [legacy redirect](../hosting/legacy-redirect/README.md) sends old links to the canonical site.

For live television, the staging script carries the Android station IDs into the browser. [The stream adapter](../docs/demo-host/live-streams.js) resolves HLS sources and schedules from the broadcasters. [The browser player](../docs/demo-host/live-tv.js) binds the remote keys, displays the programme guide and keeps browser recordings local. The on-demand catalogue preview uses bundled sample media.

## Installation and release boundaries

| Artifact | Produced by | Consumed by |
| --- | --- | --- |
| `OpenNava.apk` and `OpenNava-Companion.apk` | [Android builder](../android/build.py) | Android installer, desktop setup, phone-to-TV install |
| [release.json](../release.json) and the published `update.json` | [Release publisher](../tools/publish.py) | First-time setup reads its checkout's manifest. The shared [native updater](../android/updates/) and [host updater](../tools/update.py) read the stable release feed. |
| `OpenNava-Studio-macOS.zip` and `OpenNava-Fire-Keys.apk` | [Studio builder](../macos/build.py) and the Android builder's `--only-fire-keys` option | Separate, optional downloads in the [tools release](https://github.com/fcore247/opennava/releases/tag/tools-v1.0.1). |

The Android identifiers `site.navatv.launcher`, `site.navatv.companion` and `site.navatv.keys` are part of device and update compatibility. The project records the official certificate's [public SHA-256 fingerprint](../release-key.sha256). Private signing material, ADB identities, paired addresses and each household's recordings belong on their owners' devices. [Build instructions](../android/BUILD.md) · [Release process](MAINTAINING.md)
