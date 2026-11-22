# Rust preview assets and notices

The public preview uses the following source assets from the OpenNava repository.

| Included file | Source |
| --- | --- |
| `assets/{ard,zdf,arte}-cover.jpg` | Identical to the project-generated landscapes in `android/tv/src/main/assets/assets/`. |
| `assets/{ard,zdf,arte}-logo.png` | Rasterized from the public text-only provider marks in `android/tv/src/main/assets/assets/{ard,zdf,arte}-logo.svg`. The station names identify their respective services. |
| `assets/{forest,mountains,ocean}.jpg` | Identical to the corresponding photos in `android/tv/src/main/assets/web/assets/`. Photographer source links and the Unsplash License are listed in [android/NOTICE.md](../android/NOTICE.md). |
| `assets/alpine-lake.webp` | Identical to the OpenNava website cover in `docs/assets/alpine-lake.webp`. |
| Android app icon and TV banner | The project's OpenNava mark from `docs/assets/opennava-mark.svg` and the preview's title. |
| `assets/Manrope.ttf` | Weight 500 instantiated from OpenNava's variable Manrope. The font's upstream SIL Open Font License is in `assets/OFL.txt`. |
| `assets/ReenieBeanie.ttf` | Reenie Beanie by James Grieshaber, distributed with its SIL Open Font License in `assets/OFL-ReenieBeanie.txt`. [Upstream font](https://github.com/google/fonts/tree/main/ofl/reeniebeanie). |

The guide uses Instrument Serif and the senior player’s bundled landscape backgrounds. Its Instrument Serif license is at `assets/licenses/fonts/OFL-InstrumentSerif.txt`. The APK contains the font licenses, the upstream Android player licenses and notices (including corresponding Regex source), and the license texts and inventory of the locked Rust dependencies under `assets/licenses/`. For crates whose published archive omits the workspace license, `notices/` contains the original MIT license and its exact source revision. [Android media library revisions](../android/player/source-pins.json) and [Rust dependency versions and checksums](Cargo.lock) are available in the source tree. Programme and video thumbnails are requested from the respective providers at runtime.
