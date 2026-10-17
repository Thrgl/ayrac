"""Draws the Android launcher icons (same ribbon-on-paper mark as desktop/make_icon.py) into res/mipmap-*."""

import sys
from pathlib import Path

from PIL import Image, ImageDraw

res = Path(sys.argv[1])
S = 512
img = Image.new("RGBA", (S, S), (0, 0, 0, 0))
d = ImageDraw.Draw(img)
d.rounded_rectangle((24, 24, S - 24, S - 24), radius=96, fill=(250, 251, 248, 255), outline=(213, 218, 212, 255), width=8)
for y in (172, 244, 316, 388):
    d.rounded_rectangle((120, y, 300 if y == 388 else 392, y + 20), radius=10, fill=(205, 212, 206, 255))
x0, x1, bottom = 300, 412, 428
d.polygon([(x0, 0), (x1, 0), (x1, bottom), ((x0 + x1) // 2, bottom - 80), (x0, bottom)], fill=(44, 93, 76, 255))
for name, size in {"mdpi": 48, "hdpi": 72, "xhdpi": 96, "xxhdpi": 144, "xxxhdpi": 192}.items():
    out = res / f"mipmap-{name}"
    out.mkdir(parents=True, exist_ok=True)
    img.resize((size, size), Image.LANCZOS).save(out / "ic_launcher.png")
print("icons:", res)
