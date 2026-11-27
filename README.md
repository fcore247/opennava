# OpenNava

A TV home screen for Android TV, Google TV and Android-based Fire TV, with a phone app that follows what
is on the television.

**[Try it in your browser](https://open-nava.com/)** · **[Download the TV app](https://github.com/fcore247/opennava/releases/latest/download/OpenNava-Rust-TV.apk)** · **[Download the phone app](https://github.com/fcore247/opennava/releases/latest/download/OpenNava-Companion.apk)** · **[Set it up with your AI agent](https://open-nava.com/agent/)**

## What is in it

- **Native TV home:** live TV, media libraries, YouTube (NavaTube), your TV's apps, your own photos as background
- **Guided setup:** a QR code on the TV gets you the phone app, a second one pairs it. Language and country
  decide which channels and libraries appear (Germany, Austria, Switzerland, United States).
- **Phone app:** an editable start page, remote, channel list, libraries, NavaTube, a mini player for what
  the TV is playing, and every app of your TV
- **Your own libraries and streams,** also added by your AI agent: [library format](guide/MEDIATHEK_FORMAT.md)
- **Reset** under Settings → System

The TV screen and parts of the phone app are available in German and English; some pages are still mixed.

## Upgrading from 1.1.x

Version 1.2 has **one** TV launcher. The classic and senior views (`OpenNava.apk`) are no longer part of
OpenNava. A TV that has only the old app keeps working with its 1.1.4 version but gets no further updates.
The updated phone app needs the new launcher: install it from the phone app's connection page or with the
download above. **Settings, recordings and reminders of the old app are not moved over.**

## Requirements

Android TV, Google TV or Android-based Fire TV with Android 8 or newer; the phone app needs Android 11 or
newer. The phone app controls the TV over Android's debugging connection, which you turn on and authorise
yourself. Fire TV devices that run Vega OS use a different app platform.

## Install from a computer

```sh
git clone https://github.com/fcore247/opennava.git
cd opennava
python3 tools/setup.py --list
python3 tools/setup.py --tv TV_SERIAL --phone PHONE_SERIAL
```

Pairing and connecting with `adb` is described in the [agent instructions](docs/agent/agent.md).

## More

[Architecture](guide/ARCHITECTURE.md) · [Maintainer guide](guide/MAINTAINING.md) · [Privacy and licences](android/NOTICE.md).
Some guides still describe version 1.1 and are being updated.
