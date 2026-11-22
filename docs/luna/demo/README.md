# Luna Browser embedded demo

The demo runs directly from the static website at `luna/demo/`. The browser window, tab management, page-scoped notes and the address command dispatcher live in `demo.js`. OpenNava supplies the translations. The same-origin parent can change the display language through the `luna-demo:language` message. Standalone links support the site's `?lang=` preference.

## Source provenance

The source revision is [Luna Browser b3c83f4](https://github.com/fcore247/luna-browser/tree/b3c83f495a4eb798af5cb5b58091e681cf7dec31/frontend/src). The repository's Mac frontend is the source of the following browser assets. In a checkout rooted inside the Mac project, these paths begin with `frontend/src/`.

| Embedded file | Original path beneath frontend/src | Use |
| --- | --- | --- |
| vendor/branding.js | branding.js | Hand-drawn start-page wordmark |
| vendor/color-themes.js | color-themes.js | Eleven palettes, appearance variables, palette controls and recolored Luna icon |
| vendor/start-background.js | start-background.js | Animated canvas waves, reduced-motion static canvas |
| vendor/flipclock.js | flipclock.js | Native time-query matching and the ticking 31 × 11 display |
| vendor/flipclock.css | styles/flipclock.css | Flip-dot display styling |
| vendor/gesture-core.js | gesture-core.js | Mouse input, drawn trails and letter recognition |

These six files are copied byte for byte. The ES-module import map in `index.html` points their desktop imports to the following browser adapters.

- `adapters/icon.js` exports the unchanged contents of `assets/luna-icon.svg` as a string, matching Vite's SVG raw import.
- `adapters/personalization.js` supplies the wave motif and its normal playback speed. It observes viewport and document visibility to pause the original renderer.
- `adapters/i18n.js` connects the clock and presentation labels to the current OpenNava language. The palette names are relabeled whenever the site changes language.
- `adapters/api.js` implements the two desktop palette and Dock notifications as empty functions. The palette renderer still updates the window and its start-page icon locally.

`vendor/luna-base.css` contains selected original CSS rules. In source order they are `styles/foundation.css` lines 1–56, `styles/start-page.css` lines 11–96 and 577–583, `styles/browser-enhancements.css` lines 253–350 and `styles/luna-glass.css` lines 242–252. `demo.css` styles the surrounding web controls. The home greeting follows the time-of-day thresholds from Luna's `main.js`.

## Interactions

- Type `theme` or `farben` to open the palette chooser. The native palette preference uses its original `luna-color-theme` storage key. The display mode uses the demo's `lunaWebsiteDemo.mode` key.
- Submit `time`, `uhr`, or one of the other original time queries to open the ticking display.
- Open `gestures` or `gesten` and draw a circle with the primary mouse button. Luna's recognizer identifies the letter and opens the note for the underlying page. The note button provides direct access on touch or keyboard.
- Notes are kept under `lunaWebsiteDemo.note.` for their page, shared across tabs and reloads. Tab navigation is in memory. Entering a web address shows a link to open it in a separate browser tab.
