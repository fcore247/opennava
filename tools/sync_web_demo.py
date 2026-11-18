#!/usr/bin/env python3
"""Stage the same launcher assets as the Android WebView, adding the browser device bridge."""
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
GOOGLE_OVERRIDES = ("index.html", "styles.css", "native.js", "tube.js")


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
    shutil.copytree(ASSETS / "web", full)
    for name in GOOGLE_OVERRIDES:
        shutil.copy2(ASSETS / "google" / name, full / name)
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
    for folder in (full, senior):
        page = folder / "index.html"
        html = page.read_text(encoding="utf-8")
        if folder == full:
            # Reuse cached assets only when they match this edition of the full launcher.
            def version_local_asset(match):
                attribute, name = match.groups()
                return f'{attribute}="{asset_url(name, folder / name)}"'
            html = re.sub(r'\b(src|href)="([\w.-]+\.(?:js|css))"', version_local_asset, html)
        charset = '<meta charset="utf-8">'
        if html.count(charset) != 1:
            raise ValueError(f"Missing UTF-8 declaration in {page}")
        page.write_text(html.replace(charset, charset + "\n  " + hook), encoding="utf-8")


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
        if args.check:
            if not same_tree(expected, DEST):
                raise SystemExit("Demo differs from the Android launcher. Run python3 tools/sync_web_demo.py.")
            print("Demo matches both Android launchers.")
        else:
            if DEST.exists():
                shutil.rmtree(DEST)
            shutil.copytree(expected, DEST)
            print("Staged both Android launchers under docs/demo.")


if __name__ == "__main__":
    main()
