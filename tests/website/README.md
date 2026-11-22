# TV launcher browser tests

These Playwright tests exercise the website, the paired Luna demo and the full and senior TV demos in Chromium. They require Node.js 20 or newer. Run them from this directory:

```sh
npm ci
npx playwright install chromium
npm test
```

The default target is <https://opennava.site/>. To use a local checkout, start `python3 -m http.server 8000 --directory docs` at the repository root. Then run `OPENNAVA_URL=http://127.0.0.1:8000/ npm test` from this directory.

`launcher.spec.cjs` covers keyboard and touch navigation, switching profiles, on-demand sample playback, and the TV keyboard. `multipage.spec.cjs` covers desktop and mobile menu navigation, the product downloads, the setup profile and language selection across pages. `live.spec.cjs` covers station changes, the guide, favourite channels, reminders, remote gestures, pause, stream-limited seeking, browser recordings, and leaving Live TV. It also checks what the visitor can do when a broadcaster is unavailable.

The phone download tests cover matching Android and TV preview assets, the row/grid sample, language selection and the original Rust download URL.

The deterministic Live TV tests intercept broadcaster metadata, programme schedules and HLS responses. They exercise the production guide parser, player, decoder, seekable video window and recording controls. The fixture is original, silent, solid-colour H.264 footage in fragmented MP4. `fixtures/live/media.cjs` serves changing playlists and byte-range responses. No TV or Android device is needed.

To check the real public broadcaster, run the optional smoke test with a working internet connection in a supported viewing region:

```sh
OPENNAVA_LIVE_SMOKE=1 npm test -- -g 'unmodified public broadcaster'
```

This test makes real network requests and requires the browser to decode video frames from the live stream. The bundled on-demand sample cannot satisfy it.
