# Browser HLS playback

The website loads the full hls.js 1.7.4 distribution on demand in browsers that require Media Source Extensions to play a broadcaster's HLS stream. Safari can use its native HLS player. The JavaScript is copied byte-for-byte from `dist/hls.min.js` in the published npm package. The upstream license is included beside it.

| File | SHA-256 |
| --- | --- |
| `hls-1.7.4.min.js` | `cfc70cc9e9fc63d071919e087670abc760c8fd1bc889b88588026e4d22193bbe` |

For upgrades, inspect the release, obtain the official npm tarball with `npm pack hls.js@VERSION` and copy both the distribution and its license. Update the pinned filename in `live-streams.js` and test a German public-service channel in a browser with MSE and a browser with native HLS.

[Upstream hls.js](https://github.com/video-dev/hls.js) · [npm package](https://www.npmjs.com/package/hls.js/v/1.7.4) · [License](hls-LICENSE.txt)
