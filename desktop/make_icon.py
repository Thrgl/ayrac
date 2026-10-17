"""Draws the app icon (a green bookmark ribbon on a paper tile) and saves it as a multi-size .ico."""

import sys

from PIL import Image, ImageDraw

out = sys.argv[1] if len(sys.argv) > 1 else "ayrac.ico"
S = 256
img = Image.new("RGBA", (S, S), (0, 0, 0, 0))
d = ImageDraw.Draw(img)
d.rounded_rectangle((12, 12, S - 12, S - 12), radius=48, fill=(250, 251, 248, 255), outline=(213, 218, 212, 255), width=4)
# page lines
for y in (86, 122, 158, 194):
    d.rounded_rectangle((60, y, 150 if y == 194 else 196, y + 10), radius=5, fill=(205, 212, 206, 255))
# ribbon
x0, x1, top, bottom = 150, 206, 0, 214
d.polygon([(x0, top), (x1, top), (x1, bottom), ((x0 + x1) // 2, bottom - 40), (x0, bottom)], fill=(44, 93, 76, 255))
img.save(out, sizes=[(16, 16), (24, 24), (32, 32), (48, 48), (64, 64), (128, 128), (256, 256)])
print("icon:", out)
