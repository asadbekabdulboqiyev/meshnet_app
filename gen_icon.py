#!/usr/bin/env python3
"""MeshNet app icon generator — mesh network temali icon."""
from PIL import Image, ImageDraw, ImageFont
import math
import os

SIZES = {
    'mdpi': 48,
    'hdpi': 72,
    'xhdpi': 96,
    'xxhdpi': 144,
    'xxxhdpi': 192,
}
FOREGROUND_SIZE = 432  # adaptive icon standard
BASE = 'android/app/src/main/res'

BG_COLOR = (0, 105, 92)       # #00695C — app theme primary
NODE_COLOR = (255, 255, 255)   # white nodes
LINE_COLOR = (200, 230, 220)   # light green-white lines
GLOW_COLOR = (0, 200, 150)    # subtle glow


def draw_mesh_icon(draw, size, padding=0):
    """Draw mesh network icon: 4 nodes connected by lines."""
    s = size - padding * 2
    cx, cy = size / 2, size / 2

    # Node positions (diamond-ish mesh layout)
    nodes = [
        (cx, cy - s * 0.32),          # top
        (cx - s * 0.30, cy + s * 0.05),  # left
        (cx + s * 0.30, cy + s * 0.05),  # right
        (cx, cy + s * 0.32),          # bottom
        (cx, cy),                      # center
    ]

    line_w = max(2, int(s * 0.025))
    node_r = max(3, int(s * 0.07))

    # Draw connections (mesh lines)
    connections = [
        (0, 1), (0, 2), (0, 4),
        (1, 2), (1, 3), (1, 4),
        (2, 3), (2, 4),
        (3, 4),
    ]
    for i, j in connections:
        x1, y1 = nodes[i]
        x2, y2 = nodes[j]
        draw.line([(x1, y1), (x2, y2)], fill=LINE_COLOR, width=line_w)

    # Draw center glow
    glow_r = int(node_r * 2.5)
    for r in range(glow_r, 0, -1):
        alpha = int(40 * (1 - r / glow_r))
        glow_c = (*GLOW_COLOR[:3],)
        draw.ellipse(
            [cx - r, cy - r, cx + r, cy + r],
            fill=glow_c,
        )

    # Draw nodes (outer first, then center on top)
    for i, (x, y) in enumerate(nodes):
        r = node_r if i != 4 else int(node_r * 1.3)
        # shadow
        draw.ellipse([x - r - 1, y - r + 2, x + r - 1, y + r + 2],
                      fill=(0, 60, 50))
        # node
        draw.ellipse([x - r, y - r, x + r, y + r], fill=NODE_COLOR)


def generate_foreground():
    """Generate adaptive icon foreground (432x432 transparent + icon)."""
    img = Image.new('RGBA', (FOREGROUND_SIZE, FOREGROUND_SIZE), (0, 0, 0, 0))
    draw = ImageDraw.Draw(img)
    draw_mesh_icon(draw, FOREGROUND_SIZE, padding=0)
    return img


def generate_launcher(fg_img, size):
    """Generate launcher icon from foreground."""
    # Scale foreground to fit with padding (108/432 = 25% padding on each side)
    inner = int(size * 0.72)
    fg_scaled = fg_img.resize((inner, inner), Image.LANCZOS)

    canvas = Image.new('RGBA', (size, size), (*BG_COLOR, 255))
    offset = (size - inner) // 2
    canvas.paste(fg_scaled, (offset, offset), fg_scaled)
    return canvas.convert('RGB')


def generate_round(fg_img, size):
    """Generate round launcher icon."""
    inner = int(size * 0.72)
    fg_scaled = fg_img.resize((inner, inner), Image.LANCZOS)

    canvas = Image.new('RGBA', (size, size), (0, 0, 0, 0))
    offset = (size - inner) // 2
    canvas.paste(fg_scaled, (offset, offset), fg_scaled)

    # Apply circle mask
    mask = Image.new('L', (size, size), 0)
    mask_draw = ImageDraw.Draw(mask)
    mask_draw.ellipse([0, 0, size, size], fill=255)
    canvas.putalpha(mask)
    return canvas.convert('RGB')


def main():
    fg = generate_foreground()

    # Save foreground for adaptive icon reference
    fg_path = os.path.join(BASE, 'mipmap-xxxhdpi', 'ic_launcher_foreground.png')
    fg.resize((192, 192), Image.LANCZOS).save(fg_path)

    for density, size in SIZES.items():
        mipmap = os.path.join(BASE, f'mipmap-{density}')
        os.makedirs(mipmap, exist_ok=True)

        # Square launcher
        launcher = generate_launcher(fg, size)
        launcher.save(os.path.join(mipmap, 'ic_launcher.png'))

        # Round launcher
        round_icon = generate_round(fg, size)
        round_icon.save(os.path.join(mipmap, 'ic_launcher_round.png'))

        print(f'  {density}: {size}x{size} OK')

    print('Icon generation complete!')


if __name__ == '__main__':
    main()
