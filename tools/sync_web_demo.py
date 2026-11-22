#!/usr/bin/env python3
"""Stage shared Android assets and the senior launcher for the independently authored website."""
import argparse
import base64
import hashlib
import json
import re
import shutil
import tempfile
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
ASSETS = ROOT / "android/tv/src/main/assets"
DEST = ROOT / "docs/demo"
# The public full demo has its own search, navigation and home screen. Only these
# resources come from the Android launcher; docs/demo/index.html is also site-owned.
FULL_SHARED = ("assets", "licenses", "brand.svg", "tube.css", "styles.css")


def stage_channels(target):
    guide = ASSETS.parent / "java/site/navatv/launcher/LiveGuide.java"
    rows = re.findall(r'new Channel\("([a-z0-9-]+)", "([^"]+)", "([^"]*)", (true|false)\)', guide.read_text())
    if not rows or len({row[0] for row in rows}) != len(rows):
        raise ValueError("Cannot read the TV broadcaster lineup")
    channels = []
    for number, (station, name, crid, regional) in enumerate(rows, 1):
        provider = "arte" if station == "arte" else "ard" if crid else "zdf"
        source_id = (base64.urlsafe_b64encode(crid.encode()).decode().rstrip("=")
                     if crid else station + "-live-beitrag-100")
        channels.append(dict(id=station, name=name, number=number, provider=provider,
                             sourceId=source_id, regional=regional == "true"))
    (target / "channels.js").write_text("window.openNavaStations = " + json.dumps(channels, ensure_ascii=False, indent=2) + ";\n", encoding="utf-8")


def stage(target):
    full = target / "full"
    full.mkdir()
    for name in FULL_SHARED:
        source = ASSETS / ("google" if name == "styles.css" else "web") / name
        if source.is_dir():
            shutil.copytree(source, full / name)
        else:
            shutil.copy2(source, full / name)
    senior = target / "senior"
    senior.mkdir()
    for entry in ASSETS.iterdir():
        if entry.is_file():
            shutil.copy2(entry, senior / entry.name)
    shutil.copytree(ASSETS / "assets", senior / "assets", ignore=shutil.ignore_patterns("wetterort.json"))
    stage_channels(target)
    host = ROOT / "docs/demo-host"
    def asset_url(relative_url, source):
        digest = hashlib.sha256(source.read_bytes()).hexdigest()[:12]
        return f"{relative_url}?v={digest}"

    css = asset_url("../../demo-host/device.css", host / "device.css")
    scripts = [asset_url("../channels.js", target / "channels.js")]
    scripts.extend(asset_url("../../demo-host/" + name, host / name)
                   for name in ("live-streams.js", "live-tv.js", "bridge.js"))
    tags = [f'<link rel="stylesheet" href="{css}">', *(f'<script src="{url}"></script>' for url in scripts)]
    hook = "\n  ".join(tags)
    page = senior / "index.html"
    html = page.read_text(encoding="utf-8")
    charset = '<meta charset="utf-8">'
    if html.count(charset) != 1:
        raise ValueError(f"Missing UTF-8 declaration in {page}")
    page.write_text(html.replace(charset, charset + "\n  " + hook), encoding="utf-8")

    # The full demo authors its own HTML. Refresh its cache keys against both the
    # generated assets above and the site-owned JS and CSS, including the bridge.
    authored = DEST / "full/index.html"
    def refresh_asset(match):
        relative = match.group(2)
        generated = full / relative
        source = generated if generated.is_file() else authored.parent / relative
        return match.group(1) + asset_url(relative, source) + '"'

    html = re.sub(r'((?:src|href)=")([.\w/-]+)\?v=[a-f0-9]{12}"',
                  refresh_asset, authored.read_text(encoding="utf-8"))
    (full / "index.html").write_text(html, encoding="utf-8")


def same_tree(left, right):
    files = {p.relative_to(left) for p in left.rglob("*") if p.is_file()}
    if files != {p.relative_to(right) for p in right.rglob("*") if p.is_file()}:
        return False
    return all((left / f).read_bytes() == (right / f).read_bytes() for f in files)


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--check", action="store_true", help="Fail if the published demo has drifted")
    args = parser.parse_args()
    with tempfile.TemporaryDirectory() as temp:
        expected = Path(temp) / "demo"
        expected.mkdir()
        stage(expected)
        generated = [Path("channels.js"), Path("senior")]
        generated.extend(Path("full") / name for name in (*FULL_SHARED, "index.html"))
        for name in generated:
            source, dest = expected / name, DEST / name
            if source.is_dir():
                matches = dest.is_dir() and same_tree(source, dest)
            else:
                matches = dest.is_file() and source.read_bytes() == dest.read_bytes()
            if matches:
                continue
            if args.check:
                raise SystemExit(f"Demo asset {name} differs. Run python3 tools/sync_web_demo.py.")
            if source.is_dir():
                if dest.exists():
                    shutil.rmtree(dest)
                shutil.copytree(source, dest)
            else:
                shutil.copy2(source, dest)
        print("Shared launcher assets and browser references are up to date.")


if __name__ == "__main__":
    main()
