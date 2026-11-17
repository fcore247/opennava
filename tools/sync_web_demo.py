#!/usr/bin/env python3
"""Stage the same launcher assets as the Android WebView, adding the browser device bridge."""
import argparse
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
    rows = re.findall(r'new Channel\("([a-z0-9-]+)", "([^"]+)", "[^"]*", (true|false)\)', guide.read_text())
    if not rows or len({row[0] for row in rows}) != len(rows):
        raise ValueError("Cannot read the TV broadcaster lineup")
    channels = []
    for number, (station, name, regional) in enumerate(rows, 1):
        provider = "arte" if station == "arte" else "zdf" if station in {"zdf", "zdfneo", "zdfinfo", "3sat"} else "ard"
        channels.append(dict(id=station, name=name, number=number, provider=provider, regional=regional == "true"))
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
    shutil.copytree(ASSETS / "assets", senior / "assets")
    stage_channels(target)
    for folder in (full, senior):
        page = folder / "index.html"
        html = page.read_text(encoding="utf-8")
        hook = '<script src="../channels.js"></script>\n  <script src="../../demo-host/bridge.js"></script>'
        if html.count("<head>") != 1:
            raise ValueError(f"Missing launcher head in {page.name}")
        page.write_text(html.replace("<head>", "<head>\n  " + hook), encoding="utf-8")


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
