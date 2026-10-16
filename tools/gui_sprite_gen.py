#!/usr/bin/env python3
# Generates Hibiscus Teams' GUI sprites under src/main/resources/assets/hibiscusteams/textures/gui/sprites/.
# Style: Hibiscus Lib's default theme (flat fills, 1 px bevels: light top-left, dark bottom-right, no gradients or
# noise), in its colours (hibiscuslib's assets/hibiscuslib/hibiscus_theme/default.json).
# Usage: python tools/gui_sprite_gen.py
import os

from PIL import Image

OUT = os.path.join(os.path.dirname(__file__), "..", "src", "main", "resources", "assets", "hibiscusteams", "textures", "gui", "sprites")

SHADOW = "#18191C"
TEXT = "#E6E6E6"
TEXT_MUTED = "#9A9DA3"
TEXT_DISABLED = "#5C5F66"
ACCENT = "#F2557A"
ACCENT_HOVER = "#FF7898"
ACCENT_DARK = "#B23A5A"


def rgba(hex_color, alpha=255):
    h = hex_color.lstrip("#")
    return (int(h[0:2], 16), int(h[2:4], 16), int(h[4:6], 16), alpha)


def person(cx, head_y, head_r, body_top, half_width, corner):
    """The pixels of one person: a round head, and below it shoulders with rounded top corners down to the bottom."""
    pixels = set()
    for y in range(16):
        for x in range(16):
            px, py = x + 0.5, y + 0.5
            if (px - cx) ** 2 + (py - head_y) ** 2 <= head_r ** 2:
                pixels.add((x, y))
            elif py >= body_top and abs(px - cx) <= half_width:
                # Round the top corners off.
                ex = abs(px - cx) - (half_width - corner)
                ey = (body_top + corner) - py
                if ex <= 0 or ey <= 0 or ex * ex + ey * ey <= corner * corner:
                    pixels.add((x, y))
    return pixels


def paint(px, shape, fill, light, dark):
    """Flat fill with a 1 px light edge where the shape faces up or left and a dark edge where it faces down or right."""
    for (x, y) in shape:
        if (x, y - 1) not in shape or (x - 1, y) not in shape:
            px[x, y] = rgba(light)
        elif (x, y + 1) not in shape or (x + 1, y) not in shape:
            px[x, y] = rgba(dark)
        else:
            px[x, y] = rgba(fill)


def icon_team():
    """The Teams button: two people, one in front of the other."""
    img = Image.new("RGBA", (16, 16), (0, 0, 0, 0))
    px = img.load()
    back = {(x, y) for (x, y) in person(cx=5.0, head_y=4.0, head_r=2.6, body_top=7.6, half_width=4.0, corner=2.5) if y <= 12}
    front = person(cx=10.5, head_y=7.0, head_r=2.9, body_top=11.0, half_width=5.0, corner=3.0)
    paint(px, back, TEXT_MUTED, TEXT, TEXT_DISABLED)
    # A dark line round the front person keeps the two apart.
    for (x, y) in front:
        for dx, dy in ((-1, 0), (0, -1), (-1, -1)):
            n = (x + dx, y + dy)
            if n not in front and n in back:
                px[n[0], n[1]] = rgba(SHADOW)
    paint(px, front, ACCENT, ACCENT_HOVER, ACCENT_DARK)
    return img


def save(name, img):
    path = os.path.join(OUT, name + ".png")
    os.makedirs(os.path.dirname(path), exist_ok=True)
    img.save(path)
    print("wrote", os.path.relpath(path))


def main():
    save("button/teams", icon_team())


if __name__ == "__main__":
    main()
