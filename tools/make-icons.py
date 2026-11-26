#!/usr/bin/env python3
"""Draws NaviLink's icon (a folded map with a pin on the app's blue) and writes every size the
installers and Android need: app/icons/navilink.{png,ico,icns} and Android mipmaps.
Run from the repository root: python3 tools/make-icons.py (needs Pillow)."""
import os
from PIL import Image, ImageDraw

BLUE = (0x1F, 0x5A, 0x80, 255)
WHITE = (255, 255, 255, 255)
LIGHT = (0xE1, 0xEC, 0xF4, 255)
ORANGE = (0xF2, 0x8C, 0x28, 255)
S = 1024


def draw(size=S, rounded=True, pad=0):
    k = 4  # drawn 4x larger, then scaled down for smooth edges
    n = size * k
    im = Image.new("RGBA", (n, n), (0, 0, 0, 0))
    d = ImageDraw.Draw(im)
    u = n / 1024
    p = pad * u
    if rounded:
        d.rounded_rectangle([p + 40 * u, p + 40 * u, n - p - 40 * u, n - p - 40 * u], radius=200 * u, fill=BLUE)
    else:
        d.rectangle([0, 0, n, n], fill=BLUE)

    def P(x, y):  # the 1024 design, shrunk into the padded area
        f = (1024 - 2 * pad) / 1024
        return (p + x * f * u, p + y * f * u)
    # The folded map: three panels, the middle one lighter.
    y0, y1, d1 = 300, 780, 50
    xs = [230, 420, 610, 800]
    panels = [(xs[0], y0 + d1, xs[1], y0, xs[1], y1 - d1, xs[0], y1, WHITE),
              (xs[1], y0, xs[2], y0 + d1, xs[2], y1, xs[1], y1 - d1, LIGHT),
              (xs[2], y0 + d1, xs[3], y0, xs[3], y1 - d1, xs[2], y1, WHITE)]
    for a, b, c, e, f_, g, h, i, col in panels:
        d.polygon([P(a, b), P(c, e), P(f_, g), P(h, i)], fill=col)
    # A route across it and the pin at its end.
    d.line([P(300, 690), P(420, 560), P(540, 620), P(650, 528)], fill=ORANGE, width=int(34 * u * (1024 - 2 * pad) / 1024), joint="curve")
    cx, cy, r = 650, 330, 95
    d.ellipse([P(cx - r, cy - r), P(cx + r, cy + r)], fill=ORANGE)
    d.polygon([P(cx - r * 0.86, cy + r * 0.5), P(cx + r * 0.86, cy + r * 0.5), P(cx, cy + r * 2.1)], fill=ORANGE)
    d.ellipse([P(cx - r * 0.42, cy - r * 0.42), P(cx + r * 0.42, cy + r * 0.42)], fill=WHITE)
    return im.resize((size, size), Image.LANCZOS)


def main():
    os.makedirs("app/icons", exist_ok=True)
    big = draw()
    big.save("app/icons/navilink.png")
    big.save("app/icons/navilink.ico", sizes=[(16, 16), (24, 24), (32, 32), (48, 48), (64, 64), (128, 128), (256, 256)])
    big.save("app/icons/navilink.icns")
    # The desktop window's own icon.
    os.makedirs("app/src/desktopMain/resources", exist_ok=True)
    draw(256).save("app/src/desktopMain/resources/navilink.png")
    # Android: square-ish launcher icons (the launcher masks them) at each density.
    for name, px in {"mdpi": 48, "hdpi": 72, "xhdpi": 96, "xxhdpi": 144, "xxxhdpi": 192}.items():
        d = f"app/src/androidMain/res/mipmap-{name}"
        os.makedirs(d, exist_ok=True)
        draw(px).save(f"{d}/ic_launcher.png")


if __name__ == "__main__":
    main()
