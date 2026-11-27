# Your own media libraries

OpenNava shows libraries you add next to ARD, ZDF and ARTE: on the Rust TV launcher and in the Android companion, in the same layout. Only add content you are allowed to watch.

## Two ways to describe a library

### 1. A video feed (RSS, MRSS or Atom)

Give the feed address. Each entry needs a title and a video in `<enclosure type="video/…">` or `<media:content medium="video">`. `<media:thumbnail>`, `<itunes:image>`, `<description>` and `<itunes:duration>` are used when present. Audio-only podcasts are not listed.

### 2. An OpenNava library document

Use this when a provider has no video feed. A script or an AI agent can generate it.

```json
{
  "format": "opennava.mediathek/1",
  "name": "My library",
  "sections": [
    {
      "title": "New this week",
      "items": [
        {
          "title": "Evening news",
          "url": "https://example.org/news/master.m3u8",
          "image": "https://example.org/news.jpg",
          "subtitle": "News",
          "description": "The day in 15 minutes.",
          "duration": 900,
          "channel": "daserste",
          "start": "2026-11-24T20:00:00+01:00"
        }
      ]
    }
  ]
}
```

| Field | Meaning |
| --- | --- |
| `title`, `url` | Required. `url` is a playable video address: HLS (`.m3u8`) or a video file. Web pages do not play. |
| `image` | Cover, 16:9, HTTPS. |
| `subtitle`, `description`, `duration` | Shown in lists and details. `duration` is in seconds. |
| `channel`, `start` | Optional pair for catch-up. `channel` is a live channel ID and `start` the broadcast time with offset. The companion then lists the item under **Earlier shows** while you watch that channel. |

Limits: 20 libraries, 400 videos each. A document sent directly to the TV may have 60,000 bytes; publish larger ones at a web address. A library with an address is read again when you open it, so a scheduled script can keep it current.

Addresses that expire, need a login or use DRM stop working or do not play.

## Let your AI agent add a library

`tools/opennava_mcp.py` is a Model Context Protocol server (Python 3.8 or newer, no packages). It checks a library and sends it to your TV over ADB. The computer needs `adb` and an authorized connection to the TV, and the TV needs the Rust launcher.

```sh
claude mcp add opennava -- python3 /path/to/opennava/tools/opennava_mcp.py
```

Other MCP clients use the same command as a stdio server. With several devices connected, set `OPENNAVA_TV` to the TV's ADB serial.

Then ask, for example: *"Add the video feed of my local museum to OpenNava"* or *"Build an OpenNava library from these videos and link last night's recordings to ZDF."*

| Tool | Purpose |
| --- | --- |
| `library_format` | This description |
| `check_library` | Validate an address or document without touching the TV |
| `list_libraries`, `add_library`, `remove_library` | Manage the libraries on the TV |
| `list_channels` | Channel IDs for catch-up |
| `list_streams`, `add_stream`, `remove_stream` | Manage your own live channels |
