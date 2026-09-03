#!/usr/bin/env python3
"""Render every icon the project ships from art/icon-fg.svg.

Needs rsvg-convert and Pillow. Run from the repository root:

    python3 art/build-icons.py

Nothing here should be edited by hand afterwards — change the SVG and re-run.
"""
import subprocess, sys
from pathlib import Path
from PIL import Image, ImageDraw

ROOT = Path(__file__).resolve().parent.parent
FG = ROOT / "art/icon-fg.svg"
BG = "#0B0E14"          # keep in sync with @color/ic_launcher_background

# An adaptive icon's foreground is masked to the centre 72 of its 108dp canvas,
# so the artwork has to sit well inside it. 0.57 is what the icon shipped with
# before this script existed, measured off the old assets.
ADAPTIVE_FILL = 0.57
DENSITIES = {"mdpi": 1, "hdpi": 1.5, "xhdpi": 2, "xxhdpi": 3, "xxxhdpi": 4}


def artwork() -> Image.Image:
    """The SVG rendered large, cropped to its content."""
    out = ROOT / "art/.render.png"
    subprocess.run(["rsvg-convert", "-w", "2048", "-h", "2048", str(FG), "-o", str(out)], check=True)
    im = Image.open(out).convert("RGBA")
    art = im.crop(im.split()[3].getbbox())
    out.unlink()
    return art


def compose(art: Image.Image, size: int, fill: float, bg: str | None, shape: str = "square") -> Image.Image:
    scale = size * fill / max(art.size)
    a = art.resize((max(1, round(art.width * scale)), max(1, round(art.height * scale))), Image.LANCZOS)
    canvas = Image.new("RGBA", (size, size), bg if bg else (0, 0, 0, 0))
    if bg and shape == "circle":
        canvas = Image.new("RGBA", (size, size), (0, 0, 0, 0))
        disc = Image.new("RGBA", (size, size), bg)
        mask = Image.new("L", (size, size), 0)
        ImageDraw.Draw(mask).ellipse([0, 0, size - 1, size - 1], fill=255)
        canvas.paste(disc, (0, 0), mask)
    canvas.alpha_composite(a, ((size - a.width) // 2, (size - a.height) // 2))
    return canvas


def main() -> int:
    art = artwork()
    res = ROOT / "app/src/main/res"
    for name, mult in DENSITIES.items():
        d = res / f"mipmap-{name}"
        d.mkdir(parents=True, exist_ok=True)
        compose(art, round(108 * mult), ADAPTIVE_FILL, None).save(d / "ic_launcher_foreground.png", optimize=True)
        compose(art, round(48 * mult), 0.80, BG).save(d / "ic_launcher.png", optimize=True)
        compose(art, round(48 * mult), 0.66, BG, "circle").save(d / "ic_launcher_round.png", optimize=True)
        print(f"mipmap-{name}")

    # GitHub App avatar: GitHub masks it into a circle in some views, so leave room.
    compose(art, 1024, 0.84, BG).convert("RGB").save(ROOT / "art/github-app-avatar.png", optimize=True)
    for size, path in ((192, "site/assets/icon-192.png"), (180, "site/assets/apple-touch-icon.png"),
                       (32, "site/assets/favicon-32.png")):
        compose(art, size, 0.84, BG).convert("RGB").save(ROOT / path, optimize=True)
    print("avatar + site icons")
    return 0


if __name__ == "__main__":
    sys.exit(main())
