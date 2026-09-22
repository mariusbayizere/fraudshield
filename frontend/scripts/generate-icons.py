"""Draws the console's app icons from the design tokens' brand colours (ADR 0080 §5).

Run from frontend/: python3 scripts/generate-icons.py. Needs Pillow. Writes public/icon.svg,
icon-192.png, icon-512.png, icon-maskable-512.png and apple-touch-icon.png.
"""

import json
from pathlib import Path

from PIL import Image, ImageDraw

ROOT = Path(__file__).resolve().parent.parent
TOKENS = json.loads((ROOT.parent / "design-tokens" / "tokens.json").read_text())
brand = TOKENS["color"]["light"]["brand"]
NAVY, WHITE = brand["navy"], brand["onBrand"]

# A shield on a unit square, as fractions: top edge, shoulders, point.
SHIELD = [(0.5, 0.16), (0.78, 0.26), (0.76, 0.52), (0.5, 0.84), (0.24, 0.52), (0.22, 0.26)]


def png(size: int, scale: float, name: str) -> None:
    image = Image.new("RGB", (size, size), NAVY)
    inset = (1 - scale) / 2
    points = [(size * (inset + x * scale), size * (inset + y * scale)) for x, y in SHIELD]
    ImageDraw.Draw(image).polygon(points, fill=WHITE)
    image.save(ROOT / "public" / name, optimize=True)


png(192, 1.0, "icon-192.png")
png(512, 1.0, "icon-512.png")
# Maskable icons keep their content inside the central 80% safe zone.
png(512, 0.7, "icon-maskable-512.png")
png(180, 1.0, "apple-touch-icon.png")
path = " ".join(f"{x * 100:.0f},{y * 100:.0f}" for x, y in SHIELD)
(ROOT / "public" / "icon.svg").write_text(
    f'<svg xmlns="http://www.w3.org/2000/svg" viewBox="0 0 100 100">'
    f'<rect width="100" height="100" fill="{NAVY}"/>'
    f'<polygon points="{path}" fill="{WHITE}"/></svg>\n'
)
