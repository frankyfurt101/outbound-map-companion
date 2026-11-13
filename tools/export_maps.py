#!/usr/bin/env python3
"""Export the four region map textures from your own copy of the game's Unity bundle.

Usage:
    python3 export_maps.py <default_assets_all_*.bundle> <out-dir>
    e.g. python3 export_maps.py default_assets_all_XXXX.bundle ../app/src/main/assets/maps

Reads the bundle with UnityPy and saves the Texture2D objects named map-pacific-coast,
map-starting-level, map-canyons and map-mountains as <name>.png into <out-dir>. The bundle comes from
your own legally obtained game (find it with tools/extract_romfs.py list ... bundle). These images are
game artwork: keep them for your own use and do not redistribute them.
Needs: pip install UnityPy
"""
import argparse
import os
import sys

NAMES = ("map-pacific-coast", "map-starting-level", "map-canyons", "map-mountains")


def main():
    ap = argparse.ArgumentParser(description=__doc__.split("\n\n")[0],
                                 epilog="Writes map-pacific-coast.png, map-starting-level.png, map-canyons.png, map-mountains.png.")
    ap.add_argument("bundle", help="path to your default_assets_all_*.bundle")
    ap.add_argument("out_dir", help="output folder (app/src/main/assets/maps)")
    ap.add_argument("--force", action="store_true", help="overwrite map images that already exist in <out-dir>")
    a = ap.parse_args()
    if not os.path.isfile(a.bundle):
        sys.exit("error: bundle not found: " + a.bundle)
    existing = [os.path.join(a.out_dir, n + ".png") for n in NAMES if os.path.exists(os.path.join(a.out_dir, n + ".png"))]
    if existing and not a.force:
        sys.exit("error: %s already exists (use --force to overwrite)" % existing[0])
    try:
        import UnityPy
    except ImportError:
        sys.exit("error: missing dependency 'UnityPy' (pip install -r tools/requirements.txt)")
    os.makedirs(a.out_dir, exist_ok=True)
    env = UnityPy.load(a.bundle)
    done = set()
    for o in env.objects:
        if o.type.name != "Texture2D":
            continue
        d = o.read()
        if d.m_Name in NAMES and d.m_Name not in done:
            path = os.path.join(a.out_dir, d.m_Name + ".png")
            d.image.save(path)
            done.add(d.m_Name)
            print("wrote", path)
    missing = [n for n in NAMES if n not in done]
    if missing:
        sys.exit("error: texture(s) not found in this bundle: " + ", ".join(missing) +
                 ". Try another default_assets_all_*.bundle from the game's files.")


if __name__ == "__main__":
    main()
