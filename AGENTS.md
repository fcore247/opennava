# Set up OpenNava

The step-by-step instructions for AI agents are in [docs/agent/agent.md](docs/agent/agent.md)
(published at <https://open-nava.com/agent/>). Read them completely before you touch a device.

OpenNava is one TV launcher (`site.navatv.rustpreview`) and an optional Android phone app
(`site.navatv.companion`). From a checkout of this repository the installer does the transfer and
verifies the signed release:

```sh
python3 tools/setup.py --list
python3 tools/setup.py --tv TV_SERIAL [--phone PHONE_SERIAL] [--set-home]
```

`--set-home` only when the person asks for OpenNava as the default Home app. The TV then guides the
person through the first-time setup.

## Before modifying or publishing source

Keep personal device addresses, private home configuration, photos, logs, signing keys, local Android
preferences and environment files out of the repository. Use placeholder serials in public examples.
Build the TV app first (`rust-tv/tools/package_tv.py`), then the phone app, which carries it
(`android/build.py --tv-apk …`). `android/tv/` still holds Java sources the TV launcher compiles.
See the [maintainer guide](guide/MAINTAINING.md).
